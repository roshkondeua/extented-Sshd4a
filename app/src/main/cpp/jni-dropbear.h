#ifndef SSHD4A_JNI_DROPBEAR_H
#define SSHD4A_JNI_DROPBEAR_H

#define SSHD4A_LIB_DIR  "SSHD4A_LIB_DIR"
#define SSHD4A_CONF_DIR "SSHD4A_CONF_DIR"

#define AUTHORIZED_USERS_FILE "master_password"
#define AUTHORIZED_KEYS_FILE "authorized_keys"

extern const char *sshd4a_shell_exe;
extern const char *sshd4a_home_path;

char *sshd4a_conf_file(const char *fn);
char *sshd4a_exe_to_lib(const char *cmd);

void sshd4a_set_env();

int sshd4a_enable_service_shell_access();
int sshd4a_enable_public_key_auth();
int sshd4a_enable_single_use_passwords();
int sshd4a_enable_password_file();

void sshd4a_generate_single_use_password(char **gen_pass);

void sshd4a_svr_auth_password(const char *password, unsigned int passwordlen,
                              char **passwdcrypt, char **testcrypt);

#endif /* SSHD4A_JNI_DROPBEAR_H */
