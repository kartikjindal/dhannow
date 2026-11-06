import 'package:flutter/material.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';

/// Kit `Panel`: the near-white card (#FAFAFA, radius 22, soft layered shadow, white top edge).
/// `dark` is the ink gradient panel used for the credit level card.
class DnPanel extends StatelessWidget {
  const DnPanel({super.key, required this.child, this.padding = const EdgeInsets.all(20), this.dark = false, this.onTap});

  final Widget child;
  final EdgeInsetsGeometry padding;
  final bool dark;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final radius = BorderRadius.circular(DnTokens.rLg);
    final body = Padding(padding: padding, child: child);
    return DecoratedBox(
      decoration: BoxDecoration(
        color: dark ? null : DnTokens.surface,
        gradient: dark ? DnTokens.darkPanel : null,
        borderRadius: radius,
        border: dark ? null : const Border.fromBorderSide(BorderSide(color: DnTokens.line)),
        boxShadow: dark ? DnTokens.shMd : DnTokens.shSm,
      ),
      child: Material(
        type: MaterialType.transparency,
        borderRadius: radius,
        clipBehavior: Clip.antiAlias,
        child: DefaultTextStyle.merge(
          style: TextStyle(color: dark ? Colors.white : DnTokens.ink),
          child: Stack(children: [
            onTap == null ? body : InkWell(onTap: onTap, child: body),
            // The kit's `inset 0 1px 0 #fff` top edge.
            if (!dark) const Positioned(left: 0, right: 0, top: 0, child: ColoredBox(color: Color(0xE6FFFFFF), child: SizedBox(height: 1))),
          ]),
        ),
      ),
    );
  }
}

/// Kit `Tile`: the white inner tile inside a panel.
class DnTile extends StatelessWidget {
  const DnTile({super.key, required this.child, this.padding = const EdgeInsets.symmetric(horizontal: 16, vertical: 14)});

  final Widget child;
  final EdgeInsetsGeometry padding;

  @override
  Widget build(BuildContext context) => Container(
        padding: padding,
        decoration: BoxDecoration(
          color: DnTokens.paper,
          borderRadius: BorderRadius.circular(DnTokens.r),
          border: Border.all(color: DnTokens.line),
          boxShadow: DnTokens.shXs,
        ),
        child: child,
      );
}

/// Kit `PanelHeader`: section title, optional meta line, optional trailing control.
class DnPanelHeader extends StatelessWidget {
  const DnPanelHeader({super.key, required this.title, this.meta, this.trailing, this.dark = false});

  final String title;
  final String? meta;
  final Widget? trailing;
  final bool dark;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 16),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(title, style: DnText.section.copyWith(color: dark ? Colors.white : null)),
              if (meta != null)
                Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text(meta!, style: DnText.hint.copyWith(fontSize: 11.5, color: dark ? DnTokens.onDarkMuted : null)),
                ),
            ]),
          ),
          // Flexible so a long trailing label wraps instead of overflowing at 320dp × 2.0.
          if (trailing != null) ...[const SizedBox(width: 10), Flexible(child: trailing!)],
        ]),
      );
}

/// Kit icon pill: round white button for back / bell / help.
class DnIconPill extends StatelessWidget {
  const DnIconPill({super.key, required this.icon, required this.onPressed, required this.tooltip, this.badge = false, this.size = 40});

  final IconData icon;
  final VoidCallback? onPressed;
  final String tooltip;
  final bool badge;
  final double size;

  @override
  Widget build(BuildContext context) => Tooltip(
        message: tooltip,
        child: Container(
          width: size,
          height: size,
          decoration: BoxDecoration(
            color: DnTokens.paper,
            shape: BoxShape.circle,
            border: Border.all(color: DnTokens.line),
            boxShadow: DnTokens.shPill,
          ),
          child: Material(
            type: MaterialType.transparency,
            shape: const CircleBorder(),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: onPressed,
              child: Stack(alignment: Alignment.center, children: [
                Icon(icon, size: 20, color: DnTokens.ink, semanticLabel: tooltip),
                if (badge)
                  Positioned(
                    top: size * .18,
                    right: size * .2,
                    child: Container(
                      width: 8,
                      height: 8,
                      decoration: BoxDecoration(color: DnTokens.err, shape: BoxShape.circle, border: Border.all(color: DnTokens.paper, width: 2)),
                    ),
                  ),
              ]),
            ),
          ),
        ),
      );
}

/// Uppercase small label above a group ("RECENT", "DOCUMENTS").
class DnCaps extends StatelessWidget {
  const DnCaps(this.text, {super.key});
  final String text;

  @override
  Widget build(BuildContext context) => Text(text.toUpperCase(), style: DnText.caps);
}
