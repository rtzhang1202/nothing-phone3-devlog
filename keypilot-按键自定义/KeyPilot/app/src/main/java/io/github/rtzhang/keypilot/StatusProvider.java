package io.github.rtzhang.keypilot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.rtzhang.keypilot.config.KeyPilotConfig;

/**
 * Lightweight IPC channel allowing system_server / ntessentialrecorder hooks to report
 * heartbeats and execution logs back to the KeyPilot app UI without needing self-hooking.
 */
public class StatusProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.rtzhang.keypilot.status";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String LOG_FILENAME = "status.log";
    public static final String HEARTBEAT_FILENAME = "heartbeat.txt";
    private static final int MAX_LOG_LINES = 160;

    /** Callback for TRY_LAUNCH results returned by system_server (always on the main thread). */
    public interface TryLaunchListener {
        void onTryLaunchResult(String token, boolean ok, String error, String component);
    }

    private static volatile TryLaunchListener sTryLaunchListener;
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /** Registers (or clears with null) the single TRY_LAUNCH result listener. */
    public static void setTryLaunchListener(TryLaunchListener listener) {
        sTryLaunchListener = listener;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null || method == null) {
            return null;
        }
        try {
            if ("log".equals(method) && arg != null) {
                appendLog(ctx, arg);
                Bundle out = new Bundle();
                out.putBoolean("ok", true);
                return out;
            } else if ("heartbeat".equals(method)) {
                writeHeartbeat(ctx, arg != null ? arg : "active");
                Bundle out = new Bundle();
                out.putBoolean("ok", true);
                return out;
            } else if (KeyPilotConfig.METHOD_TRY_LAUNCH_RESULT.equals(method)) {
                deliverTryLaunchResult(arg, extras);
                Bundle out = new Bundle();
                out.putBoolean("ok", true);
                return out;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void deliverTryLaunchResult(final String token, final Bundle extras) {
        final TryLaunchListener l = sTryLaunchListener;
        if (l == null || extras == null) return;
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                try {
                    l.onTryLaunchResult(
                            token,
                            extras.getBoolean("ok", false),
                            extras.getString("error"),
                            extras.getString("component"));
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static synchronized void appendLog(Context ctx, String msg) {
        File file = new File(ctx.getFilesDir(), LOG_FILENAME);
        List<String> lines = new ArrayList<>();
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    lines.add(line);
                }
            } catch (Throwable ignored) {
            }
        }
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        lines.add(ts + " " + msg);
        int start = Math.max(0, lines.size() - MAX_LOG_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    private static synchronized void writeHeartbeat(Context ctx, String info) {
        File file = new File(ctx.getFilesDir(), HEARTBEAT_FILENAME);
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String content = ts + " | " + info;
        try (FileOutputStream fos = new FileOutputStream(file, false)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
