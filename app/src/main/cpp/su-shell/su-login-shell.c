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
 * interactive shell uses to source a startup file on its own - written by
 * US (harmless, since it's read/sourced by an already-running interpreter,
 * not exec'd - the W^X restriction above only blocks execve(), not this).
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

#define SSHD4A_STRINGIFY(x) #x
#define SSHD4A_TOSTRING(x) SSHD4A_STRINGIFY(x)
#define SSHD4A_TARGET_UID_STR SSHD4A_TOSTRING(SSHD4A_SU_TARGET_UID)
/* SSHD4A_HOME_DIR / SSHD4A_RC_FILE are passed in already-quoted from CMake
 * (SSHD4A_HOME_DIR="/some/path"), used directly as string literals below -
 * NOT run through SSHD4A_TOSTRING (that's only for the numeric UID). */

static void write_rc_file(void) {
    /* Written while we're still the unprivileged app uid, into the app's
     * OWN private files dir - always writable regardless of W^X (which only
     * blocks *executing* app-written files, not reading them as shell input
     * via $ENV). Best-effort: if this fails, the interactive shell simply
     * starts in whatever cwd dropbear left it in (same as before this fix -
     * not worse, just not better). */
    FILE *f = fopen(SSHD4A_RC_FILE, "w");
    if (f == NULL) {
        return;
    }
    fprintf(f, "cd '" SSHD4A_HOME_DIR "' 2>/dev/null\n");
    fclose(f);
}

static void debug_log(const char *msg) {
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
        /* Interactive login: bare "su UID sh" (confirmed clean, no tty
         * issues), cd happens via $ENV instead of "-c". */
        debug_log("interactive-mode, writing rc file + setenv ENV, about to exec bare su");
        write_rc_file();
        setenv("ENV", SSHD4A_RC_FILE, 1);
        execlp("su", "su", SSHD4A_TARGET_UID_STR, "sh", (char *) NULL);
    }

    /* execlp() only returns on failure. */
    debug_log("exec of su FAILED");
    fprintf(stderr, "su-login-shell(uid=" SSHD4A_TARGET_UID_STR "): exec of 'su' failed: %s\n",
            strerror(errno));
    return 127;
}
