package xyz.doikki.dkplayer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 局域网控制服务器
 *
 * 本类实现了一个基于NanoHTTPD的轻量级HTTP服务器，提供以下功能：
 * 1. RESTful API接口，用于远程控制播放器的播放状态
 * 2. Web控制页面，用户可通过浏览器直接控制播放器
 *
 * 支持的操作包括：播放/暂停、上一集/下一集、全屏切换、播放模式切换、文件选择等
 */
public class LanControlServer extends NanoHTTPD {

    /**
     * 控制桥接接口
     *
     * 用于HTTP服务器与播放器之间的通信
     * getStateJson: 获取当前播放器的状态JSON，供远程客户端查询
     * handleAction: 处理来自远程客户端的操作指令
     */
    public interface ControlBridge {
        @NonNull String getStateJson();
        void handleAction(@NonNull String action, @Nullable String value);
    }

    // ==================== 常量定义 ====================

    /** API版本1的前缀路径 */
    private static final String API_PREFIX_V1 = "/api/v1";
    /** CORS允许所有来源 */
    private static final String HEADER_ALLOW_ORIGIN = "*";
    /** CORS允许的HTTP方法 */
    private static final String HEADER_ALLOW_METHODS = "GET,POST,OPTIONS";
    /** CORS允许的请求头 */
    private static final String HEADER_ALLOW_HEADERS = "Content-Type, Authorization";

    // ==================== 成员变量 ====================

    /** 控制桥接接口实例，用于与播放器通信 */
    private final ControlBridge bridge;

    // ==================== 构造函数 ====================

    /**
     * 构造函数
     * @param port HTTP服务器监听的端口号
     * @param bridge 控制桥接接口实现，用于服务器与播放器之间的通信
     */
    public LanControlServer(int port, @NonNull ControlBridge bridge) {
        super(port);
        this.bridge = bridge;
    }

    /**
     * 安全启动服务器
     * 使用非阻塞模式启动HTTP服务器
     * @throws Exception 如果启动失败
     */
    public void startSafely() throws Exception {
        start(SOCKET_READ_TIMEOUT, false);
    }

    // ==================== HTTP请求处理 ====================

    /**
     * 处理HTTP请求的主方法
     *
     * 根据请求的URI和HTTP方法，返回相应的响应
     * 支持的端点：
     * - GET  /           : 返回Web控制页面HTML
     * - GET  /api/ping   : 心跳检测接口
     * - GET  /api/state   : 获取播放器状态（旧版API）
     * - POST /api/action  : 执行播放操作（旧版API）
     * - GET  /api/v1/ping : 服务器信息查询
     * - GET  /api/v1/state : 获取完整播放器状态
     * - GET  /api/v1/state/playing : 获取播放状态
     * - GET  /api/v1/state/fullscreen : 获取全屏状态
     * - GET  /api/v1/state/mode : 获取播放模式
     * - GET  /api/v1/state/current : 获取当前播放项信息
     * - GET  /api/v1/state/playlist : 获取播放列表
     * - POST /api/v1/player/* : 播放器控制操作
     *
     * @param session HTTP会话对象，包含请求信息
     * @return HTTP响应对象
     */
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // 处理CORS预检请求
        if (Method.OPTIONS.equals(method)) {
            return withCors(newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", "{\"ok\":true}"));
        }

        // 根路径返回Web控制页面
        if ("/".equals(uri)) {
            return html(buildPageHtml());
        }

        // 旧版API：心跳检测
        if ("/api/ping".equals(uri)) {
            return json(Response.Status.OK, "{\"ok\":true}");
        }

        // 旧版API：获取播放器状态
        if ("/api/state".equals(uri)) {
            return json(Response.Status.OK, bridge.getStateJson());
        }

        // 旧版API：执行播放操作
        if ("/api/action".equals(uri)) {
            // 检查HTTP方法是否支持
            if (!supportsActionMethod(method)) {
                return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method not allowed", "method_not_allowed");
            }
            // 收集请求参数
            Map<String, String> params = collectRequestParams(session);
            String action = safeTrim(params.get("action"));
            String value = params.get("value");
            // 验证action参数
            if (action.isEmpty()) {
                return jsonError(Response.Status.BAD_REQUEST, "missing action", "missing_action");
            }
            // 执行操作并返回成功响应
            bridge.handleAction(action, value);
            return json(Response.Status.OK, "{\"ok\":true}");
        }

        // 新版API v1：服务器信息
        if ((API_PREFIX_V1 + "/ping").equals(uri)) {
            JSONObject data = new JSONObject();
            putSafe(data, "server", "lan-control");
            putSafe(data, "version", "v1");
            return jsonOk(data);
        }

        // 新版API v1：获取完整状态
        if ((API_PREFIX_V1 + "/state").equals(uri)) {
            return jsonOk(getStateObject());
        }

        // 新版API v1：获取播放状态（是否正在播放）
        if ((API_PREFIX_V1 + "/state/playing").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "playing", state.optBoolean("playing", false));
            return jsonOk(data);
        }

        // 新版API v1：获取全屏状态
        if ((API_PREFIX_V1 + "/state/fullscreen").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "fullScreen", state.optBoolean("fullScreen", false));
            return jsonOk(data);
        }

        // 新版API v1：获取播放模式（单集循环/列表循环）
        if ((API_PREFIX_V1 + "/state/mode").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "mode", state.optString("mode", "list"));
            putSafe(data, "modeText", state.optString("modeText", ""));
            return jsonOk(data);
        }

        // 新版API v1：获取当前播放项信息
        if ((API_PREFIX_V1 + "/state/current").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "currentIndex", state.optInt("currentIndex", -1));
            putSafe(data, "currentName", state.optString("currentName", ""));
            putSafe(data, "total", state.optInt("total", 0));
            return jsonOk(data);
        }

        // 新版API v1：获取播放列表
        if ((API_PREFIX_V1 + "/state/playlist").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            JSONArray list = state.optJSONArray("playlist");
            putSafe(data, "playlist", list == null ? new JSONArray() : list);
            putSafe(data, "currentIndex", state.optInt("currentIndex", -1));
            putSafe(data, "total", state.optInt("total", 0));
            return jsonOk(data);
        }

        // 从路径中解析直接操作（如 /api/v1/player/play）
        String directAction = actionFromPath(uri);
        if (directAction != null) {
            // 检查HTTP方法是否支持
            if (!supportsActionMethod(method)) {
                return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method not allowed", "method_not_allowed");
            }
            // 收集请求参数
            Map<String, String> params = collectRequestParams(session);
            String value = params.get("value");
            // play_index 操作需要特殊处理索引参数
            if ("play_index".equals(directAction)) {
                String index = safeTrim(params.get("index"));
                // 如果没有index参数，尝试从value中获取
                if (index.isEmpty()) {
                    index = safeTrim(params.get("value"));
                }
                // 验证索引参数
                if (index.isEmpty()) {
                    return jsonError(Response.Status.BAD_REQUEST, "missing index", "missing_index");
                }
                value = index;
            }

            // 执行播放器操作
            bridge.handleAction(directAction, value);
            JSONObject result = new JSONObject();
            putSafe(result, "action", directAction);
            putSafe(result, "value", value == null ? "" : value);
            return jsonOk(result);
        }

        // 未找到匹配的端点
        return jsonError(Response.Status.NOT_FOUND, "not found", "not_found");
    }

    /**
     * 检查HTTP方法是否支持操作
     * 目前支持GET和POST方法
     *
     * @param method HTTP方法
     * @return true如果方法支持操作
     */
    private boolean supportsActionMethod(@NonNull Method method) {
        return Method.GET.equals(method) || Method.POST.equals(method);
    }

    /**
     * 从URI路径中解析操作类型
     * 将RESTful风格的路径映射为操作名称
     *
     * @param uri 请求的URI路径
     * @return 操作名称，如果无法解析则返回null
     */
    @Nullable
    private String actionFromPath(@NonNull String uri) {
        if ((API_PREFIX_V1 + "/player/play").equals(uri)) return "play";
        if ((API_PREFIX_V1 + "/player/pause").equals(uri)) return "pause";
        if ((API_PREFIX_V1 + "/player/toggle").equals(uri)) return "toggle";
        if ((API_PREFIX_V1 + "/player/replay").equals(uri)) return "replay";
        if ((API_PREFIX_V1 + "/player/next").equals(uri)) return "next";
        if ((API_PREFIX_V1 + "/player/prev").equals(uri)) return "prev";
        if ((API_PREFIX_V1 + "/player/fullscreen/toggle").equals(uri)) return "fullscreen";
        if ((API_PREFIX_V1 + "/player/mode/single").equals(uri)) return "mode_single";
        if ((API_PREFIX_V1 + "/player/mode/list").equals(uri)) return "mode_list";
        if ((API_PREFIX_V1 + "/picker/video").equals(uri)) return "pick_video";
        if ((API_PREFIX_V1 + "/picker/folder").equals(uri)) return "pick_folder";
        if ((API_PREFIX_V1 + "/playlist/play").equals(uri)) return "play_index";
        return null;
    }

    /**
     * 获取播放器状态对象
     * 从桥接接口获取JSON字符串并解析为JSONObject
     *
     * @return 播放器状态的JSON对象
     */
    @NonNull
    private JSONObject getStateObject() {
        try {
            return new JSONObject(bridge.getStateJson());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    /**
     * 收集HTTP请求参数
     * 支持GET查询参数和POST请求体参数
     * POST请求体支持JSON格式和表单格式
     *
     * @param session HTTP会话对象
     * @return 包含所有参数的Map
     */
    @NonNull
    private Map<String, String> collectRequestParams(@NonNull IHTTPSession session) {
        Map<String, String> params = new HashMap<>(session.getParms());
        // 处理POST请求体
        if (Method.POST.equals(session.getMethod())) {
            try {
                Map<String, String> files = new HashMap<>();
                // 解析请求体，可能包含文件上传
                session.parseBody(files);
                params.putAll(session.getParms());

                // 尝试读取并解析JSON请求体
                String postDataFile = files.get("postData");
                if (postDataFile != null && !postDataFile.isEmpty()) {
                    String body = readTextFile(postDataFile);
                    if (body != null && body.trim().startsWith("{")) {
                        JSONObject json = new JSONObject(body);
                        JSONArray names = json.names();
                        if (names != null) {
                            for (int i = 0; i < names.length(); i++) {
                                String key = names.optString(i, "");
                                if (!key.isEmpty()) {
                                    params.put(key, String.valueOf(json.opt(key)));
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
                // 忽略请求解析错误，回退到查询参数
            }
        }
        return params;
    }

    /**
     * 读取文本文件内容
     *
     * @param path 文件路径
     * @return 文件内容，如果读取失败则返回null
     */
    @Nullable
    private String readTextFile(@NonNull String path) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new FileInputStream(path), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 安全地去掉字符串两端的空白
     *
     * @param value 待处理的字符串，可能为null
     * @return 处理后的字符串，如果是null则返回空字符串
     */
    @NonNull
    private String safeTrim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 安全地向JSONObject中添加键值对
     * 捕获可能的JSONException异常
     *
     * @param object JSON对象
     * @param key 键名
     * @param value 值，可以为null
     */
    private void putSafe(@NonNull JSONObject object, @NonNull String key, @Nullable Object value) {
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
    }

    // ==================== 响应构建方法 ====================

    /**
     * 构建HTML响应
     *
     * @param content HTML内容
     * @return 配置好的HTTP响应
     */
    private Response html(String content) {
        return withCors(newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", content));
    }

    /**
     * 构建JSON响应
     *
     * @param status HTTP状态码
     * @param content JSON字符串内容
     * @return 配置好的HTTP响应
     */
    private Response json(Response.Status status, String content) {
        return withCors(newFixedLengthResponse(status, "application/json; charset=UTF-8", content));
    }

    /**
     * 构建成功响应的JSON数据
     * 自动添加 ok=true、data 字段和时间戳
     *
     * @param data 响应数据对象
     * @return 配置好的HTTP 200响应
     */
    private Response jsonOk(@NonNull JSONObject data) {
        JSONObject root = new JSONObject();
        putSafe(root, "ok", true);
        putSafe(root, "data", data);
        putSafe(root, "ts", System.currentTimeMillis());
        return json(Response.Status.OK, root.toString());
    }

    /**
     * 构建错误响应的JSON数据
     *
     * @param status HTTP状态码
     * @param message 错误消息
     * @param code 错误代码
     * @return 配置好的HTTP错误响应
     */
    private Response jsonError(@NonNull Response.Status status, @NonNull String message, @NonNull String code) {
        JSONObject root = new JSONObject();
        putSafe(root, "ok", false);
        putSafe(root, "error", code);
        putSafe(root, "message", message);
        putSafe(root, "ts", System.currentTimeMillis());
        return json(status, root.toString());
    }

    /**
     * 为响应添加CORS头
     * 允许跨域访问，支持所有来源和常用的HTTP方法
     *
     * @param response 原始响应对象
     * @return 添加了CORS头的响应对象
     */
    private Response withCors(@NonNull Response response) {
        response.addHeader("Access-Control-Allow-Origin", HEADER_ALLOW_ORIGIN);
        response.addHeader("Access-Control-Allow-Methods", HEADER_ALLOW_METHODS);
        response.addHeader("Access-Control-Allow-Headers", HEADER_ALLOW_HEADERS);
        return response;
    }

    // ==================== Web页面构建 ====================

    /**
     * 构建Web控制页面的HTML
     *
     * 该页面提供了播放器的远程控制界面，包括：
     * - 当前播放状态和视频信息显示
     * - 播放控制按钮（播放/暂停、重新播放、上一集、下一集、全屏）
     * - 播放模式切换（单集循环、列表循环）
     * - 文件选择按钮（选择视频、选择文件夹）
     * - 播放列表显示和快速跳转
     *
     * 页面使用AJAX轮询（每1.5秒）保持状态同步
     *
     * @return 完整的HTML页面字符串
     */
    private String buildPageHtml() {
        return "<!DOCTYPE html>"
                + "<html><head><meta charset='utf-8'/><meta name='viewport' content='width=device-width,initial-scale=1'/>"
                + "<title>局域网控制</title>"
                // 页面样式：深色主题，现代卡片式布局
                + "<style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif;background:#0f172a;color:#e2e8f0;margin:0;padding:16px;}"
                + ".card{background:#1e293b;border-radius:12px;padding:12px;margin-bottom:12px;}"
                + ".row{display:flex;gap:8px;flex-wrap:wrap;}"
                + "button{border:0;border-radius:10px;padding:10px 12px;background:#334155;color:#f8fafc;font-size:14px;}"
                + "button:active{background:#475569;}"
                + "#playlist button{width:100%;text-align:left;margin-top:6px;background:#263447;}"
                + ".hint{font-size:12px;color:#94a3b8;line-height:1.5;}"
                + ".copyright{font-size:11px;color:#64748b;text-align:center;margin-top:10px;}"
                + "</style></head><body>"
                // 状态显示卡片
                + "<div class='card'><div id='title'>播放器状态加载中...</div><div id='meta' class='hint'></div></div>"
                // 播放控制按钮卡片
                + "<div class='card'><div class='row'>"
                + "<button onclick=\"act('/api/v1/player/toggle')\">播放/暂停</button>"
                + "<button onclick=\"act('/api/v1/player/replay')\">重新播放</button>"
                + "<button onclick=\"act('/api/v1/player/prev')\">上一集</button>"
                + "<button onclick=\"act('/api/v1/player/next')\">下一集</button>"
                + "<button onclick=\"act('/api/v1/player/fullscreen/toggle')\">全屏切换</button>"
                + "</div></div>"
                // 文件选择按钮卡片
                + "<div class='card'><div class='row'>"
                + "<button onclick=\"act('/api/v1/picker/video')\">选择视频(手机上确认)</button>"
                + "<button onclick=\"act('/api/v1/picker/folder')\">选择文件夹(手机上确认)</button>"
                + "<button onclick=\"act('/api/v1/player/mode/single')\">单集循环</button>"
                + "<button onclick=\"act('/api/v1/player/mode/list')\">列表循环</button>"
                + "</div><div class='hint' style='margin-top:8px'>说明：文件/文件夹选择器会在播放器手机端弹出，请在手机上完成选择。</div></div>"
                // 播放列表卡片
                + "<div class='card'><div>播放列表</div><div id='playlist'></div></div>"
                // 开发者文档链接
                + "<div class='card hint'>开发者文档：请查看项目根目录 LAN_CONTROL_API.md</div>"
                // 版权信息
                + "<div class='copyright'>@版权所有：山东华赛科技发展有限公司</div>"
                // JavaScript脚本：处理API调用和状态同步
                + "<script>"
                // 发送POST请求到API端点
                + "async function callApi(path, body){const opt={method:'POST',headers:{'Content-Type':'application/json'}};if(body){opt.body=JSON.stringify(body);}return fetch(path,opt);}"
                // 执行操作后短暂延迟再刷新状态
                + "async function act(path,body){await callApi(path,body);setTimeout(load,180);}"
                // 加载并显示播放器状态
                + "async function load(){try{const r=await fetch('/api/v1/state?_='+Date.now());const x=await r.json();const s=x.data||{};"
                // 更新标题：显示播放状态和当前视频名
                + "document.getElementById('title').innerText=`${s.playing?'播放中':'已暂停'} ｜ ${s.currentName||'未选择视频'}`;"
                // 更新元信息：显示模式、全屏状态、列表进度
                + "document.getElementById('meta').innerText=`模式：${s.modeText||''} ｜ 全屏：${s.fullScreen?'是':'否'} ｜ 列表：${(s.currentIndex||0)+1}/${s.total||0}`;"
                // 清空播放列表容器
                + "const wrap=document.getElementById('playlist');wrap.innerHTML='';"
                // 如果播放列表为空，显示提示
                + "if(!s.playlist||s.playlist.length===0){wrap.innerHTML='<div class=\"hint\">暂无列表</div>'; }"
                // 渲染播放列表，每项可点击跳转播放
                + "else{(s.playlist||[]).forEach(it=>{const b=document.createElement('button');const p=it.index===s.currentIndex?'▶ ':'';b.innerText=`${p}${it.index+1}. ${it.name||''}`;b.onclick=()=>act('/api/v1/playlist/play',{index:it.index});wrap.appendChild(b);});}"
                // 捕获异常，显示连接失败提示
                + "}catch(e){document.getElementById('title').innerText='连接失败，请确认手机与控制端在同一局域网';}}"
                // 页面加载时立即刷新，之后每1.5秒轮询一次
                + "load();setInterval(load,1500);"
                + "</script></body></html>";
    }
}
