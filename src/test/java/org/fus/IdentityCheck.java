package org.fus;

import org.fus.domain.*;
import java.util.*;
import static org.fus.SafetyFixture.*;

public final class IdentityCheck {
    private static PhysicalTagIdentity.Observation tag(String epc, String tid, boolean full) { return new PhysicalTagIdentity.Observation(epc, tid, full); }
    private static void blocked(List<PhysicalTagIdentity.Observation> observations, boolean verified) throws Exception {
        try { PhysicalTagIdentity.select(observations, verified); throw new AssertionError("unsafe discovery accepted"); }
        catch (StationFailure expected) { check(!expected.tagMayHaveChanged, "selection changed tag"); }
    }
    public static void main(String[] args) throws Exception {
        blocked(Collections.<PhysicalTagIdentity.Observation>emptyList(), true);
        blocked(Arrays.asList(tag(EPC, TID, true)), false);
        blocked(Arrays.asList(tag(EPC, null, false)), true);
        blocked(Arrays.asList(tag(EPC, "E2801170", false)), true);
        blocked(Arrays.asList(tag(EPC, TID, true), tag(EPC, "E28011700000000000000002", true)), true);
        blocked(Arrays.asList(tag(EPC, TID, true), tag("394B1135600660004038", TID, true)), true);
        blocked(Arrays.asList(tag(EPC, "not-hex", true)), true);
        StationWorkflow.Target target = PhysicalTagIdentity.select(Arrays.asList(tag(EPC, TID, true), tag(EPC.toLowerCase(Locale.ROOT), TID, true)), true);
        check(target.oldEpc().equals(EPC) && target.serializedTid().equals(TID), "stable identity lost");
        // Inspect public signatures only. No SDK construction, inventory or access operations.
        Class<?> access = Class.forName("com.mot.rfid.api3.TagAccess", false, IdentityCheck.class.getClassLoader());
        for (java.lang.reflect.Method method : access.getMethods()) {
            if (Arrays.asList("readWait", "writeWait", "readEvent", "writeEvent").contains(method.getName()))
                System.out.println("SDK public surface: " + method.toGenericString());
        }
        Class<?> gateway = Class.forName("org.fus.device.rfid.RfidGateway", false, IdentityCheck.class.getClassLoader());
        check(gateway.getMethod("supportsVerifiedIdentityAccess").getDeclaringClass() == StationWorkflow.Gateway.class,
                "production hardware gate unexpectedly enabled");
        System.out.println("Identity checks passed (duplicate EPC is not physical uniqueness; hardware access blocked)");
    }
}
