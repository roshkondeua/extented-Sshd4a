package com.hardbacknutter.sshd;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds and installs a small, conservative Magisk module (id {@value #MODULE_ID}).
 * <p>
 * Content: two new files, {@code /system/bin/sshd4a} (the app's CLI: start / stop /
 * status / shell / env / help) and its short alias {@code /system/bin/sshd}, so the commands work in {@code adb shell} and in any terminal
 * app, root or not. It replaces nothing, installs no boot scripts and no SELinux
 * rules. Installation goes through Magisk's own installer
 * ({@code magisk --install-module}); it only takes effect after a reboot.
 */
public final class MagiskModule {

    static final String MODULE_ID = "sshd4a_ext";
    static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;

    private static final String CLI_NAME = "sshd4a";

    /** The stock Magisk module installer stub. */
    private static final String UPDATE_BINARY =
            "#!/sbin/sh\n"
            + "umask 022\n"
            + "ui_print() { echo \"$1\"; }\n"
            + "require_new_magisk() {\n"
            + "  ui_print \"*******************************\"\n"
            + "  ui_print \" Please install Magisk v20.4+! \"\n"
            + "  ui_print \"*******************************\"\n"
            + "  exit 1\n"
            + "}\n"
            + "OUTFD=$2\n"
            + "ZIPFILE=$3\n"
            + "mount /data 2>/dev/null\n"
            + "[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk\n"
            + ". /data/adb/magisk/util_functions.sh\n"
            + "[ $MAGISK_VER_CODE -lt 20400 ] && require_new_magisk\n"
            + "install_module\n"
            + "exit 0\n";

    /** Names added to /system/bin: the CLI itself and a short alias. */
    private static final String ALIAS_NAME = "sshd";

    private static final String CUSTOMIZE =
            "ui_print \"- Sshd4a extended: shell commands\"\n"
            + "if [ \"$API\" -lt 30 ]; then\n"
            + "  abort \"! Android 11 or newer is required\"\n"
            + "fi\n"
            + "# Never shadow something that is already there (re-installing our own file is fine).\n"
            + "for n in " + CLI_NAME + " " + ALIAS_NAME + "; do\n"
            + "  if [ -e /system/bin/$n ] && [ ! -e " + MODULE_DIR + "/system/bin/$n ]; then\n"
            + "    abort \"! /system/bin/$n already exists - refusing to shadow it\"\n"
            + "  fi\n"
            + "  set_perm $MODPATH/system/bin/$n 0 2000 0755 u:object_r:system_file:s0\n"
            + "done\n"
            + "ui_print \"- Adds: /system/bin/" + CLI_NAME + " and /system/bin/" + ALIAS_NAME + "\"\n"
            + "ui_print \"  (start|stop|status|shell|env|help)\"\n"
            + "ui_print \"- Replaces nothing, no boot scripts, no SELinux changes\"\n"
            + "ui_print \"- Reboot to activate. Remove: Magisk app, or the app's Settings\"\n";

    private MagiskModule() {
    }

    /** Human-readable summary shown in the confirmation dialog's log / docs. */
    @NonNull
    static String describe() {
        return "module id: " + MODULE_ID + "\nadds: /system/bin/" + CLI_NAME + ", /system/bin/" + ALIAS_NAME;
    }

    @NonNull
    private static byte[] readAsset(@NonNull final Context context,
                                    @NonNull final String name) throws IOException {
        try (InputStream is = context.getAssets().open(name)) {
            return is.readAllBytes();
        }
    }

    private static void put(@NonNull final ZipOutputStream zos,
                            @NonNull final String name,
                            @NonNull final byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    @NonNull
    static File buildZip(@NonNull final Context context) throws IOException {
        final PackageManager pm = context.getPackageManager();
        String versionName = "unknown";
        long versionCode = 1;
        try {
            final PackageInfo pi = pm.getPackageInfo(context.getPackageName(), 0);
            if (pi.versionName != null) {
                versionName = pi.versionName.replace("\n", " ");
            }
            versionCode = pi.getLongVersionCode();
        } catch (@NonNull final PackageManager.NameNotFoundException ignored) {
            // keep defaults
        }

        final String prop = "id=" + MODULE_ID + "\n"
                            + "name=Sshd4a extended: shell commands\n"
                            + "version=" + versionName + "\n"
                            + "versionCode=" + versionCode + "\n"
                            + "author=Sshd4a extented\n"
                            + "description=Adds the sshd4a and sshd commands (start/stop/status/shell/env/help) "
                            + "to /system/bin. Replaces nothing.\n";

        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            put(zos, "META-INF/com/google/android/update-binary",
                UPDATE_BINARY.getBytes(StandardCharsets.UTF_8));
            put(zos, "META-INF/com/google/android/updater-script",
                "#MAGISK\n".getBytes(StandardCharsets.UTF_8));
            put(zos, "module.prop", prop.getBytes(StandardCharsets.UTF_8));
            put(zos, "customize.sh", CUSTOMIZE.getBytes(StandardCharsets.UTF_8));
            put(zos, "system/bin/" + CLI_NAME, readAsset(context, CLI_NAME));
            put(zos, "system/bin/" + ALIAS_NAME,
                ("#!/system/bin/sh\nexec /system/bin/" + CLI_NAME + " \"$@\"\n")
                        .getBytes(StandardCharsets.UTF_8));
        }

        final File zip = new File(context.getCacheDir(), MODULE_ID + ".zip");
        try (FileOutputStream fos = new FileOutputStream(zip)) {
            fos.write(bos.toByteArray());
        }
        //noinspection ResultOfMethodCallIgnored
        zip.setReadable(true, false);
        return zip;
    }

    /** Install or update. MUST be called from a background thread. */
    @NonNull
    public static String install(@NonNull final Context context) {
        final File zip;
        try {
            zip = buildZip(context);
        } catch (@NonNull final IOException e) {
            return "Could not build the module zip: " + e;
        }
        final String script =
                "command -v magisk >/dev/null 2>&1 || { echo 'magisk command not found'; exit 10; }\n"
                + "magisk --install-module '" + zip.getAbsolutePath() + "'\n"
                + "exit $?\n";
        final RootProvisioner.SuResult r = RootProvisioner.runAsSuCapture(context, script);
        //noinspection ResultOfMethodCallIgnored
        zip.delete();
        if (r.exit == 0) {
            return r.output.trim() + "\n\nDone. Reboot the device to activate the module.";
        }
        return "Installation FAILED (exit " + r.exit + "). Nothing was activated.\n\n"
               + r.output.trim();
    }

    /** Mark the module for removal at the next boot (Magisk's standard mechanism). */
    @NonNull
    public static String remove(@NonNull final Context context) {
        final String script =
                "if [ -d " + MODULE_DIR + " ]; then\n"
                + "  touch " + MODULE_DIR + "/remove && echo 'Module marked for removal.'\n"
                + "else\n"
                + "  echo 'Module is not installed.'\n"
                + "fi\n"
                + "exit 0\n";
        final RootProvisioner.SuResult r = RootProvisioner.runAsSuCapture(context, script);
        if (r.exit != 0) {
            return "Removal request FAILED (exit " + r.exit + ").\n\n" + r.output.trim();
        }
        return r.output.trim() + "\n\nReboot the device to complete the removal.";
    }
}
