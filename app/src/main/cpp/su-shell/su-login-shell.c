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
 */

#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#ifndef SSHD4A_SU_TARGET_UID
#error "SSHD4A_SU_TARGET_UID must be defined at compile time (see app/CMakeLists.txt)"
#endif
#ifndef SSHD4A_HOME_DIR
#error "SSHD4A_HOME_DIR must be defined at compile time (see app/CMakeLists.txt)"
#endif

#define SSHD4A_STRINGIFY(x) #x
#define SSHD4A_TOSTRING(x) SSHD4A_STRINGIFY(x)
#define SSHD4A_TARGET_UID_STR SSHD4A_TOSTRING(SSHD4A_SU_TARGET_UID)
/* SSHD4A_HOME_DIR is passed in already-quoted from CMake
 * (SSHD4A_HOME_DIR="/some/path"), used directly as a string literal below -
 * NOT run through SSHD4A_TOSTRING (that's only for the numeric UID). */

int main(int argc, char **argv) {
    /* dropbear itself already tried (and, for a non-root/non-owner process,
     * necessarily failed) to chdir() into the home dir BEFORE forking us -
     * we're still the unprivileged app uid at that point, so a 700-permission
     * home dir owned by root/shell is correctly inaccessible to it yet. Do the
     * cd ourselves, inside the `su -c` command, where it actually has the
     * right uid to succeed. `cd ... ; exec ...` rather than `cd ... && exec`
     * on purpose - if the home dir is ever missing/inaccessible for some
     * other reason, we still want to land in a working shell instead of
     * silently exiting (that's the ENTIRE bug we're fixing here).
     */
    if (argc >= 2 && strcmp(argv[1], "-c") == 0) {
        /* Non-interactive: dropbear calls us as "<argv0> -c <command>". */
        const char *cmd = (argc >= 3) ? argv[2] : "";
        char full_cmd[4096];
        snprintf(full_cmd, sizeof(full_cmd),
                 "cd '" SSHD4A_HOME_DIR "' 2>/dev/null; %s", cmd);
        execlp("su", "su", SSHD4A_TARGET_UID_STR, "sh", "-c", full_cmd, (char *) NULL);
    } else {
        /* Interactive login shell.
         * Deliberately NOT using "sh -l" here (login-shell flag) - it makes
         * the shell try to grab session/tty leadership via job-control setup,
         * which fails ("No controlling tty"/"won't have full job control")
         * since it's not actually the pty's session leader at this point in
         * the exec chain. Plain "sh" still inherits the correct cwd from the
         * `cd` just before it (exec doesn't reset cwd), just without that
         * extra (and here, harmful) login-shell setup.
         */
        execlp("su", "su", SSHD4A_TARGET_UID_STR, "sh", "-c",
               "cd '" SSHD4A_HOME_DIR "' 2>/dev/null; exec sh", (char *) NULL);
    }

    /* execlp() only returns on failure. */
    fprintf(stderr, "su-login-shell(uid=" SSHD4A_TARGET_UID_STR "): exec of 'su' failed: %s\n",
            strerror(errno));
    return 127;
}
