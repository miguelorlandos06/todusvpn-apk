import 'package:flutter/material.dart';
import '../core/vpn_bridge.dart';
import '../theme/app_theme.dart';

/// Pantalla principal: un botón grande para activar/desactivar la VPN.
///
/// Estados mostrados (espejo del servicio Kotlin):
///  - stopped  → "Tocar para conectar"
///  - connecting → "Conectando… iniciando conexión XMPP"
///  - active   → "Conectada"
///  - error    → mensaje de error
class ConnectScreen extends StatefulWidget {
  const ConnectScreen({super.key, required this.bridge});
  final VpnBridge bridge;

  @override
  State<ConnectScreen> createState() => _ConnectScreenState();
}

class _ConnectScreenState extends State<ConnectScreen> {
  @override
  void initState() {
    super.initState();
    widget.bridge.addListener(_onChanged);
  }

  @override
  void dispose() {
    widget.bridge.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final bridge = widget.bridge;
    return Scaffold(
      appBar: AppBar(
        title: const Text('toDusVPN'),
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            tooltip: 'Cerrar sesión',
            onPressed: () async {
              await _logout();
            },
          ),
        ],
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 28),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 12),
              _AccountChip(phone: bridge.phone, server: bridge.serverPhone),
              const Spacer(),
              _BigButton(
                state: bridge.state,
                onTap: bridge.isConnecting ? null : () => bridge.toggle(),
              ),
              const SizedBox(height: 28),
              _StatusPanel(bridge: bridge),
              const Spacer(),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _logout() async {
    // Borra credenciales y vuelve a la bienvenida.
    // ignore: use_build_context_synchronously
    Navigator.of(context).pushNamedAndRemoveUntil('/', (r) => false);
  }
}

class _AccountChip extends StatelessWidget {
  const _AccountChip({required this.phone, required this.server});
  final String phone;
  final String server;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.primaryLight.withValues(alpha: 0.4),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Row(
        children: [
          const Icon(Icons.sim_card, size: 20, color: AppColors.primary),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('+53 $phone', style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
                const SizedBox(height: 2),
                Text('Servidor: +53 $server',
                    style: const TextStyle(fontSize: 12, color: AppColors.muted)),
              ],
            ),
          ),
          const Icon(Icons.check_circle, color: AppColors.connected, size: 18),
        ],
      ),
    );
  }
}

class _BigButton extends StatelessWidget {
  const _BigButton({required this.state, required this.onTap});
  final VpnState state;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final (color, ringColor, icon, label) = switch (state) {
      VpnState.active => (AppColors.connected, AppColors.connected, Icons.power_settings_new, 'Desconectar'),
      VpnState.connecting => (AppColors.connecting, AppColors.connecting, Icons.sync, 'Conectando…'),
      VpnState.error => (AppColors.error, AppColors.error, Icons.error_outline, 'Reintentar'),
      VpnState.stopped => (AppColors.primary, AppColors.primaryLight, Icons.power_settings_new, 'Conectar'),
    };
    final size = MediaQuery.of(context).size.shortestSide * 0.62;
    return Center(
      child: GestureDetector(
        onTap: onTap,
        child: SizedBox(
          width: size,
          height: size,
          child: Stack(
            alignment: Alignment.center,
            children: [
              // halo
              Container(
                width: size,
                height: size,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: ringColor.withValues(alpha: state == VpnState.connecting ? 0.18 : 0.10),
                ),
              ),
              // ring
              Container(
                width: size * 0.78,
                height: size * 0.78,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  border: Border.all(color: ringColor, width: 6),
                ),
              ),
              // core button
              Container(
                width: size * 0.58,
                height: size * 0.58,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: color,
                  boxShadow: [
                    BoxShadow(
                      color: color.withValues(alpha: 0.45),
                      blurRadius: 30,
                      offset: const Offset(0, 16),
                    ),
                  ],
                ),
                child: Icon(icon, color: Colors.white, size: size * 0.22),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StatusPanel extends StatelessWidget {
  const _StatusPanel({required this.bridge});
  final VpnBridge bridge;

  @override
  Widget build(BuildContext context) {
    final statusText = switch (bridge.state) {
      VpnState.stopped => 'Toca el botón para conectar.',
      VpnState.connecting => 'Conectando… iniciando conexión XMPP con el servidor.',
      VpnState.active => 'Conectada. Tu tráfico pasa por mensajes toDus.',
      VpnState.error => 'Error: ${bridge.lastError.isEmpty ? "desconocido" : bridge.lastError}',
    };
    final statusColor = switch (bridge.state) {
      VpnState.active => AppColors.connected,
      VpnState.connecting => AppColors.connecting,
      VpnState.error => AppColors.error,
      VpnState.stopped => AppColors.muted,
    };
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surface,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: statusColor.withValues(alpha: 0.25)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.circle, size: 12, color: statusColor),
              const SizedBox(width: 8),
              Text(bridge.state.label.toUpperCase(),
                  style: TextStyle(color: statusColor, fontWeight: FontWeight.w700, fontSize: 13, letterSpacing: 1)),
            ],
          ),
          const SizedBox(height: 8),
          Text(statusText, style: const TextStyle(fontSize: 15, height: 1.4)),
          if (bridge.logLine.isNotEmpty) ...[
            const SizedBox(height: 10),
            Text(bridge.logLine,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(
                  fontSize: 11,
                  fontFamily: 'JetBrainsMono',
                  color: AppColors.muted.withValues(alpha: 0.9),
                )),
          ],
        ],
      ),
    );
  }
}
