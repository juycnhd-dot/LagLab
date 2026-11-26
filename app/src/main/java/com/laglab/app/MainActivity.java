package com.laglab.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.*;

public class MainActivity extends Activity {
    private final HashSet<String> selected = new HashSet<>();
    private SharedPreferences prefs;
    private SeekBar delay, loss;
    private CheckBox block;
    private Button apps, toggle;
    private TextView pingResult;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("s", MODE_PRIVATE);
        selected.addAll(prefs.getStringSet("apps", new HashSet<>()));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setFitsSystemWindows(true);

        text(root, "পিং টেস্ট", 20);
        EditText host = new EditText(this);
        host.setSingleLine();
        host.setText("google.com");
        root.addView(host);
        Button ping = button(root, "পিং মাপুন");
        pingResult = text(root, "", 16);
        ping.setOnClickListener(v -> ping(host.getText().toString().trim()));

        text(root, "খারাপ নেটওয়ার্ক", 20);
        delay = slider(root, "দেরি (প্রতি দিকে)", " ms", 2000, "delay");
        loss = slider(root, "প্যাকেট লস", " %", 100, "loss");
        block = new CheckBox(this);
        block.setText("পুরো ব্লক");
        root.addView(block);
        apps = button(root, "");
        apps.setOnClickListener(v -> pickApps());
        toggle = button(root, "");
        toggle.setOnClickListener(v -> {
            if (LagService.active) {
                startService(new Intent(this, LagService.class).setAction("stop"));
                LagService.active = false;
                refresh();
            } else if (selected.isEmpty()) {
                Toast.makeText(this, "আগে অ্যাপ বাছাই করুন", Toast.LENGTH_SHORT).show();
            } else {
                Intent ask = VpnService.prepare(this);
                if (ask != null) startActivityForResult(ask, 1); else start();
            }
        });
        text(root, "মান বদলালে বন্ধ করে আবার চালু করুন।", 13);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == 1 && result == RESULT_OK) start();
    }

    private void start() {
        prefs.edit().putInt("delay", delay.getProgress()).putInt("loss", loss.getProgress()).apply();
        Intent i = new Intent(this, LagService.class)
                .putStringArrayListExtra("apps", new ArrayList<>(selected))
                .putExtra("delay", delay.getProgress())
                .putExtra("loss", loss.getProgress())
                .putExtra("block", block.isChecked());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        toggle.postDelayed(this::refresh, 500);
    }

    private void refresh() {
        apps.setText("অ্যাপ বাছাই (" + selected.size() + ")");
        toggle.setText(LagService.active ? "বন্ধ করুন" : "চালু করুন");
    }

    private void pickApps() {
        PackageManager pm = getPackageManager();
        TreeMap<String, String> found = new TreeMap<>(); // "label\npackage" -> package
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(q, 0)) {
            String pkg = r.activityInfo.packageName;
            if (!pkg.equals(getPackageName())) found.put(r.loadLabel(pm) + "\n" + pkg, pkg);
        }
        String[] labels = found.keySet().toArray(new String[0]);
        String[] pkgs = found.values().toArray(new String[0]);
        selected.retainAll(Arrays.asList(pkgs)); // forget uninstalled apps
        boolean[] checked = new boolean[pkgs.length];
        for (int i = 0; i < pkgs.length; i++) checked[i] = selected.contains(pkgs[i]);
        new AlertDialog.Builder(this)
                .setMultiChoiceItems(labels, checked, (d, i, on) -> {
                    if (on) selected.add(pkgs[i]); else selected.remove(pkgs[i]);
                })
                .setPositiveButton("ঠিক আছে", null)
                .setOnDismissListener(d -> {
                    prefs.edit().putStringSet("apps", new HashSet<>(selected)).apply();
                    refresh();
                }).show();
    }

    /** Times five TCP connects to port 443. */
    private void ping(String host) {
        pingResult.setText("মাপা হচ্ছে...");
        new Thread(() -> {
            long total = 0, min = Long.MAX_VALUE, max = 0;
            int ok = 0;
            try {
                InetSocketAddress address = new InetSocketAddress(java.net.InetAddress.getByName(host), 443);
                for (int i = 0; i < 5; i++) {
                    try (Socket s = new Socket()) {
                        long t = System.nanoTime();
                        s.connect(address, 3000);
                        long ms = (System.nanoTime() - t) / 1_000_000;
                        total += ms; ok++;
                        min = Math.min(min, ms); max = Math.max(max, ms);
                    } catch (java.io.IOException ignored) { }
                }
            } catch (Exception ignored) { }
            String result = ok == 0 ? "সংযোগ হয়নি"
                    : "গড় " + total / ok + " ms (সর্বনিম্ন " + min + ", সর্বোচ্চ " + max + "), ব্যর্থ " + (5 - ok) + "/5";
            runOnUiThread(() -> pingResult.setText(result));
        }).start();
    }

    private TextView text(LinearLayout root, String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setPadding(0, 16, 0, 8);
        root.addView(t);
        return t;
    }

    private Button button(LinearLayout root, String s) {
        Button b = new Button(this);
        b.setText(s);
        root.addView(b);
        return b;
    }

    private SeekBar slider(LinearLayout root, String name, String unit, int max, String key) {
        TextView label = text(root, "", 15);
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int v, boolean user) { label.setText(name + ": " + v + unit); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        label.setText(name + ": 0" + unit);
        bar.setProgress(prefs.getInt(key, 0));
        root.addView(bar);
        return bar;
    }
}
