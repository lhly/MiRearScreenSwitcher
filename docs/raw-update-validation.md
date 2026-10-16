# Raw 更新检查验证（清单初始改造）

后续应用内下载和安装验证见[应用内更新验证](in-app-update-validation.md)。下文保留首次Raw改造的历史验收。

2026-10-15，按用户要求改为读取Raw version.json；仅本地提交，不推送、不创建tag或Release。构建版本保持开发预览构建，初始更新清单为已公开的3.3.0/2012。

## 客户端与CI约定

- 固定读取`https://raw.githubusercontent.com/lhly/MiRearScreenSwitcher/main/version.json`，不调用GitHub Release API、不携带认证信息。
- 清单含schemaVersion=1、versionName、versionCode、tag；类型严格校验，tag必须为vMAJOR.MINOR.PATCH并与versionName一致。预览版、非法版本、未知schema、错误类型均不能成为更新通知。
- 结合语义版本和Android versionCode判断新版、相同或本地较新；不建议降级。仅构造本仓库Release链接，忽略外部URL。
- 保留后台线程、重复点击去重、销毁取消、10秒连接/读取超时及256KiB响应上限。404提示清单暂不可用，可重试或前往发布页，不误报最新版。
- CI的update-version job仅在正式版构建和发布成功后运行；再次确认公开Release及所需附件，以运行时GITHUB_TOKEN仅更新main/version.json。预览/草稿/失败发布不更新清单；旧任务不回退、重复执行无提交、SHA冲突重读并有限重试。

## 本地验证

- debug、androidTest、R8 release及完整lintRelease通过（零错误，69项既有警告）。正式APK为1,643,378字节，个人证书、包名、版本和完整法律资产校验通过；DEX包含Raw清单地址，不含api.github.com地址。
- API36模拟器25项完整运行测试通过，新增404清单界面回归单独通过，共26项。覆盖原首页/页面、更新跳转/重试、语义版本与versionCode、schema/类型错误、404/403/429/网络错误、后台线程、重复检查、销毁取消及Raw请求无认证头。
- 18项清单/CI测试、14项发布检查、16项服务连接回归通过；actionlint及git diff --check通过。CI检查覆盖公开状态、附件完整性、幂等、防回退、SHA冲突重试上限及错误不泄漏凭据。
- 实际安装压缩正式APK，点击检查更新后Raw返回404，界面正确提示清单暂不可用，显示“发布记录/取消/重试”，没有误报最新或GitHub API限流。已目视确认截图，恢复测试前语言设置。

日志及实拍保存在被忽略的`.local/diagnostics/raw-updates/`。使用项目专用模拟器5556测试，未修改用户Medium Phone模拟器5554的数据。

本轮不会执行真实云端发布或清单写入。Raw在线文件需后续推送main后才会存在；成功分支使用与CI相同格式的可控清单验证，发布状态使用离线API替身覆盖。
