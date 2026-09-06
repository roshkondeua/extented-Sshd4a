/*
 * Login-shell helper for the "root" and "shell" login roles (see
 * docs/DESIGN-SPEC.md). Compiled TWICE, once per role, each time with a
 * different SSHD4A_SU_TARGET_UID (0 for root, 2000 for the ADB "shell" UID) -
 * see the two add_executable(libsuroot.so / libsushell.so) targets in
 * app/CMakeLists.txt.
 *
 * dropbear execve()'s this directly as `pw_shell` for that role's session:
 *   - with no extra args for an interactive login (ssh user@host)
 *   - with "-c <command>" for a non-interactive one (ssh user@host "cmd")
 *
 * WHY THIS IS A COMPILED EXECUTABLE, NOT A SHELL SCRIPT WRITTEN AT RUNTIME:
 * Android 10+ blocks execution of files an app wrote itself outside its own
 * signed APK content (confirmed by on-device testing: attempting to exec a
 * script placed under /data/local/tmp gave "bad interpreter: Permission
 * denied", even though its own writable-file permissions were fine, because
 * dropbear's forked child is still running as the app's own (unprivileged)
 * process at that point - su hasn't been invoked yet). Executables that ship
 * inside the APK's own nativeLibraryDir (i.e. anything matching "lib*.so"
 * that Gradle packages from src/main/cpp) are specifically exempt, since
 * they were installed as part of the signed package rather than written at
 * runtime - see also libscp.so/librsync.so/libsftp-server.so, which already
 * use this same trick for a different reason (fake "libEXE.so" command
 * names, see jni-dropbear.c: sshd4a_exe_to_lib).
 *
 * The su invocation uses the positional "su UID sh [-c cmd]" form rather
 * than "su -c cmd", since that's what was confirmed working on-device (some
 * su binaries - notably some 32-bit/older ones - don't support "-c" at all).
 *
 * WHY THE INTERACTIVE PATH DOESN'T USE "su UID sh -c '...'":
 * On-device testing showed that going through su's "-c" mode - even just to
 * chain a `cd` before an `exec sh` - loses the controlling terminal ("No
 * controlling tty" / "won't have full job control"), while a bare
 * interactive "su UID sh" (no -c at all) does NOT have this problem. su
 * apparently treats "-c" invocations as non-interactive and detaches from
 * the session/tty accordingly (likely a setsid() of its own), which we can't
 * control from our side once we've exec'd into it. So for the interactive
 * case we go back to the known-clean bare invocation, and get the `cd` into
 * the home dir a different way: via $ENV, the standard POSIX mechanism an
 * interactive shell uses to source a startup file on its own.
 *
 * WHY THE $ENV RC FILE LIVES UNDER /data/local/tmp/Sshd4a/etc, NOT THE APP'S
 * OWN PRIVATE FILES DIR:
 * Originally written by this binary itself into the app's private files/
 * dir. Worked fine for the "root" role (root bypasses both unix permissions
 * AND SELinux checks in practice, e.g. via an unconfined su domain), but the
 * real "shell" uid 2000 is a genuinely restricted SELinux domain and got
 * denied regardless of unix chmod - Android labels an app's private data
 * with a per-app SELinux category that only that app's own process may
 * access, independent of DAC permission bits. /data/local/tmp isn't part of
 * any app's private sandbox, so it isn't subject to that restriction. The rc
 * files are now pre-created there by RootProvisioner (which already runs as
 * root via su for the home-directory setup) - see RootProvisioner.java,
 * RC_ROOT/RC_SHELL - this binary just points $ENV at the appropriate one.
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef SSHD4A_SU_TARGET_UID
#error "SSHD4A_SU_TARGET_UID must be defined at compile time (see app/CMakeLists.txt)"
#endif
#ifndef SSHD4A_HOME_DIR
#error "SSHD4A_HOME_DIR must be defined at compile time (see app/CMakeLists.txt)"
#endif
#ifndef SSHD4A_RC_FILE
#error "SSHD4A_RC_FILE must be defined at compile time (see app/CMakeLists.txt)"
#endif
#ifndef SSHD4A_DEBUG_LOG
#error "SSHD4A_DEBUG_LOG must be defined at compile time (see app/CMakeLists.txt)"
#endif
#ifndef SSHD4A_BIN_DIR
#error "SSHD4A_BIN_DIR must be defined at compile time (see app/CMakeLists.txt)"
#endif

#define SSHD4A_STRINGIFY(x) #x
#define SSHD4A_TOSTRING(x) SSHD4A_STRINGIFY(x)
#define SSHD4A_TARGET_UID_STR SSHD4A_TOSTRING(SSHD4A_SU_TARGET_UID)
/* SSHD4A_HOME_DIR / SSHD4A_RC_FILE are passed in already-quoted from CMake
 * (SSHD4A_HOME_DIR="/some/path"), used directly as string literals below -
 * NOT run through SSHD4A_TOSTRING (that's only for the numeric UID). */

static void debug_log(const char *msg) {
    /* Written to the app's own private files dir - fine here, this is only
     * ever read back by the app's own process (via adb/su cat), never by
     * the "shell"-uid shell itself, so the SELinux per-app restriction that
     * bit us for the rc file above doesn't matter for this. */
    FILE *f = fopen(SSHD4A_DEBUG_LOG, "a");
    if (f == NULL) {
        return;
    }
    fprintf(f, "[su-login-shell uid=" SSHD4A_TARGET_UID_STR "] %s\n", msg);
    fclose(f);
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "-c") == 0) {
        /* Non-interactive: dropbear calls us as "<argv0> -c <command>".
         * No tty involved here, so su's own "-c" session handling doesn't
         * matter - chain the cd directly. */
        const char *cmd = (argc >= 3) ? argv[2] : "";
        debug_log("command-mode");
        char full_cmd[4096];
        snprintf(full_cmd, sizeof(full_cmd),
                 "cd '" SSHD4A_HOME_DIR "' 2>/dev/null; %s", cmd);
        execlp("su", "su", SSHD4A_TARGET_UID_STR, "sh", "-c", full_cmd, (char *) NULL);

    } else {
        /* Interactive login: try candidates in order of preference, most
         * capable/verified first, falling back progressively to what's
         * definitely safe.
         *
         * IMPORTANT - HISTORY, so nobody re-tries blindly:
         * - "bash --rcfile <rc> -i" and "bash --rcfile <rc>" (no -i) BOTH
         *   broke the session outright on the bnsmb bash-static-stripped
         *   build that used to be our default pkg-index entry - confirmed
         *   by two separate on-device tests. Since execlp() only returns if
         *   exec() itself fails to *start* the program, a crash *inside*
         *   bash after a successful exec is invisible to us here.
         * - On-device testing later confirmed "/system/bin/bash --rcfile
         *   <rc>" (no -i) DOES work correctly on devices that have a full
         *   Magisk-provided bash (GNU bash 5.0, native arch) - so that
         *   specific combination is safe to use WHEN that binary is
         *   present. Not every device will have it.
         * - Our own self-hosted pkg-index bash (BIN_DIR/bash, user-supplied
         *   32-bit static builds) has NOT been verified with --rcfile - so
         *   it's invoked bare, same as before, until someone confirms it.
         *
         * "Starts in the right directory" is handled independently at the
         * permission level regardless of which of these runs (see
         * RootProvisioner.java: home dirs are chmod 701 so dropbear's own
         * pre-escalation chdir() already succeeds before any of this). HOME
         * is exported best-effort for whichever shell ends up running.
         */
        setenv("HOME", SSHD4A_HOME_DIR, 1);

        struct stat st;

        static const char *SYSTEM_BASH = "/system/bin/bash";
        if (stat(SYSTEM_BASH, &st) == 0) {
            debug_log("interactive-mode, /system/bin/bash found, exec su -> bash --rcfile");
            execlp("su", "su", SSHD4A_TARGET_UID_STR, SYSTEM_BASH,
                   "--rcfile", SSHD4A_RC_FILE, (char *) NULL);
            /* falls through to the next candidate if this exec failed to start */
        }

        char bash_path[512];
        snprintf(bash_path, sizeof(bash_path), "%s/bash", SSHD4A_BIN_DIR);
        if (stat(bash_path, &st) == 0) {
            debug_log("interactive-mode, BIN_DIR bash found, exec bare su -> bare bash (no flags)");
            execlp("su", "su", SSHD4A_TARGET_UID_STR, bash_path, (char *) NULL);
        }

        debug_log("interactive-mode, setenv ENV, about to exec bare su -> sh");
        setenv("ENV", SSHD4A_RC_FILE, 1);
        execlp("su", "su", SSHD4A_TARGET_UID_STR, "sh", (char *) NULL);
    }

    /* execlp() only returns on failure. */
    debug_log("exec of su FAILED");
    fprintf(stderr, "su-login-shell(uid=" SSHD4A_TARGET_UID_STR "): exec of 'su' failed: %s\n",
            strerror(errno));
    return 127;
}
