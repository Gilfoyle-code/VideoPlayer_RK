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

/**
 * MainActivity - 本地视频播放器主界面
 *
 * 主要功能：
 * 1. 视频播放：支持多种视频格式的本地播放
 * 2. 播放列表管理：支持从文件或文件夹加载视频列表
 * 3. 播放模式：支持单集循环和列表循环两种模式
 * 4. 局域网控制：启动HTTP服务器，允许通过浏览器远程控制播放器
 * 5. USB视频扫描：自动扫描U盘中的video_play文件夹并自动播放
 * 6. 全屏/小屏切换：支持横屏全屏和竖屏小屏模式
 * 7. 手势操作：支持双击暂停/播放、单击返回等手势
 *
 * 启动流程：
 * 1. onCreate：初始化UI组件、设置手势监听、启动局域网服务器
 * 2. 进入默认全屏模式
 * 3. 尝试自动扫描USB设备并播放视频
 */
public class MainActivity extends AppCompatActivity {

    // ==================== 枚举定义 ====================

    /**
     * 播放模式枚举
     * SINGLE_LOOP: 单集循环模式 - 当前视频播放完成后从头开始循环播放
     * LIST_LOOP: 列表循环模式 - 当前视频播放完成后自动播放列表中的下一个视频
     */
    private enum PlayMode {
        SINGLE_LOOP,
        LIST_LOOP
    }

    // ==================== 内部类定义 ====================

    /**
     * 播放项数据结构
     * 用于存储播放列表中每个视频的URI和显示名称
     */
    private static class PlayItem {
        final Uri uri;      // 视频文件的URI
        final String name;  // 视频文件的显示名称

        PlayItem(@NonNull Uri uri, @NonNull String name) {
            this.uri = uri;
            this.name = name;
        }
    }

    /**
     * USB扫描结果数据结构
     * 用于封装USB视频扫描的最终结果
     */
    private static class UsbScanResult {
        final List<PlayItem> items = new ArrayList<>();  // 扫描到的视频列表
        boolean folderFound = false;  // 标记是否找到了video_play文件夹
    }

    /**
     * USB扫描进度数据结构
     * 用于跟踪扫描过程中的进度信息
     */
    private static class UsbScanProgress {
        final int totalNodes;   // 需要遍历的总节点数
        int processedNodes;      // 已处理的节点数
        int foundVideos;         // 已找到的视频数量

        UsbScanProgress(int totalNodes) {
            this.totalNodes = Math.max(1, totalNodes);
            this.processedNodes = 0;
            this.foundVideos = 0;
        }
    }

    // ==================== 常量定义 ====================

    /** 局域网控制服务器默认端口号 */
    private static final int LAN_PORT = 18080;
    /** 局域网服务器健康检查间隔（毫秒） */
    private static final long LAN_HEALTH_INTERVAL_MS = 15_000L;
    /** USB视频播放文件夹的名称（U盘根目录下期望的文件夹名） */
    private static final String USB_VIDEO_FOLDER_NAME = "video_play";
    /** USB扫描的最大递归深度 */
    private static final int USB_SCAN_MAX_DEPTH = 6;
    /** USB扫描的根目录列表，尝试从这些路径查找U盘 */
    private static final String[] USB_SCAN_ROOTS = {"/storage", "/mnt/media_rw", "/mnt", "/storage/usbotg"};

    // ==================== UI组件声明 ====================

    private VideoView videoView;          // 视频播放器组件
    private TextView tvPath;              // 显示当前播放视频路径的文本
    private View headerContainer;         // 顶部标题栏容器
    private View rootMainContent;         // 主内容区域根视图
    private View controlsContainer;       // 底部控制按钮容器
    private View usbScanContainer;        // USB扫描进度显示容器
    private ProgressBar pbUsbScan;        // USB扫描进度条
    private TextView tvUsbScanStatus;     // USB扫描状态文本
    private DrawerLayout drawerLayout;    // 侧边抽屉布局（用于显示局域网信息）
    private TextView tvLanStatus;         // 局域网服务器状态文本
    private TextView tvLanIp;             // 本机IP地址文本
    private TextView tvLanUrl;            // 局域网控制访问URL文本
    private Button btnToggle;             // 播放/暂停切换按钮
    private Button btnMode;               // 播放模式切换按钮
    private Button btnReplay;             // 重新播放按钮
    private Button btnFullscreen;         // 全屏切换按钮

    // ==================== 状态变量 ====================

    private GestureDetectorCompat gestureDetector;  // 手势检测器
    private boolean controlsVisible = true;         // 控制按钮是否可见
    private boolean appFullscreen = false;           // 是否处于全屏模式
    private boolean autoUsbScanStarted = false;      // 是否已启动过自动USB扫描

    /** 播放列表，存储所有待播放的视频项 */
    private final List<PlayItem> playList = new ArrayList<>();
    private int currentIndex = -1;                   // 当前播放的视频索引
    private PlayMode playMode = PlayMode.LIST_LOOP;  // 当前播放模式

    /** 局域网控制服务器实例，可能为null */
    @Nullable
    private LanControlServer lanControlServer;
    /** 本机局域网IP地址 */
    @Nullable
    private String localIp;

    /** 用于执行局域网健康检查的Handler */
    private final Handler lanHealthHandler = new Handler(Looper.getMainLooper());
    /** 用于执行局域网相关操作的线程池 */
    private final ExecutorService lanExecutor = Executors.newSingleThreadExecutor();
    /** 用于执行USB扫描的线程池 */
    private final ExecutorService usbScanExecutor = Executors.newSingleThreadExecutor();
    private boolean lanHealthChecking = false;  // 是否正在执行健康检查
    private boolean usbScanRunning = false;     // USB扫描是否正在运行

    // ==================== 生命周期方法 ====================

    /**
     * Activity创建时调用的生命周期方法
     *
     * 主要初始化工作：
     * 1. 设置布局文件
     * 2. 绑定UI组件
     * 3. 设置小屏窗口尺寸
     * 4. 配置手势检测
     * 5. 注册播放状态监听器
     * 6. 设置所有按钮的点击事件
     * 7. 进入默认全屏模式
     * 8. 尝试自动播放USB视频
     *
     * @param savedInstanceState 如果Activity被系统销毁后重建，保存的实例状态
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ==================== 绑定UI组件 ====================

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

        // ==================== 初始化配置 ====================

        setupTinyScreenSize();      // 设置小屏窗口尺寸
        setupGestureInteraction();  // 配置手势交互
        videoView.addOnStateChangeListener(playStateListener);  // 添加播放状态监听器

        // ==================== 设置按钮点击事件 ====================

        // 侧边抽屉相关按钮
        btnLanInfo.setOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.END));  // 打开侧边抽屉显示局域网信息
        btnLanRefresh.setOnClickListener(v -> {
            tvLanStatus.setText(R.string.lan_status_refreshing);
            refreshLanStatus(true);  // 手动刷新局域网状态
        });

        // 文件选择相关按钮
        btnPickVideo.setOnClickListener(v -> openVideoPicker());    // 打开视频选择器
        btnPickFolder.setOnClickListener(v -> openFolderPicker());   // 打开文件夹选择器
        btnPlaylist.setOnClickListener(v -> showPlaylistDialog());  // 显示播放列表对话框

        // 播放控制相关按钮
        btnMode.setOnClickListener(v -> showModeDialog());           // 显示播放模式选择对话框
        btnToggle.setOnClickListener(v -> togglePlayState(true));   // 切换播放/暂停状态
        btnReplay.setOnClickListener(v -> replayCurrentVideo(true));  // 重新播放当前视频
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());  // 切换全屏/小屏模式

        // ==================== 初始化UI状态 ====================

        updateModeButtonText();      // 更新播放模式按钮文字
        updateFullscreenButtonText();  // 更新全屏按钮文字
        refreshLanStatus(false);     // 刷新局域网状态
        enterDefaultFullscreen();    // 进入默认全屏模式
        tryAutoPlayUsbOnAppStart();  // 尝试自动播放USB设备上的视频
    }

    /**
     * 进入默认的全屏模式
     * 使用postDelayed确保在视图完全创建后再执行全屏操作
     */
    private void enterDefaultFullscreen() {
        videoView.post(() -> setAppFullscreen(true, false));
    }

    // ==================== 局域网控制相关方法 ====================

    /**
     * 刷新局域网状态
     *
     * 执行流程：
     * 1. 检查网络连接状态
     * 2. 获取本机局域网IP地址
     * 3. 启动或恢复局域网控制服务器
     * 4. 执行健康检查
     *
     * @param manual 是否为手动刷新（手动刷新会显示Toast提示）
     */
    private void refreshLanStatus(boolean manual) {
        // 检查网络是否已连接
        if (!isNetworkConnected()) {
            stopLanControlServer();  // 停止服务器
            localIp = null;
            tvLanIp.setText(R.string.lan_not_available);
            tvLanUrl.setText("-");
            tvLanStatus.setText(R.string.lan_status_network_down);
            if (manual) {
                Toast.makeText(this, R.string.manual_refresh_done, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // 获取本机局域网IP地址
        String newIp = getLocalIpAddress();
        localIp = newIp;
        tvLanIp.setText(newIp == null ? getString(R.string.lan_not_available) : newIp);
        tvLanUrl.setText(newIp == null ? "-" : "http://" + newIp + ":" + LAN_PORT);

        // 如果无法获取IP地址
        if (newIp == null) {
            stopLanControlServer();
            tvLanStatus.setText(R.string.lan_status_no_ip);
            if (manual) {
                Toast.makeText(this, R.string.manual_refresh_done, Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // 确保局域网服务器已启动
        ensureLanControlServer();
        // 执行健康检查，可能需要恢复服务器
        checkServerHealthAndRecover(manual);
    }

    /**
     * 检查网络是否已连接
     *
     * @return true如果网络可用且已连接
     */
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

    /**
     * 确保局域网控制服务器已启动
     * 如果服务器尚未启动，则创建并启动它
     */
    private void ensureLanControlServer() {
        if (lanControlServer != null) {
            return;
        }
        startLanControlServer();
    }

    /**
     * 启动局域网控制服务器
     *
     * 创建HTTP服务器实例并注册控制桥接接口
     * 控制桥接接口提供：
     * - getStateJson: 返回当前播放器状态JSON
     * - handleAction: 处理远程控制指令
     *
     * @return true如果启动成功
     */
    private boolean startLanControlServer() {
        if (localIp == null) {
            return false;
        }

        // 创建控制桥接接口实现
        LanControlServer server = new LanControlServer(LAN_PORT, new LanControlServer.ControlBridge() {
            /**
             * 获取播放器当前状态的JSON表示
             * 用于远程客户端查询播放器状态
             */
            @NonNull
            @Override
            public String getStateJson() {
                return getStateJsonSafely();
            }

            /**
             * 处理来自远程客户端的操作指令
             * 在UI线程上执行以确保线程安全
             */
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

    /**
     * 停止局域网控制服务器
     */
    private void stopLanControlServer() {
        if (lanControlServer != null) {
            try {
                lanControlServer.stop();
            } catch (Exception ignored) {
            }
            lanControlServer = null;
        }
    }

    /**
     * 重启局域网控制服务器
     * 先停止再启动，如果启动失败会保持停止状态
     */
    private void restartLanControlServer() {
        stopLanControlServer();
        boolean ok = startLanControlServer();
        if (ok) {
            tvLanStatus.setText(R.string.lan_status_restart);
        }
    }

    /**
     * 检查服务器健康状态并在必要时恢复
     *
     * 通过HTTP请求测试服务器是否可访问
     * 如果服务器无响应，尝试重启服务器
     *
     * @param manual 是否为手动触发的检查
     */
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
            // 尝试通过本机回环地址访问
            boolean healthy = isHttpReachable("http://127.0.0.1:" + LAN_PORT + "/api/ping");
            // 如果回环地址失败，尝试通过局域网IP访问
            if (!healthy) {
                healthy = isHttpReachable("http://" + checkIp + ":" + LAN_PORT + "/api/ping");
            }

            final boolean finalHealthy = healthy;
            runOnUiThread(() -> {
                lanHealthChecking = false;
                // 如果服务器不健康，尝试重启
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

    /**
     * 检查HTTP端点是否可达
     *
     * @param url 要检查的URL地址
     * @return true如果HTTP请求成功（状态码在200-499之间）
     */
    private boolean isHttpReachable(@NonNull String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(1200);   // 连接超时1.2秒
            conn.setReadTimeout(1200);      // 读取超时1.2秒
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

    /**
     * 安全地获取播放器状态JSON
     * 使用CountDownLatch确保在UI线程获取到状态
     *
     * @return 播放器状态的JSON字符串
     */
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

    /**
     * 构建播放器状态的JSON表示
     *
     * 包含的字段：
     * - playing: 是否正在播放
     * - fullScreen: 是否处于全屏模式
     * - currentIndex: 当前播放项的索引
     * - total: 播放列表总长度
     * - mode: 当前播放模式（single/list）
     * - modeText: 播放模式的可读文本
     * - currentName: 当前播放视频的名称
     * - playlist: 完整播放列表
     *
     * @return JSON格式的状态字符串
     */
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

            // 构建播放列表数组
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

    /**
     * 应用远程控制指令
     *
     * 根据action参数执行相应的播放操作
     * 支持的操作：
     * - play: 开始播放
     * - pause: 暂停播放
     * - toggle: 切换播放/暂停状态
     * - next: 播放下一个
     * - prev: 播放上一个
     * - mode_single: 切换到单集循环模式
     * - mode_list: 切换到列表循环模式
     * - fullscreen: 切换全屏模式
     * - replay: 重新播放当前视频
     * - pick_video: 打开视频选择器
     * - pick_folder: 打开文件夹选择器
     * - play_index: 播放指定索引的视频
     *
     * @param action 操作名称
     * @param value 操作参数值（如播放索引）
     */
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

    /**
     * 解析播放索引值
     *
     * @param value 待解析的字符串
     * @return 解析出的索引值，解析失败返回-1
     */
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

    /**
     * 确保正在播放
     * 如果当前未播放，尝试恢复或重新开始播放
     */
    private void ensurePlay() {
        if (playList.isEmpty()) {
            return;
        }
        if (videoView.isPlaying()) {
            return;
        }
        int state = videoView.getCurrentPlayState();
        // 如果播放器处于空闲、播放完成或错误状态，需要重新加载
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

    /**
     * 播放下一个视频
     * 在列表循环模式下自动跳转到下一集
     */
    private void playNext() {
        if (playList.isEmpty()) {
            return;
        }
        int next = currentIndex < 0 ? 0 : (currentIndex + 1) % playList.size();
        playAt(next, true);
    }

    /**
     * 播放上一个视频
     * 在列表循环模式下自动跳转到上一集
     */
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

    /**
     * 重新播放当前视频
     *
     * @param showToastIfEmpty 如果播放列表为空是否显示提示
     */
    private void replayCurrentVideo(boolean showToastIfEmpty) {
        if (playList.isEmpty() || currentIndex < 0 || currentIndex >= playList.size()) {
            if (showToastIfEmpty) {
                Toast.makeText(this, R.string.pick_video_or_folder_first, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        playAt(currentIndex, true);
    }

    // ==================== 手势操作相关 ====================

    /**
     * 设置手势交互
     *
     * 支持的手势：
     * - 单击（仅在全屏模式）：触发系统返回键，回到按钮页面
     * - 双击（仅在全屏模式）：切换播放/暂停状态
     */
    private void setupGestureInteraction() {
        gestureDetector = new GestureDetectorCompat(this, new GestureDetector.SimpleOnGestureListener() {
            /**
             * 单击手势处理
             * 需求：全屏单击视为系统返回键，直接回到按钮页面
             */
            @Override
            public boolean onSingleTapUp(@NonNull MotionEvent e) {
                if (!appFullscreen) {
                    return false;
                }
                // 全屏单击触发返回操作
                onBackPressed();
                return true;
            }

            /**
             * 双击手势处理
             * 全屏双击切换播放/暂停状态
             */
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

    // ==================== 全屏/小屏切换相关 ====================

    /**
     * 设置小屏模式下的视频窗口尺寸
     * 视频宽度为屏幕宽度的72%，高度按16:9比例计算
     */
    private void setupTinyScreenSize() {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int tinyWidth = (int) (screenWidth * 0.72f);
        int tinyHeight = (int) (tinyWidth * 9f / 16f);
        videoView.setTinyScreenSize(new int[]{tinyWidth, tinyHeight});
    }

    /**
     * 切换全屏/小屏模式
     */
    private void toggleFullscreen() {
        setAppFullscreen(!appFullscreen, true);
    }

    /**
     * 设置全屏或小屏模式
     *
     * @param fullscreen 是否设置为全屏模式
     * @param animateControls 是否动画显示/隐藏控制按钮
     */
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

    /**
     * 应用全屏/小屏的布局变化
     * 控制各个UI组件的显示/隐藏和背景颜色
     */
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

    /**
     * 隐藏底部控制按钮
     * 支持动画效果，向下滑动并渐隐
     *
     * @param animate 是否使用动画效果
     */
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

    /**
     * 显示底部控制按钮
     * 支持动画效果，从下滑入并渐显
     *
     * @param animate 是否使用动画效果
     */
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

    /**
     * 进入全屏UI模式
     * 设置系统栏（状态栏、导航栏）为黑色并隐藏
     */
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

    /**
     * 退出全屏UI模式
     * 恢复系统栏的默认显示
     */
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

    /**
     * 将dp值转换为像素值
     *
     * @param value 以dp为单位的值
     * @return 以像素为单位的值
     */
    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== USB视频扫描相关 ====================

    /**
     * 尝试在应用启动时自动播放USB设备上的视频
     * 检查是否需要请求存储权限，然后启动USB扫描
     */
    private void tryAutoPlayUsbOnAppStart() {
        // 如果已经扫描过或已有播放列表，则跳过
        if (autoUsbScanStarted || !playList.isEmpty()) {
            return;
        }
        autoUsbScanStarted = true;

        showUsbScanProgress(1, getString(R.string.usb_scan_preparing));
        // 检查是否需要请求存储权限
        if (needRequestStoragePermission()) {
            storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            return;
        }
        autoPlayUsbVideoFolderOnStartup();
    }

    /**
     * 检查是否需要请求存储权限
     *
     * Android 6.0（API 23）及以上需要运行时请求权限
     *
     * @return true如果需要请求READ_EXTERNAL_STORAGE权限
     */
    private boolean needRequestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 应用启动时自动播放U盘中的video_play文件夹
     * 在后台线程执行扫描，找到视频后自动播放第一个
     */
    private void autoPlayUsbVideoFolderOnStartup() {
        // 如果已有播放列表或正在扫描，则退出
        if (!playList.isEmpty() || usbScanRunning) {
            hideUsbScanProgress();
            return;
        }

        usbScanRunning = true;
        showUsbScanProgress(1, getString(R.string.usb_scan_preparing));

        usbScanExecutor.execute(() -> {
            // 执行USB扫描
            UsbScanResult result = scanUsbVideoFoldersWithProgress();
            runOnUiThread(() -> {
                usbScanRunning = false;
                hideUsbScanProgress();

                // 如果已有播放列表（用户可能手动添加了），跳过
                if (!playList.isEmpty()) {
                    return;
                }
                // 如果扫描到了视频
                if (!result.items.isEmpty()) {
                    playList.clear();
                    playList.addAll(result.items);
                    playAt(0, true);
                    Toast.makeText(this, getString(R.string.folder_loaded, playList.size()), Toast.LENGTH_SHORT).show();
                } else if (result.folderFound) {
                    // 找到了文件夹但里面没有视频
                    Toast.makeText(this, R.string.no_video_in_folder, Toast.LENGTH_SHORT).show();
                } else {
                    // 没有找到video_play文件夹
                    Toast.makeText(this, R.string.usb_scan_no_video_found, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    /**
     * 扫描USB存储设备中的video_play文件夹
     * 支持进度回调，可以显示扫描进度
     *
     * 扫描流程：
     * 1. 遍历预定义的根目录列表
     * 2. 递归查找名为"video_play"的文件夹
     * 3. 统计文件夹中的所有视频文件
     * 4. 收集所有视频文件
     * 5. 如果File API找不到视频，使用MediaStore作为兜底方案
     * 6. 对结果按名称排序
     *
     * @return 扫描结果，包含找到的所有视频
     */
    @NonNull
    private UsbScanResult scanUsbVideoFoldersWithProgress() {
        UsbScanResult result = new UsbScanResult();
        // 构建可能包含video_play文件夹的目录候选列表
        List<File> candidates = buildUsbVideoPlayCandidates();
        int candidateTotal = Math.max(1, candidates.size());
        Set<String> visitedFiles = new HashSet<>();

        // 遍历每个候选目录
        for (int i = 0; i < candidates.size(); i++) {
            File folder = candidates.get(i);
            // 更新进度：显示正在检查第几个候选目录
            int phaseProgress = Math.max(1, Math.min(30, (i + 1) * 30 / candidateTotal));
            postUsbScanProgress(phaseProgress, getString(R.string.usb_scan_candidates, i + 1, candidateTotal));

            // 验证目录是否有效
            if (folder == null || !folder.exists() || !folder.isDirectory() || !folder.canRead()) {
                continue;
            }

            result.folderFound = true;
            // 统计该目录下所有节点（文件+目录）的数量，用于计算进度
            Set<String> countVisitedDirs = new HashSet<>();
            int totalNodes = countFolderNodes(folder, countVisitedDirs);
            UsbScanProgress progress = new UsbScanProgress(totalNodes);

            // 收集该目录下的所有视频文件
            Set<String> collectVisitedDirs = new HashSet<>();
            collectVideoFilesFromFolderWithProgress(folder, collectVisitedDirs, visitedFiles, result.items, progress);
        }

        // 某些设备上U盘目录可见但File API不可遍历，使用MediaStore兜底
        if (result.items.isEmpty()) {
            collectVideosFromMediaStore(visitedFiles, result);
        }

        // 按名称排序
        Collections.sort(result.items, Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT)));
        postUsbScanProgress(100, getString(R.string.usb_scan_completed, result.items.size()));
        return result;
    }

    /**
     * 构建可能包含video_play文件夹的候选目录列表
     * 从预定义的根目录开始递归搜索
     *
     * @return 找到的video_play文件夹列表
     */
    @NonNull
    private List<File> buildUsbVideoPlayCandidates() {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String rootPath : USB_SCAN_ROOTS) {
            File root = new File(rootPath);
            if (!root.exists() || !root.isDirectory() || !root.canRead()) {
                continue;
            }
            // 递归收集video_play文件夹
            collectVideoPlayFoldersRecursively(root, USB_SCAN_MAX_DEPTH, seen, out);
        }

        return out;
    }

    /**
     * 递归收集video_play文件夹
     *
     * @param current 当前遍历的目录
     * @param depth 剩余递归深度
     * @param seen 已访问目录的路径集合（用于去重）
     * @param out 找到的video_play文件夹输出列表
     */
    private void collectVideoPlayFoldersRecursively(@NonNull File current,
                                                    int depth,
                                                    @NonNull Set<String> seen,
                                                    @NonNull List<File> out) {
        // 递归深度用尽或目录无效时退出
        if (depth < 0 || !current.exists() || !current.isDirectory() || !current.canRead()) {
            return;
        }

        String currentName = current.getName();
        // 检查当前目录是否为目标文件夹
        if (!TextUtils.isEmpty(currentName) && USB_VIDEO_FOLDER_NAME.equalsIgnoreCase(currentName)) {
            String key = safeCanonicalPath(current);
            if (seen.add(key)) {
                out.add(current);
            }
            // 找到目标目录后只在其内部扫描视频，不再继续向下找同名目录
            return;
        }

        // 跳过不需要扫描的系统目录
        if (shouldSkipScanDir(current)) {
            return;
        }

        File[] children = current.listFiles();
        if (children == null || children.length == 0) {
            return;
        }

        // 递归遍历子目录
        for (File child : children) {
            if (child == null || !child.isDirectory() || !child.canRead()) {
                continue;
            }
            collectVideoPlayFoldersRecursively(child, depth - 1, seen, out);
        }
    }

    /**
     * 判断目录是否应该跳过扫描
     * 跳过Android系统目录和其他不需要扫描的目录
     *
     * @param dir 要检查的目录
     * @return true如果应该跳过
     */
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

    /**
     * 统计文件夹中所有节点的数量
     * 包括文件和目录，用于计算扫描进度
     *
     * @param folder 要统计的文件夹
     * @param visitedDirs 已访问目录的集合（用于去重）
     * @return 节点总数
     */
    private int countFolderNodes(@Nullable File folder, @NonNull Set<String> visitedDirs) {
        if (folder == null || !folder.exists() || !folder.isDirectory() || !folder.canRead()) {
            return 0;
        }

        String folderKey = safeCanonicalPath(folder);
        if (!visitedDirs.add(folderKey)) {
            return 0;
        }

        int count = 1;  // 计数当前目录本身
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

    /**
     * 从文件夹中收集视频文件，支持进度回调
     *
     * @param folder 要扫描的文件夹
     * @param visitedDirs 已访问目录的集合（用于去重）
     * @param visitedFiles 已访问文件的集合（用于去重）
     * @param out 找到的视频文件输出列表
     * @param progress 进度跟踪对象
     */
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
            // 如果是目录，递归处理
            if (node.isDirectory()) {
                collectVideoFilesFromFolderWithProgress(node, visitedDirs, visitedFiles, out, progress);
                continue;
            }

            progress.processedNodes++;
            // 检查是否为视频文件
            if (!isVideoFile(node)) {
                maybeDispatchUsbScanProgress(progress);
                continue;
            }

            // 检查文件是否已添加过
            String fileKey = safeCanonicalPath(node);
            if (!visitedFiles.add(fileKey)) {
                maybeDispatchUsbScanProgress(progress);
                continue;
            }

            // 添加到结果列表
            out.add(new PlayItem(Uri.fromFile(node), safeName(node.getName())));
            progress.foundVideos++;
            maybeDispatchUsbScanProgress(progress);
        }
    }

    /**
     * 可能在需要时分发USB扫描进度更新
     * 控制更新频率，避免过于频繁的UI刷新
     *
     * @param progress 进度跟踪对象
     */
    private void maybeDispatchUsbScanProgress(@NonNull UsbScanProgress progress) {
        // 计算完成百分比
        int completedPercent = Math.min(100, Math.max(1, progress.processedNodes * 100 / progress.totalNodes));
        // 计算阶段百分比（30-99%为扫描阶段）
        int phasePercent = Math.min(99, 30 + (completedPercent * 70 / 100));

        // 在特定时机更新进度：开始时、每20个节点、或结束时
        if (progress.processedNodes == 1
                || progress.processedNodes % 20 == 0
                || progress.processedNodes >= progress.totalNodes) {
            postUsbScanProgress(
                    phasePercent,
                    getString(R.string.usb_scan_progress, progress.foundVideos, phasePercent));
        }
    }

    /**
     * 在主线程显示USB扫描进度
     *
     * @param progress 进度值（0-100）
     * @param text 要显示的文本
     */
    private void showUsbScanProgress(int progress, @NonNull String text) {
        if (usbScanContainer == null || pbUsbScan == null || tvUsbScanStatus == null) {
            return;
        }
        usbScanContainer.setVisibility(View.VISIBLE);
        pbUsbScan.setProgress(Math.max(0, Math.min(100, progress)));
        tvUsbScanStatus.setText(text);
    }

    /**
     * 在后台线程中安全地更新USB扫描进度
     * 将操作切换到主线程执行
     *
     * @param progress 进度值（0-100）
     * @param text 要显示的文本
     */
    private void postUsbScanProgress(int progress, @NonNull String text) {
        runOnUiThread(() -> showUsbScanProgress(progress, text));
    }

    /**
     * 隐藏USB扫描进度显示
     */
    private void hideUsbScanProgress() {
        if (usbScanContainer == null || pbUsbScan == null || tvUsbScanStatus == null) {
            return;
        }
        usbScanContainer.setVisibility(View.GONE);
        pbUsbScan.setProgress(0);
        tvUsbScanStatus.setText(R.string.usb_scan_preparing);
    }

    /**
     * 使用MediaStore收集U盘中的视频文件
     * 作为File API扫描失败的兜底方案
     *
     * @param visitedFiles 已访问文件的集合（用于去重）
     * @param result 扫描结果收集器
     */
    private void collectVideosFromMediaStore(@NonNull Set<String> visitedFiles, @NonNull UsbScanResult result) {
        Cursor cursor = null;
        try {
            // Android Q及以上使用EXTERNAL存储卷，Q以下使用EXTERNAL_CONTENT_URI
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

            // 查询投影：视频ID、显示名称、相对路径
            String[] projection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH
            };
            // 查询条件：相对路径包含"video_play/"
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

            // 遍历查询结果
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idIndex);
                Uri uri = ContentUris.withAppendedId(collection, id);
                String key = uri.toString();
                // 去重检查
                if (!visitedFiles.add(key)) {
                    continue;
                }

                // 检查路径是否在video_play目录下
                String relativePath = pathIndex >= 0 ? cursor.getString(pathIndex) : null;
                if (!TextUtils.isEmpty(relativePath)
                        && relativePath.toLowerCase(Locale.ROOT).contains("video_play/")) {
                    result.folderFound = true;
                }

                String name = nameIndex >= 0 ? cursor.getString(nameIndex) : null;
                result.items.add(new PlayItem(uri, safeName(name)));
            }
        } catch (Exception ignored) {
            // 忽略MediaStore兜底错误
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 安全获取文件的规范路径
     * 如果获取规范路径失败，回退到绝对路径
     *
     * @param file 文件对象
     * @return 文件的规范路径或绝对路径
     */
    @NonNull
    private String safeCanonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }

    /**
     * 判断文件是否为视频文件
     * 通过检查文件扩展名来判断
     *
     * 支持的格式：mp4, mkv, avi, mov, flv, wmv, m4v, 3gp, ts
     *
     * @param file 要检查的文件
     * @return true如果是视频文件
     */
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

    // ==================== 文件选择相关 ====================

    /**
     * 打开视频选择器
     * 使用系统文档选择器选择单个视频文件
     */
    private void openVideoPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pickVideoLauncher.launch(intent);
    }

    /**
     * 打开文件夹选择器
     * 使用系统文档树选择器选择文件夹
     */
    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pickFolderLauncher.launch(intent);
    }

    /**
     * 播放单个视频
     * 清除当前播放列表，只保留选中的视频
     *
     * @param uri 视频文件的URI
     */
    private void playSingleVideo(@NonNull Uri uri) {
        playList.clear();
        String displayName = queryDisplayName(uri);
        if (TextUtils.isEmpty(displayName)) {
            displayName = uri.getLastPathSegment();
        }
        playList.add(new PlayItem(uri, safeName(displayName)));
        playAt(0, true);
    }

    /**
     * 播放选中文件夹中的所有视频
     *
     * @param treeUri 文件夹的树URI
     */
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

        // 按名称排序
        Collections.sort(temp, Comparator.comparing(item -> item.name.toLowerCase(Locale.ROOT)));

        playList.clear();
        playList.addAll(temp);
        playAt(0, true);

        Toast.makeText(this, getString(R.string.folder_loaded, playList.size()), Toast.LENGTH_SHORT).show();
    }

    /**
     * 递归收集文件夹中的所有视频文件
     *
     * @param node 当前遍历的节点（文件或目录）
     * @param out 找到的视频列表
     */
    private void collectVideosRecursively(@Nullable DocumentFile node, @NonNull List<PlayItem> out) {
        if (node == null || !node.exists() || !node.canRead()) {
            return;
        }
        // 如果是文件，检查并添加
        if (node.isFile()) {
            if (isVideoFile(node) && node.getUri() != null) {
                out.add(new PlayItem(node.getUri(), safeName(node.getName())));
            }
            return;
        }

        // 如果是目录，递归处理子节点
        DocumentFile[] children = node.listFiles();
        if (children == null || children.length == 0) {
            return;
        }
        for (DocumentFile child : children) {
            collectVideosRecursively(child, out);
        }
    }

    /**
     * 判断DocumentFile是否为视频文件
     * 同时检查MIME类型和文件扩展名
     *
     * @param file 要检查的文件
     * @return true如果是视频文件
     */
    private boolean isVideoFile(@NonNull DocumentFile file) {
        if (!file.isFile()) {
            return false;
        }
        // 首先检查MIME类型
        String type = file.getType();
        if (!TextUtils.isEmpty(type) && type.startsWith("video/")) {
            return true;
        }
        // MIME类型不可用时，检查文件扩展名
        String name = file.getName();
        if (TextUtils.isEmpty(name)) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".avi")
                || lower.endsWith(".mov") || lower.endsWith(".flv") || lower.endsWith(".wmv")
                || lower.endsWith(".m4v") || lower.endsWith(".3gp") || lower.endsWith(".ts");
    }

    // ==================== 播放控制相关 ====================

    /**
     * 在指定索引位置播放视频
     *
     * @param index 播放列表中的索引
     * @param autoStart 是否自动开始播放
     */
    private void playAt(int index, boolean autoStart) {
        if (playList.isEmpty() || index < 0 || index >= playList.size()) {
            return;
        }

        currentIndex = index;
        PlayItem item = playList.get(currentIndex);

        // 释放之前的播放器资源
        videoView.release();
        // 设置循环模式
        videoView.setLooping(playMode == PlayMode.SINGLE_LOOP);
        // 设置视频源
        videoView.setUrl(item.uri.toString());
        if (autoStart) {
            videoView.start();
            btnToggle.setText(R.string.pause);
        } else {
            btnToggle.setText(R.string.play);
        }

        // 更新路径显示
        tvPath.setText(getString(R.string.current_video_with_index,
                currentIndex + 1,
                playList.size(),
                item.name));
    }

    /**
     * 显示播放列表对话框
     */
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

    /**
     * 显示播放模式选择对话框
     */
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

    /**
     * 更新播放模式按钮的文字
     */
    private void updateModeButtonText() {
        if (btnMode == null) {
            return;
        }
        int modeText = playMode == PlayMode.SINGLE_LOOP ? R.string.mode_single_loop : R.string.mode_list_loop;
        btnMode.setText(getString(R.string.play_mode_prefix, getString(modeText)));
    }

    /**
     * 更新全屏按钮的文字
     */
    private void updateFullscreenButtonText() {
        if (btnFullscreen == null) {
            return;
        }
        btnFullscreen.setText(appFullscreen
                ? R.string.exit_fullscreen
                : R.string.enter_fullscreen);
    }

    /**
     * 切换播放/暂停状态
     *
     * @param showToastIfEmpty 如果播放列表为空是否显示提示
     */
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
        // 如果播放器处于空闲、完成或错误状态，需要重新加载视频
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

    /**
     * 获取URI的持久化读取权限
     * 使得在重启应用后仍能访问该文件
     *
     * @param data 包含权限标志的Intent数据
     * @param uri 要获取权限的URI
     */
    private void takePersistReadPermission(@NonNull Intent data, @NonNull Uri uri) {
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
            // 有些ROM/Provider不支持持久化授权，不影响本次播放
        }
    }

    /**
     * 安全获取文件名，处理空值情况
     *
     * @param name 原始文件名
     * @return 处理后的文件名，如果为空则返回默认名称
     */
    @NonNull
    private String safeName(@Nullable String name) {
        return TextUtils.isEmpty(name) ? getString(R.string.unknown_video_name) : name;
    }

    /**
     * 查询URI对应的显示名称
     *
     * @param uri 文件的URI
     * @return 显示名称，查询失败返回null
     */
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

    /**
     * 获取本机的局域网IPv4地址
     *
     * 遍历所有网络接口，查找IPv4地址
     * 只返回私有IP地址（192.x.x.x、10.x.x.x、172.16-31.x.x.x）
     *
     * @return 本机IP地址，获取失败返回null
     */
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
                    // 过滤IPv4地址，排除回环地址
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String host = addr.getHostAddress();
                        // 只返回私有IP地址
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

    // ==================== Activity生命周期回调 ====================

    /**
     * 处理返回键按下事件
     *
     * 返回逻辑：
     * 1. 如果侧边抽屉打开，先关闭抽屉
     * 2. 如果处于全屏模式，退出全屏
     * 3. 否则执行默认的返回行为（关闭Activity）
     */
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

    /**
     * Activity恢复时的回调
     *
     * 重新进入全屏模式（如果之前是全屏）
     * 重新启动局域网健康检查定时任务
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (appFullscreen) {
            enterFullscreenUi();
        }
        // 移除可能存在的旧任务
        lanHealthHandler.removeCallbacks(lanHealthRunnable);
        // 立即执行一次，然后定时执行
        lanHealthHandler.post(lanHealthRunnable);
    }

    /**
     * 窗口焦点变化时的回调
     * 当Activity重新获得焦点时，确保全屏模式正确显示
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && appFullscreen) {
            enterFullscreenUi();
        }
    }

    /**
     * Activity暂停时的回调
     *
     * 停止局域网健康检查
     * 如果正在播放，自动暂停视频
     */
    @Override
    protected void onPause() {
        super.onPause();
        // 停止局域网健康检查
        lanHealthHandler.removeCallbacks(lanHealthRunnable);
        // 如果正在播放，暂停
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
            btnToggle.setText(R.string.play);
        }
    }

    /**
     * Activity销毁时的回调
     *
     * 清理所有资源：
     * 1. 移除所有Handler回调
     * 2. 关闭线程池
     * 3. 停止局域网服务器
     * 4. 释放播放器资源
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 移除所有Handler消息
        lanHealthHandler.removeCallbacksAndMessages(null);
        // 关闭线程池
        lanExecutor.shutdownNow();
        usbScanExecutor.shutdownNow();
        // 停止局域网服务器
        stopLanControlServer();
        // 释放播放器资源
        if (videoView != null) {
            videoView.removeOnStateChangeListener(playStateListener);
            videoView.setOnTouchListener(null);
            videoView.release();
        }
    }

    // ==================== 回调接口实现 ====================

    /**
     * 局域网健康检查的Runnable实现
     * 定时执行局域网状态检查
     */
    private final Runnable lanHealthRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLanStatus(false);
            // 延迟后再次执行
            lanHealthHandler.postDelayed(this, LAN_HEALTH_INTERVAL_MS);
        }
    };

    /**
     * 播放状态监听器
     * 监听播放状态变化和播放器状态变化
     */
    private final VideoView.SimpleOnStateChangeListener playStateListener =
            new VideoView.SimpleOnStateChangeListener() {
                /**
                 * 播放状态变化时的回调
                 * 用于处理列表循环模式下自动播放下一个
                 */
                @Override
                public void onPlayStateChanged(int playState) {
                    // 当播放完成且处于列表循环模式时，自动播放下一个
                    if (playState == VideoView.STATE_PLAYBACK_COMPLETED
                            && playMode == PlayMode.LIST_LOOP
                            && !playList.isEmpty()) {
                        int nextIndex = (currentIndex + 1) % playList.size();
                        playAt(nextIndex, true);
                    }
                }

                /**
                 * 播放器状态变化时的回调
                 * 用于更新全屏按钮文字
                 */
                @Override
                public void onPlayerStateChanged(int playerState) {
                    updateFullscreenButtonText();
                }
            };

    /**
     * 视频选择器的回调
     * 使用Activity Result API处理选择结果
     */
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
                // 获取持久化权限
                takePersistReadPermission(data, uri);
                // 播放选中的视频
                playSingleVideo(uri);
            });

    /**
     * 文件夹选择器的回调
     * 使用Activity Result API处理选择结果
     */
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
                // 获取持久化权限
                takePersistReadPermission(data, treeUri);
                // 播放文件夹中的视频
                playFolderVideos(treeUri);
            });

    /**
     * 存储权限请求的回调
     * 用于USB视频扫描
     */
    private final ActivityResultLauncher<String> storagePermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    // 权限授予后开始自动播放USB视频
                    autoPlayUsbVideoFolderOnStartup();
                } else {
                    hideUsbScanProgress();
                    Toast.makeText(this, R.string.usb_scan_permission_denied, Toast.LENGTH_SHORT).show();
                }
            });
}
