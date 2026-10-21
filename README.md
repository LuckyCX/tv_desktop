# tv_desktop

基于 [CleanYourTV](https://github.com/gathin/cleanyourtv) 的 Android TV 简洁桌面增强版，并附带 **局域网管理后台**（Vue3 + Element Plus + Node Express）。

本仓库适合在同一局域网内：编译/安装桌面 APK、用手填 IP:端口推送普通 APK、维护桌面 OTA 更新包。

> 原版功能与机型说明见 [README.zh-CN.md](README.zh-CN.md) · [README.en.md](README.en.md)。

---

## 目录结构

| 路径 | 说明 |
|------|------|
| `launcher/` | Android TV 桌面（Java），含设置中心、Dock、默认打开、本地备份、HTTP 推送服务 |
| `admin/` | PC 局域网管理后台：连接电视、远程推送 APK、桌面 OTA 包 |
| `tools/` | 原版清理/主题/ADB 等脚本 |
| `docs/` `examples/` `themes/` | 文档、机型样例、主题 |

---

## 环境要求

### 通用

- macOS / Linux / Windows（推荐 macOS 或 Linux）
- Git
- 与电视同一局域网

### 编译桌面 APK（`launcher/`）

| 依赖 | 建议版本 |
|------|----------|
| JDK | **17**（Android Gradle Plugin 8.x 要求） |
| Android SDK | API **34**（`compileSdk 34` / `targetSdk 34`），`minSdk 26` |
| Android SDK Build-Tools | 34.x |
| Gradle | 使用仓库自带 Wrapper（`launcher/gradlew`），无需全局安装 |

可选：安装 [Android Studio](https://developer.android.com/studio)，用 SDK Manager 装好 Platform 34 与 Build-Tools。

### 管理后台（`admin/`）

| 依赖 | 建议版本 |
|------|----------|
| Node.js | **18+**（推荐 20 LTS） |
| npm | 随 Node 自带（本仓库用 npm workspaces） |

---

## 一、初始化：桌面（launcher）

### 1. 配置 SDK 路径

在 `launcher/` 下创建本地文件（**不要提交**）：

```bash
cd launcher
echo "sdk.dir=/你的路径/Android/sdk" > local.properties
```

macOS 常见路径示例：

```text
sdk.dir=/Users/你的用户名/Library/Android/sdk
```

也可用环境变量：

```bash
export ANDROID_HOME=/Users/你的用户名/Library/Android/sdk
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

### 2. 编译 Release APK

```bash
cd launcher
./gradlew :app:assembleRelease
```

产物：

```text
launcher/app/build/outputs/apk/release/app-release.apk
```

### 3. 安装到电视（需已授权 ADB）

```bash
# 网络 ADB 示例（端口因机型而异，常见 5555）
adb connect 电视IP:5555
adb install -r app/build/outputs/apk/release/app-release.apk
```

也可使用仓库脚本（需本机已装 `adb` 且电视已授权）：

```bash
cd launcher
./build-and-install.sh
```

安装后在系统「默认桌面」中选择本应用；海信 Vidda 等机型需先 **启用原桌面包**，才能在设置里切回原桌面。

---

## 二、初始化：管理后台（admin）

### 1. 安装依赖

```bash
cd admin
npm install
```

### 2. 开发模式（推荐）

```bash
npm run dev
```

- 后端：`http://0.0.0.0:8787`
- 前端：`http://localhost:5173`（`/api`、`/ota` 已代理到后端）

局域网内其它设备可访问电脑的 `5173` / `8787`（注意防火墙放行）。

### 3. 仅后端 / 仅前端

```bash
npm run server   # Express :8787
npm run client   # Vite :5173
```

### 4. 生产托管（可选）

```bash
npm run build    # 构建 web 到 dist
npm start        # 由 Node 在 8787 托管前端 + API
```

### 5. 使用流程

1. 电视打开本桌面 → 右上角 **设置** → 查看 **局域网推送** 显示的 `IP:端口`
2. 电脑打开管理后台 → **连接**页手填该 IP 与端口 → 测通 `/api/info`
3. **远程推送**：拖拽/选择 APK，查看进度；失败可重试；列表可安装/删包/卸载
4. **桌面更新包**：上传 launcher APK，复制类似  
   `http://电脑局域网IP:8787/ota/launcher.apk`  
   粘贴到电视设置里的「更新地址」→ 保存 → 立即更新

> 无登录、无配对码、无 UDP 发现；请仅在可信局域网使用。

---

## 三、桌面端本期能力摘要

- **设置中心**：系统设置 / 切换桌面 / 更新 URL（缓存、清空、下载安装+重试）
- **Apple TV 风格 Dock**：左右切换；下键进全量应用；长按上屏 / 下屏 / 隐藏；「已隐藏」可恢复
- **默认打开**：默认开启；目标包已卸载则跳过并清除配置
- **本地备份**：仅本机 `files/backups/`（设置 + 壁纸），按时间倒序恢复；缺包不显示坏图标
- **HTTP 推送**：随机端口 `10000–60000`；`GET /api/info`、`POST /api/push`、推送记录页

---

## 四、常见问题

### `sdk.dir` / SDK 找不到

确认 `launcher/local.properties` 中路径正确，且已安装 `platforms;android-34`。

### Gradle 下载慢或失败

可配置国内镜像（阿里云等）到 `~/.gradle/init.gradle` 或项目 `gradle.properties`。

### 电视连不上管理后台

- 手机/电脑与电视同一网段
- 电视设置页端口是否已显示（推送服务启动后才有）
- 防火墙是否放行电视端口与电脑 `8787`/`5173`
- 浏览器访问 `http://电视IP:端口/api/info` 应返回 JSON

### ADB `unauthorized`

需在电视上点允许调试；部分机型无弹窗时需厂商工具或本机授权流程，与管理后台 HTTP 推送无关（推送不依赖 ADB）。

### `npm install` 失败

升级 Node 到 18+，删除 `admin/node_modules` 与 `package-lock.json` 后重试；也可换淘宝/npmmirror 源。

---

## 五、Git 首次提交（本仓库）

若从零推送到 GitHub：

```bash
cd /path/to/tvdesktop
git init
git add .
git commit -m "Initial commit: CleanYourTV launcher + LAN admin"
git branch -M main
git remote add origin https://github.com/LuckyCX/tv_desktop.git
git push -u origin main
```

不要把 `local.properties`、`node_modules/`、`.sdk/`、`*.apk` 提交进仓库（见 `.gitignore`）。

---

## License

沿用上游 CleanYourTV 的 [LICENSE](LICENSE)。管理后台与桌面增强部分同属本仓库，仅供局域网内使用，请勿暴露到公网。
