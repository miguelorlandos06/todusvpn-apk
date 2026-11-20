import 'package:flutter/material.dart';
import '../core/vpn_bridge.dart';
import '../theme/app_theme.dart';
import 'phone_screen.dart';

/// Pantalla de bienvenida: identidad roja/blanca, logo y CTA "Comenzar".
class WelcomeScreen extends StatelessWidget {
  const WelcomeScreen({super.key, required this.bridge});
  final VpnBridge bridge;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 28, vertical: 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Spacer(flex: 2),
              _Logo(),
              const SizedBox(height: 28),
              Text(
                'toDusVPN',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.displayLarge?.copyWith(
                      fontSize: 40,
                      color: AppColors.primary,
                      letterSpacing: -0.5,
                    ),
              ),
              const SizedBox(height: 10),
              Text(
                'Tu conexión privada sobre la mensajería de toDus.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                      fontSize: 16,
                      color: AppColors.muted,
                    ),
              ),
              const Spacer(flex: 3),
              _FeatureRow(
                icon: Icons.lock_outline,
                title: 'Cifrado de extremo a extremo',
                subtitle: 'Tráfico encapsulado en mensajes XMPP de toDus.',
              ),
              const SizedBox(height: 18),
              _FeatureRow(
                icon: Icons.bolt_outline,
                title: 'Sin relay, sin S3',
                subtitle: 'Reemplazo del relay de objetos por mensajes directos.',
              ),
              const SizedBox(height: 18),
              _FeatureRow(
                icon: Icons.phone_android_outlined,
                title: 'Solo tu número',
                subtitle: 'Login automático con tu teléfono cubano.',
              ),
              const Spacer(flex: 2),
              ElevatedButton(
                onPressed: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => PhoneScreen(bridge: bridge)),
                ),
                style: ElevatedButton.styleFrom(
                  minimumSize: const Size.fromHeight(56),
                  backgroundColor: AppColors.primary,
                ),
                child: const Text('Comenzar', style: TextStyle(fontSize: 18)),
              ),
              const SizedBox(height: 8),
            ],
          ),
        ),
      ),
    );
  }
}

class _Logo extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        width: 110,
        height: 110,
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [AppColors.primary, AppColors.primaryDark],
          ),
          borderRadius: BorderRadius.circular(28),
          boxShadow: [
            BoxShadow(
              color: AppColors.primary.withValues(alpha: 0.35),
              blurRadius: 24,
              offset: const Offset(0, 12),
            ),
          ],
        ),
        child: const Icon(Icons.shield_outlined, size: 60, color: Colors.white),
      ),
    );
  }
}

class _FeatureRow extends StatelessWidget {
  const _FeatureRow({required this.icon, required this.title, required this.subtitle});
  final IconData icon;
  final String title;
  final String subtitle;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 44,
          height: 44,
          decoration: BoxDecoration(
            color: AppColors.primaryLight,
            borderRadius: BorderRadius.circular(12),
          ),
          child: Icon(icon, color: AppColors.primary, size: 22),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
              const SizedBox(height: 2),
              Text(subtitle, style: const TextStyle(fontSize: 13, color: AppColors.muted)),
            ],
          ),
        ),
      ],
    );
  }
}

