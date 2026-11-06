import 'package:flutter/material.dart';

/// Every colour, radius, shadow and curve in the app. Values mirror the approved UI kit
/// (docs/wireframes/ui-kit.html `:root`), which mirrors navix app_navix theme.css with the
/// cobalt accent. Light only in v1 (CC-58), so these are plain constants; nothing else
/// hard-codes a colour.
abstract final class DnTokens {

  // Surfaces
  static const canvas = Color(0xFFE4E6E7);
  static const frame = Color(0xFFF2F3F3);
  static const surface = Color(0xFFFAFAFA);
  static const paper = Color(0xFFFFFFFF);

  // Text and lines
  static const ink = Color(0xFF0B0B0C);
  static const charcoal = Color(0xFF2A2C30);
  static const slate = Color(0xFF4A4D52);
  static const muted = Color(0xFF8B8F96);
  static const line = Color(0xFFE5E7E8);
  static const line2 = Color(0xFFD9DBDD);
  static const grey100 = Color(0xFFF1F2F2);
  static const grey200 = Color(0xFFE7E9E9);
  static const track = Color(0xFFD6D9DC);
  static const disabled = Color(0xFFE3E5E6);
  static const lockBg = Color(0xFFECEDEE);

  // Cobalt accent
  static const primary = Color(0xFF1F5BDB);
  static const primary600 = Color(0xFF1A4BB8);
  static const primary700 = Color(0xFF163F9C);
  static const tint = Color(0xFFEEF3FF);
  static const primarySoft = Color(0xFFD6E2FB);

  // Status
  static const ok = Color(0xFF17804F);
  static const okBg = Color(0xFFE3F7EC);
  static const okInk = Color(0xFF116A40);
  static const okLine = Color(0xFFC2EED6);
  static const warn = Color(0xFF8A6200);
  static const warnBg = Color(0xFFFEF6D8);
  static const warnInk = Color(0xFF6F4F00);
  static const warnLine = Color(0xFFFCEAA6);
  static const err = Color(0xFFC62A2F);
  static const errBg = Color(0xFFFDECEC);
  static const errInk = Color(0xFFA11F24);
  static const errLine = Color(0xFFFAD3D4);
  static const info = Color(0xFF6A4DE0);
  static const infoBg = Color(0xFFF1EDFE);
  static const infoInk = Color(0xFF5136B8);
  static const infoLine = Color(0xFFE0D8FC);

  // Chart companions
  static const sun = Color(0xFFF8CF40);
  static const mint = Color(0xFF5CD398);
  static const mintInk = Color(0xFF0D5533);
  static const violet = Color(0xFF8E6CF2);
  static const sky = Color(0xFF5BA3F0);

  static const onDark = Color(0xEBFFFFFF);
  static const onDarkMuted = Color(0x9EFFFFFF);
  static const scrim = Color(0x73111318);

  // Radii
  static const rSm = 12.0;
  static const r = 16.0;
  static const rLg = 22.0;
  static const rXl = 28.0;
  static const pill = 999.0;

  // Spacing: page gutter and the gap between stacked panels.
  static const gutter = 16.0;
  static const gap = 12.0;

  // Shadows (cool, layered). Flutter has no inset shadow, so the white top edge is a 1px top border
  // on the panel itself (see DnPanel).
  static const _s = Color(0xFF111318);
  static List<BoxShadow> get shXs => [BoxShadow(color: _s.withValues(alpha: .05), offset: const Offset(0, 1), blurRadius: 2)];
  static List<BoxShadow> get shSm => [
        BoxShadow(color: _s.withValues(alpha: .04), offset: const Offset(0, 1), blurRadius: 2),
        BoxShadow(color: _s.withValues(alpha: .08), offset: const Offset(0, 4), blurRadius: 14, spreadRadius: -6),
      ];
  static List<BoxShadow> get sh => [
        BoxShadow(color: _s.withValues(alpha: .04), offset: const Offset(0, 1), blurRadius: 2),
        BoxShadow(color: _s.withValues(alpha: .14), offset: const Offset(0, 10), blurRadius: 28, spreadRadius: -14),
      ];
  static List<BoxShadow> get shMd => [
        BoxShadow(color: _s.withValues(alpha: .04), offset: const Offset(0, 2), blurRadius: 4),
        BoxShadow(color: _s.withValues(alpha: .18), offset: const Offset(0, 18), blurRadius: 40, spreadRadius: -20),
      ];
  static List<BoxShadow> get shLg => [
        BoxShadow(color: _s.withValues(alpha: .05), offset: const Offset(0, 2), blurRadius: 6),
        BoxShadow(color: _s.withValues(alpha: .28), offset: const Offset(0, 40), blurRadius: 80, spreadRadius: -36),
      ];
  static List<BoxShadow> get shPill => [
        BoxShadow(color: _s.withValues(alpha: .06), offset: const Offset(0, 1), blurRadius: 2),
        BoxShadow(color: _s.withValues(alpha: .10), offset: const Offset(0, 3), blurRadius: 10, spreadRadius: -4),
      ];
  static List<BoxShadow> get shPrimary =>
      [BoxShadow(color: primary.withValues(alpha: .55), offset: const Offset(0, 10), blurRadius: 24, spreadRadius: -12)];

  /// Focus rings: cobalt for buttons, faint ink for fields.
  static List<BoxShadow> get ringInk => [BoxShadow(color: _s.withValues(alpha: .07), spreadRadius: 4)];
  static List<BoxShadow> get ringErr => [BoxShadow(color: err.withValues(alpha: .12), spreadRadius: 4)];

  // Motion (kit: rise / growY / pop).
  static const easeOut = Cubic(.16, 1, .3, 1);
  static const popCurve = Cubic(.34, 1.56, .64, 1);
  static const rise = Duration(milliseconds: 600);
  static const grow = Duration(milliseconds: 400);
  static const pop = Duration(milliseconds: 450);

  static const darkPanel = LinearGradient(
    begin: Alignment(-.5, -1),
    end: Alignment(.5, 1),
    colors: [Color(0xFF222326), Color(0xFF050506)],
  );
}

/// Font families bundled in assets/fonts (subset to Latin + ₹). Bebas Neue has no ₹ or lowercase,
/// so display text falls back to Inter for those glyphs.
abstract final class DnFonts {
  static const body = 'Inter';
  static const display = 'BebasNeue';
}

/// Motion is off when the OS asks for reduced motion (kit rule; CC-58 accessibility).
extension DnMotion on BuildContext {
  bool get reduceMotion => MediaQuery.maybeDisableAnimationsOf(this) ?? false;
  Duration motion(Duration d) => reduceMotion ? Duration.zero : d;
}
