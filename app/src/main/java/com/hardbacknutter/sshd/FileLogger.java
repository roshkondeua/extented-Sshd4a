package com.hardbacknutter.sshd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.hardbacknutter.sshd.settings.Prefs;

/**
 * TEMPORARY on-device debug logging to a plain text file, in addition to logcat.
 * Enabled/configured from Settings > Diagnostics ("Write debug log to file" +
 * a manually-entered directory path). This is meant to be switched OFF again
 * once whatever is being diagnosed is fixed - not for permanent/production use.
 * <p>
 * Writes a single "sshd4a-debug.log" file in the given directory (append mode).
 * Tries a plain (fast) write first; if the directory isn't writable by the app's
 * own process (e.g. a root-only path like /data/local/tmp/Sshd4a), falls back to
 * writing via `su` - covers both "app-writable" and "root-only" log locations
 * without the user needing to pick differently depending on what's being logged.
 */
public final class FileLogger {

    private static final String TAG = "FileLogger";
    private static final String LOG_FILE_NAME = "sshd4a-debug.log";

    private FileLogger() {
    }

    public static void log(@NonNull final Context context,
                           @NonNull final String tag,
                           @NonNull final String message) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        if (!Prefs.isFileLoggingEnabled(prefs)) {
            return;
        }
        final String dir = Prefs.getFileLoggingDir(prefs);
        if (dir == null || dir.isBlank()) {
            return;
        }

        final SimpleDateFormat ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
        final String line = ts.format(new Date()) + " [" + tag + "] " + message + "\n";

        final String normalizedDir = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;
        final String path = normalizedDir + "/" + LOG_FILE_NAME;

        if (writePlain(path, line)) {
            return;
        }
        // Plain write failed (permission denied outside the app sandbox, most likely) -
        // fall back to su. Slower, but this is a temporary diagnostic feature only.
        writeViaSu(normalizedDir, path, line);
    }

    private static boolean writePlain(@NonNull final String path,
                                      @NonNull final String line) {
        try {
            final File file = new File(path);
            final File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (FileWriter fw = new FileWriter(file, true)) {
                fw.write(line);
            }
            return true;
        } catch (@NonNull final IOException | SecurityException e) {
            return false;
        }
    }

    private static void writeViaSu(@NonNull final String dir,
                                   @NonNull final String path,
                                   @NonNull final String line) {
        try {
            final Process process = new ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start();

            final String marker = "SSHD4A_LOG_EOF";
            final String script = "mkdir -p '" + dir + "'\n"
                                   + "cat >> '" + path + "' <<'" + marker + "'\n"
                                   + line
                                   + marker + "\n"
                                   + "exit\n";

            try (OutputStream os = process.getOutputStream()) {
                os.write(script.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            process.getInputStream().readAllBytes(); // drain, avoid blocking on full pipe
            process.waitFor();

        } catch (@NonNull final IOException e) {
            Log.w(TAG, "writeViaSu|failed - no su binary, or root access denied", e);
        } catch (@NonNull final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
