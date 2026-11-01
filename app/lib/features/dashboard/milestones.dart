import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';

/// Board F4 (4): the countdown ring sweeps from 0 to the share of days left when the dashboard
/// opens. The kit ring tweens between values, so the first frame is built with an unreachable
/// tenure (fraction ≈ 0) and the real tenure follows; the kit's tween does the sweep, and under
/// reduced motion it lands at once.
class SweepRing extends StatefulWidget {
  const SweepRing({super.key, required this.daysLeft, required this.tenureDays, this.size = 72});

  final int daysLeft;
  final int tenureDays;
  final double size;

  @override
  State<SweepRing> createState() => _SweepRingState();
}

class _SweepRingState extends State<SweepRing> {
  bool _opened = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) setState(() => _opened = true);
    });
  }

  @override
  Widget build(BuildContext context) => DnCountdownRing(
        daysLeft: widget.daysLeft,
        tenureDays: _opened || context.reduceMotion ? widget.tenureDays : 1 << 30,
        size: widget.size,
      );
}

/// Board F4 (3): credit level-up after a verified on-time repayment. The next segment fills
/// (600 ms), a soft blue halo pulses once, the headline rises in. No confetti. Late repayment:
/// plain "Loan closed", no animation.
class LevelUpCard extends StatefulWidget {
  const LevelUpCard({super.key, required this.level, this.closed});

  final CreditLevel level;
  final Loan? closed;

  /// Levels already celebrated.
  // ponytail: in memory, so it plays once per level per app run; persist it with the app's
  // storage layer when one lands ("remembered on the device", F4).
  @visibleForTesting
  static final played = <int>{};

  @override
  State<LevelUpCard> createState() => _LevelUpCardState();
}

class _LevelUpCardState extends State<LevelUpCard> with SingleTickerProviderStateMixin {
  late final _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1200));
  late int _shown = widget.level.level;

  bool get _onTime => widget.closed?.closedOnTime ?? false;
  bool get _celebrate => _onTime && widget.level.level > 1;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_c.isAnimating || _c.isCompleted) return;
    final lv = widget.level.level;
    if (!_celebrate || context.reduceMotion || LevelUpCard.played.contains(lv)) {
      _c.value = 1;
      return;
    }
    LevelUpCard.played.add(lv);
    _shown = lv - 1;
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      setState(() => _shown = lv);
      HapticFeedback.lightImpact();
      _c.forward();
    });
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final lv = widget.level;
    final closed = widget.closed;
    if (!_celebrate) {
      return DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Text('Loan closed', style: DnText.section.copyWith(fontSize: 17)),
          const SizedBox(height: 4),
          Text(closed == null ? 'Your previous loan is repaid and closed.' : 'Your ${inr(closed.quote.principalPaise)} loan is repaid and closed.', style: DnText.body),
        ]),
      );
    }
    final halo = CurvedAnimation(parent: _c, curve: const Interval(.25, .75));
    final rise = CurvedAnimation(parent: _c, curve: const Interval(.5, 1, curve: DnTokens.easeOut));
    return AnimatedBuilder(
      animation: halo,
      builder: (context, child) => DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(DnTokens.rLg),
          boxShadow: [BoxShadow(color: DnTokens.primary.withValues(alpha: .35 * math.sin(math.pi * halo.value)), blurRadius: 24, spreadRadius: 2)],
        ),
        child: child,
      ),
      child: DnPanel(
        dark: true,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          FadeTransition(
            opacity: rise,
            child: SlideTransition(
              position: Tween(begin: const Offset(0, .4), end: Offset.zero).animate(rise),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('Level ${lv.level} unlocked', style: DnText.display(30, color: Colors.white)),
                const SizedBox(height: 4),
                Text('Your next loan can be up to ${inr(lv.maxPaise)}.', style: DnText.body.copyWith(color: DnTokens.onDarkMuted)),
              ]),
            ),
          ),
          const SizedBox(height: 16),
          DnLevelBar(level: _shown, labels: levelLabels(lv), dark: true),
          if (closed != null) ...[
            const SizedBox(height: 14),
            Text('Your ${inr(closed.quote.principalPaise)} loan is closed and in good standing.', style: DnText.hint.copyWith(color: DnTokens.onDarkMuted)),
          ],
        ]),
      ),
    );
  }
}

/// "1k", "2k" … axis labels for the ladder.
List<String> levelLabels(CreditLevel lv) => [for (final p in lv.ladderPaise) '${p ~/ 100000}k'];
