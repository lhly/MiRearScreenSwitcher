# 应用内下载与安装更新验证

当前源码已将下载流程合并到更新弹窗，目标版本仅展示一次；相关实现和本轮验收见[弹窗下载验证](update-dialog-validation.md)。下文保留独立下载页初次实现的历史验收，不代表当前交互。

2026-10-15，本轮只准备和本地提交代码，不推送、不创建tag或Release。应用版本保持开发预览构建；version.json仍描述已公开的3.3.0/2012，补齐该版本已核验的原APK地址、大小和SHA256。

## 实际流程

1. 用户点击检查更新，匿名读取Raw的version.json；发现新版后提供“下载更新”和“查看更新”。
2. 点击下载后打开独立页面，显示百分比、已下载/总大小；支持取消、网络失败重试和屏幕旋转保留进度。退出该页面会取消下载，应用进程存活时退到后台可继续；后台完成不会强行拉起安装器。
3. APK保存在私有cache/updates目录。核对精确大小、SHA256、包名、版本名/versionCode以及当前已安装应用的签名，全部通过才变成可安装文件。校验失败删除临时文件，不交给安装器。
4. 通过非导出的FileProvider授予单个APK临时读取权限，再打开Android安装器。API26以上未允许本应用安装时先提供系统安装来源授权入口，拒绝后保留已校验文件，授权返回后重新校验并继续。用户在系统安装器确认安装；应用不会静默安装。

下载只接受本仓库对应tag的HTTPS APK链接，重定向仅接受GitHub及其已知Release附件域名，拒绝HTTP降级、任意域名和无限重定向。连接/读取超时为10/20秒、传输最多10分钟，文件上限256MiB。取消/失败清理临时文件，已完成缓存保留供系统读取，超过24小时的更新缓存在后续下载时清理。进程被终止后重新进入会重新下载，不提供断点续传。

调试版包名与personal正式版不同，不能互相覆盖。正式包使用原个人签名和包名；校验不匹配时停止并保留发布页入口。

## CI来源

清单继续使用schemaVersion=1，新增apk对象：url、size、sha256、packageName。构建job在验签、版本和法律资产检查通过后，从实际APK生成version-artifact.json，随Actions artifact传给update-version job。

正式版Release发布成功后，更新job核对对应公开Release、完整附件、APK的browser_download_url/size/digest与构建元数据一致，再提交main/version.json。缺失摘要、附件不同、草稿/预览版、回退或并发冲突按原保护处理，不发布错误更新信息。认证仅使用CI运行时GITHUB_TOKEN；APK及清单没有Token。

## 验证结果

- API36隔离模拟器41项常规运行测试通过，含原首页/设置回归、版本检查、下载按钮、9项下载/校验/URI测试和5项下载UI测试。旋转后不重复下载；进度、失败重试、校验拦截、权限拒绝/返回及退出取消均通过。
- 另1项显式启用的系统安装器冒烟测试通过：真实签名APK经下载校验后，content URI成功交给系统安装器，测试返回取消，不安装候选包、不修改安装来源权限。
- 真实APK样本覆盖同签名新版、不同签名、不同包名、当前旧版。另覆盖截断、超量、篡改内容、错误HTTP/重定向、取消及安装前文件变更。候选3.4.0/2015仅为本地测试构建，不存在对应公开发布。
- 22项CI清单测试、14项发布检查、16项服务连接回归、actionlint及git diff --check通过。
- debug、androidTest、开启R8的release和完整lintRelease通过（零错误，69项既有警告）。正式APK为1,671,930字节（约1.67MB），个人证书、包名/版本和离线法律资产校验通过；DEX确认包含Raw端点，不含api.github.com、调试测试Activity或嵌套测试APK。
- 已目视检查下载进度和安装授权页面截图。日志、APK测试样本和实拍仅在被忽略的.local/diagnostics/in-app-updates目录。

公开Raw文件需未来推送main后才能访问，本轮没有执行云端发布或清单写入。联网下载使用受控传输和真实APK测试；真实安装器入口单独验收，未以测试为由发布假版本。

## 复核方法

```sh
source .local/env.sh
python3 tools/test_ci_release.py
python3 tools/test_update_version_manifest.py
python3 tools/test_task_service_connection.py
.local/toolchains/actionlint/actionlint .github/workflows/release.yml
```

androidTest需要在本地生成候选APK测试资源，放入自选目录：candidate.apk（同调试签名3.4.0/2015）、wrong-signer.apk（同候选但使用测试签名）、other-package.apk（personal正式包）、current.apk（当前调试包）。以`-PmrssUpdateFixtureDir=<该目录绝对路径>`构建androidTest，资源只进入测试APK。normal debug/release不包含这些APK。

常规测试排除UpdateInstallerSystemTest；真实安装器测试需显式指定该class并传入`-e verifyInstallerUi true`，只在隔离模拟器运行。FixtureUpdateActivity为debug专用、非导出的本地传输入口，生产release完全不包含该类或入口；它以已安装调试应用核对真实调试签名，公共Raw协议仍指向personal正式版。

依据：[Android文件共享](https://developer.android.com/training/secure-file-sharing)、[安装来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[签名信息](https://developer.android.com/reference/android/content/pm/SigningInfo)。
