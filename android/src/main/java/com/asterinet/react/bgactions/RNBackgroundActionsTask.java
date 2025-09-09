package com.asterinet.react.bgactions;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.jstasks.HeadlessJsTaskConfig;

final public class RNBackgroundActionsTask extends HeadlessJsTaskService {

    public static final int SERVICE_NOTIFICATION_ID = 92901;
    private static final String CHANNEL_ID = "RN_BACKGROUND_ACTIONS_CHANNEL";

    @SuppressLint("UnspecifiedImmutableFlag")
    @NonNull
    public static Notification buildNotification(@NonNull Context context, @NonNull final BackgroundTaskOptions bgOptions) {
        // Get info
        final String taskTitle = bgOptions.getTaskTitle();
        final String taskDesc = bgOptions.getTaskDesc();
        final int iconInt = bgOptions.getIconInt();
        final int color = bgOptions.getColor();
        final String linkingURI = bgOptions.getLinkingURI();
        Intent notificationIntent;
        if (linkingURI != null) {
            notificationIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(linkingURI));
        } else {
            //as RN works on single activity architecture - we don't need to find current activity on behalf of react context
            notificationIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        }
        final PendingIntent contentIntent;
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            contentIntent = PendingIntent.getActivity(context,0, notificationIntent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_MUTABLE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            contentIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(taskTitle)
                .setContentText(taskDesc)
                .setSmallIcon(iconInt)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setColor(color);

        final Bundle progressBarBundle = bgOptions.getProgressBar();
        if (progressBarBundle != null) {
            final int progressMax = (int) Math.floor(progressBarBundle.getDouble("max"));
            final int progressCurrent = (int) Math.floor(progressBarBundle.getDouble("value"));
            final boolean progressIndeterminate = progressBarBundle.getBoolean("indeterminate");
            builder.setProgress(progressMax, progressCurrent, progressIndeterminate);
        }
        return builder.build();
    }

    @Override
    protected @Nullable
    HeadlessJsTaskConfig getTaskConfig(Intent intent) {
        final Bundle extras = intent.getExtras();
        if (extras != null) {
            return new HeadlessJsTaskConfig(extras.getString("taskName"), Arguments.fromBundle(extras), 0, true);
        }
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final Bundle extras = intent.getExtras();
        if (extras == null) {
            throw new IllegalArgumentException("Extras cannot be null");
        }
        final BackgroundTaskOptions bgOptions = new BackgroundTaskOptions(extras);
        createNotificationChannel(bgOptions.getTaskTitle(), bgOptions.getTaskDesc()); // Necessary creating channel for API 26+
        // Create the notification
        final Notification notification = buildNotification(this, bgOptions);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+): Check required permissions based on foregroundServiceType
            // This prevents SecurityException when permissions are revoked while service is running
            if (!hasRequiredPermissions()) {
                Log.e("RNBackgroundActionsTask", "Required permissions not granted for foreground service! Stopping.");
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        startForeground(SERVICE_NOTIFICATION_ID, notification);
        return super.onStartCommand(intent, flags, startId);
    }

    private void createNotificationChannel(@NonNull final String taskTitle, @NonNull final String taskDesc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            final int importance = NotificationManager.IMPORTANCE_LOW;
            final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, taskTitle, importance);
            channel.setDescription(taskDesc);
            final NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Check if required permissions are granted for location foreground service
     * @return true if all required permissions are granted, false otherwise
     */
    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true; // No permission check needed for Android < 10
        }

        // Only check permissions if this is a location foreground service
        if (!isLocationForegroundService()) {
            return true; // Not a location service, no location permissions required
        }

        // Check location permissions
        boolean hasFineLocation = ContextCompat.checkSelfPermission(this, 
            android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasCoarseLocation = ContextCompat.checkSelfPermission(this, 
            android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasBackgroundLocation = ContextCompat.checkSelfPermission(this, 
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;

        boolean allPermissionsGranted = hasFineLocation && hasCoarseLocation && hasBackgroundLocation;

        return allPermissionsGranted;
    }

    /**
     * Check if this service is configured as a location foreground service
     * @return true if foregroundServiceType includes location, false otherwise
     */
    private boolean isLocationForegroundService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }

        try {
            PackageManager pm = getPackageManager();
            ComponentName componentName = new ComponentName(this, this.getClass());
            ServiceInfo serviceInfo = pm.getServiceInfo(componentName, PackageManager.GET_META_DATA);
            
            // Use reflection to access foregroundServiceType field (available from API 29+)
            try {
                int serviceType = (Integer) ServiceInfo.class.getField("foregroundServiceType").get(serviceInfo);
                int FOREGROUND_SERVICE_TYPE_LOCATION = (Integer) ServiceInfo.class.getField("FOREGROUND_SERVICE_TYPE_LOCATION").get(null);
                
                boolean isLocation = (serviceType & FOREGROUND_SERVICE_TYPE_LOCATION) != 0;
                Log.d("RNBackgroundActionsTask", "Service type check - isLocation: " + isLocation);
                return isLocation;
            } catch (Exception e) {
                Log.w("RNBackgroundActionsTask", "Could not read foregroundServiceType field", e);
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w("RNBackgroundActionsTask", "Could not find service info", e);
            return false;
        }
    }
}
