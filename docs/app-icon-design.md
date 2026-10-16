# MRSS图标设计与验收

2026-10-16重新设计。此文保留开发过程的本地验收，图标随v3.3.2正式发布。

## 设计

蓝色渐变底与App的蓝白界面统一，浅色玻璃模块保留双摄和背屏的识别特征，屏内双向箭头表达切屏。图标不带文字或维护版标记；关于页使用同一标识。

![MRSS图标](../pic_res/app-icon.png)

这是本项目的独立图标设计，不是小米官方图标。

## 资源与维护

- 源头：`tools/generate_launcher_icons.py`，只依赖Python标准库。运行`python3 tools/generate_launcher_icons.py`可确定性生成Android矢量资源与`pic_res/app-icon.svg`。
- Android8+：`mipmap-anydpi-v26/ic_launcher.xml`引用独立的背景、前景、单色层。108dp画布，主体在中央66dp安全区，系统决定桌面外形。依据[Android官方自适应图标规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。
- Android7：`mipmap-anydpi/ic_launcher.xml`引用圆角矢量`drawable/ic_app_logo.xml`；关于页也使用这份资源。
- 主题图标：`drawable/ic_launcher_monochrome.xml`使用同一轮廓的实线版本，由支持该功能的桌面着色。
- 状态栏：四处服务通知统一引用24dp的`drawable/ic_stat_mrss.xml`，使用透明底单色轮廓。
- 已移除十份旧的密度PNG及白底颜色资源，不引入新依赖。历史`pic_res/logo*.png`保留原样。
- `pic_res/app-icon.png`为真实Android编译资源的512px导出，用于仓库预览。修改几何后应从Android重新导出这份PNG；APK使用矢量，不引用它。

## 验证

- Debug、androidTest、Release构建成功；`lintRelease`为0错误、63项既有警告。与修改前相比消除了5项启动图标形状警告和1项单色层缺失警告。
- 在本项目MRSS_Native_API36模拟器（端口5556）运行临时QA导出器，确认系统读取到`AdaptiveIconDrawable`及非空单色层，再由Android Canvas直接绘制编译资源。QA导出器不加入产品或已提交测试代码。
- 目视通过：圆形、圆角方形、连续圆角示例，32/48/72px尺寸，浅深色单色着色，24/32px状态栏图标。
- 正式APK安装后实拍：系统桌面应用列表图标、关于页浅色和深色布局。模拟器实际桌面为圆形遮罩；其他外形为相同编译资源的遮罩预览，尚未在小米真机验收。
- 现有`HomeComposeTest`8项及`NativeSettingsTest#compactAboutKeepsCreditsAndReadsEntireLicenseFromDiagnostics`1项通过，覆盖滑动/点击切页、状态恢复、关闭动画和关于页诊断/许可证。未改变原有业务功能。
- 最终个人正式包签名、包名、版本及法律资产验证通过。版本仍为开发预览构建，本轮不创建tag、不推送、不发版。
- 新APK为1,393,442字节；对比上一轮界面预览包1,704,682字节，减少311,240字节（18.26%）。这是本地预览包对比，不是替换已公开Release附件。

本地验收材料位于`.local/diagnostics/icon-refresh/`：`icon-variants.png`、`icon-android-mask.png`、`about-light.png`、`about-dark.png`、`launcher.png`、构建/Lint、回归和正式签名日志。
