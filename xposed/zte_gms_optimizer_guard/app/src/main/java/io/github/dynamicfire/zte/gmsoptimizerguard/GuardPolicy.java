package io.github.dynamicfire.zte.gmsoptimizerguard;

final class GuardPolicy {
    private GuardPolicy() {
    }

    /**
     * A destructive OEM optimization must be neutralized whenever Google is already allowed,
     * the user disabled the feature, a VPN is active/recently active, or policy inspection failed.
     */
    static boolean shouldNeutralize(
            boolean latchskyEnabled,
            boolean vpnActive,
            boolean optimizerDisabled,
            boolean oemGmsAllowed,
            boolean vpnGraceActive,
            boolean inspectionFailed) {
        return inspectionFailed
                || !latchskyEnabled
                || vpnActive
                || optimizerDisabled
                || oemGmsAllowed
                || vpnGraceActive;
    }

    static boolean shouldBypassRuntime(
            boolean callbackHealthy,
            boolean latchskyEnabled,
            boolean vpnActive,
            boolean optimizerDisabled,
            boolean oemGmsAllowed,
            boolean vpnGraceActive,
            boolean inspectionFailed) {
        return !callbackHealthy || shouldNeutralize(
                latchskyEnabled,
                vpnActive,
                optimizerDisabled,
                oemGmsAllowed,
                vpnGraceActive,
                inspectionFailed);
    }

    /** Keep the independent wake-lock policy in its usable (less aggressive) state while bypassing. */
    static boolean shouldForceGmsAllowed(
            boolean latchskyEnabled,
            boolean vpnActive,
            boolean optimizerDisabled,
            boolean vpnGraceActive,
            boolean inspectionFailed) {
        return inspectionFailed
                || !latchskyEnabled
                || vpnActive
                || optimizerDisabled
                || vpnGraceActive;
    }

    static boolean shouldForceGmsAllowedRuntime(
            boolean callbackHealthy,
            boolean latchskyEnabled,
            boolean vpnActive,
            boolean optimizerDisabled,
            boolean vpnGraceActive,
            boolean inspectionFailed) {
        return !callbackHealthy || shouldForceGmsAllowed(
                latchskyEnabled,
                vpnActive,
                optimizerDisabled,
                vpnGraceActive,
                inspectionFailed);
    }
}
