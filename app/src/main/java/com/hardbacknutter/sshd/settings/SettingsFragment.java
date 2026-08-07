package com.hardbacknutter.sshd.settings;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import com.hardbacknutter.prefslib.BooleanSetting;
import com.hardbacknutter.prefslib.PasswordSetting;
import com.hardbacknutter.prefslib.Setting;
import com.hardbacknutter.prefslib.SettingsDataStore;
import com.hardbacknutter.prefslib.SettingsManager;
import com.hardbacknutter.prefslib.SharedPreferencesDataStore;
import com.hardbacknutter.prefslib.StringSetting;
import com.hardbacknutter.sshd.R;
import com.hardbacknutter.sshd.SshdSettings;
import com.hardbacknutter.util.theme.NightMode;

public class SettingsFragment
        extends Fragment {

    public static final String TAG = "SettingsFragment";

    private static final int PORT_MIN = 1024;
    private static final int PORT_MAX = 32768;

    private boolean isTelevision;
    private SettingsViewModel vm;
    private final OnBackPressedCallback backPressedCallback =
            new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    try {
                        //noinspection DataFlowIssue
                        vm.getUserPassDataStore().storeCredentials(getContext());

                    } catch (@NonNull final IOException | NoSuchAlgorithmException ignore) {
                        // we should never get here... flw
                        //noinspection DataFlowIssue
                        Snackbar.make(getView(), R.string.err_failed_to_save,
                                      Snackbar.LENGTH_LONG).show();
                        getView().postDelayed(() -> getParentFragmentManager().popBackStack(),
                                              2_000);
                    }

                    if (vm.hasAtLeastOneAuthMethod(getContext())) {
                        getParentFragmentManager().popBackStack();
                    } else {
                        new MaterialAlertDialogBuilder(getContext())
                                .setIcon(R.drawable.warning_24px)
                                .setTitle(R.string.dialog_title_warning)
                                .setMessage(R.string.warning_no_auth_methods)
                                .setCancelable(true)
                                .setNegativeButton(R.string.cancel, (d, which) ->
                                        d.dismiss())
                                .setPositiveButton(R.string.ok, (d, which) ->
                                        getParentFragmentManager().popBackStack())
                                .create()
                                .show();
                    }
                }
            };
    private SettingsManager settingsManager;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection DataFlowIssue
        isTelevision = getContext().getPackageManager()
                                   .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    private SettingsManager.Builder onCreateSettings() {
        final Context context = getContext();

        //noinspection DataFlowIssue
        final SettingsDataStore store = new SharedPreferencesDataStore(
                Prefs.getSharedPreferences(context));

        final SettingsManager.Builder factory = new SettingsManager.Builder(context, store);

        vm = new ViewModelProvider(this).get(SettingsViewModel.class);
        vm.init(context);

        factory.header(R.string.pc_startup);
        factory.bool(Prefs.RUN_ON_BOOT,
                     R.string.pt_start_service_on_device_boot,
                     this::onRunModeChanged, null);
        factory.bool(Prefs.RUN_ON_APP_START,
                     R.string.pt_start_service_on_app_opening,
                     null, null);
        factory.bool(Prefs.RUN_ON_INTENT_ALLOWED,
                     R.string.pt_start_service_by_intent_in_fg_allowed,
                     null, null);
        factory.bool(Prefs.RUN_IN_FOREGROUND,
                     R.string.pt_foreground,
                     R.string.ps_foreground_off,
                     R.string.ps_foreground_on,
                     this::onRunModeChanged, p -> {
                    p.setChecked(true);
                });
        factory.bool(Prefs.ON_APP_EXIT_KEEP_RUNNING,
                     R.string.pt_keep_running_after_app_exit,
                     R.string.disabled, R.string.enabled,
                     null, null);

        factory.header(R.string.pc_dropbear, p -> {
            final String interfaces =
                    getString(R.string.lbl_all_interfaces)
                    + '\n'
                    + String.join("\n", SshdSettings.getHostAddresses(6));

            p.setSummary(interfaces);
        });
        factory.text(Prefs.SSHD_PORT,
                     R.string.pt_port,
                     this::onPortChange, p -> {
                    p.setInputType(InputType.TYPE_CLASS_NUMBER);
                    p.setValue(String.valueOf(Prefs.DEFAULT_PORT));
                });
        factory.text(Prefs.DROPBEAR_CMDLINE_OPTIONS,
                     R.string.pt_cmdline_options,
                     this::onCmdLineOptionsChanged, null);
        factory.text(Prefs.ENV_VARS,
                     R.string.pt_env,
                     null, null);

        factory.header(R.string.pc_paths);
        factory.bool(Prefs.ENABLE_SERVICE_SHELL_ACCESS,
                     R.string.pt_enable_shell_access,
                     R.string.disabled, R.string.enabled,
                     null, p -> {
                    p.setChecked(true);
                });
        // default is set from code
        factory.text(Prefs.HOME,
                     R.string.pt_home,
                     null, p -> {
            p.setIcon(R.drawable.home_24px);
        });
        factory.text(Prefs.SHELL, R.string.pt_shell,
                     null, p -> {
            p.setIcon(R.drawable.terminal_24px);
            p.setValue(Prefs.DEFAULT_SHELL);
        });
        factory.bool(Prefs.ENABLE_SINGLE_USE_PASSWORDS,
                     R.string.pt_single_use_passwords,
                     null, p -> {
                    p.setSummary(R.string.ps_single_use_password_info);
                    p.setChecked(true);
                });
        factory.bool(Prefs.ENABLE_PUBLIC_KEY_LOGIN,
                     R.string.pt_public_key_login,
                     null, p -> {
                    p.setSummary(R.string.pt_public_key_login_info);
                    p.setChecked(true);
                });
        // Not saved to preferences. Handled in code.
        factory.text(UserPassStorage.PK_SSHD_AUTH_USERNAME,
                     R.string.pt_authorized_username,
                     this::onUsernameChange, p -> {
                    p.setIcon(R.drawable.supervisor_account_24px);
                });
        // Not saved to preferences. Handled in code.
        factory.password(UserPassStorage.PK_SSHD_AUTH_PASSWORD,
                         R.string.pt_authorized_password,
                         this::onPasswordChange, p -> {
                    p.setIcon(R.drawable.password_24px);
                });

        factory.header(R.string.pc_ui);
        factory.singleChoice(NightMode.PK_UI_THEME_MODE, R.string.pt_ui_theme,
                             R.array.pe_ui_theme_mode,
                             R.array.pv_ui_theme_mode,
                             this::onChangeTheme, p -> {
                    p.setIcon(R.drawable.settings_brightness_24px);
                    p.setSelectedIndex(0);
                });

        return factory;
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final RecyclerView recyclerView = Objects.requireNonNull(view.findViewById(R.id.settings));
        this.settingsManager = onCreateSettings()
                .build(this, recyclerView);

        if (isTelevision) {
            // Needed as a workaround to be able to get to the last option
            // when using the d-pad 'down' key.
            recyclerView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            final int p = getResources().getDimensionPixelSize(R.dimen.tv_prefs_padding_bottom);
            recyclerView.setPadding(0, 0, 0, p);
        }

        // Allow edge-to-edge for the root view, but apply margin insets to the list itself.
        //InsetsListenerBuilder.apply(recyclerView);

        //noinspection ConstantConditions
        getActivity().getOnBackPressedDispatcher()
                     .addCallback(getViewLifecycleOwner(), backPressedCallback);

        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        if (toolbar != null) {
            toolbar.setNavigationIcon(R.drawable.arrow_back_24px);
        }

        initPortField();
        initUsernameAndPasswordFields();
    }

    /**
     * Initial display.
     * Enable/Disable the port field to clarify it will be ignored
     * when there is an explicit "-p" option.
     */
    private void initPortField() {
        final StringSetting setting = settingsManager.requireSetting(
                Prefs.DROPBEAR_CMDLINE_OPTIONS);
        final String s = setting.getValue();
        settingsManager.setEnabled(s == null || !s.contains("-p"), Prefs.SSHD_PORT);
    }

    /**
     * Hook up with the separate {@link UserPassStorage}
     * to set the initial values and state.
     *
     * @see #onUsernameChange(Setting, Object)
     * @see #onPasswordChange(Setting, Object)
     */
    private void initUsernameAndPasswordFields() {
        // init the observer BEFORE setting the field text
        vm.onPasswordSummaryUpdate().observe(getViewLifecycleOwner(), s -> {
            final PasswordSetting setting = settingsManager
                    .requireSetting(UserPassStorage.PK_SSHD_AUTH_PASSWORD);
            setting.setSummary(s);
        });

        final StringSetting pUsername = settingsManager.requireSetting(
                UserPassStorage.PK_SSHD_AUTH_USERNAME);
        final String currentUsername = vm.getUserPassDataStore().getCurrentUsername();
        pUsername.setValue(currentUsername);
        settingsManager.notifyItemChanged(pUsername);

        // The ViewModel will initially ALWAYS return a {@code null} password,
        // but  will return the current unencrypted password
        // after the user has entered it once during this settings-session
        final StringSetting pPassword = settingsManager.requireSetting(
                UserPassStorage.PK_SSHD_AUTH_PASSWORD);
        pPassword.setValue(vm.getUserPassDataStore().getCurrentPassword());
        settingsManager.notifyItemChanged(pPassword);

        // set the initial state
        final boolean hasUsername = currentUsername != null && !currentUsername.isBlank();
        settingsManager.setEnabled(hasUsername, UserPassStorage.PK_SSHD_AUTH_PASSWORD);
    }

    private boolean onRunModeChanged(@NonNull final Setting setting,
                                     @Nullable final Object newValue) {

        final BooleanSetting boot = settingsManager.requireSetting(Prefs.RUN_ON_BOOT);
        final BooleanSetting foreground = settingsManager.requireSetting(Prefs.RUN_IN_FOREGROUND);
        if (boot.isChecked() && !foreground.isChecked()) {
            foreground.setChecked(true);
        }
        return true;
    }

    private boolean onCmdLineOptionsChanged(@NonNull final Setting setting,
                                            @Nullable final Object newValue) {
        final String s = (String) newValue;
        settingsManager.setEnabled(s == null || !s.contains("-p"), Prefs.SSHD_PORT);
        return true;
    }

    private boolean onPortChange(@NonNull final Setting setting,
                                 @Nullable final Object newValue) {

        final String s = (String) newValue;
        // -1: invalid
        int port = -1;
        if (newValue != null && !s.isBlank()) {
            try {
                port = Integer.parseInt(s);
            } catch (@NonNull final NumberFormatException ignore) {
                // ignore
            }
        }

        if (port >= PORT_MIN && port <= PORT_MAX) {
            // all is fine, accept the port
            return true;
        }

        //noinspection ConstantConditions
        Snackbar.make(getView(), R.string.err_port_number,
                      Snackbar.LENGTH_LONG)
                .show();
        // reject the change
        return false;
    }

    private boolean onUsernameChange(@NonNull final Setting setting,
                                     @Nullable final Object newValue) {
        final String username = (String) newValue;

        final boolean hasUsername = username != null && !username.isBlank();
        settingsManager.setEnabled(hasUsername, UserPassStorage.PK_SSHD_AUTH_PASSWORD);

        // save manually to the setting
        ((StringSetting) setting).setValue((String) newValue);
        // and notify the sm about the change.
        settingsManager.notifyItemChanged(setting);
        // Now save to the datastore
        //noinspection DataFlowIssue
        setting.save(getContext(), vm.getUserPassDataStore());
        // but NOT to SharedPreferences,
        return false;
    }

    private boolean onPasswordChange(@NonNull final Setting setting,
                                     @Nullable final Object newValue) {

        // save manually to the setting
        ((StringSetting) setting).setValue((String) newValue);
        // and notify the sm about the change.
        settingsManager.notifyItemChanged(setting);
        // Now save to the datastore
        //noinspection DataFlowIssue
        setting.save(getContext(), vm.getUserPassDataStore());
        // but NOT to SharedPreferences,
        return false;
    }

    private boolean onChangeTheme(@NonNull final Setting setting,
                                  @Nullable final Object newValue) {
        // we should never have an invalid setting in the prefs... flw
        try {
            final int mode;
            if (newValue == null) {
                mode = 0;
            } else {
                mode = Integer.parseInt(String.valueOf(newValue));
            }
            NightMode.apply(mode);
        } catch (@NonNull final NumberFormatException ignore) {
            NightMode.apply(0);
        }
        return true;
    }
}
