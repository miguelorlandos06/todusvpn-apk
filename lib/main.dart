import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'core/vpn_bridge.dart';
import 'screens/connect_screen.dart';
import 'screens/phone_screen.dart';
import 'screens/welcome_screen.dart';
import 'theme/app_theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
      statusBarBrightness: Brightness.dark,
    ),
  );
  SystemChrome.setPreferredOrientations(<DeviceOrientation>[DeviceOrientation.portraitUp]);
  runApp(ToDusVpnApp(bridge: VpnBridge()..start()));
}

/// Raíz de la app toDusVPN: tema rojo/blanco, decide la pantalla inicial
/// según si ya hay un JWT persistido (auto-login previo).
class ToDusVpnApp extends StatelessWidget {
  const ToDusVpnApp({super.key, required this.bridge});
  final VpnBridge bridge;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'toDusVPN',
      debugShowCheckedModeBanner: false,
      theme: appTheme(Brightness.light),
      darkTheme: appTheme(Brightness.dark),
      home: _SplashGate(bridge: bridge),
    );
  }
}

class _SplashGate extends StatefulWidget {
  const _SplashGate({required this.bridge});
  final VpnBridge bridge;

  @override
  State<_SplashGate> createState() => _SplashGateState();
}

class _SplashGateState extends State<_SplashGate> {
  bool _loading = true;
  bool _hasJwt = false;

  @override
  void initState() {
    super.initState();
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    final ok = await widget.bridge.loadCredentials();
    if (!mounted) return;
    setState(() {
      _hasJwt = ok;
      _loading = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return Scaffold(
        backgroundColor: Theme.of(context).colorScheme.surface,
        body: const Center(child: CircularProgressIndicator()),
      );
    }
    return _hasJwt
        ? ConnectScreen(bridge: widget.bridge)
        : WelcomeScreen(bridge: widget.bridge);
  }
}
