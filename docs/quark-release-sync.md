# 正式版自动同步到夸克网盘

本地后台任务定时检查 ReMRSS 稳定版清单，通过夸克网盘官方 Skill 上传发行文件。不需要打开夸克网盘客户端或 Codex；电脑需要保持登录、联网且未关机。休眠或离线期间无法上传，恢复后会在后续检查时同步最新正式版。

## 同步内容

现有 GitHub Actions 完成正式发布并更新 `updates/remrss/version.json` 后，本地任务会：

1. 读取已公开的稳定版版本、下载地址和 APK 校验信息。
2. 获取 `ReMRSS-vX.Y.Z.apk`、`SHA256SUMS`、`LICENSE` 和 `NOTICE`，校验大小及 SHA256。
3. 在指定网盘目录下创建 `vX.Y.Z` 文件夹，逐个上传发行文件，再读取内容校验。
4. 保存完成记录，后续检查不会重复上传该版本。

它不会重新构建 APK，也不会使用草稿、预发布或本地测试包。检测依据是现有 CI 维护的稳定清单，不消耗 GitHub Release API 的匿名配额。清单还未更新时会等待下一次检查，不将未验证附件提前上传。

## 本机配置

需要 Python 3.11+、Node.js 16+，以及已安装并授权的[夸克网盘官方 Skill](https://pdds.quark.cn/download/stfile/eekkigineefiegmgo/quarkclouddrive-1.0.25.zip)。

配置保存在被 Git 忽略的 `.local/quark-release-sync/config.json`，包含：

- `repository`：固定为 `lhly/MiRearScreenSwitcher`。
- `minimum_version`：开始同步的最低正式版本。
- `publish_dir`：只存放已校验公开附件的本地缓存目录。
- `quark_target_fid`：用户指定的网盘目录；不使用默认根目录。
- `skill_dir`：全局安装的官方 Skill 目录。
- `node_path`、`python_path`：后台任务使用的完整程序路径。
- `interval_seconds`：检查间隔，当前为 `900`，即15分钟。

应在原 Codex 环境中启用后台任务。任务会保留创建时已有的 Codex 渠道标识，供官方 CLI 识别原授权环境；不复制线程标识、聊天记录或凭据。系统定时任务默认只有精简环境，仅配置程序路径会导致官方 CLI 无法识别运行环境，即使定时器仍在执行也无法上传。

授权由官方 Skill 保存和维护，同步配置不包含 Cookie 或授权令牌。定时任务为非对话调用，不发送聊天记录；通过 Agent 手动执行时可传入 `--session-input-file`，复用该对话的原始请求和会话标识。

## 日常使用

立即同步最新正式版：

```sh
python3 tools/quark_release_sync.py run
```

查看上次检查结果：

```sh
python3 tools/quark_release_sync.py status
```

启用本机后台任务，之后登录电脑时自动加载：

```sh
python3 tools/quark_release_job.py enable
```

查看或停止后台任务：

```sh
python3 tools/quark_release_job.py status
python3 tools/quark_release_job.py disable
```

停止本地同步不会删除网盘文件或解除 Skill 授权。任务注册在当前用户的 LaunchAgents 中，名称为 `top.zainzh.remrss.quark-sync`。项目位置或 Python/Node 安装位置改变后，需更新本机配置并重新启用任务。

## 失败、重试与冲突

- 未发现新版本时，只检查稳定版清单，不调用上传接口。
- 中途失败保留已完成文件记录；下一次检查只补齐缺失文件。已上传但回执未落盘的文件会先读取校验，内容一致后复用。
- 网盘已有同名但内容不同的文件，或目录名称存在歧义时，停止并保留原文件，不覆盖、不删除、不擅自改名。
- 授权过期时停止上传，不在后台反复弹出登录页。重新授权官方 Skill 后，下一次检查会继续。
- 上传完成后以完整目录清单核对文件名、大小和返回的文件标识，才将该版本记为完成。
- 已完成记录用于避免重复发布；它不是全盘巡检。若手动删除了网盘里的已同步版本，请先检查本地状态，再决定是否恢复。

状态与日志位于 `.local/quark-release-sync/`：`state.json`、`last-result.json`、`sync.log`、`sync-error.log`。这些文件、缓存附件和 Skill 授权都不加入 Git。

## 验证

```sh
python3 tools/test_quark_release_sync.py
python3 tools/test_update_version_manifest.py
python3 tools/check_private_plans.py
```

回归覆盖附件完整性、错误来源、预发布拒绝、版本回退、重复检查、中断续跑、云端内容不符、目标变化以及并发互斥。真实上传结果以本机验收记录和网盘实际内容为准。

2026-10-31 已完成本机验收：v3.6.0 的四个正式附件上传成功，网盘内容逐个通过 SHA256 校验，完整目录核对通过；再次运行未重复上传。后台任务已加载，每15分钟检查一次，首次自动检查正常且无错误。24项同步回归和22项稳定清单回归均通过。

2026-11-01 修复后台任务未继承创建时 Codex 渠道环境、导致夸克工具无法识别运行环境的问题。重新加载后的真实 LaunchAgent 成功补传 v3.6.1 四个公开发行文件，逐文件回读 SHA256 与完整目录核验通过；再次触发返回 unchanged，状态文件哈希保持一致，无重复上传。26 项同步回归与 22 项稳定清单回归通过。
