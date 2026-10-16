# v3.3.0 原生界面迁移与验证

2026-10-13，将设置界面从 Flutter 迁移至 Java / Android View，沿用既有 Shizuku 13.1.5、TaskServiceManager、背屏服务、磁贴及 URI 控制逻辑。[v3.3.0 正式版](https://github.com/lhly/MiRearScreenSwitcher/releases/tag/v3.3.0) 已通过 tag 自动构建并发布为 Latest，公开下载成品复核通过；用户已在小米真机安装验证，反馈使用未发现问题。

## 体积与签名

| 产物 | 实测 APK 字节 | 十进制 MB |
| --- | ---: | ---: |
| 已发布个人 v3.2.1（Flutter ARM64） | 17,693,866 | 17.69 |
| 个人 v3.3.0（本机 / GitHub 正式原生包） | 581,195 | 0.58 |

减少约 **96.72%**。新版 ZIP 条目解压合计 815,853 字节，不含 Flutter assets、libflutter.so、libapp.so 或其他 native lib；这不是系统安装占用的计算口径。

API 36 模拟器中，从旧个人正式版覆盖升级后，系统存储页显示应用大小 **1.02 MB**、用户数据 **73.73 kB**、缓存 **86.02 kB**、总计 **1.18 MB**。此前用户看到的 100 多 MB 对应旧“MRSS 修复测试”debug 包；此前体积诊断见根目录 findings.md。

- 版本：`3.3.0 / 2012`；minSdk 24、targetSdk 36；不可调试。
- 包名：`com.tgwgroup.MiRearScreenSwitcher.personal`，URI：`mrss://`。
- 沿用个人正式证书 SHA256：`6479192fc1646532afc357f27d1581832bef5e6ad15e2f44501d883f04c783ac`。
- GitHub 正式 APK SHA256：`b14a0b0346dadb7124e0f86335df4c00d5149336967216710eb88fca64a0cf7d`，与公开 `SHA256SUMS`、GitHub asset digest 一致。
- 本机构建 APK SHA256：`a403f0cd87ecc03b93db6b10b9cb5df37b378acfb5fa0a847307268102d40c9a`；本机与云端包的体积、版本及证书一致，下载公开包时以 Release 附件校验文件为准。
- 本机归档：`dist/MRSS-v3.3.0-native.apk`、`dist/SHA256SUMS`，不提交二进制到 Git。

## 功能对应

| 原有功能 | 原生入口 / 保留实现 |
| --- | --- |
| Shizuku 检查、授权、服务连接与诊断 | 背屏连接卡片、关于诊断；原共享连接管理和权限 API |
| DPI 设置/恢复，四方向旋转 | 背屏显示卡片；原 TaskService 动作，等待放在后台线程 |
| 遮盖检测、两种互斥常亮 | 背屏显示与唤醒；旧偏好键和 Keeper/AlwaysWakeUp |
| 充电动画与充电常亮 | 背屏充电组；原 ChargingService/背屏动画 |
| 背屏通知总开关、授权、选择应用 | 通知页、应用选择子页；原 NotificationService |
| 应用搜索、系统筛选、刷新、批量选择 | 原生回收列表；按可见范围全选/取消，保留筛选外的已选项 |
| 隐藏标题/内容、通知深色、勿扰跟随、倒扣条件 | 通知隐私和投送规则；原服务配置 |
| 自动销毁时长 | 原生数值对话框；任意正 int，不引入自定义上限 |
| 教程与历史支持入口 | v3.3.0 关于页沿用上游链接；开发预览版本 改为维护分支项目/反馈/教程及原始贡献致谢 |
| URI 说明与重启 | 关于自动化和诊断；沿用协议与背屏任务返回动作 |
| 控制中心切屏/截图/录屏、返回通知 | 原生后台组件继续保留；截图/录屏等业务逻辑没有重写 |

后续开发构建进一步调整关于页：当前维护者仅在源码仓库栏显示，保留原作者与测试贡献致谢，移除原打赏和交流群入口，新增离线 GPL/NOTICE 阅读及对应 tag 源码入口。包名与正式签名沿用 v3.3.0，具体改动见 [README 更新日志](../README.md)。

旧 MethodChannel 的业务动作处理已改为轻量 NativeAction，同一份原生控制实现继续使用；全部旧 UI 动作仍有对应处理或原生直接替代。

## 设置兼容与设计

同签名、同包名覆盖升级时，首次导入 FlutterSharedPreferences 的 `flutter.*` 值，并继续兼容旧磁贴/URI/后台服务读取的键；mrss_settings 中应用选择集合保留。旧 Flutter 时长 long 转为服务读取的 int。迁移只执行一次，新配置同时写两套键。

不同包名独立保存数据：作者原版、个人 `.personal`、修复测试 `.debug` 不会自动跨包迁移。当前个人正式包不会覆盖“MRSS 修复测试”，首次安装仍需设置和授权。

使用 ui-ux-pro-max 的 Glassmorphism 与可访问性规则，视觉为渐变背景、半透明玻璃卡片、边缘高光、悬浮导航。材质由轻量 Canvas 模拟，未调用 HyperOS 私有液态玻璃接口。详情见 [设计规范](../design-system/mrss-native/MASTER.md)。支持简体/繁体/英文、系统深色、系统字号、安全区及系统动画关闭。

配置变化使用 Android 标准 Activity 重建，保存页面、滚动、搜索、筛选及数字输入草稿，关闭旧弹窗；大字号按钮纵排，短横屏应用选择采用左右布局。

## 验证

- 本机正式构建、完整 `lintRelease`（0 错误，保留历史警告）；apksigner、包名/版本/非调试和无 Flutter 结构检查通过。
- 9 项发布参数/凭据测试、16 项 Shizuku 连接与磁贴时序回归通过；actionlint 和构建脚本语法检查通过。
- API 36 模拟器 5 项运行测试通过：偏好迁移、两套服务偏好契约、页面/开关/重建、应用搜索/系统筛选/可见范围批量选择、数字输入草稿重建。
- 已目视检查浅色/深色、简繁英、375dp 小屏、两倍字号、800×375dp 横屏、1067dp 大屏；横屏应用列表调整后可视高度约 301dp。
- 真实 v3.2.1 APK 同签名覆盖升级成功；旧 UI 关闭的遮盖检测和充电动画，在原生正式版中仍为关闭。混淆后的正式包已检查背屏、通知、关于与 URI 对话框。
- [原生云端构建与发布 38376513962](https://github.com/lhly/MiRearScreenSwitcher/actions/runs/38376513962) 成功；公开附件重新下载后通过哈希、个人证书、包名、版本、非调试及无 Flutter/native lib 结构复核。

2026-10-13，用户确认已在小米真机安装 v3.3.0 原生正式版并完成使用验证，未发现问题。此前修复测试版的真机磁贴验证见 [历史记录](hyperos4-tile-validation.md)。

构建方式见 [Android Studio / 终端正式构建](android-studio-release.md)。运行记录与截图位于被 Git 忽略的 `.local/diagnostics/native-*`。
