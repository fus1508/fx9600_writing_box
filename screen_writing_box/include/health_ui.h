#pragma once
#include <stdint.h>
#include <string.h>

// Shared by the firmware and the executable host checks; no Arduino dependencies.
namespace healthui {
enum class Health { CONNECTED, READY, DISCONNECTED, UNKNOWN, RECOVERING };
enum class View { WAITING_FOR_STARTUP, STATION_UNREACHABLE, READER_OFFLINE, SCANNER_OFFLINE,
                  CONNECTED, READY, UNKNOWN, RECOVERING, WRITING, PASS, FAIL, RECOVERY_REQUIRED };
constexpr uint32_t freshnessMs = 1000;
constexpr uint32_t offlineMs = 2500;
struct Snapshot {
    char bootId[37], revision[65], transactionId[65], workflow[24];
    char barcode[65], epc[125], error[241], safetyReason[161];
    char readerReason[81], scannerReason[81], applicationReason[81];
    uint64_t sequence;
    Health reader, scanner, application;
    int64_t readerAge, scannerAge, applicationAge;
    bool recovery, safeToTrigger;
};
inline const char* healthName(Health state) {
    switch (state) {
        case Health::CONNECTED: return "CONNECTED";
        case Health::READY: return "READY";
        case Health::DISCONNECTED: return "DISCONNECTED";
        case Health::RECOVERING: return "RECOVERING";
        default: return "UNKNOWN";
    }
}
inline const char* viewName(View state) {
    switch (state) {
        case View::WAITING_FOR_STARTUP: return "WAITING FOR STARTUP";
        case View::STATION_UNREACHABLE: return "STATION UNREACHABLE";
        case View::READER_OFFLINE: return "READER OFFLINE";
        case View::SCANNER_OFFLINE: return "SCANNER OFFLINE";
        case View::CONNECTED: return "CONNECTED";
        case View::READY: return "READY";
        case View::RECOVERING: return "RECOVERING";
        case View::WRITING: return "WRITING";
        case View::PASS: return "PASS";
        case View::FAIL: return "FAIL";
        case View::RECOVERY_REQUIRED: return "RECOVERY REQUIRED";
        default: return "UNKNOWN";
    }
}
inline bool expired(int64_t age, uint32_t elapsed) {
    return age < 0 || age >= freshnessMs || elapsed >= freshnessMs || age + elapsed >= freshnessMs;
}
class Controller {
    Snapshot sample = {};
    uint32_t receivedAt = 0, transportMs = 0;
    uint32_t lastLinkLossAt = 0;
    bool hasSample = false, uncertainWrite = false, requireFreshAfterLinkLoss = false, sampleExpired = false;
    char uncertainBoot[37] = {}, uncertainTransaction[65] = {};
    void markUncertain() {
        if (uncertainWrite) return;
        uncertainWrite = true;
        strcpy(uncertainBoot, sample.bootId); strcpy(uncertainTransaction, sample.transactionId);
    }
public:
    bool accept(const Snapshot& next, uint32_t requestStarted, uint32_t now) {
        uint32_t duration = now - requestStarted;
        if (duration >= freshnessMs || !next.bootId[0] || !next.sequence) return false;
        if (requireFreshAfterLinkLoss && now - lastLinkLossAt <= duration) return false;
        bool sameBoot = hasSample && strcmp(next.bootId, sample.bootId) == 0;
        if (sameBoot && next.sequence <= sample.sequence) return false;
        if (hasSample && !sameBoot && strcmp(sample.workflow, "WRITING") == 0) markUncertain();
        if (uncertainWrite && strcmp(next.bootId, uncertainBoot) == 0 && next.transactionId[0] &&
            uncertainTransaction[0] && strcmp(next.transactionId, uncertainTransaction) == 0 &&
            (strcmp(next.workflow, "PASS") == 0 || strcmp(next.workflow, "FAIL") == 0) && !next.recovery)
            uncertainWrite = false;
        sample = next; receivedAt = now; transportMs = duration; hasSample = true; requireFreshAfterLinkLoss = false; sampleExpired = false;
        return true;
    }
    bool reachable(bool wifi, uint32_t now) const { return wifi && !requireFreshAfterLinkLoss && !sampleExpired && hasSample && now - receivedAt < offlineMs; }
    Health effective(Health value, int64_t age, uint32_t now) const {
        if (value == Health::DISCONNECTED) return value;
        uint32_t elapsed = now - receivedAt;
        if (elapsed >= freshnessMs || transportMs >= freshnessMs - elapsed || expired(age, elapsed + transportMs))
            return Health::UNKNOWN;
        return value;
    }
    View view(bool wifi, uint32_t now) {
        if (!wifi) { lastLinkLossAt = now; requireFreshAfterLinkLoss = true; }
        if (hasSample && now - receivedAt >= offlineMs) sampleExpired = true;
        bool live = reachable(wifi, now);
        Health app = effective(sample.application, sample.applicationAge, now);
        Health reader = effective(sample.reader, sample.readerAge, now);
        if (hasSample && strcmp(sample.workflow, "WRITING") == 0 && (!live || app == Health::UNKNOWN ||
            app == Health::DISCONNECTED || reader == Health::UNKNOWN || reader == Health::DISCONNECTED)) markUncertain();
        if (sample.recovery || uncertainWrite) return View::RECOVERY_REQUIRED;
        if (!live) return hasSample || !wifi || now >= offlineMs ? View::STATION_UNREACHABLE : View::WAITING_FOR_STARTUP;
        if (app == Health::UNKNOWN) return View::UNKNOWN;
        if (app == Health::DISCONNECTED) return View::STATION_UNREACHABLE;
        Health scanner = effective(sample.scanner, sample.scannerAge, now);
        if (reader == Health::DISCONNECTED) return View::READER_OFFLINE;
        if (scanner == Health::DISCONNECTED) return View::SCANNER_OFFLINE;
        if (reader == Health::RECOVERING || scanner == Health::RECOVERING || app == Health::RECOVERING) return View::RECOVERING;
        if (strcmp(sample.workflow, "WRITING") == 0) return View::WRITING;
        if (strcmp(sample.workflow, "PASS") == 0) return View::PASS;
        if (strcmp(sample.workflow, "FAIL") == 0) return View::FAIL;
        if (reader == Health::UNKNOWN || scanner == Health::UNKNOWN) return View::UNKNOWN;
        if (reader == Health::READY && scanner == Health::READY && app == Health::READY && sample.safeToTrigger) return View::READY;
        return View::CONNECTED;
    }
    const Snapshot& current() const { return sample; }
    bool seen() const { return hasSample; }
};
}
