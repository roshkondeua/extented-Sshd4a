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
        /* Interactive login: bare "su UID <shell>" (confirmed clean, no tty
         * issues, no crashes). HOME is exported best-effort (some su forks
         * reset it regardless when switching user - harmless either way
         * now, see below).
         *
         * IMPORTANT - HISTORY OF WHAT DOESN'T WORK HERE, so nobody re-tries
         * these: both "bash --rcfile <rc> -i" AND "bash --rcfile <rc>"
         * (without -i) were tried and BOTH broke the session outright
         * (immediate disconnect) on-device. Since execlp() only returns if
         * exec() itself fails to *start* the program, a crash/incompatible
         * flag *inside* bash after a successful exec is invisible to us and
         * can't be caught here to fall back from - so this particular
         * static/stripped bash build's --rcfile support (or lack of it) is
         * simply not usable for us. Bash is now invoked completely BARE -
         * no flags of any kind - mirroring the plain "su UID sh" form that
         * IS confirmed reliable.
         *
         * The "starts in the right directory" problem this used to solve
         * via --rcfile is instead fixed at the permission level:
         * RootProvisioner chmods the home dirs 701 (execute-only for
         * "other"), which lets dropbear's OWN pre-escalation chdir()
         * attempt succeed even before su - so the process cwd is already
         * correct by the time anything here runs, inherited automatically
         * across every exec() in this chain, bash included. See
         * RootProvisioner.java for the permission comment.
         *
         * Trade-off accepted for now: bash's ~/.bashrc / $HISTFILE default
         * still depend on $HOME, which may or may not survive su - so
         * aliases/prompt/history *persistence* may not apply inside bash
         * specifically (in-session history still works regardless - that's
         * bash's own in-memory feature, unaffected). Revisit if that turns
         * out to matter in practice. */
        setenv("HOME", SSHD4A_HOME_DIR, 1);

        char bash_path[512];
        snprintf(bash_path, sizeof(bash_path), "%s/bash", SSHD4A_BIN_DIR);

        struct stat st;
        if (stat(bash_path, &st) == 0) {
            debug_log("interactive-mode, bash found, exec bare su -> bare bash (no flags)");
            execlp("su", "su", SSHD4A_TARGET_UID_STR, bash_path, (char *) NULL);
            /* if THIS particular exec failed (e.g. bash_path stopped existing
             * between the stat() and here), fall through to plain sh below
             * rather than giving up entirely. */
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
