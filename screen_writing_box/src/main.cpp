#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <TFT_eSPI.h>
#include "config.h"
#include "be_vietnam_pro_20.h"
#include "be_vietnam_pro_32.h"

#if TFT_RST == TFT_CS && TFT_RST >= 0
#error "TFT_CS and TFT_RST share a GPIO; confirm and correct the display wiring"
#endif

namespace {
TFT_eSPI display;
TFT_eSprite lineSprite(&display);
unsigned long lastPoll = 0;
unsigned long lastWifiAttempt = 0;
unsigned long lastSuccess = 0;
bool hasSuccess = false;
bool offlineShown = false;
bool offlineWifiConnected = false;
String offlineReason;
int lastWifiStatus = -1;
String revision;
constexpr unsigned long retryMs = 500;
constexpr unsigned long offlineMs = 5000;
constexpr unsigned long wifiRetryMs = 10000;

struct DisplayLine {
    String text;
    uint16_t color = TFT_WHITE;
    bool drawn = false;
};

DisplayLine lines[8];
bool spriteReady = false;
bool largeFontLoaded = false;

void useFont(bool large) {
    if (largeFontLoaded == large) return;
    lineSprite.loadFont(large ? beVietnamPro32 : beVietnamPro20);
    largeFontLoaded = large;
}

void updateBadge(unsigned int index, int x, const char* label, uint16_t color, bool wifi = false) {
    DisplayLine& line = lines[index];
    if (line.drawn && line.color == color) return;
    if (spriteReady) {
        useFont(false);
        lineSprite.fillSprite(TFT_BLACK);
        if (wifi) {
            lineSprite.drawLine(8, 15, 14, 10, color);
            lineSprite.drawLine(14, 10, 22, 8, color);
            lineSprite.drawLine(22, 8, 30, 10, color);
            lineSprite.drawLine(30, 10, 36, 15, color);
            lineSprite.drawLine(13, 20, 18, 16, color);
            lineSprite.drawLine(18, 16, 26, 16, color);
            lineSprite.drawLine(26, 16, 31, 20, color);
            lineSprite.fillCircle(22, 25, 3, color);
        } else {
            lineSprite.fillCircle(17, 20, 6, color);
        }
        lineSprite.setTextColor(TFT_WHITE, TFT_BLACK);
        lineSprite.setCursor(wifi ? 43 : 31, 6);
        lineSprite.printToSprite(String(label));
        lineSprite.pushSprite(x, 4, 0, 0, 160, 40);
    } else {
        display.fillRect(x, 4, 160, 40, TFT_BLACK);
        display.fillCircle(x + 17, 24, 6, color);
        display.setTextColor(TFT_WHITE, TFT_BLACK);
        display.setCursor(x + 31, 10);
        display.print(label);
    }
    line.color = color;
    line.drawn = true;
}

void updateLine(unsigned int index, int y, int height, const String& text,
                uint16_t color = TFT_WHITE, bool large = false) {
    DisplayLine& line = lines[index];
    if (line.drawn && line.text == text && line.color == color) return;
    if (spriteReady) {
        useFont(large);
        // Keep long barcodes visible by falling back to the smaller font.
        if (large && lineSprite.textWidth(text) > display.width() - 16) useFont(false);
        lineSprite.fillSprite(TFT_BLACK);
        lineSprite.setTextColor(color, TFT_BLACK);
        int x = max(8, (display.width() - lineSprite.textWidth(text)) / 2);
        lineSprite.setCursor(x, 0);
        lineSprite.printToSprite(text);
        lineSprite.pushSprite(0, y, 0, 0, display.width(), height);
    } else {
        display.fillRect(0, y, display.width(), height, TFT_BLACK);
        display.setTextColor(color, TFT_BLACK);
        display.setCursor(max(8, (display.width() - display.textWidth(text)) / 2), y);
        display.print(text);
    }
    line.text = text;
    line.color = color;
    line.drawn = true;
}

uint16_t deviceColor(const char* status) {
    if (strcmp(status, "READY") == 0 || strcmp(status, "CONNECTED") == 0) return TFT_GREEN;
    if (strcmp(status, "UNKNOWN") == 0) return TFT_YELLOW;
    return TFT_RED;
}

const char* stateMessage(const char* state) {
    if (strcmp(state, "WAIT_TRIGGER") == 0) return "Chờ nhấn nút";
    if (strcmp(state, "WRITING") == 0) return "Đang ghi";
    if (strcmp(state, "PASS") == 0) return "Thành công";
    if (strcmp(state, "FAIL") == 0) return "Thất bại";
    if (strcmp(state, "IDLE") == 0) return "Sẵn sàng";
    return state;
}

void showOffline(const char* reason) {
    offlineWifiConnected = WiFi.status() == WL_CONNECTED;
    updateBadge(0, 0, "WiFi", offlineWifiConnected ? TFT_GREEN : TFT_RED, true);
    updateBadge(1, 160, "Reader", TFT_YELLOW);
    updateBadge(2, 320, "Scanner", TFT_YELLOW);
    updateLine(3, 65, 32, "");
    updateLine(4, 108, 55, "Đang chờ mã", TFT_WHITE, true);
    updateLine(5, 180, 32, "");
    updateLine(6, 220, 32, "");
    updateLine(7, 265, 32, "");
    offlineShown = true;
    offlineReason = reason;
}

void showStatus(const JsonDocument& data) {
    const char* state = data["state"] | "UNKNOWN";
    const char* scanner = data["scanner"] | "UNKNOWN";
    const char* reader = data["reader"] | "UNKNOWN";
    const char* barcode = data["barcode"] | "";
    const char* epc = data["epc"] | "-";
    const char* error = data["error"] | "";
    updateBadge(0, 0, "WiFi", TFT_GREEN, true);
    updateBadge(1, 160, "Reader", deviceColor(reader));
    updateBadge(2, 320, "Scanner", deviceColor(scanner));
    updateLine(3, 65, 32, "");
    updateLine(4, 108, 55, *barcode ? barcode : "Đang chờ mã", TFT_WHITE, true);
    updateLine(5, 180, 32, stateMessage(state), strcmp(state, "PASS") == 0 ? TFT_GREEN :
               strcmp(state, "FAIL") == 0 ? TFT_RED : TFT_CYAN);
    updateLine(6, 220, 32, error);
    updateLine(7, 265, 32, *barcode && *epc ? String("EPC: ") + epc : "", TFT_LIGHTGREY);
    offlineShown = false;
}
}

void setup() {
    Serial.begin(115200);
    display.init();
    display.setRotation(1);
    display.fillScreen(TFT_BLACK);
    display.setTextWrap(false);
    display.loadFont(beVietnamPro20);
    lineSprite.setColorDepth(16);
    spriteReady = lineSprite.createSprite(display.width(), 55) != nullptr;
    if (spriteReady) {
        lineSprite.setTextWrap(false);
        lineSprite.loadFont(beVietnamPro20);
    }
    showOffline("Connecting");
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    lastWifiAttempt = millis();
}

void loop() {
    unsigned long now = millis();
    wl_status_t wifiStatus = WiFi.status();
    if (static_cast<int>(wifiStatus) != lastWifiStatus) {
        lastWifiStatus = static_cast<int>(wifiStatus);
        Serial.printf("[DEBUG-wifi] status=%d\n", lastWifiStatus);
        if (wifiStatus == WL_CONNECTED) Serial.printf("[DEBUG-wifi] ip=%s\n", WiFi.localIP().toString().c_str());
    }
    if (wifiStatus != WL_CONNECTED) {
        if (!offlineShown || offlineWifiConnected || offlineReason != "WiFi disconnected") showOffline("WiFi disconnected");
        hasSuccess = false;
        revision = "";
        if (now - lastWifiAttempt >= wifiRetryMs) {
            lastWifiAttempt = now;
            WiFi.disconnect();
            WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
        }
        delay(50);
        return;
    }
    if (offlineShown && !offlineWifiConnected) showOffline("Waiting for reader");
    if (now - lastPoll >= retryMs) {
        HTTPClient http;
        http.setConnectTimeout(1000);
        http.setTimeout(3500);
        String endpoint = STATION_STATUS_URL;
        bool validUrl = endpoint.endsWith("/status");
        if (validUrl) endpoint += "/wait?since=" + revision;
        bool success = false;
        if (validUrl && http.begin(endpoint)) {
            int code = http.GET();
            if (code == HTTP_CODE_OK && http.getSize() >= 0 && http.getSize() <= 1024) {
                String payload = http.getString();
                StaticJsonDocument<1024> doc;
                if (!deserializeJson(doc, payload) && doc["revision"].is<const char*>() && doc["state"].is<const char*>() &&
                    doc["reader"].is<const char*>() && doc["scanner"].is<const char*>()) {
                    const char* receivedRevision = doc["revision"];
                    String nextRevision(receivedRevision);
                    if (nextRevision.length() > 0 && nextRevision.length() <= 64) {
                        if (offlineShown || nextRevision != revision) showStatus(doc);
                        revision = nextRevision;
                        lastSuccess = millis();
                        hasSuccess = true;
                        success = true;
                    }
                }
            }
            http.end();
        }
        // Successful long-polls re-open immediately; failures retry with a delay.
        lastPoll = success ? millis() - retryMs : millis();
    }
    now = millis();
    if ((!hasSuccess || now - lastSuccess >= offlineMs) && !offlineShown) {
        revision = "";
        showOffline("No station status");
    }
    delay(5);
}
