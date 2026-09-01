package com.hardbacknutter.sshd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.hardbacknutter.sshd.settings.Prefs;

/**
 * Allows external apps (MacroDroid, Tasker, ADB, ...) to run a command
 * directly, without going through SSH at all. See docs/DESIGN-SPEC.md,
 * Этап 6.
 * <p>
 * Disabled by default. Must be explicitly enabled in Settings ("Allow
 * external command execution"), with a warning shown at that point - once
 * enabled, ANY app on the device can send this broadcast (unless a token is
 * also configured, see below). This mirrors the existing START/STOP intent
 * handling in MainActivity, which has the same "no security by default"
 * property already.
 * <p>
 * From adb shell:
 * {@code am broadcast -a com.hardbacknutter.sshd.fg.EXEC --es cmd "id" --es exec_as root}
 * <p>
 * Extras:
 * - cmd (required)      the command to run
 * - exec_as (optional)  "root" | "shell" | "user" (default: "user")
 * - token (optional)    only checked if a token is configured in Settings
 * <p>
 * Fire-and-forget for now: the command's output goes to the debug log (see
 * FileLogger) if file logging is enabled, and to logcat either way. There is
 * deliberately no synchronous result delivery yet (Android doesn't support
 * that well for plain broadcasts) - a future iteration could add a
 * PendingIntent/ResultReceiver-based callback for callers that want the
 * output back, or write it to a fixed result file.
 */
public class ExecReceiver extends BroadcastReceiver {

    private static final String TAG = "ExecReceiver";

    public static final String ACTION_EXEC = "com.hardbacknutter.sshd.fg.EXEC";

    @Override
    public void onReceive(@NonNull final Context context,
                          @Nullable final Intent intent) {
        if (intent == null || !ACTION_EXEC.equals(intent.getAction())) {
            return;
        }

        final Context appContext = context.getApplicationContext();
        final SharedPreferences prefs = Prefs.getSharedPreferences(appContext);

        if (!Prefs.isExecIntentEnabled(prefs)) {
            Log.w(TAG, "onReceive|EXEC intent received but disabled in Settings - ignoring");
            return;
        }

        final String configuredToken = Prefs.getExecToken(prefs);
        if (configuredToken != null && !configuredToken.isBlank()) {
            final String givenToken = intent.getStringExtra("token");
            if (!configuredToken.equals(givenToken)) {
                Log.w(TAG, "onReceive|EXEC intent token mismatch - ignoring");
                return;
            }
        }

        final String cmd = intent.getStringExtra("cmd");
        if (cmd == null || cmd.isBlank()) {
            Log.w(TAG, "onReceive|EXEC intent missing 'cmd' extra - ignoring");
            return;
        }

        String execAs = intent.getStringExtra("exec_as");
        if (execAs == null || execAs.isBlank()) {
            execAs = "user";
        }
        final String finalExecAs = execAs;

        // BroadcastReceiver.onReceive() must return quickly - do the actual
        // (potentially slow, e.g. waiting on su) work on a background thread.
        new Thread(() -> runExec(appContext, cmd, finalExecAs), "ExecReceiver").start();
    }

    private static void runExec(@NonNull final Context context,
                                @NonNull final String cmd,
                                @NonNull final String execAs) {
        try {
            final Process process;
            if ("root".equals(execAs) || "shell".equals(execAs)) {
                final String uid = "root".equals(execAs) ? "0" : "2000";
                // same positional "su UID sh -c cmd" form used everywhere else
                // in this fork - see su-login-shell.c for why.
                process = new ProcessBuilder("su", uid, "sh", "-c", cmd)
                        .redirectErrorStream(true).start();
            } else {
                process = new ProcessBuilder("sh", "-c", cmd)
                        .redirectErrorStream(true).start();
            }

            final String output;
            try (InputStream is = process.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            final int exit = process.waitFor();

            Log.i(TAG, "runExec|execAs=" + execAs + "|exit=" + exit + "|output=" + output);
            FileLogger.log(context, TAG, "runExec|execAs=" + execAs
                                         + "|cmd=" + cmd
                                         + "|exit=" + exit
                                         + "|output=" + output);

        } catch (@NonNull final IOException e) {
            Log.w(TAG, "runExec|failed", e);
            FileLogger.log(context, TAG, "runExec|IOException|" + e);
        } catch (@NonNull final InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "runExec|interrupted", e);
        }
    }
}
