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
 * - nowait (optional)   true = do not wait for the command (fire and forget)
 * <p>
 * The caller of an ordered broadcast ({@code am broadcast} is one) gets the command's
 * output back: result=1 data="exit=N\n<output>" when it finished within 8 s, result=8
 * when it is still running (the full output then goes to the debug log), 9 when it
 * could not be started. Extra {@code nowait=true} (--ez nowait true) restores
 * fire-and-forget. Output also goes to logcat, and to the debug log if enabled.
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
            report(3, "disabled: enable 'Allow external command execution'");
            return;
        }

        final String configuredToken = Prefs.getExecToken(prefs);
        if (configuredToken != null && !configuredToken.isBlank()) {
            final String givenToken = intent.getStringExtra("token");
            if (!configuredToken.equals(givenToken)) {
                Log.w(TAG, "onReceive|EXEC intent token mismatch - ignoring");
                report(6, "refused: token mismatch");
                return;
            }
        }

        final String cmd = intent.getStringExtra("cmd");
        if (cmd == null || cmd.isBlank()) {
            Log.w(TAG, "onReceive|EXEC intent missing 'cmd' extra - ignoring");
            report(7, "refused: missing 'cmd' extra");
            return;
        }

        String execAs = intent.getStringExtra("exec_as");
        if (execAs == null || execAs.isBlank()) {
            execAs = "user";
        }
        final String finalExecAs = execAs;

        // Default: wait up to WAIT_MS for the command and hand its output back to the caller
        // (am broadcast prints it as data="..."). A longer-running command keeps running in the
        // background; its full output then goes to the log. Extra "nowait" = fire and forget.
        final boolean nowait = intent.getBooleanExtra("nowait", false);
        final PendingResult pending = (nowait || !isOrderedBroadcast()) ? null : goAsync();
        if (pending == null) {
            new Thread(() -> {
                try {
                    runExec(appContext, cmd, finalExecAs, 0);
                } catch (@NonNull final Exception e) {
                    FileLogger.log(appContext, TAG, "runExec|failed|" + e);
                }
            }, "ExecReceiver").start();
            report(1, "accepted: running as " + finalExecAs + " (output goes to the debug log)");
            return;
        }
        new Thread(() -> {
            try {
                final Outcome o = runExec(appContext, cmd, finalExecAs, WAIT_MS);
                String out = o.output;
                if (out.length() > MAX_RESULT_CHARS) {
                    out = out.substring(0, MAX_RESULT_CHARS) + "\n[truncated]";
                }
                if (o.finished) {
                    pending.setResultCode(1);
                    pending.setResultData("exit=" + o.exit + "\n" + out);
                } else {
                    pending.setResultCode(8);
                    pending.setResultData("still running after " + (WAIT_MS / 1000)
                                          + " s (full output goes to the debug log)\n" + out);
                }
            } catch (@NonNull final Exception e) {
                FileLogger.log(appContext, TAG, "runExec|failed|" + e);
                pending.setResultCode(9);
                pending.setResultData("failed to run: " + e);
            } finally {
                pending.finish();
            }
        }, "ExecReceiver").start();
    }

    /** Result code/data shown by {@code am broadcast} (ordered broadcasts only). */
    private void report(final int code, @NonNull final String message) {
        if (isOrderedBroadcast()) {
            setResultCode(code);
            setResultData(message);
        }
    }

    private static final long WAIT_MS = 8000;
    private static final int MAX_RESULT_CHARS = 20000;

    private static final class Outcome {
        final boolean finished;
        final int exit;
        @NonNull
        final String output;

        Outcome(final boolean finished, final int exit, @NonNull final String output) {
            this.finished = finished;
            this.exit = exit;
            this.output = output;
        }
    }

    /**
     * Start the command; wait at most {@code waitMs} (0 = do not wait). A separate reader
     * thread always collects the output and logs it when the process ends.
     */
    @NonNull
    private static Outcome runExec(@NonNull final Context context,
                                   @NonNull final String cmd,
                                   @NonNull final String execAs,
                                   final long waitMs) throws IOException, InterruptedException {
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

        final java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        final Thread reader = new Thread(() -> {
            try (InputStream is = process.getInputStream()) {
                final byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) {
                    synchronized (collected) {
                        collected.write(buf, 0, n);
                    }
                }
            } catch (@NonNull final IOException ignored) {
                // stream closed
            }
            int exit = -1;
            try {
                exit = process.waitFor();
            } catch (@NonNull final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            final String out;
            synchronized (collected) {
                out = new String(collected.toByteArray(), StandardCharsets.UTF_8);
            }
            Log.i(TAG, "runExec|execAs=" + execAs + "|exit=" + exit + "|output=" + out);
            FileLogger.log(context, TAG, "runExec|execAs=" + execAs
                                         + "|cmd=" + cmd
                                         + "|exit=" + exit
                                         + "|output=" + out);
        }, "ExecReader");
        reader.start();

        final boolean finished = waitMs > 0
                                 && process.waitFor(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (finished) {
            reader.join(1000);
        }
        final String out;
        synchronized (collected) {
            out = new String(collected.toByteArray(), StandardCharsets.UTF_8);
        }
        return new Outcome(finished, finished ? process.exitValue() : -1, out);
    }
}
