import 'package:flutter/material.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'progress.dart';
import 'surface.dart';

class DnNavItem {
  const DnNavItem(this.label, this.icon);
  final String label;
  final IconData icon;
}

/// Kit `PillNav` as the bottom bar: the active item sits in an ink pill.
class DnBottomNav extends StatelessWidget {
  const DnBottomNav({super.key, required this.items, required this.index, required this.onTap});

  final List<DnNavItem> items;
  final int index;
  final ValueChanged<int> onTap;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: const BoxDecoration(color: DnTokens.surface, border: Border(top: BorderSide(color: DnTokens.line))),
        child: SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(6, 8, 6, 10),
            child: Row(children: [
              for (var i = 0; i < items.length; i++)
                Expanded(
                  child: Semantics(
                    selected: i == index,
                    button: true,
                    label: items[i].label,
                    excludeSemantics: true,
                    child: InkResponse(
                      onTap: () => onTap(i),
                      radius: 32,
                      child: Column(mainAxisSize: MainAxisSize.min, children: [
                        AnimatedContainer(
                          duration: context.motion(const Duration(milliseconds: 250)),
                          width: 56,
                          height: 32,
                          decoration: BoxDecoration(color: i == index ? DnTokens.ink : Colors.transparent, borderRadius: BorderRadius.circular(DnTokens.pill)),
                          child: Icon(items[i].icon, size: 20, color: i == index ? Colors.white : DnTokens.slate),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          items[i].label,
                          style: TextStyle(fontSize: 11, fontWeight: i == index ? FontWeight.w600 : FontWeight.w400, color: i == index ? DnTokens.ink : DnTokens.slate),
                        ),
                      ]),
                    ),
                  ),
                ),
            ]),
          ),
        ),
      );
}

/// Kit `UnderlineTabs` (Loans: Active / Closed; Documents). [counts] are optional badges.
class DnUnderlineTabs extends StatelessWidget {
  const DnUnderlineTabs({super.key, required this.labels, required this.index, required this.onTap, this.counts});

  final List<String> labels;
  final int index;
  final ValueChanged<int> onTap;
  final List<int?>? counts;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: DnTokens.line))),
        child: Row(children: [
          for (var i = 0; i < labels.length; i++)
            Expanded(
              child: Semantics(
                selected: i == index,
                button: true,
                child: InkWell(
                  onTap: () => onTap(i),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    padding: const EdgeInsets.fromLTRB(6, 4, 6, 12),
                    decoration: BoxDecoration(border: Border(bottom: BorderSide(color: i == index ? DnTokens.ink : Colors.transparent, width: 2))),
                    child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
                      Flexible(child: Text(labels[i], maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: i == index ? DnTokens.ink : DnTokens.muted))),
                      if (counts?[i] != null) ...[
                        const SizedBox(width: 8),
                        Container(
                          constraints: const BoxConstraints(minWidth: 18),
                          height: 18,
                          padding: const EdgeInsets.symmetric(horizontal: 5),
                          alignment: Alignment.center,
                          decoration: BoxDecoration(color: i == index ? DnTokens.ink : DnTokens.grey200, borderRadius: BorderRadius.circular(DnTokens.pill)),
                          child: Text('${counts![i]}', style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: i == index ? Colors.white : DnTokens.slate)),
                        ),
                      ],
                    ]),
                  ),
                ),
              ),
            ),
        ]),
      );
}

/// Step header for journey screens: back pill, "Step 3 of 9", optional save link, progress bar,
/// then the Bebas page title and a subtitle.
class DnStepHeader extends StatelessWidget {
  const DnStepHeader({super.key, required this.title, this.subtitle, this.step, this.of, this.onBack, this.trailing});

  final String title;
  final String? subtitle;
  final int? step;
  final int? of;
  final VoidCallback? onBack;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        SizedBox(
          height: 56,
          child: Row(children: [
            if (onBack != null) ...[DnIconPill(icon: Icons.arrow_back_rounded, tooltip: 'Back', onPressed: onBack, size: 34), const SizedBox(width: 8)],
            Expanded(child: step != null && of != null ? Text('Step $step of $of', style: const TextStyle(fontSize: 13, color: DnTokens.slate)) : const SizedBox()),
            ?trailing,
          ]),
        ),
        if (step != null && of != null) ...[DnProgressBar(value: step! / of!, semanticLabel: 'Application progress'), const SizedBox(height: 18)],
        Semantics(header: true, child: Text(title, style: DnText.title)),
        if (subtitle != null) ...[const SizedBox(height: 6), Text(subtitle!, style: DnText.body)],
      ]);
}
