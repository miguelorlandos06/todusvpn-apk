import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../core/vpn_bridge.dart';
import '../theme/app_theme.dart';
import 'connect_screen.dart';

/// Pantalla de ingreso del número: al confirmar, ejecuta el login automático
/// (login_with_phone_only en el lado Kotlin) que obtiene el JWT y lo guarda.
class PhoneScreen extends StatefulWidget {
  const PhoneScreen({super.key, required this.bridge});
  final VpnBridge bridge;

  @override
  State<PhoneScreen> createState() => _PhoneScreenState();
}

class _PhoneScreenState extends State<PhoneScreen> {
  final TextEditingController _ctrl = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  String _normalize(String raw) {
    // Acepta 8 o 10 dígitos; el lado Kotlin valida completamente.
    final digits = raw.replaceAll(RegExp(r'[^\d]'), '');
    return digits;
  }

  Future<void> _submit() async {
    final phone = _normalize(_ctrl.text.trim());
    if (phone.isEmpty) {
      setState(() => _error = 'Ingresa tu número cubano (8 o 10 dígitos).');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await widget.bridge.autoLogin(phone);
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => ConnectScreen(bridge: widget.bridge)),
      );
    } on PlatformException catch (e) {
      setState(() => _error = e.message ?? 'No se pudo autenticar.');
    } catch (e) {
      setState(() => _error = 'Error: $e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.of(context).maybePop(),
        ),
        title: const Text('Tu número'),
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 32),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 16),
              Text(
                'Ingresa tu número',
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontSize: 26),
              ),
              const SizedBox(height: 8),
              const Text(
                'Se obtiene el JWT automáticamente con tu teléfono. '
                'No se necesita SMS ni contraseña.',
                style: TextStyle(fontSize: 14, color: AppColors.muted, height: 1.4),
              ),
              const SizedBox(height: 28),
              TextField(
                controller: _ctrl,
                keyboardType: TextInputType.phone,
                inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[\d\s]'))],
                decoration: const InputDecoration(
                  hintText: '5353974499',
                  prefixText: '+53 ',
                  prefixStyle: TextStyle(fontWeight: FontWeight.w600),
                ),
                onSubmitted: (_) => _submit(),
              ),
              if (_error != null) ...[
                const SizedBox(height: 14),
                Text(_error!, style: const TextStyle(color: AppColors.error, fontSize: 13)),
              ],
              const Spacer(),
              ElevatedButton(
                onPressed: _busy ? null : _submit,
                style: ElevatedButton.styleFrom(minimumSize: const Size.fromHeight(56)),
                child: _busy
                    ? const SizedBox(
                        height: 22,
                        width: 22,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                      )
                    : const Text('Entrar', style: TextStyle(fontSize: 18)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
