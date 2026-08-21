package com.hardbacknutter.sshd;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.hardbacknutter.sshd.settings.Prefs;

/**
 * Creates (via `su`) the shared directory tree and login-shell wrapper scripts
 * used by the "root" and "shell" login roles. See docs/DESIGN-SPEC.md.
 * <p>
 * The "user" role needs none of this - it keeps using the app's own sandbox
 * directory as before.
 * <p>
 * Everything here is best-effort: if there is no su binary, or root access is
 * denied, we simply log a warning. The regular "user" role login is completely
 * unaffected either way.
 */
public final class RootProvisioner {

    private static final String TAG = "RootProvisioner";

    static final String BASE_DIR = "/data/local/tmp/Sshd4a";
    static final String ROOT_HOME = BASE_DIR + "/root/home";
    static final String SHELL_HOME = BASE_DIR + "/shell/home";
    static final String BIN_DIR = BASE_DIR + "/bin";
    static final String ETC_DIR = BASE_DIR + "/etc";

    static final String ROOT_SHELL_SCRIPT = BIN_DIR + "/su-root-shell";
    static final String SHELL_SHELL_SCRIPT = BIN_DIR + "/su-shell-shell";

    /** UID that dropbear's `getpwnam`-less setup uses for the "shell" (ADB) role. */
    private static final String SHELL_UID = "2000";
    private static final String ROOT_UID = "0";

    private RootProvisioner() {
    }

    /**
     * Ensure the shared directory tree and su wrapper scripts exist, if (and only
     * if) the "root" and/or "shell" role currently has credentials configured.
     * Runs `su` synchronously - MUST be called from a background thread.
     *
     * @param context Current context
     *
     * @return {@code true} on success or if nothing needed to be done;
     *         {@code false} if provisioning was needed but failed
     */
    public static boolean provisionIfNeeded(@NonNull final Context context) {
        final boolean needRoot =
                SshdSettings.readAuthorizedUser(context, SshdSettings.ROLE_ROOT) != null;
        final boolean needShell =
                SshdSettings.readAuthorizedUser(context, SshdSettings.ROLE_SHELL) != null;

        // NOT gated behind BuildConfig.DEBUG - this is cheap and we need it visible
        // in release builds too while diagnosing on-device.
        Log.i(TAG, "provisionIfNeeded|ENTER|needRoot=" + needRoot + "|needShell=" + needShell);
        FileLogger.log(context, TAG, "provisionIfNeeded|ENTER|needRoot=" + needRoot
                                     + "|needShell=" + needShell);

        if (!needRoot && !needShell) {
            // nothing configured -> nothing to provision, and no need to ever invoke su
            return true;
        }

        final SharedPreferences prefs = Prefs.getSharedPreferences(context);
        final String style = Prefs.getSuCommandStyle(prefs);

        final StringBuilder script = new StringBuilder();
        script.append("mkdir -p '").append(BIN_DIR).append("' '").append(ETC_DIR).append("'\n");
        script.append("chmod 755 '").append(BASE_DIR).append("' '")
              .append(BIN_DIR).append("' '").append(ETC_DIR).append("'\n");

        if (needRoot) {
            script.append("mkdir -p '").append(ROOT_HOME).append("'\n");
            script.append("chmod 700 '").append(ROOT_HOME).append("'\n");
            appendWriteScript(script, ROOT_SHELL_SCRIPT, buildWrapper(ROOT_UID, style));
        }
        if (needShell) {
            script.append("mkdir -p '").append(SHELL_HOME).append("'\n");
            script.append("chmod 700 '").append(SHELL_HOME).append("'\n");
            appendWriteScript(script, SHELL_SHELL_SCRIPT, buildWrapper(SHELL_UID, style));
        }
        script.append("exit\n");

        final boolean ok = runAsSu(context, script.toString());
        Log.i(TAG, "provisionIfNeeded|DONE|needRoot=" + needRoot
                   + "|needShell=" + needShell
                   + "|style=" + style
                   + "|ok=" + ok);
        FileLogger.log(context, TAG, "provisionIfNeeded|DONE|needRoot=" + needRoot
                                     + "|needShell=" + needShell
                                     + "|style=" + style
                                     + "|ok=" + ok);
        return ok;
    }

    /**
     * Build the content of a login-shell wrapper script for the given target uid.
     * <p>
     * Dropbear execs this as {@code pw_shell}:
     * - with no arguments for an interactive login (ssh user@host)
     * - with "-c &lt;command&gt;" for a non-interactive one (ssh user@host "cmd")
     * <p>
     * Some su binaries (mostly 32-bit/older) don't understand "su -c cmd" and
     * need the positional "su UID sh -c cmd" form instead - hence the two styles.
     */
    @NonNull
    private static String buildWrapper(@NonNull final String targetUid,
                                       @NonNull final String style) {
        final String interactiveCmd;
        final String commandModeCmd;
        if (Prefs.SU_STYLE_POSITIONAL.equals(style)) {
            interactiveCmd = "su " + targetUid + " sh";
            commandModeCmd = "su " + targetUid + " sh -c \"$*\"";
        } else {
            interactiveCmd = "su " + targetUid;
            commandModeCmd = "su " + targetUid + " -c \"$*\"";
        }

        return "#!/system/bin/sh\n"
               + "# Auto-generated by Sshd4a extented - do not edit, will be overwritten.\n"
               + "if [ \"$1\" = \"-c\" ]; then\n"
               + "    shift\n"
               + "    exec " + commandModeCmd + "\n"
               + "else\n"
               + "    exec " + interactiveCmd + "\n"
               + "fi\n";
    }

    /**
     * Append shell commands to {@code script} that write {@code content} to
     * {@code path} (via a quoted heredoc, so no expansion happens) and chmod it
     * executable. Must be run as part of a script fed to `su`'s stdin.
     */
    private static void appendWriteScript(@NonNull final StringBuilder script,
                                          @NonNull final String path,
                                          @NonNull final String content) {
        final String marker = "SSHD4A_EOF";
        script.append("cat > '").append(path).append("' <<'").append(marker).append("'\n");
        script.append(content);
        script.append(marker).append('\n');
        script.append("chmod 755 '").append(path).append("'\n");
    }

    /**
     * Pipe {@code script} into an interactively-spawned `su` process' stdin -
     * this is the most broadly-compatible way to run root commands from Java,
     * since it doesn't depend on whichever `su -c`/positional argument style
     * that particular su binary supports (that variance only matters for the
     * generated wrapper SCRIPTS above, exec'd later by dropbear - not for this
     * one-time provisioning call).
     *
     * @return {@code true} if su exited with status 0
     */
    private static boolean runAsSu(@NonNull final Context context,
                                   @NonNull final String script) {
        try {
            final Process process = new ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start();

            try (OutputStream os = process.getOutputStream()) {
                os.write(script.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            final String output;
            try (InputStream is = process.getInputStream()) {
                output = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            final int exit = process.waitFor();
            if (exit != 0) {
                Log.w(TAG, "runAsSu|exit=" + exit + "|output=" + output);
            } else if (BuildConfig.DEBUG) {
                Log.d(TAG, "runAsSu|exit=0|output=" + output);
            }
            FileLogger.log(context, TAG, "runAsSu|exit=" + exit + "|output=" + output);
            return exit == 0;

        } catch (@NonNull final IOException e) {
            Log.w(TAG, "runAsSu|failed - no su binary, or root access denied", e);
            FileLogger.log(context, TAG, "runAsSu|IOException|" + e);
            return false;
        } catch (@NonNull final InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "runAsSu|interrupted", e);
            FileLogger.log(context, TAG, "runAsSu|InterruptedException|" + e);
            return false;
        }
    }
}
