package org.fus;

import org.fus.domain.StationWorkflow;
import org.fus.domain.EpcCodec;
import org.fus.device.gpi.GpiButton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class WorkflowCheck {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static class Rfid implements StationWorkflow.Gateway {
        int writes; boolean unique = true; boolean mismatch;
        String epc;
        public StationWorkflow.Target selectUnique() { if (!unique) throw new IllegalStateException("MULTIPLE_TAGS"); return new StationWorkflow.Target() {}; }
        public void write(StationWorkflow.Target target, String value) { writes++; epc = value; }
        public String read(StationWorkflow.Target target) { return mismatch ? "394B1135600660004038" : epc; }
    }
    public static void main(String[] args) {
        String barcode = "9K1135600660004039";
        check(EpcCodec.encode(barcode).equals("394B1135600660004039"));
        check(EpcCodec.decode("394b1135600660004039").equals(barcode));
        try { EpcCodec.decode("394B113560066000403A"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        AtomicLong now = new AtomicLong();
        List<String> states = new ArrayList<String>();
        Rfid rfid = new Rfid();
        StationWorkflow s = new StationWorkflow(rfid, (state, b, e, d) -> states.add(state), now::get, 100);
        s.connections(true, true);
        s.scan(barcode); s.trigger(); check(rfid.writes == 1 && states.get(states.size()-1).equals("PASS"));
        s.trigger(); check(rfid.writes == 1);
        s.scan(barcode); now.set(100); s.trigger(); check(rfid.writes == 1);
        s.scan("invalid"); s.trigger(); check(rfid.writes == 1);
        s.scan(barcode); rfid.unique = false; s.trigger(); check(rfid.writes == 1 && states.get(states.size()-1).equals("FAIL"));
        rfid.unique = true; rfid.mismatch = true; s.scan(barcode); s.trigger(); check(rfid.writes == 2 && states.get(states.size()-1).equals("FAIL"));
        rfid.mismatch = false;
        s.scan(barcode); s.connections(false, true); s.connections(true, true); s.trigger(); check(rfid.writes == 2);
        s.scan(barcode); s.trigger(); check(rfid.writes == 3 && states.get(states.size()-1).equals("PASS"));
        now.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(5)); s.tick(); check(states.get(states.size()-1).equals("IDLE"));
        String next = "9F1135600660004039";
        s.scan(barcode); s.scan(next); s.trigger(); check(rfid.epc.equals(EpcCodec.encode(next)));
        s.scan(barcode); Runnable captured = s.beginTrigger(); check(captured != null);
        check(!s.scan(next)); captured.run(); check(rfid.epc.equals(EpcCodec.encode(barcode)));
        s.scan(barcode); Runnable disconnected = s.beginTrigger();
        int before = rfid.writes; s.connections(false, true); disconnected.run();
        check(rfid.writes == before && states.get(states.size()-1).equals("FAIL"));
        GpiButton button = new GpiButton(10);
        check(!button.update(false, 0)); check(!button.update(false, 20));
        check(!button.update(true, 21)); check(!button.update(true, 31));
        check(!button.update(false, 32)); check(button.update(false, 42)); check(!button.update(false, 60));
        System.out.println("Workflow checks passed");
    }
}
