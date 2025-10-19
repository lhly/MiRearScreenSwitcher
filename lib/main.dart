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
import 'dart:io';
import 'dart:ui';
import 'dart:math' as math;

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
  bool _isInputFocused = false;
  
  // V2.1: 显示控制相关
  int _currentRotation = 0;  // 当前旋转方向 (0=0°, 1=90°, 2=180°, 3=270°)
  
  @override
  void initState() {
    super.initState();
    _checkShizuku();
    _setupMethodCallHandler();
    _requestNotificationPermission();  // 请求通知权限
    
    // 监听输入框焦点状态
    _dpiFocusNode.addListener(() {
      setState(() {
        _isInputFocused = _dpiFocusNode.hasFocus;
      });
    });
    
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
      }
    });
  }
  
  Future<void> _requestNotificationPermission() async {
    // Android 13+ 需要请求通知权限
    if (Platform.isAndroid) {
      try {
        await platform.invokeMethod('requestNotificationPermission');
        print('通知权限请求已发送');
      } catch (e) {
        print('请求通知权限失败: $e');
      }
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
  
  Future<void> _requestShizukuPermission() async {
    setState(() {
      _statusMessage = '正在请求Shizuku权限...\n\n请在弹出的对话框中点击"允许"';
    });
    
    try {
      await platform.invokeMethod('requestShizukuPermission');
      
      // 等待用户操作
      await Future.delayed(const Duration(seconds: 2));
      
      // 重新检查
      await _checkShizuku();
    } catch (e) {
      setState(() {
        _statusMessage = '❌ 请求失败: $e';
      });
    }
  }
  
  Future<void> _switchApp(String package, String name) async {
    if (!_shizukuRunning) {
      _showMessage('请先启动Shizuku！');
      return;
    }
    
    setState(() {
      _isLoading = true;
      _statusMessage = '正在切换$name...';
    });
    
    try {
      final success = await platform.invokeMethod('toggleAppDisplay', {
        'package': package,
      });
      
      setState(() {
        _isLoading = false;
        _statusMessage = success 
            ? '🎉 $name 已切换！\n\n如果移到了背屏，请翻转手机查看！'
            : '❌ 切换失败\n\n可能原因：\n1. 应用未运行\n2. 权限不足\n\n提示：请先打开$name';
      });
    } catch (e) {
      setState(() {
        _isLoading = false;
        _statusMessage = '❌ 错误: $e';
      });
    }
  }
  
  void _showMessage(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg)),
    );
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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        foregroundColor: Colors.white,
        elevation: 0,
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
            physics: _isInputFocused ? const BouncingScrollPhysics() : const NeverScrollableScrollPhysics(),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
            // 整合后的状态和权限卡片（毛玻璃效果）
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: 32,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: 32),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.3),
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
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: 32,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: 32),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white.withOpacity(0.3),
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
                                        borderRadius: BorderRadius.all(Radius.circular(12)),
                                        borderSide: BorderSide(color: Colors.black26),
                                      ),
                                      enabledBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(12)),
                                        borderSide: BorderSide(color: Colors.black26),
                                      ),
                                      focusedBorder: OutlineInputBorder(
                                        borderRadius: BorderRadius.all(Radius.circular(12)),
                                        borderSide: BorderSide(color: Colors.black54, width: 2),
                                      ),
                                    ),
                                  ),
                                ),
                                const SizedBox(width: 12),
                                ClipPath(
                                  clipper: _SquircleClipper(cornerRadius: 12),
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
                                          borderRadius: BorderRadius.circular(12),
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
                                  radius: 12,
                                  color: Colors.black26,
                                  strokeWidth: 1,
                                ),
                                child: ClipPath(
                                  clipper: _SquircleClipper(cornerRadius: 12),
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
                  
                const SizedBox(height: 20),
                
                // 使用教程 - 可点击跳转到酷安帖子
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: 32,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: 32),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
                      child: Material(
                        color: Colors.transparent,
                        child: InkWell(
                          onTap: () async {
                            // 跳转到酷安使用教程帖子
                            try {
                              await platform.invokeMethod('openCoolApkTutorial');
                            } catch (e) {
                              print('打开教程失败: $e');
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
                              color: Colors.white.withOpacity(0.3),
                            ),
                            padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
                          child: Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              const Text(
                                '📖',
                                style: TextStyle(fontSize: 20),
                              ),
                              const SizedBox(width: 6),
                              Image.asset(
                                'assets/kuan.png',
                                width: 24,
                                height: 24,
                                errorBuilder: (context, error, stackTrace) {
                                  return const Icon(Icons.book, size: 24, color: Colors.black87);
                                },
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
                    radius: 32,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: 32),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
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
                              color: Colors.white.withOpacity(0.3),
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
                
                // 测试人员信息 - 可点击跳转到酷安
                CustomPaint(
                  painter: _SquircleBorderPainter(
                    radius: 32,
                    color: Colors.white.withOpacity(0.5),
                    strokeWidth: 1.5,
                  ),
                  child: ClipPath(
                    clipper: _SquircleClipper(cornerRadius: 32),
                    child: BackdropFilter(
                      filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
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
                            color: Colors.white.withOpacity(0.3),
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
                  
                const SizedBox(height: 20),
              ],
            ),
          ),
        ),
      ),
    );
  }
  
  Future<void> _toggleCurrentApp() async {
    setState(() {
      _isLoading = true;
      _statusMessage = '正在获取当前应用...';
    });
    
    try {
      final currentApp = await platform.invokeMethod('getCurrentApp');
      
      if (currentApp != null && currentApp.toString().contains(':')) {
        List<String> parts = currentApp.toString().split(':');
        String packageName = parts[0];
        int taskId = int.parse(parts[1]);
        
        if (taskId > 0) {
          // 有有效的taskId，尝试切换
          setState(() {
            _statusMessage = '找到应用: $packageName\n正在切换...';
          });
          
          int currentDisplay = await platform.invokeMethod('getTaskDisplay', {'taskId': taskId});
          int targetDisplay = (currentDisplay == 0) ? 1 : 0;
          
          bool success = await platform.invokeMethod('moveTaskToDisplay', {
            'taskId': taskId,
            'displayId': targetDisplay
          });
          
          setState(() {
            _isLoading = false;
            if (success) {
              _statusMessage = '🎉 应用已切换到${targetDisplay == 1 ? "背屏" : "主屏"}！\n\n包名: $packageName\n\n${targetDisplay == 1 ? "请翻转手机查看！" : ""}';
            } else {
              _statusMessage = '❌ 切换失败\n\n可能应用已被系统关闭';
            }
          });
        } else {
          setState(() {
            _isLoading = false;
            _statusMessage = '❌ 无法获取taskId\n\n请使用下方的应用按钮';
          });
        }
      } else {
        setState(() {
          _isLoading = false;
          _statusMessage = '❌ 未找到最近使用的应用\n\n请先打开其他应用再试';
        });
      }
    } catch (e) {
      setState(() {
        _isLoading = false;
        _statusMessage = '❌ 错误: $e';
      });
    }
  }
  
  // V2.1: 构建旋转按钮（精确超椭圆，统一12px圆角）
  Widget _buildRotationButton(String label, int rotation) {
    bool isSelected = _currentRotation == rotation;
    
    return SizedBox(
      width: 50,
      height: 32,
      child: ClipPath(
        clipper: _SquircleClipper(cornerRadius: 12),
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

