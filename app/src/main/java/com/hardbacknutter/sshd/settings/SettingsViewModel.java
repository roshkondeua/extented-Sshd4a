package com.hardbacknutter.sshd.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.hardbacknutter.sshd.SshdSettings;

@SuppressWarnings("WeakerAccess")
public class SettingsViewModel
        extends ViewModel {

    /** Updates the password summary. */
    private final MutableLiveData<String> pwSummaryUpdate = new MutableLiveData<>();
    /** DataStore for user/password fields. */
    private UserPassStorage userPassDataStore;

    void init(@NonNull final Context context) {
        if (userPassDataStore == null) {
            final SharedPreferences prefs = Prefs.getSharedPreferences(context);
            // Force init if never set before
            Prefs.getHomePath(context, prefs);

            userPassDataStore = new UserPassStorage(context, this);
        }
    }

    @NonNull
    MutableLiveData<String> onPasswordSummaryUpdate() {
        return pwSummaryUpdate;
    }

    void updatePasswordSummary(@NonNull final String summary) {
        pwSummaryUpdate.setValue(summary);
    }

    @NonNull
    UserPassStorage getUserPassDataStore() {
        return userPassDataStore;
    }

    boolean hasAtLeastOneAuthMethod(@NonNull final Context context) {
        final SharedPreferences prefs = Prefs.getSharedPreferences(context);

        if (Prefs.isEnableSingleUsePasswordAuth(prefs)
            || Prefs.isEnablePublicKeyAuth(prefs)) {
            return true;
        }

        final String[] userAndPassword = SshdSettings.readPasswordFile(context);
        // both need to be not-empty
        return userAndPassword != null && userAndPassword.length == 2
               && userAndPassword[0] != null && !userAndPassword[0].isBlank()
               && userAndPassword[1] != null && !userAndPassword[1].isBlank();
    }
}
