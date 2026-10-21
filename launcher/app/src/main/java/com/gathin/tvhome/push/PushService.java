package com.gathin.tvhome.push;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.gathin.tvhome.R;
import com.gathin.tvhome.UserPrefs;

/** Keeps the LAN push HTTP server alive while the launcher process runs. */
public final class PushService extends Service {
    private static final String CHANNEL = "push_http";
    private static final int NOTI_ID = 42;

    public static void ensureStarted(Context ctx) {
        if (!UserPrefs.pushEnabled(ctx)) return;
        Intent i = new Intent(ctx, PushService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.push_channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.push_channel_desc));
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle(getString(R.string.push_noti_title))
                .setContentText(getString(R.string.push_noti_text))
                .setSmallIcon(R.drawable.ic_settings)
                .setOngoing(true);
        startForeground(NOTI_ID, b.build());
        PushHttpServer.get(this).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!PushHttpServer.get(this).isRunning()) {
            PushHttpServer.get(this).start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        PushHttpServer.get(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
