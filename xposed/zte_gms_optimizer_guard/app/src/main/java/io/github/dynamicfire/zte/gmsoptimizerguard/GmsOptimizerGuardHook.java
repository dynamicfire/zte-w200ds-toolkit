package io.github.dynamicfire.zte.gmsoptimizerguard;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Runtime guard for the ZTE Android 13 GoogleOptimizer implementation found on P720P01.
 *
 * <p>The OEM implementation couples useful background-power policy to a destructive UID firewall.
 * This hook preserves the OEM path while no VPN is in use, but switches the optimizer to its own
 * clear/allow state whenever a VPN is active. It also blocks stale delayed jobs from writing DROP
 * rules after the user disabled the feature or after Google became allowed.</p>
 */
public final class GmsOptimizerGuardHook implements IXposedHookLoadPackage {
    private static final String TAG = "ZteGmsOptGuard";
    private static final String OPTIMIZER_CLASS = "com.android.server.am.GoogleOptimizer";
    private static final int OPTIMIZE_SET = 0;
    private static final int OPTIMIZE_CLEAR = 1;
    private static final int MSG_CHECK_GOOGLE = 1;
    private static final long VPN_LOSS_GRACE_MS = 30_000L;
    private static final int MAX_CALLBACK_RETRIES = 3;

    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean CALLBACK_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean CALLBACK_REGISTRATION_IN_PROGRESS = new AtomicBoolean(false);
    private static final AtomicBoolean CALLBACK_RETRY_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean CALLBACK_RETRIES_EXHAUSTED = new AtomicBoolean(false);
    private static final AtomicBoolean OEM_RESUME_SCHEDULED = new AtomicBoolean(false);
    private static final AtomicBoolean CLEAR_PENDING = new AtomicBoolean(false);
    private static final AtomicInteger CALLBACK_RETRY_COUNT = new AtomicInteger(0);
    private static final ThreadLocal<Boolean> INTERCEPTED_OPTIMIZE = new ThreadLocal<>();

    private static volatile Method optimizeMethod;
    private static volatile long lastVpnSeenElapsed;
    private static volatile Object optimizerInstance;
    private static volatile PolicySnapshot cachedSnapshot = PolicySnapshot.failedOpen();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // Vector represents system_server as the virtual scope "system/0". Depending on the
        // legacy bridge version, the callback package label may be "system" or "android", so use
        // UID 1000 plus the audited target class instead of relying on that label.
        if (Process.myUid() != Process.SYSTEM_UID) {
            return;
        }

        if (!DeviceGate.isSupported(
                Build.FINGERPRINT, Build.VERSION.SDK_INT, Build.VERSION.INCREMENTAL)) {
            Log.e(TAG, "Unsupported firmware; module remains inert: sdk="
                    + Build.VERSION.SDK_INT + " incremental=" + Build.VERSION.INCREMENTAL
                    + " fingerprint=" + Build.FINGERPRINT);
            return;
        }

        if (!HOOKS_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        List<XC_MethodHook.Unhook> installedHooks = new ArrayList<>();
        try {
            Class<?> optimizerClass = XposedHelpers.findClass(
                    OPTIMIZER_CLASS, loadPackageParam.classLoader);
            optimizeMethod = verifyFirmwareShape(optimizerClass);

            // Install the final safety net first and constructor capture last. A live optimizer
            // can therefore never be observed with only part of the hook set installed.
            installedHooks.add(hookFinalFirewallGuard(optimizerClass));
            installedHooks.add(hookGmsAllowStatus(optimizerClass));
            installedHooks.add(hookDelayedHandler(optimizerClass));
            installedHooks.add(hookUpdateStrategy(optimizerClass));
            installedHooks.add(hookConstructor(optimizerClass));
            Log.i(TAG, "Hooks installed for supported P720P01 firmware");
        } catch (Throwable error) {
            boolean rollbackClean = true;
            for (int index = installedHooks.size() - 1; index >= 0; index--) {
                try {
                    installedHooks.get(index).unhook();
                } catch (Throwable unhookError) {
                    rollbackClean = false;
                    Log.e(TAG, "Unable to roll back a partially installed hook", unhookError);
                }
            }
            if (rollbackClean) {
                optimizeMethod = null;
                HOOKS_INSTALLED.set(false);
                Log.e(TAG, "Hook installation failed and was fully rolled back", error);
            } else {
                // Never retry over a hook that could not be removed; duplicated callbacks in
                // system_server are riskier than keeping the first partial set until reboot.
                HOOKS_INSTALLED.set(true);
                Log.e(TAG, "Hook installation failed; partial rollback prevents retry until reboot", error);
            }
        }
    }

    private static Method verifyFirmwareShape(Class<?> optimizerClass)
            throws ReflectiveOperationException {
        Constructor<?> constructor = optimizerClass.getDeclaredConstructor(Context.class);
        Field context = optimizerClass.getDeclaredField("mContext");
        Field handler = optimizerClass.getDeclaredField("mHandler");
        Field allowed = optimizerClass.getDeclaredField("mGmsAllowed");
        Field disabled = optimizerClass.getDeclaredField("disableOptimizer");
        Method optimize = optimizerClass.getDeclaredMethod("optimize", int.class);
        Method update = optimizerClass.getDeclaredMethod("updateCheckStrategy");
        Method delayed = optimizerClass.getDeclaredMethod("handleOptimizerMsg");
        Method status = optimizerClass.getDeclaredMethod("getGmsAllowStatus");

        if (constructor.getParameterTypes().length != 1
                || !Context.class.isAssignableFrom(context.getType())
                || !Handler.class.isAssignableFrom(handler.getType())
                || allowed.getType() != boolean.class
                || disabled.getType() != boolean.class
                || optimize.getReturnType() != void.class
                || update.getReturnType() != void.class
                || delayed.getReturnType() != void.class
                || status.getReturnType() != boolean.class) {
            throw new NoSuchMethodException("GoogleOptimizer shape does not match audited firmware");
        }
        return optimize;
    }

    private static XC_MethodHook.Unhook hookConstructor(Class<?> optimizerClass) {
        return XposedHelpers.findAndHookConstructor(
                optimizerClass,
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        optimizerInstance = param.thisObject;
                        try {
                            refreshSnapshot(param.thisObject);
                            ensureVpnCallback(param.thisObject);
                            PolicySnapshot snapshot = cachedSnapshot;
                            if (shouldBypassNow(snapshot)) {
                                requestClear(param.thisObject, "constructor:" + effectiveReason(snapshot));
                            }
                        } catch (Throwable error) {
                            publishSnapshot(PolicySnapshot.failedOpen());
                            Log.e(TAG, "Constructor setup failed; requesting fail-open clear", error);
                            requestClear(param.thisObject, "constructor-fail-open");
                        }
                    }
                });
    }

    private static XC_MethodHook.Unhook hookUpdateStrategy(Class<?> optimizerClass) {
        return XposedHelpers.findAndHookMethod(
                optimizerClass,
                "updateCheckStrategy",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            refreshSnapshot(param.thisObject);
                            ensureVpnCallback(param.thisObject);
                            PolicySnapshot snapshot = cachedSnapshot;
                            if (shouldBypassNow(snapshot)) {
                                // Block first, then serialize netd/freezer/alarm work on the OEM
                                // handler. The settings observer may invoke this hook off-handler.
                                param.setResult(null);
                                requestClear(param.thisObject, "update:" + effectiveReason(snapshot));
                            }
                        } catch (Throwable error) {
                            param.setResult(null);
                            publishSnapshot(PolicySnapshot.failedOpen());
                            Log.e(TAG, "Policy refresh failed in updateCheckStrategy; blocking DROP path", error);
                            requestClear(param.thisObject, "update-fail-open");
                        }
                    }
                });
    }

    private static XC_MethodHook.Unhook hookDelayedHandler(Class<?> optimizerClass) {
        return XposedHelpers.findAndHookMethod(
                optimizerClass,
                "handleOptimizerMsg",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            refreshSnapshot(param.thisObject);
                            ensureVpnCallback(param.thisObject);
                            PolicySnapshot snapshot = cachedSnapshot;
                            if (shouldBypassNow(snapshot)) {
                                param.setResult(null);
                                requestClear(param.thisObject, "delayed:" + effectiveReason(snapshot));
                            }
                        } catch (Throwable error) {
                            param.setResult(null);
                            publishSnapshot(PolicySnapshot.failedOpen());
                            Log.e(TAG, "Policy refresh failed in delayed check; blocking DROP path", error);
                            requestClear(param.thisObject, "delayed-fail-open");
                        }
                    }
                });
    }

    private static XC_MethodHook.Unhook hookFinalFirewallGuard(Class<?> optimizerClass) {
        return XposedHelpers.findAndHookMethod(
                optimizerClass,
                "optimize",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        INTERCEPTED_OPTIMIZE.remove();
                        if (param.args == null
                                || param.args.length == 0
                                || !(param.args[0] instanceof Integer)
                                || ((Integer) param.args[0]) != OPTIMIZE_SET) {
                            return;
                        }

                        // optimize() can be called while the OEM holds its monitor. Never do a
                        // SettingsProvider or ConnectivityService Binder call here.
                        PolicySnapshot snapshot = param.thisObject == optimizerInstance
                                ? cachedSnapshot
                                : PolicySnapshot.failedOpen();
                        if (shouldBypassNow(snapshot)) {
                            param.setResult(null);
                            INTERCEPTED_OPTIMIZE.set(true);
                            requestClear(param.thisObject, "final-guard:" + effectiveReason(snapshot));
                            Log.i(TAG, "Blocked destructive optimize(0): " + effectiveReason(snapshot));
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!Boolean.TRUE.equals(INTERCEPTED_OPTIMIZE.get())) {
                            return;
                        }
                        INTERCEPTED_OPTIMIZE.remove();
                        try {
                            Handler handler = getOptimizerHandler(param.thisObject);
                            // A caller can enqueue message 1 immediately after optimize() returns.
                            // A short tail cleanup reliably lands after that enqueue on any caller.
                            boolean posted = handler.postDelayed(
                                    () -> {
                                        PolicySnapshot snapshot = cachedSnapshot;
                                        if (shouldBypassNow(snapshot)) {
                                            handler.removeMessages(MSG_CHECK_GOOGLE);
                                        }
                                    }, 100L);
                            if (!posted) {
                                Log.e(TAG, "Optimizer handler rejected tail cleanup");
                            }
                        } catch (Throwable error) {
                            Log.e(TAG, "Unable to schedule tail cleanup for blocked optimize", error);
                        }
                    }
                });
    }

    private static XC_MethodHook.Unhook hookGmsAllowStatus(Class<?> optimizerClass) {
        return XposedHelpers.findAndHookMethod(
                optimizerClass,
                "getGmsAllowStatus",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // Called from a PowerManager wake-lock path: cache reads only, no Binder.
                        PolicySnapshot snapshot = param.thisObject == optimizerInstance
                                ? cachedSnapshot
                                : PolicySnapshot.failedOpen();
                        if (snapshot.shouldForceGmsAllowedRuntime(CALLBACK_REGISTERED.get())) {
                            param.setResult(true);
                        }
                    }
                });
    }

    @SuppressLint("MissingPermission") // Runs only inside UID 1000 system_server.
    @TargetApi(Build.VERSION_CODES.R) // DeviceGate requires Android 13 before hooks install.
    private static void ensureVpnCallback(Object optimizer) {
        if (CALLBACK_REGISTERED.get()
                || CALLBACK_RETRY_SCHEDULED.get()
                || CALLBACK_RETRIES_EXHAUSTED.get()
                || !CALLBACK_REGISTRATION_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }

        try {
            Context context = getOptimizerContext(optimizer);
            ConnectivityManager connectivity =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivity == null) {
                throw new IllegalStateException("ConnectivityManager unavailable");
            }

            Handler handler = getOptimizerHandler(optimizer);
            NetworkRequest request = new NetworkRequest.Builder()
                    .clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                    .build();
            connectivity.registerNetworkCallback(
                    request,
                    new ConnectivityManager.NetworkCallback() {
                        @Override
                        public void onAvailable(Network network) {
                            handleVpnAvailable(optimizer, "vpn-available");
                        }

                        @Override
                        public void onCapabilitiesChanged(
                                Network network, NetworkCapabilities capabilities) {
                            if (capabilities != null
                                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                                handleVpnAvailable(optimizer, "vpn-capabilities");
                            }
                        }

                        @Override
                        public void onLost(Network network) {
                            handleVpnLost(optimizer);
                        }
                    },
                    handler);
            CALLBACK_REGISTERED.set(true);
            CALLBACK_RETRY_COUNT.set(0);
            CALLBACK_RETRIES_EXHAUSTED.set(false);
            PolicySnapshot snapshot = refreshSnapshot(optimizer);
            if (snapshot.shouldNeutralize()) {
                requestClear(optimizer, "callback-registered:" + snapshot.reason());
            } else {
                scheduleOemResume(optimizer, 1_000L);
            }
            Log.i(TAG, "VPN callback registered");
        } catch (Throwable error) {
            publishSnapshot(PolicySnapshot.failedOpen());
            requestClear(optimizer, "callback-unhealthy");
            Log.e(TAG, "VPN callback unavailable; low-frequency method hooks remain active", error);
            scheduleCallbackRetry(optimizer);
        } finally {
            CALLBACK_REGISTRATION_IN_PROGRESS.set(false);
        }
    }

    private static void scheduleCallbackRetry(Object optimizer) {
        if (CALLBACK_RETRY_COUNT.get() >= MAX_CALLBACK_RETRIES) {
            CALLBACK_RETRIES_EXHAUSTED.set(true);
            Log.e(TAG, "VPN callback retries exhausted; remaining fail-open until reboot");
            return;
        }
        if (!CALLBACK_RETRY_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        int attempt = CALLBACK_RETRY_COUNT.incrementAndGet();
        try {
            Handler handler = getOptimizerHandler(optimizer);
            long delay = attempt * 5_000L;
            boolean posted = handler.postDelayed(() -> {
                CALLBACK_RETRY_SCHEDULED.set(false);
                if (optimizer == optimizerInstance && !CALLBACK_REGISTERED.get()) {
                    ensureVpnCallback(optimizer);
                }
            }, delay);
            if (!posted) {
                CALLBACK_RETRY_SCHEDULED.set(false);
                Log.e(TAG, "Optimizer handler rejected VPN callback retry");
            }
        } catch (Throwable error) {
            CALLBACK_RETRY_SCHEDULED.set(false);
            Log.e(TAG, "Unable to schedule VPN callback retry", error);
        }
    }

    private static void handleVpnAvailable(Object optimizer, String reason) {
        if (optimizer != optimizerInstance) {
            return;
        }
        PolicySnapshot before = cachedSnapshot;
        markVpnSeen();
        PolicySnapshot after = refreshSnapshot(optimizer);
        if (after.shouldNeutralize() && (!before.vpnActive || before.inspectionFailed)) {
            requestClear(optimizer, reason);
        }
    }

    private static void handleVpnLost(Object optimizer) {
        if (optimizer != optimizerInstance) {
            return;
        }
        markVpnSeen();
        PolicySnapshot snapshot = refreshSnapshot(optimizer);
        if (!snapshot.vpnActive) {
            scheduleOemResume(optimizer, VPN_LOSS_GRACE_MS + 1_000L);
        }
    }

    private static void scheduleOemResume(Object optimizer, long delayMs) {
        if (!OEM_RESUME_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        try {
            Handler handler = getOptimizerHandler(optimizer);
            boolean posted = handler.postDelayed(() -> {
                OEM_RESUME_SCHEDULED.set(false);
                if (optimizer != optimizerInstance) {
                    return;
                }
                try {
                    // Re-enter the OEM decision point. Its hook refreshes state and still blocks
                    // if a VPN returned, latchsky is off, or the grace period remains active.
                    XposedHelpers.callMethod(optimizer, "updateCheckStrategy");
                } catch (Throwable error) {
                    publishSnapshot(PolicySnapshot.failedOpen());
                    Log.e(TAG, "Unable to resume OEM strategy after VPN grace", error);
                    requestClear(optimizer, "resume-fail-open");
                }
            }, delayMs);
            if (!posted) {
                OEM_RESUME_SCHEDULED.set(false);
                Log.e(TAG, "Optimizer handler rejected OEM strategy resume");
            }
        } catch (Throwable error) {
            OEM_RESUME_SCHEDULED.set(false);
            Log.e(TAG, "Unable to schedule OEM strategy resume", error);
        }
    }

    private static void markVpnSeen() {
        lastVpnSeenElapsed = SystemClock.elapsedRealtime();
    }

    private static PolicySnapshot refreshSnapshot(Object optimizer) {
        try {
            Context context = getOptimizerContext(optimizer);
            boolean latchskyEnabled = Settings.Global.getInt(
                    context.getContentResolver(), "latchsky_enable", 1) != 0;
            boolean optimizerDisabled = XposedHelpers.getBooleanField(
                    optimizer, "disableOptimizer");
            boolean oemGmsAllowed = XposedHelpers.getBooleanField(
                    optimizer, "mGmsAllowed");
            boolean vpnActive = isVpnActive(context);
            if (vpnActive) {
                markVpnSeen();
            }
            long lastSeen = lastVpnSeenElapsed;
            long age = lastSeen == 0L
                    ? Long.MAX_VALUE
                    : SystemClock.elapsedRealtime() - lastSeen;
            boolean vpnGraceActive = age >= 0L && age <= VPN_LOSS_GRACE_MS;
            PolicySnapshot snapshot = new PolicySnapshot(
                    latchskyEnabled,
                    vpnActive,
                    optimizerDisabled,
                    oemGmsAllowed,
                    vpnGraceActive,
                    false);
            PolicySnapshot previous = cachedSnapshot;
            publishSnapshot(snapshot);
            if (optimizer == optimizerInstance
                    && previous.vpnActive
                    && !snapshot.vpnActive) {
                scheduleOemResume(optimizer, VPN_LOSS_GRACE_MS + 1_000L);
            }
            return snapshot;
        } catch (Throwable error) {
            Log.e(TAG, "Policy refresh failed", error);
            PolicySnapshot snapshot = PolicySnapshot.failedOpen();
            publishSnapshot(snapshot);
            return snapshot;
        }
    }

    private static void publishSnapshot(PolicySnapshot snapshot) {
        PolicySnapshot previous = cachedSnapshot;
        cachedSnapshot = snapshot;
        if (previous.vpnActive != snapshot.vpnActive
                || previous.shouldNeutralize() != snapshot.shouldNeutralize()) {
            Log.i(TAG, "Policy state: vpn=" + snapshot.vpnActive
                    + " bypass=" + snapshot.shouldNeutralize()
                    + " reason=" + snapshot.reason());
        }
    }

    private static boolean shouldBypassNow(PolicySnapshot snapshot) {
        return snapshot.shouldBypassRuntime(CALLBACK_REGISTERED.get());
    }

    private static String effectiveReason(PolicySnapshot snapshot) {
        return CALLBACK_REGISTERED.get() ? snapshot.reason() : "callback-unhealthy";
    }

    @SuppressLint("MissingPermission") // Runs only inside UID 1000 system_server.
    private static boolean isVpnActive(Context context) {
        ConnectivityManager connectivity =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            throw new IllegalStateException("ConnectivityManager unavailable");
        }

        Network[] networks = connectivity.getAllNetworks();
        if (networks == null) {
            throw new IllegalStateException("getAllNetworks returned null");
        }

        for (Network network : networks) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    private static Context getOptimizerContext(Object optimizer) {
        return (Context) XposedHelpers.getObjectField(optimizer, "mContext");
    }

    private static Handler getOptimizerHandler(Object optimizer) {
        return (Handler) XposedHelpers.getObjectField(optimizer, "mHandler");
    }

    private static void requestClear(Object optimizer, String reason) {
        try {
            Handler handler = getOptimizerHandler(optimizer);
            handler.removeMessages(MSG_CHECK_GOOGLE);
            if (!CLEAR_PENDING.compareAndSet(false, true)) {
                return;
            }
            if (Looper.myLooper() == handler.getLooper()) {
                try {
                    runClearOnOptimizerHandler(optimizer, reason);
                } finally {
                    CLEAR_PENDING.set(false);
                }
                return;
            }
            boolean posted = handler.post(() -> {
                try {
                    runClearOnOptimizerHandler(optimizer, reason);
                } finally {
                    CLEAR_PENDING.set(false);
                }
            });
            if (!posted) {
                CLEAR_PENDING.set(false);
                Log.e(TAG, "Optimizer handler rejected clear request: " + reason);
            }
        } catch (Throwable error) {
            CLEAR_PENDING.set(false);
            Log.e(TAG, "Unable to request OEM GMS clear: " + reason, error);
        }
    }

    private static void runClearOnOptimizerHandler(Object optimizer, String reason) {
        try {
            Handler handler = getOptimizerHandler(optimizer);
            if (Looper.myLooper() != handler.getLooper()) {
                throw new IllegalStateException("Clear attempted outside optimizer handler");
            }
            handler.removeMessages(MSG_CHECK_GOOGLE);
            Method method = optimizeMethod;
            if (method == null) {
                throw new IllegalStateException("Audited optimize method unavailable");
            }
            XposedBridge.invokeOriginalMethod(method, optimizer, new Object[]{OPTIMIZE_CLEAR});
            Log.i(TAG, "OEM GMS block cleared: " + reason);
        } catch (Throwable error) {
            Log.e(TAG, "OEM GMS clear failed: " + reason, error);
        }
    }

    private static final class PolicySnapshot {
        final boolean latchskyEnabled;
        final boolean vpnActive;
        final boolean optimizerDisabled;
        final boolean oemGmsAllowed;
        final boolean vpnGraceActive;
        final boolean inspectionFailed;

        PolicySnapshot(
                boolean latchskyEnabled,
                boolean vpnActive,
                boolean optimizerDisabled,
                boolean oemGmsAllowed,
                boolean vpnGraceActive,
                boolean inspectionFailed) {
            this.latchskyEnabled = latchskyEnabled;
            this.vpnActive = vpnActive;
            this.optimizerDisabled = optimizerDisabled;
            this.oemGmsAllowed = oemGmsAllowed;
            this.vpnGraceActive = vpnGraceActive;
            this.inspectionFailed = inspectionFailed;
        }

        static PolicySnapshot failedOpen() {
            return new PolicySnapshot(true, false, false, false, false, true);
        }

        boolean shouldNeutralize() {
            return GuardPolicy.shouldNeutralize(
                    latchskyEnabled,
                    vpnActive,
                    optimizerDisabled,
                    oemGmsAllowed,
                    vpnGraceActive,
                    inspectionFailed);
        }

        boolean shouldBypassRuntime(boolean callbackHealthy) {
            return GuardPolicy.shouldBypassRuntime(
                    callbackHealthy,
                    latchskyEnabled,
                    vpnActive,
                    optimizerDisabled,
                    oemGmsAllowed,
                    vpnGraceActive,
                    inspectionFailed);
        }

        boolean shouldForceGmsAllowed() {
            return GuardPolicy.shouldForceGmsAllowed(
                    latchskyEnabled,
                    vpnActive,
                    optimizerDisabled,
                    vpnGraceActive,
                    inspectionFailed);
        }

        boolean shouldForceGmsAllowedRuntime(boolean callbackHealthy) {
            return GuardPolicy.shouldForceGmsAllowedRuntime(
                    callbackHealthy,
                    latchskyEnabled,
                    vpnActive,
                    optimizerDisabled,
                    vpnGraceActive,
                    inspectionFailed);
        }

        String reason() {
            if (inspectionFailed) {
                return "inspection-failed";
            }
            if (!latchskyEnabled) {
                return "setting-disabled";
            }
            if (vpnActive) {
                return "vpn-active";
            }
            if (optimizerDisabled) {
                return "optimizer-disabled";
            }
            if (oemGmsAllowed) {
                return "oem-gms-allowed";
            }
            if (vpnGraceActive) {
                return "vpn-loss-grace";
            }
            return "oem-policy";
        }
    }
}
