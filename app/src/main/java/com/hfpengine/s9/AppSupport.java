package com.hfpengine.s9;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

final class AppSupport {
    static final String PREFS = "engine_prefs";
    static final String CHANNEL = "hfp_engine_status";
    private AppSupport() {}

    static Notification notification(Context ctx, String title, String detail) {
        NotificationManager manager = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "HFP audio service", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_service)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(pending)
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }
}
