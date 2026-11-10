# 开机自启（MIUI 上的实际表现与解决办法）

2026-11-11 真机（MI 8 / MIUI Android 10）排查记录。

## 症状

App 内「开机自启」已打开（`auto_start_on_boot=true`），重启后 App 不启动、服务不存在。

## 查到的两层原因

### 1. 应用层：开机启动的是 Activity（已修）

`BootReceiver` 原来做的是 `startActivity(DiPlayActivity)`。Android 10 起后台启动 Activity 会被拒，
所以即使广播送到了也什么都起不来：

```
ActivityTaskManager: START u0 {flg=0x34000000 cmp=…/DiPlayActivity}   ← 调用被记录
（界面未出现，服务也没起来）
```

修复：开机改为 `startForegroundService(DiPlaySessionService)`，服务起来后照常拉起 Web 服务和
断电待机逻辑，不需要界面。决策过程写进 `files/logs/standby.log`：

```
开机自启：已启动 DiPlaySessionService
开机自启：设置未开启，忽略
开机自启：service 启动失败（…）
```

### 2. 系统层：MIUI 不在开机时派发 `BOOT_COMPLETED`

实测（重启后立即检查）：

| 检查 | 结果 |
|---|---|
| `dumpsys package` 里的接收器注册 | 正常（`BOOT_COMPLETED` + `ACL_CONNECTED`） |
| `cmd package query-receivers -a BOOT_COMPLETED` | 能解析到 `…/BootReceiver` |
| 开机后 `am_proc_start` 里有我们 | 没有 |
| `am_broadcast_discard_app` 里有我们 | 没有（不是被丢，是根本没派） |
| 手动 `am broadcast -a BOOT_COMPLETED -p <pkg>`（root） | **立刻起来**，且日志正确 |

即使把 MIUI 设置 → 应用管理 → 自启动里 WheelPlay 打开，冷启动仍然不派发；MIUI 会在 App 被
手动打开后**补发**（日志里能看到开机几分钟后才出现的 `开机自启：已启动 DiPlaySessionService`）。
结论：不能依赖 `BOOT_COMPLETED`。

## 解决办法：Magisk 启动脚本

设备已 Magisk root，用 `service.d` 脚本在开机完成后自己启动服务。

```sh
# /data/adb/service.d/wheelplay-boot.sh （权限 755）
#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done

attempt=1
while [ "$attempt" -le 6 ]; do
  sleep 10
  out="$(am start-foreground-service --user 0 -n <包名>/com.shilapi.xcertplay.DiPlaySessionService 2>&1)"
  case "$out" in
    *Error*|*Exception*|*"does not exist"*) : ;;
    *) exit 0 ;;
  esac
  attempt=$((attempt + 1))
done
```

安装：

```bash
adb push wheelplay-boot.sh /data/local/tmp/
adb shell su -c 'cp /data/local/tmp/wheelplay-boot.sh /data/adb/service.d/ && chmod 755 /data/adb/service.d/wheelplay-boot.sh'
```

取消：删除该文件并重启。

## 验证

```bash
adb reboot
# 等 sys.boot_completed=1 后约 10-30 秒：
adb shell pidof <包名>                      # 有 pid
adb shell dumpsys activity services <包名>  # 有 ServiceRecord
adb shell su -c 'tail -3 /data/data/<包名>/files/logs/standby.log'
# 期望：服务被启动（action=null）：plugged=… ，开始工作
```

2026-11-11 实测：重启 81 秒完成，脚本触发后进程与服务都在，日志时为 `07:58:00`。
