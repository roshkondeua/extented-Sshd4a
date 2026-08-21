package com.hardbacknutter.sshd.settings;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User settings.
 */
public final class Prefs {

    /** boolean. */
    static final String RUN_IN_FOREGROUND = "service.start.foreground";
    /** boolean. */
    static final String RUN_ON_BOOT = "service.start.onboot";
    /** boolean. */
    static final String RUN_ON_APP_START = "service.start.onopen";
    /** boolean. */
    static final String RUN_ON_INTENT_ALLOWED = "service.start.on.intent.allowed";
    /** boolean. */
    static final String ON_APP_EXIT_KEEP_RUNNING = "service.onappexit.keep_running";

    /** String. */
    static final String SSHD_PORT = "sshd.port";
    static final int DEFAULT_PORT = 2222;
    /** String. */
    static final String DROPBEAR_CMDLINE_OPTIONS = "dropbear.options";
    /** String. */
    static final String ENV_VARS = "sshd.env";
    /** String. */
    static final String HOME = "sshd.home";
    /** String. */
    static final String SHELL = "sshd.shell";
    static final String DEFAULT_SHELL = "/system/bin/sh";


    /** boolean. */
    static final String ENABLE_SERVICE_SHELL_ACCESS = "sshd.enable.service.shell";
    /** boolean. */
    static final String ENABLE_SINGLE_USE_PASSWORDS = "sshd.enable.single.use.password";
    /** boolean. */
    static final String ENABLE_PUBLIC_KEY_LOGIN = "sshd.enable.publickey.login";

    /**
     * Which `su` invocation style to use when generating the root/shell login
     * shell wrappers (see {@link com.hardbacknutter.sshd.RootProvisioner}).
     * Some (mostly older/32-bit) su binaries don't support the standard
     * "su -c cmd" form and need the positional "su UID sh -c cmd" form instead.
     */
    static final String SU_COMMAND_STYLE = "sshd.su.command.style";
    public static final String SU_STYLE_POSITIONAL = "positional";
    public static final String SU_STYLE_DASH_C = "dash_c";
    /** Default matches the device this fork was originally built/tested on. */
    static final String DEFAULT_SU_COMMAND_STYLE = SU_STYLE_POSITIONAL;

    /**
     * TEMPORARY diagnostic file logging - see {@link com.hardbacknutter.sshd.FileLogger}.
     */
    static final String ENABLE_FILE_LOGGING = "debug.file.logging.enabled";
    static final String FILE_LOGGING_DIR = "debug.file.logging.dir";

    /** The url from which the user last imported keys. */
    static final String IMPORT_URL = "import.url";

    /**
     * Extra command line options to pass to the dropbear executable.
     * Splits on spaces, but respects " and \
     */
    private static final Pattern CMD_OPTIONS_PATTERN = Pattern.compile(
            "\\S*\"([^\"]*)\"\\S*|(\\S+)");

    private Prefs() {
    }

    @NonNull
    public static SharedPreferences getSharedPreferences(@NonNull final Context context) {
        // Use same file name as used by the legacy androidx.preference
        return context.getSharedPreferences(
                context.getPackageName() + "_preferences",
                Context.MODE_PRIVATE);
    }

    /**
     * Start the service when the device is booted.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartOnBoot(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(RUN_ON_BOOT, false);
    }

    /**
     * Start the service when the app is started.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isRunOnAppStart(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(RUN_ON_APP_START, false);
    }

    /**
     * Whether an external Intent can start app+service and stop the service+app.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isStartByIntentAllowed(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(RUN_ON_INTENT_ALLOWED, false);
    }

    /**
     * Whether a user/manually started service should be stopped
     * when the app enters {@code #onDestroy()}.
     *
     * @param context Current context
     *
     * @return flag
     */
    public static boolean isKeepRunningOnAppExit(@NonNull final Context context) {
        return getSharedPreferences(context).getBoolean(ON_APP_EXIT_KEEP_RUNNING, false);
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
        return isRunInForeground(getSharedPreferences(context));
    }

    /**
     * How the service should be handled by the system when the App goes to the background.
     *
     * @param prefs to read from
     *
     * @return {@code true}: the service should keep running
     *         {@code false}: the system can kill the service
     */
    public static boolean isRunInForeground(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(RUN_IN_FOREGROUND, true);
    }

    /**
     * Get the bindings as configured.
     *
     * @param prefs to read from
     *
     * @return list of "[address:]port" strings
     */
    @NonNull
    public static List<String> getBindings(@NonNull final SharedPreferences prefs) {
        final List<String> userOptions = getCmdLineOptions(prefs);
        if (userOptions.contains("-p")) {
            return collectBindings(userOptions);
        } else {
            return List.of("*:" + getPort(prefs));
        }
    }

    /**
     * Collect all user configured bindings, we're NOT checking validity.
     * 1. we're assuming the user knows what they are doing... flw
     * 2. broken options will be detected in dropbear
     *
     * @param userOptions to parse
     *
     * @return list of "-p" arguments, i.e. the "addr:port" settings
     */
    @VisibleForTesting
    @NonNull
    public static List<String> collectBindings(@NonNull final List<String> userOptions) {
        final List<String> bindings = new ArrayList<>();
        final Iterator<String> it = userOptions.iterator();
        while (it.hasNext()) {
            final String s = it.next();
            if ("-p".equals(s) && it.hasNext()) {
                bindings.add(it.next());
            }
        }
        return bindings;
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
            //noinspection OverlyBroadCatchBlock
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
     *
     * @return options
     */
    @NonNull
    public static List<String> getCmdLineOptions(@NonNull final SharedPreferences prefs) {
        return splitOptions(prefs.getString(DROPBEAR_CMDLINE_OPTIONS, ""));
    }

    /**
     * Split the single String with options in a list.
     *
     * @param options to split
     *
     * @return list
     */
    @VisibleForTesting
    @NonNull
    public static List<String> splitOptions(@NonNull final CharSequence options) {
        final List<String> argList = new ArrayList<>();
        final Matcher matcher = CMD_OPTIONS_PATTERN.matcher(options);
        while (matcher.find()) {
            argList.add(matcher.group());
        }
        return argList;
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param context Current context
     * @param prefs   to read from
     *
     * @return path
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
     * Allow remote shell access to the device.
     *
     * @param prefs to read from
     *
     * @return {@code true} to enable
     */
    public static boolean isEnableServiceShellAccess(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_SERVICE_SHELL_ACCESS, true);
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return path
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
     * The configured `su` invocation style; see {@link #SU_COMMAND_STYLE}.
     *
     * @param prefs to read from
     *
     * @return one of {@link #SU_STYLE_POSITIONAL} or {@link #SU_STYLE_DASH_C}
     */
    @NonNull
    public static String getSuCommandStyle(@NonNull final SharedPreferences prefs) {
        final String style = prefs.getString(SU_COMMAND_STYLE, DEFAULT_SU_COMMAND_STYLE);
        return style == null ? DEFAULT_SU_COMMAND_STYLE : style;
    }

    /**
     * Is temporary file-based debug logging enabled?
     * See {@link com.hardbacknutter.sshd.FileLogger}.
     */
    public static boolean isFileLoggingEnabled(@NonNull final SharedPreferences prefs) {
        return prefs.getBoolean(ENABLE_FILE_LOGGING, false);
    }

    /**
     * The manually-entered directory to write the debug log file to, or
     * {@code null}/blank if not configured.
     */
    @Nullable
    public static String getFileLoggingDir(@NonNull final SharedPreferences prefs) {
        return prefs.getString(FILE_LOGGING_DIR, null);
    }

    /**
     * {@code SshdService#start_sshd} parameter.
     *
     * @param prefs to read from
     *
     * @return env vars
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
