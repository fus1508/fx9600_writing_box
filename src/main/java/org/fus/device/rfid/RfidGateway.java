package org.fus.device.rfid;

import org.fus.domain.StationWorkflow;
import org.fus.domain.StationFailure;
import com.mot.rfid.api3.*;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Requires an operational enclosure containing exactly one physical tag.
 */
public final class RfidGateway implements StationWorkflow.Gateway, RfidEventsListener {
    private final RFIDReader reader;
    private final long password;
    private volatile CountDownLatch stopped;
    private volatile boolean unreliable;
    private volatile boolean sessionUsable = true;

    private static final class Tag implements StationWorkflow.Target {
        final String epc;
        String current;

        Tag(String epc) {
            this.epc = epc;
            this.current = epc;
        }
    }

    public RfidGateway(RFIDReader reader, long password) {
        this.reader = reader;
        this.password = password;
        reader.Events.addEventsListener(this);
        reader.Events.setInventoryStopEvent(true);
        reader.Events.setBufferFullEvent(true);
        reader.Events.setBufferFullWarningEvent(true);
        reader.Events.setReaderDisconnectEvent(true);
        reader.Events.setReaderExceptionEvent(true);
    }

    public void eventReadNotify(RfidReadEvents e) {
    }

    public void eventStatusNotify(RfidStatusEvents e) {
        STATUS_EVENT_TYPE type = e.StatusEventData.getStatusEventType();
        if (type == STATUS_EVENT_TYPE.INVENTORY_STOP_EVENT) {
            CountDownLatch latch = stopped;
            if (latch != null) latch.countDown();
        }
        if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT || type == STATUS_EVENT_TYPE.READER_EXCEPTION_EVENT)
            sessionUsable = false;
        if (type == STATUS_EVENT_TYPE.BUFFER_FULL_EVENT || type == STATUS_EVENT_TYPE.BUFFER_FULL_WARNING_EVENT || type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT || type == STATUS_EVENT_TYPE.READER_EXCEPTION_EVENT)
            unreliable = true;
    }
    public boolean isSessionUsable() { return sessionUsable && reader.isConnected(); }

    public StationWorkflow.Target selectUnique() throws Exception {
        try { return inventory(); }
        catch (StationFailure e) { throw e; }
        catch (Exception e) { throw translate("INVENTORY", false, e); }
    }
    private StationWorkflow.Target inventory() throws Exception {
        if (!isSessionUsable()) throw new IllegalStateException("READER_DISCONNECTED");
        TagStorageSettings storage = reader.Config.getTagStorageSettings();
        if (storage == null || storage.isTagsOnInventoryStopDiscarded())
            throw new IllegalStateException("INVENTORY_STORAGE_CONFIGURATION");
        if (!reader.Actions.purgeTags()) throw new IllegalStateException("PURGE_FAILED");
        TriggerInfo trigger = new TriggerInfo();
        trigger.StartTrigger.setTriggerType(START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE);
        trigger.StopTrigger.setTriggerType(STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_DURATION);
        trigger.StopTrigger.setDurationMilliSeconds(1000);
        trigger.setTagReportTrigger(1);
        Set<String> epcs = new LinkedHashSet<String>();
        stopped = new CountDownLatch(1);
        unreliable = false;
        boolean running = false;
        try {
            reader.Actions.Inventory.perform(null, trigger, null);
            running = true;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
            while (stopped.getCount() != 0) {
                collect(epcs);
                if (unreliable || !reader.isConnected() || System.nanoTime() >= deadline)
                    throw new IllegalStateException("INVENTORY_UNRELIABLE");
                stopped.await(100, TimeUnit.MILLISECONDS);
            }
            running = false;
            collect(epcs);
            if (unreliable || !reader.isConnected()) throw new IllegalStateException("INVENTORY_UNRELIABLE");
            if (epcs.size() != 1) throw new StationFailure("BUSINESS", epcs.isEmpty() ? "NO_TAG" : "MULTIPLE_TAGS", "INVENTORY", false, "Check the tag area", null);
            String epc = epcs.iterator().next();
            if (epc.length() < 4 || epc.length() > 124 || epc.length() % 4 != 0) throw new IllegalStateException("INVALID_TARGET_EPC_LENGTH");
            return new Tag(epc);
        } finally {
            if (running) {
                try {
                    reader.Actions.Inventory.stop();
                } catch (Exception ex) {
                    unreliable = true;
                    sessionUsable = false;
                }
            }
            stopped = null;
        }
    }

    private void collect(Set<String> epcs) throws StationFailure {
        int batches = 0;
        TagData[] tags;
        while ((tags = reader.Actions.getReadTags(100)) != null && tags.length > 0) {
            if (++batches > 100) throw new IllegalStateException("TOO_MANY_REPORTS");
            for (TagData tag : tags) {
                String id = tag.getTagID();
                if (id == null || !id.matches("[0-9A-Fa-f]+")) throw new IllegalStateException("INVALID_INVENTORY");
                epcs.add(id.toUpperCase(Locale.ROOT));
                if (epcs.size() > 1) throw new StationFailure("BUSINESS", "MULTIPLE_TAGS", "INVENTORY", false, "Check the tag area", null);
            }
        }
    }

    public void write(StationWorkflow.Target target, String epc) throws Exception {
        try { writeChecked(target, epc); }
        catch (StationFailure e) { throw e; }
        catch (Exception e) { throw translate("PRECHECK", false, e); }
    }
    private void writeChecked(StationWorkflow.Target target, String epc) throws Exception {
        Tag tag = (Tag) target;
        byte[] payload = hexBytes(epc);
        if (tag.epc.length() != epc.length()) {
            throw new StationFailure("BUSINESS", "EPC_RESIZE_UNVERIFIED", "PRECHECK", false,
                    "EPC length change is disabled until verified on MR6P", null);
        }
        writeStep(tag.epc, 4, payload, "WRITE_EPC");
        tag.current = epc;
    }
    private static byte[] hexBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }
    private void writeStep(String target, int offset, byte[] bytes, String stage) throws Exception {
        try { writeMemory(target, offset, bytes); }
        catch (Exception e) { throw translate(stage, true, e); }
    }
    private void writeMemory(String target, int offset, byte[] bytes) throws Exception {
        TagAccess.WriteAccessParams params = reader.Actions.TagAccess.new WriteAccessParams();
        params.setMemoryBank(MEMORY_BANK.MEMORY_BANK_EPC); params.setByteOffset(offset);
        params.setWriteData(bytes); params.setWriteDataLength(bytes.length); params.setAccessPassword(password);
        reader.Actions.TagAccess.writeWait(target, params, null);
    }
    public String read(StationWorkflow.Target target) throws Exception {
        try { return readMemory(((Tag) target).current, 4, 10); }
        catch (Exception e) { throw translate("VERIFY_EPC", true, e); }
    }
    private String readMemory(String target, int offset, int length) throws Exception {
        TagAccess.ReadAccessParams params = reader.Actions.TagAccess.new ReadAccessParams();
        params.setMemoryBank(MEMORY_BANK.MEMORY_BANK_EPC); params.setByteOffset(offset); params.setByteCount(length); params.setAccessPassword(password);
        TagData result = reader.Actions.TagAccess.readWait(target, params, null);
        if (result == null || result.getOpStatus() != ACCESS_OPERATION_STATUS.ACCESS_SUCCESS || result.getMemoryBankData() == null
                || result.getMemoryBankData().length() != length * 2 || !result.getMemoryBankData().matches("[0-9A-Fa-f]+")) {
            throw new IllegalStateException("READ_FAILED");
        }
        return result.getMemoryBankData();
    }
    private static StationFailure translate(String stage, boolean changed, Exception e) {
        String detail = e.getClass().getSimpleName();
        if (e instanceof OperationFailureException) {
            OperationFailureException failure = (OperationFailureException) e;
            detail += " sdkResult=" + failure.getResults() + " sdkStatus=" + failure.getStatusDescription();
        } else if (e instanceof IllegalStateException && e.getMessage() != null) {
            detail += " reason=" + e.getMessage();
        }
        return new StationFailure("TECHNICAL", "RFID_OPERATION_FAILED", stage, changed, detail, e);
    }

}
