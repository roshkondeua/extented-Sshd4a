package com.hardbacknutter.sshd;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Invisible trampoline: starts (or stops) the SSH service and finishes at once,
 * without ever showing a window ({@code Theme.NoDisplay}).
 * <p>
 * Why an activity: since Android 12 a foreground service may not be started from
 * the background; a broadcast receiver of a stopped app does not qualify, while an
 * activity started from adb/shell does (the app is briefly in the TOP state).
 * <p>
 * From adb shell:
 * <pre>
 * am start -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.QuickStartActivity \
 *     -a com.hardbacknutter.sshd.fg.START
 * </pre>
 * (and {@code ...fg.STOP}). No intent filter on purpose: explicit component only,
 * the original Sshd4a answers the same actions. The outcome is written to logcat
 * (tag ExternalControl) and to the debug file log.
 */
public class QuickStartActivity extends Activity {

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Intent intent = getIntent();
        final String action = intent != null ? intent.getAction() : null;
        if (ControlReceiver.ACTION_START.equals(action)) {
            ExternalControl.start(getApplicationContext(), "quick-activity");
        } else if (ControlReceiver.ACTION_STOP.equals(action)) {
            ExternalControl.stop(getApplicationContext(), "quick-activity");
        }
        finish();
    }
}
