package com.hardbacknutter.sshd.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import com.hardbacknutter.prefslib.SettingsDataStore;
import com.hardbacknutter.sshd.SshdSettings;

class UserPassStorage
        implements SettingsDataStore {

    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_USERNAME = "sshd.authorized.username";
    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_PASSWORD = "sshd.authorized.password";

    @Nullable
    private String currentUsername;
    @Nullable
    private String currentPassword;

    private boolean passwordUpdated;

    UserPassStorage(@NonNull final Context context) {

        final String[] up = SshdSettings.readPasswordFile(context);
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
     * Write credentials to the dropbear file.
     *
     * @param context Current context
     */
    void storeCredentials(@NonNull final Context context)
            throws IOException, NoSuchAlgorithmException {
        SshdSettings.writePasswordFile(context, currentUsername, passwordUpdated, currentPassword);
    }

    @Override
    public void putString(@NonNull final String key,
                          @Nullable final String value) {
        switch (key) {
            case PK_SSHD_AUTH_USERNAME:
                // Don't check for empty and remove the password.
                // It's not user-friendly.
                // If the user is empty, the password is removed at save-time.
                currentUsername = value;
                break;
            case PK_SSHD_AUTH_PASSWORD:
                currentPassword = value;
                passwordUpdated = true;
                break;
            default:
                throw new IllegalArgumentException(key);
        }
    }

    @Nullable
    @Override
    public String getString(@NonNull final String key,
                            @Nullable final String defValue) {
        switch (key) {
            case PK_SSHD_AUTH_USERNAME:
                return currentUsername;
            case PK_SSHD_AUTH_PASSWORD:
                return currentPassword;
            default:
                throw new IllegalArgumentException(key);
        }
    }
}
