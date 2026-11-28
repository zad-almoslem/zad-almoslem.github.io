package com.zad.almoslem;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

// Keeps the next-prayer status visible without a home-screen widget.
public final class PrayerStatusNotification {
    private static final String CHANNEL_ID = "prayer_status_channel";
    private static final int NOTIFICATION_ID = 74120;
    private static final int ALARM_REQUEST = 74121;
    private static final String ACTION_UPDATE = "com.zad.almoslem.UPDATE_PRAYER_STATUS";

    private PrayerStatusNotification() {}

    public static void start(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("zad_reminders", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("prayer_status_notification_enabled", false)) return;
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        createChannel(context);
        update(context);
        schedule(context);
    }

    public static void refreshIfEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("zad_reminders", Context.MODE_PRIVATE);
        if (prefs.getBoolean("prayer_status_notification_enabled", false)) start(context);
    }

    public static void stop(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) alarm.cancel(alarmPendingIntent(context));
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    static void update(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("zad_reminders", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("prayer_status_notification_enabled", false)) {
            stop(context);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) return;

        String[] names = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};
        String zoneId = prefs.getString("native_city_timezone", "");
        if (zoneId.isEmpty() && "Egypt".equals(prefs.getString("native_country", "Egypt"))) zoneId = "Africa/Cairo";
        if (zoneId.isEmpty()) zoneId = TimeZone.getDefault().getID();
        TimeZone zone = TimeZone.getTimeZone(zoneId);
        long now = System.currentTimeMillis();
        long nextAt = Long.MAX_VALUE;
        String nextName = null;
        for (String name : names) {
            int hour = prefs.getInt("native_time_hour_" + name, -1);
            int minute = prefs.getInt("native_time_minute_" + name, -1);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) continue;
            Calendar candidate = Calendar.getInstance(zone);
            candidate.setTimeInMillis(now);
            candidate.set(Calendar.HOUR_OF_DAY, hour);
            candidate.set(Calendar.MINUTE, minute);
            candidate.set(Calendar.SECOND, 0);
            candidate.set(Calendar.MILLISECOND, 0);
            if (candidate.getTimeInMillis() <= now) candidate.add(Calendar.DAY_OF_YEAR, 1);
            if (candidate.getTimeInMillis() < nextAt) {
                nextAt = candidate.getTimeInMillis();
                nextName = name;
            }
        }

        String title;
        String detail;
        if (nextName == null) {
            title = "سكينة | مواقيت الصلاة";
            detail = "حدّث مواقيت الصلاة لعرض الصلاة القادمة";
        } else {
            long remainingMinutes = Math.max(0L, (nextAt - now + 59999L) / 60000L);
            long hours = remainingMinutes / 60L;
            long minutes = remainingMinutes % 60L;
            String remaining;
            if (remainingMinutes == 0) remaining = "حان وقتها الآن";
            else if (hours > 0) remaining = "باقي " + hours + " س و " + minutes + " د";
            else remaining = "باقي " + minutes + " دقيقة";
            title = "الصلاة القادمة: " + nextName;
            detail = remaining;
            String city = prefs.getString("native_prayer_city", "");
            if (!city.isEmpty()) detail += "  •  " + city;
        }

        Intent open = new Intent(context, PrayerDashboardActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.ic_launcher_round)
                .setContentTitle(title)
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPriority(Notification.PRIORITY_LOW);
        builder.setSound(null).setDefaults(0);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "متابعة الصلاة القادمة", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("إشعار ثابت يعرض الصلاة القادمة والوقت المتبقي");
                channel.setShowBadge(false);
                channel.setSound(null, null);
                manager.createNotificationChannel(channel);
            }
        }
    }

    private static void schedule(Context context) {
        AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarm != null) alarm.setInexactRepeating(AlarmManager.RTC,
                System.currentTimeMillis() + 60000L, 60000L, alarmPendingIntent(context));
    }

    private static PendingIntent alarmPendingIntent(Context context) {
        Intent intent = new Intent(context, PrayerStatusNotificationReceiver.class).setAction(ACTION_UPDATE);
        return PendingIntent.getBroadcast(context, ALARM_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
