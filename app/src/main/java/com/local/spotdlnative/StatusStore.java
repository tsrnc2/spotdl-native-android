package com.local.spotdlnative;

import android.content.Context;
import android.content.SharedPreferences;

final class StatusStore {
    static final String PREFS = "spotdl_status";
    static final String ACTION_STATUS_CHANGED = "com.local.spotdlnative.STATUS_CHANGED";

    private StatusStore() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void write(Context context, String state, String detail, String log, int files, int published) {
        prefs(context)
                .edit()
                .putString("state", state)
                .putString("detail", detail)
                .putString("log", log)
                .putInt("files", files)
                .putInt("published", published)
                .putLong("updated", System.currentTimeMillis())
                .apply();
        context.sendBroadcast(new android.content.Intent(ACTION_STATUS_CHANGED).setPackage(context.getPackageName()));
    }

    static void writeDetail(Context context, String state, String detail) {
        SharedPreferences current = prefs(context);
        write(
                context,
                state,
                detail,
                current.getString("log", ""),
                current.getInt("files", 0),
                current.getInt("published", 0)
        );
    }

    static void writePlaylistResults(Context context, String state, String detail, String resultsJson, String log) {
        SharedPreferences current = prefs(context);
        prefs(context)
                .edit()
                .putString("state", state)
                .putString("detail", detail)
                .putString("log", log == null ? "" : log)
                .putString("playlist_results", resultsJson == null ? "[]" : resultsJson)
                .putInt("files", current.getInt("files", 0))
                .putInt("published", current.getInt("published", 0))
                .putLong("updated", System.currentTimeMillis())
                .apply();
        context.sendBroadcast(new android.content.Intent(ACTION_STATUS_CHANGED).setPackage(context.getPackageName()));
    }
}
