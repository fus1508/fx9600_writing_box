package org.fus.domain;

import java.util.Collection;
import java.util.Locale;

/** Identity policy for a future verified discovery adapter; not a hardware TID reader. */
public final class PhysicalTagIdentity {
    private PhysicalTagIdentity() { }
    public static final class Observation {
        public final String epc, tid;
        public final boolean fullSerializedTid;
        public Observation(String epc, String tid, boolean fullSerializedTid) {
            this.epc = epc; this.tid = tid; this.fullSerializedTid = fullSerializedTid;
        }
    }
    private static final class Identity implements StationWorkflow.Target {
        private final String epc, tid;
        Identity(String epc, String tid) { this.epc = epc; this.tid = tid; }
        public String oldEpc() { return epc; }
        public String serializedTid() { return tid; }
        public boolean identityVerified() { return true; }
    }
    public static StationWorkflow.Target select(Collection<Observation> observations, boolean discoveryVerified) throws StationFailure {
        if (!discoveryVerified) throw blocked();
        Identity identity = null;
        for (Observation observation : observations) {
            if (observation == null || !observation.fullSerializedTid || !hex(observation.epc) || !hex(observation.tid))
                throw failure("IDENTITY_UNPROVEN");
            String epc = observation.epc.toUpperCase(Locale.ROOT), tid = observation.tid.toUpperCase(Locale.ROOT);
            if (identity != null && (!identity.tid.equals(tid) || !identity.epc.equals(epc))) throw failure("MULTIPLE_OR_UNSTABLE_TAGS");
            identity = new Identity(epc, tid);
        }
        if (identity == null) throw failure("NO_TAG");
        return identity;
    }
    private static boolean hex(String value) { return value != null && value.length() >= 4 && value.length() <= 512 && value.length() % 4 == 0 && value.matches("[0-9A-Fa-f]+"); }
    private static StationFailure failure(String code) { return new StationFailure("BUSINESS", code, "IDENTITY", false, "Physical target is unproven", null); }
    public static StationFailure blocked() {
        return new StationFailure("TECHNICAL", "HARDWARE_VERIFICATION_REQUIRED", "IDENTITY", false,
                "Full serialized TID discovery and access must be verified on SDK/firmware", null);
    }
}
