import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Estado de la VPN reflejado desde el servicio Kotlin.
enum VpnState { stopped, connecting, active, error }

extension VpnStateX on VpnState {
  String get label => switch (this) {
        VpnState.stopped => 'desconectada',
        VpnState.connecting => 'conectando',
        VpnState.active => 'conectada',
        VpnState.error => 'error',
      };
}

/// Puente con el núcleo Kotlin a través del MethodChannel `todus.vpn/bridge`.
///
/// Responsable de:
///  - persistir el número de teléfono y el JWT (auto-login) en SharedPreferences;
///  - exponer connect/disconnect/getStatus al servicio [TodusVpnService];
///  - notificar a la UI los cambios de estado vía [ChangeNotifier].
class VpnBridge extends ChangeNotifier {
  VpnBridge({MethodChannel? channel})
      : _channel = channel ?? const MethodChannel('todus.vpn/bridge');

  final MethodChannel _channel;

  VpnState _state = VpnState.stopped;
  String _lastError = '';
  String _phone = '';
  String _jwt = '';
  String _serverPhone = '5353974499';
  String _logLine = '';

  Timer? _timer;
  bool _polling = false;

  VpnState get state => _state;
  String get lastError => _lastError;
  String get phone => _phone;
  String get jwt => _jwt;
  String get serverPhone => _serverPhone;
  String get logLine => _logLine;
  bool get isActive => _state == VpnState.active;
  bool get isConnecting => _state == VpnState.connecting;

  /// Carga número/JWT/servidor guardados. Devuelve true si hay JWT válido.
  Future<bool> loadCredentials() async {
    final prefs = await SharedPreferences.getInstance();
    _phone = prefs.getString('phone') ?? '';
    _jwt = prefs.getString('jwt') ?? '';
    _serverPhone = prefs.getString('server_phone') ?? '5353974499';
    notifyListeners();
    return _jwt.isNotEmpty;
  }

  /// Guarda el número y JWT tras el login automático.
  Future<void> saveCredentials(String phone, String jwt, String serverPhone) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('phone', phone);
    await prefs.setString('jwt', jwt);
    await prefs.setString('server_phone', serverPhone);
    _phone = phone;
    _jwt = jwt;
    _serverPhone = serverPhone;
    notifyListeners();
  }

  /// Login automático: el lado Kotlin hace login_with_phone_only y devuelve
  /// el JWT. Si falla, lanza PlatformException con el mensaje.
  Future<String> autoLogin(String phone, {String serverPhone = '5353974499'}) async {
    final res = await _channel.invokeMapMethod<String, dynamic>('autoLogin', <String, dynamic>{
      'phone': phone,
      'serverPhone': serverPhone,
    });
    final jwt = res?['jwt'] as String? ?? '';
    if (jwt.isEmpty) {
      throw PlatformException(code: 'AUTH_FAILED', message: res?['error'] as String? ?? 'login fallido');
    }
    await saveCredentials(phone, jwt, serverPhone);
    return jwt;
  }

  /// Inicia el sondeo de estado cada 500 ms (como la app original).
  void start() {
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(milliseconds: 500), (_) => _refresh());
    _refresh();
  }

  @override
  void dispose() {
    _timer?.cancel();
    _timer = null;
    super.dispose();
  }

  Future<void> _refresh() async {
    if (_polling) return;
    _polling = true;
    try {
      final raw = await _channel.invokeMapMethod<Object?, Object?>('getStatus');
      if (raw != null) _apply(raw);
    } on PlatformException {
      // núcleo no listo todavía
    } on MissingPluginException {
      // entorno sin bridge (tests)
    } catch (_) {
      // fallo de sondeo no tumba la UI
    } finally {
      _polling = false;
    }
  }

  void _apply(Map<Object?, Object?> raw) {
    final newState = _parseState(raw['state']);
    _lastError = raw['lastError'] as String? ?? '';
    final log = raw['log'] as List<Object?>?;
    _logLine = (log == null || log.isEmpty) ? '' : log.last as String? ?? '';
    if (newState != _state) {
      _state = newState;
      notifyListeners();
    }
  }

  static VpnState _parseState(Object? raw) {
    switch (raw) {
      case 'activa':
        return VpnState.active;
      case 'conectando':
        return VpnState.connecting;
      case 'error':
        return VpnState.error;
      default:
        return VpnState.stopped;
    }
  }

  /// Alterna conectar/desconectar.
  Future<bool> toggle() {
    if (_state == VpnState.active || _state == VpnState.connecting) {
      return disconnect();
    }
    return connect();
  }

  Future<bool> connect() async {
    try {
      final ok = await _channel.invokeMethod<bool>('connect');
      await _refresh();
      return ok ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<bool> disconnect() async {
    try {
      await _channel.invokeMethod<bool>('disconnect');
      await _refresh();
      return true;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }
}
