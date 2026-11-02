# Root 权限来源

首页连接状态卡提供 Shizuku / Root 二选一，默认仍为 Shizuku。选择 Root 后点击“授权并连接”，在 KernelSU-Next 等 root 管理器中授权当前安装包。Root 路径通过 libsu 6.0.0 启动独立服务，无需 Shizuku 或 Sui。

正式版、普通调试版与 Root 验证版的包名不同，必须分别授权。Root 选择会保存；已成功授权的模式在应用下次启动时尝试连接。状态刷新和磁贴监听只读取状态、复用已有连接，不主动循环申请 root。授权或连接失败后由用户重试，也可以手动选择 Shizuku。

## 连接与会话

`TaskServiceManager` 继续为页面、磁贴、自动化及后台功能共享同一条连接；`ShizukuBackend` 和 `RootBackend` 分别处理绑定。首次 root 授权等待与服务绑定超时分开，异常重连次数有限，旧连接回调通过 generation 失效。

投屏、录屏控制或截图等命令进行期间拒绝切换权限来源。空闲切换先检查远端会话和背屏占用，再解绑旧服务、保存选择并连接新服务。检查失败或超时会取消切换；不会自动改用另一种权限。

普通应用保持 Android 分配的 UID。只有 `RootTaskService` 运行在 UID 0，其 `ITaskService` Binder 核对本安装包的实际 UID，拒绝其他应用调用。普通通知权限、通知使用权和悬浮窗权限仍按 Android 的授权流程处理；Root Binder 不提供给声明式模块。

Root 容器销毁时清理虚拟屏和输入会话。冻结保护 helper 统一以 shell UID 2000 运行，继续使用同一恢复目录和独占锁，目录权限为 0700、记录为 0600。历史 root 属主记录的迁移必须持有同一锁；不放宽目录权限、不删除活动恢复记录。录屏停止前同时核对进程名称和输出路径，避免陈旧 PID 误伤其他进程。

## 验证方式

独立验证包通过 `-PmrssRootValidation=true` 构建，包名为 `top.zainzh.rearscreen.rootdebug`，协议为 `mrss-root-debug://`。加入 `-PmrssRootValidationMinify=true` 后启用 R8、资源收缩并关闭 debuggable，适合验证优化后的实际 IPC。

普通验证包的 `RootBackendDeviceTest` 覆盖真实远端 UID、Binder 代理、截图文件描述符、不同 UID 调用拒绝、物理/虚拟投屏、输入和任务返回。`RootPermissionUiTest` 在无 root 的模拟器上验证来源选择、页面重建及权限页，无需申请 root。

混淆包使用仅在 debug 源集注册的 `RootIpcProbeInstrumentation` 自仪器入口，验证真实 Root IPC、不同 UID 拒绝调用、PNG 文件描述符、视频轨道、受控停止、物理/虚拟投屏与输入恢复。安装独立验证包与普通验证构建的配套测试 APK，授权独立验证包并解锁手机后，可在项目根目录运行：

```sh
python3 tools/test_release_contract.py /path/to/ReMRSS-rootdebug-r8.apk \
  --serial DEVICE_SERIAL --root-ipc-package top.zainzh.rearscreen.rootdebug
```

工具先核对 AIDL 与反射入口，再校验手机上 APK 的 SHA256 与输入文件相同，最后运行真正的 App → Root 服务测试。单纯在同一进程内构造 `TaskService` 的契约检查不算 Root 跨进程验证。所有探针和测试宿主均不进入正式 release。

在 Shizuku 中预先允许独立验证包后，可增加 `--root-switch`，继续验证切换至 Shizuku 后的录屏、投屏和触摸，并切回 Root。该检查不会代替用户授予 Shizuku 权限。

## 当前验收范围

2026-11-02 在小米 17 Pro Max、Android 17 / HyperOS 4、KernelSU-Next 临时 root 环境中，普通独立验证包已通过：应用自主连接 UID 0 服务、真实 Binder/截图 FD、不同 UID 拒绝调用、物理任务往返、虚拟屏与输入代理、虚拟触摸、活动投屏拒绝切换及 shell 属主恢复目录检查。

主机连接管理 28 项用例及冻结恢复、显示识别、旋转、命令参数边界回归通过。首页/权限页在浅色、深色、1.6 倍字号和真实横屏四种配置下共 8 项 UI 用例通过；debug 与正式 release 构建、release 反射和 AIDL 契约检查通过。

2026-11-03 同一手机上，关闭 debuggable、启用 R8 和资源收缩的独立验证包也已通过完整专用探针：Root 真实 Binder/截图 FD、不同 UID 拒绝调用、录屏视频轨道与受控停止、物理/虚拟投屏、输入及任务恢复。随后切至 Shizuku，重复录屏、物理/虚拟投屏和触摸，再切回 Root，全部通过。此次 Shizuku 服务本身运行在 UID 0；权限来源仍分别走 Shizuku 与 libsu 的独立绑定路径。恢复目录继续为 shell UID 2000、0700。

本轮录屏验证覆盖编码视频轨道和启停命令。录屏悬浮按钮、相册浏览、通知/充电/常亮长期运行、Root 撤权后的管理器行为、锁屏/AOD 完整组合及其他 root 管理器仍需专项验证。临时 root 失效不会自动改变权限来源；撤销授权是否立即结束既有服务由管理器决定。

本轮不包含手机重启、正式包覆盖安装或发布。

[使用指南](usage.md) · [构建说明](android-studio-release.md)
