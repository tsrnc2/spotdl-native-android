package com.local.spotdlnative;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int COLOR_BG = 0xFF101412;
    private static final int COLOR_SURFACE = 0xFF1A211D;
    private static final int COLOR_SURFACE_ALT = 0xFF222B26;
    private static final int COLOR_FIELD = 0xFF0D100F;
    private static final int COLOR_STROKE = 0xFF344039;
    private static final int COLOR_TEXT = 0xFFF4F8F5;
    private static final int COLOR_MUTED = 0xFFA7B2AC;
    private static final int COLOR_ACCENT = 0xFF1ED760;
    private static final int COLOR_ACCENT_DARK = 0xFF0C2A18;
    private static final int COLOR_WARNING = 0xFFF0B84A;
    private static final int COLOR_DANGER = 0xFFE84D5B;
    private static final Pattern SHARED_LINK_PATTERN = Pattern.compile("(?i)\\b(?:https?://|spotify:)[^\\s<>\"]+");
    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText playlistSearch;
    private LinearLayout playlistResults;
    private String renderedPlaylistResults = null;
    private EditText input;
    private EditText ffmpegPath;
    private Spinner format;
    private CheckBox publish;
    private CheckBox wifiOnly;
    private CheckBox background;
    private LinearLayout helpPanel;
    private boolean helpVisible = false;
    private TextView status;
    private TextView counts;
    private TextView detail;
    private TextView log;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshStatus();
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        handleIncomingIntent(getIntent());
        requestRuntimePermissions();
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    
    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, new IntentFilter(StatusStore.ACTION_STATUS_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, new IntentFilter(StatusStore.ACTION_STATUS_CHANGED));
        }
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        try {
            unregisterReceiver(statusReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(24));
        root.setBackgroundColor(COLOR_BG);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("spotDL Native");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Spotify playlists, tracks, and albums in one native queue.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setPadding(0, dp(4), 0, dp(12));
        root.addView(subtitle);

        status = badge("Idle");
        root.addView(status);

        counts = label("");
        counts.setPadding(0, dp(8), 0, dp(8));
        root.addView(counts);

        detail = label("Ready.");
        detail.setTextColor(COLOR_TEXT);
        detail.setPadding(0, 0, 0, dp(12));
        root.addView(detail);

        root.addView(section("Queue"));
        playlistSearch = new EditText(this);
        playlistSearch.setSingleLine(true);
        playlistSearch.setHint("Playlist search");
        playlistSearch.setTextColor(COLOR_TEXT);
        playlistSearch.setHintTextColor(COLOR_MUTED);
        playlistSearch.setBackground(rounded(COLOR_FIELD, COLOR_STROKE, 8, 1));
        playlistSearch.setPadding(dp(10), dp(10), dp(10), dp(10));
        styleField(playlistSearch);
        root.addView(playlistSearch, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button spotifyPlaylistSearch = button("Search Spotify");
        spotifyPlaylistSearch.setOnClickListener(v -> startPlaylistSearch());
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
        );
        searchParams.setMargins(0, dp(8), 0, 0);
        root.addView(spotifyPlaylistSearch, searchParams);

        LinearLayout playlistButtons = new LinearLayout(this);
        playlistButtons.setOrientation(LinearLayout.HORIZONTAL);
        playlistButtons.setPadding(0, dp(8), 0, dp(10));
        root.addView(playlistButtons);

        Button queuePlaylist = button("Queue Playlist");
        queuePlaylist.setOnClickListener(v -> queuePlaylistSearch(false));
        playlistButtons.addView(queuePlaylist, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button downloadPlaylist = button("Download Playlist");
        downloadPlaylist.setOnClickListener(v -> queuePlaylistSearch(true));
        LinearLayout.LayoutParams downloadPlaylistParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        downloadPlaylistParams.setMargins(dp(8), 0, 0, 0);
        playlistButtons.addView(downloadPlaylist, downloadPlaylistParams);

        playlistResults = new LinearLayout(this);
        playlistResults.setOrientation(LinearLayout.VERTICAL);
        playlistResults.setPadding(0, 0, 0, dp(8));
        root.addView(playlistResults, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        input = new EditText(this);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("https://open.spotify.com/playlist/...\nplaylist: road trip classics\nartist - track");
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_MUTED);
        input.setBackground(rounded(COLOR_FIELD, COLOR_STROKE, 8, 1));
        input.setPadding(dp(10), dp(10), dp(10), dp(10));
        styleField(input);
        root.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(118)
        ));

        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(0, dp(10), 0, dp(6));
        root.addView(options);

        LinearLayout formatRow = new LinearLayout(this);
        formatRow.setOrientation(LinearLayout.HORIZONTAL);
        formatRow.setGravity(Gravity.CENTER_VERTICAL);
        options.addView(formatRow);

        format = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{"mp3", "m4a", "opus", "ogg", "flac", "wav"}
        ) {
            
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(COLOR_TEXT);
                view.setTextSize(14);
                return view;
            }

            
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(COLOR_BG);
                view.setTextSize(14);
                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        format.setBackground(rounded(COLOR_SURFACE_ALT, COLOR_STROKE, 8, 1));
        format.setAdapter(adapter);
        formatRow.addView(format, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout toggles = new LinearLayout(this);
        toggles.setOrientation(LinearLayout.VERTICAL);
        toggles.setPadding(0, dp(8), 0, 0);
        options.addView(toggles);

        publish = new CheckBox(this);
        publish.setText("Publish to Music");
        publish.setChecked(true);
        styleCheckBox(publish);
        toggles.addView(publish);

        wifiOnly = new CheckBox(this);
        wifiOnly.setText("Wi-Fi only");
        wifiOnly.setChecked(true);
        styleCheckBox(wifiOnly);
        toggles.addView(wifiOnly);

        background = new CheckBox(this);
        background.setText("Background");
        background.setChecked(true);
        styleCheckBox(background);
        toggles.addView(background);

        ffmpegPath = new EditText(this);
        ffmpegPath.setSingleLine(true);
        ffmpegPath.setHint("Optional FFmpeg path inside app files");
        ffmpegPath.setTextColor(COLOR_TEXT);
        ffmpegPath.setHintTextColor(COLOR_MUTED);
        styleField(ffmpegPath);
        root.addView(ffmpegPath, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(0, dp(12), 0, dp(12));
        root.addView(buttons);

        Button download = button("Download");
        download.setOnClickListener(v -> startDownload());
        buttons.addView(download, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button health = button("Health");
        health.setOnClickListener(v -> startHealth());
        LinearLayout.LayoutParams healthParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        healthParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(health, healthParams);

        Button stop = button("Stop");
        stop.setOnClickListener(v -> startAction(SpotdlService.ACTION_STOP));
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        stopParams.setMargins(dp(8), 0, 0, 0);
        buttons.addView(stop, stopParams);

        LinearLayout secondaryButtons = new LinearLayout(this);
        secondaryButtons.setOrientation(LinearLayout.HORIZONTAL);
        secondaryButtons.setPadding(0, 0, 0, dp(12));
        root.addView(secondaryButtons);

        Button settings = button("App Settings");
        settings.setOnClickListener(v -> openAppSettings());
        secondaryButtons.addView(settings, new LinearLayout.LayoutParams(0, dp(44), 1f));

        Button help = button("Help");
        help.setOnClickListener(v -> toggleHelp());
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
        helpParams.setMargins(dp(8), 0, 0, 0);
        secondaryButtons.addView(help, helpParams);

        helpPanel = buildHelpPanel();
        helpPanel.setVisibility(View.GONE);
        root.addView(helpPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        root.addView(section("Log"));
        log = label("");
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextSize(12);
        log.setTextColor(COLOR_TEXT);
        log.setBackground(rounded(COLOR_FIELD, COLOR_STROKE, 8, 1));
        log.setPadding(dp(10), dp(10), dp(10), dp(10));
        root.addView(log, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        setContentView(scroll);
    }

    private void startPlaylistSearch() {
        String query = playlistSearch.getText().toString().trim();
        if (query.isEmpty()) {
            detail.setText("Enter a Spotify playlist search term.");
            return;
        }
        Intent intent = baseServiceIntent(SpotdlService.ACTION_SEARCH_PLAYLISTS);
        intent.putExtra(SpotdlService.EXTRA_QUERY, query);
        startServiceCompat(intent);
    }

    private void queuePlaylistSearch(boolean downloadNow) {
        String query = playlistSearch.getText().toString().trim();
        if (query.isEmpty()) {
            detail.setText("Enter a playlist name or playlist URL.");
            return;
        }

        String item = isLikelyUrl(query) || query.toLowerCase(Locale.US).startsWith("playlist:")
                ? query
                : "playlist:" + query;
        appendItemsToQueue(java.util.Collections.singletonList(item));
        playlistSearch.setText("");
        detail.setText(downloadNow ? "Queued playlist search and starting download." : "Queued playlist search.");
        if (downloadNow) {
            startDownload();
        }
    }

    private void startDownload() {
        Intent intent = baseServiceIntent(SpotdlService.ACTION_ENQUEUE);
        intent.putExtra(SpotdlService.EXTRA_ITEMS, input.getText().toString());
        intent.putExtra(SpotdlService.EXTRA_FORMAT, String.valueOf(format.getSelectedItem()));
        intent.putExtra(SpotdlService.EXTRA_FFMPEG, ffmpegPath.getText().toString());
        intent.putExtra(SpotdlService.EXTRA_PUBLISH, publish.isChecked());
        intent.putExtra(SpotdlService.EXTRA_WIFI_ONLY, wifiOnly != null && wifiOnly.isChecked());
        boolean sendToBackground = background != null && background.isChecked();
        startServiceCompat(intent);
        if (sendToBackground) {
            moveTaskToBack(true);
        }
    }

    private void startHealth() {
        Intent intent = baseServiceIntent(SpotdlService.ACTION_HEALTH);
        intent.putExtra(SpotdlService.EXTRA_FFMPEG, ffmpegPath.getText().toString());
        startServiceCompat(intent);
    }

    private void startAction(String action) {
        startServiceCompat(baseServiceIntent(action));
    }

    private void handleIncomingIntent(Intent intent) {
        List<String> items = incomingItems(intent);
        if (items.isEmpty()) {
            return;
        }
        appendItemsToQueue(items);
        if (detail != null) {
            detail.setText("Added " + items.size() + " shared item(s) to the queue.");
        }
    }

    private List<String> incomingItems(Intent intent) {
        List<String> items = new ArrayList<>();
        if (intent == null) {
            return items;
        }

        String action = intent.getAction();
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(action) && data != null) {
            items.add(data.toString());
            return items;
        }

        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (extraText != null) {
                items.addAll(extractSharedItems(extraText.toString()));
            }
        }
        return items;
    }

    private List<String> extractSharedItems(String text) {
        Set<String> items = new LinkedHashSet<>();
        Matcher matcher = SHARED_LINK_PATTERN.matcher(text);
        while (matcher.find()) {
            String item = cleanSharedItem(matcher.group());
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        if (items.isEmpty()) {
            String fallback = cleanSharedItem(text);
            if (!fallback.isEmpty()) {
                items.add(fallback);
            }
        }
        return new ArrayList<>(items);
    }

    private void appendItemsToQueue(List<String> items) {
        Set<String> existing = new LinkedHashSet<>();
        for (String line : input.getText().toString().split("\\R")) {
            String item = line.trim();
            if (!item.isEmpty()) {
                existing.add(item);
            }
        }

        boolean changed = false;
        for (String raw : items) {
            String item = cleanSharedItem(raw);
            if (!item.isEmpty() && existing.add(item)) {
                changed = true;
            }
        }
        if (!changed) {
            return;
        }

        StringBuilder builder = new StringBuilder();
        for (String item : existing) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(item);
        }
        input.setText(builder.toString());
        input.setSelection(input.getText().length());
    }

    private String cleanSharedItem(String item) {
        String cleaned = item == null ? "" : item.trim();
        while (cleaned.length() > 1 && ".,;)]}".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.startsWith("<") && cleaned.endsWith(">") && cleaned.length() > 2) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private boolean isLikelyUrl(String text) {
        String lower = text.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("spotify:");
    }

    private Intent baseServiceIntent(String action) {
        return new Intent(this, SpotdlService.class).setAction(action);
    }

    private void startServiceCompat(Intent intent) {
        if (SpotdlService.ACTION_STOP.equals(intent.getAction())) {
            startService(intent);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void refreshStatus() {
        SharedPreferences prefs = StatusStore.prefs(this);
        String state = prefs.getString("state", "Idle");
        String detailText = prefs.getString("detail", "Ready.");
        String logText = prefs.getString("log", "");
        String playlistResultsText = prefs.getString("playlist_results", "[]");
        int files = prefs.getInt("files", 0);
        int published = prefs.getInt("published", 0);
        long updated = prefs.getLong("updated", 0);

        status.setText(state);
        status.setTextColor(colorForState(state));
        detail.setText(detailText == null || detailText.isEmpty() ? "Ready." : detailText);
        counts.setText("Files: " + files + "   Published: " + published + (updated > 0 ? "   Updated: " + android.text.format.DateFormat.format("HH:mm:ss", updated) : ""));
        log.setText(logText == null || logText.isEmpty() ? "No log yet." : logText);
        renderPlaylistResults(playlistResultsText);
    }

    private void renderPlaylistResults(String resultsJson) {
        if (playlistResults == null) {
            return;
        }
        String normalized = resultsJson == null || resultsJson.trim().isEmpty() ? "[]" : resultsJson;
        if (normalized.equals(renderedPlaylistResults)) {
            return;
        }
        renderedPlaylistResults = normalized;
        playlistResults.removeAllViews();
        try {
            JSONArray results = new JSONArray(normalized);
            if (results.length() == 0) {
                return;
            }
            playlistResults.addView(section("Spotify playlist results"));
            for (int index = 0; index < results.length(); index++) {
                addPlaylistResult(results.getJSONObject(index));
            }
        } catch (Exception exc) {
            TextView error = label("Unable to show playlist results.");
            error.setTextColor(COLOR_DANGER);
            playlistResults.addView(error);
        }
    }

    private void addPlaylistResult(JSONObject playlist) {
        final String url = playlist.optString("url", "").trim();
        if (url.isEmpty()) {
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(10), dp(10), dp(10), dp(10));
        row.setBackground(rounded(COLOR_SURFACE, COLOR_STROKE, 8, 1));

        TextView name = label(playlist.optString("name", "Untitled playlist"));
        name.setTextColor(COLOR_TEXT);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        playlistResults.addView(row, resultLayoutParams());
        row.addView(name);

        TextView meta = label(playlistSummary(playlist));
        meta.setTextColor(COLOR_MUTED);
        meta.setPadding(0, dp(2), 0, 0);
        row.addView(meta);

        String description = cleanDescription(playlist.optString("description", ""));
        if (!description.isEmpty()) {
            TextView desc = label(description);
            desc.setTextColor(COLOR_TEXT);
            desc.setPadding(0, dp(6), 0, 0);
            row.addView(desc);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        row.addView(actions);

        Button queue = button("Queue");
        queue.setOnClickListener(v -> {
            appendItemsToQueue(java.util.Collections.singletonList(url));
            detail.setText("Queued Spotify playlist: " + playlist.optString("name", url));
        });
        actions.addView(queue, new LinearLayout.LayoutParams(0, dp(40), 1f));

        Button download = button("Download");
        download.setOnClickListener(v -> {
            appendItemsToQueue(java.util.Collections.singletonList(url));
            detail.setText("Queued Spotify playlist and starting download.");
            startDownload();
        });
        LinearLayout.LayoutParams downloadParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        downloadParams.setMargins(dp(8), 0, 0, 0);
        actions.addView(download, downloadParams);
    }

    private LinearLayout.LayoutParams resultLayoutParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        return params;
    }

    private String playlistSummary(JSONObject playlist) {
        String owner = playlist.optString("owner", "Spotify");
        int tracks = playlist.optInt("tracks", 0);
        return owner + " - " + tracks + " track" + (tracks == 1 ? "" : "s");
    }

    private String cleanDescription(String description) {
        String cleaned = description == null ? "" : description.replaceAll("<[^>]*>", "").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 157) + "..." : cleaned;
    }

    private int colorForState(String state) {
        if ("Done".equals(state) || "Ready".equals(state) || "Search results".equals(state)) {
            return COLOR_ACCENT;
        }
        if ("Failed".equals(state) || "Runtime issue".equals(state) || "Search failed".equals(state)) {
            return COLOR_DANGER;
        }
        if ("Running".equals(state) || "Publishing".equals(state) || "Checking".equals(state) || "Searching".equals(state) || "Waiting for Wi-Fi".equals(state)) {
            return COLOR_WARNING;
        }
        return COLOR_MUTED;
    }

    private LinearLayout buildHelpPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));
        panel.setBackground(rounded(COLOR_SURFACE, COLOR_STROKE, 8, 1));

        TextView title = label("Help");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(6));
        panel.addView(title);

        addHelpLine(panel, "Queue Spotify links, spotify: URLs, YouTube links, or plain artist - track searches.");
        addHelpLine(panel, "Search Spotify finds playlists, then Queue or Download adds the selected playlist URL.");
        addHelpLine(panel, "Wi-Fi only holds downloads until Wi-Fi is connected; turn it off to allow mobile data.");
        addHelpLine(panel, "Background moves the app out of the way while the foreground notification shows status and Stop.");
        addHelpLine(panel, "Publish to Music copies completed files into Android Music; if publishing fails, files stay in the app spotdl folder.");
        addHelpLine(panel, "Health checks embedded Python, spotDL, and FFmpeg before a download.");
        return panel;
    }

    private void addHelpLine(LinearLayout panel, String text) {
        TextView line = label(text);
        line.setTextColor(COLOR_MUTED);
        line.setPadding(0, dp(4), 0, dp(4));
        panel.addView(line);
    }

    private void toggleHelp() {
        helpVisible = !helpVisible;
        if (helpPanel != null) {
            helpPanel.setVisibility(helpVisible ? View.VISIBLE : View.GONE);
        }
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1002);
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private TextView section(String text) {
        TextView view = new TextView(this);
        view.setText(text.toUpperCase(Locale.US));
        view.setTextColor(COLOR_ACCENT);
        view.setTextSize(12);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(0, dp(16), 0, dp(8));
        return view;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setTextColor(COLOR_MUTED);
        view.setLineSpacing(0, 1.08f);
        return view;
    }

    private TextView badge(String text) {
        TextView view = label(text);
        view.setTextSize(18);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(10), dp(6), dp(10), dp(6));
        view.setBackground(rounded(COLOR_SURFACE_ALT, COLOR_STROKE, 8, 1));
        return view;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(buttonTextColor(text));
        button.setBackground(rounded(buttonBackgroundColor(text), buttonStrokeColor(text), 8, 1));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private void styleField(EditText view) {
        view.setTextColor(COLOR_TEXT);
        view.setHintTextColor(COLOR_MUTED);
        view.setBackground(rounded(COLOR_FIELD, COLOR_STROKE, 8, 1));
    }

    private void styleCheckBox(CheckBox view) {
        view.setTextColor(COLOR_TEXT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            int[][] states = new int[][]{
                    new int[]{android.R.attr.state_checked},
                    new int[]{}
            };
            int[] colors = new int[]{COLOR_ACCENT, COLOR_MUTED};
            view.setButtonTintList(new ColorStateList(states, colors));
        }
    }

    private int buttonTextColor(String text) {
        if (isPrimaryButton(text)) {
            return COLOR_ACCENT_DARK;
        }
        return COLOR_TEXT;
    }

    private int buttonBackgroundColor(String text) {
        if (isDangerButton(text)) {
            return COLOR_DANGER;
        }
        if (isPrimaryButton(text)) {
            return COLOR_ACCENT;
        }
        return COLOR_SURFACE_ALT;
    }

    private int buttonStrokeColor(String text) {
        if (isDangerButton(text)) {
            return COLOR_DANGER;
        }
        if (isPrimaryButton(text)) {
            return COLOR_ACCENT;
        }
        return COLOR_STROKE;
    }

    private boolean isPrimaryButton(String text) {
        return text.contains("Download") || text.contains("Search Spotify");
    }

    private boolean isDangerButton(String text) {
        return "Stop".equals(text);
    }

    private GradientDrawable rounded(int color, int strokeColor, int radiusDp, int strokeWidthDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0) {
            drawable.setStroke(dp(strokeWidthDp), strokeColor);
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
