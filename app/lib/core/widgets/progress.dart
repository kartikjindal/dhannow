import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';

/// Kit `SegmentBars` as the credit level bar: one segment per ladder level, filled up to
/// [level]. On a level-up the new segment fills in 600 ms (board F4 c).
class DnLevelBar extends StatelessWidget {
  const DnLevelBar({super.key, required this.level, required this.labels, this.dark = false});

  /// 1-based current level.
  final int level;

  /// One axis label per level ("1k", "2k" …).
  final List<String> labels;
  final bool dark;

  @override
  Widget build(BuildContext context) {
    final track = dark ? Colors.white.withValues(alpha: .15) : DnTokens.track;
    return Semantics(
      label: 'Credit level $level of ${labels.length}',
      excludeSemantics: true,
      child: Column(children: [
        Row(children: [
          for (var i = 0; i < labels.length; i++) ...[
            if (i > 0) const SizedBox(width: 4),
            Expanded(
              child: Container(
                height: 10,
                decoration: BoxDecoration(color: track, borderRadius: BorderRadius.circular(DnTokens.pill)),
                alignment: Alignment.centerLeft,
                child: TweenAnimationBuilder<double>(
                  tween: Tween(end: i < level ? 1 : 0),
                  duration: context.motion(const Duration(milliseconds: 600)),
                  curve: DnTokens.easeOut,
                  builder: (_, v, _) => FractionallySizedBox(
                    widthFactor: v,
                    child: Container(decoration: BoxDecoration(color: DnTokens.primary, borderRadius: BorderRadius.circular(DnTokens.pill))),
                  ),
                ),
              ),
            ),
          ],
        ]),
        const SizedBox(height: 6),
        Row(children: [
          for (var i = 0; i < labels.length; i++) ...[
            if (i > 0) const SizedBox(width: 4),
            Expanded(
              child: Text(
                labels[i],
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontSize: 10.5,
                  fontWeight: i == level - 1 ? FontWeight.w700 : FontWeight.w400,
                  color: i == level - 1 ? (dark ? Colors.white : DnTokens.ink) : (dark ? DnTokens.onDarkMuted : DnTokens.muted),
                  fontFeatures: const [FontFeature.tabularFigures()],
                ),
              ),
            ),
          ],
        ]),
      ]),
    );
  }
}

/// Thin progress bar: step header (6px) and upload progress (8px).
class DnProgressBar extends StatelessWidget {
  const DnProgressBar({super.key, required this.value, this.height = 6, this.semanticLabel});

  final double value;
  final double height;
  final String? semanticLabel;

  @override
  Widget build(BuildContext context) => Semantics(
        label: semanticLabel,
        value: '${(value * 100).round()}%',
        child: Container(
          height: height,
          decoration: BoxDecoration(color: DnTokens.line, borderRadius: BorderRadius.circular(DnTokens.pill)),
          alignment: Alignment.centerLeft,
          child: TweenAnimationBuilder<double>(
            tween: Tween(end: value.clamp(0, 1)),
            duration: context.motion(const Duration(milliseconds: 500)),
            curve: DnTokens.easeOut,
            builder: (_, v, _) => FractionallySizedBox(
              widthFactor: v,
              child: Container(decoration: BoxDecoration(color: DnTokens.primary, borderRadius: BorderRadius.circular(DnTokens.pill))),
            ),
          ),
        ),
      );
}

/// Kit `GoalProgress`: ink capsule with a mint fill (or [light]: grey with cobalt).
class DnGoalProgress extends StatelessWidget {
  const DnGoalProgress({super.key, required this.value, this.left, this.right, this.light = false});

  final double value;
  final String? left;
  final String? right;
  final bool light;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (left != null || right != null)
          Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Row(children: [
              Expanded(child: Text(left ?? '', style: DnText.hint.copyWith(fontSize: 11))),
              const SizedBox(width: 8),
              Flexible(fit: FlexFit.tight, child: Text(right ?? '', textAlign: TextAlign.end, style: DnText.hint.copyWith(fontSize: 11))),
            ]),
          ),
        Container(
          height: 16,
          padding: const EdgeInsets.all(3),
          decoration: BoxDecoration(color: light ? DnTokens.grey200 : DnTokens.ink, borderRadius: BorderRadius.circular(DnTokens.pill)),
          alignment: Alignment.centerLeft,
          child: TweenAnimationBuilder<double>(
            tween: Tween(end: value.clamp(0, 1)),
            duration: context.motion(const Duration(milliseconds: 700)),
            curve: DnTokens.easeOut,
            builder: (_, v, _) => FractionallySizedBox(
              widthFactor: v,
              child: Container(decoration: BoxDecoration(color: light ? DnTokens.primary : DnTokens.mint, borderRadius: BorderRadius.circular(DnTokens.pill))),
            ),
          ),
        ),
      ]);
}

/// Dashboard countdown ring to `due_at` (board F4 d). [daysLeft] comes from the server
/// (CC-44: device time is never used); 0 = due today, negative = days late. Red on due/overdue.
class DnCountdownRing extends StatelessWidget {
  const DnCountdownRing({super.key, required this.daysLeft, this.tenureDays = 14, this.size = 96});

  final int daysLeft;
  final int tenureDays;
  final double size;

  @override
  Widget build(BuildContext context) {
    final late = daysLeft <= 0;
    final color = late ? DnTokens.err : DnTokens.primary;
    final fraction = late ? 1.0 : daysLeft / tenureDays;
    final (big, small) = daysLeft > 0
        ? ('$daysLeft', daysLeft == 1 ? 'day left' : 'days left')
        : daysLeft == 0
            ? ('0', 'due today')
            : ('${-daysLeft}', daysLeft == -1 ? 'day late' : 'days late');
    final sm = size < 80;
    return Semantics(
      label: '$big $small',
      excludeSemantics: true,
      child: SizedBox.square(
        dimension: size,
        child: TweenAnimationBuilder<double>(
          tween: Tween(end: fraction),
          duration: context.motion(const Duration(milliseconds: 600)),
          curve: DnTokens.easeOut,
          builder: (_, v, child) => CustomPaint(painter: _RingPainter(v, color, sm ? 6 : 8), child: child),
          // The ring is a fixed size, so its text shrinks to fit at large font scales.
          child: Padding(
            padding: EdgeInsets.all(size * .16),
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Column(mainAxisSize: MainAxisSize.min, children: [
                Text(big, style: DnText.display(sm ? 21 : 30, color: late ? DnTokens.err : DnTokens.ink).copyWith(height: .9)),
                Text(small, style: TextStyle(fontSize: sm ? 8.5 : 10, color: DnTokens.slate)),
              ]),
            ),
          ),
        ),
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  _RingPainter(this.value, this.color, this.stroke);
  final double value;
  final Color color;
  final double stroke;

  @override
  void paint(Canvas canvas, Size size) {
    final rect = Offset.zero & size;
    final r = rect.deflate(stroke / 2);
    canvas.drawArc(r, 0, math.pi * 2, false, Paint()
      ..color = DnTokens.line
      ..style = PaintingStyle.stroke
      ..strokeWidth = stroke);
    canvas.drawArc(r, -math.pi / 2, math.pi * 2 * value, false, Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeCap = StrokeCap.round
      ..strokeWidth = stroke);
  }

  @override
  bool shouldRepaint(_RingPainter old) => old.value != value || old.color != color;
}

enum DnStepState { done, now, todo, branch }

class DnStep {
  const DnStep(this.title, {this.detail, this.state = DnStepState.todo});
  final String title;
  final String? detail;
  final DnStepState state;
}

/// Milestone timeline (B12 status, C3). A newly reached step pops its dot and grows the
/// connector in 400 ms (board F4 a); side branches (overdue, on hold) are dashed amber.
class DnTimeline extends StatelessWidget {
  const DnTimeline({super.key, required this.steps});

  final List<DnStep> steps;

  @override
  Widget build(BuildContext context) => Column(children: [
        for (var i = 0; i < steps.length; i++)
          _StepRow(step: steps[i], last: i == steps.length - 1, nextDone: i + 1 < steps.length && steps[i + 1].state != DnStepState.todo),
      ]);
}

class _StepRow extends StatelessWidget {
  const _StepRow({required this.step, required this.last, required this.nextDone});

  final DnStep step;
  final bool last;
  final bool nextDone;

  @override
  Widget build(BuildContext context) {
    final s = step.state;
    final (dotBg, dotBorder) = switch (s) {
      DnStepState.done => (DnTokens.ok, DnTokens.ok),
      DnStepState.now => (DnTokens.primary, DnTokens.primary),
      DnStepState.todo => (DnTokens.paper, DnTokens.line2),
      DnStepState.branch => (DnTokens.warnBg, DnTokens.warn),
    };
    Widget dot = Container(
      width: 22,
      height: 22,
      decoration: BoxDecoration(
        color: dotBg,
        shape: BoxShape.circle,
        border: Border.all(color: dotBorder, width: 2),
        boxShadow: s == DnStepState.now ? [BoxShadow(color: DnTokens.primary.withValues(alpha: .16), spreadRadius: 5)] : null,
      ),
      child: switch (s) {
        DnStepState.done => const Icon(Icons.check_rounded, size: 13, color: Colors.white),
        DnStepState.now => Center(child: Container(width: 6, height: 6, decoration: const BoxDecoration(color: Colors.white, shape: BoxShape.circle))),
        _ => null,
      },
    );
    if (s == DnStepState.done || s == DnStepState.now) {
      dot = TweenAnimationBuilder<double>(
        tween: Tween(begin: context.reduceMotion ? 1 : .4, end: 1),
        duration: context.motion(DnTokens.pop),
        curve: DnTokens.popCurve,
        builder: (_, v, child) => Transform.scale(scale: v, child: child),
        child: dot,
      );
    }
    final title = TextStyle(
      fontSize: 14,
      height: 22 / 14,
      fontWeight: s == DnStepState.todo ? FontWeight.w500 : FontWeight.w600,
      color: switch (s) { DnStepState.todo => DnTokens.muted, DnStepState.branch => DnTokens.warnInk, _ => DnTokens.ink },
    );
    return Semantics(
      label: '${step.title}, ${switch (s) { DnStepState.done => 'done', DnStepState.now => 'current step', DnStepState.todo => 'not yet', DnStepState.branch => 'side step' }}',
      excludeSemantics: true,
      child: Padding(
        padding: EdgeInsets.only(left: s == DnStepState.branch ? 34 : 0),
        child: IntrinsicHeight(
          child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            SizedBox(
              width: 22,
              child: Column(children: [
                dot,
                if (!last)
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2),
                      // growY: scale from the top, which leaves layout alone.
                      child: TweenAnimationBuilder<double>(
                        tween: Tween(begin: context.reduceMotion || !(s == DnStepState.done && nextDone) ? 1 : 0, end: 1),
                        duration: context.motion(DnTokens.grow),
                        curve: DnTokens.easeOut,
                        builder: (_, v, child) => Transform(alignment: Alignment.topCenter, transform: Matrix4.diagonal3Values(1, v, 1), child: child),
                        child: Container(width: 2, decoration: BoxDecoration(color: s == DnStepState.done && nextDone ? DnTokens.ok : DnTokens.line2, borderRadius: BorderRadius.circular(2))),
                      ),
                    ),
                  ),
              ]),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Padding(
                padding: EdgeInsets.only(bottom: last ? 0 : 16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(step.title, style: title),
                  if (step.detail != null) Text(step.detail!, style: DnText.hint),
                ]),
              ),
            ),
          ]),
        ),
      ),
    );
  }
}

/// Kit `PagerDots`: onboarding carousel (S2–S4). The current dot widens to a pill.
class DnPagerDots extends StatelessWidget {
  const DnPagerDots({super.key, required this.count, required this.index, this.onTap});

  final int count;
  final int index;
  final ValueChanged<int>? onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        label: 'Page ${index + 1} of $count',
        excludeSemantics: true,
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          for (var i = 0; i < count; i++)
            GestureDetector(
              onTap: onTap == null ? null : () => onTap!(i),
              child: AnimatedContainer(
                duration: context.motion(const Duration(milliseconds: 300)),
                curve: DnTokens.easeOut,
                margin: const EdgeInsets.symmetric(horizontal: 3),
                width: i == index ? 22 : 8,
                height: 8,
                decoration: BoxDecoration(color: i == index ? DnTokens.ink : DnTokens.track, borderRadius: BorderRadius.circular(DnTokens.pill)),
              ),
            ),
        ]),
      );
}
