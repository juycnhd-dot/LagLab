package com.laglab.app;

import android.app.*;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;

public class LagService extends VpnService {
    static volatile boolean active;
    private ParcelFileDescriptor tun;
    private Engine engine;

    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (i == null || "stop".equals(i.getAction())) { shutdown(); stopSelf(); return START_NOT_STICKY; }
        shutdown();
        try {
            notifyRunning();
            Builder b = new Builder().setSession("LagLab").setMtu(1400).setBlocking(true)
                    .addAddress("10.111.0.2", 32).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1");
            ArrayList<String> apps = i.getStringArrayListExtra("apps");
            // An empty allow-list would capture every app, including this one.
            if (apps == null || apps.isEmpty()) throw new IllegalStateException("no apps");
            for (String app : apps) b.addAllowedApplication(app);
            tun = b.establish();
            if (tun == null) throw new IllegalStateException("VPN not permitted");
            FileOutputStream out = new FileOutputStream(tun.getFileDescriptor());
            engine = new Engine(p -> { try { out.write(p); } catch (IOException ignored) { } });
            engine.delayMs = i.getIntExtra("delay", 0);
            engine.lossPercent = i.getIntExtra("loss", 0);
            engine.block = i.getBooleanExtra("block", false);
            new Thread(engine, "engine").start();
            final Engine e = engine;
            final FileInputStream in = new FileInputStream(tun.getFileDescriptor());
            new Thread(() -> {
                byte[] buf = new byte[32767];
                try {
                    int n;
                    while ((n = in.read(buf)) >= 0) if (n > 0) e.input(Arrays.copyOf(buf, n));
                } catch (IOException ignored) { }
            }, "tun-reader").start();
            active = true;
        } catch (Exception ex) {
            android.widget.Toast.makeText(this, "চালু করা যায়নি: " + ex.getMessage(), android.widget.Toast.LENGTH_LONG).show();
            shutdown();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void notifyRunning() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder n;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("run", "Running", NotificationManager.IMPORTANCE_LOW));
            n = new Notification.Builder(this, "run");
        } else n = new Notification.Builder(this);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, LagService.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, n.setSmallIcon(R.drawable.icon).setContentTitle("LagLab চলছে")
                .setContentText("বন্ধ করতে এখানে চাপুন").setContentIntent(stop).build());
    }

    private void shutdown() {
        active = false;
        if (engine != null) engine.stop();
        engine = null;
        if (tun != null) try { tun.close(); } catch (IOException ignored) { }
        tun = null;
        stopForeground(true);
    }

    @Override public void onRevoke() { shutdown(); stopSelf(); }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
}
