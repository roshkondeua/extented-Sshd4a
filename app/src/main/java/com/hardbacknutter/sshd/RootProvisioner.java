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
        // Print each command before running it (sh -x style, but manual since
        // some /system/bin/sh builds don't support "set -x" usefully), and
        // verify with `ls` at the end - a clean exit code alone doesn't prove
        // anything actually got created (no `set -e`, so an interactive su
        // session just reports the LAST command's status, not the whole script).
        script.append("echo SSHD4A_STEP mkdir_base\n");
        script.append("mkdir -p '").append(BIN_DIR).append("' '").append(ETC_DIR).append("'\n");
        script.append("echo SSHD4A_STEP chmod_base\n");
        script.append("chmod 755 '").append(BASE_DIR).append("' '")
              .append(BIN_DIR).append("' '").append(ETC_DIR).append("'\n");

        if (needRoot) {
            script.append("echo SSHD4A_STEP mkdir_root_home\n");
            script.append("mkdir -p '").append(ROOT_HOME).append("'\n");
            script.append("chmod 700 '").append(ROOT_HOME).append("'\n");
            script.append("echo SSHD4A_STEP write_root_script\n");
            appendWriteScript(script, ROOT_SHELL_SCRIPT, buildWrapper(context, ROOT_UID, style));
        }
        if (needShell) {
            script.append("echo SSHD4A_STEP mkdir_shell_home\n");
            script.append("mkdir -p '").append(SHELL_HOME).append("'\n");
            script.append("chmod 700 '").append(SHELL_HOME).append("'\n");
            script.append("echo SSHD4A_STEP write_shell_script\n");
            appendWriteScript(script, SHELL_SHELL_SCRIPT, buildWrapper(context, SHELL_UID, style));
        }

        script.append("echo SSHD4A_STEP verify\n");
        script.append("ls -la '").append(BASE_DIR).append("' '")
              .append(BIN_DIR).append("' '").append(ETC_DIR).append("'\n");
        if (needRoot) {
            script.append("ls -la '").append(ROOT_SHELL_SCRIPT).append("'\n");
            script.append("cat '").append(ROOT_SHELL_SCRIPT).append("'\n");
        }
        if (needShell) {
            script.append("ls -la '").append(SHELL_SHELL_SCRIPT).append("'\n");
        }
        script.append("echo SSHD4A_STEP done\n");
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
     * <p>
     * The wrapper logs its own invocation (args + `id`) to a fixed file in the
     * app's OWN internal storage - i.e. writable by the app UID that dropbear's
     * forked child still has at the point it execs this script, BEFORE su has
     * (potentially) escalated it - so we can tell whether dropbear even reaches
     * this script at all, independently of whether su itself then succeeds.
     */
    @NonNull
    private static String buildWrapper(@NonNull final Context context,
                                       @NonNull final String targetUid,
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

        final String logFile = context.getFilesDir().getAbsolutePath() + "/wrapper-debug.log";

        return "#!/system/bin/sh\n"
               + "# Auto-generated by Sshd4a extented - do not edit, will be overwritten.\n"
               + "LOG='" + logFile + "'\n"
               + "echo \"$(date) wrapper-invoked uid=$(id) args=[$*]\" >> \"$LOG\" 2>&1\n"
               + "if [ \"$1\" = \"-c\" ]; then\n"
               + "    shift\n"
               + "    echo \"$(date) command-mode about-to-exec: " + commandModeCmd
               + "\" >> \"$LOG\" 2>&1\n"
               + "    exec " + commandModeCmd + "\n"
               + "else\n"
               + "    echo \"$(date) interactive-mode about-to-exec: " + interactiveCmd
               + "\" >> \"$LOG\" 2>&1\n"
               + "    exec " + interactiveCmd + "\n"
               + "fi\n"
               + "echo \"$(date) exec FAILED, reached unreachable code\" >> \"$LOG\" 2>&1\n";
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
