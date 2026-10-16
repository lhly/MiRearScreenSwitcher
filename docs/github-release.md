# 推送 tag 自动发布 APK

工作流为 `.github/workflows/release.yml`，替代旧的 `build.yml`。推送 `v*` tag 时，GitHub Actions 会构建并发布个人正式 APK。普通分支推送不会触发此工作流。

## 首次配置 GitHub Secrets

当前仓库的四项 Secrets 已于 2026-10-13 配置完成，使用本机生成的个人正式签名密钥。后续沿用这把密钥发版时无需重复填写，直接按下方步骤更新版本并推送新 tag。

打开当前仓库的 [Actions Secrets 设置](https://github.com/lhly/MiRearScreenSwitcher/settings/secrets/actions)，在 **Repository secrets** 中添加以下四项。原工作流使用相同名称；若之前填过作者密钥，请替换成此次生成的个人密钥。

| Secret 名称 | 内容 | 本机已导出的文件 |
| --- | --- | --- |
| `KEYSTORE_FILE` | 个人 PKCS12 密钥文件的 Base64 内容 | `.local/github-secrets/KEYSTORE_FILE.txt` |
| `KEYSTORE_PASSWORD` | 密钥库密码 | `.local/github-secrets/KEYSTORE_PASSWORD.txt` |
| `KEY_ALIAS` | 密钥别名，当前为 `mrss-release` | `.local/github-secrets/KEY_ALIAS.txt` |
| `KEY_PASSWORD` | 私钥密码，当前与密钥库密码一致 | `.local/github-secrets/KEY_PASSWORD.txt` |

这四个文件已经在本机生成，权限为 `600`，目录权限为 `700`，并被 Git 忽略。填写每一项时复制对应文件的完整内容；`KEYSTORE_FILE` 填的是编码内容，不是文件路径。文件包含签名凭据，仅用于此仓库的 Secrets 配置，勿加入提交或 Release 附件。

如需重新导出，在项目根目录运行：

```sh
python3 tools/export_github_signing_secrets.py
```

该工具只导出本地文件，不访问 GitHub。工作流使用内置 `GITHUB_TOKEN`，不需要 `PAT` Secret。发布 job 的 `contents: write` 权限已经在 YAML 中配置；仓库需启用 GitHub Actions，并允许工作流使用所引用的 Actions。

如果已安装 GitHub CLI 并登录，也可以自行执行以下命令填写 Secrets：

```sh
for name in KEYSTORE_FILE KEYSTORE_PASSWORD KEY_ALIAS KEY_PASSWORD; do
  gh secret set "$name" --repo lhly/MiRearScreenSwitcher \
    < ".local/github-secrets/$name.txt"
done
```

## 发版操作

当前版本为 `3.3.2 / 2014`。先提交应用、配置与文档，再推送新的同名tag；tag必须指向完整构建源码。下方为本次发布命令，已发布后不要重复执行；后续发布须递增版本与构建号。

```sh
git push origin main
git tag -a v3.3.2 -m "MRSS 3.3.2"
git push origin v3.3.2
```

预览版可使用下一版本的`v3.3.3-rc.1`，并递增versionCode。预览版标记为Pre-release，正式版标记为Latest。tag必须与versionName一致，不允许带构建元数据。

[Release](https://github.com/lhly/MiRearScreenSwitcher/releases/tag/v3.3.2)提供`MRSS-v3.3.2.apk`、`SHA256SUMS`、`LICENSE`和`NOTICE`，以及同tag源码和构建说明链接。说明列出本次功能变更，不自动拼接旧发布记录。Actions另保存`release-v3.3.2`构建产物14天。

## 构建与签名检查

环境为Ubuntu22.04、JDK21、Gradle9.7.1、AGP9.4.1、Kotlin2.4.20、Android SDK37.0/Build Tools37.0.0；不需要Flutter或NDK。外部Actions均用完整commit SHA固定。

构建前执行发布参数、清单和服务连接测试。签名凭据缺失、格式错误或证书不符即失败。成品必须满足：

- 包名`com.tgwgroup.MiRearScreenSwitcher.personal`，版本与tag和version.properties一致，不可调试。
- 证书SHA256为`6479192fc1646532afc357f27d1581832bef5e6ad15e2f44501d883f04c783ac`，沿用个人正式签名，可覆盖升级并保留数据。
- 包含DEX，不含Flutter引擎/Dart AOT，内置LICENSE/NOTICE与根文件逐字节一致。

工作流包含构建、发布、更新清单三个job。构建job仅有仓库读取权限，发布job只接收已验证的附件；先上传草稿，再公开。重跑可补齐草稿，已公开的相同tag不替换附件。正式公开后，清单job才写入新版本。

## Raw版本清单与应用内更新

App匿名读取`https://raw.githubusercontent.com/lhly/MiRearScreenSwitcher/main/version.json`，不含Token，不请求GitHub Release API。清单含schemaVersion、versionName、versionCode、tag，以及apk.url/sha256/size/packageName。下载必须来自本仓库对应tag的HTTPS APK附件。

`version.properties`表示构建版本，`version.json`只描述已公开的正式成品。新版本的URL、大小和SHA256由CI在公开成功后填写，不能用本地预览包提前生成线上清单。撤下问题发布时，可将清单恢复为已核验且仍公开的上一稳定版；新版本成功后仍由CI替换，不修改客户端协议或防回退逻辑。

清单job读取公开Release，确认不是草稿或预览，APK/SHA256SUMS/LICENSE/NOTICE附件齐全且上传结束；再将下载URL、digest和size与构建job生成的version-artifact.json对照。全部一致才使用内置GITHUB_TOKEN和contents: write权限，仅提交main/version.json；Token不进入APK或清单。

同一清单更新串行执行，版本号和versionCode阻止延迟任务回退；重复运行不产生额外提交，SHA冲突重读后最多重试三次。CI提交后，本地需同步main再继续开发。

发现新版本后，App提供“下载更新”、进度、取消与重试。下载完成核对大小、SHA256、包名、版本和已安装应用签名，再通过FileProvider唤起系统安装器，由用户确认安装；缺少安装来源授权则引导系统授权。清单404/格式错误/网络异常不会误报最新版，Raw可能存在CDN缓存延迟。详情见[应用内更新验证](in-app-update-validation.md)。

## 对应源码与许可

Release提供同tag源码下载/浏览、该版本构建说明、完整GPL与贡献说明，APK可从“关于→诊断信息→开源许可证”离线阅读。已发布的有效tag不重打，后续修改使用新版本。

## 本地验证与云端验收

v3.3.2包含Compose/Miuix首页、页签滑动和动画、蓝白新图标、精简关于页、Raw版本检查、应用内下载校验/系统安装器，以及腾讯文档教程恢复。构建和本地验证完成后推送tag，云端发布及公开附件验收结果在本节追加；云端文件的校验信息以CI输出为准。

发布前本地52项脚本检查、actionlint、Debug/androidTest/Release构建及完整Lint通过（0错误、63项既有警告）。项目专用API36模拟器45项回归通过（98.472秒），覆盖8项首页、12项原生设置、11项更新检查、9项下载校验及5项下载页面交互。正式APK为1,393,442字节，个人签名、包名、3.3.2/2014元数据、非调试及法律资产复核通过。

历史稳定版v3.3.0的[云端运行](https://github.com/lhly/MiRearScreenSwitcher/actions/runs/38376513962)成功，APK为581,195字节，SHA256为`b14a0b0346dadb7124e0f86335df4c00d5149336967216710eb88fca64a0cf7d`，个人签名、包名和3.3.0/2012元数据已核验，真机记录见[磁贴验证](hyperos4-tile-validation.md)。

```sh
source .local/env.sh
python3 tools/test_ci_release.py
python3 tools/test_update_version_manifest.py
python3 tools/test_task_service_connection.py
RELEASE_TAG=v3.3.2 python3 tools/ci_release.py metadata
```

参考：[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub CLI创建Release](https://cli.github.com/manual/gh_release_create)、[发布草稿](https://cli.github.com/manual/gh_release_edit)。
