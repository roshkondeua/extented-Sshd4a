package com.hardbacknutter.sshd;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hardbacknutter.sshd.settings.Prefs;

/**
 * Headless start/stop of the SSH service: no activity, so the app UI never
 * comes to the foreground (unlike the START/STOP intents handled by
 * {@link MainActivity}).
 * <p>
 * From adb shell:
 * <pre>
 * am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ControlReceiver \
 *     -a com.hardbacknutter.sshd.fg.START
 * </pre>
 * (and {@code ...fg.STOP}). Needs the same Setting as the activity variant:
 * "Allow starting the service by an external Intent".
 * <p>
 * {@code am broadcast} prints the result of this ordered broadcast:
 * {@code result=<code>, data=<message>}. Codes: see {@link #RESULT_STARTED} etc.
 */
public class ControlReceiver extends BroadcastReceiver {

    private static final String TAG = "ControlReceiver";

    public static final String ACTION_START = "com.hardbacknutter.sshd.fg.START";
    public static final String ACTION_STOP = "com.hardbacknutter.sshd.fg.STOP";

    /** The request was carried out (service started / stopped). */
    public static final int RESULT_OK_DONE = 1;
    /** Nothing to do: already running (START) or not running (STOP). */
    public static final int RESULT_NOOP = 2;
    /** The Setting "Allow starting the service by an external Intent" is off. */
    public static final int RESULT_DISABLED = 3;
    /** Starting failed (see data), e.g. background foreground-service restriction. */
    public static final int RESULT_FAILED = 4;
    /** STOP refused: the service was not started by an external intent. */
    public static final int RESULT_REFUSED = 5;

    @Override
    public void onReceive(@NonNull final Context context,
                          @Nullable final Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        if (!ACTION_START.equals(action) && !ACTION_STOP.equals(action)) {
            return;
        }

        final Context appContext = context.getApplicationContext();

        if (!Prefs.isStartByIntentAllowed(appContext)) {
            Log.w(TAG, "onReceive|disabled in Settings - ignoring " + action);
            result(RESULT_DISABLED, "disabled: enable 'Allow starting the service by an external Intent'");
            return;
        }

        if (ACTION_START.equals(action)) {
            start(appContext);
        } else {
            stop(appContext);
        }
    }

    private void start(@NonNull final Context context) {
        if (SshdService.isRunning()) {
            result(RESULT_NOOP, "already running");
            return;
        }
        try {
            final ComponentName cn = SshdService.startService(context, StartMode.ByIntent);
            if (cn != null) {
                FileLogger.log(context, TAG, "start|ok");
                result(RESULT_OK_DONE, "started");
            } else {
                FileLogger.log(context, TAG, "start|null component");
                result(RESULT_FAILED, "failed: service not found");
            }
        } catch (@NonNull final Exception e) {
            // API31+: ForegroundServiceStartNotAllowedException when the app is not
            // exempt from the background foreground-service-start restriction.
            Log.w(TAG, "start|failed", e);
            FileLogger.log(context, TAG, "start|failed|" + e);
            result(RESULT_FAILED, "failed: " + e);
        }
    }

    private void stop(@NonNull final Context context) {
        if (!SshdService.isRunning()) {
            result(RESULT_NOOP, "not running");
            return;
        }
        // Same rule as MainActivity: only stop what an external intent started.
        if (SshdService.getStartMode() != StartMode.ByIntent) {
            result(RESULT_REFUSED, "refused: service was not started by an external intent");
            return;
        }
        try {
            SshdService.stopService(context);
            FileLogger.log(context, TAG, "stop|ok");
            result(RESULT_OK_DONE, "stopped");
        } catch (@NonNull final Exception e) {
            Log.w(TAG, "stop|failed", e);
            FileLogger.log(context, TAG, "stop|failed|" + e);
            result(RESULT_FAILED, "failed: " + e);
        }
    }

    /** Only ordered broadcasts (the default for {@code am broadcast}) can carry a result. */
    private void result(final int code, @NonNull final String message) {
        if (isOrderedBroadcast()) {
            setResultCode(code);
            setResultData(message);
        }
    }
}
