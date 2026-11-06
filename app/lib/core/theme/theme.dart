import 'package:flutter/material.dart';

import 'tokens.dart';

/// Type scale from the kit: Bebas Neue for page titles and headline money, Inter for the rest,
/// tabular figures wherever money is shown.
abstract final class DnText {
  static const _tnum = [FontFeature.tabularFigures()];

  static TextStyle display(double size, {Color color = DnTokens.ink}) => TextStyle(
        fontFamily: DnFonts.display,
        fontFamilyFallback: const [DnFonts.body],
        fontSize: size,
        height: .95,
        letterSpacing: size * .01,
        color: color,
        fontFeatures: _tnum,
      );

  /// Page title (h1).
  static TextStyle get title => display(34).copyWith(height: 1, letterSpacing: .7);
  static const section = TextStyle(fontFamily: DnFonts.body, fontSize: 15, fontWeight: FontWeight.w600, letterSpacing: -.15, color: DnTokens.ink);
  static const body = TextStyle(fontFamily: DnFonts.body, fontSize: 14, height: 1.5, color: DnTokens.slate);
  static const hint = TextStyle(fontFamily: DnFonts.body, fontSize: 12, height: 1.5, color: DnTokens.slate);
  static const strong = TextStyle(fontFamily: DnFonts.body, fontSize: 14, fontWeight: FontWeight.w600, color: DnTokens.ink);
  static const label = TextStyle(fontFamily: DnFonts.body, fontSize: 13, fontWeight: FontWeight.w600, color: DnTokens.charcoal);
  static const caps = TextStyle(fontFamily: DnFonts.body, fontSize: 11, fontWeight: FontWeight.w700, letterSpacing: .88, color: DnTokens.muted);
  static const money = TextStyle(fontFamily: DnFonts.body, fontSize: 14, fontWeight: FontWeight.w600, color: DnTokens.ink, fontFeatures: _tnum);
}

ThemeData dnTheme() {
  const scheme = ColorScheme.light(
    primary: DnTokens.primary,
    onPrimary: Colors.white,
    secondary: DnTokens.ink,
    onSecondary: Colors.white,
    surface: DnTokens.frame,
    onSurface: DnTokens.ink,
    error: DnTokens.err,
    onError: Colors.white,
    outline: DnTokens.line2,
    outlineVariant: DnTokens.line,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: scheme,
    fontFamily: DnFonts.body,
    scaffoldBackgroundColor: DnTokens.frame,
    splashFactory: InkSparkle.splashFactory,
    textTheme: const TextTheme(bodyMedium: DnText.body, bodyLarge: DnText.body, titleMedium: DnText.section, labelLarge: DnText.strong),
    appBarTheme: const AppBarTheme(
      backgroundColor: DnTokens.frame,
      foregroundColor: DnTokens.ink,
      elevation: 0,
      scrolledUnderElevation: 0,
      centerTitle: false,
    ),
    dividerTheme: const DividerThemeData(color: DnTokens.line, thickness: 1, space: 1),
    textSelectionTheme: const TextSelectionThemeData(cursorColor: DnTokens.ink, selectionColor: DnTokens.primarySoft),
    progressIndicatorTheme: const ProgressIndicatorThemeData(color: DnTokens.primary),
  );
}
