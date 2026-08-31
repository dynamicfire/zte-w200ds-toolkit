package io.github.dynamicfire.zte.gmsoptimizerguard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GuardPolicyTest {
    @Test
    public void neutralizeTruthTableAllowsOnlyOneOemBlockingState() {
        int preservingStates = 0;
        for (int mask = 0; mask < 64; mask++) {
            boolean latchsky = (mask & 1) != 0;
            boolean vpn = (mask & 2) != 0;
            boolean disabled = (mask & 4) != 0;
            boolean allowed = (mask & 8) != 0;
            boolean grace = (mask & 16) != 0;
            boolean failed = (mask & 32) != 0;

            boolean neutralize = GuardPolicy.shouldNeutralize(
                    latchsky, vpn, disabled, allowed, grace, failed);
            boolean expectedPreserve = latchsky
                    && !vpn
                    && !disabled
                    && !allowed
                    && !grace
                    && !failed;
            assertEquals(!expectedPreserve, neutralize);
            if (expectedPreserve) {
                preservingStates++;
            }
        }
        assertEquals(1, preservingStates);
    }

    @Test
    public void forceGmsAllowedDoesNotOverrideNormalOemState() {
        assertFalse(GuardPolicy.shouldForceGmsAllowed(
                true, false, false, false, false));
        assertTrue(GuardPolicy.shouldForceGmsAllowed(
                true, true, false, false, false));
        assertTrue(GuardPolicy.shouldForceGmsAllowed(
                false, false, false, false, false));
        assertTrue(GuardPolicy.shouldForceGmsAllowed(
                true, false, false, true, false));
        assertTrue(GuardPolicy.shouldForceGmsAllowed(
                true, false, false, false, true));
    }

    @Test
    public void unhealthyVpnCallbackAlwaysFailsOpen() {
        assertTrue(GuardPolicy.shouldBypassRuntime(
                false, true, false, false, false, false, false));
        assertTrue(GuardPolicy.shouldForceGmsAllowedRuntime(
                false, true, false, false, false, false));
        assertFalse(GuardPolicy.shouldBypassRuntime(
                true, true, false, false, false, false, false));
        assertFalse(GuardPolicy.shouldForceGmsAllowedRuntime(
                true, true, false, false, false, false));
    }

    @Test
    public void deviceGateIsExactAndFailsInertAfterOta() {
        assertTrue(DeviceGate.isSupported(
                DeviceGate.SUPPORTED_FINGERPRINT, 33, DeviceGate.SUPPORTED_INCREMENTAL));
        assertFalse(DeviceGate.isSupported(
                DeviceGate.SUPPORTED_FINGERPRINT, 34, DeviceGate.SUPPORTED_INCREMENTAL));
        assertFalse(DeviceGate.isSupported(
                DeviceGate.SUPPORTED_FINGERPRINT + ".ota", 33, DeviceGate.SUPPORTED_INCREMENTAL));
        assertFalse(DeviceGate.isSupported(
                DeviceGate.SUPPORTED_FINGERPRINT, 33, DeviceGate.SUPPORTED_INCREMENTAL + ".ota"));
        assertFalse(DeviceGate.isSupported(
                null, 33, DeviceGate.SUPPORTED_INCREMENTAL));
    }
}
