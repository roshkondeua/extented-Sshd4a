#ifndef SSHD4A_JNI_DROPBEAR_H
#define SSHD4A_JNI_DROPBEAR_H

#define SSHD4A_LIB_DIR  "SSHD4A_LIB_DIR"
#define SSHD4A_CONF_DIR "SSHD4A_CONF_DIR"

#define AUTHORIZED_USERS_FILE "master_password"
#define AUTHORIZED_KEYS_FILE "authorized_keys"

/* Roles a login username can have, see fourth field... actually third field of
 * AUTHORIZED_USERS_FILE lines: "username:sha512base64hash:role".
 * Missing role field (old 2-field format) defaults to SSHD4A_ROLE_USER. */
#define SSHD4A_ROLE_USER  "user"
#define SSHD4A_ROLE_ROOT  "root"
#define SSHD4A_ROLE_SHELL "shell"

/* Fixed locations for the root/shell login roles (see docs/DESIGN-SPEC.md).
 * The "user" role keeps using sshd4a_home_path/sshd4a_shell_exe as before
 * (app sandbox, passed in from Java at dropbear startup). */
#define SSHD4A_ROOT_HOME       "/data/local/tmp/Sshd4a/root/home"
#define SSHD4A_SHELL_HOME      "/data/local/tmp/Sshd4a/shell/home"
#define SSHD4A_ROOT_SHELL_EXE  "/data/local/tmp/Sshd4a/bin/su-root-shell"
#define SSHD4A_SHELL_SHELL_EXE "/data/local/tmp/Sshd4a/bin/su-shell-shell"

extern const char *sshd4a_shell_exe;
extern const char *sshd4a_home_path;

char *sshd4a_conf_file(const char *fn);
char *sshd4a_exe_to_lib(const char *cmd);

void sshd4a_set_env();

int sshd4a_enable_service_shell_access();

void sshd4a_svr_authinitialise();
void sshd4a_svr_auth_password(const char *password, unsigned int passwordlen,
                              char **passwdcrypt, char **testcrypt);

/* Look up the role ("user"/"root"/"shell") for a given login username.
 * Returned string must be free()'d by the caller. Never returns NULL. */
char *sshd4a_get_role_for_user(const char *username);

#endif /* SSHD4A_JNI_DROPBEAR_H */
