# ReMRSS

<img src="pic_res/app-icon.png" alt="ReMRSS 图标" width="96" height="96">

面向小米 17 Pro / 17 Pro Max 等双屏设备的背屏工具，通过 Shizuku 实现应用切换、截图、录屏、通知和充电动画，无需 Root。

基于 [MRSS](https://github.com/AntiOblivionis/MiRearScreenSwitcher) 继续开发，由本仓库独立维护。

## 功能

- **应用投放**：通过控制中心磁贴将应用切换到背屏，并移回主屏。
- **截图与录屏**：截取背屏画面，使用可拖动的悬浮按钮控制录制。
- **背屏通知**：选择来源应用，设置隐私、勿扰跟随、倒扣条件和显示时长。
- **充电与显示**：液体充电动画、DPI、四方向旋转、常亮和遮盖检测。
- **界面与自动化**：Miuix 界面、深浅色主题、简繁英，以及 Tasker / MacroDroid 等工具的 URI 调用。

## 下载

前往 [最新正式版](https://github.com/lhly/MiRearScreenSwitcher/releases/latest) 下载 APK；发布页同时提供 `SHA256SUMS` 和对应版本源码。

[历史版本](https://github.com/lhly/MiRearScreenSwitcher/releases) · [更新日志](CHANGELOG.md)

当前源码包含尚未发布的调整，下载版本的功能以对应 Release 说明为准。

## 快速上手

1. 在支持背屏的设备上安装 ReMRSS，安装并启动 [Shizuku](https://shizuku.rikka.app/)。
2. 打开 ReMRSS，完成 Shizuku 授权，等待控制服务连接。
3. 编辑控制中心快捷开关，添加“切换至背屏”“获取背屏截图”和“背屏录屏”。
4. 在主屏打开目标应用，点击“切换至背屏”磁贴；通知、充电动画等功能在应用内按需开启。

详细步骤、权限与后台设置见 [使用指南](docs/usage.md)。当前源码还提供应用内“使用教程”和“关于 → 权限检测”入口。

从旧 MRSS 迁移时需重新配置、授权并添加磁贴；两者可同时安装，自动化调用可指定 ReMRSS 包名 `top.zainzh.rearscreen`。

## 文档与反馈

- [使用指南](docs/usage.md)：日常操作、权限、后台运行、故障排查和自动化。
- [构建说明](docs/android-studio-release.md) · [发版说明](docs/github-release.md)
- [文档索引](docs/README.md)：迁移说明、界面与功能验收、包体积审计。
- [提交问题](https://github.com/lhly/MiRearScreenSwitcher/issues)：请附上机型、系统版本、复现步骤和诊断信息。

## 许可证与致谢

采用 [GPL-3.0](LICENSE)，贡献归属和第三方组件说明见 [NOTICE](NOTICE)。已发布版本的完整源码与构建说明可在对应 tag 中查看。

感谢原作者 **AntiOblivionis**、上游测试贡献者 **汐木泽**，以及 [Shizuku](https://github.com/RikkaApps/Shizuku) 和 [Miuix](https://github.com/compose-miuix-ui/miuix) 项目。

应用图标为[独立设计](docs/app-icon-design.md)，第三方组件和品牌标识归各自权利人所有。本项目为独立第三方工具，与小米官方无关。
