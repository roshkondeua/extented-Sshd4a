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
import java.util.List;
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
     * File with the fixed user/password.
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     * <p>
     * I should have named this "smurf_password" ...
     */
    public static final String AUTHORIZED_USERS = "master_password";
    /**
     * The traditional OpenSSH key file.
     * Stored in {@link SshdSettings#getDropbearDirectory}.
     */
    static final String AUTHORIZED_KEYS = "authorized_keys";

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
     * @return a list of all IP addresses used by the device.
     */
    @SuppressWarnings("SameParameterValue")
    @NonNull
    static List<String> getHostAddresses(final int limit) {
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

    @NonNull
    static File getDropbearDirectory(@NonNull final Context context) {
        final File path = new File(context.getFilesDir(), ".dropbear");
        if (!path.exists()) {
            path.mkdir();
        }
        return path;
    }

    /**
     * Read the user + hashed password from the dropbear file.
     *
     * @param context Current context
     *
     * @return a String array with [0] the username, and [1] the hashed/base64 password.
     */
    @Nullable
    public static String[] readPasswordFile(@NonNull final Context context) {
        final File path = getDropbearDirectory(context);
        final File file = new File(path, AUTHORIZED_USERS);
        final List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath());
            if (!lines.isEmpty()) {
                final String[] up = lines.get(0).split(":");
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "readPasswordFile"
                               + "|username: " + up[0]
                               + "|password: " + up[1]);
                }
                return up;
            }
        } catch (@NonNull final IOException ignore) {
            // ignore
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "readPasswordFile|null");
        }
        return null;
    }

    /**
     * Write credentials to the dropbear file.
     *
     * <pre>
     * username pwUpdated   password      file
     * Not-set  IGNORE      IGNORE        IGNORE      nop/DELETE
     *
     * set      FALSE       Not-set       absent      nop
     * set      FALSE       set           absent      nop
     * set      FALSE       Not-set       present     upd user
     * set      FALSE       set           present     upd user
     *
     * set      TRUE        Not-set       absent      nop
     * set      TRUE        set           absent      write both
     * set      TRUE        Not-set       present     DELETE
     * set      TRUE        set           present     write both
     * </pre>
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
            throws IOException,
                   NoSuchAlgorithmException {

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "writePasswordFile"
                       + "|username: " + username
                       + "|passwordUpdated: " + passwordUpdated
                       + "|password: " + password);
        }

        final File path = getDropbearDirectory(context);
        final File file = new File(path, AUTHORIZED_USERS);
        final boolean fileExists = file.exists();

        // below code is on purpose NOT simplified/combined for readability.

        // If we have no username,
        // Remove the credentials file.
        if (username == null || username.isBlank()) {
            if (fileExists) {
                file.delete();
            }
            return;
        }

        // At this point, we always have a non-blank username.

        // If the user explicitly removed the password,
        // Silently drop the username.
        // Remove the credentials file.
        //
        //
        if (passwordUpdated && (password == null || password.isBlank())) {
            if (fileExists) {
                file.delete();
            }
            return;
        }

        // If the file does NOT exist, we silently drop the username, and we're done.
        if (!passwordUpdated && !fileExists) {
            return;
        }

        // If the user did NOT change the password
        if (!passwordUpdated) {
            // The file exists; we must update the username, but keep the previous password.
            // Retrieve the previously hashed password,
            final String[] previous = readPasswordFile(context);
            // and rewrite the file using the new username
            // and the retrieved hashed password.
            // i.e. REPLACE username, KEEP previous password
            if (previous != null && previous.length == 2) {
                writeFile(file, username, previous[1]);
                return;
            } else {
                // We failed to read from the existing file? This should not be happening
                // unless for example the user manually fiddled with the file/permissions.
                throw new IOException("Could not read previous user/password");
            }
        }

        // We have a new user and an updated non-blank password.
        // Create the file if it does not exist yet.
        file.createNewFile();
        // and write the user and hashed password to the file.
        writeFile(file, username, hash(password));
    }

    private static void writeFile(@NonNull final File file,
                                  @NonNull final String username,
                                  @NonNull final String hash)
            throws IOException {
        try (FileWriter fw = new FileWriter(file)) {
            fw.write((username + ":" + hash).toCharArray());
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
