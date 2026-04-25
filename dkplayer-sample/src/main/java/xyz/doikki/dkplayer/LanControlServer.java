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

public class LanControlServer extends NanoHTTPD {

    public interface ControlBridge {
        @NonNull String getStateJson();
        void handleAction(@NonNull String action, @Nullable String value);
    }

    private static final String API_PREFIX_V1 = "/api/v1";
    private static final String HEADER_ALLOW_ORIGIN = "*";
    private static final String HEADER_ALLOW_METHODS = "GET,POST,OPTIONS";
    private static final String HEADER_ALLOW_HEADERS = "Content-Type, Authorization";

    private final ControlBridge bridge;

    public LanControlServer(int port, @NonNull ControlBridge bridge) {
        super(port);
        this.bridge = bridge;
    }

    public void startSafely() throws Exception {
        start(SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.OPTIONS.equals(method)) {
            return withCors(newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", "{\"ok\":true}"));
        }

        if ("/".equals(uri)) {
            return html(buildPageHtml());
        }

        if ("/api/ping".equals(uri)) {
            return json(Response.Status.OK, "{\"ok\":true}");
        }

        if ("/api/state".equals(uri)) {
            return json(Response.Status.OK, bridge.getStateJson());
        }

        if ("/api/action".equals(uri)) {
            if (!supportsActionMethod(method)) {
                return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method not allowed", "method_not_allowed");
            }
            Map<String, String> params = collectRequestParams(session);
            String action = safeTrim(params.get("action"));
            String value = params.get("value");
            if (action.isEmpty()) {
                return jsonError(Response.Status.BAD_REQUEST, "missing action", "missing_action");
            }
            bridge.handleAction(action, value);
            return json(Response.Status.OK, "{\"ok\":true}");
        }

        if ((API_PREFIX_V1 + "/ping").equals(uri)) {
            JSONObject data = new JSONObject();
            putSafe(data, "server", "lan-control");
            putSafe(data, "version", "v1");
            return jsonOk(data);
        }

        if ((API_PREFIX_V1 + "/state").equals(uri)) {
            return jsonOk(getStateObject());
        }

        if ((API_PREFIX_V1 + "/state/playing").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "playing", state.optBoolean("playing", false));
            return jsonOk(data);
        }

        if ((API_PREFIX_V1 + "/state/fullscreen").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "fullScreen", state.optBoolean("fullScreen", false));
            return jsonOk(data);
        }

        if ((API_PREFIX_V1 + "/state/mode").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "mode", state.optString("mode", "list"));
            putSafe(data, "modeText", state.optString("modeText", ""));
            return jsonOk(data);
        }

        if ((API_PREFIX_V1 + "/state/current").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            putSafe(data, "currentIndex", state.optInt("currentIndex", -1));
            putSafe(data, "currentName", state.optString("currentName", ""));
            putSafe(data, "total", state.optInt("total", 0));
            return jsonOk(data);
        }

        if ((API_PREFIX_V1 + "/state/playlist").equals(uri)) {
            JSONObject state = getStateObject();
            JSONObject data = new JSONObject();
            JSONArray list = state.optJSONArray("playlist");
            putSafe(data, "playlist", list == null ? new JSONArray() : list);
            putSafe(data, "currentIndex", state.optInt("currentIndex", -1));
            putSafe(data, "total", state.optInt("total", 0));
            return jsonOk(data);
        }

        String directAction = actionFromPath(uri);
        if (directAction != null) {
            if (!supportsActionMethod(method)) {
                return jsonError(Response.Status.METHOD_NOT_ALLOWED, "method not allowed", "method_not_allowed");
            }
            Map<String, String> params = collectRequestParams(session);
            String value = params.get("value");
            if ("play_index".equals(directAction)) {
                String index = safeTrim(params.get("index"));
                if (index.isEmpty()) {
                    index = safeTrim(params.get("value"));
                }
                if (index.isEmpty()) {
                    return jsonError(Response.Status.BAD_REQUEST, "missing index", "missing_index");
                }
                value = index;
            }

            bridge.handleAction(directAction, value);
            JSONObject result = new JSONObject();
            putSafe(result, "action", directAction);
            putSafe(result, "value", value == null ? "" : value);
            return jsonOk(result);
        }

        return jsonError(Response.Status.NOT_FOUND, "not found", "not_found");
    }

    private boolean supportsActionMethod(@NonNull Method method) {
        return Method.GET.equals(method) || Method.POST.equals(method);
    }

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

    @NonNull
    private JSONObject getStateObject() {
        try {
            return new JSONObject(bridge.getStateJson());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    @NonNull
    private Map<String, String> collectRequestParams(@NonNull IHTTPSession session) {
        Map<String, String> params = new HashMap<>(session.getParms());
        if (Method.POST.equals(session.getMethod())) {
            try {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                params.putAll(session.getParms());

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
                // ignore request parse errors and fallback to query params
            }
        }
        return params;
    }

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

    @NonNull
    private String safeTrim(@Nullable String value) {
        return value == null ? "" : value.trim();
    }

    private void putSafe(@NonNull JSONObject object, @NonNull String key, @Nullable Object value) {
        try {
            object.put(key, value);
        } catch (Exception ignored) {
        }
    }

    private Response html(String content) {
        return withCors(newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", content));
    }

    private Response json(Response.Status status, String content) {
        return withCors(newFixedLengthResponse(status, "application/json; charset=UTF-8", content));
    }

    private Response jsonOk(@NonNull JSONObject data) {
        JSONObject root = new JSONObject();
        putSafe(root, "ok", true);
        putSafe(root, "data", data);
        putSafe(root, "ts", System.currentTimeMillis());
        return json(Response.Status.OK, root.toString());
    }

    private Response jsonError(@NonNull Response.Status status, @NonNull String message, @NonNull String code) {
        JSONObject root = new JSONObject();
        putSafe(root, "ok", false);
        putSafe(root, "error", code);
        putSafe(root, "message", message);
        putSafe(root, "ts", System.currentTimeMillis());
        return json(status, root.toString());
    }

    private Response withCors(@NonNull Response response) {
        response.addHeader("Access-Control-Allow-Origin", HEADER_ALLOW_ORIGIN);
        response.addHeader("Access-Control-Allow-Methods", HEADER_ALLOW_METHODS);
        response.addHeader("Access-Control-Allow-Headers", HEADER_ALLOW_HEADERS);
        return response;
    }

    private String buildPageHtml() {
        return "<!DOCTYPE html>"
                + "<html><head><meta charset='utf-8'/><meta name='viewport' content='width=device-width,initial-scale=1'/>"
                + "<title>局域网控制</title>"
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
                + "<div class='card'><div id='title'>播放器状态加载中...</div><div id='meta' class='hint'></div></div>"
                + "<div class='card'><div class='row'>"
                + "<button onclick=\"act('/api/v1/player/toggle')\">播放/暂停</button>"
                + "<button onclick=\"act('/api/v1/player/replay')\">重新播放</button>"
                + "<button onclick=\"act('/api/v1/player/prev')\">上一集</button>"
                + "<button onclick=\"act('/api/v1/player/next')\">下一集</button>"
                + "<button onclick=\"act('/api/v1/player/fullscreen/toggle')\">全屏切换</button>"
                + "</div></div>"
                + "<div class='card'><div class='row'>"
                + "<button onclick=\"act('/api/v1/picker/video')\">选择视频(手机上确认)</button>"
                + "<button onclick=\"act('/api/v1/picker/folder')\">选择文件夹(手机上确认)</button>"
                + "<button onclick=\"act('/api/v1/player/mode/single')\">单集循环</button>"
                + "<button onclick=\"act('/api/v1/player/mode/list')\">列表循环</button>"
                + "</div><div class='hint' style='margin-top:8px'>说明：文件/文件夹选择器会在播放器手机端弹出，请在手机上完成选择。</div></div>"
                + "<div class='card'><div>播放列表</div><div id='playlist'></div></div>"
                + "<div class='card hint'>开发者文档：请查看项目根目录 LAN_CONTROL_API.md</div>"
                + "<div class='copyright'>@版权所有：山东华赛科技发展有限公司</div>"
                + "<script>"
                + "async function callApi(path, body){const opt={method:'POST',headers:{'Content-Type':'application/json'}};if(body){opt.body=JSON.stringify(body);}return fetch(path,opt);}"
                + "async function act(path,body){await callApi(path,body);setTimeout(load,180);}"
                + "async function load(){try{const r=await fetch('/api/v1/state?_='+Date.now());const x=await r.json();const s=x.data||{};"
                + "document.getElementById('title').innerText=`${s.playing?'播放中':'已暂停'} ｜ ${s.currentName||'未选择视频'}`;"
                + "document.getElementById('meta').innerText=`模式：${s.modeText||''} ｜ 全屏：${s.fullScreen?'是':'否'} ｜ 列表：${(s.currentIndex||0)+1}/${s.total||0}`;"
                + "const wrap=document.getElementById('playlist');wrap.innerHTML='';"
                + "if(!s.playlist||s.playlist.length===0){wrap.innerHTML='<div class=\"hint\">暂无列表</div>'; }"
                + "else{(s.playlist||[]).forEach(it=>{const b=document.createElement('button');const p=it.index===s.currentIndex?'▶ ':'';b.innerText=`${p}${it.index+1}. ${it.name||''}`;b.onclick=()=>act('/api/v1/playlist/play',{index:it.index});wrap.appendChild(b);});}"
                + "}catch(e){document.getElementById('title').innerText='连接失败，请确认手机与控制端在同一局域网';}}"
                + "load();setInterval(load,1500);"
                + "</script></body></html>";
    }
}
