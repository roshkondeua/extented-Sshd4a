package com.hardbacknutter.sshd.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User settings.
 */
public final class Prefs {

    /** boolean. */
    public static final String RUN_IN_FOREGROUND = "service.start.foreground";
    /** boolean. */
    static final String RUN_ON_BOOT = "service.start.onboot";

    /** String. */
    static final String SSHD_PORT = "sshd.port";
    /** String. */
    static final int DEFAULT_PORT = 2222;
    /** String. */
    static final String DROPBEAR_CMDLINE_OPTIONS = "dropbear.options";

    /** boolean. */
    private static final String ENABLE_SINGLE_USE_PASSWORDS = "sshd.enable.single.use.password";
    /** boolean. */
    private static final String ENABLE_PUBLIC_KEY_LOGIN = "sshd.enable.publickey.login";
    private static final String ENV_VARS = "sshd.env";
    private static final String HOME = "sshd.home";
    private static final String SHELL = "sshd.shell";
    private static final String DEFAULT_SHELL = "/system/bin/sh";

    /** boolean. */
    private static final String RUN_ON_APP_START = "service.start.onopen";
    /** boolean. */
    private static final String RUN_ON_INTENT_ALLOWED = "service.start.on.intent.allowed";

    /**
     * Extra command line options to pass to the dropbear executable.
     * Splits on spaces, but respects " and \
     */
    private static final Pattern CMD_OPTIONS_PATTERN = Pattern.compile(
            "\\S*\"([^\"]*)\"\\S*|(\\S+)");

    private Prefs() {
    }

    /**
     * Start the service when the device is booted.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartOnBoot(@NonNull final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(RUN_ON_BOOT, false);
    }

    /**
     * Start the service when the app is started.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isRunOnAppStart(@NonNull final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(RUN_ON_APP_START, false);
    }

    /**
     * Whether an external Intent can start app+service and stop the service+app.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartByIntentAllowed(final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(RUN_ON_INTENT_ALLOWED, false);
    }

    /**
     * How the service should be handled by the system when the App goes to the background.
     *
     * @param context Current context
     *
     * @return {@code true}: the service should keep running
     *         {@code false}: the system can kill the service
     */
    public static boolean isRunInForeground(@NonNull final Context context) {
        return PreferenceManager
                .getDefaultSharedPreferences(context)
                .getBoolean(RUN_IN_FOREGROUND, true);
    }

    /**
     * The user configured port to listen on.
     *
     * @param context Current context
     *
     * @return port as a {@code String}
     */
    @NonNull
    public static String getPort(@NonNull final Context context) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return String.valueOf(getPort(prefs));
    }

    /**
     * The user configured port to listen on.
     *
     * @param prefs to read from
     *
     * @return port as a {@code String}
     */
    public static int getPort(@NonNull final SharedPreferences prefs) {
        final String ps = prefs.getString(Prefs.SSHD_PORT, null);
        if (ps != null && !ps.isBlank()) {
            try {
                return Integer.parseInt(ps);
            } catch (@NonNull final Exception ignore) {
                // ignore
            }
        }

        return DEFAULT_PORT;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     */
    @NonNull
    public static List<String> getCmdLineOptions(@NonNull final SharedPreferences prefs) {
        final List<String> argList = new ArrayList<>();

        // add custom user defined options
        final Matcher matcher = CMD_OPTIONS_PATTERN.matcher(
                prefs.getString(DROPBEAR_CMDLINE_OPTIONS, ""));
        while (matcher.find()) {
            argList.add(matcher.group());
        }
        return argList;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     */
    @NonNull
    public static String getHomePath(@NonNull final Context context,
                                     @NonNull final SharedPreferences prefs) {
        String homePath = prefs.getString(HOME, null);
        // If never set, init to the 'files' directory. */
        if (homePath == null || !new File(homePath).exists()) {
            homePath = context.getFilesDir().getPath();
            prefs.edit().putString(HOME, homePath).apply();
        }
        return homePath;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     */
    @NonNull
    public static String getShellCmd(@NonNull final SharedPreferences prefs) {
        String shellCmd = prefs.getString(SHELL, null);
        if (shellCmd == null || !new File(shellCmd).exists()) {
            shellCmd = DEFAULT_SHELL;
        }
        return shellCmd;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     */
    @NonNull
    public static String getEnv(@NonNull final SharedPreferences prefs) {
        return prefs.getString(ENV_VARS, "");
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnablePublicKeyAuth(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_PUBLIC_KEY_LOGIN, true);
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnableSingleUsePasswordAuth(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_SINGLE_USE_PASSWORDS, true);
    }
}
