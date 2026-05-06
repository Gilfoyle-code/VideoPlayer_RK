## DKVideoPlayer_RK 本地开发与编译说明（Ubuntu 22.04）

### 1. 本次已完成的环境配置

已在当前仓库内完成一套**免 sudo**、可复用的 Android 构建环境：

- **JDK 11（用于 Gradle/AGP 构建）**：
  - `./.tools/jdk-11`
- **JDK 17（用于 sdkmanager）**：
  - `./.tools/jdk-17`
- **Android SDK（命令行工具 + 构建组件）**：
  - `./.tools/android-sdk`
- **已安装 SDK 组件**：
  - `platform-tools`
  - `platforms;android-31`
  - `build-tools;33.0.0`
- **Gradle Wrapper 执行权限已修复**：
  - `./gradlew` 已可执行
- **本地 SDK 配置文件已创建**：
  - `local.properties`
  - 内容：`sdk.dir=/home/zyz/code/DKVideoPlayer_RK/.tools/android-sdk`

---

### 2. 工程总体结构（已裁切为本地播放专用）

当前工程已按“仅保留本地视频播放”目标进行裁切，Gradle 实际参与构建的模块只剩：

- **`dkplayer-java`**
  - 播放核心层（`VideoView`、`BaseVideoView`、渲染与播放内核封装）
- **`dkplayer-sample`**
  - 精简后的演示 App，仅包含一个页面：
    - 选择本地视频（`ACTION_OPEN_DOCUMENT`）
    - 播放 / 暂停控制

已从 sample 侧删除或移除依赖：

- 弹幕、列表流、PIP、缓存、Exo/Ijk 扩展演示
- 与上述功能相关的页面、资源、第三方依赖

关键构建文件：

- **根配置**：`build.gradle`、`settings.gradle`、`gradle.properties`
- **版本常量**：`constants.gradle`
- **sample 构建**：`dkplayer-sample/build.gradle`

---

### 3. 当前工程构建参数（来自源码）

- **AGP**：`7.1.2`
- **Gradle Wrapper**：`7.2`
- **Kotlin Gradle Plugin**：`1.6.21`
- **compileSdkVersion**：`31`
- **targetSdkVersion**：`31`
- **minSdkVersion**：`16`
- **buildToolsVersion**：`33.0.0`
- **Java 源兼容**：`1.8`

说明：AGP 7.1.2 推荐构建时使用 JDK 11，本机已配置可用。

---

### 4. 每次打开新终端后的推荐环境变量

在项目根目录执行：

```bash
cd /home/zyz/code/DKVideoPlayer_RK
export JAVA_HOME=/home/zyz/code/DKVideoPlayer_RK/.tools/jdk-11
0............export ANDROID_SDK_ROOT=/home/zyz/code/DKVideoPlayer_RK/.tools/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH
```

如需使用 `sdkmanager`（仅安装/更新 SDK 时）：

```bash
export JAVA_HOME=/home/zyz/code/DKVideoPlayer_RK/.tools/jdk-17
export PATH=$JAVA_HOME/bin:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH
```

---

### 5. 常用编译命令

在项目根目录执行：

- **清理工程**
  - `./gradlew clean`

- **构建 Sample Debug 包**
  - `./gradlew :dkplayer-sample:assembleDebug`

- **构建 Sample Release 包**
  - `./gradlew :dkplayer-sample:assembleRelease`

- **安装 Debug 到设备**
  - `./gradlew :dkplayer-sample:installDebug`

- **构建核心库 AAR（可选）**
  - `./gradlew :dkplayer-java:assembleRelease`

**例如（我的编译指令）：**

我的工程目录是：`/home/zyz/code/DKVideoPlayer_RK`
（以下命令均为一整行）
- 编译Debug包：`cd /home/zyz/code/DKVideoPlayer_RK && export JAVA_HOME=/home/zyz/code/DKVideoPlayer_RK/.tools/jdk-11 && export  ANDROID_SDK_ROOT=/home/zyz/code/DKVideoPlayer_RK/.tools/android-sdk && export PATH=$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH && ./gradlew :dkplayer-sample:assembleDebug --no-daemon --console=plain`

- 编译Release包：`cd /home/zyz/code/DKVideoPlayer_RK && export JAVA_HOME=/home/zyz/code/DKVideoPlayer_RK/.tools/jdk-11 && export  ANDROID_SDK_ROOT=/home/zyz/code/DKVideoPlayer_RK/.tools/android-sdk && export PATH=$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH && ./gradlew :dkplayer-sample:assembleRelease --no-daemon --console=plain`
---

### 6. 本次验证结果

已在当前环境完成以下验证：

- `./gradlew :dkplayer-sample:assembleDebug --no-daemon`：**成功**
- `./gradlew :dkplayer-sample:assembleRelease --no-daemon`：**成功**

结论：**裁切后的本地播放器版本可正常 Debug/Release 编译打包**。

---

### 7. 产物位置说明

- Sample APK 产物目录：
  - `dkplayer-sample/build/outputs/apk/`
- 你的 `dkplayer-sample/build.gradle` 中配置了自定义 APK 命名规则，文件名会包含：
  - `variant`、`versionName`、日期、`versionCode`

---

### 8. 常见问题排查

- **`java: command not found`**
  - 未设置 `JAVA_HOME`，按“第 4 节”重新导出环境变量。

- **`SDK location not found`**
  - 检查 `local.properties` 是否存在且 `sdk.dir` 路径正确。

- **`sdkmanager` 报 class version 错误**
  - 使用 JDK 17 运行 `sdkmanager`。

- **Gradle 下载依赖慢/失败**
  - 检查网络连通性，必要时配置代理或镜像源。

---

### 9. 开发入口

- 本地视频播放主流程：`dkplayer-sample/src/main/java/xyz/doikki/dkplayer/MainActivity.java`
- 播放内核与 `VideoView` 行为：`dkplayer-java`
- UI 调整入口：`dkplayer-sample/src/main/res/layout/activity_main.xml`

---

### 10. 备注（安全与发布）

- 目前 `gradle.properties` 中存在签名相关明文示例字段（`KEYSTORE_PWD`、`KEY_PWD` 等）。
- 若仓库用于长期公开协作，建议改为：
  - 本地 `local.properties` 或环境变量注入；
  - 避免在版本库中保存真实密钥信息。

---

### 11. 局域网控制 API 文档

- `LAN_CONTROL_API.md`
- 所有控制按钮对应接口、全部状态接口、请求/响应示例、错误码、跨域说明、兼容旧接口说明。
