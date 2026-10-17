# 包体积专项审计与精简

日期：2026-10-17。约束：保持现有功能、Miuix界面、语言和ABI支持；只删除有调用/产物证据的冗余。

## 清理项

- `NativeStrings`移除86个无调用的旧分发分支。更新弹窗仍直接使用其中4条资源，保留这些资源；其余82条文案及未引用的`app_name`，共83个资源名及对应语言版本删除。
- `MainActivity`删除7个没有生产调用方的内部动作：`checkShizuku`、`getCurrentApp`、`checkNotificationListenerPermission`、`checkQueryAllPackagesPermission`、`getInstalledApps`、`getSelectedNotificationApps`、`setSelectedNotificationApps`。旧应用列表及全分辨率图标序列化已由`AppPickerModel`取代；同步删除未使用的shell代理方法。不改变URI协议或AIDL接口。
- `MyApplication`保留`Sui.init`，删除无读取方的返回值/访问器、空生命周期覆盖和指向未打包类的无效HiddenApiBypass反射尝试。
- 移除Activity/Service/Receiver全成员keep及SDK整包keep，允许R8裁剪私有实现与未使用API。AAPT继续保留Manifest组件/XML构造入口，显式保留TaskService公开无参构造器、AIDL契约及固定事务号，使用Shizuku AAR自带BinderContainer规则。
- 将未使用的`activity-compose`适配器替换为同版本`activity`核心依赖；全部正式界面通过ComposeView承载。解析后的依赖坐标对比只少该适配模块，其余依赖版本未变；R8原已裁掉其多数代码，不将声明变化虚报为MB级收益。
- 仅Release排除8份Kotlin built-ins编译描述、AndroidX版本检查标记和未使用的协程调试agent模板`DebugProbesKt.bin`。Debug保留IDE检查元数据；不包含完整kotlin-reflect或coroutines-debug，正式运行不加载这些资源。

## 保留项

- Miuix UI及Blur模块：当前卡片/控件及API33+底部导航模糊仍使用。
- Core、Activity、Shizuku API/Provider及其实际传递依赖：保留界面宿主、生命周期、返回分发、授权和跨进程功能。
- 导出的旧`RearScreenWakeActivity`：虽无内部启动方，仍是已发布兼容入口，不据内部搜索结果直接删除。
- 所有语言、四种ABI、baseline profiles及服务发现文件保留。APK内全部LICENSE/NOTICE逐字节与基线对比一致。
- 不降低图片质量，不更换控件，不移除离线教程，不修改录制逻辑、协议、版本或签名。

## 体积

基线为`30dc626`实现的签名Release，1,695,366字节。分批实测：

| 阶段 | APK字节 |
| --- | ---: |
| 基线 | 1,695,366 |
| 死代码/旧文案/应用keep规则清理 | 1,650,550 |
| SDK keep收敛 | 1,634,166 |
| 最终候选（Release元数据及依赖精简） | 1,610,691 |

最终候选减少84,675字节，约84.7 KB / **4.99%**。ZIP内容、目录/签名对齐均计入实际文件大小，分组增量不简单等于单个库成本。

压缩DEX由1,285,916降到1,261,113字节；资源表由253,928降到209,112字节。剩余主要是实际使用的Compose技术栈，不能在维持功能与风格的前提下把整套框架当作冗余删除。

## 验证

新增`tools/test_release_contract.py`可直接在专用测试设备shell进程加载已压缩APK，验证TaskService反射构造、固定AIDL descriptor/事务号、真实Binder输出与布尔返回。

- Debug、androidTest及签名Release构建通过；Release Lint为0错误、35警告，未发现UnusedResources。
- 签名、包名、版本、许可证校验通过。最终SHA256：`7c738ff510dd50a6cd8bc64269cc1e72147f2e57a17c7aac9a005af5734ac5f6`。
- TaskService连接状态16项、显示方向24项、发布流程16项，共56项Python回归通过。
- 77个不同的仪器回归用例全部通过：完整批次76项通过；应用搜索用例收起软键盘后单项复测通过。覆盖首页、设置、权限、教程、更新下载/校验/安装、充电与通知交接、录屏浮层和偏好兼容。
- 修正仅影响测试定位和debug更新fixture：使用实际资源引用、主动滚动懒列表、避开IME动画坐标、在宿主恢复后请求测试更新。正式更新、输入与布局行为不变。
- 最终已压缩APK的反射构造和真实Binder事务验证通过，shell UID为2000。
- 正式包断网教程/权限页面、系统磁贴启动录屏浮层、浅深色切换与关闭通过；已逐张复核截图，Miuix页面及白/深灰浮层风格一致。浮层为不可聚焦窗口，验收通过WindowManager实际可见帧与截图确认，未启动真实录制。

测试用Shizuku来自[官方v13.6.0 Release](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)，仅安装在项目专用API36模拟器。已完成真实授权及UserService连接、服务死亡/重启后重新绑定；测试管理器、启动器和临时授权已清理。

本地证据：`.local/diagnostics/apk-audit-20261017/`。不将测试APK、工具链或诊断日志打包进正式App。

## 验证边界

本轮使用API36模拟器；未执行小米真机背屏验收或实际录制。没有为缩小包体删除不确定的兼容入口或降低现有功能。此次结果表示已清理可确认的冗余，不保证仓库或第三方依赖绝无其他可优化实现。

保持版本3.3.4/2016与原签名，验收包保留在本地；后续按用户授权保存全部改动为本地Git提交，未推送、打tag或发布。
