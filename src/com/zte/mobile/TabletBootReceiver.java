package com.zte.mobile;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Restores the stock tablet HOME once Android has finished booting.
 *
 * The ZTE firmware persists USmart as the logical HOME when the device powers
 * off in cloud mode. Calling the exported stock provider keeps the vendor HOME
 * state machine authoritative; this receiver does not edit roles or input
 * mappings itself and does not start a resident service. It invokes the
 * bidirectional method at most once after confirming USmart. A delayed,
 * private alarm only finalizes the known MiFavor/USmart half-state left by the
 * vendor boot sequence. Opening the F9 chooser invalidates the complete boot
 * recovery session before the chooser renders anything.
 */
public final class TabletBootReceiver extends BroadcastReceiver {

    private static final String TAG = "ZteTabletBoot";
    private static final String ACTION_FINALIZE_TABLET_HOME =
            "com.zte.mobile.action.FINALIZE_TABLET_HOME";
    private static final String TABLET_PACKAGE = "com.zte.mifavor.launcher";
    private static final String USMART_PACKAGE = "com.zte.usmartlauncher";
    private static final Uri DEFAULT_HOME_URI =
            Uri.parse("content://com.zte.usmartlauncher.defaulthome");
    private static final String METHOD_SWITCH_MODE = "switch_mode";
    private static final String METHOD_SWITCH_PAD = "switch_pad";
    // The public SDK stub on some vendor toolchains omits the BOOT_COUNT
    // constant even though the Global setting itself is present on Android 13.
    private static final String SETTING_BOOT_COUNT = "boot_count";

    private static final String RECOVERY_PREFS = "boot_recovery";
    private static final String KEY_NEXT_SESSION = "next_session";
    private static final String KEY_ACTIVE_SESSION = "active_session";
    private static final String KEY_ACTIVE_BOOT_COUNT = "active_boot_count";
    private static final String KEY_PENDING_SESSION = "pending_session";
    private static final String KEY_CANCELLED_BOOT_COUNT = "cancelled_boot_count";
    private static final String EXTRA_RECOVERY_SESSION = "recovery_session";
    private static final String EXTRA_BOOT_COUNT = "boot_count";
    private static final Object RECOVERY_LOCK = new Object();
    private static final long NO_SESSION = -1L;
    private static final int UNKNOWN_BOOT_COUNT = -1;
    private static volatile boolean cancellationRequestedInProcess;
    private static int cancelledBootInProcess = Integer.MIN_VALUE;

    private static final long INITIAL_DELAY_MILLIS = 1000L;
    private static final int HOME_DISCOVERY_ATTEMPTS = 3;
    private static final long HOME_DISCOVERY_DELAY_MILLIS = 1500L;
    private static final long TABLET_FINALIZE_DELAY_MILLIS = 10000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        final boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        final boolean isFinalizer = ACTION_FINALIZE_TABLET_HOME.equals(action);
        if (!isBoot && !isFinalizer) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        final Context applicationContext = context.getApplicationContext();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (isBoot) {
                        BootSession session = beginBootSession(applicationContext);
                        if (session == null) {
                            return;
                        }
                        SystemClock.sleep(INITIAL_DELAY_MILLIS);
                        restoreTabletMode(applicationContext, session);
                    } else {
                        long session = intent.getLongExtra(
                                EXTRA_RECOVERY_SESSION, NO_SESSION);
                        int bootCount = intent.getIntExtra(
                                EXTRA_BOOT_COUNT, UNKNOWN_BOOT_COUNT);
                        finalizeTabletHome(applicationContext, session, bootCount);
                    }
                } finally {
                    pendingResult.finish();
                }
            }
        }, isBoot ? "zte-tablet-boot" : "zte-tablet-finalize");
        try {
            worker.start();
        } catch (RuntimeException ignored) {
            pendingResult.finish();
        }
    }

    private void restoreTabletMode(Context context, BootSession session) {
        for (int attempt = 0; attempt < HOME_DISCOVERY_ATTEMPTS; attempt++) {
            if (!isSessionActive(context, session)) {
                Log.i(TAG, "Boot recovery was cancelled before mode inspection");
                return;
            }

            String currentHome = readPersistedHome(context);
            if (TABLET_PACKAGE.equals(currentHome)) {
                String resolvedHome = resolveDefaultHomePackage(context);
                if (USMART_PACKAGE.equals(resolvedHome)) {
                    Log.i(TAG, "MiFavor persisted but USmart still resolves as HOME; "
                            + "scheduling guarded finalizer");
                } else {
                    Log.i(TAG, "MiFavor persisted; scheduling guarded post-boot "
                            + "verification for later vendor writes");
                }
                // This firmware can overwrite the preferred HOME after our
                // BOOT_COMPLETED receiver has already observed a stable state.
                // Always schedule the verification; the finalizer remains a
                // no-op unless the exact MiFavor/USmart half-state appears.
                scheduleTabletFinalizer(context, session);
                return;
            }

            if (USMART_PACKAGE.equals(currentHome)) {
                if (!isSessionActive(context, session)) {
                    Log.i(TAG, "Boot recovery was cancelled before full mode switch");
                    return;
                }

                Log.i(TAG, "USmart persisted; requesting one full mode switch");
                // switch_mode is a bidirectional toggle. Invoke it at most once:
                // repeating an ambiguous result could switch the device back to cloud.
                try {
                    context.getContentResolver().call(
                            DEFAULT_HOME_URI, METHOD_SWITCH_MODE, null, null);
                } catch (RuntimeException ignored) {
                    Log.w(TAG, "Full mode switch failed; finalizer will re-check state");
                }

                // scheduleTabletFinalizer re-checks the session while holding the
                // same lock used by the F9 chooser's cancellation gate.
                scheduleTabletFinalizer(context, session);
                return;
            }

            // A null/unknown value usually means Settings is still settling.
            if (attempt + 1 < HOME_DISCOVERY_ATTEMPTS) {
                SystemClock.sleep(HOME_DISCOVERY_DELAY_MILLIS);
            }
        }
        Log.w(TAG, "Persisted HOME stayed unknown; boot restore skipped");
        finishSession(context, session);
    }

    private void scheduleTabletFinalizer(Context context, BootSession session) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager unavailable; HOME finalizer not scheduled");
            finishSession(context, session);
            return;
        }

        Intent finalizerIntent = finalizerIntent(context)
                .putExtra(EXTRA_RECOVERY_SESSION, session.id)
                .putExtra(EXTRA_BOOT_COUNT, session.bootCount);
        PendingIntent finalizer = PendingIntent.getBroadcast(
                context,
                0,
                finalizerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int currentBootCount = readBootCount(context);
        synchronized (RECOVERY_LOCK) {
            SharedPreferences preferences = recoveryPreferences(context);
            if (!isSessionActiveLocked(
                    preferences, session, currentBootCount)) {
                Log.i(TAG, "Boot recovery was cancelled before finalizer scheduling");
                finalizer.cancel();
                return;
            }

            if (!preferences.edit()
                    .putLong(KEY_PENDING_SESSION, session.id)
                    .commit()) {
                Log.w(TAG, "Could not persist finalizer token; recovery stopped");
                finalizer.cancel();
                return;
            }

            // Keep alarm creation inside the cancellation lock. If F9 wins the
            // lock first this branch cannot arm; if it wins second it cancels the
            // already-created PendingIntent and clears the persisted session.
            try {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + TABLET_FINALIZE_DELAY_MILLIS,
                        finalizer);
            } catch (RuntimeException ignored) {
                preferences.edit()
                        .remove(KEY_PENDING_SESSION)
                        .remove(KEY_ACTIVE_SESSION)
                        .remove(KEY_ACTIVE_BOOT_COUNT)
                        .commit();
                finalizer.cancel();
                Log.w(TAG, "Alarm scheduling failed; recovery stopped");
                return;
            }
        }
        Log.i(TAG, "Guarded tablet HOME finalizer scheduled");
    }

    private void finalizeTabletHome(Context context, long sessionId, int bootCount) {
        int currentBootCount = readBootCount(context);
        synchronized (RECOVERY_LOCK) {
            SharedPreferences preferences = recoveryPreferences(context);
            BootSession session = new BootSession(sessionId, bootCount);
            if (!isSessionActiveLocked(
                    preferences, session, currentBootCount)
                    || preferences.getLong(KEY_PENDING_SESSION, NO_SESSION)
                            != sessionId) {
                Log.i(TAG, "Finalizer skipped because its boot session is stale");
                return;
            }

            String persistedHome = readPersistedHome(context);
            String resolvedHome = resolveDefaultHomePackage(context);
            boolean halfState = TABLET_PACKAGE.equals(persistedHome)
                    && USMART_PACKAGE.equals(resolvedHome);

            SharedPreferences.Editor consume = preferences.edit()
                    .remove(KEY_PENDING_SESSION)
                    .remove(KEY_ACTIVE_SESSION)
                    .remove(KEY_ACTIVE_BOOT_COUNT);
            if (!consume.commit()) {
                Log.w(TAG, "Could not consume finalizer token; recovery stopped");
                return;
            }

            // PcChooserActivity publishes this volatile flag before waiting for
            // RECOVERY_LOCK. Re-read both vendor states after the synchronous
            // token commit so an F9 transition cannot rely on stale values.
            boolean stillHalfState = TABLET_PACKAGE.equals(readPersistedHome(context))
                    && USMART_PACKAGE.equals(resolveDefaultHomePackage(context));
            if (cancellationRequestedInProcess || !halfState || !stillHalfState) {
                Log.i(TAG, "Finalizer skipped because HOME is no longer the guarded half-state");
                return;
            }

            try {
                // On this firmware switch_pad only updates the preferred HOME
                // and default_home setting; it does not start an Activity.
                context.getContentResolver().call(
                        DEFAULT_HOME_URI, METHOD_SWITCH_PAD, null, null);
                Log.i(TAG, "Tablet HOME finalizer requested");
            } catch (RuntimeException ignored) {
                Log.w(TAG, "Tablet HOME finalizer failed");
            }
        }
    }

    private static BootSession beginBootSession(Context context) {
        cancelFinalizerAlarm(context);
        int bootCount = readBootCount(context);
        if (bootCount == UNKNOWN_BOOT_COUNT) {
            Log.w(TAG, "BOOT_COUNT unavailable; automatic recovery skipped");
            return null;
        }

        synchronized (RECOVERY_LOCK) {
            SharedPreferences preferences = recoveryPreferences(context);
            if (cancellationRequestedInProcess
                    || cancelledBootInProcess == bootCount
                    || preferences.getInt(
                            KEY_CANCELLED_BOOT_COUNT, UNKNOWN_BOOT_COUNT)
                            == bootCount) {
                Log.i(TAG, "This boot was cancelled by an intentional F9 switch");
                return null;
            }

            long sessionId = preferences.getLong(KEY_NEXT_SESSION, 0L) + 1L;
            boolean stored = preferences.edit()
                    .putLong(KEY_NEXT_SESSION, sessionId)
                    .putLong(KEY_ACTIVE_SESSION, sessionId)
                    .putInt(KEY_ACTIVE_BOOT_COUNT, bootCount)
                    .remove(KEY_PENDING_SESSION)
                    .commit();
            if (!stored) {
                Log.w(TAG, "Could not persist boot session; automatic recovery skipped");
                return null;
            }
            return new BootSession(sessionId, bootCount);
        }
    }

    private static boolean isSessionActive(Context context, BootSession session) {
        int currentBootCount = readBootCount(context);
        synchronized (RECOVERY_LOCK) {
            return isSessionActiveLocked(
                    recoveryPreferences(context), session, currentBootCount);
        }
    }

    private static boolean isSessionActiveLocked(
            SharedPreferences preferences,
            BootSession session,
            int currentBootCount) {
        return session.id != NO_SESSION
                && session.bootCount != UNKNOWN_BOOT_COUNT
                && currentBootCount == session.bootCount
                && !cancellationRequestedInProcess
                && cancelledBootInProcess != session.bootCount
                && preferences.getInt(
                        KEY_CANCELLED_BOOT_COUNT, UNKNOWN_BOOT_COUNT)
                        != session.bootCount
                && preferences.getLong(KEY_ACTIVE_SESSION, NO_SESSION)
                        == session.id
                && preferences.getInt(
                        KEY_ACTIVE_BOOT_COUNT, UNKNOWN_BOOT_COUNT)
                        == session.bootCount;
    }

    private static void finishSession(Context context, BootSession session) {
        synchronized (RECOVERY_LOCK) {
            SharedPreferences preferences = recoveryPreferences(context);
            if (preferences.getLong(KEY_ACTIVE_SESSION, NO_SESSION)
                    != session.id) {
                return;
            }
            if (!preferences.edit()
                    .remove(KEY_PENDING_SESSION)
                    .remove(KEY_ACTIVE_SESSION)
                    .remove(KEY_ACTIVE_BOOT_COUNT)
                    .commit()) {
                Log.w(TAG, "Could not clear completed boot session");
            }
        }
    }

    private static String readPersistedHome(Context context) {
        try {
            return Settings.Secure.getString(
                    context.getContentResolver(), "default_home");
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String resolveDefaultHomePackage(Context context) {
        try {
            Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME);
            ResolveInfo resolved = context.getPackageManager().resolveActivity(
                    homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null) {
                return null;
            }
            return resolved.activityInfo.packageName;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int readBootCount(Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(),
                    SETTING_BOOT_COUNT,
                    UNKNOWN_BOOT_COUNT);
        } catch (RuntimeException ignored) {
            return UNKNOWN_BOOT_COUNT;
        }
    }

    /**
     * Called by the F9 chooser before it renders anything. This is a persistent
     * cancellation gate for the complete current-boot session, not merely an
     * attempt to cancel an alarm that may or may not exist yet.
     */
    public static void cancelPendingRecovery(Context context) {
        cancellationRequestedInProcess = true;
        Context applicationContext = context.getApplicationContext();
        int bootCount = readBootCount(applicationContext);
        synchronized (RECOVERY_LOCK) {
            cancelledBootInProcess = bootCount;
            SharedPreferences.Editor cancellation =
                    recoveryPreferences(applicationContext).edit()
                            .remove(KEY_PENDING_SESSION)
                            .remove(KEY_ACTIVE_SESSION)
                            .remove(KEY_ACTIVE_BOOT_COUNT);
            if (bootCount != UNKNOWN_BOOT_COUNT) {
                cancellation.putInt(KEY_CANCELLED_BOOT_COUNT, bootCount);
            }
            if (!cancellation.commit()) {
                Log.w(TAG, "Could not persist F9 recovery cancellation");
            }
        }
        cancelFinalizerAlarm(applicationContext);
    }

    private static void cancelFinalizerAlarm(Context context) {
        AlarmManager alarmManager =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent existing = PendingIntent.getBroadcast(
                context,
                0,
                finalizerIntent(context),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) {
            alarmManager.cancel(existing);
            existing.cancel();
        }
    }

    private static SharedPreferences recoveryPreferences(Context context) {
        return context.getSharedPreferences(RECOVERY_PREFS, Context.MODE_PRIVATE);
    }

    private static Intent finalizerIntent(Context context) {
        return new Intent(context, TabletBootReceiver.class)
                .setAction(ACTION_FINALIZE_TABLET_HOME);
    }

    private static final class BootSession {
        final long id;
        final int bootCount;

        BootSession(long id, int bootCount) {
            this.id = id;
            this.bootCount = bootCount;
        }
    }
}
