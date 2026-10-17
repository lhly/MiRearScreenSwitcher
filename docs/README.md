# 文档索引

## 使用与开发

| 内容 | 文档 |
| --- | --- |
| 日常操作、权限、后台与自动化 | [使用指南](usage.md) |
| 旧 MRSS 迁移、包名与协议冲突 | [迁移说明](remrss-identity-validation.md) |
| Android Studio、终端构建与签名 | [构建说明](android-studio-release.md) |
| GitHub Actions、版本清单与发布 | [发版说明](github-release.md) |
| 各版本变化 | [更新日志](../CHANGELOG.md) |
| 当前界面开发约定 | [Miuix 设计规范](../design-system/mrss-native/MASTER.md) |

## 专项记录

以下文档保留各阶段的实现、测试证据与验收边界。历史界面和实现描述以记录时的版本为准，当前 UI 开发遵循上方设计规范。

- **界面与交互**：[原生迁移](native-ui-validation.md)、[Miuix 首页](compose-home-validation.md)、[Miuix 全面统一](miuix-cleanup-validation.md)、[全 App UI](app-ui-audit-validation.md)、[导航交互](navigation-interactions-validation.md)、[图标设计](app-icon-design.md)、[录屏浮层](recording-overlay-validation.md)。
- **权限与教程**：[权限检测](permission-checks-validation.md)、[离线教程](offline-tutorial-validation.md)、[维护与归属信息](maintainer-info-validation.md)。
- **背屏功能**：[磁贴切屏](hyperos4-tile-validation.md)、[显示方向](display-rotation-validation.md)、[充电动画](charging-animation-validation.md)、[首次内容消失诊断](first-trigger-disappearance-diagnosis.md)、[充电与通知稳定性](charging-notification-stability-validation.md)。
- **更新流程**：[关于页更新](about-updates-validation.md)、[Raw 清单](raw-update-validation.md)、[应用内更新](in-app-update-validation.md)、[下载弹窗](update-dialog-validation.md)、[镜像代理](update-proxy-validation.md)。
- **包体积**：[增长分析](apk-size-analysis.md)、[专项审计与精简](apk-size-audit.md)。

[返回项目首页](../README.md)
