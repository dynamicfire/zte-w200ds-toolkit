package com.zte.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ZTE F9 云模式应用选择器。
 *
 * 固件的 usmartlauncher 在 pc_switch_mode=0 时显式启动
 * com.zte.mobile/com.zte.mspice.ui.WelcomeActivity（Manifest 中的别名）。
 * 此时固件已经打开云电脑输入映射；本 Activity 只负责选择要启动的 App。
 */
public final class PcChooserActivity extends Activity {

    private static final String PREFS = "pcswitch";
    private static final String KEY_PACKAGES = "pkgs";

    private static final Uri DEFAULT_HOME_URI =
            Uri.parse("content://com.zte.usmartlauncher.defaulthome");
    private static final String METHOD_SWITCH_PAD = "switch_pad";

    // 首次运行时只加入确实已经安装且可启动的项目。
    private static final String[] DEFAULT_PACKAGES = {
        "com.cmss.cloudcomputer.tablet",
        "cm.komect.aqb.android.cloudcomputerpad",
        "com.limelight"
    };

    private SharedPreferences preferences;
    private boolean leavingForChosenApp;
    private boolean returningToTablet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TabletBootReceiver.cancelPendingRecovery(this);
        preferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        showChooser();
    }

    private List<String> loadSelection() {
        String stored = preferences.getString(KEY_PACKAGES, null);
        List<String> packages = new ArrayList<>();
        if (stored == null) {
            PackageManager packageManager = getPackageManager();
            for (String packageName : DEFAULT_PACKAGES) {
                if (packageManager.getLaunchIntentForPackage(packageName) != null) {
                    packages.add(packageName);
                }
            }
            saveSelection(packages);
        } else {
            for (String packageName : stored.split("\\n")) {
                if (!packageName.isEmpty()) {
                    packages.add(packageName);
                }
            }
        }
        return packages;
    }

    private void saveSelection(List<String> packages) {
        StringBuilder value = new StringBuilder();
        for (String packageName : packages) {
            if (value.length() > 0) {
                value.append('\n');
            }
            value.append(packageName);
        }
        preferences.edit().putString(KEY_PACKAGES, value.toString()).apply();
    }

    private void showChooser() {
        final PackageManager packageManager = getPackageManager();
        final List<String> selectedPackages = loadSelection();
        final List<Intent> launchIntents = new ArrayList<>();
        final List<CharSequence> rows = new ArrayList<>();

        for (String packageName : selectedPackages) {
            Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                continue;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            rows.add(displayName(packageManager, packageName));
            launchIntents.add(launchIntent);
        }

        rows.add("⚙ 编辑可选应用…");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("F9 云模式：选择要启动的应用")
                .setItems(rows.toArray(new CharSequence[0]),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface ignored, int which) {
                                if (which >= launchIntents.size()) {
                                    showEditor();
                                    return;
                                }
                                launchSelectedApp(launchIntents.get(which));
                            }
                        })
                .setNegativeButton("返回平板模式", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface ignored, int which) {
                        returnToTabletMode("已取消应用选择");
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface ignored) {
                        returnToTabletMode("已取消应用选择");
                    }
                })
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }

    private void launchSelectedApp(Intent launchIntent) {
        try {
            leavingForChosenApp = true;
            startActivity(launchIntent);
            finish();
        } catch (RuntimeException error) {
            leavingForChosenApp = false;
            returnToTabletMode("所选应用无法启动，已尝试恢复平板模式");
        }
    }

    /**
     * 使用原厂 DefaultHomeProvider 完成完整的平板模式恢复，而不仅仅启动桌面。
     * 这样默认 HOME 与云电脑输入映射都由原厂状态机复原。
     */
    private void returnToTabletMode(String message) {
        if (leavingForChosenApp || returningToTablet || isFinishing()) {
            return;
        }
        returningToTablet = true;
        try {
            getContentResolver().call(DEFAULT_HOME_URI, METHOD_SWITCH_PAD, null, null);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            finish();
        } catch (RuntimeException error) {
            returningToTablet = false;
            showReturnFailure(error);
        }
    }

    private void showReturnFailure(RuntimeException error) {
        String detail = error.getMessage();
        if (detail == null || detail.trim().isEmpty()) {
            detail = error.getClass().getSimpleName();
        }

        new AlertDialog.Builder(this)
                .setTitle("无法自动返回平板模式")
                .setMessage("调用中兴模式切换服务失败（" + detail
                        + "）。请再按一次 F9；如果仍未恢复，请重新打开选择器后点击“返回平板模式”。")
                .setPositiveButton("重试", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface ignored, int which) {
                        returnToTabletMode("已返回平板模式");
                    }
                })
                .setNegativeButton("返回应用列表", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface ignored, int which) {
                        showChooser();
                    }
                })
                .setCancelable(false)
                .show();
    }

    private void showEditor() {
        final PackageManager packageManager = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> activities = packageManager.queryIntentActivities(query, 0);
        LinkedHashSet<String> uniquePackages = new LinkedHashSet<>();
        for (ResolveInfo resolveInfo : activities) {
            uniquePackages.add(resolveInfo.activityInfo.packageName);
        }
        uniquePackages.remove(getPackageName());
        uniquePackages.remove("com.zte.usmartlauncher");

        final List<String> allPackages = new ArrayList<>(uniquePackages);
        Collections.sort(allPackages, new Comparator<String>() {
            @Override
            public int compare(String first, String second) {
                return appLabel(packageManager, first)
                        .compareToIgnoreCase(appLabel(packageManager, second));
            }
        });

        final List<String> selectedPackages = loadSelection();
        final boolean[] checked = new boolean[allPackages.size()];
        final CharSequence[] names = new CharSequence[allPackages.size()];
        for (int index = 0; index < allPackages.size(); index++) {
            String packageName = allPackages.get(index);
            names[index] = displayName(packageManager, packageName);
            checked[index] = selectedPackages.contains(packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("选择 F9 列表中的应用")
                .setMultiChoiceItems(names, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                            @Override
                            public void onClick(DialogInterface ignored, int which,
                                    boolean isChecked) {
                                checked[which] = isChecked;
                            }
                        })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface ignored, int which) {
                        List<String> newSelection = new ArrayList<>();
                        for (int index = 0; index < allPackages.size(); index++) {
                            if (checked[index]) {
                                newSelection.add(allPackages.get(index));
                            }
                        }
                        saveSelection(newSelection);
                        showChooser();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface ignored, int which) {
                        showChooser();
                    }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface ignored) {
                        showChooser();
                    }
                })
                .show();
    }

    private CharSequence displayName(PackageManager packageManager, String packageName) {
        return appLabel(packageManager, packageName) + "\n" + packageName;
    }

    private String appLabel(PackageManager packageManager, String packageName) {
        try {
            return packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(packageName, 0)).toString();
        } catch (PackageManager.NameNotFoundException error) {
            return packageName;
        }
    }
}
