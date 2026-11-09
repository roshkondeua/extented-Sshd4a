package com.hardbacknutter.sshd;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hardbacknutter.sshd.settings.Prefs;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Logs why the app's previous process(es) died (Android 11+ exit history),
 * so a kill by the system / MIUI cleaner can be diagnosed afterwards without
 * being connected at the time. Written to the file log (Settings ->
 * Diagnostics); each record is logged once.
 */
final class ExitReasonLogger {

    private static final String TAG = "ExitReason";
    private static final String PREFS = "exit_reasons";
    private static final String KEY_LAST_TS = "last_ts";

    private ExitReasonLogger() {
    }

    static void logNew(@NonNull final Context context) {
        try {
            // only consume records when they can actually be written
            if (!Prefs.isFileLoggingEnabled(Prefs.getSharedPreferences(context))) {
                return;
            }
            final ActivityManager am =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return;
            }
            final List<ApplicationExitInfo> list =
                    am.getHistoricalProcessExitReasons(null, 0, 15);
            final SharedPreferences sp =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            final long last = sp.getLong(KEY_LAST_TS, 0);
            long max = last;
            final SimpleDateFormat ts =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            // newest first -> log oldest first
            for (int i = list.size() - 1; i >= 0; i--) {
                final ApplicationExitInfo info = list.get(i);
                if (info.getTimestamp() <= last) {
                    continue;
                }
                FileLogger.log(context, TAG,
                               "exit|" + ts.format(new Date(info.getTimestamp()))
                               + "|process=" + info.getProcessName()
                               + "|reason=" + name(info.getReason())
                               + "(" + info.getReason() + ")"
                               + "|status=" + info.getStatus()
                               + "|importance=" + info.getImportance()
                               + "|pssKb=" + info.getPss()
                               + "|rssKb=" + info.getRss()
                               + "|desc=" + info.getDescription());
                max = Math.max(max, info.getTimestamp());
            }
            if (max > last) {
                sp.edit().putLong(KEY_LAST_TS, max).apply();
            }
        } catch (@NonNull final Exception e) {
            Log.w(TAG, "logNew failed", e);
        }
    }

    @NonNull
    private static String name(final int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF:
                return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH:
                return "CRASH";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR:
                return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED:
                return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_USER_STOPPED:
                return "USER_STOPPED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED:
                return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER:
                return "OTHER";
            case ApplicationExitInfo.REASON_FREEZER:
                return "FREEZER";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE:
                return "PACKAGE_STATE_CHANGE";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED:
                return "PACKAGE_UPDATED";
            default:
                return "UNKNOWN";
        }
    }
}
