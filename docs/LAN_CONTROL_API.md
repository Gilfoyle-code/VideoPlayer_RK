## 局域网控制 API 文档（LAN Control API）

本文档用于给第三方网页/系统开发者对接播放器的局域网控制能力。

- **协议**：HTTP
- **默认端口**：`18080`
- **基础地址**：`http://<播放器IP>:18080`
- **版本前缀**：`/api/v1`
- **编码**：UTF-8
- **返回格式**：JSON

---

## 1. 快速开始

### 1.1 连通性检查

- **接口**：`GET /api/v1/ping`
- **作用**：检测服务是否可用。

示例：

```bash
curl "http://192.168.1.88:18080/api/v1/ping"
```

成功响应：

```json
{
  "ok": true,
  "data": {
    "server": "lan-control",
    "version": "v1"
  },
  "ts": 1714032000000
}
```

### 1.2 获取完整状态

- **接口**：`GET /api/v1/state`
- **作用**：一次性获取播放器主要状态（建议前端轮询该接口刷新 UI）。

示例：

```bash
curl "http://192.168.1.88:18080/api/v1/state"
```

成功响应（示例）：

```json
{
  "ok": true,
  "data": {
    "playing": true,
    "fullScreen": false,
    "currentIndex": 0,
    "total": 3,
    "mode": "list",
    "modeText": "列表循环",
    "currentName": "demo01.mp4",
    "playlist": [
      {"index": 0, "name": "demo01.mp4"},
      {"index": 1, "name": "demo02.mp4"},
      {"index": 2, "name": "demo03.mp4"}
    ]
  },
  "ts": 1714032000000
}
```

---

## 2. 认证与跨域

当前版本默认：

- **无需认证**（同局域网可访问）
- **已开启 CORS**
  - `Access-Control-Allow-Origin: *`
  - `Access-Control-Allow-Methods: GET,POST,OPTIONS`
  - `Access-Control-Allow-Headers: Content-Type, Authorization`

说明：可直接在自定义网页中通过 `fetch` 调用。

---

## 3. 统一返回规范（v1）

### 3.1 成功

```json
{
  "ok": true,
  "data": { },
  "ts": 1714032000000
}
```

### 3.2 失败

```json
{
  "ok": false,
  "error": "missing_index",
  "message": "missing index",
  "ts": 1714032000000
}
```

### 3.3 常见错误码

- `not_found`：接口不存在
- `method_not_allowed`：请求方法不允许
- `missing_action`：旧版通用动作接口缺少 `action`
- `missing_index`：播放列表播放接口缺少 `index`

---

## 4. 按钮控制接口（全量）

以下接口即网页所有按钮对应能力，支持 `GET` / `POST`（建议用 `POST`）。

## 4.1 播放控制

- **播放**：`POST /api/v1/player/play`
- **暂停**：`POST /api/v1/player/pause`
- **播放/暂停切换**：`POST /api/v1/player/toggle`
- **重播当前视频**：`POST /api/v1/player/replay`
- **下一集**：`POST /api/v1/player/next`
- **上一集**：`POST /api/v1/player/prev`

示例：

```bash
curl -X POST "http://192.168.1.88:18080/api/v1/player/toggle"
```

## 4.2 全屏控制

- **全屏切换**：`POST /api/v1/player/fullscreen/toggle`

## 4.3 播放模式

- **单集循环**：`POST /api/v1/player/mode/single`
- **列表循环**：`POST /api/v1/player/mode/list`

## 4.4 列表播放定位

- **按序号播放**：`POST /api/v1/playlist/play`

参数：

- `index`（必填，整数，从 0 开始）

支持三种传参方式：

1. Query：`/api/v1/playlist/play?index=2`
2. 表单：`index=2`
3. JSON：`{"index":2}`

示例：

```bash
curl -X POST "http://192.168.1.88:18080/api/v1/playlist/play" \
  -H "Content-Type: application/json" \
  -d '{"index":2}'
```

## 4.5 文件选择（需手机端人工确认）

- **弹出选择视频**：`POST /api/v1/picker/video`
- **弹出选择文件夹**：`POST /api/v1/picker/folder`

说明：这两个接口触发后，会在播放器设备上弹出系统选择器，需在设备上完成操作。

---

## 5. 状态接口（全量）

除 `GET /api/v1/state` 外，提供拆分状态接口，便于低耦合对接。

- **播放状态**：`GET /api/v1/state/playing`
  - 返回：`playing`
- **全屏状态**：`GET /api/v1/state/fullscreen`
  - 返回：`fullScreen`
- **模式状态**：`GET /api/v1/state/mode`
  - 返回：`mode`、`modeText`
- **当前播放项状态**：`GET /api/v1/state/current`
  - 返回：`currentIndex`、`currentName`、`total`
- **播放列表状态**：`GET /api/v1/state/playlist`
  - 返回：`playlist`、`currentIndex`、`total`

---

## 6. 字段定义（`/api/v1/state`）

- `playing`：`boolean`，是否正在播放
- `fullScreen`：`boolean`，是否全屏
- `currentIndex`：`number`，当前播放序号（从 0 开始，若无则可能为 -1）
- `total`：`number`，播放列表总数
- `mode`：`string`，`single` / `list`
- `modeText`：`string`，模式文案（例如“单集循环”“列表循环”）
- `currentName`：`string`，当前播放文件名
- `playlist`：数组，元素结构如下：
  - `index`：`number`，列表序号
  - `name`：`string`，文件名

---

## 7. 前端调用示例（JavaScript）

```js
const base = "http://192.168.1.88:18080";

async function apiGet(path) {
  const r = await fetch(base + path);
  return await r.json();
}

async function apiPost(path, body) {
  const r = await fetch(base + path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  return await r.json();
}

// 轮询状态
setInterval(async () => {
  const s = await apiGet("/api/v1/state");
  if (s.ok) {
    console.log("当前状态", s.data);
  }
}, 1500);

// 控制：下一集
await apiPost("/api/v1/player/next");

// 控制：按索引播放
await apiPost("/api/v1/playlist/play", { index: 1 });
```

---

## 8. 建议对接策略

- 页面初始化时先调用 `GET /api/v1/ping` 判断在线。
- UI 刷新建议轮询 `GET /api/v1/state`（1~2 秒一次）。
- 按钮点击统一调用对应 `POST` 控制接口。
- 每次控制后可延迟 100~300ms 重新拉取状态，避免界面不同步。

---

## 9. 向后兼容（旧接口）

为兼容既有客户端，以下旧接口仍可用：

- `GET /api/ping`
- `GET /api/state`
- `GET/POST /api/action?action=<action>&value=<value>`

建议新项目优先使用 `/api/v1/*`。

---

