package org.fus;

import org.fus.domain.EpcCodec;
import org.fus.device.gpi.GpiButton;
import static org.fus.SafetyFixture.*;

public final class WorkflowCheck {
    public static void main(String[] args) throws Exception {
        check(EpcCodec.encode(BARCODE).equals(EPC), "encode");
        check(EpcCodec.decode(EPC.toLowerCase(java.util.Locale.ROOT)).equals(BARCODE), "decode");
        check(EpcCodec.decode("30300000000000000001").equals("000000000000000001"), "leading zero");
        try { EpcCodec.decode("394B113560066000403A"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        try (SafetyFixture f = new SafetyFixture()) {
            f.write(); check(f.rfid.writes == 1 && f.state().equals("PASS"), "verified write");
            f.station.trigger(); check(f.rfid.writes == 1, "consumed barcode");
            f.station.scan(BARCODE); f.now.set(100); f.station.trigger(); check(f.rfid.writes == 1, "expiry");
            f.station.scan("invalid"); f.station.trigger(); check(f.rfid.writes == 1, "invalid barcode");
            f.rfid.unique = false; f.write(); check(f.rfid.writes == 1 && f.state().equals("FAIL"), "multiple tags");
            f.rfid.unique = true;
            f.station.scan(BARCODE); f.station.connections(false, true); f.station.connections(true, true); f.station.trigger();
            check(f.rfid.writes == 1, "disconnect consumes barcode");
            f.write(); check(f.rfid.writes == 2 && f.state().equals("PASS"), "recovered safe failure");
            f.now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(5)); f.station.tick(); check(f.state().equals("IDLE"), "result timeout");
            String next = "9F1135600660004039";
            f.station.scan(BARCODE); f.station.scan(next); f.station.trigger(); check(f.rfid.epc.equals(EpcCodec.encode(next)), "latest barcode");
            f.station.scan(BARCODE); Runnable captured = f.station.beginTrigger(); check(captured != null, "reserve");
            check(!f.station.scan(next), "scan during write"); captured.run(); check(f.rfid.epc.equals(EPC), "reserved barcode");
            f.station.scan(BARCODE); Runnable disconnected = f.station.beginTrigger(); int before = f.rfid.writes;
            f.station.connections(false, true); disconnected.run(); check(f.rfid.writes == before && f.state().equals("FAIL"), "disconnect before worker");
        }
        GpiButton button = new GpiButton(10);
        check(!button.update(false, 0) && !button.update(false, 20), "boot low");
        check(!button.update(true, 21) && !button.update(true, 31), "release");
        check(!button.update(false, 32) && button.update(false, 42) && !button.update(false, 60), "debounce");
        System.out.println("Workflow checks passed");
    }
}
