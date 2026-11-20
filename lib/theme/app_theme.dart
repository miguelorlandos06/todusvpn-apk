import 'package:flutter/material.dart';

/// Tema rojo y blanco de toDusVPN.
///
/// La identidad visual se basa en un rojo principal fuerte (#E53935) sobre
/// fondo blanco/negro según el brillo del sistema. La tipografía corporativa
/// es SpaceGrotesk (cargada en pubspec) con JetBrainsMono para datos.
class AppColors {
  AppColors._();

  /// Rojo principal de marca.
  static const Color primary = Color(0xFFE53935);
  static const Color primaryDark = Color(0xFFB71C1C);
  static const Color primaryLight = Color(0xFFFFCDD2);
  static const Color accent = Color(0xFFFF5252);

  static const Color bgLight = Color(0xFFFFFFFF);
  static const Color surfaceLight = Color(0xFFFFF5F5);
  static const Color ink = Color(0xFF1A1A1A);
  static const Color muted = Color(0xFF6B6B6B);

  static const Color connected = Color(0xFF43A047);
  static const Color connecting = Color(0xFFFB8C00);
  static const Color error = Color(0xFFE53935);
}

ThemeData appTheme(Brightness brightness) {
  final isLight = brightness == Brightness.light;
  final scheme = ColorScheme.fromSeed(
    seedColor: AppColors.primary,
    brightness: brightness,
    primary: AppColors.primary,
    secondary: AppColors.accent,
    error: AppColors.error,
    surface: isLight ? AppColors.surfaceLight : const Color(0xFF121212),
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    scaffoldBackgroundColor: isLight ? AppColors.bgLight : const Color(0xFF0E0E0E),
    fontFamily: 'SpaceGrotesk',
    textTheme: const TextTheme(
      displayLarge: TextStyle(fontFamily: 'SpaceGrotesk', fontWeight: FontWeight.w700),
      headlineMedium: TextStyle(fontFamily: 'SpaceGrotesk', fontWeight: FontWeight.w600),
      bodyLarge: TextStyle(fontFamily: 'SpaceGrotesk', fontWeight: FontWeight.w400),
      bodyMedium: TextStyle(fontFamily: 'SpaceGrotesk', fontWeight: FontWeight.w400),
      labelLarge: TextStyle(fontFamily: 'SpaceGrotesk', fontWeight: FontWeight.w600),
    ).apply(
      bodyColor: isLight ? AppColors.ink : Colors.white,
      displayColor: isLight ? AppColors.ink : Colors.white,
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: AppColors.primary,
        foregroundColor: Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
        textStyle: const TextStyle(fontFamily: 'SpaceGrotesk', fontWeight: FontWeight.w600, fontSize: 16),
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: isLight ? const Color(0xFFF7F2F2) : const Color(0xFF1C1C1C),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: AppColors.primaryLight, width: 1.5),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(14),
        borderSide: BorderSide(color: AppColors.primary, width: 2),
      ),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
    ),
  );
}
