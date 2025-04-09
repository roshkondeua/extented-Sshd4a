package com.hardbacknutter.sshd.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.preference.PreferenceManager;

import com.hardbacknutter.sshd.SshdSettings;

@SuppressWarnings("WeakerAccess")
public class SettingsViewModel
        extends ViewModel {

    private UserPassStorage dsUP;

    private final MutableLiveData<String> pwSummaryUpdate = new MutableLiveData<>();

    void init(@NonNull final Context context) {
        if ( dsUP == null) {
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            // Force init if never set before
            Prefs.getHomePath(context, prefs);

            dsUP = new UserPassStorage(context, this);
            updatePasswordSummary(dsUP.getPasswordSummaryText());
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
    UserPassStorage getDsUP() {
        return dsUP;
    }

    boolean hasAtLeastOneAuthMethod(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

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
