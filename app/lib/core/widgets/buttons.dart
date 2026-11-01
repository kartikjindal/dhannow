import 'package:flutter/material.dart';

import '../theme/tokens.dart';

enum DnButtonKind { primary, dark, outline, ghost, danger, text }

enum DnButtonSize { md, sm, xs }

/// Kit `.btn`: pill buttons. While [loading] the button shows a spinner and ignores taps
/// (wireframe rule E: primary buttons lock while a request is in flight). A null [onPressed]
/// renders the disabled look.
class DnButton extends StatelessWidget {
  const DnButton(
    this.label, {
    super.key,
    required this.onPressed,
    this.kind = DnButtonKind.primary,
    this.size = DnButtonSize.md,
    this.icon,
    this.arrow = false,
    this.loading = false,
    this.block = false,
  });

  const DnButton.dark(this.label, {super.key, required this.onPressed, this.size = DnButtonSize.md, this.icon, this.arrow = false, this.loading = false, this.block = false})
      : kind = DnButtonKind.dark;
  const DnButton.outline(this.label, {super.key, required this.onPressed, this.size = DnButtonSize.md, this.icon, this.arrow = false, this.loading = false, this.block = false})
      : kind = DnButtonKind.outline;
  const DnButton.ghost(this.label, {super.key, required this.onPressed, this.size = DnButtonSize.md, this.icon, this.arrow = false, this.loading = false, this.block = false})
      : kind = DnButtonKind.ghost;
  const DnButton.danger(this.label, {super.key, required this.onPressed, this.size = DnButtonSize.md, this.icon, this.arrow = false, this.loading = false, this.block = false})
      : kind = DnButtonKind.danger;
  const DnButton.text(this.label, {super.key, required this.onPressed, this.icon, this.arrow = false})
      : kind = DnButtonKind.text,
        size = DnButtonSize.md,
        loading = false,
        block = false;

  final String label;
  final VoidCallback? onPressed;
  final DnButtonKind kind;
  final DnButtonSize size;
  final IconData? icon;
  final bool arrow;
  final bool loading;
  final bool block;

  @override
  Widget build(BuildContext context) {
    final enabled = onPressed != null;
    final (bg, fg, border, shadow) = switch (kind) {
      _ when !enabled && kind != DnButtonKind.text => (DnTokens.disabled, DnTokens.muted, DnTokens.disabled, const <BoxShadow>[]),
      DnButtonKind.primary => (DnTokens.primary, Colors.white, DnTokens.primary, DnTokens.shPrimary),
      DnButtonKind.dark => (DnTokens.ink, Colors.white, DnTokens.ink, DnTokens.shSm),
      DnButtonKind.outline => (DnTokens.paper, DnTokens.ink, DnTokens.line, DnTokens.shPill),
      DnButtonKind.ghost => (Colors.transparent, DnTokens.ink, DnTokens.line2, const <BoxShadow>[]),
      DnButtonKind.danger => (DnTokens.paper, DnTokens.err, DnTokens.errLine, const <BoxShadow>[]),
      DnButtonKind.text => (Colors.transparent, enabled ? DnTokens.primary600 : DnTokens.muted, Colors.transparent, const <BoxShadow>[]),
    };
    final (height, padX, fontSize) = switch (size) {
      _ when kind == DnButtonKind.text => (36.0, 2.0, 14.0),
      DnButtonSize.md => (52.0, 24.0, 15.0),
      DnButtonSize.sm => (40.0, 16.0, 13.0),
      DnButtonSize.xs => (32.0, 12.0, 12.0),
    };
    final text = TextStyle(fontSize: fontSize, fontWeight: FontWeight.w600, letterSpacing: -.15, color: fg);
    final content = Row(mainAxisSize: MainAxisSize.min, mainAxisAlignment: MainAxisAlignment.center, children: [
      if (loading) ...[
        SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2, color: fg, backgroundColor: fg.withValues(alpha: .3))),
        const SizedBox(width: 8),
      ] else if (icon != null) ...[
        Icon(icon, size: fontSize + 3, color: fg),
        const SizedBox(width: 8),
      ],
      Flexible(child: Text(label, style: text, overflow: TextOverflow.ellipsis, maxLines: 1)),
      if (arrow && !loading) ...[const SizedBox(width: 8), Icon(Icons.arrow_forward_rounded, size: fontSize + 3, color: fg)],
    ]);
    final shape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(DnTokens.pill), side: BorderSide(color: border));
    return Semantics(
      button: true,
      enabled: enabled && !loading,
      label: loading ? '$label, working' : null,
      excludeSemantics: loading,
      child: SizedBox(
        height: height,
        width: block ? double.infinity : null,
        child: DecoratedBox(
          decoration: ShapeDecoration(shape: shape, shadows: shadow),
          child: Material(
            color: bg,
            shape: shape,
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: enabled && !loading ? onPressed : null,
              child: Padding(padding: EdgeInsets.symmetric(horizontal: padX), child: Center(widthFactor: 1, child: content)),
            ),
          ),
        ),
      ),
    );
  }
}
