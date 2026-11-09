package com.hardbacknutter.sshd;

import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

/**
 * Shared logic for the headless START/STOP entry points
 * ({@link ControlReceiver} and {@link QuickStartActivity}).
 */
final class ExternalControl {

    private static final String TAG = "ExternalControl";

    /** The request was carried out (service started / stopped). */
    static final int RESULT_OK_DONE = 1;
    /** Nothing to do: already running (START) or not running (STOP). */
    static final int RESULT_NOOP = 2;
    /** The Setting "Allow starting the service by an external Intent" is off. */
    static final int RESULT_DISABLED = 3;
    /** Starting failed (see message). */
    static final int RESULT_FAILED = 4;
    /** STOP refused: the service was not started by an external intent. */
    static final int RESULT_REFUSED = 5;

    static final class Result {
        final int code;
        @NonNull
        final String message;

        Result(final int code, @NonNull final String message) {
            this.code = code;
            this.message = message;
        }
    }

    private ExternalControl() {
    }

    @NonNull
    static Result start(@NonNull final Context context, @NonNull final String via) {
        if (!com.hardbacknutter.sshd.settings.Prefs.isStartByIntentAllowed(context)) {
            return log(context, via, "start", new Result(RESULT_DISABLED,
                    "disabled: enable 'Allow starting the service by an external Intent'"));
        }
        if (SshdService.isRunning()) {
            return log(context, via, "start", new Result(RESULT_NOOP, "already running"));
        }
        try {
            final ComponentName cn = SshdService.startService(context, StartMode.ByIntent);
            if (cn != null) {
                return log(context, via, "start", new Result(RESULT_OK_DONE, "started"));
            }
            return log(context, via, "start",
                       new Result(RESULT_FAILED, "failed: service not found"));
        } catch (@NonNull final Exception e) {
            // API31+: ForegroundServiceStartNotAllowedException when the app is in the
            // background and not exempt. The QuickStartActivity entry point avoids that.
            Log.w(TAG, "start|failed", e);
            return log(context, via, "start", new Result(RESULT_FAILED,
                    "failed: " + e + " -> use QuickStartActivity (see docs/EXTERNAL-RUN.md)"));
        }
    }

    @NonNull
    static Result stop(@NonNull final Context context, @NonNull final String via) {
        if (!com.hardbacknutter.sshd.settings.Prefs.isStartByIntentAllowed(context)) {
            return log(context, via, "stop", new Result(RESULT_DISABLED,
                    "disabled: enable 'Allow starting the service by an external Intent'"));
        }
        if (!SshdService.isRunning()) {
            return log(context, via, "stop", new Result(RESULT_NOOP, "not running"));
        }
        // Same rule as MainActivity: only stop what an external intent started.
        if (SshdService.getStartMode() != StartMode.ByIntent) {
            return log(context, via, "stop", new Result(RESULT_REFUSED,
                    "refused: service was not started by an external intent"));
        }
        try {
            SshdService.stopService(context);
            return log(context, via, "stop", new Result(RESULT_OK_DONE, "stopped"));
        } catch (@NonNull final Exception e) {
            Log.w(TAG, "stop|failed", e);
            return log(context, via, "stop", new Result(RESULT_FAILED, "failed: " + e));
        }
    }

    @NonNull
    private static Result log(@NonNull final Context context,
                              @NonNull final String via,
                              @NonNull final String what,
                              @NonNull final Result result) {
        final String line = via + "|" + what + "|result=" + result.code + "|" + result.message;
        Log.i(TAG, line);
        FileLogger.log(context, TAG, line);
        return result;
    }
}
