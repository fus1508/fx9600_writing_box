#include <Arduino.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>
#include <TFT_eSPI.h>
#include "config.h"
#include "be_vietnam_pro_20.h"
#include "be_vietnam_pro_32.h"
#include "health_ui.h"
#include "status_parser.h"
#include <freertos/FreeRTOS.h>
#include <freertos/queue.h>
#include <freertos/task.h>

#if TFT_RST == TFT_CS && TFT_RST >= 0
#error "TFT_CS and TFT_RST share a GPIO; confirm and correct the display wiring"
#endif

namespace {
TFT_eSPI display;
TFT_eSprite lineSprite(&display);
unsigned long lastWifiAttempt = 0;
constexpr unsigned long wifiRetryMs = 10000;
healthui::Controller controller;
struct NetworkSample { healthui::Snapshot snapshot; uint32_t startedAt, completedAt; };
QueueHandle_t samples;

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
    if (line.drawn && line.color == color && line.text == label) return;
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
    line.text = label;
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
    if (strcmp(status, "READY") == 0) return TFT_GREEN;
    if (strcmp(status, "CONNECTED") == 0 || strcmp(status, "UNKNOWN") == 0 || strcmp(status, "RECOVERING") == 0) return TFT_YELLOW;
    return TFT_RED;
}

// This task never touches TFT or the UI controller. One fixed-size latest-snapshot mailbox.
void networkTask(void*) {
    String revision;
    for (;;) {
        if (WiFi.status() != WL_CONNECTED) { revision = ""; vTaskDelay(pdMS_TO_TICKS(100)); continue; }
        HTTPClient http;
        http.setConnectTimeout(700); http.setTimeout(900); http.setReuse(false);
        String url = STATION_STATUS_URL;
        bool ok = false;
        NetworkSample message = {};
        message.startedAt = millis();
        if (url.endsWith("/status") && http.begin(url + "/wait?since=" + revision)) {
            if (http.GET() == HTTP_CODE_OK && http.getSize() > 0 && http.getSize() <= 8192) {
                String payload = http.getString();
                StaticJsonDocument<6144> doc;
                if (payload.length() <= 8192 && !deserializeJson(doc, payload) && healthui::parseSnapshot(doc, message.snapshot)) {
                    message.completedAt = millis();
                    revision = message.snapshot.revision;
                    xQueueOverwrite(samples, &message); ok = true;
                }
            }
            http.end();
        }
        if (!ok) revision = "";
        vTaskDelay(pdMS_TO_TICKS(ok ? 5 : 200));
    }
}

void showCurrent(bool wifi, uint32_t now) {
    using namespace healthui;
    View view = controller.view(wifi, now);
    const Snapshot& s = controller.current();
    bool live = controller.reachable(wifi, now);
    Health r = live ? controller.effective(s.reader, s.readerAge, now) : Health::UNKNOWN;
    Health c = live ? controller.effective(s.scanner, s.scannerAge, now) : Health::UNKNOWN;
    updateBadge(0, 0, "WiFi", wifi ? TFT_GREEN : TFT_RED, true);
    updateBadge(1, 160, "Reader", deviceColor(healthName(r)));
    updateBadge(2, 320, "Scanner", deviceColor(healthName(c)));
    updateLine(3, 52, 44, String("R: ") + healthName(r) + " / S: " + healthName(c), TFT_LIGHTGREY);
    uint16_t color = view == View::PASS || view == View::READY ? TFT_GREEN :
        view == View::RECOVERY_REQUIRED || view == View::FAIL || view == View::STATION_UNREACHABLE ||
        view == View::READER_OFFLINE || view == View::SCANNER_OFFLINE ? TFT_RED : TFT_YELLOW;
    updateLine(4, 105, 55, viewName(view), color, true);
    String detail;
    if (view == View::RECOVERY_REQUIRED) detail = "Cần kiểm tra tag / kết quả chưa rõ";
    else if (!live) detail = wifi ? "Không nhận được trạng thái trạm" : "Mất kết nối Wi-Fi";
    else if (view == View::UNKNOWN) detail = "Trạng thái cũ / thiếu bằng chứng";
    else if (view == View::CONNECTED) detail = "Đã kết nối, chưa đủ điều kiện READY";
    else if (view == View::READY) detail = "Chờ nhấn nút";
    else detail = s.error[0] ? s.error : s.safetyReason;
    updateLine(5, 172, 44, detail);
    updateLine(6, 222, 38, live ? s.barcode : "", TFT_WHITE);
    updateLine(7, 267, 44, live ? String("App: ") + healthName(controller.effective(s.application, s.applicationAge, now)) +
        (s.transactionId[0] ? String(" / ") + String(s.transactionId).substring(0, 8) : "") : "App: UNKNOWN", TFT_LIGHTGREY);
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
    samples = xQueueCreate(1, sizeof(NetworkSample));
    showCurrent(true, 0);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    lastWifiAttempt = millis();
    if (samples != nullptr) xTaskCreate(networkTask, "station-http", 16384, nullptr, 1, nullptr);
}

void loop() {
    unsigned long now = millis();
    wl_status_t wifiStatus = WiFi.status();
    if (wifiStatus != WL_CONNECTED) {
        if (now - lastWifiAttempt >= wifiRetryMs) {
            lastWifiAttempt = now;
            WiFi.disconnect();
            WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
        }
    }
    NetworkSample message;
    if (samples != nullptr && xQueueReceive(samples, &message, 0) == pdTRUE) {
        // Include mailbox delay in the age; a suspended render task cannot replay READY.
        controller.accept(message.snapshot, message.startedAt, millis());
    }
    showCurrent(wifiStatus == WL_CONNECTED, millis());
    delay(20);
}
