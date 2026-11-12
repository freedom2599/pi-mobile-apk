package com.polaris.pimobile;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * APK 下载 + 安装。
 *
 * 用系统 DownloadManager 而不是自己开 HttpURLConnection：
 *  - 系统负责网络、断点、通知栏进度，不会因为 Activity 生命周期被杀
 *  - 失败时会给出明确的 reason code（可以展示给用户）
 *  - 不受 WebView 网络栈影响
 *
 * 安装走 FileProvider，Android 8+ 会自动引导用户授权「安装未知应用」。
 */
public class ApkUpdater {

    private static final String TAG = "ApkUpdater";

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile String state = "idle";   // idle|downloading|done|error|installing
    private volatile String error = "";       // 出错原因
    private volatile String step = "";        // 当前阶段
    private volatile long dlId = -1;
    private volatile File apkFile;
    private volatile String serverBase = "";   // 由 JS 传入，用于上报日志
    private final java.util.Deque<String> logBuf = new java.util.ArrayDeque<>();

    private BroadcastReceiver receiver;

    public ApkUpdater(Activity activity) {
        this.activity = activity;
    }

    /** JS 启动时调用，告诉原生层服务器地址 */
    @JavascriptInterface
    public void setServer(String base) {
        this.serverBase = base == null ? "" : base;
        plog("setServer " + this.serverBase);
    }

    /** 记录一条原生日志（环形缓冲，最多 100 条） */
    private synchronized void plog(String msg) {
        Log.i(TAG, msg);
        logBuf.addLast("[" + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "] " + msg);
        while (logBuf.size() > 100) logBuf.removeFirst();
    }

    /** 上报到服务器，失败静默 */
    private void report(String kind, String message, String extra) {
        plog("REPORT " + kind + ": " + message);
        final String base = serverBase;
        if (base == null || base.isEmpty()) return;
        final String urlStr = base + "/api/clientlog";
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                StringBuilder logs = new StringBuilder("[");
                synchronized (this) {
                    java.util.Iterator<String> it = logBuf.iterator();
                    boolean first = true;
                    while (it.hasNext()) {
                        if (!first) logs.append(",");
                        first = false;
                        logs.append("\"").append(it.next().replace("\\","\\\\").replace("\"","'").replace("\n"," ")).append("\"");
                    }
                }
                logs.append("]");

                String body = "{\"kind\":\"" + kind + "\",\"message\":\"" + message.replace("\\","\\\\").replace("\"","'").replace("\n"," ")
                        + "\",\"appVersion\":\"" + (extra == null ? "" : extra) + "\",\"platform\":\"android-native\""
                        + ",\"context\":{\"server\":\"" + base + "\"}"
                        + ",\"logs\":" + logs + "}";

                c = (HttpURLConnection) new URL(urlStr).openConnection();
                c.setRequestMethod("POST");
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setDoOutput(true);
                c.setRequestProperty("content-type", "application/json");
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body.getBytes("utf-8"));
                }
                int code = c.getResponseCode();
                Log.i(TAG, "report " + kind + " -> HTTP " + code);
            } catch (Exception e) {
                Log.w(TAG, "report failed: " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }).start();
    }

    private static String jesc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ");
    }

    /** 是否已获得「安装未知应用」权限（JS 可查，用于提前提示） */
    @JavascriptInterface
    public boolean canInstall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return activity.getPackageManager().canRequestPackageInstalls();
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "canRequestPackageInstalls failed", t);
            return false;
        }
    }

    /** 打开系统设置页让用户授权「安装未知应用」 */
    @JavascriptInterface
    public void openInstallPermission() {
        ui.post(() -> {
            try {
                Intent i;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    i.setData(Uri.parse("package:" + activity.getPackageName()));
                } else {
                    i = new Intent(Settings.ACTION_SECURITY_SETTINGS);
                }
                activity.startActivity(i);
            } catch (Throwable t) {
                Log.e(TAG, "open permission settings failed", t);
            }
        });
    }

    /** JS 轮询：状态 + 进度（直接从 DownloadManager 查，最真实） */
    @JavascriptInterface
    public String getState() {
        long got = 0, total = -1, pct = -1;

        if (dlId >= 0) {
            try {
                DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
                try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(dlId))) {
                    if (c != null && c.moveToFirst()) {
                        int sIdx = c.getColumnIndex(DownloadManager.COLUMN_STATUS);
                        int soFar = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
                        int tot = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
                        int rIdx = c.getColumnIndex(DownloadManager.COLUMN_REASON);

                        got = soFar >= 0 ? c.getLong(soFar) : 0;
                        total = tot >= 0 ? c.getLong(tot) : -1;
                        if (total > 0) pct = got * 100 / total;

                        int status = c.getInt(sIdx);
                        if (status == DownloadManager.STATUS_SUCCESSFUL && !"done".equals(state) && !"installing".equals(state)) {
                            state = "done";
                            step = "downloaded";
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            int reason = c.getInt(rIdx);
                            state = "error";
                            error = "下载失败 code=" + reason + " (" + reasonText(reason) + ")";
                            report("download_failed", error, String.valueOf(dlId));
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "query failed", e);
            }
        }

        return "{\"state\":\"" + state + "\""
             + ",\"step\":\"" + jesc(step) + "\""
             + ",\"got\":" + got
             + ",\"total\":" + total
             + ",\"percent\":" + pct
             + ",\"canInstall\":" + canInstall()
             + ",\"error\":\"" + jesc(error) + "\"}";
    }

    private static String reasonText(int r) {
        switch (r) {
            case DownloadManager.ERROR_UNKNOWN: return "未知错误";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS: return "文件已存在";
            case DownloadManager.ERROR_FILE_ERROR: return "文件系统错误";
            case DownloadManager.ERROR_HTTP_DATA_ERROR: return "HTTP 数据错误";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE: return "存储空间不足";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS: return "重定向过多";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE: return "未处理的 HTTP 状态码";
            case DownloadManager.ERROR_CANNOT_RESUME: return "无法续传";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND: return "存储不可用";
            default: return "其他";
        }
    }

    @JavascriptInterface
    public boolean download(String url) {
        if ("downloading".equals(state)) return false;
        state = "downloading";
        step = "enqueue";
        error = "";

        try {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new Exception("外部存储不可用");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录");
            // 清旧包
            File[] olds = dir.listFiles((d, n) -> n.startsWith("pi-mobile-update"));
            if (olds != null) for (File f : olds) f.delete();

            apkFile = new File(dir, "pi-mobile-update.apk");
            if (apkFile.exists()) apkFile.delete();

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("π mobile 更新");
            req.setDescription("正在下载新版本…");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
            req.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "pi-mobile-update.apk");
            req.setAllowedOverRoaming(true);
            req.setAllowedOverMetered(true);

            // 清掉可能的旧授权

            dlId = dm.enqueue(req);
            step = "queued";
            Log.i(TAG, "enqueued id=" + dlId + " url=" + url);

            // 注册完成广播（可选，轮询也能拿到）
            ui.post(() -> {
                try { registerReceiver(); } catch (Exception e) { Log.w(TAG, "registerReceiver failed", e); }
            });
            return true;
        } catch (Exception e) {
            Log.e(TAG, "enqueue failed", e);
            state = "error";
            error = "无法开始下载：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            report("native_download_error", error + "\n" + Log.getStackTraceString(e) + "\nurl=" + url, "");
            return false;
        }
    }

    private void registerReceiver() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id == dlId) {
                    Log.i(TAG, "download finished broadcast id=" + id);
                    // 状态由 getState 的 query 更新
                }
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(receiver, f);
        }
    }

    /** 调起系统安装器 */
    @JavascriptInterface
    public void install() {
        // 整体兜底：任何异常都不能穿越 @JavascriptInterface 边界，
        // 否则 JS 只会看到 "Java exception was raised during method invocation"
        try {
            doInstall();
        } catch (Throwable t) {
            Log.e(TAG, "install threw", t);
            state = "error";
            error = "安装异常：" + t.getClass().getSimpleName()
                  + (t.getMessage() == null ? "" : " - " + t.getMessage())
                  + " [step=" + step + "]";
            report("native_install_error", error + "\n" + Log.getStackTraceString(t), String.valueOf(dlId));
        }
    }

    private void doInstall() {
        step = "install-start";

        // 1) 先查权限
        boolean needPerm = false;
        try {
            needPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    && !activity.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            Log.w(TAG, "canRequestPackageInstalls failed", t);
        }
        if (needPerm) {
            state = "installing";
            step = "need-permission";
            error = "请允许「安装未知应用」后返回，再点一次「安装」";
            ui.post(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    i.setData(Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(i);
                } catch (Throwable t) {
                    Log.e(TAG, "open settings failed", t);
                }
            });
            return;
        }

        // 2) 优先用 DownloadManager 的 content:// URI（最可靠，不依赖路径猜测）
        step = "dm-uri";
        try {
            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null && dlId >= 0) {
                Uri dmUri = dm.getUriForDownloadedFile(dlId);
                if (dmUri != null) {
                    Log.i(TAG, "install via DownloadManager uri: " + dmUri);
                    step = "dm-uri-ok";
                    startInstaller(dmUri);
                    return;
                }
                Log.w(TAG, "getUriForDownloadedFile 返回 null，dlId=" + dlId);
            } else {
                Log.w(TAG, "DownloadManager 不可用或 dlId<0 (dlId=" + dlId + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "dm uri failed", t);
        }

        // 3) 退回本地文件 + FileProvider
        step = "local-file";
        File f = apkFile;
        if (f == null || !f.exists()) {
            // 再找几个可能的位置
            String[][] guesses = {
                {Environment.DIRECTORY_DOWNLOADS, "pi-mobile-update.apk"},
                {null, "updates/pi-mobile-update.apk"},
            };
            for (String[] g : guesses) {
                try {
                    File dir = activity.getExternalFilesDir(g[0]);
                    if (dir == null) continue;
                    File cand = g[1].contains("/")
                            ? new File(activity.getExternalFilesDir(null), g[1])
                            : new File(dir, g[1]);
                    Log.i(TAG, "检查候选路径: " + cand.getAbsolutePath() + " exists=" + cand.exists());
                    if (cand.exists() && cand.length() > 1024) { f = cand; apkFile = cand; break; }
                } catch (Throwable ignore) {}
            }
        }

        if (f == null || !f.exists()) {
            error = "安装包不存在（未下载完成或已被清理）\ndlId=" + dlId;
            state = "error";
            step = "no-file";
            report("install_no_file", error, String.valueOf(dlId));
            return;
        }

        Log.i(TAG, "install file=" + f.getAbsolutePath() + " size=" + f.length());

        step = "fileprovider";
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", f);
            Log.i(TAG, "content uri = " + uri);
        } catch (Throwable t) {
            Log.e(TAG, "getUriForFile failed", t);
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                startInstallerFromFile(f);
                return;
            }
            error = "无法生成安装 URI：" + t.getMessage() + "\n路径: " + f.getAbsolutePath();
            state = "error";
            step = "fileprovider-fail";
            report("fileprovider_error", error + "\n" + Log.getStackTraceString(t), String.valueOf(dlId));
            return;
        }

        startInstaller(uri);
    }

    /** file:// 兜底（仅旧系统） */
    private void startInstallerFromFile(File f) {
        state = "installing";
        step = "install-legacy";
        ui.post(() -> {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(it);
            } catch (Throwable t) {
                Log.e(TAG, "legacy install failed", t);
                error = "无法启动安装器：" + t.getMessage();
                state = "error";
            }
        });
    }

    private void startInstaller(Uri uri) {
        state = "installing";
        step = "install";
        ui.post(() -> {
            try {
                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(uri, "application/vnd.android.package-archive");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(it);
                Log.i(TAG, "installer started");
            } catch (Throwable t) {
                Log.e(TAG, "startInstaller failed", t);
                error = "无法启动安装器：" + t.getClass().getSimpleName()
                      + (t.getMessage() == null ? "" : " - " + t.getMessage());
                state = "error";
                report("start_installer_error", error + "\n" + Log.getStackTraceString(t), String.valueOf(dlId));
            }
        });
    }

    /** 清理：Activity 销毁时调 */
    public void destroy() {
        if (receiver != null) {
            try { activity.unregisterReceiver(receiver); } catch (Exception ignore) {}
            receiver = null;
        }
    }
}
