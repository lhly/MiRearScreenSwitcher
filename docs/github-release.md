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

先将工作流、辅助脚本、当前构建配置和应用修改提交并推送到 GitHub。tag 必须指向包含这些修改的提交。

当前 `version.properties` 为 `versionName=3.3.0`、`versionCode=2012`。`v3.2.1` 已发布；原生 `v3.3.0` 目前仅本地构建。确认源码与配置后，提交修改再运行：

```sh
git push origin main
git tag -a v3.3.0 -m "MRSS 3.3.0"
git push origin v3.3.0
```

tag 中的版本必须与 `version.properties` 的 versionName 完全一致；versionCode 必须为正整数，更新个人正式版时应持续递增。已发布的 tag 不再重复创建或覆盖。

预览版示例：`versionName=3.3.0-rc.1`、`versionCode=2012` 对应 `v3.3.0-rc.1`。预览版会被标记为 Pre-release；正式版会被标记为 Latest。不接受 `v3.3.0+2012` 这种带构建号的 tag。

在 [Actions 页面](https://github.com/lhly/MiRearScreenSwitcher/actions) 查看 **Release APK** 的运行状态。成功后，[Releases 页面](https://github.com/lhly/MiRearScreenSwitcher/releases) 会出现：

- `MRSS-v3.3.0-native.apk`：已正式签名、不含 Flutter 的原生 APK。
- `SHA256SUMS`：APK 的 SHA256 校验文件。
- GitHub 自动生成的版本说明。

工作流还会保存一份名为 `release-v3.3.0` 的 Actions artifact，保留 14 天。Release 附件不受这份 artifact 的保留期限影响。

## 构建与签名检查

构建环境固定为 Ubuntu 22.04、JDK 21、SDK 36 / Build Tools 36.0.0，使用 Gradle Wrapper 8.9 和显式版本的 Android 依赖。无需 Flutter 或 NDK；外部 Actions 使用完整 commit SHA 固定。

构建前运行发布参数测试与 16 项服务生命周期回归。签名缺失、Base64 错误、密钥无法读取或证书与预期不一致时，工作流失败并停止发版。

产物必须符合以下条件：

- 包名为 `com.tgwgroup.MiRearScreenSwitcher.personal`。
- 版本与 tag、`version.properties` 一致。
- APK 验签通过，签名证书与本机 Android Studio 构建使用的个人密钥一致。
- 不可调试，包含 `classes.dex`，不含 Flutter 引擎、Dart AOT 和 Flutter assets。

预期签名证书 SHA256 已写入工作流：`6479192fc1646532afc357f27d1581832bef5e6ad15e2f44501d883f04c783ac`。沿用这把密钥和包名即可继续覆盖更新个人正式版；作者原版和 `.debug` 测试版有各自的数据目录。

发布使用两个 job：构建 job 只有仓库读取权限；发布 job 只接收已验证的 APK 和校验文件。附件先上传至草稿，再正式发布。失败后可在 Actions 中重新运行：已有草稿会补齐附件并发布，已经公开的同 tag Release 会保留已有附件。修改已发布版本时请递增版本、发布新 tag。

## 本地验证与云端验收

原生工作流在本地检查 YAML 和发布参数，正式产物校验签名、包名、版本与无 Flutter 结构。原生 v3.3.0 尚未触发真实云端运行。以下记录是此前 Flutter v3.2.1 的历史验收，不代表新工作流已云端验收。错误证书和校验文件会阻止发布。

2026-10-13 已完成首次真实云端验收：[Release APK 运行 38348746002](https://github.com/lhly/MiRearScreenSwitcher/actions/runs/38348746002) 的构建和发布两个 job 均成功，[MRSS v3.2.1](https://github.com/lhly/MiRearScreenSwitcher/releases/tag/v3.2.1) 已公开并标记为 Latest，包含 APK 和 `SHA256SUMS`。构建耗时 6 分 35 秒，发布耗时 10 秒。

已从公开 Release 重新下载附件，APK 的 SHA256 与校验文件及 GitHub asset digest 一致：`ce6f1dcd71ec70d41003dde990cee656b4bd70a10bfa84185dfc2e332a69092d`。下载成品的签名证书、个人包名、版本 `3.2.1 / 2011`、不可调试及 ARM64 AOT 结构均通过复核。APK 大小为 17,693,866 字节。本次验收覆盖云端发布和下载产物；手机上的磁贴实测记录见 `docs/hyperos4-tile-validation.md`。

本地可运行：

```sh
source .local/env.sh
python3 tools/test_ci_release.py
python3 tools/test_task_service_connection.py
RELEASE_TAG=v3.3.0 python3 tools/ci_release.py metadata
```

参考：[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub CLI 创建 Release](https://cli.github.com/manual/gh_release_create)、[编辑并发布草稿](https://cli.github.com/manual/gh_release_edit)。
