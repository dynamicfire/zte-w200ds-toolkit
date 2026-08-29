package io.github.dynamicfire.zte.installerfix;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Firmware-specific installer repair for the ZTE W200DS Android 13 build.
 *
 * <p>The vendor installer still contains the AOSP/CTS confirmation activity,
 * but normally selects it only for callers whose package name contains
 * ".cts". The same activity is also assigned a full-screen install theme in
 * the vendor manifest. These narrow hooks restore the hidden route, bypass the
 * firmware's broken optional scan, and apply the package's own dialog-alert
 * theme before AlertActivity creates its window.</p>
 */
public final class InstallerFixHook implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.android.packageinstaller";
    private static final String TAG = "ZteInstallerFix";
    private static final String CTS_MARKER = ".cts";
    private static final String INSTALL_START =
            "com.android.packageinstaller.InstallStart";
    private static final String INSTALL_STAGING =
            "com.android.packageinstaller.InstallStaging";
    private static final String INSTALL_INSTALLING =
            "com.android.packageinstaller.InstallInstalling";
    private static final String INSTALL_SCANNING =
            "com.android.packageinstaller.InstallScanning";
    private static final String INSTALL_SUCCESS =
            "com.android.packageinstaller.InstallSuccess";
    private static final String CTS_INSTALLER =
            "com.android.packageinstaller.CtsPackageInstallerActivity";
    private static final String DELETE_STAGED_FILE =
            "com.android.packageinstaller.DeleteStagedFileOnResult";
    private static final String PACKAGE_UTIL =
            "com.android.packageinstaller.PackageUtil";
    private static final String SETTINGS_PREFS = "setting_sp";
    private static final String DELETE_SOURCE_APK_KEY = "key_del_pkg";
    private static final float DIALOG_DIM_AMOUNT = 0.30f;
    private static final int SUCCESS_DIALOG_MAX_WIDTH_DP = 560;
    private static final int SUCCESS_DIALOG_MARGIN_DP = 48;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        try {
            hookCtsRoute(lpparam.classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register CTS routing hook", error);
        }

        try {
            hookVendorScanRoute();
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register vendor-scan routing hook", error);
        }

        try {
            hookDialogTheme(lpparam.classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register dialog-theme hook", error);
        }

        try {
            hookSourceApkDeletionGuard(lpparam.classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register source-APK deletion guard", error);
        }

        try {
            hookSuccessDialog(lpparam.classLoader);
        } catch (Throwable error) {
            Log.e(TAG, "Unable to register success-dialog hook", error);
        }
    }

    /**
     * Skip the firmware's optional full-screen InstallScanning activity before
     * it can bind the missing HeartyService components or query the Nubia app
     * store. The replacement destinations mirror routes already implemented by
     * this exact firmware.
     */
    private static void hookVendorScanRoute() {
        XposedHelpers.findAndHookMethod(
                "android.app.Activity",
                null,
                "startActivity",
                Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null
                                    || param.args.length == 0
                                    || !(param.thisObject instanceof Activity)
                                    || !(param.args[0] instanceof Intent)) {
                                return;
                            }

                            Activity source = (Activity) param.thisObject;
                            Intent intent = (Intent) param.args[0];
                            ComponentName component = intent.getComponent();
                            if (component == null
                                    || !TARGET_PACKAGE.equals(component.getPackageName())
                                    || !INSTALL_SCANNING.equals(component.getClassName())) {
                                return;
                            }

                            String sourceClass = source.getClass().getName();
                            if (INSTALL_START.equals(sourceClass)) {
                                // This is identical to InstallStart's own CTS
                                // branch: keep the same Intent, data, extras,
                                // flags and RETURN_RESULT contract.
                                intent.setClassName(TARGET_PACKAGE, CTS_INSTALLER);
                                Log.i(TAG, "Skipped vendor scan for direct install route");
                            } else if (INSTALL_STAGING.equals(sourceClass)) {
                                // Content-URI installs own a staged copy. Keep
                                // the firmware wrapper so it deletes that copy
                                // and forwards the final result to the caller.
                                intent.setClassName(TARGET_PACKAGE, DELETE_STAGED_FILE);
                                intent.putExtra("isCtsInstall", true);
                                Log.i(TAG, "Skipped vendor scan while preserving staged cleanup");
                            }
                        } catch (Throwable error) {
                            // Fail open: if this firmware changes, leave its
                            // original destination untouched.
                            Log.e(TAG, "Vendor-scan routing hook failed", error);
                        }
                    }
                });
    }

    private static void hookCtsRoute(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                INSTALL_START,
                classLoader,
                "getCallingPackageNameForUid",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object result = param.getResult();
                            if (!(result instanceof String)) {
                                return;
                            }

                            String packageName = (String) result;
                            if (!packageName.contains(CTS_MARKER)) {
                                // This private helper's result is consumed only by
                                // InstallStart's CTS routing check on this firmware.
                                param.setResult(packageName + CTS_MARKER);
                                Log.i(TAG, "Routed installer confirmation through CTS UI");
                            }
                        } catch (Throwable error) {
                            // Fail open: leave the vendor route untouched.
                            Log.e(TAG, "CTS routing hook failed", error);
                        }
                    }
                });
    }

    private static void hookDialogTheme(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                CTS_INSTALLER,
                classLoader,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.thisObject;
                            persistSourceApkDeletionDisabled(activity);
                            applyDialogTheme(activity, "confirmation");
                        } catch (Throwable error) {
                            // Fail open: retain the manifest-selected vendor theme.
                            Log.e(TAG, "Dialog-theme hook failed", error);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.thisObject;
                            applyDialogWindow(activity, false, "confirmation");
                        } catch (Throwable error) {
                            // Fail open: retain the theme-selected window state.
                            Log.e(TAG, "Dialog-window hook failed", error);
                        }
                    }
                });
    }

    /**
     * Disable the vendor's misleading "delete installer and leftovers" feature.
     * The firmware implementation only calls File.delete() on one source APK;
     * it neither scans leftovers nor verifies that deletion succeeded. The
     * separate DeleteStagedFileOnResult path remains untouched.
     */
    private static void hookSourceApkDeletionGuard(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                PACKAGE_UTIL,
                classLoader,
                "getSettingSwitchSP",
                Context.class,
                String.class,
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null
                                && param.args.length >= 2
                                && DELETE_SOURCE_APK_KEY.equals(param.args[1])) {
                            param.setResult(false);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                PACKAGE_UTIL,
                classLoader,
                "setSettingSwitchSP",
                Context.class,
                String.class,
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null
                                && param.args.length >= 3
                                && DELETE_SOURCE_APK_KEY.equals(param.args[1])) {
                            param.args[2] = false;
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(
                INSTALL_INSTALLING,
                classLoader,
                "delSourcePkgA",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Skip only the vendor source-file deletion routine.
                        // Staged-copy cleanup lives in another Activity and is
                        // deliberately preserved.
                        param.setResult(null);
                        Log.i(TAG, "Blocked vendor source APK deletion");
                    }
                });
    }

    private static void hookSuccessDialog(ClassLoader classLoader) {
        XposedHelpers.findAndHookMethod(
                INSTALL_SUCCESS,
                classLoader,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.thisObject;
                            persistSourceApkDeletionDisabled(activity);
                            applyDialogTheme(activity, "success");
                        } catch (Throwable error) {
                            Log.e(TAG, "Success-theme hook failed", error);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity activity = (Activity) param.thisObject;
                            if (activity.isFinishing()) {
                                return;
                            }
                            int detailId = activity.getResources().getIdentifier(
                                    "del_detail",
                                    "id",
                                    TARGET_PACKAGE);
                            if (detailId != 0) {
                                View detail = activity.findViewById(detailId);
                                if (detail != null) {
                                    detail.setVisibility(View.GONE);
                                    Log.i(TAG, "Hidden misleading source-deletion result");
                                }
                            }
                            applyDialogWindow(activity, true, "success");
                        } catch (Throwable error) {
                            Log.e(TAG, "Success-window hook failed", error);
                        }
                    }
                });
    }

    private static void persistSourceApkDeletionDisabled(Activity activity) {
        SharedPreferences preferences = activity.getSharedPreferences(
                SETTINGS_PREFS,
                Context.MODE_PRIVATE);
        if (!preferences.getBoolean(DELETE_SOURCE_APK_KEY, false)) {
            return;
        }

        boolean committed = preferences.edit()
                .putBoolean(DELETE_SOURCE_APK_KEY, false)
                .commit();
        Log.i(TAG, "Persisted source APK deletion disabled: " + committed);
    }

    private static void applyDialogTheme(Activity activity, String screen) {
        Resources resources = activity.getResources();
        int themeId = resources.getIdentifier(
                "PackageInstallerTheme.Dialog.Alert.Activity",
                "style",
                TARGET_PACKAGE);

        if (themeId == 0) {
            themeId = resources.getIdentifier(
                    "PackageInstallerTheme.Dialog",
                    "style",
                    TARGET_PACKAGE);
        }

        if (themeId != 0) {
            activity.setTheme(themeId);
            Log.i(TAG, "Applied package-installer dialog theme to " + screen);
        } else {
            Log.e(TAG, "No compatible package-installer dialog theme found for " + screen);
        }
    }

    private static void applyDialogWindow(
            Activity activity,
            boolean constrainWidth,
            String screen) {
        Window window = activity.getWindow();
        int backgroundId = activity.getResources().getIdentifier(
                "dialog_background_material",
                "drawable",
                TARGET_PACKAGE);
        if (backgroundId != 0) {
            // The vendor Alert theme uses a transparent window background. Its
            // installer screens do not consistently paint an alert panel, so
            // restore the package's own rounded day/night-aware background.
            window.setBackgroundDrawableResource(backgroundId);
            window.getDecorView().setClipToOutline(true);
            Log.i(TAG, "Applied package-installer dialog background to " + screen);
        } else {
            Log.e(TAG, "No compatible dialog background found for " + screen);
        }

        // ActivityTaskManager decides whether an Activity occludes its parent
        // from the manifest theme before this hook swaps in a floating theme.
        // Synchronize the server-side ActivityRecord so the caller remains
        // visible through the translucent area.
        try {
            Object converted = XposedHelpers.callMethod(
                    activity,
                    "convertToTranslucent",
                    (Object) null,
                    (Object) null);
            Log.i(TAG, "Converted " + screen + " Activity to translucent: " + converted);
        } catch (Throwable error) {
            // Keep applying the local floating-window size and dim even if a
            // future firmware removes this hidden platform method.
            Log.e(TAG, "Unable to convert " + screen + " Activity to translucent", error);
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = DIALOG_DIM_AMOUNT;
        window.setAttributes(attributes);

        if (constrainWidth) {
            float density = activity.getResources().getDisplayMetrics().density;
            int displayWidth = activity.getResources().getDisplayMetrics().widthPixels;
            int desiredWidth = Math.round(SUCCESS_DIALOG_MAX_WIDTH_DP * density);
            int margin = Math.round(SUCCESS_DIALOG_MARGIN_DP * density);
            int availableWidth = Math.max(1, displayWidth - (margin * 2));
            window.setGravity(Gravity.CENTER);
            window.setLayout(
                    Math.min(desiredWidth, availableWidth),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }

        Log.i(TAG, "Applied " + screen + " dialog window with dim amount: "
                + DIALOG_DIM_AMOUNT);
    }
}
