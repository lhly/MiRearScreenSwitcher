# MiRearScreenSwitcher (MRSS)

为小米17Pro/17Pro Max等双屏设备的背屏一键切换器

**💬 交流与支持**
- QQ交流群：**932738927** - [加入群聊](https://tgwgroup.ltd/2025/10/21/%e5%85%b3%e4%ba%8emrss%e4%ba%a4%e6%b5%81%e7%be%a4/)
- 打赏支持：[请作者喝杯咖啡](https://tgwgroup.ltd/2025/10/19/%e5%85%b3%e4%ba%8e%e6%89%93%e8%b5%8f/) ☕

---

## ✨ 功能特性 (V3.0.0)

- 🎯 **快捷切换**: 通过控制中心快捷开关一键将应用切换到背屏
- 📸 **背屏截图**: 一键截取背屏画面并保存到相册（自动收起控制中心，keycode唤醒）
- 📹 **背屏录屏**: 悬浮窗控制，录制背屏画面并保存到Movies文件夹（持续keycode唤醒）
- ⚡ **充电动画**: 插电时在背屏显示精美的充电动画（3D闪电+流动液体效果）
- 📢 **通知推送**: 收到通知时自动在背屏显示通知内容（支持应用选择+隐私模式+动态重载）
- 🔓 **后台可用**: 即使应用在后台也能正常触发切换
- 🚀 **无需ROOT**: 基于Shizuku实现，无需ROOT权限
- 🎨 **精致UI**: Material 3设计，四色渐变UI，精确2.84超椭圆圆角
- 🛡️ **智能保护**: 防止系统Launcher覆盖投射的应用
- 💡 **背屏常亮**: 可选的背屏常亮功能，防止自动息屏
- 🔄 **智能监控**: 背屏应用退出或切换时自动清除通知
- 📱 **DPI调整**: 支持动态调整背屏DPI，优化显示效果（推荐260-350）
- 🤚 **背屏遮盖检测**: 可选的接近传感器检测功能
- 🔄 **背屏旋转**: 支持背屏0°/90°/180°/270°旋转，应用自动复活
- 🚫 **任务隐藏**: 主应用不在最近任务列表显示，防止误清理
- 🌐 **URI调用**: 支持通过mrss://协议从外部应用控制MRSS（Tasker/MacroDroid等）
- 🔔 **智能通知**: 支持跟随系统勿扰模式和仅在锁屏时通知
- 🎬 **媒体库集成**: 截图和录制自动刷新到相册，方便查看

## 📋 使用前提

1. **设备要求**: 支持背屏的小米手机（小米17Pro/17Pro Max等双屏设备）
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
   - **背屏录制**: 录制背屏画面（可选）
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

**录制背屏画面：**
1. 下拉控制中心
2. 点击"**背屏录制**"快捷开关
3. 悬浮窗出现，点击红色圆形按钮开始录制
4. 录制中按钮变为方形，再次点击停止录制
5. 视频保存到Movies/MRSS_*.mp4

**返回主屏：**
- 方法1: 点击通知"点击将应用切换回主屏"
- 方法2: 在背屏退出应用，通知自动消失

**充电动画和通知：**
- 充电动画: 插电时自动显示，可在应用内关闭
- 通知推送: 在应用内启用，选择需要推送的应用，支持隐私模式和勿扰模式跟随

**调整背屏显示：**
- DPI调整: 在应用内设置，推荐260-350
- 旋转控制: 支持0°/90°/180°/270°旋转
- 背屏常亮: 可在应用内开关
- 背屏遮盖检测: 可在应用内开关（基于接近传感器）

**💡 提示**: 
- 即使MRSS应用在后台或已关闭，快捷开关依然可以正常使用！
- MRSS不会出现在最近任务列表，避免误清理
- 背屏常亮功能可在应用内开关
- 应用退出或切换时，通知会自动清除
- 充电动画和通知推送都可独立开关
- 支持通过URI调用（mrss://switch?current=1等）

## 🔧 技术实现

- **Flutter**: 跨平台UI框架，Material 3设计，四色渐变+精确超椭圆圆角
- **Shizuku**: 提供shell权限执行特权操作
- **Quick Settings Tile**: Android系统级快捷开关服务（切换/截图/录屏）
- **ActivityTaskManager**: 通过system service调用实现显示切换
- **Foreground Service + WakeLock**: 前台服务持有唤醒锁，可选的背屏常亮
- **NotificationListenerService**: 系统通知监听，实时推送到背屏
- **Keycode Wakeup**: 使用input keyevent KEYCODE_WAKEUP精确唤醒背屏
- **Media Scanner**: 自动刷新媒体库，截图和录制自动出现在相册
- **Dynamic Animation Reload**: 通知动画动态重载机制，支持连续通知
- **Rear Animation Manager**: 统一管理充电动画和通知动画，实现动画打断
- **智能监控**: 每2秒检测背屏前台应用，自动清除无效通知
- **充电监听**: BroadcastReceiver监听充电事件，触发背屏动画
- **3D动画**: 自定义Canvas绘制，非线性动画，重力感应液体效果
- **Screenshot & Record**: screencap截图 + screenrecord录屏
- **URI Protocol**: 支持mrss://协议外部调用

## 📝 权限说明

- `moe.shizuku.manager.permission.API_V23`: Shizuku API权限，用于执行特权操作
- `android.permission.WAKE_LOCK`: 保持背屏常亮
- `android.permission.FOREGROUND_SERVICE`: 前台服务权限
- `android.permission.POST_NOTIFICATIONS`: 通知权限（Android 13+）
- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE`: 通知监听服务（可选）
- `android.permission.SYSTEM_ALERT_WINDOW`: 悬浮窗权限（录屏功能需要）
- `android.permission.QUERY_ALL_PACKAGES`: 获取应用列表（通知功能需要）
- 系统广播接收: 监听`ACTION_POWER_CONNECTED/DISCONNECTED`充电事件

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

### V3.0 核心功能

1. **智能应用切换** 🎯
   - 通过Quick Settings Tile快捷开关触发
   - 使用`am stack`命令获取前台应用
   - 调用`service call activity_task 50`切换显示屏
   - 自动杀死系统Launcher防止挤占
   - Toast提示显示具体应用名

2. **充电动画** ⚡
   - 插电时自动在背屏显示充电动画
   - 3D玻璃闪电容器 + 流动绿色液体
   - 重力感应真实液体效果
   - 非线性动画，从0%填充到当前电量
   - 可在应用内开关

3. **通知推送** 📢
   - NotificationListenerService监听系统通知
   - 选择需要推送的应用（支持搜索、批量选择）
   - 通知内容显示在背屏（应用图标+标题+内容）
   - 隐私模式：隐藏通知具体内容
   - 精美动画：图标缩放+内容淡入

4. **背屏录屏** 📹
   - 悬浮窗控制，红色圆形/方形按钮
   - 使用screenrecord录制背屏画面
   - 持续唤醒背屏防止录制中断
   - 视频保存到Movies/MRSS_*.mp4
   - 可拖动悬浮窗位置

5. **前台Service保活** 🛡️
   - 统一的"MRSS内核服务"前台通知
   - 可选的背屏常亮功能（FLAG_KEEP_SCREEN_ON）
   - 点击通知可快速返回主屏
   - 主应用隐藏于最近任务，防止误清理

6. **智能监控与清理** 🔄
   - 每2秒检测背屏前台应用状态
   - 应用退出或切换时自动停止服务并清除通知
   - 防止多应用同时投放

7. **DPI动态调整** 📱
   - 支持实时调整背屏显示密度
   - 使用`wm density`命令修改
   - 一键还原默认设置
   - 推荐范围: 260-350

8. **背屏旋转控制** 🔄
   - 支持4个方向: 0°/90°/180°/270°
   - 使用`wm user-rotation -d 1`独立控制背屏
   - 旋转后应用自动复活
   - 实时显示当前旋转状态

9. **背屏遮盖检测** 🤚
   - 可选的接近传感器检测功能
   - 应用内可开关
   
10. **URI协议支持** 🌐
   - mrss://switch?current=1 - 切换当前应用
   - mrss://switch?packageName=xxx - 切换指定应用
   - mrss://return?current=1 - 返回主屏
   - mrss://screenshot - 截图
   - mrss://config?dpi=xxx&rotation=x - 配置

### V3.0 新增特性

- ✅ **充电动画**: 3D闪电容器 + 重力感应液体
- ✅ **通知推送**: 系统通知实时显示到背屏
- ✅ **背屏录屏**: 悬浮窗控制录制功能
- ✅ **URI调用**: 支持外部应用控制（Tasker等）
- ✅ **精美UI**: 四色渐变 + 超椭圆圆角设计
- ✅ **代码优化**: 移除未使用代码，修复乱码问题

### 性能优化

- ✅ 代码混淆（ProGuard/R8）
- ✅ 资源压缩
- ✅ 只包含arm64-v8a架构
- ✅ APK体积优化

## 📄 许可证

**协议变更说明**：
- **V3.0.0及以后版本**：GPL-3.0 License
- **V3.0.0以前版本**：仍受MIT License保护

详见 [LICENSE](LICENSE) 文件

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

---

## 📜 版权声明

### 图标归属

本应用使用的图标及品牌标识归属如下：

1. **应用图标**：本应用图标直接使用了小米HyperOS系统中的图标资源。根据[小米操作系统用户协议](https://terms.miui.com/doc/eula/cn.html)，小米、MIUI、Xiaomi HyperOS等商标及相关图标的版权归小米科技有限责任公司所有。本应用仅为第三方开发的辅助工具，与小米官方无关，如有侵权请联系删除。

2. **酷安图标**：应用内使用的酷安图标归酷安（北京酷安网络科技有限公司）所有。根据[酷安用户协议](https://m.coolapk.com/mp/user/agreement)，酷安的商标、图标等知识产权归其所有。本应用使用酷安图标仅用于跳转链接标识，不代表与酷安有任何官方合作关系。

### 免责声明

本应用为开源项目，基于Shizuku实现背屏功能扩展，仅供学习交流使用。使用本应用即表示您理解并同意：
- 本应用非小米官方应用，与小米公司无任何关联
- 使用本应用的风险由用户自行承担
- 开发者不对使用本应用造成的任何损失负责
- 如有侵权，请联系删除

---


