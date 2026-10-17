# 项目开发约定

- 所有解释和回复使用简体中文；标识符、命令和报错保留原文。
- 多步骤任务遵循 planning-with-files-zh，维护项目 task_plan.md、findings.md、progress.md。

## UI 开发

- 设置界面唯一方案为 Kotlin / Jetpack Compose / Miuix，当前规范见 [设计规范](design-system/mrss-native/MASTER.md)。
- 开发新增界面前先读取该规范并复用 MiuixComponents.kt 的共享主题和组件。
- GlassUi 已删除，不得恢复旧 Java View/Canvas 设置渲染或另建同类工具类。业务控制代码可以继续用 Java。
- 历史验收文档中的旧样式不代表当前规范；通用设计技能不得覆盖用户指定的 Miuix 方向。
- UI 验收必须与首页对照风格一致性，同时验证浅色/深色、大字号、横屏和返回/重建状态。
- 服务悬浮控件可以保留原生 WindowManager，但须沿用 ReMRSS 中性主题和语义配色、随深浅色更新；录屏悬浮层不再使用旧粉色渐变。
- Compose、Activity 宿主、背屏通知及浮层共用 theme_colors.xml 深浅色资源；背屏通知保留用户纯黑模式，充电绿色、录屏红色和品牌蓝色图标为明确的语义/品牌例外。
- 系统磁贴使用单色线性矢量；禁止新建重复密度旧位图或恢复粉紫通知背景、玻璃闪电绘制。
