# ReMRSS身份迁移与协议冲突检测

2026-10-16，本地改动，尚未推送或发布。

## 应用身份

- 正式应用名：ReMRSS，包名与源码namespace：`top.zainzh.rearscreen`。
- 调试名称：ReMRSS 调试版，包名：`top.zainzh.rearscreen.debug`。
- Java/Kotlin/AIDL目录、生成R/BuildConfig引用、混淆规则、自定义布局、组件名称和测试均迁移。Provider authorities继续按实际applicationId生成，分别为`.updates`和`.shizuku`。录屏共享临时文件按包名区分。
- 保留已有PKCS12证书和别名；正式包不再追加`.personal`。CI与本机构建一致，后续附件名为`ReMRSS-<tag>.apk`。
- 原作者、上游贡献、仓库地址和许可证归属保留，NOTICE追加本次改名记录。旧版历史文档和已发布附件名称按当时真实情况保留。

新旧包可并存。新应用不能覆盖旧版，配置、授权和已添加磁贴不会自动转入新包，需要重新设置和授权；本次没有申请读取旧包私有数据或删除旧应用。新包的后续同包名、同签名版本继续支持正常更新。

## 保留协议与检测

按用户明确要求，正式版仍为`mrss://`，调试版仍为`mrss-debug://`。Android允许多个应用注册相同自定义scheme，无法将其作为独占资源判断；本实现检查其他可见应用是否也能处理现有切屏、返回、截图及配置命令。依据[Android Intent说明](https://developer.android.com/guide/components/intents-filters)及[包可见性说明](https://developer.android.com/training/package-visibility)。

检测使用queryIntentActivities/MATCH_DEFAULT_ONLY，排除自身、未导出/停用组件并按包名去重，包含仅匹配某一命令host的应用。利用现有应用查询权限，不增加权限。扫描在后台进行，进入前台、打开自动化弹窗、其他包安装/卸载/启停时刷新，停止/销毁后注销广播和任务。

存在冲突时，首页标题下显示琥珀色警告卡片，可打开自动化入口；关于页自动化弹窗顶部显示相同警告及相关应用名称/包名。没有冲突时不显示警告；已有普通URI和复制入口保留。检测不执行URI、不改变默认应用、不停用或卸载其他应用。其他用户/工作资料中不可见的应用不在检测范围内。

## 独立更新

新版固定读取`main/updates/remrss/version.json`，成功正式发布后由CI创建/更新。旧根清单继续对应旧MRSS包，不改公开附件或假造新版本信息。首次ReMRSS正式发布前，新清单404属于尚未生成，应用如实提示清单暂不可用。镜像代理仍同时覆盖新版清单和下载；大小、SHA256、包名、版本、签名验证和系统安装器流程继续生效。

## 验证

59项Android运行回归通过（73.405秒）：新增3项身份/组件/协议/更新校验和3项协议检测测试，原有53项首页、设置、清单、下载校验及弹窗交互全部通过。54项发布/清单/服务脚本及actionlint通过；最终Debug/androidTest/R8 Release构建、正式证书/法律资产校验通过，Lint为0错误、63项既有警告。

在项目模拟器中，旧MRSS和新ReMRSS正式/调试包已同时安装。实际检测到旧正式版处理mrss://，首页及关于页自动化弹窗均出现琥珀色警告。仅在项目模拟器临时停用旧版，未关闭弹窗即可自动清除警告；恢复原启用状态后警告自动回来。中文浅深色和两倍字号实拍已目视核对，弹窗内容可滚动、按钮可见。指定新包名的系统URI解析结果为top.zainzh.rearscreen/.UriReceiverActivity。

真实系统安装器冒烟另通过1项（3.624秒），新包FileProvider内容URI被系统安装器接受；仅打开后取消，未安装候选APK或批准来源权限。

旧版启用状态、系统字号、主题和语言已恢复；根version.json与版本号逐字节保持不变。本地材料位于`.local/diagnostics/remrss-identity/`，只在项目专用MRSS_Native_API36模拟器运行，保留用户5554/5556设备。
