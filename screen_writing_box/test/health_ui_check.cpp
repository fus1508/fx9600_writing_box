#include "health_ui.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

using namespace healthui;
static Snapshot ready(unsigned sequence = 1) {
    Snapshot s = {};
    strcpy(s.bootId, "boot-A"); strcpy(s.revision, "boot-A-1"); strcpy(s.transactionId, "tx-A");
    strcpy(s.workflow, "WAIT_TRIGGER");
    s.sequence = sequence; s.reader = s.scanner = s.application = Health::READY;
    s.readerAge = s.scannerAge = s.applicationAge = 0; s.safeToTrigger = true;
    return s;
}
int main() {
    Controller ui;
    assert(ui.view(true, 0) == View::WAITING_FOR_STARTUP);
    Snapshot s = ready(); assert(ui.accept(s, 10, 20));
    assert(ui.view(true, 20) == View::READY);
    assert(ui.view(false, 21) == View::STATION_UNREACHABLE);
    assert(ui.view(true, 22) == View::STATION_UNREACHABLE); // reconnect needs a new response
    assert(!ui.accept(s, 1020, 1030)); // frozen/replayed API response
    assert(ui.view(true, 2520) == View::STATION_UNREACHABLE);
    s = ready(2); assert(ui.accept(s, 2600, 2610)); assert(ui.view(true, 2610) == View::READY);
    Controller stalled; stalled.accept(ready(), 0, 10);
    assert(stalled.view(true, 1010) == View::UNKNOWN); // render timer runs while HTTP is blocked
    assert(stalled.view(true, 2510) == View::STATION_UNREACHABLE);
    assert(stalled.view(true, 10) == View::STATION_UNREACHABLE); // full millis wrap cannot revive an expired cache
    Controller longOutage; longOutage.view(false, 1);
    assert(longOutage.accept(ready(), 0x80000100u, 0x80000110u)); // reconnect after >24 days
    s = ready(3); s.scanner = Health::CONNECTED; ui.accept(s, 2700, 2710);
    assert(ui.view(true, 2710) == View::CONNECTED);
    s = ready(4); s.reader = Health::DISCONNECTED; ui.accept(s, 2800, 2810);
    assert(ui.view(true, 2810) == View::READER_OFFLINE);
    s = ready(5); s.scanner = Health::DISCONNECTED; ui.accept(s, 2900, 2910);
    assert(ui.view(true, 2910) == View::SCANNER_OFFLINE);
    s = ready(6); s.reader = Health::RECOVERING; ui.accept(s, 3000, 3010);
    assert(ui.view(true, 3010) == View::RECOVERING);
    s = ready(7); s.recovery = true; ui.accept(s, 3100, 3110);
    assert(ui.view(false, 9000) == View::RECOVERY_REQUIRED); // offline does not hide recovery
    s = ready(8); s.recovery = false; strcpy(s.workflow, "WRITING"); ui.accept(s, 9100, 9110);
    assert(ui.view(true, 9110) == View::WRITING);
    assert(ui.view(false, 9120) == View::RECOVERY_REQUIRED);
    s = ready(9); strcpy(s.workflow, "PASS"); ui.accept(s, 9200, 9210);
    assert(ui.view(true, 9210) == View::PASS);
    s = ready(10); strcpy(s.workflow, "FAIL"); ui.accept(s, 9300, 9310);
    assert(ui.view(true, 9310) == View::FAIL);
    s = ready(11); s.applicationAge = 1000; ui.accept(s, 9400, 9410);
    assert(ui.view(true, 9410) == View::UNKNOWN);
    s = ready(12); assert(!ui.accept(s, 9500, 10501)); // delayed response cannot restore READY
    Controller wrapped; s = ready(); wrapped.accept(s, 0xfffffff0u, 0xfffffffau);
    assert(wrapped.view(true, 20) == View::READY);
    assert(wrapped.view(true, 1100) == View::UNKNOWN);
    for (unsigned i = 13; i < 1013; ++i) {
        s = ready(i); s.reader = i % 2 ? Health::READY : Health::DISCONNECTED;
        ui.accept(s, i * 1000, i * 1000 + 10);
        assert(ui.view(true, i * 1000 + 10) == (i % 2 ? View::READY : View::READER_OFFLINE));
    }
    // Boot replacement during a write must not reuse the old result/readiness.
    Controller reboot; s = ready(); strcpy(s.workflow, "WRITING"); reboot.accept(s, 0, 1);
    s = ready(1); strcpy(s.bootId, "boot-B"); reboot.accept(s, 2, 3);
    assert(reboot.view(true, 3) == View::RECOVERY_REQUIRED);
    Controller pending; s = ready(); strcpy(s.workflow, "WRITING"); pending.accept(s, 0, 1);
    assert(pending.view(false, 2) == View::RECOVERY_REQUIRED);
    s = ready(2); strcpy(s.transactionId, "tx-B"); pending.accept(s, 3, 4);
    assert(pending.view(true, 4) == View::RECOVERY_REQUIRED);
    s = ready(3); strcpy(s.transactionId, "tx-B"); strcpy(s.workflow, "PASS"); pending.accept(s, 5, 6);
    assert(pending.view(true, 6) == View::RECOVERY_REQUIRED); // another transaction cannot resolve tx-A
    Controller noId; s = ready(); strcpy(s.workflow, "WRITING"); s.transactionId[0] = '\0'; noId.accept(s, 0, 1);
    assert(noId.view(false, 2) == View::RECOVERY_REQUIRED);
    s = ready(2); strcpy(s.workflow, "PASS"); noId.accept(s, 3, 4);
    assert(noId.view(true, 4) == View::RECOVERY_REQUIRED); // missing identity is never a wildcard
    s = ready(3); strcpy(s.workflow, "FAIL"); noId.accept(s, 5, 6);
    assert(noId.view(true, 6) == View::RECOVERY_REQUIRED);
    puts("ESP32 UI reducer checks passed (1000 transitions, stale/replay/timeout, WiFi, recovery, boot, millis wrap)");
}
