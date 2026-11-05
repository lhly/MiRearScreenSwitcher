# 应用内更新说明

稳定版更新清单可带有 `releaseNotes` 展示字段：

```json
{
  "releaseNotes": {
    "added": ["新增功能"],
    "improved": ["体验优化"],
    "fixed": ["问题修复"]
  }
}
```

三个字段均为字符串数组，内容仅用于版本说明展示。旧清单缺少 `releaseNotes` 时保持兼容；如果可选字段结构错误，客户端将按说明缺失处理，不影响 APK 地址、大小、SHA256、包名、版本或签名校验。说明字段不会放宽下载与安装验证。

## 发布来源

每个 tag 的 Release 正文和 `version-artifact.json` 都从该 tag 对应的 CHANGELOG 版本段提取“新增、优化、修复”三类条目。工作流要求三类标题齐全、至少有一条功能说明，并拒绝不属于分类列表的额外正文，因此 `Unreleased` 和其他版本内容不会混入本次发布。发布 job 使用构建 job 传来的 Markdown 文件；Raw 清单写入前还会核对 Release 正文与结构化说明一致。

相同 tag 的 notes-only 补齐只能在 Raw 清单尚无 `releaseNotes` 时进行，并要求已发布 APK 元数据完全一致。已存在的说明不可被覆盖；较新清单也不会被延迟的旧发布任务回退。
