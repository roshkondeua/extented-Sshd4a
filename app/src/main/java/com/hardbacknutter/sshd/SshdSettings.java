package com.hardbacknutter.sshd;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Provides read/write methods for the dropbear master password.
 * Lookup the dropbear (hidden) configuration directory.
 * Enumerate the device host addresses.
 * <p>
 * The file names for keys and passwords are also defined in:
 * <ul>
 *     <li>"cpp/jni-dropbear.h"</li>
 *     <li>"res/xml/backup_rules.xml"</li>
 *     <li>"res/xml/data_extraction_rules.xml"</li>
 * </ul>
 *
 * Dev note: we could just merge this into the SshdService class.
 */
@SuppressWarnings({
        "BlockingMethodInNonBlockingContext",
        "ResultOfMethodCallIgnored",
        "ImplicitDefaultCharsetUsage"})
public final class SshdSettings {

    /**
     * File with the fixed user/password(s).
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     * <p>
     * One line per configured role: "username:sha512base64hash[:role]"
     * The role field is omitted for the (legacy/default) "user" role for
     * backwards compatibility with the native code's parsing.
     * <p>
     * I should have named this "smurf_password" ...
     */
    public static final String AUTHORIZED_USERS = "master_password";

    /** Role identifiers - MUST match the SSHD4A_ROLE_* defines in "cpp/jni-dropbear.h". */
    public static final String ROLE_USER = "user";
    public static final String ROLE_ROOT = "root";
    public static final String ROLE_SHELL = "shell";
    /**
     * The traditional OpenSSH key file.
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     */
    public static final String AUTHORIZED_KEYS = "authorized_keys";

    /**
     * Logfile for the native code.
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     */
    static final String DROPBEAR_ERR = "dropbear.err";

    private static final String TAG = "SshdSettings";

    private SshdSettings() {
    }

    /**
     * Typically only returns a single address, but there could be a list/mix of IPv4 and IPv6.
     * <p>
     * See <a href="https://developer.android.com/studio/run/emulator-networking">emulator</a>.
     *
     * @param limit max number of entries to return
     *
     * @return a list of all IP addresses used by the device.
     */
    @SuppressWarnings("SameParameterValue")
    @NonNull
    public static List<String> getHostAddresses(final int limit) {
        //noinspection OverlyBroadCatchBlock
        try {
            return Collections
                    .list(NetworkInterface.getNetworkInterfaces())
                    .stream()
                    .flatMap(ni -> Collections.list(ni.getInetAddresses()).stream())
                    .filter(ina -> !ina.isLoopbackAddress() && !ina.isLinkLocalAddress())
                    .map(InetAddress::getHostAddress)
                    .filter(Objects::nonNull)
                    // sorting will move IPv6 to the end of the list
                    .sorted()
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (@NonNull final Exception ignore) {
            // ignore
        }

        return new ArrayList<>();
    }

    /**
     * Get the directory were the dropbear configuration files are stored.
     * This if the ".dropbear" directory in the standard/internal Files folder.
     *
     * @param context Current context
     *
     * @return File
     */
    @NonNull
    public static File getDropbearDirectory(@NonNull final Context context) {
        final File path = new File(context.getFilesDir(), ".dropbear");
        if (!path.exists()) {
            path.mkdir();
        }
        return path;
    }

    /**
     * Read the user + hashed password for the (legacy) default "user" role.
     * Kept for backwards API compatibility - delegates to {@link #readAuthorizedUser}.
     *
     * @param context Current context
     *
     * @return a String array with [0] the username, and [1] the hashed/base64 password.
     */
    @Nullable
    public static String[] readPasswordFile(@NonNull final Context context) {
        return readAuthorizedUser(context, ROLE_USER);
    }

    /**
     * Read the username + hashed password for a single role.
     *
     * @param context Current context
     * @param role    one of {@link #ROLE_USER}, {@link #ROLE_ROOT}, {@link #ROLE_SHELL}
     *
     * @return a String array with [0] the username, and [1] the hashed/base64 password,
     *         or {@code null} if that role has no entry.
     */
    @Nullable
    public static String[] readAuthorizedUser(@NonNull final Context context,
                                              @NonNull final String role) {
        final Map<String, String[]> all = readAllAuthorizedUsers(context);
        final String[] up = all.get(role);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "readAuthorizedUser|role: " + role
                       + "|username: " + (up != null ? up[0] : null));
        }
        return up;
    }

    /**
     * Read all configured role entries from the AUTHORIZED_USERS file.
     *
     * @param context Current context
     *
     * @return map of role -> [username, hashed/base64 password]; roles without
     *         a valid line are simply absent from the map.
     */
    @NonNull
    public static Map<String, String[]> readAllAuthorizedUsers(@NonNull final Context context) {
        final Map<String, String[]> result = new LinkedHashMap<>();
        final File path = getDropbearDirectory(context);
        final File file = new File(path, AUTHORIZED_USERS);
        try {
            final List<String> lines = Files.readAllLines(file.toPath());
            for (final String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                final String[] parts = line.split(":", 3);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    continue;
                }
                final String role = parts.length >= 3 && !parts[2].isBlank()
                        ? parts[2] : ROLE_USER;
                result.put(role, new String[]{parts[0], parts[1]});
            }
        } catch (@NonNull final IOException ignore) {
            // file does not exist (yet) -> empty map
        }
        return result;
    }

    /**
     * Write credentials to the dropbear file for the (legacy) default "user" role.
     * Kept for backwards API compatibility - delegates to {@link #writeAuthorizedUser}.
     *
     * @param context         Current context
     * @param username        (optional)
     * @param passwordUpdated flag
     * @param password        (optional)
     *
     * @throws IOException              if reading or writing the file failed
     * @throws NoSuchAlgorithmException if we couldn't hash the password (should never happen)
     */
    public static void writePasswordFile(@NonNull final Context context,
                                         @Nullable final String username,
                                         final boolean passwordUpdated,
                                         @Nullable final String password)
            throws IOException, NoSuchAlgorithmException {
        writeAuthorizedUser(context, ROLE_USER, username, passwordUpdated, password);
    }

    /**
     * Write (or remove) credentials for a single role, leaving the other
     * roles' entries in the file untouched.
     *
     * <pre>
     * username pwUpdated   password      role had entry?   action
     * Not-set  IGNORE      IGNORE        IGNORE             remove role's entry (if any)
     *
     * set      FALSE       Not-set       no                 nop
     * set      FALSE       set           no                 nop
     * set      FALSE       Not-set       yes                update username, keep old hash
     * set      FALSE       set           yes                update username, keep old hash
     *
     * set      TRUE        Not-set       no                 nop
     * set      TRUE        set           no                 write both (new entry)
     * set      TRUE        Not-set       yes                remove role's entry
     * set      TRUE        set           yes                write both (new hash)
     * </pre>
     *
     * @param context         Current context
     * @param role            one of {@link #ROLE_USER}, {@link #ROLE_ROOT}, {@link #ROLE_SHELL}
     * @param username        (optional)
     * @param passwordUpdated flag
     * @param password        (optional)
     *
     * @throws IOException              if reading or writing the file failed
     * @throws NoSuchAlgorithmException if we couldn't hash the password (should never happen)
     */
    public static void writeAuthorizedUser(@NonNull final Context context,
                                           @NonNull final String role,
                                           @Nullable final String username,
                                           final boolean passwordUpdated,
                                           @Nullable final String password)
            throws IOException, NoSuchAlgorithmException {

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "writeAuthorizedUser"
                       + "|role: " + role
                       + "|username: " + username
                       + "|passwordUpdated: " + passwordUpdated);
        }

        final Map<String, String[]> all = readAllAuthorizedUsers(context);
        final boolean hadEntry = all.containsKey(role);

        // below code is on purpose NOT simplified/combined for readability;
        // mirrors the original single-role truth table, now scoped per-role.

        if (username == null || username.isBlank()) {
            all.remove(role);
            writeAllAuthorizedUsers(context, all);
            return;
        }

        if (!passwordUpdated) {
            if (!hadEntry) {
                // nop - no previous entry, and the user isn't setting a password now either.
                return;
            }
            // keep the previous hash, just update the username
            final String[] previous = all.get(role);
            all.put(role, new String[]{username, previous[1]});
            writeAllAuthorizedUsers(context, all);
            return;
        }

        // passwordUpdated == true
        if (password == null || password.isBlank()) {
            if (!hadEntry) {
                // nop
                return;
            }
            // explicit password removal -> drop this role's entry entirely
            all.remove(role);
            writeAllAuthorizedUsers(context, all);
            return;
        }

        // new/updated non-blank password
        all.put(role, new String[]{username, hash(password)});
        writeAllAuthorizedUsers(context, all);
    }

    /**
     * Rewrite the whole AUTHORIZED_USERS file from the given role->[user,hash] map.
     * Deletes the file entirely if the map is empty.
     */
    private static void writeAllAuthorizedUsers(@NonNull final Context context,
                                                @NonNull final Map<String, String[]> all)
            throws IOException {
        final File path = getDropbearDirectory(context);
        final File file = new File(path, AUTHORIZED_USERS);

        if (all.isEmpty()) {
            if (file.exists()) {
                file.delete();
            }
            return;
        }

        file.createNewFile();
        try (FileWriter fw = new FileWriter(file)) {
            for (final Map.Entry<String, String[]> entry : all.entrySet()) {
                final String role = entry.getKey();
                final String[] up = entry.getValue();
                // "user" role is written WITHOUT the role field for backwards
                // compatibility (native code defaults missing field to ROLE_USER).
                if (ROLE_USER.equals(role)) {
                    fw.write(up[0] + ":" + up[1] + "\n");
                } else {
                    fw.write(up[0] + ":" + up[1] + ":" + role + "\n");
                }
            }
        }
    }

    /**
     * Hash with SHA-512, and convert to a base64 string.
     *
     * @param password to hash
     *
     * @return base64 string
     *
     * @throws NoSuchAlgorithmException on ...
     */
    @NonNull
    private static String hash(@NonNull final String password)
            throws NoSuchAlgorithmException {
        final MessageDigest md = MessageDigest.getInstance("SHA-512");
        final byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }
}
