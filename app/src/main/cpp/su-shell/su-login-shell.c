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

#define SSHD4A_STRINGIFY(x) #x
#define SSHD4A_TOSTRING(x) SSHD4A_STRINGIFY(x)
#define SSHD4A_TARGET_UID_STR SSHD4A_TOSTRING(SSHD4A_SU_TARGET_UID)

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "-c") == 0) {
        /* Non-interactive: dropbear calls us as "<argv0> -c <command>"
         * (the whole command line as a single argv[2] string). */
        const char *cmd = (argc >= 3) ? argv[2] : "";
        execlp("su", "su", SSHD4A_TARGET_UID_STR, "sh", "-c", cmd, (char *) NULL);
    } else {
        /* Interactive login shell. */
        execlp("su", "su", SSHD4A_TARGET_UID_STR, "sh", (char *) NULL);
    }

    /* execlp() only returns on failure. */
    fprintf(stderr, "su-login-shell(uid=" SSHD4A_TARGET_UID_STR "): exec of 'su' failed: %s\n",
            strerror(errno));
    return 127;
}
