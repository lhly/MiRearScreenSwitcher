# HyperOS 4 磁贴修复的本地构建与验证

本次排查设备为 Android 17 / HyperOS 4。系统切屏接口在该设备上可用；已发现并修复 App 内多个组件销毁共享 Shizuku UserService、磁贴固定一秒重试丢失连接，以及只按命令退出码判断切屏成功的问题。

## 本机 Android 构建环境

工具与缓存安装在项目 `.local/`，没有加入版本控制：Flutter 3.35.7、Dart 3.9.2、JDK 21、Gradle 8.9、Android SDK 36、NDK 27.0.12077973。Android toolchain 与许可证检查已通过。进入项目后执行：

```sh
source .local/env.sh
flutter pub get
flutter build apk --debug --target-platform android-arm64 --build-number=2010 --build-name=3.2.0
python3 tools/test_task_service_connection.py
```

APK 输出为 `build/app/outputs/flutter-apk/app-debug.apk`。该环境文件包含本机路径；换机器时需要重新安装工具并配置 `android/local.properties`。

## 测试包与原版数据

debug 包名为 `com.tgwgroup.MiRearScreenSwitcher.debug`，名称为“MRSS 修复测试”，磁贴为“切换至背屏（修复测试）”，URI 为 `mrss-debug://`。release 仍使用原包名与 `mrss://`。

原版作者签名与本地测试包签名不同。原版使用 `pm uninstall -k --user 0 com.tgwgroup.MiRearScreenSwitcher` 保留手机上的私有数据，测试版使用独立数据目录，原设置不会自动出现在测试版中。原版 APK 备份位于 `.local/backups/original/base.apk`；私有数据没有离线导出。

如需恢复原版，使用备份 APK：

```sh
adb install -r .local/backups/original/base.apk
```

不要使用 `pm clear` 或不带 `-k` 的卸载命令清除原版保留的数据。

## 真机验证步骤

1. 解锁手机并允许 USB 安装，安装测试 APK，打开“MRSS 修复测试”并授予 Shizuku 权限。
2. 添加测试版磁贴，打开系统“设置”的普通页面。
3. 真正展开控制中心并点击测试版磁贴，核对设置任务从 display 0 移到 display 1。
4. 核对控制中心收起后 `RearScreenKeeperService` 仍能使用同一个远端服务。
5. 用测试版 URI 拉回设置任务，再重复展开控制中心和点击，验证后续切屏。
6. 定向终止测试版的 `:task_service` 进程，验证重连；不要终止全局 Shizuku server 或其他应用的 UserService。

定向日志：

```sh
adb logcat -v threadtime TaskServiceManager:I SwitchToRearTile:I TaskService:I RearScreenKeeperService:W '*:S'
```

成功切屏应出现 `stage=click`、`stage=ready`、`stage=target`、`stage=move_result ... verified=true`、`stage=finished`。`TaskService` 会再次查询任务实际所在显示屏，避免将异常 Parcel 的零退出码误判为成功。

使用真实控制中心点击。不要在面板隐藏时使用 `cmd statusbar click-tile`，该命令可能排队并在下次展开时切走另一应用。

16 项本地回归覆盖延迟连接、超时、共享连接释放、Binder 死亡、Shizuku 重启、重复点击、面板关闭以及切屏失败恢复。完整构建与这些测试已通过。

## 2026-10-11 真机验证结果

最终 APK SHA256 为 `1b37bc882eb162a85324ace7f93242eeca2f6f3d81277c6131da90df7071ea84`，已核对手机安装文件与本地成品完全一致。

- 最终 APK 两轮真实控制中心点击均把设置任务 51079 从 display 0 移到 display 1；两次测试版 URI 均成功将其拉回 display 0。
- 控制中心收起后，设置仍在背屏、Keeper 保活正常，远端 PID 11227 在重复切屏之间保持不变。
- 定向终止测试版远端 PID 11227 后立即点击，日志先记录 `binder_alive=false`，再记录 generation 3 建立新连接；磁贴等待 309ms 后执行，实际切屏验证成功。新远端 PID 为 14578，全局 Shizuku server PID 13596 未变化。
- 背屏截图确认系统“显示与亮度”页面实际显示，验证不限于命令退出码。
- 初次测试时背屏遮盖检测按产品逻辑自动拉回主屏。重复验证期间临时关闭了测试版遮盖检测，结束时已恢复默认开启。
- 结束时测试任务均位于主屏，Keeper 已停止，官方背屏 Launcher 已恢复。原版保持保留数据卸载状态，原 appId、CE/DE 数据目录 inode 与卸载前一致。

日志与状态记录保存在 `.local/diagnostics/tile-device-final.log` 和 `tile-device-states.json`，背屏截图为 `rear-cycle-final-1.png`。截图、录屏、充电和通知等其他功能未进行完整验收。
