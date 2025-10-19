# MiRearScreenSwitcher (MRSS)

为小米17Pro及以上（也许及以后）机型的背屏一键切换器

## ✨ 功能特性 (V2.1.0)

- 🎯 **快捷切换**: 通过控制中心快捷开关一键将应用切换到背屏
- 📸 **背屏截图**: 一键截取背屏画面并保存到相册（自动收起控制中心）
- 🔓 **后台可用**: 即使应用在后台也能正常触发切换
- 🚀 **无需ROOT**: 基于Shizuku实现，无需ROOT权限
- 🎨 **精致UI**: Material 3设计，渐变色UI，精确2.84超椭圆圆角
- 🛡️ **智能保护**: 防止系统Launcher覆盖投射的应用
- 💡 **保持常亮**: 背屏自动保持常亮，防止自动息屏
- 🔄 **智能监控**: 背屏应用退出或切换时自动清除通知
- 📱 **DPI调整**: 支持动态调整背屏DPI，优化显示效果（推荐260-350）
- 🤚 **接近传感器**: 覆盖背屏接近传感器自动返回主屏
- 🔄 **背屏旋转**: 支持背屏0°/90°/180°/270°旋转，应用自动复活
- 🚫 **任务隐藏**: 主应用不在最近任务列表显示，防止误清理

## 📋 使用前提

1. **设备要求**: 支持背屏的小米手机（如小米17Pro等）
2. **Shizuku**: 需要安装并启动Shizuku
   - 下载地址: [Shizuku官网](https://shizuku.rikka.app/)
   - 启动方式: ADB或无线调试

## 🚀 使用方法

### 1. 初次设置

1. 安装MRSS应用
2. 确保Shizuku已启动
3. 打开MRSS应用，授予Shizuku权限
4. 等待状态显示"一切就绪"

### 2. 添加快捷开关

1. 从屏幕顶部下拉打开**控制中心**
2. 点击**编辑按钮**
3. 找到以下快捷开关并添加：
   - **切换至背屏**: 将当前应用投放到背屏
   - **获取背屏截图**: 截取背屏画面并保存
4. 完成！

### 3. 日常使用

**切换应用到背屏：**
1. 打开任意想要切换到背屏的应用
2. 下拉控制中心
3. 点击"**切换至背屏**"快捷开关
4. 应用立即切换到背屏，控制中心自动收起
5. 翻转手机即可在背屏查看

**截取背屏画面：**
1. 下拉控制中心
2. 点击"**获取背屏截图**"快捷开关
3. 控制中心自动收起，截图保存到相册

**返回主屏：**
- 方法1: 点击通知"点击将应用切换回主屏"
- 方法2: 用手覆盖背屏接近传感器
- 方法3: 在背屏退出应用，通知自动消失

**调整背屏显示：**
- DPI调整: 在应用内设置，推荐260-350
- 旋转控制: 支持0°/90°/180°/270°旋转
- 输入焦点时允许滚动，其他时候禁止

**💡 提示**: 
- 即使MRSS应用在后台或已关闭，快捷开关依然可以正常使用！
- MRSS不会出现在最近任务列表，避免误清理
- 背屏会自动保持常亮，防止自动息屏
- 应用退出或切换时，通知会自动清除
- 完整教程请查看: [酷安使用教程帖](https://www.coolapk.com/feed/67979666)

## 🔧 技术实现

- **Flutter**: 跨平台UI框架，Material 3渐变色设计
- **Shizuku**: 提供shell权限执行特权操作
- **Quick Settings Tile**: Android系统级快捷开关服务（两个Tile）
- **ActivityTaskManager**: 通过system service调用实现显示切换
- **Foreground Service + WakeLock**: 前台服务持有唤醒锁保持背屏常亮
- **智能监控**: 每2秒检测背屏前台应用，自动清除无效通知
- **Proximity Sensor**: 背屏接近传感器检测，自动返回主屏
- **DPI Management**: 动态调整背屏显示密度
- **Screenshot**: 通过screencap直接截取背屏画面

## 📝 权限说明

- `moe.shizuku.manager.permission.API_V23`: Shizuku API权限，用于执行特权操作
- `android.permission.WAKE_LOCK`: 保持背屏常亮
- `android.permission.FOREGROUND_SERVICE`: 前台服务权限
- `android.permission.POST_NOTIFICATIONS`: 通知权限（Android 13+）
- 系统广播接收: 监听`SUB_SCREEN_ON/OFF`和`SCREEN_ON/OFF`事件

## 🛠️ 开发构建

```bash
# 安装依赖
flutter pub get

# 构建Debug APK
flutter build apk --debug

# 构建Release APK (arm64-v8a, 代码混淆+资源压缩)
flutter build apk --release --split-per-abi --target-platform android-arm64
```

生成的APK位于: `build/app/outputs/flutter-apk/app-arm64-v8a-release.apk`

## 🔍 技术细节

### V2.1 核心功能

1. **智能应用切换** 🎯
   - 通过Quick Settings Tile快捷开关触发
   - 使用`am stack`命令获取前台应用
   - 调用`service call activity_task 50`切换显示屏
   - 自动杀死系统Launcher防止挤占
   - Toast提示显示具体应用名

2. **前台Service保活** 🛡️
   - 使用前台Service持有WakeLock保持背屏常亮
   - 低优先级通知，最小化用户干扰
   - 点击通知可快速返回主屏
   - 主应用隐藏于最近任务，防止误清理

3. **智能监控与清理** 🔄
   - 每2秒检测背屏前台应用状态
   - 应用退出或切换时自动停止服务并清除通知
   - 接近传感器触发时自动返回主屏
   - 防止多应用同时投放

4. **背屏截图功能** 📸
   - 直接使用screencap截取背屏画面
   - 点击后自动收起控制中心
   - 截图保存到系统相册

5. **DPI动态调整** 📱
   - 支持实时调整背屏显示密度
   - 使用`wm density`命令修改
   - 一键还原默认设置
   - 推荐范围: 260-350

6. **背屏旋转控制** 🔄
   - 支持4个方向: 0°/90°/180°/270°
   - 使用`wm user-rotation -d 1`独立控制背屏
   - 旋转后应用自动复活
   - 实时显示当前旋转状态

### 性能优化

- ✅ 代码混淆（ProGuard/R8）
- ✅ 资源压缩
- ✅ 只包含arm64-v8a架构
- ✅ 移除所有调试日志
- ✅ APK体积优化至15.9MB

## 📄 许可证

MIT License - 详见 [LICENSE](LICENSE) 文件

## 👥 团队

### 作者
**AntiOblivionis**
- 🎮 QQ: 319641317
- 📱 酷安: [@AntiOblivionis](http://www.coolapk.com/u/8158212)
- 🐙 Github: [GoldenglowSusie](https://github.com/GoldenglowSusie/)
- 📺 Bilibili: [罗德岛T0驭械术师澄闪](https://space.bilibili.com/407059627)

### 首席测试官
**汐木泽**
- 📱 酷安: [@汐木泽](http://www.coolapk.com/u/4279097)
- 提供关键测试反馈和功能建议

## 🤖 AI协作开发

本项目由作者与以下AI助手共同开发：
- Cursor
- Claude-4.5-Sonnet
- GPT-5
- Gemini-2.5-Pro

## 🙏 致谢

- [Shizuku](https://github.com/RikkaApps/Shizuku) - 提供特权API支持
- Flutter团队 - 优秀的跨平台框架
- Xiaomi HyperOS 小米澎湃OS团队 - 小米手机背屏功能
