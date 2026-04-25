package xyz.doikki.dkplayer;

import android.Manifest;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;
import androidx.core.view.GravityCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.drawerlayout.widget.DrawerLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import xyz.doikki.videoplayer.player.VideoView;

public class MainActivity extends AppCompatActivity {

    private enum PlayMode {
        SINGLE_LOOP,
        LIST_LOOP
    }

    private static class PlayItem {
        final Uri uri;
        final String name;

        PlayItem(@NonNull Uri uri, @NonNull String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    private static class UsbScanResult {
        final List<PlayItem> items = new ArrayList<>();
        boolean folderFound = false;
    }

    private static class UsbScanProgress {
        final int totalNodes;
        int processedNodes;
        int foundVideos;

        UsbScanProgress(int totalNodes) {
            this.totalNodes = Math.max(1, totalNodes);
            this.processedNodes = 0;
            this.foundVideos = 0;
        }
    }

    private static final int LAN_PORT = 18080;
    private static final long LAN_HEALTH_INTERVAL_MS = 15_000L;
    private static final String USB_VIDEO_FOLDER_NAME = "video_play";
    private static final int USB_SCAN_MAX_DEPTH = 6;
    private static final String[] USB_SCAN_ROOTS = {"/storage", "/mnt/media_rw", "/mnt", "/storage/usbotg"};

    private VideoView videoView;
    private TextView tvPath;
    private View headerContainer;
    private View rootMainContent;
    private View controlsContainer;
    private View usbScanContainer;
    private ProgressBar pbUsbScan;
    private TextView tvUsbScanStatus;
    private DrawerLayout drawerLayout;
    private TextView tvLanStatus;
    private TextView tvLanIp;
    private TextView tvLanUrl;
    private Button btnToggle;
    private Button btnMode;
    private Button btnReplay;
    private Button btnFullscreen;

    private GestureDetectorCompat gestureDetector;
    private boolean controlsVisible = true;
    private boolean appFullscreen = false;
    private boolean autoUsbScanStarted = false;

    private final List<PlayItem> playList = new ArrayList<>();
    private int currentIndex = -1;
    private PlayMode playMode = PlayMode.LIST_LOOP;

    @Nullable
    private LanControlServer lanControlServer;
    @Nullable
    private String localIp;

    private final Handler lanHealthHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService lanExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService usbScanExecutor = Executors.newSingleThreadExecutor();
    private boolean lanHealthChecking = false;
    private boolean usbScanRunning = false;

    private final Runnable lanHealthRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLanStatus(false);
            lanHealthHandler.postDelayed(this, LAN_HEALTH_INTERVAL_MS);
        }
    };

    private final VideoView.SimpleOnStateChangeListener playStateListener =
            new VideoView.SimpleOnStateChangeListener() {
                @Override
                public void onPlayStateChanged(int playState) {
                    if (playState == VideoView.STATE_PLAYBACK_COMPLETED
                            && playMode == PlayMode.LIST_LOOP
                            && !playList.isEmpty()) {
                        int nextIndex = (currentIndex + 1) % playList.size();
                        playAt(nextIndex, true);
                    }
                }

                @Override
                public void onPlayerStateChanged(int playerState) {
                    updateFullscreenButtonText();
                }
            };

    private final ActivityResultLauncher<Intent> pickVideoLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                Intent data = result.getData();
                Uri uri = data.getData();
                if (uri == null) {
                    return;
                }
                takePersistReadPermission(data, uri);
                playSingleVideo(uri);
            });

    private final ActivityResultLauncher<Intent> pickFolderLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                    return;
                }
                Intent data = result.getData();
                Uri treeUri = data.getData();
                if (treeUri == null) {
                    return;
                }
                takePersistReadPermission(data, treeUri);
                playFolderVideos(treeUri);
            });

    private final ActivityResultLauncher<String> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    autoPlayUsbVideoFolderOnStartup();
                } else {
                    hideUsbScanProgress();
                    Toast.makeText(this, R.string.usb_scan_permission_denied, Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        videoView = findViewById(R.id.video_view);
        tvPath = findViewById(R.id.tv_path);
        headerContainer = findViewById(R.id.header_container);
        rootMainContent = findViewById(R.id.root_main_content);
        controlsContainer = findViewById(R.id.controls_container);
        usbScanContainer = findViewById(R.id.usb_scan_container);
        pbUsbScan = findViewById(R.id.pb_usb_scan);
        tvUsbScanStatus = findViewById(R.id.tv_usb_scan_status);
        tvLanStatus = findViewById(R.id.tv_lan_status);
        tvLanIp = findViewById(R.id.tv_lan_ip);
        tvLanUrl = findViewById(R.id.tv_lan_url);

        Button btnLanInfo = findViewById(R.id.btn_lan_info);
        Button btnLanRefresh = findViewById(R.id.btn_lan_refresh);
        Button btnPickVideo = findViewById(R.id.btn_pick_video);
        Button btnPickFolder = findViewById(R.id.btn_pick_folder);
        Button btnPlaylist = findViewById(R.id.btn_playlist);
        btnMode = findViewById(R.id.btn_mode);
        btnToggle = findViewById(R.id.btn_toggle_play);
        btnReplay = findViewById(R.id.btn_replay);
        btnFullscreen = findViewById(R.id.btn_fullscreen);

        setupTinyScreenSize();
        setupGestureInteraction();
        videoView.addOnStateChangeListener(playStateListener);

        btnLanInfo.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.END));
        btnLanRefresh.setOnClickListener(v -> {
            tvLanStatus.setText(R.string.lan_status_refreshing);
            refreshLanStatus(true);
        });
        btnPickVideo.setOnClickListener(v -> openVideoPicker());
        btnPickFolder.setOnClickListener(v -> openFolderPicker());
        btnPlaylist.setOnClickListener(v -> showPlaylistDialog());
        btnMode.setOnClickListener(v -> showModeDialog());
        btnToggle.setOnClickListener(v -> togglePlayState(true));
        btnReplay.setOnClickListener(v -> replayCurrentVideo(true));
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        updateModeButtonText();
        updateFullscreenButtonText();
        refreshLanStatus(false);
        enterDefaultFullscreen();
        tryAutoPlayUsbOnAppStart();
    }

    private void enterDefaultFullscreen() {
        videoView.post(() -> setAppFullscreen(true, false));
    }

    private void refreshLanStatus(boolean manual) {
        if (!isNetworkConnected()) {
            stopLanControlServer();
            localIp = null;
            tvLanIp.setText(R.string.lan_not_available);
            tvLanUrl.setText("-");
            tvLanStatus.setText(R.string.lan_status_network_down);
            if (manual) {
                Toast.makeText(this, R.string.manual_refresh_done, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        String newIp = getLocalIpAddress();
        localIp = newIp;
        tvLanIp.setText(newIp == null ? getString(R.string.lan_not_available) : newIp);
        tvLanUrl.setText(newIp == null ? "-" : "http://" + newIp + ":" + LAN_PORT);

        if (newIp == null) {
            stopLanControlServer();
            tvLanStatus.setText(R.string.lan_status_no_ip);
            if (manual) {
                Toast.makeText(this, R.string.manual_refresh_done, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        ensureLanControlServer();
        checkServerHealthAndRecover(manual);
    }

    private boolean isNetworkConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void ensureLanControlServer() {
        if (lanControlServer != null) {
            return;
        }
        startLanControlServer();
    }

    private boolean startLanControlServer() {
        if (localIp == null) {
            return false;
        }

        LanControlServer server = new LanControlServer(LAN_PORT, new LanControlServer.ControlBridge() {
            @NonNull
            @Override
            public String getStateJson() {
                return getStateJsonSafely();
            }

            @Override
            public void handleAction(@NonNull String action, @Nullable String value) {
                runOnUiThread(() -> applyRemoteAction(action, value));
            }
        });

        try {
            server.startSafely();
            lanControlServer = server;
            tvLanStatus.setText(R.string.lan_status_running);
            return true;
        } catch (Exception e) {
            lanControlServer = null;
            tvLanStatus.setText(R.string.lan_status_start_failed);
            return false;
        }
    }

    private void stopLanControlServer() {
        if (lanControlServer != null) {
            try {
                lanControlServer.stop();
            } catch (Exception ignored) {
            }
            lanControlServer = null;
        }
    }

    private void restartLanControlServer() {
        stopLanControlServer();
        boolean ok = startLanControlServer();
        if (ok) {
            tvLanStatus.setText(R.string.lan_status_restart);
        }
    }

    private void checkServerHealthAndRecover(boolean manual) {
        if (lanHealthChecking || localIp == null) {
            if (manual) {
                Toast.makeText(this, R.string.manual_refresh_done, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        lanHealthChecking = true;
        final String checkIp = localIp;
        lanExecutor.execute(() -> {
            boolean healthy = isHttpReachable("http://127.0.0.1:" + LAN_PORT + "/api/ping");
            if (!healthy) {
                healthy = isHttpReachable("http://" + checkIp + ":" + LAN_PORT + "/api/ping");
            }

            final boolean finalHealthy = healthy;
            runOnUiThread(() -> {
                lanHealthChecking = false;
                if (!finalHealthy) {
                    restartLanControlServer();
                } else {
                    tvLanStatus.setText(R.string.lan_status_running);
                }
                if (manual) {
                    Toast.makeText(this, R.string.manual_refresh_done, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private boolean isHttpReachable(@NonNull String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(1200);
            conn.setRequestMethod("GET");
            conn.connect();
            int code = conn.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @NonNull
    private String getStateJsonSafely() {
        final String[] state = new String[]{"{}"};
        CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(() -> {
            state[0] = buildStateJson();
            latch.countDown();
        });
        try {
            latch.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return state[0];
    }

    @NonNull
    private String buildStateJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("playing", videoView != null && videoView.isPlaying());
            root.put("fullScreen", appFullscreen);
            root.put("currentIndex", currentIndex);
            root.put("total", playList.size());
            root.put("mode", playMode == PlayMode.SINGLE_LOOP ? "single" : "list");
            root.put("modeText", playMode == PlayMode.SINGLE_LOOP
                    ? getString(R.string.mode_single_loop)
                    : getString(R.string.mode_list_loop));
            root.put("currentName", currentIndex >= 0 && currentIndex < playList.size() ? playList.get(currentIndex).name : "");

            JSONArray list = new JSONArray();
            for (int i = 0; i < playList.size(); i++) {
                JSONObject item = new JSONObject();
                item.put("index", i);
                item.put("name", playList.get(i).name);
                list.put(item);
            }
            root.put("playlist", list);
            return root.toString();
        } catch (Exception ignored) {
            return "{}";
        }
    }

    private void applyRemoteAction(@NonNull String action, @Nullable String value) {
        switch (action) {
            case "play":
                ensurePlay();
                break;
            case "pause":
                if (videoView.isPlaying()) {
                    videoView.pause();
                    btnToggle.setText(R.string.play);
                }
                break;
            case "toggle":
                togglePlayState(false);
                break;
            case "next":
                playNext();
                break;
            case "prev":
                playPrev();
                break;
            case "mode_single":
                playMode = PlayMode.SINGLE_LOOP;
                videoView.setLooping(true);
                updateModeButtonText();
                break;
            case "mode_list":
                playMode = PlayMode.LIST_LOOP;
                videoView.setLooping(false);
                updateModeButtonText();
                break;
            case "fullscreen":
                toggleFullscreen();
                break;
            case "replay":
                replayCurrentVideo(false);
                break;
            case "pick_video":
                openVideoPicker();
                break;
            case "pick_folder":
                openFolderPicker();
                break;
            case "play_index":
                int idx = parseIndex(value);
                if (idx >= 0) {
                    playAt(idx, true);
                }
                break;
            default:
                break;
        }
    }

    private int parseIndex(@Nullable String value) {
        if (TextUtils.isEmpty(value)) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void ensurePlay() {
        if (playList.isEmpty()) {
            return;
        }
        if (videoView.isPlaying()) {
            return;
        }
        int state = videoView.getCurrentPlayState();
        if (state == VideoView.STATE_IDLE || state == VideoView.STATE_PLAYBACK_COMPLETED || state == VideoView.STATE_ERROR) {
            if (currentIndex < 0) {
                currentIndex = 0;
            }
            playAt(currentIndex, true);
        } else {
            videoView.resume();
            btnToggle.setText(R.string.pause);
        }
    }

    private void playNext() {
        if (playList.isEmpty()) {
            return;
        }
        int next = currentIndex < 0 ? 0 : (currentIndex + 1) % playList.size();
        playAt(next, true);
    }

    private void playPrev() {
        if (playList.isEmpty()) {
            return;
        }
        int prev;
        if (currentIndex < 0) {
            prev = 0;
        } else {
            prev = (currentIndex - 1 + playList.size()) % playList.size();
        }
        playAt(prev, true);
    }

    private void replayCurrentVideo(boolean showToastIfEmpty) {
        if (playList.isEmpty() || currentIndex < 0 || currentIndex >= playList.size()) {
            if (showToastIfEmpty) {
                Toast.makeText(this, R.string.pick_video_or_folder_first, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        playAt(currentIndex, true);
    }

    private void setupGestureInteraction() {
        gestureDetector = new GestureDetectorCompat(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (!appFullscreen) {
                    return false;
                }
                // 需求：全屏单击视为系统返回键，直接回到按钮页面
                onBackPressed();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                togglePlayState(false);
                return true;
            }
        });

        videoView.setClickable(true);
        videoView.setFocusable(true);
        videoView.setOnTouchListener((v, event) -> {
            boolean handled = gestureDetector.onTouchEvent(event);
            if (event.getAction() == MotionEvent.ACTION_UP) {
                v.performClick();
            }
            return appFullscreen || handled;
        });
    }

    private void setupTinyScreenSize() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int tinyWidth = (int) (screenWidth * 0.72f);
        int tinyHeight = (int) (tinyWidth * 9f / 16f);
        videoView.setTinyScreenSize(new int[]{tinyWidth, tinyHeight});
    }

    private void toggleFullscreen() {
        setAppFullscreen(!appFullscreen, true);
    }

    private void setAppFullscreen(boolean fullscreen, boolean animateControls) {
        if (appFullscreen == fullscreen) {
            return;
        }
        appFullscreen = fullscreen;
        applyFullscreenLayout(fullscreen);
        if (fullscreen) {
            enterFullscreenUi();
            hideControls(animateControls);
        } else {
            exitFullscreenUi();
            showControls(false);
        }
        updateFullscreenButtonText();
    }

    private void applyFullscreenLayout(boolean fullscreen) {
        if (headerContainer != null) {
            headerContainer.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (tvPath != null) {
            tvPath.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }
        if (drawerLayout != null) {
            drawerLayout.setBackgroundColor(fullscreen ? Color.BLACK : Color.WHITE);
        }
        if (rootMainContent != null) {
            rootMainContent.setBackgroundColor(fullscreen ? Color.BLACK : Color.parseColor("#F5F7FA"));
            int padding = fullscreen ? 0 : dp(12);
            rootMainContent.setPadding(padding, padding, padding, padding);
        }
        if (videoView != null && videoView.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) videoView.getLayoutParams();
            params.topMargin = fullscreen ? 0 : dp(10);
            videoView.setLayoutParams(params);
        }
    }

    private void hideControls(boolean animate) {
        controlsVisible = false;
        if (controlsContainer == null || controlsContainer.getVisibility() != View.VISIBLE) {
            return;
        }
        controlsContainer.animate().cancel();

        controlsContainer.post(() -> {
            float distance = controlsContainer.getHeight() + dp(20);
            if (animate) {
                controlsContainer.animate()
                        .translationY(distance)
                        .alpha(0f)
                        .setDuration(220)
                        .withEndAction(() -> controlsContainer.setVisibility(View.GONE))
                        .start();
            } else {
                controlsContainer.setTranslationY(distance);
                controlsContainer.setAlpha(0f);
                controlsContainer.setVisibility(View.GONE);
            }
        });
    }

    private void showControls(boolean animate) {
        controlsVisible = true;
        if (controlsContainer == null) {
            return;
        }

        controlsContainer.animate().cancel();
        controlsContainer.setVisibility(View.VISIBLE);
        if (animate) {
            controlsContainer.setTranslationY(dp(20));
            controlsContainer.setAlpha(0f);
            controlsContainer.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(200)
                    .start();
        } else {
            controlsContainer.setTranslationY(0f);
            controlsContainer.setAlpha(1f);
        }
    }

    private void enterFullscreenUi() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void exitFullscreenUi() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, true);
        window.setStatusBarColor(Color.BLACK);
        window.setNavigationBarColor(Color.BLACK);
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void tryAutoPlayUsbOnAppStart() {
        if (autoUsbScanStarted || !playList.isEmpty()) {
            return;
        }
        autoUsbScanStarted = true;

        showUsbScanProgress(1, getString(R.string.usb_scan_preparing));
        if (needRequestStoragePermission()) {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            return;
        }
        autoPlayUsbVideoFolderOnStartup();
    }

    private boolean needRequestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void autoPlayUsbVideoFolderOnStartup() {
        if (!playList.isEmpty() || usbScanRunning) {
            hideUsbScanProgress();
            return;
        }

        usbScanRunning = true;
        showUsbScanProgress(1, getString(R.string.usb_scan_preparing));

        usbScanExecutor.execute(() -> {
            UsbScanResult result = scanUsbVideoFoldersWithProgress();
            runOnUiThread(() -> {
                usbScanRunning = false;
                hideUsbScanProgress();

                if (!playList.isEmpty()) {
                    return;
                }
                if (!result.items.isEmpty()) {
                    playList.clear();
                    playList.addAll(result.items);
                    playAt(0, true);
                    Toast.makeText(this, getString(R.string.folder_loaded, playList.size()), Toast.LENGTH_SHORT).show();
                } else if (result.folderFound) {
                    Toast.makeText(this, R.string.no_video_in_folder, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, R.string.usb_scan_no_video_found, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @NonNull
    private UsbScanResult scanUsbVideoFoldersWithProgress() {
        UsbScanResult result = new UsbScanResult();
        List<File> candidates = buildUsbVideoPlayCandidates();
        int candidateTotal = Math.max(1, candidates.size());
        Set<String> visitedFiles = new HashSet<>();

        for (int i = 0; i < candidates.size(); i++) {
            File folder = candidates.get(i);
            int phaseProgress = Math.max(1, Math.min(30, (i + 1) * 30 / candidateTotal));
            postUsbScanProgress(phaseProgress, getString(R.string.usb_scan_candidates, i + 1, candidateTotal));

            if (folder == null || !folder.exists() || !folder.isDirectory() || !folder.canRead()) {
                continue;
            }

            result.folderFound = true;
            Set<String> countVisitedDirs = new HashSet<>();
            int totalNodes = countFolderNodes(folder, countVisitedDirs);
            UsbScanProgress progress = new UsbScanProgress(totalNodes);

            Set<String> collectVisitedDirs = new HashSet<>();
            collectVideoFilesFromFolderWithProgress(folder, collectVisitedDirs, visitedFiles, result.items, progress);
        }

        // 某些设备上U盘目录可见但File API不可遍历，使用MediaStore兜底
        if (result.items.isEmpty()) {
            collectVideosFromMediaStore(visitedFiles, result);
        }

        Collections.sort(result.items, Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT)));
        postUsbScanProgress(100, getString(R.string.usb_scan_completed, result.items.size()));
        return result;
    }

    @NonNull
    private List<File> buildUsbVideoPlayCandidates() {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String rootPath : USB_SCAN_ROOTS) {
            File root = new File(rootPath);
            if (!root.exists() || !root.isDirectory() || !root.canRead()) {
                continue;
            }
            collectVideoPlayFoldersRecursively(root, USB_SCAN_MAX_DEPTH, seen, out);
        }

        return out;
    }

    private void collectVideoPlayFoldersRecursively(@NonNull File current,
                                                    int depth,
                                                    @NonNull Set<String> seen,
                                                    @NonNull List<File> out) {
        if (depth < 0 || !current.exists() || !current.isDirectory() || !current.canRead()) {
            return;
        }

        String currentName = current.getName();
        if (!TextUtils.isEmpty(currentName) && USB_VIDEO_FOLDER_NAME.equalsIgnoreCase(currentName)) {
            String key = safeCanonicalPath(current);
            if (seen.add(key)) {
                out.add(current);
            }
            // 找到目标目录后只在其内部扫描视频，不再继续向下找同名目录
            return;
        }

        if (shouldSkipScanDir(current)) {
            return;
        }

        File[] children = current.listFiles();
        if (children == null || children.length == 0) {
            return;
        }

        for (File child : children) {
            if (child == null || !child.isDirectory() || !child.canRead()) {
                continue;
            }
            collectVideoPlayFoldersRecursively(child, depth - 1, seen, out);
        }
    }

    private boolean shouldSkipScanDir(@NonNull File dir) {
        String name = dir.getName();
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        return "emulated".equalsIgnoreCase(name)
                || "self".equalsIgnoreCase(name)
                || "enc_emulated".equalsIgnoreCase(name)
                || "obb".equalsIgnoreCase(name)
                || "Android".equalsIgnoreCase(name)
                || name.startsWith(".");
    }

    private int countFolderNodes(@Nullable File folder, @NonNull Set<String> visitedDirs) {
        if (folder == null || !folder.exists() || !folder.isDirectory() || !folder.canRead()) {
            return 0;
        }

        String folderKey = safeCanonicalPath(folder);
        if (!visitedDirs.add(folderKey)) {
            return 0;
        }

        int count = 1;
        File[] nodes = folder.listFiles();
        if (nodes == null || nodes.length == 0) {
            return count;
        }

        for (File node : nodes) {
            if (node == null || !node.exists()) {
                continue;
            }
            if (node.isDirectory()) {
                count += countFolderNodes(node, visitedDirs);
            } else {
                count += 1;
            }
        }
        return count;
    }

    private void collectVideoFilesFromFolderWithProgress(@Nullable File folder,
                                                         @NonNull Set<String> visitedDirs,
                                                         @NonNull Set<String> visitedFiles,
                                                         @NonNull List<PlayItem> out,
                                                         @NonNull UsbScanProgress progress) {
        if (folder == null || !folder.exists() || !folder.isDirectory() || !folder.canRead()) {
            return;
        }

        String folderKey = safeCanonicalPath(folder);
        if (!visitedDirs.add(folderKey)) {
            return;
        }

        progress.processedNodes++;
        maybeDispatchUsbScanProgress(progress);

        File[] nodes = folder.listFiles();
        if (nodes == null || nodes.length == 0) {
            return;
        }

        for (File node : nodes) {
            if (node == null || !node.exists()) {
                continue;
            }
            if (node.isDirectory()) {
                collectVideoFilesFromFolderWithProgress(node, visitedDirs, visitedFiles, out, progress);
                continue;
            }

            progress.processedNodes++;
            if (!isVideoFile(node)) {
                maybeDispatchUsbScanProgress(progress);
                continue;
            }

            String fileKey = safeCanonicalPath(node);
            if (!visitedFiles.add(fileKey)) {
                maybeDispatchUsbScanProgress(progress);
                continue;
            }

            out.add(new PlayItem(Uri.fromFile(node), safeName(node.getName())));
            progress.foundVideos++;
            maybeDispatchUsbScanProgress(progress);
        }
    }

    private void maybeDispatchUsbScanProgress(@NonNull UsbScanProgress progress) {
        int completedPercent = Math.min(100, Math.max(1, progress.processedNodes * 100 / progress.totalNodes));
        int phasePercent = Math.min(99, 30 + (completedPercent * 70 / 100));

        if (progress.processedNodes == 1
                || progress.processedNodes % 20 == 0
                || progress.processedNodes >= progress.totalNodes) {
            postUsbScanProgress(
                    phasePercent,
                    getString(R.string.usb_scan_progress, progress.foundVideos, phasePercent));
        }
    }

    private void showUsbScanProgress(int progress, @NonNull String text) {
        if (usbScanContainer == null || pbUsbScan == null || tvUsbScanStatus == null) {
            return;
        }
        usbScanContainer.setVisibility(View.VISIBLE);
        pbUsbScan.setProgress(Math.max(0, Math.min(100, progress)));
        tvUsbScanStatus.setText(text);
    }

    private void postUsbScanProgress(int progress, @NonNull String text) {
        runOnUiThread(() -> showUsbScanProgress(progress, text));
    }

    private void hideUsbScanProgress() {
        if (usbScanContainer == null || pbUsbScan == null || tvUsbScanStatus == null) {
            return;
        }
        usbScanContainer.setVisibility(View.GONE);
        pbUsbScan.setProgress(0);
        tvUsbScanStatus.setText(R.string.usb_scan_preparing);
    }

    private void collectVideosFromMediaStore(@NonNull Set<String> visitedFiles, @NonNull UsbScanResult result) {
        Cursor cursor = null;
        try {
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

            String[] projection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH
            };
            String selection = "LOWER(" + MediaStore.Video.Media.RELATIVE_PATH + ") LIKE ?";
            String[] selectionArgs = new String[]{"%video_play/%"};

            cursor = getContentResolver().query(
                    collection,
                    projection,
                    selection,
                    selectionArgs,
                    MediaStore.Video.Media.DISPLAY_NAME + " COLLATE NOCASE ASC"
            );
            if (cursor == null) {
                return;
            }

            int idIndex = cursor.getColumnIndex(MediaStore.Video.Media._ID);
            int nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int pathIndex = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
            if (idIndex < 0) {
                return;
            }

            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String key = uri.toString();
                if (!visitedFiles.add(key)) {
                    continue;
                }

                String relativePath = pathIndex >= 0 ? cursor.getString(pathIndex) : null;
                if (!TextUtils.isEmpty(relativePath)
                        && relativePath.toLowerCase(Locale.ROOT).contains("video_play/")) {
                    result.folderFound = true;
                }

                String name = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                result.items.add(new PlayItem(uri, safeName(name)));
            }
        } catch (Exception ignored) {
            // ignore MediaStore fallback errors
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @NonNull
    private String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    private boolean isVideoFile(@NonNull File file) {
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName();
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".flv") || lower.endsWith(".wmv")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp") || lower.endsWith(".ts");
    }

    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pickVideoLauncher.launch(intent);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pickFolderLauncher.launch(intent);
    }

    private void playSingleVideo(@NonNull Uri uri) {
        playList.clear();
        String displayName = queryDisplayName(uri);
        if (TextUtils.isEmpty(displayName)) {
            displayName = uri.getLastPathSegment();
        }
        playList.add(new PlayItem(uri, safeName(displayName)));
        playAt(0, true);
    }

    private void playFolderVideos(@NonNull Uri treeUri) {
        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null || !root.exists() || !root.canRead()) {
            Toast.makeText(this, R.string.folder_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }

        List<PlayItem> temp = new ArrayList<>();
        collectVideosRecursively(root, temp);
        if (temp.isEmpty()) {
            Toast.makeText(this, R.string.no_video_in_folder, Toast.LENGTH_SHORT).show();
            return;
        }

        Collections.sort(temp, Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT)));

        playList.clear();
        playList.addAll(temp);
        playAt(0, true);

        Toast.makeText(this, getString(R.string.folder_loaded, playList.size()), Toast.LENGTH_SHORT).show();
    }

    private void collectVideosRecursively(@Nullable DocumentFile node, @NonNull List<PlayItem> out) {
        if (node == null || !node.exists() || !node.canRead()) {
            return;
        }
        if (node.isFile()) {
            if (isVideoFile(node) && node.getUri() != null) {
                out.add(new PlayItem(node.getUri(), safeName(node.getName())));
            }
            return;
        }

        DocumentFile[] children = node.listFiles();
        if (children == null || children.length == 0) {
            return;
        }
        for (DocumentFile child : children) {
            collectVideosRecursively(child, out);
        }
    }

    private boolean isVideoFile(@NonNull DocumentFile file) {
        if (!file.isFile()) {
            return false;
        }
        String type = file.getType();
        if (!TextUtils.isEmpty(type) && type.startsWith("video/")) {
            return true;
        }
        String name = file.getName();
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".flv") || lower.endsWith(".wmv")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp") || lower.endsWith(".ts");
    }

    private void playAt(int index, boolean autoStart) {
        if (playList.isEmpty() || index < 0 || index >= playList.size()) {
            return;
        }

        currentIndex = index;
        PlayItem item = playList.get(currentIndex);

        videoView.release();
        videoView.setLooping(playMode == PlayMode.SINGLE_LOOP);
        videoView.setUrl(item.uri.toString());
        if (autoStart) {
            videoView.start();
            btnToggle.setText(R.string.pause);
        } else {
            btnToggle.setText(R.string.play);
        }

        tvPath.setText(getString(R.string.current_video_with_index,
                currentIndex + 1,
                playList.size(),
                item.name));
    }

    private void showPlaylistDialog() {
        if (playList.isEmpty()) {
            Toast.makeText(this, R.string.pick_video_or_folder_first, Toast.LENGTH_SHORT).show();
            return;
        }

        String[] labels = new String[playList.size()];
        for (int i = 0; i < playList.size(); i++) {
            String prefix = (i == currentIndex) ? "▶ " : "";
            labels[i] = prefix + (i + 1) + ". " + playList.get(i).name;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.playlist)
                .setItems(labels, (dialog, which) -> playAt(which, true))
                .show();
    }

    private void showModeDialog() {
        String[] modes = {
                getString(R.string.mode_single_loop),
                getString(R.string.mode_list_loop)
        };
        int checked = playMode == PlayMode.SINGLE_LOOP ? 0 : 1;

        new AlertDialog.Builder(this)
                .setTitle(R.string.play_mode)
                .setSingleChoiceItems(modes, checked, (dialog, which) -> {
                    playMode = which == 0 ? PlayMode.SINGLE_LOOP : PlayMode.LIST_LOOP;
                    videoView.setLooping(playMode == PlayMode.SINGLE_LOOP);
                    updateModeButtonText();
                    dialog.dismiss();
                })
                .show();
    }

    private void updateModeButtonText() {
        if (btnMode == null) {
            return;
        }
        int modeText = playMode == PlayMode.SINGLE_LOOP ? R.string.mode_single_loop : R.string.mode_list_loop;
        btnMode.setText(getString(R.string.play_mode_prefix, getString(modeText)));
    }

    private void updateFullscreenButtonText() {
        if (btnFullscreen == null) {
            return;
        }
        btnFullscreen.setText(appFullscreen
                ? R.string.exit_fullscreen
                : R.string.enter_fullscreen);
    }

    private void togglePlayState(boolean showToastIfEmpty) {
        if (playList.isEmpty()) {
            if (showToastIfEmpty) {
                Toast.makeText(this, R.string.pick_video_or_folder_first, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        if (videoView.isPlaying()) {
            videoView.pause();
            btnToggle.setText(R.string.play);
            return;
        }

        int state = videoView.getCurrentPlayState();
        if (state == VideoView.STATE_IDLE
                || state == VideoView.STATE_PLAYBACK_COMPLETED
                || state == VideoView.STATE_ERROR) {
            if (currentIndex < 0) {
                currentIndex = 0;
            }
            playAt(currentIndex, true);
        } else {
            videoView.resume();
            btnToggle.setText(R.string.pause);
        }
    }

    private void takePersistReadPermission(@NonNull Intent data, @NonNull Uri uri) {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // 有些 ROM / Provider 不支持持久化授权，不影响本次播放
        }
    }

    @NonNull
    private String safeName(@Nullable String name) {
        return TextUtils.isEmpty(name) ? getString(R.string.unknown_video_name) : name;
    }

    @Nullable
    private String queryDisplayName(@NonNull Uri uri) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Exception ignored) {
            // ignore
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    @Nullable
    private String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface == null || !networkInterface.isUp() || networkInterface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses != null && addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        if (!TextUtils.isEmpty(host)
                                && (host.startsWith("192.") || host.startsWith("10.") || host.startsWith("172."))) {
                            return host;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.END)) {
            drawerLayout.closeDrawer(GravityCompat.END);
            return;
        }
        if (appFullscreen) {
            setAppFullscreen(false, false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appFullscreen) {
            enterFullscreenUi();
        }
        lanHealthHandler.removeCallbacks(lanHealthRunnable);
        lanHealthHandler.post(lanHealthRunnable);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && appFullscreen) {
            enterFullscreenUi();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        lanHealthHandler.removeCallbacks(lanHealthRunnable);
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
            btnToggle.setText(R.string.play);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        lanHealthHandler.removeCallbacksAndMessages(null);
        lanExecutor.shutdownNow();
        usbScanExecutor.shutdownNow();
        stopLanControlServer();
        if (videoView != null) {
            videoView.removeOnStateChangeListener(playStateListener);
            videoView.setOnTouchListener(null);
            videoView.release();
        }
    }
}
