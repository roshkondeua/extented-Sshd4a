package com.hardbacknutter.sshd;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Creates (via `su`) the shared home directories used by the "root" and
 * "shell" login roles. See docs/DESIGN-SPEC.md.
 * <p>
 * The "user" role needs none of this - it keeps using the app's own sandbox
 * directory as before.
 * <p>
 * NOTE: this used to ALSO write login-shell wrapper SCRIPTS to
 * /data/local/tmp/Sshd4a/bin/ - that approach was abandoned after on-device
 * testing confirmed Android 10+ blocks executing a file the app wrote itself
 * outside its own signed APK content ("bad interpreter: Permission denied"),
 * even from a directory with otherwise-correct unix permissions. The actual
 * login-shell executables are now compiled into the APK itself and installed
 * into nativeLibraryDir (see su-shell/su-login-shell.c, libsuroot.so /
 * libsushell.so) - only the plain (non-executable) home directories are still
 * created here.
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
    static final String RC_ROOT = ETC_DIR + "/rc-root";
    static final String RC_SHELL = ETC_DIR + "/rc-shell";

    private RootProvisioner() {
    }

    /**
     * Ensure the shared home directories exist, if (and only if) the "root"
     * and/or "shell" role currently has credentials configured.
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

        Log.i(TAG, "provisionIfNeeded|ENTER|needRoot=" + needRoot + "|needShell=" + needShell);
        FileLogger.log(context, TAG, "provisionIfNeeded|ENTER|needRoot=" + needRoot
                                     + "|needShell=" + needShell);

        if (!needRoot && !needShell) {
            // nothing configured -> nothing to provision, and no need to ever invoke su
            return true;
        }

        final StringBuilder script = new StringBuilder();
        script.append("echo SSHD4A_STEP mkdir_base\n");
        script.append("mkdir -p '").append(BIN_DIR).append("' '").append(ETC_DIR).append("'\n");
        script.append("chmod 755 '").append(BASE_DIR).append("' '")
              .append(BIN_DIR).append("' '").append(ETC_DIR).append("'\n");

        if (needRoot) {
            script.append("echo SSHD4A_STEP mkdir_root_home\n");
            script.append("mkdir -p '").append(ROOT_HOME).append("'\n");
            script.append("chmod 700 '").append(ROOT_HOME).append("'\n");
            // $ENV startup file for the su-login-shell wrapper (see su-login-shell.c) -
            // lives here (not in the app's own private files dir) because that dir's
            // SELinux per-app category blocks the "shell" uid regardless of unix
            // permissions; /data/local/tmp isn't subject to that restriction. World
            // readable is fine, it's just a one-line `cd`.
            script.append("echo SSHD4A_STEP write_rc_root\n");
            script.append("cat > '").append(RC_ROOT).append("' <<'SSHD4A_RC_EOF'\n");
            script.append("cd '").append(ROOT_HOME).append("' 2>/dev/null\n");
            script.append("export PS1='$PWD # '\n");
            script.append("SSHD4A_RC_EOF\n");
            script.append("chmod 644 '").append(RC_ROOT).append("'\n");
        }
        if (needShell) {
            script.append("echo SSHD4A_STEP mkdir_shell_home\n");
            script.append("mkdir -p '").append(SHELL_HOME).append("'\n");
            // Owned by root:root by default (created via su) - the real "shell" uid (2000)
            // can't cd/write there otherwise, even after correctly escalating via su.
            script.append("chown 2000:2000 '").append(SHELL_HOME).append("'\n");
            script.append("chmod 700 '").append(SHELL_HOME).append("'\n");
            script.append("echo SSHD4A_STEP write_rc_shell\n");
            script.append("cat > '").append(RC_SHELL).append("' <<'SSHD4A_RC_EOF'\n");
            script.append("cd '").append(SHELL_HOME).append("' 2>/dev/null\n");
            script.append("export PS1='$PWD $ '\n");
            script.append("SSHD4A_RC_EOF\n");
            script.append("chmod 644 '").append(RC_SHELL).append("'\n");
        }

        script.append("echo SSHD4A_STEP verify\n");
        script.append("ls -la '").append(BASE_DIR).append("' '").append(ETC_DIR).append("'\n");
        script.append("echo SSHD4A_STEP done\n");
        script.append("exit\n");

        final boolean ok = runAsSu(context, script.toString());
        Log.i(TAG, "provisionIfNeeded|DONE|needRoot=" + needRoot
                   + "|needShell=" + needShell
                   + "|ok=" + ok);
        FileLogger.log(context, TAG, "provisionIfNeeded|DONE|needRoot=" + needRoot
                                     + "|needShell=" + needShell
                                     + "|ok=" + ok);
        return ok;
    }

    /**
     * Pipe {@code script} into an interactively-spawned `su` process' stdin -
     * the most broadly-compatible way to run root commands from Java, since it
     * doesn't depend on whichever `su -c`/positional argument style that
     * particular su binary supports.
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
