# Vidda CleanYourTV — 备份与恢复

电视默认 IP：`192.168.0.110`（可用 `TV_HOST=` 覆盖）。原桌面包：`com.jamdeo.tv.vod`。

## 一键脚本

| 脚本 | 作用 |
|------|------|
| `tools/backup-tv.sh` | 备份 packages / settings / HOME |
| `tools/restore.sh examples/vidda-local/debloat.txt com.jamdeo.tv.vod` | 按 debloat 记录回滚净化 |
| `tools/restore-from-backup.sh` | 按 `backup/<timestamp>/` 恢复 enabled 包 + 原桌面 |
| `switch-stock.sh` | 启用原桌面（JuUI 抢回 Home） |
| `switch-clean.sh` | 停用原桌面，设回 CleanYourTV |
| `update-launcher.sh` | 重新编译并安装桌面 |
| `push-theme.sh themes/<name>` | 主题热更新（无需重编） |
| `tools/adb-closed.py 192.168.0.110` | 确认 5555 已关闭 |

## 净化

```bash
TV_HOST=192.168.0.110 tools/disable.sh examples/vidda-local/debloat.txt
```

保留投屏相关包；停用 `com.jamdeo.updateservice`（OTA）。**勿停用** `com.hisense.tv.tiletransition`。

## 收尾

电视上关闭「USB 调试 / 网络 ADB」，然后：

```bash
tools/venv/bin/python -I tools/adb-closed.py 192.168.0.110
```
