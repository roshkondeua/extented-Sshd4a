package com.hardbacknutter.sshd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.hardbacknutter.sshd.settings.Prefs;

/**
 * Handle start-on-boot.
 */
public class BootReceiver
        extends BroadcastReceiver {

    public void onReceive(@NonNull final Context context,
                          @NonNull final Intent intent) {

        final String action = intent.getAction();

        // After an app update the scripts in /data/local/tmp/Sshd4a/bin (pkg, sshd4a, ...)
        // are the OLD ones until provisioning runs again, which normally only happens when
        // the service starts. Do it right away so an update takes effect without a manual
        // service restart - unless start-on-boot is on: that path starts the service, and
        // the service provisions.
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) && !Prefs.isStartOnBoot(context)) {
            final PendingResult pending = goAsync();
            final Context appContext = context.getApplicationContext();
            new Thread(() -> {
                try {
                    FileLogger.log(appContext, "BootReceiver", "package replaced: provisioning");
                    RootProvisioner.provisionIfNeeded(appContext);
                } finally {
                    pending.finish();
                }
            }, "PostUpdateProvisioning").start();
            return;
        }

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            if (Prefs.isStartOnBoot(context)) {
                SshdService.startService(context, StartMode.OnBoot);
            }
        }
    }
}
