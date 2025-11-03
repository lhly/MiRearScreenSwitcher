/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 * 
 * Chief Tester: 汐木泽
 * 
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'dart:ui';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  // 设置沉浸式状态栏（透明状态栏）
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Colors.transparent,
    statusBarIconBrightness: Brightness.light,
    systemNavigationBarColor: Colors.transparent,
    systemNavigationBarIconBrightness: Brightness.light,
  ));
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  
  runApp(const DisplaySwitcherApp());
}

class DisplaySwitcherApp extends StatelessWidget {
  const DisplaySwitcherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MRSS',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFFFF9D88)),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const platform = MethodChannel('com.display.switcher/task');
  
  bool _shizukuRunning = false;
  String _statusMessage = '正在检查Shizuku...';
  bool _isLoading = false;
  bool _hasError = false;  // 是否有错误
  String _errorDetail = '';  // 错误详情
  
  // V15: 背屏DPI相关
  int _currentRearDpi = 0;
  bool _dpiLoading = true;  // DPI加载状态
  final TextEditingController _dpiController = TextEditingController();
  final FocusNode _dpiFocusNode = FocusNode();
  
  // V2.1: 显示控制相关
  int _currentRotation = 0;  // 当前旋转方向 (0=0°, 1=90°, 2=180°, 3=270°)
  
  // V2.2: 接近传感器开关
  bool _proximitySensorEnabled = true;  // 默认打开
  
  // V2.3: 充电动画开关
  bool _chargingAnimationEnabled = true;  // 默认打开
  
  // V2.5: 背屏常亮开关
  bool _keepScreenOnEnabled = true;  // 默认打开
  
  // V3.5: 未投放应用时常亮开关（与背屏常亮互斥）
  bool _alwaysWakeUpEnabled = false;  // 默认关闭
  
  // V3.5: 充电动画常亮开关
  bool _chargingAlwaysOnEnabled = false;  // 默认关闭
  
  // V2.4: 通知功能
  bool _notificationEnabled = false;  // 默认关闭（需要授权）
  bool _notificationDarkMode = false;  // 通知暗夜模式（默认关闭）
  
  @override
  void initState() {
    super.initState();
    _checkShizuku();
    _loadSettings();  // 加载所有设置
    _setupMethodCallHandler();
    _loadProximitySensorSetting();  // 加载接近传感器设置
    
    // 通知权限会在Shizuku授权完成后自动请求（见_checkShizuku）
    
    // 延迟获取DPI和旋转，等待TaskService连接
    Future.delayed(const Duration(seconds: 2), () {
      _getCurrentRearDpi();
      _getCurrentRotation();
    });
  }
  
  @override
  void dispose() {
    _dpiController.dispose();
    _dpiFocusNode.dispose();
    super.dispose();
  }
  
  void _setupMethodCallHandler() {
    platform.setMethodCallHandler((call) async {
      if (call.method == 'onShizukuPermissionChanged') {
        final granted = call.arguments as bool;
        print('Shizuku permission changed: $granted');
        // 刷新状态
        await _checkShizuku();
        
        // Shizuku授权完成后，立即请求通知权限
        if (granted) {
          print('✓ Shizuku已授权，立即请求通知权限');
          _requestNotificationPermission();
        }
      }
    });
  }
  
  Future<void> _requestNotificationPermission() async {
    // Android 13+ 需要请求通知权限
    try {
      await platform.invokeMethod('requestNotificationPermission');
      print('通知权限请求已发送');
    } catch (e) {
      print('请求通知权限失败: $e');
    }
  }
  
  // V15: 获取当前背屏DPI
  Future<void> _getCurrentRearDpi() async {
    setState(() {
      _dpiLoading = true;
    });
    
    // 最多重试5次，每次间隔1秒
    for (int i = 0; i < 5; i++) {
      try {
        final int dpi = await platform.invokeMethod('getCurrentRearDpi');
        setState(() {
          _currentRearDpi = dpi;
          _dpiController.text = dpi.toString();
          _dpiLoading = false;
        });
        print('当前背屏DPI: $dpi');
        return; // 成功就退出
      } catch (e) {
        print('获取背屏DPI失败 (尝试 ${i + 1}/5): $e');
        if (i < 4) {
          await Future.delayed(const Duration(seconds: 1));
        }
      }
    }
    
    // 所有重试都失败
    setState(() {
      _dpiLoading = false;
      _currentRearDpi = 0;
    });
    print('获取背屏DPI最终失败');
  }
  
  // V15: 设置背屏DPI
  Future<void> _setRearDpi(int dpi) async {
    if (_isLoading) return;
    
    setState(() {
      _isLoading = true;
    });
    
    try {
      // 先尝试重新连接TaskService，确保连接正常
      await platform.invokeMethod('ensureTaskServiceConnected');
      
      // 等待连接建立
      await Future.delayed(const Duration(milliseconds: 500));
      
      await platform.invokeMethod('setRearDpi', {'dpi': dpi});
      
      // 刷新当前DPI
      await _getCurrentRearDpi();
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('背屏DPI已设置为 $dpi')),
        );
      }
    } catch (e) {
      print('设置背屏DPI失败: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('设置失败: $e\n请确保Shizuku正在运行')),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }
  
  // V15: 还原背屏DPI
  Future<void> _resetRearDpi() async {
    if (_isLoading) return;
    
    setState(() {
      _isLoading = true;
    });
    
    try {
      // 先尝试重新连接TaskService，确保连接正常
      await platform.invokeMethod('ensureTaskServiceConnected');
      
      // 等待连接建立
      await Future.delayed(const Duration(milliseconds: 500));
      
      await platform.invokeMethod('resetRearDpi');
      
      // 刷新当前DPI
      await _getCurrentRearDpi();
      
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('背屏DPI已还原')),
        );
      }
    } catch (e) {
      print('还原背屏DPI失败: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('还原失败: $e\n请确保Shizuku正在运行')),
        );
      }
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }
  
  Future<void> _checkShizuku() async {
    setState(() {
      _statusMessage = '正在检查系统权限...';
      _hasError = false;
      _errorDetail = '';
    });
    
    try {
      // 简化检查：直接调用Java层
      final result = await platform.invokeMethod('checkShizuku');
      
      setState(() {
        _shizukuRunning = result == true;
        _hasError = false;
        _errorDetail = '';
        
        if (_shizukuRunning) {
          _statusMessage = '一切就绪';
          
          // Shizuku已授权，立即请求通知权限
          print('✓ Shizuku已授权，立即请求通知权限');
          _requestNotificationPermission();
        } else {
          _hasError = true;
          _statusMessage = '权限不足';
          _errorDetail = 'Shizuku未运行或未授权';
          // 获取详细信息帮助诊断
          _getDetailedStatus();
        }
      });
    } catch (e) {
      // 解析异常类型
      String errorType = '未知错误';
      String errorMsg = e.toString();
      
      if (errorMsg.contains('binder') || errorMsg.contains('Binder')) {
        errorType = 'Shizuku通信异常';
        _errorDetail = 'Shizuku服务可能已崩溃\n请重启Shizuku应用';
      } else if (errorMsg.contains('permission') || errorMsg.contains('Permission')) {
        errorType = '权限不足';
        _errorDetail = '请在Shizuku中授权MRSS';
      } else if (errorMsg.contains('RemoteException')) {
        errorType = '服务调用失败';
        _errorDetail = 'TaskService无响应\n请重启应用';
      } else {
        errorType = '未知错误';
        _errorDetail = errorMsg.length > 50 ? errorMsg.substring(0, 50) + '...' : errorMsg;
      }
      
      setState(() {
        _shizukuRunning = false;
        _hasError = true;
        _statusMessage = errorType;
      });
    }
  }
  
  Future<void> _getDetailedStatus() async {
    try {
      final info = await platform.invokeMethod('getShizukuInfo');
      setState(() {
        _errorDetail = info.toString();
      });
    } catch (e) {
      // 获取详细信息失败，保持当前错误信息
    }
  }
  
  
  
  // V2.1: 重启应用
  Future<void> _restartApp() async {
    if (_isLoading) return;
    
    setState(() => _isLoading = true);
    
    try {
      // 确保TaskService连接
      await platform.invokeMethod('ensureTaskServiceConnected');
      await Future.delayed(const Duration(milliseconds: 500));
      
      // 检查是否有应用在背屏
      final result = await platform.invokeMethod('returnRearAppAndRestart');
      
      if (result == true) {
        // 成功返回主屏，退出应用
        SystemNavigator.pop();
      } else {
        // 没有应用在背屏，直接退出
        SystemNavigator.pop();
      }
    } catch (e) {
      // 出错也退出
      SystemNavigator.pop();
    }
  }
  
  // V2.2: 加载所有设置
  Future<void> _loadSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled = prefs.getBool('proximity_sensor_enabled') ?? true;
        _chargingAnimationEnabled = prefs.getBool('charging_animation_enabled') ?? true;
        _chargingAlwaysOnEnabled = prefs.getBool('charging_always_on_enabled') ?? false;  // V3.5: 加载充电动画常亮开关状态
        _keepScreenOnEnabled = prefs.getBool('keep_screen_on_enabled') ?? true;
        _alwaysWakeUpEnabled = prefs.getBool('always_wakeup_enabled') ?? false;  // V3.5: 加载未投放应用时常亮开关状态
        _notificationDarkMode = prefs.getBool('notification_dark_mode') ?? false;
        _notificationEnabled = prefs.getBool('notification_service_enabled') ?? false;  // V2.4: 加载背屏通知开关状态
      });
      
      // 启动充电服务（如果开关打开）
      if (_chargingAnimationEnabled) {
        _startChargingService();
      }
      
      // 检查通知监听权限（但不覆盖开关状态）
      _checkNotificationPermission();
      
      // V2.4: 如果通知开关开启，启动NotificationService
      if (_notificationEnabled) {
        _startNotificationService();
      }
    } catch (e) {
      print('加载设置失败: $e');
    }
  }
  
  // V2.2: 加载接近传感器设置
  Future<void> _loadProximitySensorSetting() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _proximitySensorEnabled = prefs.getBool('proximity_sensor_enabled') ?? true;
      });
    } catch (e) {
      print('加载接近传感器设置失败: $e');
    }
  }
  
  
  // V2.4: 检查通知监听权限
  Future<void> _checkNotificationPermission() async {
    try {
      final bool hasPermission = await platform.invokeMethod('checkNotificationListenerPermission');
      // 只更新权限状态，不覆盖开关状态
      // _notificationEnabled 现在由 SharedPreferences 中的开关状态控制
      print('通知监听权限状态: $hasPermission');
    } catch (e) {
      print('检查通知权限失败: $e');
    }
  }
  
  // V2.4: 启动通知服务
  Future<void> _startNotificationService() async {
    try {
      await platform.invokeMethod('startNotificationService');
      print('NotificationService已启动');
    } catch (e) {
      print('启动NotificationService失败: $e');
    }
  }
  
  // V2.4: 切换通知服务
  Future<void> _toggleNotificationService(bool enabled) async {
    if (enabled) {
      // 先检查权限
      final bool hasPermission = await platform.invokeMethod('checkNotificationListenerPermission');
      if (!hasPermission) {
        // 打开设置页面授权
        await platform.invokeMethod('openNotificationListenerSettings');
        return;
      }
    }
    
    try {
      // 先保存到SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_service_enabled', enabled);
      
      // 通知Service更新状态
      await platform.invokeMethod('toggleNotificationService', {'enabled': enabled});
      
      // 如果开启，启动NotificationService
      if (enabled) {
        await _startNotificationService();
      }
      
      setState(() {
        _notificationEnabled = enabled;
      });
      print('背屏通知服务已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换背屏通知服务失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _notificationEnabled = !enabled;
      });
    }
  }
  
  
  // V2.4: 打开应用选择页面
  Future<void> _openAppSelectionPage() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const AppSelectionPage()),
    );
  }
  
  
  // V2.2: 切换接近传感器开关
  Future<void> _toggleProximitySensor(bool enabled) async {
    try {
      // 先保存到SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('proximity_sensor_enabled', enabled);
      
      // 通知Service更新状态
      await platform.invokeMethod('setProximitySensorEnabled', {'enabled': enabled});
      
      setState(() {
        _proximitySensorEnabled = enabled;
      });
      print('接近传感器已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换接近传感器失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _proximitySensorEnabled = !enabled;
      });
    }
  }
  
  // V2.3: 切换充电动画开关
  Future<void> _toggleChargingAnimation(bool enabled) async {
    try {
      // 先保存到SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('charging_animation_enabled', enabled);
      
      // 启动或停止充电服务
      await platform.invokeMethod('toggleChargingService', {'enabled': enabled});
      
      setState(() {
        _chargingAnimationEnabled = enabled;
      });
      print('充电动画已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换充电动画失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _chargingAnimationEnabled = !enabled;
      });
    }
  }
  
  // V2.3: 启动充电服务
  Future<void> _startChargingService() async {
    try {
      await platform.invokeMethod('toggleChargingService', {'enabled': true});
    } catch (e) {
      print('启动充电服务失败: $e');
    }
  }
  
  // V2.5: 切换背屏常亮开关
  Future<void> _toggleKeepScreenOn(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('keep_screen_on_enabled', enabled);
      
      // V3.5: 如果开启，则关闭未投放应用时常亮
      if (enabled && _alwaysWakeUpEnabled) {
        await prefs.setBool('always_wakeup_enabled', false);
        await platform.invokeMethod('setAlwaysWakeUpEnabled', {'enabled': false});
      }
      
      // 通过Intent通知RearScreenKeeperService
      await platform.invokeMethod('setKeepScreenOnEnabled', {'enabled': enabled});
      
      setState(() {
        _keepScreenOnEnabled = enabled;
        if (enabled) _alwaysWakeUpEnabled = false;  // V3.5: 互斥关闭
      });
      print('背屏常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换背屏常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _keepScreenOnEnabled = !enabled;
      });
    }
  }
  
  // V3.5: 切换未投放应用时常亮开关
  Future<void> _toggleAlwaysWakeUp(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('always_wakeup_enabled', enabled);
      
      // V3.5: 如果开启，则关闭背屏常亮
      if (enabled && _keepScreenOnEnabled) {
        await prefs.setBool('keep_screen_on_enabled', false);
        await platform.invokeMethod('setKeepScreenOnEnabled', {'enabled': false});
      }
      
      // 通过Intent通知AlwaysWakeUpService
      await platform.invokeMethod('setAlwaysWakeUpEnabled', {'enabled': enabled});
      
      setState(() {
        _alwaysWakeUpEnabled = enabled;
        if (enabled) _keepScreenOnEnabled = false;  // V3.5: 互斥关闭
      });
      print('未投放应用时常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换未投放应用时常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _alwaysWakeUpEnabled = !enabled;
      });
    }
  }
  
  // V3.5: 切换充电动画常亮开关
  Future<void> _toggleChargingAlwaysOn(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('charging_always_on_enabled', enabled);
      
      // 通过Intent通知ChargingAlwaysOnService
      await platform.invokeMethod('setChargingAlwaysOnEnabled', {'enabled': enabled});
      
      setState(() {
        _chargingAlwaysOnEnabled = enabled;
      });
      print('充电动画常亮已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换充电动画常亮失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _chargingAlwaysOnEnabled = !enabled;
      });
    }
  }
  
  // V3.1: 通知暗夜模式开关
  Future<void> _toggleNotificationDarkMode(bool enabled) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_dark_mode', enabled);
      
      // 通过Intent通知NotificationService
      await platform.invokeMethod('setNotificationDarkMode', {'enabled': enabled});
      
      setState(() {
        _notificationDarkMode = enabled;
      });
      print('通知暗夜模式已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换通知暗夜模式失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _notificationDarkMode = !enabled;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        title: const Text('MRSS', style: TextStyle(fontWeight: FontWeight.bold)),
        actions: [
          IconButton(
            icon: const Icon(Icons.restart_alt),
            onPressed: _restartApp,
            tooltip: '重启软件',
          ),
        ],
      ),
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88),  // 珊瑚橙
              Color(0xFFFFB5C5),  // 粉红
              Color(0xFFE0B5DC),  // 紫色
              Color(0xFFA8C5E5),  // 蓝色
            ],
          ),
        ),
        child: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            physics: const BouncingScrollPhysics(), // 始终允许滑动
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
            // 整合后的状态和权限卡片（毛玻璃效果）
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
              child: BackdropFilter(
                filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                child: Container(
                  decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        padding: const EdgeInsets.all(16),
                  child: Column(
                        children: [
                          Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Icon(
                                _shizukuRunning ? Icons.check_circle : (_hasError ? Icons.error_outline : Icons.warning_rounded),
                                size: 28,
                                color: _shizukuRunning ? Colors.green : (_hasError ? Colors.red : Colors.orange),
                              ),
                              const SizedBox(width: 10),
                      Text(
                                _shizukuRunning ? 'Shizuku 运行中' : _statusMessage,
                                style: const TextStyle(
                                  fontSize: 16,
                          color: Colors.black87,
                                  fontWeight: FontWeight.w500,
                        ),
                      ),
                            ],
                          ),
                          if (_hasError && _errorDetail.isNotEmpty) ...[
                            const SizedBox(height: 8),
                      Text(
                              _errorDetail,
                              style: const TextStyle(
                                fontSize: 12,
                                color: Colors.black54,
                                height: 1.3,
                              ),
                        textAlign: TextAlign.center,
                        ),
                      ],
                    ],
                      ),
                  ),
                ),
              ),
            ),
                  
                const SizedBox(height: 20),
                  
                  // V15: 背屏DPI调整卡片
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                    child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                        ),
                        padding: const EdgeInsets.all(20),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Text(
                                  '背屏DPI调整',
                                  style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                    color: Colors.black87,
                                    fontWeight: FontWeight.bold,
                                  ),
                                ),
                                if (_dpiLoading) ...[
                                  const SizedBox(width: 12),
                                  const SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(
                                      strokeWidth: 2,
                                      valueColor: AlwaysStoppedAnimation<Color>(Colors.black54),
                                    ),
                                  ),
                                ],
                              ],
                            ),
                            const SizedBox(height: 8),
                            Text(
                              _dpiLoading ? '正在获取当前DPI...' : '当前DPI: $_currentRearDpi  推荐范围: 260-350',
                              style: const TextStyle(
                                color: Colors.black54,
                                fontSize: 14,
                              ),
                            ),
                            const SizedBox(height: 16),
                            Row(
                              children: [
                                Expanded(
                                  child: TextField(
                                    controller: _dpiController,
                                    focusNode: _dpiFocusNode,
                                    enabled: !_dpiLoading && !_isLoading,
                                    keyboardType: TextInputType.number,
                                    style: const TextStyle(color: Colors.black87),
                                    decoration: const InputDecoration(
                                      labelText: '新DPI值',
                                      labelStyle: TextStyle(color: Colors.black54),
                                      hintText: '输入数字',
                                      hintStyle: TextStyle(color: Colors.black38),
                                      border: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                        borderSide: BorderSide(color: Colors.black26),
                                      ),
                                      enabledBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                        borderSide: BorderSide(color: Colors.black26),
                                      ),
                                      focusedBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                        borderSide: BorderSide(color: Colors.black54, width: 2),
                                      ),
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 12),
                                ClipPath(
                                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
                                  child: Container(
                                    decoration: const BoxDecoration(
                                      gradient: LinearGradient(
                                        begin: Alignment.topLeft,
                                        end: Alignment.bottomRight,
                                        colors: [
                                          Color(0xFFFF9D88),  // 珊瑚橙
                                          Color(0xFFFFB5C5),  // 粉红
                                          Color(0xFFE0B5DC),  // 紫色
                                          Color(0xFFA8C5E5),  // 蓝色
                                        ],
                                      ),
                                    ),
                                    child: ElevatedButton(
                                  onPressed: (_isLoading || _dpiLoading) ? null : () {
                                    final dpi = int.tryParse(_dpiController.text);
                                    if (dpi != null && dpi > 0) {
                                      _setRearDpi(dpi);
                                    } else {
                                      ScaffoldMessenger.of(context).showSnackBar(
                                        const SnackBar(content: Text('请输入有效的DPI值')),
                                      );
                                    }
                                  },
                                  style: ElevatedButton.styleFrom(
                                        backgroundColor: Colors.transparent,
                                    foregroundColor: Colors.white,
                                        shadowColor: Colors.transparent,
                                    padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                                        shape: RoundedRectangleBorder(
                                          borderRadius: BorderRadius.circular(_SquircleRadii.small),
                                        ),
                                  ),
                                  child: const Text('设置'),
                                    ),
                                  ),
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            SizedBox(
                              width: double.infinity,
                              child: CustomPaint(
                                painter: _SquircleBorderPainter(
                                  radius: _SquircleRadii.small,
                                  color: Colors.black26,
                                  strokeWidth: 1,
                                ),
                                child: ClipPath(
                                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
                                  child: Material(
                                    color: Colors.transparent,
                                    child: InkWell(
                                      onTap: (_isLoading || _dpiLoading) ? null : _resetRearDpi,
                                      child: const Padding(
                                        padding: EdgeInsets.symmetric(vertical: 12),
                                        child: Row(
                                          mainAxisAlignment: MainAxisAlignment.center,
                                          children: [
                                            Icon(Icons.restore, color: Colors.black87, size: 20),
                                            SizedBox(width: 8),
                                            Text(
                                              '还原默认DPI',
                                              style: TextStyle(color: Colors.black87, fontSize: 14),
                                            ),
                                          ],
                                        ),
                                      ),
                                    ),
                                  ),
                                ),
                              ),
                            ),
                            
                            const SizedBox(height: 16),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 16),
                            
                            // V2.1: 旋转控制
                            Row(
                              children: [
                                const Text(
                                  '🔄 旋转',
                                  style: TextStyle(fontSize: 14, color: Colors.black87, fontWeight: FontWeight.w500),
                                ),
                                const Spacer(),
                                _buildRotationButton('0°', 0),
                                const SizedBox(width: 6),
                                _buildRotationButton('90°', 1),
                                const SizedBox(width: 6),
                                _buildRotationButton('180°', 2),
                                const SizedBox(width: 6),
                                _buildRotationButton('270°', 3),
                              ],
                            ),
                            
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
                  
                const SizedBox(height: 20),
                
                // V2.2: 背屏遮盖检测卡片（独立）
                Stack(
                  children: [
                    CustomPaint(
                      painter: _SquircleBorderPainter(
                        radius: _SquircleRadii.large,
                        color: Colors.white.withOpacity(0.5),
                        strokeWidth: 1.5,
                      ),
                      child: ClipPath(
                        clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                        child: BackdropFilter(
                          filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                          child: Container(
                            padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            child: Row(
                          children: [
                            const Text(
                              '🤚 背屏遮盖检测',
                              style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87),
                            ),
                            const Spacer(),
                            _GradientToggle(
                              value: _proximitySensorEnabled,
                              onChanged: _toggleProximitySensor,
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                  ],
                ),
                  
                const SizedBox(height: 20),
                
                // V2.5: 背屏常亮卡片
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 背屏常亮开关
                            Row(
                              children: [
                                const Text(
                                  '🔆 背屏常亮',
                                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _keepScreenOnEnabled,
                                  onChanged: _toggleKeepScreenOn,
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // 未投放应用时常亮开关
                            Row(
                              children: [
                                const Text(
                                  '💡 未投放应用时常亮',
                                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _alwaysWakeUpEnabled,
                                  onChanged: _toggleAlwaysWakeUp,
                                ),
                              ],
                            ),
                            if (_alwaysWakeUpEnabled) ...[
                              const SizedBox(height: 12),
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.orange.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(_SquircleRadii.small),
                                  border: Border.all(color: Colors.orange.withOpacity(0.4), width: 1),
                                ),
                                child: const Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        '警告：可能导致烧屏和额外耗电',
                                        style: TextStyle(fontSize: 12, color: Colors.black87),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                  
                const SizedBox(height: 20),
                
                // V2.3: 充电动画卡片（独立）
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 充电动画开关
                            Row(
                              children: [
                                const Text(
                                  '⚡ 充电动画',
                                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _chargingAnimationEnabled,
                                  onChanged: _toggleChargingAnimation,
                                ),
                              ],
                            ),
                            const SizedBox(height: 12),
                            const Divider(color: Colors.black26, height: 1),
                            const SizedBox(height: 12),
                            // 充电动画常亮开关
                            Row(
                              children: [
                                const Text(
                                  '💡 充电动画常亮',
                                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87),
                                ),
                                const Spacer(),
                                _GradientToggle(
                                  value: _chargingAlwaysOnEnabled,
                                  onChanged: _toggleChargingAlwaysOn,
                                ),
                              ],
                            ),
                            if (_chargingAlwaysOnEnabled) ...[
                              const SizedBox(height: 12),
                              Container(
                                padding: const EdgeInsets.all(12),
                                decoration: BoxDecoration(
                                  color: Colors.orange.withOpacity(0.2),
                                  borderRadius: BorderRadius.circular(_SquircleRadii.small),
                                  border: Border.all(color: Colors.orange.withOpacity(0.4), width: 1),
                                ),
                                child: const Row(
                                  children: [
                                    Expanded(
                                      child: Text(
                                        '警告：可能导致烧屏和额外耗电',
                                        style: TextStyle(fontSize: 12, color: Colors.black87),
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                  
                const SizedBox(height: 20),
                
                // V2.4: 通知功能卡片
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.25),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            // 标题行
                            Row(
                              children: [
                                const Text(
                                  '📢 背屏通知',
                                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: Colors.black87),
                                ),
                                const Spacer(),
                                // 三条杠按钮（选择应用）
                                IconButton(
                                  icon: const Icon(Icons.menu, size: 24),
                                  color: Colors.black87,
                                  onPressed: _openAppSelectionPage,
                                  tooltip: '选择应用',
                                  padding: EdgeInsets.zero,
                                  constraints: const BoxConstraints(),
                                ),
                                const SizedBox(width: 8),
                                _GradientToggle(
                                  value: _notificationEnabled,
                                  onChanged: _toggleNotificationService,
                                ),
                              ],
                            ),
                            
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                  
                const SizedBox(height: 20),
                
                // 使用教程 - 可点击跳转到酷安帖子
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: () async {
                            // 跳转到腾讯文档使用教程
                            try {
                              await platform.invokeMethod('openTutorial');
                            } catch (e) {
                              print('打开教程失败: $e');
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(content: Text('打开失败')),
                                );
                              }
                            }
                          },
                          splashColor: Colors.white.withOpacity(0.3),
                          highlightColor: Colors.white.withOpacity(0.2),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Text(
                                '📖',
                                style: TextStyle(fontSize: 20),
                              ),
                              const SizedBox(width: 8),
                              const Text(
                                '使用教程',
                                style: TextStyle(
                                  color: Colors.black87,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const SizedBox(width: 4),
                              Icon(
                                Icons.open_in_new,
                                size: 16,
                                color: Colors.black54,
                              ),
                            ],
                          ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                  
                const SizedBox(height: 16),
                
                // 底部作者信息 - 可点击跳转到酷安
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: () async {
                            // 跳转到酷安个人主页
                            try {
                              await platform.invokeMethod('openCoolApkProfile');
                            } catch (e) {
                              print('打开酷安主页失败: $e');
                              if (context.mounted) {
                                ScaffoldMessenger.of(context).showSnackBar(
                                  const SnackBar(content: Text('请先安装酷安应用')),
                                );
                              }
                            }
                          },
                          splashColor: Colors.white.withOpacity(0.3),
                          highlightColor: Colors.white.withOpacity(0.2),
                          child: Container(
                            decoration: BoxDecoration(
                              color: Colors.white.withOpacity(0.25),
                            ),
                            padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Text(
                                '👨‍💻',
                                style: TextStyle(fontSize: 20),
                              ),
                              const SizedBox(width: 6),
                              Image.asset(
                                'assets/kuan.png',
                                width: 24,
                                height: 24,
                                errorBuilder: (context, error, stackTrace) {
                                  return const Icon(Icons.person, size: 24, color: Colors.black87);
                                },
                              ),
                              const SizedBox(width: 8),
                              const Text(
                                '酷安@AntiOblivionis',
                                style: TextStyle(
                                  color: Colors.black87,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const SizedBox(width: 4),
                              Icon(
                                Icons.open_in_new,
                                size: 16,
                                color: Colors.black54,
                              ),
                            ],
                          ),
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
                  
                const SizedBox(height: 16),
                
                // 团队信息 - 可点击跳转到酷安
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: _SquircleRadii.large,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                        onTap: () async {
                          // 跳转到汐木泽酷安主页
                          try {
                            await platform.invokeMethod('openCoolApkProfileXmz');
                          } catch (e) {
                            print('打开酷安主页失败: $e');
                            if (context.mounted) {
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(content: Text('请先安装酷安应用')),
                              );
                            }
                          }
                        },
                        splashColor: Colors.white.withOpacity(0.3),
                        highlightColor: Colors.white.withOpacity(0.2),
                        child: Container(
                          decoration: BoxDecoration(
                            color: Colors.white.withOpacity(0.25),
                          ),
                          padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Text(
                                '🧪',
                                style: TextStyle(fontSize: 20),
                              ),
                              const SizedBox(width: 6),
                              Image.asset(
                                'assets/kuan.png',
                                width: 24,
                                height: 24,
                                errorBuilder: (context, error, stackTrace) {
                                  return const Icon(Icons.person, size: 24, color: Colors.black87);
                                },
                              ),
                              const SizedBox(width: 8),
                              const Text(
                                '酷安@汐木泽',
                                style: TextStyle(
                                  color: Colors.black87,
                                  fontSize: 14,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                              const SizedBox(width: 4),
                              Icon(
                                Icons.open_in_new,
                                size: 16,
                                color: Colors.black54,
                              ),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ),
                ),
              ),
                  
                const SizedBox(height: 16),
                
                // 打赏和交流群 - 两列布局
                Row(
                  children: [
                    // 请作者喝咖啡
                    Expanded(
                      child: CustomPaint(
                        painter: _SquircleBorderPainter(
                          radius: _SquircleRadii.large,
                          color: Colors.white.withOpacity(0.5),
                          strokeWidth: 1.5,
                        ),
                        child: ClipPath(
                          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Material(
                              color: Colors.transparent,
                              child: InkWell(
                                onTap: () async {
                                  // 打开打赏页面
                                  try {
                                    await platform.invokeMethod('openDonationPage');
                                  } catch (e) {
                                    print('打开打赏页面失败: $e');
                                    if (context.mounted) {
                                      ScaffoldMessenger.of(context).showSnackBar(
                                        const SnackBar(content: Text('打开失败')),
                                      );
                                    }
                                  }
                                },
                                splashColor: Colors.white.withOpacity(0.3),
                                highlightColor: Colors.white.withOpacity(0.2),
                                child: Container(
                                  decoration: BoxDecoration(
                                    color: Colors.white.withOpacity(0.25),
                                  ),
                                  padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 12),
                                  child: const Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Text(
                                        '☕',
                                        style: TextStyle(fontSize: 24),
                                      ),
                                      SizedBox(height: 4),
                                      Text(
                                        '请作者喝咖啡',
                                        style: TextStyle(
                                          color: Colors.black87,
                                          fontSize: 12,
                                          fontWeight: FontWeight.w500,
                                        ),
                                        textAlign: TextAlign.center,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                    
                    const SizedBox(width: 16),
                    
                    // MRSS交流群
                    Expanded(
                      child: CustomPaint(
                        painter: _SquircleBorderPainter(
                          radius: _SquircleRadii.large,
                          color: Colors.white.withOpacity(0.5),
                          strokeWidth: 1.5,
                        ),
                        child: ClipPath(
                          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Material(
                              color: Colors.transparent,
                              child: InkWell(
                                onTap: () async {
                                  // 打开交流群页面
                                  try {
                                    await platform.invokeMethod('openQQGroup');
                                  } catch (e) {
                                    print('打开交流群页面失败: $e');
                                    if (context.mounted) {
                                      ScaffoldMessenger.of(context).showSnackBar(
                                        const SnackBar(content: Text('打开失败')),
                                      );
                                    }
                                  }
                                },
                                splashColor: Colors.white.withOpacity(0.3),
                                highlightColor: Colors.white.withOpacity(0.2),
                                child: Container(
                                  decoration: BoxDecoration(
                                    color: Colors.white.withOpacity(0.25),
                                  ),
                                  padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 12),
                                  child: const Column(
                                    mainAxisSize: MainAxisSize.min,
                                    children: [
                                      Text(
                                        '💬',
                                        style: TextStyle(fontSize: 24),
                                      ),
                                      SizedBox(height: 4),
                                      Text(
                                        'MRSS交流群',
                                        style: TextStyle(
                                          color: Colors.black87,
                                          fontSize: 12,
                                          fontWeight: FontWeight.w500,
                                        ),
                                        textAlign: TextAlign.center,
                                      ),
                                    ],
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
                  
                const SizedBox(height: 20),
              ],
            ),
          ),
        ),
      ),
    );
  }
  
  
  // V2.1: 构建旋转按钮（精确超椭圆，统一12px圆角）
  Widget _buildRotationButton(String label, int rotation) {
    bool isSelected = _currentRotation == rotation;
    
    return SizedBox(
      width: 50,
      height: 32,
       child: ClipPath(
         clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
        child: Container(
          decoration: BoxDecoration(
            gradient: isSelected ? const LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: [
                Color(0xFFFF9D88),  // 珊瑚橙
                Color(0xFFFFB5C5),  // 粉红
                Color(0xFFE0B5DC),  // 紫色
                Color(0xFFA8C5E5),  // 蓝色
              ],
            ) : null,
            color: isSelected ? null : Colors.white70,
          ),
          child: Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: (_isLoading || _dpiLoading) ? null : () => _setRotation(rotation),
              child: Center(
                child: Text(
                  label, 
                  style: TextStyle(
                    fontSize: 12,
                    color: isSelected ? Colors.white : Colors.black54,
                    fontWeight: isSelected ? FontWeight.w500 : FontWeight.normal,
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
  
  // V2.1: 获取当前旋转方向
  Future<void> _getCurrentRotation() async {
    try {
      final rotation = await platform.invokeMethod('getDisplayRotation', {'displayId': 1});
      if (rotation != null && rotation >= 0) {
        setState(() {
          _currentRotation = rotation;
        });
      }
    } catch (e) {
      print('获取旋转方向失败: $e');
    }
  }
  
  // V2.1: 设置旋转方向
  Future<void> _setRotation(int rotation) async {
    print('[Flutter] 🔄 开始设置旋转: $rotation (${rotation * 90}°)');
    
    if (!_shizukuRunning) {
      print('[Flutter] ❌ Shizuku未运行');
      return;
    }
    if (_isLoading) {
      print('[Flutter] ⚠️ 正在加载中，跳过');
      return;
    }
    
    setState(() => _isLoading = true);
    
    try {
      // 确保TaskService连接
      print('[Flutter] 🔗 确保TaskService连接...');
      final connected = await platform.invokeMethod('ensureTaskServiceConnected');
      print('[Flutter] 🔗 TaskService连接状态: $connected');
      await Future.delayed(const Duration(milliseconds: 500));
      
      print('[Flutter] 📡 调用setDisplayRotation: displayId=1, rotation=$rotation');
      final result = await platform.invokeMethod('setDisplayRotation', {
        'displayId': 1,
        'rotation': rotation,
      });
      print('[Flutter] 📡 setDisplayRotation返回: $result');
      
      if (result == true) {
        setState(() => _currentRotation = rotation);
        print('[Flutter] ✅ 旋转成功: ${rotation * 90}°');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('已旋转至 ${rotation * 90}°'), duration: const Duration(seconds: 1)),
          );
        }
      } else {
        print('[Flutter] ❌ 旋转失败: result=$result');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(content: Text('旋转失败')),
          );
        }
      }
    } catch (e) {
      print('[Flutter] ❌ 旋转异常: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('错误: $e')),
        );
      }
    } finally {
      setState(() => _isLoading = false);
      print('[Flutter] 🏁 旋转操作结束');
    }
  }
  
}

// 渐变开关，统一四段渐变样式，替代系统绿色Switch
class _GradientToggle extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;
  const _GradientToggle({required this.value, required this.onChanged});

  @override
  State<_GradientToggle> createState() => _GradientToggleState();
}

class _GradientToggleState extends State<_GradientToggle> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: () => widget.onChanged(!widget.value),
        onHighlightChanged: (h) => setState(() => _pressed = h),
        customBorder: _SquircleShapeBorder(cornerRadius: _SquircleRadii.tiny),
        splashColor: Colors.white.withOpacity(0.2),
        highlightColor: Colors.white.withOpacity(0.1),
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.tiny),
          child: SizedBox(
            width: 52,
            height: 30,
            child: Stack(
              children: [
                // Base background
                Container(color: Colors.white.withOpacity(0.25)),
                // Gradient overlay with fade
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOut,
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // Knob
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
                  child: AnimatedAlign(
                    duration: const Duration(milliseconds: 220),
                    curve: Curves.easeOut,
                    alignment: widget.value ? Alignment.centerRight : Alignment.centerLeft,
                    child: AnimatedScale(
                      duration: const Duration(milliseconds: 120),
                      scale: _pressed ? 0.95 : 1.0,
                      child: Container(
                        width: 22,
                        height: 22,
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(11),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withOpacity(0.15),
                              blurRadius: 3,
                              offset: const Offset(0, 1),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// 应用列表项优化组件（减少重建）
class _AppListItem extends StatelessWidget {
  final String appName;
  final String packageName;
  final Uint8List? iconBytes;
  final bool isSelected;
  final VoidCallback onToggle;

  const _AppListItem({
    required this.appName,
    required this.packageName,
    required this.iconBytes,
    required this.isSelected,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onToggle,
        splashColor: const Color(0x20FFB5C5), // 浅浅的粉红色（四色渐变中间色）
        highlightColor: const Color(0x10E0B5DC), // 浅浅的紫色高光
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          child: Row(
            children: [
              // 图标（全分辨率，不压缩不受损）
              if (iconBytes != null)
                Image.memory(
                  iconBytes!,
                  width: 48,
                  height: 48,
                  fit: BoxFit.contain,
                  gaplessPlayback: true,
                  filterQuality: FilterQuality.high,
                  isAntiAlias: true,
                )
              else
                const Icon(Icons.android, size: 48, color: Colors.white),
              const SizedBox(width: 12),
              // 文本
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Text(
                      appName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(color: Colors.white, fontSize: 15),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      packageName,
                      style: const TextStyle(fontSize: 11, color: Colors.white70),
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              // 渐变复选框
              _GradientCheckbox(value: isSelected, onChanged: (_) => onToggle()),
            ],
          ),
        ),
      ),
    );
  }
}

// 渐变复选框（替代绿色Checkbox）- 带过渡动画
class _GradientCheckbox extends StatefulWidget {
  final bool value;
  final ValueChanged<bool> onChanged;

  const _GradientCheckbox({required this.value, required this.onChanged});

  @override
  State<_GradientCheckbox> createState() => _GradientCheckboxState();
}

class _GradientCheckboxState extends State<_GradientCheckbox> {
  bool _pressed = false;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTapDown: (_) => setState(() => _pressed = true),
      onTapUp: (_) => setState(() => _pressed = false),
      onTapCancel: () => setState(() => _pressed = false),
      onTap: () => widget.onChanged(!widget.value),
      child: AnimatedScale(
        duration: const Duration(milliseconds: 120),
        scale: _pressed ? 0.9 : 1.0,
        child: ClipPath(
          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.checkbox),
          child: Container(
            width: 24,
            height: 24,
            child: Stack(
              children: [
                // 底层半透明背景
                Container(
                  color: Colors.white.withOpacity(0.25),
                ),
                // 渐变层（淡入淡出）
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 1.0 : 0.0,
                  child: Container(
                    decoration: const BoxDecoration(
                      gradient: LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFFFF9D88),
                          Color(0xFFFFB5C5),
                          Color(0xFFE0B5DC),
                          Color(0xFFA8C5E5),
                        ],
                      ),
                    ),
                  ),
                ),
                // 边框（渐隐）- 使用CustomPaint绘制超椭圆边框
                AnimatedOpacity(
                  duration: const Duration(milliseconds: 200),
                  opacity: widget.value ? 0.0 : 1.0,
                  child: CustomPaint(
                    painter: _SquircleBorderPainter(
                      radius: _SquircleRadii.checkbox,
                      color: Colors.white.withOpacity(0.4),
                      strokeWidth: 2,
                    ),
                  ),
                ),
                // 对勾（缩放弹出）
                Center(
                  child: AnimatedScale(
                    duration: const Duration(milliseconds: 200),
                    curve: Curves.easeOutBack,
                    scale: widget.value ? 1.0 : 0.0,
                    child: const Icon(Icons.check, size: 18, color: Colors.white),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// 超椭圆圆角半径
/// 基于屏幕物理圆角半径16.4mm，超椭圆指数n=2.84
/// 使用固定值确保视觉一致性（基于标准DPI 420计算）
class _SquircleRadii {
  // 16.4mm @ 420dpi ≈ 27dp，实际屏幕略大，取32dp
  static const double large = 32.0;  // 大卡片圆角
  static const double small = 12.0;  // 小组件圆角 (large * 0.375)
  static const double tiny = 16.0;   // 开关圆角
  static const double checkbox = 6.0; // 复选框圆角
}

/// 精确的超椭圆（Squircle）形状边框 - 用于InkWell水波纹
/// 使用2.84指数实现与屏幕圆角一致的平滑曲线
class _SquircleShapeBorder extends ShapeBorder {
  final double cornerRadius;
  static const double n = 2.84; // 超椭圆指数
  
  const _SquircleShapeBorder({required this.cornerRadius});
  
  @override
  EdgeInsetsGeometry get dimensions => EdgeInsets.zero;
  
  @override
  Path getInnerPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }
  
  @override
  Path getOuterPath(Rect rect, {TextDirection? textDirection}) {
    return _createSquirclePath(rect.size, cornerRadius);
  }
  
  @override
  void paint(Canvas canvas, Rect rect, {TextDirection? textDirection}) {}
  
  @override
  ShapeBorder scale(double t) => _SquircleShapeBorder(cornerRadius: cornerRadius * t);
  
  static Path _createSquirclePath(Size size, double radius) {
    final double width = size.width;
    final double height = size.height;
    final double effectiveRadius = radius.clamp(0.0, math.min(width, height) / 2);
    
    final path = Path();
    
    // 顶部左侧圆角
    path.moveTo(0, effectiveRadius);
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2;
      final x = effectiveRadius * (1 - math.pow(math.cos(angle).abs(), 2 / n) * (math.cos(angle) >= 0 ? 1 : -1));
      final y = effectiveRadius * (1 - math.pow(math.sin(angle).abs(), 2 / n) * (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }
    
    // 顶边
    path.lineTo(width - effectiveRadius, 0);
    
    // 顶部右侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2;
      final x = width - effectiveRadius * (1 - math.pow(math.cos(angle).abs(), 2 / n) * (math.cos(angle) >= 0 ? 1 : -1));
      final y = effectiveRadius * (1 - math.pow(math.sin(angle).abs(), 2 / n) * (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }
    
    // 右边
    path.lineTo(width, height - effectiveRadius);
    
    // 底部右侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = (1 - t) * math.pi / 2 + math.pi / 2;
      final x = width - effectiveRadius * (1 - math.pow(math.cos(angle).abs(), 2 / n) * (math.cos(angle) >= 0 ? 1 : -1));
      final y = height - effectiveRadius * (1 - math.pow(math.sin(angle).abs(), 2 / n) * (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }
    
    // 底边
    path.lineTo(effectiveRadius, height);
    
    // 底部左侧圆角
    for (double t = 0; t <= 1.0; t += 0.02) {
      final angle = t * math.pi / 2 + math.pi;
      final x = effectiveRadius * (1 - math.pow(math.cos(angle).abs(), 2 / n) * (math.cos(angle) >= 0 ? 1 : -1));
      final y = height - effectiveRadius * (1 - math.pow(math.sin(angle).abs(), 2 / n) * (math.sin(angle) >= 0 ? 1 : -1));
      path.lineTo(x, y);
    }
    
    path.close();
    return path;
  }
}

/// 精确的超椭圆（Squircle）裁剪器
/// 使用2.84指数实现与屏幕圆角一致的平滑曲线
class _SquircleClipper extends CustomClipper<Path> {
  final double cornerRadius;
  static const double n = 2.84; // 超椭圆指数
  
  _SquircleClipper({required this.cornerRadius});
  
  @override
  Path getClip(Size size) {
    return _createSquirclePath(size, cornerRadius);
  }
  
  Path _createSquirclePath(Size size, double radius) {
    final w = size.width;
    final h = size.height;
    final r = radius;
    
    final path = Path();
    
    // 从左上角开始，顺时针绘制
    path.moveTo(0, r);
    
    // 左上角超椭圆
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);
    
    // 上边
    path.lineTo(w - r, 0);
    
    // 右上角超椭圆
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);
    
    // 右边
    path.lineTo(w, h - r);
    
    // 右下角超椭圆
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);
    
    // 下边
    path.lineTo(r, h);
    
    // 左下角超椭圆
    _drawSquircleArc(path, r, h - r, r, math.pi * 0.5, math.pi);
    
    path.close();
    return path;
  }
  
  void _drawSquircleArc(Path path, double cx, double cy, double radius, double startAngle, double endAngle) {
    const int segments = 30;
    
    for (int i = 0; i <= segments; i++) {
      final t = i / segments;
      final angle = startAngle + (endAngle - startAngle) * t;
      
      final cosA = math.cos(angle);
      final sinA = math.sin(angle);
      
      // 超椭圆公式: r * sgn(t) * |t|^(2/n)
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);
      
      path.lineTo(x, y);
    }
  }
  
  double _sgn(double x) => x < 0 ? -1.0 : 1.0;
  
  @override
  bool shouldReclip(_SquircleClipper oldClipper) => oldClipper.cornerRadius != cornerRadius;
}

/// 精确的超椭圆边框绘制器
/// 用于绘制带边框的超椭圆
class _SquircleBorderPainter extends CustomPainter {
  final double radius;
  final Color color;
  final double strokeWidth;
  static const double n = 2.84; // 超椭圆指数
  
  _SquircleBorderPainter({
    required this.radius,
    required this.color,
    required this.strokeWidth,
  });
  
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth;
    
    final path = _createSquirclePath(size, radius);
    canvas.drawPath(path, paint);
  }
  
  Path _createSquirclePath(Size size, double r) {
    final w = size.width;
    final h = size.height;
    
    final path = Path();
    path.moveTo(0, r);
    
    // 左上角
    _drawSquircleArc(path, r, r, r, math.pi, math.pi * 1.5);
    path.lineTo(w - r, 0);
    
    // 右上角
    _drawSquircleArc(path, w - r, r, r, math.pi * 1.5, math.pi * 2);
    path.lineTo(w, h - r);
    
    // 右下角
    _drawSquircleArc(path, w - r, h - r, r, 0, math.pi * 0.5);
    path.lineTo(r, h);
    
    // 左下角
    _drawSquircleArc(path, r, h - r, r, math.pi * 0.5, math.pi);
    
    path.close();
    return path;
  }
  
  void _drawSquircleArc(Path path, double cx, double cy, double radius, double startAngle, double endAngle) {
    const int segments = 30;
    for (int i = 0; i <= segments; i++) {
      final t = i / segments;
      final angle = startAngle + (endAngle - startAngle) * t;
      final cosA = math.cos(angle);
      final sinA = math.sin(angle);
      final x = cx + radius * _sgn(cosA) * math.pow(cosA.abs(), 2.0 / n);
      final y = cy + radius * _sgn(sinA) * math.pow(sinA.abs(), 2.0 / n);
      path.lineTo(x, y);
    }
  }
  
  double _sgn(double x) => x < 0 ? -1.0 : 1.0;
  
  @override
  bool shouldRepaint(_SquircleBorderPainter oldDelegate) {
    return oldDelegate.radius != radius ||
           oldDelegate.color != color ||
           oldDelegate.strokeWidth != strokeWidth;
  }
}

/// V2.4: 应用选择页面
class AppSelectionPage extends StatefulWidget {
  const AppSelectionPage({Key? key}) : super(key: key);
  
  @override
  State<AppSelectionPage> createState() => _AppSelectionPageState();
}

class _AppSelectionPageState extends State<AppSelectionPage> {
  static const platform = MethodChannel('com.display.switcher/task');  // ✅ 修正channel名称
  
  List<Map<String, dynamic>> _apps = [];
  List<Map<String, dynamic>> _visibleApps = [];
  Set<String> _selectedApps = {};
  bool _isLoading = true;
  bool _privacyMode = false; // 隐私模式
  bool _followDndMode = true; // 跟随系统勿扰模式（默认开启）
  bool _onlyWhenLocked = false; // 仅在锁屏时通知（默认关闭）
  bool _notificationDarkMode = false; // 通知暗夜模式（默认关闭）
  bool _includeSystemApps = false; // 是否显示系统应用
  final TextEditingController _searchController = TextEditingController();
  
  @override
  void initState() {
    super.initState();
    _loadApps();
    _loadPrivacyMode();
    _loadFollowDndMode();
    _loadOnlyWhenLockedMode();
    _loadNotificationDarkMode();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }
  
  // 启动权限检查循环（后台异步）
  void _startPermissionCheckLoop() async {
    print('→ 启动权限检查循环');
    int checkAttempts = 0;
    
    while (checkAttempts < 30 && mounted) { // 最多检查30次（30秒）
      await Future.delayed(const Duration(seconds: 1));
      
      if (!mounted) break; // 页面已销毁，退出循环
      
      try {
        final bool granted = await platform.invokeMethod('checkQueryAllPackagesPermission');
        if (granted) {
          print('✓ 权限已授予，自动刷新应用列表');
          
          // 权限已授予，刷新列表
          if (mounted) {
            setState(() {
              _isLoading = true;
            });
            
            await _loadAppsInternal();
            
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('权限已授予，应用列表已刷新')),
            );
          }
          return; // 成功，退出循环
        }
      } catch (e) {
        print('权限检查失败: $e');
      }
      
      checkAttempts++;
    }
    
    print('⚠ 权限检查超时（30秒），用户可能未授予权限');
    
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请在设置中授予权限后手动刷新')),
      );
    }
  }
  
  // 内部加载方法（不检查权限，直接加载）
  Future<void> _loadAppsInternal() async {
    try {
      // 加载已选择的应用
      final List<dynamic> selectedApps = await platform.invokeMethod('getSelectedNotificationApps');
      _selectedApps = selectedApps.cast<String>().toSet();
      
      // 加载所有应用
      final List<dynamic> apps = await platform.invokeMethod('getInstalledApps');
      
      setState(() {
        _apps = apps.map((app) => Map<String, dynamic>.from(app)).toList();
        _isLoading = false;
      });
      
      _applyFilters();
      
      print('已加载 ${_apps.length} 个应用');
    } catch (e) {
      print('加载应用列表失败: $e');
      setState(() {
        _isLoading = false;
      });
    }
  }

  void _applyFilters() {
    final String q = _searchController.text.trim().toLowerCase();
    List<Map<String, dynamic>> filtered = _apps.where((app) {
      final String name = (app['appName'] ?? '').toString().toLowerCase();
      final String pkg = (app['packageName'] ?? '').toString().toLowerCase();
      final bool matchesQuery = q.isEmpty || name.contains(q) || pkg.contains(q);
      if (!_includeSystemApps && _isSystemApp(app)) {
        return false;
      }
      return matchesQuery;
    }).toList();
    
    // 排序：选中的应用置顶，然后按应用名排序
    filtered.sort((a, b) {
      final String pkgA = a['packageName'] ?? '';
      final String pkgB = b['packageName'] ?? '';
      final bool selectedA = _selectedApps.contains(pkgA);
      final bool selectedB = _selectedApps.contains(pkgB);
      
      // 如果一个是选中的，一个是未选中的，选中的排在前面
      if (selectedA && !selectedB) return -1;
      if (!selectedA && selectedB) return 1;
      
      // 如果都是选中或都是未选中，按应用名排序
      final String nameA = (a['appName'] ?? '').toString().toLowerCase();
      final String nameB = (b['appName'] ?? '').toString().toLowerCase();
      return nameA.compareTo(nameB);
    });
    
    setState(() {
      _visibleApps = filtered;
    });
  }

  bool _isSystemApp(Map<String, dynamic> app) {
    final pkg = (app['packageName'] ?? '').toString();
    final dynamic flag1 = app['isSystem'];
    final dynamic flag2 = app['isSystemApp'];
    if (flag1 == true || flag2 == true) return true;
    return pkg.startsWith('com.android.') || pkg.startsWith('com.google.android.') || pkg.startsWith('android');
  }

  Future<void> _selectAllVisible() async {
    setState(() {
      for (final app in _visibleApps) {
        final String pkg = app['packageName'];
        _selectedApps.add(pkg);
      }
    });
    // 重新应用过滤器以更新排序
    _applyFilters();
    try {
      await platform.invokeMethod('setSelectedNotificationApps', _selectedApps.toList());
    } catch (e) {
      print('批量全选保存失败: $e');
    }
  }

  Future<void> _deselectAllVisible() async {
    setState(() {
      for (final app in _visibleApps) {
        final String pkg = app['packageName'];
        _selectedApps.remove(pkg);
      }
    });
    // 重新应用过滤器以更新排序
    _applyFilters();
    try {
      await platform.invokeMethod('setSelectedNotificationApps', _selectedApps.toList());
    } catch (e) {
      print('批量全不选保存失败: $e');
    }
  }
  
  Future<void> _loadPrivacyMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _privacyMode = prefs.getBool('notification_privacy_mode') ?? false;
      });
    } catch (e) {
      print('加载隐私模式设置失败: $e');
    }
  }
  
  Future<void> _togglePrivacyMode(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationPrivacyMode', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_privacy_mode', enabled);
      setState(() {
        _privacyMode = enabled;
      });
    } catch (e) {
      print('切换隐私模式失败: $e');
    }
  }
  
  Future<void> _loadFollowDndMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _followDndMode = prefs.getBool('notification_follow_dnd_mode') ?? true;
      });
    } catch (e) {
      print('加载勿扰模式设置失败: $e');
    }
  }
  
  Future<void> _toggleFollowDndMode(bool enabled) async {
    try {
      await platform.invokeMethod('setFollowDndMode', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_follow_dnd_mode', enabled);
      setState(() {
        _followDndMode = enabled;
      });
    } catch (e) {
      print('切换勿扰模式设置失败: $e');
    }
  }
  
  Future<void> _loadOnlyWhenLockedMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _onlyWhenLocked = prefs.getBool('notification_only_when_locked') ?? false;
      });
    } catch (e) {
      print('加载锁屏通知设置失败: $e');
    }
  }
  
  Future<void> _loadNotificationDarkMode() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _notificationDarkMode = prefs.getBool('notification_dark_mode') ?? false;
      });
    } catch (e) {
      print('加载通知暗夜模式设置失败: $e');
    }
  }
  
  Future<void> _toggleNotificationDarkMode(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationDarkMode', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_dark_mode', enabled);
      
      setState(() {
        _notificationDarkMode = enabled;
      });
      print('通知暗夜模式已${enabled ? "启用" : "禁用"}');
    } catch (e) {
      print('切换通知暗夜模式失败: $e');
      // 切换失败，恢复原状态
      setState(() {
        _notificationDarkMode = !enabled;
      });
    }
  }
  
  Future<void> _toggleOnlyWhenLocked(bool enabled) async {
    try {
      await platform.invokeMethod('setOnlyWhenLocked', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_only_when_locked', enabled);
      setState(() {
        _onlyWhenLocked = enabled;
      });
    } catch (e) {
      print('切换锁屏通知设置失败: $e');
    }
  }
  
  Future<void> _loadApps() async {
    setState(() => _isLoading = true);
    
    try {
      // ✅ 主动检查QUERY_ALL_PACKAGES权限
      print('🔍 开始检查QUERY_ALL_PACKAGES权限...');
      final bool hasPermission = await platform.invokeMethod('checkQueryAllPackagesPermission');
      print('🔍 权限检查结果: $hasPermission');
      
      if (!hasPermission) {
        print('❌ 没有QUERY_ALL_PACKAGES权限，显示弹窗');
        // 没有权限，弹窗提示并跳转到设置
        setState(() => _isLoading = false);
        
        if (mounted) {
          final shouldOpenSettings = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text('需要权限'),
              content: const Text(
                '为了显示完整的应用列表，需要授予"获取所有应用列表"权限。\n\n'
                '点击"去设置"后，请在应用信息页面向下滚动，找到并授予此权限。'
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.pop(context, false),
                  child: const Text('取消'),
                ),
                TextButton(
                  onPressed: () => Navigator.pop(context, true),
                  child: const Text('去设置'),
                ),
              ],
            ),
          );
          
          if (shouldOpenSettings == true) {
            await platform.invokeMethod('requestQueryAllPackagesPermission');
            
            // 启动后台检查任务（不阻塞UI）
            _startPermissionCheckLoop();
          }
        }
        return;
      }
      
      // ✅ 有权限，继续加载
      await _loadAppsInternal();
    } catch (e) {
      print('加载应用列表失败: $e');
      setState(() => _isLoading = false);
    }
  }
  
  Future<void> _toggleApp(String packageName, bool selected) async {
    setState(() {
      if (selected) {
        _selectedApps.add(packageName);
      } else {
        _selectedApps.remove(packageName);
      }
    });
    
    // 重新应用过滤器以更新排序（选中的应用置顶）
    _applyFilters();
    
    // 保存到后台
    try {
      await platform.invokeMethod('setSelectedNotificationApps', _selectedApps.toList());
    } catch (e) {
      print('保存选择失败: $e');
    }
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text('选择应用 (${_selectedApps.length})'),
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (context) => const NotificationSettingsPage()),
              );
            },
            tooltip: '通知设置',
          ),
        ],
      ),
      extendBodyBehindAppBar: true,
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88),  // 珊瑚橙
              Color(0xFFFFB5C5),  // 粉红
              Color(0xFFE0B5DC),  // 紫色
              Color(0xFFA8C5E5),  // 蓝色
            ],
          ),
        ),
        child: SafeArea(
          child: _isLoading
              ? const Center(child: CircularProgressIndicator(color: Colors.white))
              : Padding(
                  padding: const EdgeInsets.all(20),
                  child: Column(
                    children: [
                      
                      // 筛选与批量操作卡片
                      CustomPaint(
                        painter: _SquircleBorderPainter(
                          radius: 32,
                          color: Colors.white.withOpacity(0.5),
                          strokeWidth: 1.5,
                        ),
                        child: ClipPath(
                          clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                          child: BackdropFilter(
                            filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                            child: Container(
                              padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                              decoration: BoxDecoration(
                                color: Colors.white.withOpacity(0.25),
                              ),
                              child: Column(
                                children: [
                                  TextField(
                                    controller: _searchController,
                                    onChanged: (_) => _applyFilters(),
                                    style: const TextStyle(color: Colors.black87),
                                    decoration: const InputDecoration(
                                      hintText: '搜索应用或包名',
                                      hintStyle: TextStyle(color: Colors.black45),
                                      prefixIcon: Icon(Icons.search, color: Colors.black54),
                                      border: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                        borderSide: BorderSide(color: Colors.black26),
                                      ),
                                      enabledBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                        borderSide: BorderSide(color: Colors.black26),
                                      ),
                                      focusedBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                        borderSide: BorderSide(color: Colors.black54, width: 2),
                                      ),
                                    ),
                                  ),
                                  const SizedBox(height: 10),
                                  Row(
                                    children: [
                                      // 全选/全不选
                                      ClipPath(
                                         clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
                                        child: Container(
                                          decoration: const BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topLeft,
                                              end: Alignment.bottomRight,
                                              colors: [
                                                Color(0xFFFF9D88),
                                                Color(0xFFFFB5C5),
                                                Color(0xFFE0B5DC),
                                                Color(0xFFA8C5E5),
                                              ],
                                            ),
                                          ),
                                          child: Material(
                                            color: Colors.transparent,
                                            child: InkWell(
                                              onTap: _selectAllVisible,
                                              child: const Padding(
                                                padding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                                child: Text('全选', style: TextStyle(color: Colors.white)),
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                      const SizedBox(width: 8),
                                      ClipPath(
                                         clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
                                        child: Container(
                                          decoration: const BoxDecoration(
                                            gradient: LinearGradient(
                                              begin: Alignment.topLeft,
                                              end: Alignment.bottomRight,
                                              colors: [
                                                Color(0xFFFF9D88),
                                                Color(0xFFFFB5C5),
                                                Color(0xFFE0B5DC),
                                                Color(0xFFA8C5E5),
                                              ],
                                            ),
                                          ),
                                          child: Material(
                                            color: Colors.transparent,
                                            child: InkWell(
                                              onTap: _deselectAllVisible,
                                              child: const Padding(
                                                padding: EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                                                child: Text('全不选', style: TextStyle(color: Colors.white)),
                                              ),
                                            ),
                                          ),
                                        ),
                                      ),
                                      const Spacer(),
                                      const Text('显示系统应用', style: TextStyle(color: Colors.black87, fontSize: 12)),
                                      const SizedBox(width: 6),
                                      _GradientToggle(
                                        value: _includeSystemApps,
                                        onChanged: (v) {
                                          setState(() => _includeSystemApps = v);
                                          _applyFilters();
                                        },
                                      ),
                                    ],
                                  ),
                                ],
                              ),
                            ),
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                      // 应用列表
                      Expanded(
                        child: ListView.builder(
                          itemCount: _visibleApps.length,
                          padding: const EdgeInsets.symmetric(vertical: 8),
                          itemExtent: 72,
                          cacheExtent: 500,
                          addAutomaticKeepAlives: false,
                          addRepaintBoundaries: true,
                          physics: const ClampingScrollPhysics(),
                          itemBuilder: (context, index) {
                            final app = _visibleApps[index];
                            final String appName = app['appName'];
                            final String packageName = app['packageName'];
                            final Uint8List? iconBytes = app['icon'];
                            final bool isSelected = _selectedApps.contains(packageName);
                            return _AppListItem(
                              appName: appName,
                              packageName: packageName,
                              iconBytes: iconBytes,
                              isSelected: isSelected,
                              onToggle: () => _toggleApp(packageName, !isSelected),
                            );
                          },
                        ),
                      ),
                    ],
                  ),
                ),
        ),
      ),
    );
  }
}

/// V3.4: 通知设置页面
class NotificationSettingsPage extends StatefulWidget {
  const NotificationSettingsPage({Key? key}) : super(key: key);
  
  @override
  State<NotificationSettingsPage> createState() => _NotificationSettingsPageState();
}

class _NotificationSettingsPageState extends State<NotificationSettingsPage> {
  static const platform = MethodChannel('com.display.switcher/task');
  
  bool _privacyHideTitle = false;
  bool _privacyHideContent = false;
  bool _followDndMode = true;
  bool _onlyWhenLocked = false;
  bool _notificationDarkMode = false;
  int _notificationDuration = 10;
  final TextEditingController _durationController = TextEditingController();
  final FocusNode _durationFocusNode = FocusNode();
  
  @override
  void initState() {
    super.initState();
    _loadAllSettings();
  }
  
  @override
  void dispose() {
    _durationController.dispose();
    _durationFocusNode.dispose();
    super.dispose();
  }
  
  Future<void> _loadAllSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      setState(() {
        _privacyHideTitle = prefs.getBool('notification_privacy_hide_title') ?? false;
        _privacyHideContent = prefs.getBool('notification_privacy_hide_content') ?? false;
        _followDndMode = prefs.getBool('notification_follow_dnd_mode') ?? true;
        _onlyWhenLocked = prefs.getBool('notification_only_when_locked') ?? false;
        _notificationDarkMode = prefs.getBool('notification_dark_mode') ?? false;
        _notificationDuration = prefs.getInt('notification_duration') ?? 10;
        _durationController.text = _notificationDuration.toString();
      });
    } catch (e) {
      print('加载通知设置失败: $e');
    }
  }
  
  Future<void> _togglePrivacyHideTitle(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationPrivacyHideTitle', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_privacy_hide_title', enabled);
      setState(() {
        _privacyHideTitle = enabled;
      });
    } catch (e) {
      print('切换隐藏标题失败: $e');
    }
  }
  
  Future<void> _togglePrivacyHideContent(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationPrivacyHideContent', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_privacy_hide_content', enabled);
      setState(() {
        _privacyHideContent = enabled;
      });
    } catch (e) {
      print('切换隐藏内容失败: $e');
    }
  }
  
  Future<void> _toggleFollowDndMode(bool enabled) async {
    try {
      await platform.invokeMethod('setFollowDndMode', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_follow_dnd_mode', enabled);
      setState(() {
        _followDndMode = enabled;
      });
    } catch (e) {
      print('切换勿扰模式设置失败: $e');
    }
  }
  
  Future<void> _toggleOnlyWhenLocked(bool enabled) async {
    try {
      await platform.invokeMethod('setOnlyWhenLocked', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_only_when_locked', enabled);
      setState(() {
        _onlyWhenLocked = enabled;
      });
    } catch (e) {
      print('切换锁屏通知设置失败: $e');
    }
  }
  
  Future<void> _toggleNotificationDarkMode(bool enabled) async {
    try {
      await platform.invokeMethod('setNotificationDarkMode', {'enabled': enabled});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('notification_dark_mode', enabled);
      setState(() {
        _notificationDarkMode = enabled;
      });
    } catch (e) {
      print('切换通知暗夜模式失败: $e');
    }
  }
  
  Future<void> _setNotificationDuration(int seconds) async {
    try {
      await platform.invokeMethod('setNotificationDuration', {'duration': seconds});
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('notification_duration', seconds);
      setState(() {
        _notificationDuration = seconds;
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('已设置为 $seconds 秒')),
        );
      }
    } catch (e) {
      print('设置通知销毁时间失败: $e');
    }
  }
  
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('通知设置'),
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
        scrolledUnderElevation: 0,
        surfaceTintColor: Colors.transparent,
        shadowColor: Colors.transparent,
      ),
      extendBodyBehindAppBar: true,
      body: Container(
        width: double.infinity,
        height: double.infinity,
        decoration: const BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [
              Color(0xFFFF9D88),
              Color(0xFFFFB5C5),
              Color(0xFFE0B5DC),
              Color(0xFFA8C5E5),
            ],
          ),
        ),
        child: SafeArea(
          child: ListView(
            padding: const EdgeInsets.all(20),
            children: [
              // 隐私模式卡片
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Column(
                        children: [
                          Row(
                            children: [
                              const Icon(Icons.lock_outline, size: 20, color: Colors.black54),
                              const SizedBox(width: 8),
                              const Text(
                                '隐藏通知标题',
                                style: TextStyle(fontSize: 14, color: Colors.black87, fontWeight: FontWeight.w500),
                              ),
                              const Spacer(),
                              _GradientToggle(
                                value: _privacyHideTitle,
                                onChanged: _togglePrivacyHideTitle,
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          const Divider(color: Colors.black26, height: 1),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              const Icon(Icons.lock_outline, size: 20, color: Colors.black54),
                              const SizedBox(width: 8),
                              const Text(
                                '隐藏通知内容',
                                style: TextStyle(fontSize: 14, color: Colors.black87, fontWeight: FontWeight.w500),
                              ),
                              const Spacer(),
                              _GradientToggle(
                                value: _privacyHideContent,
                                onChanged: _togglePrivacyHideContent,
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              
              const SizedBox(height: 20),
              
              // 跟随系统勿扰模式
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(Icons.notifications_paused, size: 20, color: Colors.black54),
                          const SizedBox(width: 8),
                          const Text(
                            '跟随系统勿扰模式',
                            style: TextStyle(fontSize: 14, color: Colors.black87, fontWeight: FontWeight.w500),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _followDndMode,
                            onChanged: _toggleFollowDndMode,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              
              const SizedBox(height: 20),
              
              // 仅在锁屏时通知
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(Icons.screen_lock_portrait, size: 20, color: Colors.black54),
                          const SizedBox(width: 8),
                          const Text(
                            '仅在锁屏时通知',
                            style: TextStyle(fontSize: 14, color: Colors.black87, fontWeight: FontWeight.w500),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _onlyWhenLocked,
                            onChanged: _toggleOnlyWhenLocked,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              
              const SizedBox(height: 20),
              
              // 通知暗夜模式
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Row(
                        children: [
                          const Icon(Icons.dark_mode, size: 20, color: Colors.black54),
                          const SizedBox(width: 8),
                          const Text(
                            '通知暗夜模式',
                            style: TextStyle(fontSize: 14, color: Colors.black87, fontWeight: FontWeight.w500),
                          ),
                          const Spacer(),
                          _GradientToggle(
                            value: _notificationDarkMode,
                            onChanged: _toggleNotificationDarkMode,
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              
              const SizedBox(height: 20),
              
              // 自动销毁时间
              CustomPaint(
                painter: _SquircleBorderPainter(
                  radius: 32,
                  color: Colors.white.withOpacity(0.5),
                  strokeWidth: 1.5,
                ),
                child: ClipPath(
                  clipper: _SquircleClipper(cornerRadius: _SquircleRadii.large),
                  child: BackdropFilter(
                    filter: ImageFilter.blur(sigmaX: 0, sigmaY: 0),
                    child: Container(
                      padding: const EdgeInsets.all(20),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.25),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Row(
                            children: [
                              const Icon(Icons.timer_outlined, size: 20, color: Colors.black54),
                              const SizedBox(width: 8),
                              const Text(
                                '自动销毁时间',
                                style: TextStyle(fontSize: 14, color: Colors.black87, fontWeight: FontWeight.w500),
                              ),
                            ],
                          ),
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Expanded(
                                child: TextField(
                                  controller: _durationController,
                                  focusNode: _durationFocusNode,
                                  keyboardType: TextInputType.number,
                                  style: const TextStyle(color: Colors.black87),
                                  decoration: const InputDecoration(
                                    labelText: '新时间（秒）',
                                    labelStyle: TextStyle(color: Colors.black54),
                                    hintText: '输入秒数',
                                    hintStyle: TextStyle(color: Colors.black38),
                                    border: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                      borderSide: BorderSide(color: Colors.black26),
                                    ),
                                    enabledBorder: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                      borderSide: BorderSide(color: Colors.black26),
                                    ),
                                    focusedBorder: OutlineInputBorder(
                                      borderRadius: BorderRadius.all(Radius.circular(_SquircleRadii.small)),
                                      borderSide: BorderSide(color: Colors.black54, width: 2),
                                    ),
                                  ),
                                ),
                              ),
                              const SizedBox(width: 12),
                              ClipPath(
                                clipper: _SquircleClipper(cornerRadius: _SquircleRadii.small),
                                child: Container(
                                  decoration: const BoxDecoration(
                                    gradient: LinearGradient(
                                      begin: Alignment.topLeft,
                                      end: Alignment.bottomRight,
                                      colors: [
                                        Color(0xFFFF9D88),
                                        Color(0xFFFFB5C5),
                                        Color(0xFFE0B5DC),
                                        Color(0xFFA8C5E5),
                                      ],
                                    ),
                                  ),
                                  child: ElevatedButton(
                                    onPressed: () {
                                      final seconds = int.tryParse(_durationController.text);
                                      if (seconds != null && seconds > 0) {
                                        _setNotificationDuration(seconds);
                                      } else {
                                        ScaffoldMessenger.of(context).showSnackBar(
                                          const SnackBar(content: Text('请输入大于0的有效秒数')),
                                        );
                                      }
                                    },
                                    style: ElevatedButton.styleFrom(
                                      backgroundColor: Colors.transparent,
                                      foregroundColor: Colors.white,
                                      shadowColor: Colors.transparent,
                                      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
                                    ),
                                    child: const Text('确定'),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

