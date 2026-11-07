# 项目开发约定

- 所有解释和回复使用简体中文；标识符、命令和报错保留原文。
- 多步骤任务遵循 planning-with-files-zh，规划、发现与进度仅保存在已忽略的 `.planning/<任务目录>/`，通过 `.planning/.active_plan` 选择当前计划。
- 不在项目根目录生成 `task_plan.md`、`findings.md`、`progress.md`；这些本地过程记录不得加入 Git，包括强制添加。公开用户文档只保留已发布功能的使用、配置、兼容与排障说明。
- 提交前运行 `python3 tools/check_private_plans.py`，确认暂存区不包含本地规划目录或开发过程文档。

## 文档目录与公开范围

- `docs/`：公开的用户指南，只写软件使用、配置、迁移、更新、兼容范围与可操作的故障排查；入口为 `docs/README.md`。不得放入开发设计草稿、任务追踪、调试记录、测试命令/日志、性能测量原稿、验收流水账或未上线实验方案。
- `dev-docs/`：仅保存在本机的开发文档，包括设计草稿、实现追踪、调试诊断、测试/验收记录、性能分析、维护与发版操作记录。整目录由 `.gitignore` 排除，不得提交、强制添加或放入 Release 附件。
- `.planning/<任务目录>/`：继续保存 `task_plan.md`、`findings.md`、`progress.md`，通过 `.planning/.active_plan` 选择；不要用公开文档代替任务记录。
- `BUILDING.md`、`LICENSE`、`NOTICE` 与 `design-system/` 保留必要的源码构建、许可证、归属和现行开发规范，不追加某轮开发或验收过程。SDK 正式对外文档随对应已发布接口维护在 SDK 自身目录；未定稿接口设计放 `dev-docs/`。
- 混合文档先将原稿移至 `dev-docs/`，再把用户需要的操作步骤、参数含义、适用条件及已知限制整理进 `docs/`；不能因文件名带 `validation` 就丢掉有效指南，也不能把内部记录换个公开目录继续提交。
- 移动、改名或删除公开文档时，同步更新 README、文档索引、源码构建说明及发布模板引用。旧 tag 中的版本文档保持原状，普通整理不改写 Git 历史。
- 提交前运行 `python3 tools/check_private_plans.py`；`.gitignore` 只负责排除未跟踪内容，已入库的过程文档仍需通过普通清理提交停止跟踪。新克隆可执行 `git config --local core.hooksPath .githooks` 启用同一提交检查。

## UI 开发

- 设置界面唯一方案为 Kotlin / Jetpack Compose / Miuix，当前规范见 [设计规范](design-system/mrss-native/MASTER.md)。
- 开发新增界面前先读取该规范并复用 MiuixComponents.kt 的共享主题和组件。
- GlassUi 已删除，不得恢复旧 Java View/Canvas 设置渲染或另建同类工具类。业务控制代码可以继续用 Java。
- 历史验收文档中的旧样式不代表当前规范；通用设计技能不得覆盖用户指定的 Miuix 方向。
- UI 验收必须与首页对照风格一致性，同时验证浅色/深色、大字号、横屏和返回/重建状态。
- 服务悬浮控件可以保留原生 WindowManager，但须沿用 ReMRSS 中性主题和语义配色、随深浅色更新；录屏悬浮层不再使用旧粉色渐变。
- Compose、Activity 宿主、背屏通知及浮层共用 theme_colors.xml 深浅色资源；背屏通知保留用户纯黑模式，充电绿色、录屏红色和品牌蓝色图标为明确的语义/品牌例外。
- 系统磁贴使用单色线性矢量；禁止新建重复密度旧位图或恢复粉紫通知背景、玻璃闪电绘制。
