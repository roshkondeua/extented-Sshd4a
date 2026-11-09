package com.hardbacknutter.sshd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Headless STOP/START of the SSH service without any UI.
 * <p>
 * From adb shell:
 * <pre>
 * am broadcast -n com.roshkond.sshd4a.extended/com.hardbacknutter.sshd.ControlReceiver \
 *     -a com.hardbacknutter.sshd.fg.STOP
 * </pre>
 * {@code am broadcast} prints {@code result=<code>, data=<message>}
 * (codes: see {@link ExternalControl}).
 * <p>
 * NOTE: START from here only works while Android lets the app start a foreground
 * service from the background (it does not for a stopped/cached app: result=4,
 * ForegroundServiceStartNotAllowedException). For START use
 * {@link QuickStartActivity}, which has no UI but counts as "visible" for that rule.
 */
public class ControlReceiver extends BroadcastReceiver {

    public static final String ACTION_START = "com.hardbacknutter.sshd.fg.START";
    public static final String ACTION_STOP = "com.hardbacknutter.sshd.fg.STOP";

    @Override
    public void onReceive(@NonNull final Context context,
                          @Nullable final Intent intent) {
        if (intent == null) {
            return;
        }
        final String action = intent.getAction();
        final Context appContext = context.getApplicationContext();

        final ExternalControl.Result result;
        if (ACTION_START.equals(action)) {
            result = ExternalControl.start(appContext, "receiver");
        } else if (ACTION_STOP.equals(action)) {
            result = ExternalControl.stop(appContext, "receiver");
        } else {
            return;
        }

        // Only ordered broadcasts (the default for `am broadcast`) can carry a result.
        if (isOrderedBroadcast()) {
            setResultCode(result.code);
            setResultData(result.message);
        }
    }
}
