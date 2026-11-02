# 官方模板开窗测试夹具

四张单通道 PNG 只保存官方截图壳图的遮挡几何，不包含机身、镜头美术或屏幕内容，也不会进入正式 APK。`sources.json` 记录官方下载地址、原始 PNG 的 SHA256、官方定位 JSON 和生成后夹具的 SHA256。

生成方法：将官方 `full_shell.png` 按对应 `full_shell.json` 的 `leftMargin`、`topMargin`、`with` 和 `height` 裁出屏幕范围，取左侧 296 个物理像素；原图 alpha ≥ 128 的位置记为 255（遮挡），其他位置为 0（开窗）。采用实体轮廓阈值，是因为部分官方模板在整个屏幕内有低透明度装饰，不能将任意非零 alpha 当作镜头或屏幕外框。电量和文字测试还分别检查代码中保守镜头轮廓的 4px 留边及文字额外 7px 留边。

`CameraTemplateRenderingTest` 将生产 View 的实际 Android 文字、天气图标和硬件玻璃电量渲染与此独立轮廓对照。模板通过不代表面板、镜头贴膜或该机系统天气接口已通过真机验收。
