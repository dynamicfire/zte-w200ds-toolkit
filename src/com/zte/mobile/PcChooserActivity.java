package com.zte.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * F9(LAUNCHER_SWITCH) -> usmartlauncher(pc_switch_mode=0)
 *   -> 显式启动 com.zte.mobile/com.zte.mspice.ui.WelcomeActivity (本类别名)
 *   -> 此时框架已开启"云电脑/鼠标模式"。
 * 只显示用户勾选的固定列表；底部"编辑列表"可随时增删，无需重装。
 */
public class PcChooserActivity extends Activity {

    private static final String PREFS = "pcswitch";
    private static final String KEY = "pkgs";

    // 首次运行的默认项（装了才放进去）：内置云电脑 + Moonlight
    private static final String[] DEFAULT_PKGS = {
        "com.cmss.cloudcomputer.tablet",
        "cm.komect.aqb.android.cloudcomputerpad",
        "com.limelight"
    };

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        showChooser();
    }

    private List<String> loadSelection() {
        String s = prefs.getString(KEY, null);
        List<String> list = new ArrayList<>();
        if (s == null) {
            PackageManager pm = getPackageManager();
            for (String p : DEFAULT_PKGS)
                if (pm.getLaunchIntentForPackage(p) != null) list.add(p);
            saveSelection(list);
        } else {
            for (String p : s.split("\n")) if (!p.isEmpty()) list.add(p);
        }
        return list;
    }

    private void saveSelection(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String p : list) { if (sb.length() > 0) sb.append('\n'); sb.append(p); }
        prefs.edit().putString(KEY, sb.toString()).apply();
    }

    private void showChooser() {
        final PackageManager pm = getPackageManager();
        final List<String> sel = loadSelection();

        final List<String> labels = new ArrayList<>();
        final List<Intent> intents = new ArrayList<>();
        for (String pkg : sel) {
            Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li == null) continue;                 // 未安装/不可启动则跳过
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            labels.add(labelOf(pm, pkg));
            intents.add(li);
        }
        labels.add("⚙ 编辑列表…");

        AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle("进入 PC 模式 — 选择应用")
            .setItems(labels.toArray(new CharSequence[0]), new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (which >= intents.size()) {    // 最后一项 = 编辑
                        showEditor();
                        return;
                    }
                    try { startActivity(intents.get(which)); } catch (Exception e) { /* ignore */ }
                    finish();
                }
            })
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface d) { finish(); }
            })
            .create();
        dlg.show();
    }

    private void showEditor() {
        final PackageManager pm = getPackageManager();

        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (ResolveInfo ri : ris) set.add(ri.activityInfo.packageName);
        set.remove(getPackageName());
        set.remove("com.zte.usmartlauncher");
        final List<String> allPkgs = new ArrayList<>(set);
        Collections.sort(allPkgs, new Comparator<String>() {
            public int compare(String a, String c) {
                return labelOf(pm, a).compareToIgnoreCase(labelOf(pm, c));
            }
        });

        final List<String> sel = loadSelection();
        final boolean[] checked = new boolean[allPkgs.size()];
        final CharSequence[] names = new CharSequence[allPkgs.size()];
        for (int i = 0; i < allPkgs.size(); i++) {
            names[i] = labelOf(pm, allPkgs.get(i));
            checked[i] = sel.contains(allPkgs.get(i));
        }

        new AlertDialog.Builder(this)
            .setTitle("勾选要显示的应用")
            .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                public void onClick(DialogInterface d, int which, boolean isChecked) {
                    checked[which] = isChecked;
                }
            })
            .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    List<String> newSel = new ArrayList<>();
                    for (int i = 0; i < allPkgs.size(); i++)
                        if (checked[i]) newSel.add(allPkgs.get(i));
                    saveSelection(newSel);
                    showChooser();
                }
            })
            .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { showChooser(); }
            })
            .setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface d) { showChooser(); }
            })
            .show();
    }

    private String labelOf(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }
}
