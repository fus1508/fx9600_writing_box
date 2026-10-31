#include "status_parser.h"
#include <assert.h>
#include <stdio.h>
#include <string>
#include <fstream>
#include <sstream>

int main(int argc, char** argv) {
    assert(argc == 2);
    std::ifstream input(argv[1]); assert(input.good());
    std::ostringstream buffer; buffer << input.rdbuf(); std::string json = buffer.str();
    StaticJsonDocument<6144> doc;
    assert(!deserializeJson(doc, json));
    healthui::Snapshot sample = {};
    assert(healthui::parseSnapshot(doc, sample)); // actual Java HTTP fixture, not hand-written schema
    assert(sample.scanner == healthui::Health::CONNECTED && !sample.safeToTrigger);
    healthui::Controller ui; assert(ui.accept(sample, 0, 10));
    assert(ui.view(true, 10) == healthui::View::CONNECTED);
    doc["schemaVersion"] = 1; assert(!healthui::parseSnapshot(doc, sample)); doc["schemaVersion"] = 2;
    doc.remove("recoveryRequired"); assert(!healthui::parseSnapshot(doc, sample)); doc["recoveryRequired"] = false;
    doc["safeToTrigger"] = true; assert(!healthui::parseSnapshot(doc, sample)); doc["safeToTrigger"] = false;
    doc["health"]["reader"]["healthState"] = "INVALID"; assert(!healthui::parseSnapshot(doc, sample));
    doc["health"]["reader"]["healthState"] = "READY";
    doc["health"]["reader"]["lastProgressAgeMs"] = 1000; assert(!healthui::parseSnapshot(doc, sample));
    doc["health"]["reader"]["lastProgressAgeMs"] = 0;
    doc["barcode"] = std::string(100, 'A'); assert(!healthui::parseSnapshot(doc, sample));
    doc["barcode"] = ""; doc["recoveryRequired"] = true;
    assert(healthui::parseSnapshot(doc, sample));
    sample.sequence++; assert(ui.accept(sample, 20, 30));
    assert(ui.view(false, 5000) == healthui::View::RECOVERY_REQUIRED);
    puts("ESP32 parser checks passed (actual Java JSON, version/types/lengths/freshness/safe gate, recovery)");
}
