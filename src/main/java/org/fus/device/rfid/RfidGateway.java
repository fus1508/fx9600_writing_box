package org.fus.device.rfid;

import org.fus.domain.PhysicalTagIdentity;
import org.fus.domain.StationWorkflow;
import com.mot.rfid.api3.*;

/** Production access remains blocked until full serialized TID discovery/selection is verified. */
public final class RfidGateway implements StationWorkflow.Gateway, RfidEventsListener {
    private final RFIDReader reader;
    private volatile boolean sessionUsable = true;
    private final java.util.function.Consumer<String> healthEvent;

    public RfidGateway(RFIDReader reader, long password) {
        this(reader, password, reason -> { });
    }
    public RfidGateway(RFIDReader reader, long password, java.util.function.Consumer<String> healthEvent) {
        this.reader = reader;
        this.healthEvent = healthEvent;
        reader.Events.addEventsListener(this);
        reader.Events.setReaderDisconnectEvent(true);
        reader.Events.setReaderExceptionEvent(true);
    }
    public void eventReadNotify(RfidReadEvents e) { }
    public void eventStatusNotify(RfidStatusEvents e) {
        STATUS_EVENT_TYPE type = e.StatusEventData.getStatusEventType();
        if (type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT || type == STATUS_EVENT_TYPE.READER_EXCEPTION_EVENT) {
            sessionUsable = false;
            healthEvent.accept(type == STATUS_EVENT_TYPE.DISCONNECTION_EVENT ? "SDK_DISCONNECT" : "SDK_EXCEPTION");
        }
    }
    public boolean isSessionUsable() { return sessionUsable && reader.isConnected(); }
    public StationWorkflow.Target selectUnique() throws Exception { throw PhysicalTagIdentity.blocked(); }
    public void write(StationWorkflow.Target target, String epc) throws Exception { throw PhysicalTagIdentity.blocked(); }
    public String read(StationWorkflow.Target target) throws Exception { throw PhysicalTagIdentity.blocked(); }
}
