# Compose + Miuix 首页试点验证

> 历史验收记录：当前设置界面已统一迁移至 Miuix，旧 View 样式和首页试点范围不再作为开发规范。以 [当前设计规范](../design-system/mrss-native/MASTER.md) 为准。

2026-10-13，按用户要求只做本地首页/导航改造，版本仍为开发预览构建。未提交、推送、创建tag或发布Release。

## 实现与功能

- 使用Compose + Miuix 0.9.4的卡片、Switch、按钮和导航容器；灰白分组、连续圆角、轻阴影、简洁图标和浮动导航。
- Android13+导航采样真实内容并模糊、叠加柔光着色；Android7–12实体材质降级。核心与shader代码分开，manifest仅对blur依赖覆盖minSdk；APK最低API24、target36。
- Shizuku连接/详情、DPI/恢复、四方向、接近检测、常亮互斥、充电开关和磁贴教程入口全部保留。Java继续处理既有动作、异步失败回滚与偏好镜像；通知、关于和应用选择保留原实现。
- ComponentActivity管理Compose生命周期与返回分发；保存首页列表位置、其他页面状态及数字草稿，系统字体/主题改变仍可重建。
- Miuix Apache-2.0、MaterialKolor MIT完整许可进入APK并可在关于页离线阅读；应用继续采用GPL-3.0。

## APK体积

| 正式构建 | 字节 | 十进制MB |
| --- | ---: | ---: |
| 改造前 | 596,282 | 0.596 |
| 改造后 | 1,635,222 | 1.635 |
| 增量 | 1,038,940 | 1.039 |

增加174.24%，约为原包2.74倍。两者均为同个人正式签名、同包名/版本、开启R8和资源压缩的通用APK；编译工具因Miuix要求从AGP8.7.3/SDK36升级至AGP9.4.1/SDK37，因此包含工具升级的影响。这是APK文件体积，不是安装占用。

APK仍没有Flutter引擎；Compose引入的AndroidX图形路径库包含arm64-v8a、armeabi-v7a、x86、x86_64小型native库。

最终SHA256：`0025b9996b0d2ac591eed0853274df73c0e059f7733a73bf120b928c2c963d05`。

## 验证与边界

- debug、androidTest、压缩release均构建成功；完整lintRelease零错误，69项警告。
- API36模拟器最终12项运行测试通过；API28实体降级路径12项通过，并运行压缩正式包。最后对375dp按钮密度、安全区裁剪与阴影做视觉微调后，API36再次12项通过。
- 测试覆盖：首页开关/两套偏好持久化、常亮互斥、滚动与重建、Shizuku不可用时旋转不提交、首页DPI草稿；旧配置迁移、原有隐私开关、应用搜索/筛选/可见范围选择、通知时长草稿、关于链接及完整离线许可。
- 14项发布检查、16项服务连接回归通过；actionlint、git diff --check通过。签名、开发预览构建、非调试、API24/target36及完整法律资产复核通过。
- 最终正式包已目视检查：375dp浅/深色、两倍字号、800×375dp英文横屏、800dp大屏；测试后恢复模拟器尺寸、字号、主题、语言和动画设置，关闭本轮创建的额外API28模拟器。
- 目前是社区组件基础上的HyperOS风格试点；未在小米真机验收这次材质/触感/性能。模拟器没有Shizuku和背屏，实际连接与背屏硬件操作需要后续真机检查；本轮保留原动作契约并运行已有连接回归。

截图、体积基线、构建与测试日志保存在被Git忽略的`.local/diagnostics/compose-home/`；最终APK为该目录`after-release.apk`，三栏效果图为`home-comparison.png`，浅深色开关图为`switches-themes.png`。构建说明见[Android Studio/终端构建](android-studio-release.md)。
