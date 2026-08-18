package com.hardbacknutter.sshd.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import com.hardbacknutter.prefslib.SettingsDataStore;
import com.hardbacknutter.sshd.SshdSettings;

/**
 * DataStore backing the username/password preference fields for ONE role
 * (user / root / shell). See {@link SshdSettings#ROLE_USER} and friends.
 * <p>
 * Three instances of this class exist (one per role), each with its own
 * pair of preference keys so they can be wired to independent Preference
 * widgets in the Settings screen while sharing the same underlying
 * multi-role AUTHORIZED_USERS file.
 */
class UserPassStorage
        implements SettingsDataStore {

    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_USERNAME = "sshd.authorized.username";
    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_PASSWORD = "sshd.authorized.password";

    static final String PK_SSHD_AUTH_ROOT_USERNAME = "sshd.authorized.root.username";
    static final String PK_SSHD_AUTH_ROOT_PASSWORD = "sshd.authorized.root.password";

    static final String PK_SSHD_AUTH_SHELL_USERNAME = "sshd.authorized.shell.username";
    static final String PK_SSHD_AUTH_SHELL_PASSWORD = "sshd.authorized.shell.password";

    @NonNull
    private final String role;
    @NonNull
    private final String usernameKey;
    @NonNull
    private final String passwordKey;

    @Nullable
    private String currentUsername;
    @Nullable
    private String currentPassword;

    private boolean passwordUpdated;

    /**
     * @param context     Current context
     * @param role        one of {@link SshdSettings#ROLE_USER}, {@link SshdSettings#ROLE_ROOT},
     *                    {@link SshdSettings#ROLE_SHELL}
     * @param usernameKey preference key used for the username field bound to this instance
     * @param passwordKey preference key used for the password field bound to this instance
     */
    UserPassStorage(@NonNull final Context context,
                    @NonNull final String role,
                    @NonNull final String usernameKey,
                    @NonNull final String passwordKey) {
        this.role = role;
        this.usernameKey = usernameKey;
        this.passwordKey = passwordKey;

        final String[] up = SshdSettings.readAuthorizedUser(context, role);
        if (up != null) {
            currentUsername = up[0];
            currentPassword = null;
        }
    }

    /**
     * Use to set the initial enabled-state of the password field.
     *
     * @return flag
     */
    boolean hasUsername() {
        return currentUsername != null && !currentUsername.isBlank();
    }

    /**
     * Write credentials for this role to the dropbear file.
     *
     * @param context Current context
     */
    void storeCredentials(@NonNull final Context context)
            throws IOException, NoSuchAlgorithmException {
        SshdSettings.writeAuthorizedUser(context, role, currentUsername,
                                         passwordUpdated, currentPassword);
    }

    @Override
    public void putString(@NonNull final String key,
                          @Nullable final String value) {
        if (key.equals(usernameKey)) {
            // Don't check for empty and remove the password.
            // It's not user-friendly.
            // If the user is empty, the password is removed at save-time.
            currentUsername = value;
        } else if (key.equals(passwordKey)) {
            currentPassword = value;
            passwordUpdated = true;
        } else {
            throw new IllegalArgumentException(key);
        }
    }

    @Nullable
    @Override
    public String getString(@NonNull final String key,
                            @Nullable final String defValue) {
        if (key.equals(usernameKey)) {
            return currentUsername;
        } else if (key.equals(passwordKey)) {
            return currentPassword;
        } else {
            throw new IllegalArgumentException(key);
        }
    }
}
