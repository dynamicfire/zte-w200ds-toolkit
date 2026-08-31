package io.github.dynamicfire.zte.gmsoptimizerguard;

final class DeviceGate {
    static final int SUPPORTED_SDK = 33;
    static final String SUPPORTED_INCREMENTAL = "20250218.231611";
    static final String SUPPORTED_FINGERPRINT =
            "ZTE/CN_P720P01/P720P01:13/TP1A.220624.014/20250218.231611:user/release-keys";

    private DeviceGate() {
    }

    static boolean isSupported(String fingerprint, int sdk, String incremental) {
        return SUPPORTED_SDK == sdk
                && SUPPORTED_FINGERPRINT.equals(fingerprint)
                && SUPPORTED_INCREMENTAL.equals(incremental);
    }
}
