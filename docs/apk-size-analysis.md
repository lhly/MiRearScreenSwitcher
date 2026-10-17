# APK 体积增长分析

分析日期：2026-10-17。实现提交：`30dc626`。本轮仅分析，不调整依赖或压缩配置。

主要增长来自采用 Miuix 时引入的 Compose UI 技术栈和实际使用范围扩大，不能全部归因于 Miuix 自身的控件实现。还需区分 Release 与 Debug：当前签名 Release 为 **1.70 MB**，Debug 为 **12.18 MB**。

## 测量口径

直接测量本地留存 APK 文件，MB = 1,000,000 字节；不使用安装后占用，也不将 AAR/JAR 下载大小直接累加为包体积。各历史包为实际构建阶段的留存产物，不是只改变单个变量的对照实验。

| 阶段 | APK 字节 | MB |
| --- | ---: | ---: |
| Compose 引入前 | 596,282 | 0.596 |
| 首页引入 Compose / Miuix 后 | 1,635,222 | 1.635 |
| 已发布 v3.3.4 | 1,412,798 | 1.413 |
| 权限检测菜单 | 1,434,234 | 1.434 |
| 内置离线教程 | 1,461,930 | 1.462 |
| 全部设置迁移 Miuix | 1,732,530 | 1.733 |
| 录屏悬浮层样式统一 | 1,735,338 | 1.735 |
| 全 App 遗留 UI 清理后（当前 Release） | 1,695,366 | 1.695 |
| 当前 Debug | 12,178,120 | 12.178 |

- 首次引入整套 UI 技术栈净增 **1,038,940 字节，约1.04 MB**。
- 已发布3.3.4至当前净增 **282,568 字节，约0.28 MB / 20.0%**。
- 权限菜单至离线教程净增27,696字节，说明离线文字教程不是MB级增长来源。
- 全量Miuix迁移阶段净增270,600字节。该阶段未修改依赖声明，但新增使用输入、选择、弹窗等控件和Compose页面生成代码；根据调用范围与DEX增长判断，扩大了R8需要保留的实现。这是阶段归因，不能精确拆成每个控件的独立成本。
- 最近全App清理反而减少39,972字节，约40 KB。
- 首页Miuix试点后至3.3.4的下降，与图标资源优化一致：`res/`压缩内容从341,113字节降到29,469字节；不能将所有阶段的涨跌都解释成框架变化。

## 当前 APK 构成

以下为ZIP条目的压缩后大小，最后一行包含ZIP目录、签名块和对齐等开销。

| 内容 | 字节 |
| --- | ---: |
| DEX | 1,285,916 |
| resources.arsc | 253,928 |
| 四种ABI的本地库 | 37,392 |
| assets（许可、NOTICE、编译配置等） | 23,957 |
| res/ | 10,501 |
| META-INF | 14,831 |
| Kotlin元数据资源 | 12,425 |
| 其他顶层内容 | 4,713 |
| 签名/ZIP/对齐开销 | 51,703 |
| 合计 | 1,695,366 |

当前压缩DEX约占包体积76%，图片已很小。四份本地库均为`libandroidx.graphics.path.so`，合计只有37,392字节，不存在数MB的多架构渲染引擎开销。

## Miuix 与 Compose 的区别

`releaseRuntimeClasspath`显示：

- 应用直接使用`androidx.activity:activity-compose`和Miuix的Android UI/Blur模块。
- Miuix的core依赖Compose Foundation，并使用圆角、着色、窗口尺寸和配色辅助模块。
- 运行时还包含Compose Runtime/UI/Foundation/Animation、AndroidX生命周期、Kotlin标准库、协程和少量序列化支持。

SDK `apkanalyzer dex packages`加载当前R8 mapping后，对已定义代码的估算如下：

| 原始命名空间 | 代码字节估算 |
| --- | ---: |
| androidx.compose | 1,259,215 |
| top.yukonga（Miuix） | 76,471 |
| top.zainzh.rearscreen（应用） | 191,370 |
| kotlin | 101,688 |
| kotlinx | 125,013 |
| 全部命名空间合计 | 2,048,824 |

该表说明保留下来的代码以Compose为主，**不能**将各命名空间的数值当成它们单独给APK增加的压缩字节数。R8会内联、合并、重命名并共享数据，统计也不涵盖DEX的全部共享区。

新增页面不会再打包一份完整框架，但新使用的控件会让原本可裁剪的代码变成可达代码。删除旧View类或少量资源，也不能抵消已使用框架的基础成本。

## 压缩状态与复查方式

当前Release已启用`isMinifyEnabled = true`和`isShrinkResources = true`，使用R8优化默认规则；Debug未开启这些Release压缩选项，含开发所需的更多代码，因此不能拿它与已发布Release直接比较。

本轮没有更换UI方案、升级依赖、删除许可或新增体积优化。后续若要继续减小包体，应分别测量依赖和控件的实际保留范围，再评估具体取舍，不能仅凭Maven下载体积判断。

测量使用：Python `zipfile`分组、Gradle `:app:dependencies --configuration releaseRuntimeClasspath`，以及SDK `apkanalyzer dex packages --defined-only --proguard-mappings ...`。本地明细保存在`.local/diagnostics/apk-size-20261017/`，未纳入Git。

当前分析APK SHA-256：`ec3f81b4eb2b3a90e71350b313d622108a0d3d1cff3a8f8f6080f450ceee234b`。
