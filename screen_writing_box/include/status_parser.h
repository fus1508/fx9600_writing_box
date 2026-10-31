#pragma once
#include <ArduinoJson.h>
#include <initializer_list>
#include "health_ui.h"
namespace healthui {
bool parseHealth(const char* text, healthui::Health& value) {
    for (auto h : {healthui::Health::CONNECTED, healthui::Health::READY, healthui::Health::DISCONNECTED,
                   healthui::Health::UNKNOWN, healthui::Health::RECOVERING}) {
        if (strcmp(text, healthui::healthName(h)) == 0) { value = h; return true; }
    }
    return false;
}
template <size_t N> bool copyField(JsonVariantConst value, char (&destination)[N]) {
    if (!value.is<const char*>()) return false;
    const char* text = value.as<const char*>();
    if (strlen(text) >= N) return false;
    strcpy(destination, text); return true;
}
bool parseDevice(JsonVariantConst d, healthui::Health& state, int64_t& age, char (&reason)[81]) {
    if (!d["healthState"].is<const char*>() || !parseHealth(d["healthState"], state) ||
        !d["observedAgeMs"].is<int64_t>() || !d["lastProgressAgeMs"].is<int64_t>() ||
        !d["safeToOperate"].is<bool>() || !d["connection"].is<const char*>() ||
        !d["readiness"].is<const char*>() || !d["evidence"].is<const char*>() || !copyField(d["reason"], reason)) return false;
    age = d["observedAgeMs"].as<int64_t>();
    if (age < -1) return false;
    if (state == healthui::Health::READY) {
        if (strcmp(d["connection"], "CONNECTED") || strcmp(d["readiness"], "READY")) return false;
        int64_t progress = d["lastProgressAgeMs"].as<int64_t>();
        if (progress < 0 || progress >= healthui::freshnessMs) return false;
        age = age > progress ? age : progress;
    }
    return true;
}
bool parseSnapshot(const JsonDocument& d, healthui::Snapshot& s) {
    if (!d["schemaVersion"].is<int>() || d["schemaVersion"].as<int>() != 2 ||
        !d["freshnessLimitMs"].is<int>() || d["freshnessLimitMs"].as<int>() != healthui::freshnessMs ||
        !d["sampleSequence"].is<uint64_t>() || !d["recoveryRequired"].is<bool>() || !d["safeToTrigger"].is<bool>() ||
        !copyField(d["bootId"], s.bootId) || !copyField(d["revision"], s.revision) ||
        !copyField(d["transactionId"], s.transactionId) || !copyField(d["state"], s.workflow) ||
        !copyField(d["barcode"], s.barcode) || !copyField(d["epc"], s.epc) ||
        !copyField(d["error"], s.error) || !copyField(d["safetyReason"], s.safetyReason) ||
        !parseDevice(d["health"]["reader"], s.reader, s.readerAge, s.readerReason) ||
        !parseDevice(d["health"]["scanner"], s.scanner, s.scannerAge, s.scannerReason) ||
        !parseDevice(d["health"]["application"], s.application, s.applicationAge, s.applicationReason)) return false;
    if (strcmp(s.workflow, "IDLE") && strcmp(s.workflow, "WAIT_TRIGGER") && strcmp(s.workflow, "WRITING") &&
        strcmp(s.workflow, "PASS") && strcmp(s.workflow, "FAIL")) return false;
    s.sequence = d["sampleSequence"].as<uint64_t>(); s.recovery = d["recoveryRequired"].as<bool>();
    s.safeToTrigger = d["safeToTrigger"].as<bool>();
    if (s.safeToTrigger && (s.recovery || strcmp(s.workflow, "WAIT_TRIGGER") || s.reader != healthui::Health::READY ||
        s.scanner != healthui::Health::READY || s.application != healthui::Health::READY)) return false;
    if (strcmp(s.workflow, "PASS") == 0 && (!s.transactionId[0] || s.recovery)) return false;
    return s.revision[0] && s.bootId[0] && s.sequence;
}

}
