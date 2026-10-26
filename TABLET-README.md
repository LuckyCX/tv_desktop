# 平板 T33-F11 改造记录与恢复指引

> 设备：`T33-F11` · Android 14 · 序列号 `KSDSF11260207066`  
> 操作日期：2026-10-26  
> 本机 ADB：`/opt/homebrew/share/android-commandlinetools/platform-tools/adb`  
> **以后要恢复时：把本文发给 AI，并说明要恢复哪一节即可。**

---

## 一、做了什么（摘要）

1. **安装第三方桌面 Lawnchair 14 Beta 3**（包名 `app.lawnchair`）
2. **把默认 Home 改成 Lawnchair**（开机 / 按 Home 都进 Lawnchair）
3. **保留原定制桌面「学习平台」**（`com.jxw.launcher`），未卸载，可手动切回
4. **限制 / 停用一批会后台保活的应用**（见第三节），减轻「应用正在保持活跃状态」提示

---

## 二、桌面相关

### 当前状态

| 角色 | 应用 | 包名 / 组件 |
|------|------|-------------|
| **默认 Home（当前）** | Lawnchair | `app.lawnchair` / `app.lawnchair/.LawnchairLauncher` |
| 原定制桌面（保留） | 学习平台 | `com.jxw.launcher` / `com.jxw.launcher/.LaunchPageActivity` |
| 系统自带（未动） | Launcher3 Quickstep | `com.android.launcher3` |
| 其它桌面（未动） | AI伴学 | `com.xiaoyeoo.launcher` |

### 切回「学习平台」作默认桌面

```bash
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"
adb devices   # 确认平板已连接

adb shell cmd role add-role-holder android.app.role.HOME com.jxw.launcher
adb shell cmd package set-home-activity --user 0 com.jxw.launcher/.LaunchPageActivity
adb shell input keyevent KEYCODE_HOME
```

也可在平板上：**设置 → 应用 → 默认应用 → 主屏幕应用 → 学习平台**。

### 再切回 Lawnchair

```bash
adb shell cmd role add-role-holder android.app.role.HOME app.lawnchair
adb shell cmd package set-home-activity --user 0 app.lawnchair/.LawnchairLauncher
adb shell input keyevent KEYCODE_HOME
```

### 卸载 Lawnchair（可选，会先建议切回学习平台）

```bash
# 先把 Home 切回学习平台，再卸载
adb shell cmd role add-role-holder android.app.role.HOME com.jxw.launcher
adb uninstall app.lawnchair
```

Lawnchair 安装来源（如需重装）：

```text
https://github.com/LawnchairLauncher/lawnchair/releases/download/v14.0.0-beta3/Lawnchair.14.0.0.Beta.3.apk
```

```bash
adb install -r Lawnchair.14.0.0.Beta.3.apk
adb shell cmd role add-role-holder android.app.role.HOME app.lawnchair
```

---

## 三、后台 / 自启动处理明细

### 3.1 整包用户停用（`pm disable-user`）

这些包 **enabled=3（disabled-user）**，不会再自启/保活；**未卸载**，可用 `pm enable` 恢复。

| 显示名 | 包名 | 操作 |
|--------|------|------|
| 应用商店 | `com.jxw.JXWAppMarket` | `pm disable-user` |
| 应用商店（另一套） | `com.jxw.market` | `pm disable-user` |
| 学情分析 | `com.jxw.maidain` | `pm disable-user` |
| 远程控制（额外，常拉活其它应用） | `com.jxw.pushservice` | `pm disable-user`；并从 Doze 白名单移除 |

### 3.2 仅限制后台（未停用整包）

**学习平台** `com.jxw.launcher`：仍可手动打开 / 切回桌面，但后台能力被压住。

| AppOps / 策略 | 当前值 |
|---------------|--------|
| `RUN_IN_BACKGROUND` | `ignore` |
| `RUN_ANY_IN_BACKGROUND` | `ignore` |
| `WAKE_LOCK` | `ignore` |
| `START_FOREGROUND` | `ignore` |
| 待机桶 standby-bucket | `restricted`（30） |
| 后台限制级别 | `hibernation` |
| 整包状态 | **仍启用**（`enabled=0`），仅 force-stop + 限制 |

对上述停用包，同样也写过相同的 AppOps `ignore`（恢复启用后若仍想限后台，可保留；完全恢复见第四节）。

### 3.3 未改动的说明

- **没有卸载**任何上述应用  
- **没有** `pm disable-user`「学习平台」整包（为保留切回桌面）  
- 系统 `com.android.launcher3`、`com.xiaoyeoo.launcher`（AI伴学）未动  
- 组件级 `pm disable-user <组件>` 被 ROM 拒绝（SecurityException），因此对商店/学情等改用**整包停用**

---

## 四、如何恢复（按需执行）

先接上平板 USB 调试：

```bash
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"
adb devices
```

### 4.1 恢复「应用商店」

```bash
adb shell pm enable com.jxw.JXWAppMarket
adb shell pm enable com.jxw.market
# 可选：恢复默认后台权限
adb shell cmd appops set com.jxw.JXWAppMarket RUN_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.JXWAppMarket RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.JXWAppMarket WAKE_LOCK allow
adb shell cmd appops set com.jxw.JXWAppMarket START_FOREGROUND allow
adb shell cmd appops set com.jxw.market RUN_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.market RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.market WAKE_LOCK allow
adb shell cmd appops set com.jxw.market START_FOREGROUND allow
```

### 4.2 恢复「学情分析」

```bash
adb shell pm enable com.jxw.maidain
adb shell cmd appops set com.jxw.maidain RUN_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.maidain RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.maidain WAKE_LOCK allow
adb shell cmd appops set com.jxw.maidain START_FOREGROUND allow
```

### 4.3 恢复「远程控制」推送（`com.jxw.pushservice`）

```bash
adb shell pm enable com.jxw.pushservice
adb shell cmd deviceidle whitelist +com.jxw.pushservice   # 若需要重新进白名单
adb shell cmd appops set com.jxw.pushservice RUN_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.pushservice RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.pushservice WAKE_LOCK allow
adb shell cmd appops set com.jxw.pushservice START_FOREGROUND allow
```

### 4.4 恢复「学习平台」后台能力（不改默认桌面）

```bash
adb shell cmd appops set com.jxw.launcher RUN_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.launcher RUN_ANY_IN_BACKGROUND allow
adb shell cmd appops set com.jxw.launcher WAKE_LOCK allow
adb shell cmd appops set com.jxw.launcher START_FOREGROUND allow
adb shell cmd activity set-bg-restriction-level --user 0 com.jxw.launcher unrestricted
adb shell cmd activity set-standby-bucket --user 0 com.jxw.launcher active
adb shell cmd app_hibernation set-state --user 0 com.jxw.launcher false
```

### 4.5 一键恢复「全部后台相关」+ 默认桌面改回学习平台

```bash
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

# 重新启用停用包
adb shell pm enable com.jxw.JXWAppMarket
adb shell pm enable com.jxw.market
adb shell pm enable com.jxw.maidain
adb shell pm enable com.jxw.pushservice

# 恢复 AppOps（学习平台 + 商店 + 学情 + 推送）
for pkg in com.jxw.launcher com.jxw.JXWAppMarket com.jxw.market com.jxw.maidain com.jxw.pushservice; do
  adb shell cmd appops set "$pkg" RUN_IN_BACKGROUND allow
  adb shell cmd appops set "$pkg" RUN_ANY_IN_BACKGROUND allow
  adb shell cmd appops set "$pkg" WAKE_LOCK allow
  adb shell cmd appops set "$pkg" START_FOREGROUND allow
done

adb shell cmd activity set-bg-restriction-level --user 0 com.jxw.launcher unrestricted
adb shell cmd activity set-standby-bucket --user 0 com.jxw.launcher active
adb shell cmd app_hibernation set-state --user 0 com.jxw.launcher false
adb shell cmd deviceidle whitelist +com.jxw.pushservice

# 默认桌面改回学习平台
adb shell cmd role add-role-holder android.app.role.HOME com.jxw.launcher
adb shell cmd package set-home-activity --user 0 com.jxw.launcher/.LaunchPageActivity
adb shell input keyevent KEYCODE_HOME
```

> 若还要卸掉 Lawnchair，在确认 Home 已是学习平台后执行：`adb uninstall app.lawnchair`

### 4.6 给 AI 的恢复口令示例

直接说例如：

- 「按 `TABLET-README.md` **4.1** 恢复应用商店」  
- 「按 **4.2** 恢复学情分析」  
- 「按 **4.5** 全部恢复并切回学习平台」  
- 「只恢复后台，桌面仍用 Lawnchair」→ 执行 4.1～4.4，**不要**跑 4.5 里改 Home 的命令  

---

## 五、状态自检命令

```bash
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"

echo "HOME:"; adb shell cmd role get-role-holders android.app.role.HOME

for pkg in com.jxw.launcher com.jxw.JXWAppMarket com.jxw.market com.jxw.maidain com.jxw.pushservice app.lawnchair; do
  echo "==== $pkg ===="
  adb shell dumpsys package "$pkg" | grep -m1 "User 0:.*enabled="
  adb shell cmd appops get "$pkg" RUN_IN_BACKGROUND
  adb shell cmd appops get "$pkg" RUN_ANY_IN_BACKGROUND
done
```

说明：`enabled=0` 启用；`enabled=3` 为用户停用（disabled-user）。

---

## 六、相关包名速查

| 中文名 | 包名 |
|--------|------|
| 学习平台 | `com.jxw.launcher` |
| 应用商店 | `com.jxw.JXWAppMarket`、`com.jxw.market` |
| 学情分析 | `com.jxw.maidain` |
| 远程控制 | `com.jxw.pushservice` |
| Lawnchair | `app.lawnchair` |
| AI伴学 | `com.xiaoyeoo.launcher`（未改） |
