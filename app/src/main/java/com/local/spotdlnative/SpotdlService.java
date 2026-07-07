package com.local.spotdlnative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class SpotdlService extends Service {
    public static final String ACTION_ENQUEUE = "com.local.spotdlnative.ENQUEUE";
    public static final String ACTION_HEALTH = "com.local.spotdlnative.HEALTH";
    public static final String ACTION_SEARCH_PLAYLISTS = "com.local.spotdlnative.SEARCH_PLAYLISTS";
    public static final String ACTION_STOP = "com.local.spotdlnative.STOP";
    public static final String EXTRA_ITEMS = "items";
    public static final String EXTRA_FORMAT = "format";
    public static final String EXTRA_FFMPEG = "ffmpeg";
    public static final String EXTRA_PUBLISH = "publish";
    public static final String EXTRA_WIFI_ONLY = "wifi_only";
    public static final String EXTRA_QUERY = "query";

    private static final String CHANNEL_ID = "spotdl_downloads";
    private static final int NOTIFICATION_ID = 1701;
    private static final long PROGRESS_UPDATE_MS = 15000L;
    private static final long WIFI_WAIT_UPDATE_MS = 10000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile boolean stopRequested = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_HEALTH : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRequested = true;
            StatusStore.writeDetail(this, "Stopping", "The current spotDL job will stop after the active item finishes.");
            if (running.get()) {
                updateNotification("spotDL Native", "Stopping after the active item finishes", true);
            }
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("spotDL Native", "Preparing runtime", true));

        if (!running.compareAndSet(false, true)) {
            StatusStore.writeDetail(this, "Busy", "A spotDL task is already running.");
            updateNotification("spotDL Native", "A spotDL task is already running", true);
            return START_STICKY;
        }

        final Intent jobIntent = intent == null ? new Intent(ACTION_HEALTH) : intent;
        final String jobAction = action;
        executor.execute(() -> {
            try {
                if (ACTION_ENQUEUE.equals(jobAction)) {
                    runDownload(jobIntent);
                } else if (ACTION_SEARCH_PLAYLISTS.equals(jobAction)) {
                    runPlaylistSearch(jobIntent);
                } else {
                    runHealth(jobIntent);
                }
            } finally {
                running.set(false);
                stopForeground(ACTION_ENQUEUE.equals(jobAction) ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE);
                stopSelf(startId);
            }
        });
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        executor.shutdown();
        super.onDestroy();
    }

    private void runPlaylistSearch(Intent intent) {
        String query = valueOr(intent.getStringExtra(EXTRA_QUERY), "");
        if (query.isEmpty()) {
            StatusStore.writePlaylistResults(this, "Input needed", "Enter a Spotify playlist search term.", "[]", "");
            updateNotification("spotDL Native", "Enter a Spotify playlist search term", false);
            return;
        }

        try {
            StatusStore.writePlaylistResults(this, "Searching", "Searching Spotify playlists for: " + query, "[]", "");
            updateNotification("spotDL Native", "Searching Spotify playlists", true);
            PyObject runner = runner();
            JSONObject result = new JSONObject(
                    runner.callAttr(
                            "search_playlists",
                            getFilesDir().getAbsolutePath(),
                            query,
                            10
                    ).toString()
            );
            JSONArray playlists = result.optJSONArray("playlists");
            int count = playlists == null ? 0 : playlists.length();
            if (result.optBoolean("ok")) {
                String detail = count == 0
                        ? "No Spotify playlists found for: " + query
                        : "Found " + count + " Spotify playlist(s) for: " + query;
                StatusStore.writePlaylistResults(this, "Search results", detail, playlists == null ? "[]" : playlists.toString(), result.toString(2));
                updateNotification("spotDL Native", detail, false);
            } else {
                String error = result.optString("error", "Spotify playlist search failed.");
                StatusStore.writePlaylistResults(this, "Search failed", error, "[]", result.toString(2));
                updateNotification("spotDL Native", "Spotify playlist search failed", false);
            }
        } catch (Exception exc) {
            StatusStore.writePlaylistResults(this, "Search failed", exc.getMessage(), "[]", stackTrace(exc));
            updateNotification("spotDL Native", "Spotify playlist search failed: " + exc.getMessage(), false);
        }
    }

    private void runHealth(Intent intent) {
        try {
            StatusStore.writeDetail(this, "Checking", "Loading embedded Python and spotDL.");
            PyObject runner = runner();
            String ffmpeg = bundledFfmpegOr(intent.getStringExtra(EXTRA_FFMPEG));
            JSONObject health = new JSONObject(runner.callAttr("health", getFilesDir().getAbsolutePath(), ffmpeg).toString());
            String detail = "Python " + health.optString("python", "?")
                    + ", spotDL " + health.optString("spotdl_version", "?")
                    + ", FFmpeg " + (health.optBoolean("ffmpeg_available") ? "available" : "missing");
            if (!health.optString("error").isEmpty()) {
                detail += "\n" + health.optString("error");
            }
            StatusStore.write(this, health.optBoolean("spotdl_available") ? "Ready" : "Runtime issue", detail, health.toString(2), 0, 0);
        } catch (Exception exc) {
            StatusStore.write(this, "Runtime issue", exc.getMessage(), stackTrace(exc), 0, 0);
        }
    }

    private void runDownload(Intent intent) {
        stopRequested = false;
        try {
            String rawItems = intent.getStringExtra(EXTRA_ITEMS);
            String format = valueOr(intent.getStringExtra(EXTRA_FORMAT), "mp3");
            String ffmpeg = bundledFfmpegOr(intent.getStringExtra(EXTRA_FFMPEG));
            boolean publish = intent.getBooleanExtra(EXTRA_PUBLISH, true);
            boolean wifiOnly = intent.getBooleanExtra(EXTRA_WIFI_ONLY, false);
            JSONArray items = splitItems(rawItems);
            if (items.length() == 0) {
                StatusStore.writeDetail(this, "Input needed", "Enter at least one Spotify URL or search query.");
                updateNotification("spotDL Native", "Input needed: enter a URL or search query", false);
                return;
            }

            if (!waitForWifiIfNeeded(wifiOnly)) {
                StatusStore.writeDetail(this, "Stopped", "Wi-Fi wait stopped before the download started.");
                updateNotification("spotDL Native", "Stopped before download started", false);
                return;
            }

            File outputRoot = downloadOutputRoot();
            if (!outputRoot.isDirectory() && !outputRoot.mkdirs()) {
                throw new IllegalStateException("Unable to create " + outputRoot);
            }

            StatusStore.write(this, "Running", "Downloading " + items.length() + " item(s).", "", 0, 0);
            updateNotification("spotDL Native", "Downloading " + items.length() + " item(s)", true);
            AtomicBoolean progressActive = new AtomicBoolean(true);
            Thread progressThread = startProgressNotifications(outputRoot, items.length(), progressActive);
            JSONObject result;
            try {
                PyObject runner = runner();
                result = new JSONObject(
                        runner.callAttr(
                                "download",
                                items.toString(),
                                getFilesDir().getAbsolutePath(),
                                outputRoot.getAbsolutePath(),
                                format,
                                ffmpeg
                        ).toString()
                );
            } finally {
                progressActive.set(false);
                progressThread.interrupt();
            }

            JSONArray files = result.optJSONArray("files");
            int fileCount = files == null ? 0 : files.length();
            int published = 0;
            String publishLog = "";
            if (publish && files != null && fileCount > 0) {
                StatusStore.write(this, "Publishing", "Copying downloaded tracks into Android Music.", result.optString("log_tail"), fileCount, 0);
                updateNotification("spotDL Native", "Publishing " + fileCount + " downloaded file(s)", true);
                try {
                    published = MediaPublisher.publish(this, files, outputRoot);
                } catch (Exception publishExc) {
                    publishLog = stackTrace(publishExc);
                }
            }

            String state = result.optBoolean("ok") ? "Done" : "Failed";
            String detail = result.optBoolean("ok")
                    ? "Downloaded " + fileCount + " file(s); published " + published + "."
                    : "spotDL exited with code " + result.optInt("returncode", 1) + ".";
            if (!publishLog.isEmpty()) {
                detail += "\nPublish failed; downloaded files remain in " + outputRoot.getAbsolutePath() + ".";
            }
            if (stopRequested) {
                detail += "\nStop was requested while the job was active.";
            }
            String logTail = result.optString("log_tail", result.toString(2));
            if (!publishLog.isEmpty()) {
                logTail += "\n\nPublish fallback:\n" + publishLog;
            }
            StatusStore.write(this, state, detail, logTail, fileCount, published);
            updateNotification("spotDL Native", detail, false);
        } catch (Exception exc) {
            StatusStore.write(this, "Failed", exc.getMessage(), stackTrace(exc), 0, 0);
            updateNotification("spotDL Native", "Failed: " + exc.getMessage(), false);
        }
    }

    private File downloadOutputRoot() {
        File base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) {
            base = new File(getFilesDir(), "music");
        }
        return new File(base, "spotdl");
    }

    private boolean waitForWifiIfNeeded(boolean wifiOnly) {
        if (!wifiOnly) {
            return true;
        }
        boolean waited = false;
        while (!isWifiConnected()) {
            waited = true;
            String detail = "Wi-Fi only is enabled. Connect to Wi-Fi or tap Stop to cancel.";
            StatusStore.writeDetail(this, "Waiting for Wi-Fi", detail);
            updateNotification("spotDL Native", detail, true);
            if (stopRequested) {
                return false;
            }
            try {
                Thread.sleep(WIFI_WAIT_UPDATE_MS);
            } catch (InterruptedException exc) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (waited) {
            StatusStore.writeDetail(this, "Running", "Wi-Fi connected. Starting download.");
            updateNotification("spotDL Native", "Wi-Fi connected. Starting download", true);
        }
        return !stopRequested;
    }

    private boolean isWifiConnected() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return false;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    private PyObject runner() {
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }
        return Python.getInstance().getModule("spotdl_runner");
    }

    private JSONArray splitItems(String raw) {
        JSONArray array = new JSONArray();
        if (raw == null) {
            return array;
        }
        for (String line : raw.split("\\R")) {
            String item = line.trim();
            if (!item.isEmpty() && !item.startsWith("#")) {
                array.put(item);
            }
        }
        return array;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private String bundledFfmpegOr(String explicit) {
        String selected = valueOr(explicit, "");
        if (!selected.isEmpty()) {
            return selected;
        }
        File bundled = new File(getApplicationInfo().nativeLibraryDir, "libffmpeg.so");
        return bundled.isFile() ? bundled.getAbsolutePath() : "";
    }

    private Thread startProgressNotifications(File outputRoot, int totalItems, AtomicBoolean active) {
        Thread thread = new Thread(() -> {
            long startedAt = System.currentTimeMillis();
            while (active.get()) {
                updateNotification("spotDL Native", progressText(outputRoot, totalItems, startedAt), true);
                try {
                    Thread.sleep(PROGRESS_UPDATE_MS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "spotdl-progress-notifications");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private String progressText(File outputRoot, int totalItems, long startedAt) {
        int files = countMediaFiles(outputRoot);
        String text = "Downloading " + totalItems + " item(s)";
        if (files > 0) {
            text += " - " + files + " file(s) saved";
        }
        return text + " - " + elapsedText(System.currentTimeMillis() - startedAt);
    }

    private String elapsedText(long elapsedMs) {
        long seconds = Math.max(0L, elapsedMs / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format(Locale.US, "%02d:%02d elapsed", minutes, remainingSeconds);
    }

    private int countMediaFiles(File root) {
        if (root == null || !root.isDirectory()) {
            return 0;
        }
        int count = 0;
        File[] children = root.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                count += countMediaFiles(child);
            } else if (isMediaFile(child)) {
                count++;
            }
        }
        return count;
    }

    private boolean isMediaFile(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".mp3")
                || name.endsWith(".m4a")
                || name.endsWith(".opus")
                || name.endsWith(".ogg")
                || name.endsWith(".flac")
                || name.endsWith(".wav");
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "spotDL downloads",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Download progress for spotDL Native");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String title, String text, boolean active) {
        Intent launch = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(
                this,
                0,
                launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(active)
                .setShowWhen(true)
                .setCategory(active ? Notification.CATEGORY_PROGRESS : Notification.CATEGORY_STATUS)
                .setPriority(active ? Notification.PRIORITY_LOW : Notification.PRIORITY_DEFAULT);
        if (active) {
            Intent stop = new Intent(this, SpotdlService.class).setAction(ACTION_STOP);
            PendingIntent stopIntent = PendingIntent.getService(
                    this,
                    1,
                    stop,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            builder
                    .setProgress(0, 0, true)
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent);
        }
        return builder.build();
    }

    private void updateNotification(String title, String text, boolean active) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, notification(title, text, active));
        } catch (RuntimeException ignored) {
        }
    }

    private String stackTrace(Exception exc) {
        java.io.StringWriter writer = new java.io.StringWriter();
        exc.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }
}
