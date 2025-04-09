package com.hardbacknutter.sshd.settings;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import com.hardbacknutter.sshd.R;
import com.hardbacknutter.sshd.SshdSettings;

class UserPassStorage
        extends PreferenceDataStore {

    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_USERNAME = "sshd.authorized.username";
    /** NOT PERSISTED, written directly to the dropbear directory. */
    static final String PK_SSHD_AUTH_PASSWORD = "sshd.authorized.password";

    private static final String ASTERIXS = "********";

    @NonNull
    private final SettingsViewModel vm;

    private String notSetText;

    private boolean hasStoredPassword;
    @Nullable
    private String currentUsername;
    @Nullable
    private String currentPassword;

    private boolean passwordUpdated;

    UserPassStorage(@NonNull final Context context,
                    @NonNull final SettingsViewModel vm) {
        this.vm = vm;
        final String[] up = SshdSettings.readPasswordFile(context);
        if (up != null) {
            hasStoredPassword = up[1] != null;

            currentUsername = up[0];
            currentPassword = null;

            notSetText = context.getString(R.string.pref_not_set);
        }
    }

    @Nullable
    String getCurrentUsername() {
        return currentUsername;
    }

    @Nullable
    String getCurrentPassword() {
        return currentPassword;
    }

    @NonNull
    String getPasswordSummaryText() {
        if (passwordUpdated && currentPassword != null && !currentPassword.isEmpty()) {
            return ASTERIXS;
        }

        if (!passwordUpdated && hasStoredPassword) {
            return ASTERIXS;
        }

        return notSetText;
    }

    void storeCredentials(@NonNull final Context context)
            throws IOException, NoSuchAlgorithmException {
        SshdSettings.writePasswordFile(context, currentUsername, passwordUpdated, currentPassword);
    }

    @Override
    public void putString(@NonNull final String key,
                          @Nullable final String value) {
        switch (key) {
            case PK_SSHD_AUTH_USERNAME:
                currentUsername = value;
                break;
            case PK_SSHD_AUTH_PASSWORD:
                currentPassword = value;
                passwordUpdated = true;
                vm.updatePasswordSummary(getPasswordSummaryText());
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
