package com.hardbacknutter.sshd;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import com.hardbacknutter.sshd.databinding.DialogAboutBinding;
import com.hardbacknutter.sshd.databinding.FragmentMainBinding;
import com.hardbacknutter.sshd.settings.Prefs;
import com.hardbacknutter.sshd.settings.SettingsFragment;

public class MainFragment
        extends Fragment {

    static final String TAG = "MainFragment";
    /** boolean. */
    private static final String UI_NOTIFICATION_ASK_PERMISSION = "ui.notification.ask_permission";

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(), isGranted -> {
                        if (!isGranted) {
                            //noinspection DataFlowIssue
                            PreferenceManager
                                    .getDefaultSharedPreferences(getContext())
                                    .edit()
                                    .putBoolean(UI_NOTIFICATION_ASK_PERMISSION, false)
                                    .apply();
                        }
                    });

    private ServiceViewModel vm;

    /**
     * Listen for broadcasts from the service informing us about any changes.
     * We simply react by updating the UI.
     */
    private final BroadcastReceiver messageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(@NonNull final Context context,
                              @NonNull final Intent intent) {
            vm.updateUI();
        }
    };
    private FragmentMainBinding vb;
    private ActivityResultLauncher<String> authKeysImportLauncher;
    @Nullable
    private ToolbarMenuProvider toolbarMenuProvider;
    private boolean isTelevision;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        //noinspection DataFlowIssue
        isTelevision = getContext().getPackageManager()
                                   .hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater,
                             @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState) {
        vb = FragmentMainBinding.inflate(inflater, container, false);
        return vb.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull final View view,
                              @Nullable final Bundle savedInstanceState) {

        final Toolbar toolbar = requireActivity().findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(null);

        if (isTelevision) {
            vb.tvButtons.setVisibility(View.VISIBLE);

            vb.tvBtnStartAction.setOnClickListener(v -> onMenu(R.id.start_action));
            vb.tvBtnSettings.setOnClickListener(v -> onMenu(R.id.settings));
            vb.tvBtnImportKeys.setOnClickListener(v -> onMenu(R.id.menu_import_keys));
            vb.tvBtnResetKeys.setOnClickListener(v -> onMenu(R.id.menu_reset_keys));
            vb.tvBtnHelp.setOnClickListener(v -> onMenu(R.id.menu_help));
            vb.tvBtnAbout.setOnClickListener(v -> onMenu(R.id.about));

            vb.log.setSelectAllOnFocus(true);
            vb.logScroller.setFocusable(false);

            // up/down loops through the log view and all buttons.
            // left/right not defined
            vb.log.setNextFocusDownId(vb.tvBtnStartAction.getId());
            vb.log.setNextFocusUpId(vb.tvBtnAbout.getId());
            vb.tvBtnStartAction.setNextFocusUpId(vb.log.getId());
            vb.tvBtnAbout.setNextFocusDownId(vb.log.getId());

        } else {
            vb.tvButtons.setVisibility(View.GONE);
            toolbarMenuProvider = new ToolbarMenuProvider();
            toolbar.addMenuProvider(toolbarMenuProvider, getViewLifecycleOwner());
        }

        //noinspection DataFlowIssue
        vm = new ViewModelProvider(getActivity()).get(ServiceViewModel.class);
        vm.onLogUpdate().observe(getViewLifecycleOwner(), output -> {
            // We always replace the WHOLE content. TODO: receive and append updates only
            vb.log.setText(String.join("\n", output));
            vb.logScroller.post(() -> vb.logScroller.fullScroll(ScrollView.FOCUS_DOWN));
        });
        vm.onServiceStateChanged().observe(getViewLifecycleOwner(), this::onServiceStateChanged);

        authKeysImportLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(), this::onImportAuthKeys);
    }

    @Override
    public void onResume() {
        super.onResume();

        // This is quite essential, without this permission the user cannot really use rsync/sftp
        if (!Environment.isExternalStorageManager()
            && !vm.isSpecialPermDialogShowing()) {
            requestFileAccessPermission();
        }

        // This is optional and only needed from Android 13 up
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission();
        }

        //noinspection ConstantConditions
        LocalBroadcastManager.getInstance(getContext()).registerReceiver(
                messageReceiver, new IntentFilter(SshdService.SERVICE_UI_REQUEST));

        final boolean isRunning = SshdService.isRunning();

        if (Prefs.isRunOnAppStart(getContext())
            && !isRunning) {
            vm.startService(getContext(), SshdService.StartMode.ByUser);

        } else if (isRunning) {
            vm.startUpdateThread(getContext());
        }

        populateNetworkAddressList();

        if (isTelevision) {
            vb.tvBtnStartAction.requestFocus();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private void requestNotificationPermission() {
        //noinspection ConstantConditions
        if (ContextCompat.checkSelfPermission(
                getContext(), Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {

            if (PreferenceManager.getDefaultSharedPreferences(getContext())
                                 .getBoolean(UI_NOTIFICATION_ASK_PERMISSION, true)) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
    }

    /**
     * github #12:
     * Android/Google TV 11 & 12:
     * The standard Google image does not provide the needed Dialog.
     * Some vendors <strong>might</strong> add them.
     * <p>
     * Android/Google TV 13 & 14: the dialog is always available.
     */
    private void requestFileAccessPermission() {

        final Context context = requireContext();

        final Intent intent = new Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", context.getPackageName(), null));

        @SuppressLint("QueryPermissionsNeeded")
        final List<ResolveInfo> resInfoList = context
                .getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_ALL);

        if (resInfoList.isEmpty()) {
            potentiallyUnsupportedDevice();
            return;
        }

        //noinspection ConstantConditions
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.security_24px)
                .setTitle(R.string.dialog_title_attention)
                .setMessage(R.string.msg_request_files_management)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, which) -> {
                    d.dismiss();
                    vm.setSpecialPermDialogShowing(false);
                })
                .setPositiveButton(R.string.ok, (d, which) -> {
                    d.dismiss();
                    vm.setSpecialPermDialogShowing(false);
                    try {
                        startActivity(intent);
                    } catch (@NonNull final ActivityNotFoundException e) {
                        unsupportedDevice();
                    }
                })
                .create()
                .show();

        vm.setSpecialPermDialogShowing(true);
    }

    /**
     * The intent to start {@link Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION} failed.
     * The user is likely on an Android-TV 11 or 12,
     * or on an Android-Wear device.
     * <p>
     * It <strong>might</strong> be possible to manually grant permissions.
     * Take the user to the project help pages.
     */
    private void potentiallyUnsupportedDevice() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.warning_24px)
                .setMessage(R.string.err_device_maybe_not_supported)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d1, w) -> {
                    d1.dismiss();
                    gotoProjectHelp();
                })
                .create()
                .show();
    }

    /**
     * Fatal. The device claimed to support
     * {@link Settings#ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION}
     * but failed to start the Activity.
     * We should never get here... flw
     */
    private void unsupportedDevice() {
        //noinspection DataFlowIssue
        new MaterialAlertDialogBuilder(getContext())
                .setIcon(R.drawable.error_24px)
                .setMessage(R.string.err_unexpected_error)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d1, w) -> {
                    d1.dismiss();
                    gotoProjectIssues();
                })
                .create()
                .show();
    }

    @Override
    public void onPause() {
        vm.cancelUpdateThread();
        //noinspection ConstantConditions
        LocalBroadcastManager.getInstance(getContext()).unregisterReceiver(messageReceiver);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (vm != null) {
            //noinspection ConstantConditions
            vm.stopService(getContext());
        }
        super.onDestroy();
    }

    private void onServiceStateChanged(final boolean isRunning) {
        if (toolbarMenuProvider != null) {
            updateStartButton(toolbarMenuProvider.startButton, isRunning);
        } else if (isTelevision) {
            updateStartButton(vb.tvBtnStartAction, isRunning);
        }

        populateNetworkAddressList();
    }

    private void populateNetworkAddressList() {
        // Show all interfaces, limited to 6...
        final List<String> iList = SshdSettings.getHostAddresses(6);
        if (iList.isEmpty()) {
            // should never happen... flw
            vb.allInterfaces.setText(R.string.err_no_ip);
        } else {
            vb.allInterfaces.setText(String.join("\n", iList));
        }

        // The configured interface listener(s)
        //noinspection ConstantConditions
        final SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(getContext());
        final List<String> bindings = Prefs.getBindings(prefs);
        if (bindings.isEmpty()) {
            vb.listeners.setText(R.string.err_no_ip);
        } else {
            vb.listeners.setText(String.join("\n", bindings));
        }
    }

    private void onImportAuthKeys(@Nullable final Uri uri) {
        if (uri != null) {
            //noinspection ConstantConditions
            final String error = vm.importAuthKeys(getContext(), uri);
            if (error != null) {
                // note that the state of the current key file is unknown at this point
                new MaterialAlertDialogBuilder(getContext())
                        .setIcon(R.drawable.error_24px)
                        .setTitle(R.string.dialog_title_attention)
                        .setMessage(error)
                        .setCancelable(true)
                        .setPositiveButton(R.string.ok, (d, which) -> d.dismiss())
                        .create()
                        .show();
            }
        }
    }

    private void showResetKeys() {
        final Context context = getContext();
        //noinspection ConstantConditions
        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.warning_24px)
                .setTitle(R.string.lbl_reset_keys_long)
                .setMessage(R.string.confirm_reset_keys)
                .setCancelable(true)
                .setNegativeButton(R.string.cancel, (d, which) -> d.dismiss())
                .setPositiveButton(R.string.ok, (d, which) -> vm.deleteAuthKeys(context))
                .create()
                .show();
    }

    private void showAbout() {
        final Context context = getContext();
        String version;
        //noinspection OverlyBroadCatchBlock
        try {
            //noinspection ConstantConditions
            version = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).versionName;

        } catch (@NonNull final Exception e) {
            version = getString(R.string.err_no_version);
        }

        final DialogAboutBinding dvb = DialogAboutBinding.inflate(getLayoutInflater());
        dvb.version.setText(getString(R.string.about_versions,
                                      SshdService.getDropbearVersion(),
                                      SshdService.getRsyncVersion(),
                                      SshdService.getOpensshVersion()));

        new MaterialAlertDialogBuilder(context)
                .setIcon(R.drawable.info_24px)
                .setTitle(getString(R.string.about_title, version))
                .setView(dvb.getRoot())
                .setCancelable(true)
                .setNeutralButton(R.string.lbl_github_project, (d, which) -> gotoProject())
                .setPositiveButton(R.string.ok, (d, which) -> d.dismiss())
                .create()
                .show();
    }

    private void gotoProjectHelp() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                getString(R.string.github_project_docs))));
    }

    private void gotoProjectIssues() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                getString(R.string.github_project_issues))));
    }

    private void gotoProject() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                getString(R.string.github_project_url))));
    }

    @SuppressWarnings("SameParameterValue")
    private void replaceFragment(@NonNull final Class<? extends Fragment> fragmentClass,
                                 @NonNull final String tag) {
        final FragmentManager fm = getParentFragmentManager();
        if (fm.findFragmentByTag(tag) == null) {
            fm.beginTransaction()
              .addToBackStack(tag)
              .setReorderingAllowed(true)
              .replace(R.id.fragment_container, fragmentClass, null, tag)
              .commit();
        }
    }

    /**
     * React to both the options-menu and television button-menu.
     *
     * @param itemId selected
     *
     * @return {@code true} if handled
     */
    private boolean onMenu(final int itemId) {
        if (itemId == R.id.start_action) {
            if (SshdService.isRunning()) {
                //noinspection ConstantConditions
                vm.stopService(getContext());
            } else {
                //noinspection ConstantConditions
                if (!vm.startService(getContext(), SshdService.StartMode.ByUser)) {
                    //noinspection ConstantConditions
                    Snackbar.make(getView(), R.string.err_service_failed_to_start,
                                  Snackbar.LENGTH_LONG).show();
                }
            }
        } else if (itemId == R.id.settings) {
            replaceFragment(SettingsFragment.class, SettingsFragment.TAG);
            return true;
        } else if (itemId == R.id.menu_import_keys) {
            authKeysImportLauncher.launch("*/*");
            return true;
        } else if (itemId == R.id.menu_reset_keys) {
            showResetKeys();
            return true;
        } else if (itemId == R.id.menu_help) {
            gotoProjectHelp();
            return true;
        } else if (itemId == R.id.about) {
            showAbout();
            return true;
        }
        return false;
    }

    /**
     * Update text/color of the "start" button.
     *
     * @param startButton either the toolbar or the TV button.
     * @param isRunning   flag
     */
    private void updateStartButton(@NonNull final Button startButton,
                                   final boolean isRunning) {
        if (isRunning) {
            startButton.setText(R.string.lbl_stop);
            startButton.setTextColor(getColor(R.attr.stopButtonTextColor));
        } else {
            startButton.setText(R.string.lbl_start);
            startButton.setTextColor(getColor(R.attr.startButtonTextColor));
        }
    }

    @ColorInt
    private int getColor(@AttrRes final int colorAttrId) {
        //noinspection DataFlowIssue
        final TypedArray a = getContext()
                .obtainStyledAttributes(new int[]{colorAttrId});
        try {
            return a.getColor(0, 0);
        } finally {
            a.recycle();
        }
    }

    private class ToolbarMenuProvider
            implements MenuProvider {

        private Button startButton;

        @Override
        public void onCreateMenu(@NonNull final Menu menu,
                                 @NonNull final MenuInflater menuInflater) {
            menuInflater.inflate(R.menu.main_menu, menu);

            final MenuItem menuItem = menu.findItem(R.id.start_action);
            //noinspection DataFlowIssue
            startButton = menuItem.getActionView().findViewById(R.id.btn_start);
            startButton.setOnClickListener(v -> onMenu(R.id.start_action));
        }

        @Override
        public boolean onMenuItemSelected(@NonNull final MenuItem menuItem) {
            return onMenu(menuItem.getItemId());
        }
    }
}
