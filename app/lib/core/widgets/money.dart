import 'package:flutter/material.dart';

import '../format.dart';
import '../theme/theme.dart';
import '../theme/tokens.dart';

enum DnFigureSize { sm, md, lg, xl }

/// Kit `Figure`: a Bebas Neue amount over (or under) an Inter label.
class DnFigure extends StatelessWidget {
  const DnFigure({super.key, required this.paise, this.label, this.size = DnFigureSize.lg, this.trailing, this.color, this.labelFirst = true});

  final int paise;
  final String? label;
  final DnFigureSize size;
  final Widget? trailing;
  final Color? color;
  final bool labelFirst;

  @override
  Widget build(BuildContext context) {
    final px = switch (size) { DnFigureSize.sm => 26.0, DnFigureSize.md => 36.0, DnFigureSize.lg => 46.0, DnFigureSize.xl => 68.0 };
    final fg = color ?? DefaultTextStyle.of(context).style.color ?? DnTokens.ink;
    final lbl = label == null ? null : Text(label!, style: DnText.hint.copyWith(color: fg == Colors.white ? DnTokens.onDarkMuted : null));
    return Semantics(
      label: label == null ? inr(paise) : '$label ${inr(paise)}',
      excludeSemantics: true,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
        if (labelFirst && lbl != null) ...[lbl, const SizedBox(height: 4)],
        Row(crossAxisAlignment: CrossAxisAlignment.center, children: [
          // Long amounts shrink rather than overflow on 320dp screens and at 200% font (CC-58).
          Flexible(child: FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: Text(inr(paise), style: DnText.display(px, color: fg)))),
          if (trailing != null) ...[const SizedBox(width: 8), trailing!],
        ]),
        if (!labelFirst && lbl != null) ...[const SizedBox(height: 4), lbl],
      ]),
    );
  }
}

/// One line of a breakdown (B1, KFS summary, C5 pay).
class DnLine {
  const DnLine(this.label, this.paise, {this.minus = false});
  final String label;
  final int paise;

  /// Deductions show as "− ₹100" in slate.
  final bool minus;
}

/// Kit `.kv` rows ending in an ink-ruled total in display type.
class DnBreakdown extends StatelessWidget {
  const DnBreakdown({super.key, required this.lines, this.total});

  final List<DnLine> lines;
  final DnLine? total;

  @override
  Widget build(BuildContext context) => Column(children: [
        for (var i = 0; i < lines.length; i++)
          Container(
            padding: const EdgeInsets.symmetric(vertical: 9),
            decoration: BoxDecoration(border: i == 0 ? null : const Border(top: BorderSide(color: DnTokens.line))),
            child: Row(children: [
              Expanded(child: Text(lines[i].label, style: DnText.body.copyWith(height: 1.4))),
              Text(
                lines[i].minus ? '− ${inr(lines[i].paise)}' : inr(lines[i].paise),
                style: DnText.money.copyWith(color: lines[i].minus ? DnTokens.slate : null, fontWeight: lines[i].minus ? FontWeight.w500 : null),
              ),
            ]),
          ),
        if (total != null)
          Container(
            margin: const EdgeInsets.only(top: 2),
            padding: const EdgeInsets.only(top: 12),
            decoration: const BoxDecoration(border: Border(top: BorderSide(color: DnTokens.ink, width: 1.5))),
            child: Row(crossAxisAlignment: CrossAxisAlignment.baseline, textBaseline: TextBaseline.alphabetic, children: [
              Expanded(child: Text(total!.label, style: DnText.strong)),
              // Tight so the figure sits at the right edge, under the line amounts.
              Flexible(fit: FlexFit.tight, child: FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerRight, child: Text(inr(total!.paise), style: DnText.display(30)))),
            ]),
          ),
      ]);
}

/// Kit `MetricTile`: small labelled value in a white tile.
class DnMetricTile extends StatelessWidget {
  const DnMetricTile({super.key, required this.label, required this.value, this.caption});

  final String label;
  final String value;
  final String? caption;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: DnTokens.paper,
          borderRadius: BorderRadius.circular(DnTokens.r),
          border: Border.all(color: DnTokens.line),
          boxShadow: DnTokens.shXs,
        ),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text(label, style: DnText.hint),
          const SizedBox(height: 6),
          Text(value, style: DnText.display(26)),
          if (caption != null) ...[const SizedBox(height: 4), Text(caption!, style: DnText.hint.copyWith(fontSize: 11, color: DnTokens.muted))],
        ]),
      );
}

/// Ladder amount chips (B1 choose amount, C10 reborrow). Amounts above [maxPaise] are locked.
class DnLadderChips extends StatelessWidget {
  const DnLadderChips({super.key, required this.amountsPaise, required this.maxPaise, required this.selected, required this.onSelected});

  final List<int> amountsPaise;
  final int maxPaise;
  final int? selected;
  final ValueChanged<int> onSelected;

  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, c) {
        final w = (c.maxWidth - 3 * 8) / 4;
        return Wrap(spacing: 8, runSpacing: 8, children: [
          for (final a in amountsPaise) SizedBox(width: w, child: _chip(a)),
        ]);
      });

  Widget _chip(int a) {
    final locked = a > maxPaise;
    final on = a == selected;
    final (bg, fg, border) = locked
        ? (DnTokens.lockBg, DnTokens.muted, DnTokens.lockBg)
        : on
            ? (DnTokens.ink, Colors.white, DnTokens.ink)
            : (DnTokens.paper, DnTokens.ink, DnTokens.line);
    return Semantics(
      button: true,
      selected: on,
      enabled: !locked,
      label: locked ? '${inr(a)}, locked' : inr(a),
      excludeSemantics: true,
      child: GestureDetector(
        onTap: locked ? null : () => onSelected(a),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          height: 44,
          alignment: Alignment.center,
          decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(DnTokens.pill), border: Border.all(color: border)),
          padding: const EdgeInsets.symmetric(horizontal: 6),
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Row(mainAxisSize: MainAxisSize.min, children: [
              if (locked) ...[Icon(Icons.lock_outline_rounded, size: 13, color: fg), const SizedBox(width: 4)],
              Text(inr(a), style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: fg, fontFeatures: const [FontFeature.tabularFigures()])),
            ]),
          ),
        ),
      ),
    );
  }
}
