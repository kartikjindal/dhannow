import 'package:flutter/material.dart';

import '../theme/tokens.dart';

enum DnTone { ok, warn, err, info, cobalt, neutral, ink, line }

(Color bg, Color fg) _tone(DnTone t) => switch (t) {
      DnTone.ok => (DnTokens.okBg, DnTokens.okInk),
      DnTone.warn => (DnTokens.warnBg, DnTokens.warnInk),
      DnTone.err => (DnTokens.errBg, DnTokens.errInk),
      DnTone.info => (DnTokens.infoBg, DnTokens.infoInk),
      DnTone.cobalt => (DnTokens.tint, DnTokens.primary700),
      DnTone.neutral => (DnTokens.grey200, DnTokens.slate),
      DnTone.ink => (DnTokens.ink, Colors.white),
      DnTone.line => (DnTokens.paper, DnTokens.slate),
    };

/// Kit `.pill`: uppercase 10px status pill ("ACTIVE", "DUE TODAY").
class DnPill extends StatelessWidget {
  const DnPill(this.text, {super.key, this.tone = DnTone.neutral, this.dot = false});

  final String text;
  final DnTone tone;
  final bool dot;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = _tone(tone);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(DnTokens.pill),
        border: tone == DnTone.line ? Border.all(color: DnTokens.line2) : null,
      ),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        if (dot) ...[Container(width: 6, height: 6, decoration: BoxDecoration(color: fg, shape: BoxShape.circle)), const SizedBox(width: 5)],
        Flexible(child: Text(text.toUpperCase(), overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 10, height: 1.3, fontWeight: FontWeight.w700, letterSpacing: .5, color: fg))),
      ]),
    );
  }
}

/// Ink chip in mixed case ("Extended 1 of 3"), optionally error or warning filled.
class DnInkChip extends StatelessWidget {
  const DnInkChip(this.text, {super.key, this.icon, this.color = DnTokens.ink});

  final String text;
  final IconData? icon;
  final Color color;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
        decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(DnTokens.pill)),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          if (icon != null) ...[Icon(icon, size: 14, color: Colors.white), const SizedBox(width: 6)],
          Flexible(child: Text(text, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: Colors.white))),
        ]),
      );
}

/// Kit `DeltaChip`: "+1 level", "−2 days". Sign picks the tone.
class DnDeltaChip extends StatelessWidget {
  const DnDeltaChip(this.text, {super.key, required this.sign, this.onDark = false});

  final String text;

  /// On the dark panel the translucent mint has no contrast, so it goes solid.
  final bool onDark;

  /// >0 mint, <0 error, 0 neutral.
  final int sign;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = sign > 0
        ? (onDark ? DnTokens.mint : DnTokens.mint.withValues(alpha: .32), DnTokens.mintInk)
        : sign < 0
            ? (DnTokens.err, Colors.white)
            : (DnTokens.grey200, DnTokens.slate);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(DnTokens.pill)),
      child: Text(text, style: TextStyle(fontSize: 11, fontWeight: FontWeight.w600, color: fg, fontFeatures: const [FontFeature.tabularFigures()])),
    );
  }
}
