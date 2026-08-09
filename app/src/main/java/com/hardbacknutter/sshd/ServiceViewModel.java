package com.hardbacknutter.sshd;

import android.content.ComponentName;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Holds access to the service.
 */
@SuppressWarnings("WeakerAccess")
public class ServiceViewModel
        extends ViewModel {

    private static final String TAG = "ServiceViewModel";

    private static final int THREAD_SLEEP_MILLIS = 2000;
    private static final ExecutorService EXECUTOR_SERVICE = Executors.newSingleThreadExecutor();

    private final AtomicBoolean forceLogUpdate = new AtomicBoolean();
    private final AtomicBoolean cancelRequested = new AtomicBoolean();

    private final MutableLiveData<List<String>> logData = new MutableLiveData<>();
    private final MutableLiveData<Void> updateUi = new MutableLiveData<>();

    private boolean specialPermDialogShowing;

    @NonNull
    LiveData<List<String>> onLogUpdate() {
        return logData;
    }

    @NonNull
    LiveData<Void> onUpdateUi() {
        return updateUi;
    }

    void forceLogUpdate() {
        forceLogUpdate.set(true);
    }

    /**
     * Centralized code to trigger a UI update.
     */
    void updateUI() {
        updateUi.setValue(null);
    }

    boolean startService(@NonNull final Context context,
                         @NonNull final StartMode startMode) {
        cancelUpdateThread();
        ComponentName componentName = null;
        try {
            componentName = SshdService.startService(context, startMode);

            startUpdateThread(context);

        } catch (@NonNull final Exception e) {
            // On devices with API 31, theoretically we could see these:
            // ForegroundServiceStartNotAllowedException
            // BackgroundServiceStartNotAllowedException
            // ... but we shouldn't... flw
            Log.e(TAG, "", e);
        }

        final boolean success = componentName != null;
        updateUI();
        return success;
    }

    void stopService(@NonNull final Context context) {
        cancelUpdateThread();

        SshdService.stopService(context);
        updateUI();
    }

    /**
     * Start a thread to monitor the logfile.
     *
     * @param context Current context
     */
    @SuppressWarnings("BlockingMethodInNonBlockingContext")
    void startUpdateThread(@NonNull final Context context) {
        cancelRequested.set(false);
        final String path = SshdSettings.getDropbearDirectory(context).getPath();
        // poll for changes to the dropbear error file
        EXECUTOR_SERVICE.execute(() -> {
            final File file = new File(path, SshdSettings.DROPBEAR_ERR);
            long lastModified = 0;
            long lastLength = 0;
            while (!cancelRequested.get()) {
                final long mod = file.lastModified();
                final long len = file.length();
                if (forceLogUpdate.getAndSet(false)
                    || (mod != lastModified)
                    || (len != lastLength)) {
                    logData.postValue(collectLogLines(file));

                    lastModified = mod;
                    lastLength = len;
                }
                try {
                    //noinspection BusyWait
                    Thread.sleep(THREAD_SLEEP_MILLIS);
                } catch (@NonNull final InterruptedException e) {
                    cancelRequested.set(true);
                }
            }
        });

        updateUI();
    }

    @AnyThread
    void cancelUpdateThread() {
        cancelRequested.set(true);
    }

    /**
     * Collect up to {@link BuildConfig#NR_OF_LOG_LINES} lines from the end of the log file.
     *
     * @param file to read
     *
     * @return list
     */
    @WorkerThread
    @NonNull
    private List<String> collectLogLines(@NonNull final File file) {
        final List<String> lines = new ArrayList<>();
        //noinspection OverlyBroadCatchBlock
        try {
            if (file.exists()) {
                //noinspection ImplicitDefaultCharsetUsage
                try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        lines.add(line);
                    }
                }
            }
        } catch (@NonNull final Exception ignore) {
            // ignore
        }

        final int size = lines.size();
        if (size > BuildConfig.NR_OF_LOG_LINES) {
            return lines.subList(size - BuildConfig.NR_OF_LOG_LINES, size);
        } else {
            return lines;
        }
    }

    public boolean isSpecialPermDialogShowing() {
        return specialPermDialogShowing;
    }

    public void setSpecialPermDialogShowing(final boolean specialPermDialogShowing) {
        this.specialPermDialogShowing = specialPermDialogShowing;
    }
}
