import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/brand.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';

/// Opens wherever `GET /journey` says (wireframe rule E6). OFFLINE goes to E3, other failures to
/// E4 with the request ID.
Future<void> openJourney(BuildContext context) async {
  try {
    final j = await api.journey();
    if (context.mounted) context.go(j.route);
  } on ApiException catch (e) {
    if (context.mounted) context.go(e.code == 'OFFLINE' ? '/offline' : '/error', extra: e.requestId);
  }
}

/// The cobalt "D" tile beside the wordmark (S1, S3).
class BrandMark extends StatelessWidget {
  const BrandMark({super.key, this.large = false});
  final bool large;

  @override
  Widget build(BuildContext context) {
    final tile = large ? 88.0 : 44.0;
    final mark = Container(
      width: tile,
      height: tile,
      alignment: Alignment.center,
      decoration: BoxDecoration(color: DnTokens.primary, borderRadius: BorderRadius.circular(large ? 24 : 12), boxShadow: DnTokens.shPrimary),
      child: Text(
        'D',
        style: TextStyle(fontSize: large ? 40 : 20, fontWeight: FontWeight.w700, color: Colors.white),
      ),
    );
    final word = Text(
      Brand.wordmark,
      style: TextStyle(fontSize: large ? 30 : 20, fontWeight: FontWeight.w700, letterSpacing: .5, color: DnTokens.ink),
    );
    return Semantics(
      label: Brand.name,
      excludeSemantics: true,
      child: large
          ? Column(mainAxisSize: MainAxisSize.min, children: [mark, const SizedBox(height: 14), word])
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                mark,
                const SizedBox(width: 10),
                Flexible(child: word),
              ],
            ),
    );
  }
}

/// Board S1. Shows for about a second, then the server journey decides where the app opens.
class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer(const Duration(seconds: 1), () => openJourney(context));
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(DnTokens.gutter),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const BrandMark(large: true),
              const SizedBox(height: 8),
              const Text(Brand.tagline, textAlign: TextAlign.center, style: DnText.body),
              const SizedBox(height: 24),
              SizedBox.square(
                dimension: 28,
                child: context.reduceMotion
                    ? const CircularProgressIndicator(value: .25, strokeWidth: 3, backgroundColor: DnTokens.line)
                    : const CircularProgressIndicator(strokeWidth: 3, backgroundColor: DnTokens.line),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

/// Board S2: three slides on first launch only. Skip or finishing marks first launch done.
class IntroScreen extends StatefulWidget {
  const IntroScreen({super.key});

  @override
  State<IntroScreen> createState() => _IntroScreenState();
}

class _IntroScreenState extends State<IntroScreen> {
  static const _slides = [
    (icon: Icons.phone_android_rounded, title: Brand.tagline, body: 'Apply for a short-term personal loan entirely on your phone. See every charge before you accept.'),
    (icon: Icons.bolt_rounded, title: 'Check eligibility in minutes', body: 'PAN, mobile and a few details. No branch visits.'),
    (icon: Icons.receipt_long_rounded, title: 'Clear costs, no surprises', body: 'Fee, GST, interest and repayment date shown before you sign.'),
  ];

  final _pages = PageController();
  int _index = 0;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  void _finish() {
    api.firstLaunch = false;
    context.go('/welcome');
  }

  void _next() {
    if (_index == _slides.length - 1) return _finish();
    _pages.nextPage(duration: context.motion(DnTokens.grow), curve: DnTokens.easeOut);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Column(
        children: [
          Align(
            alignment: Alignment.centerRight,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(0, 4, 8, 0),
              child: DnButton.text('Skip', onPressed: _finish),
            ),
          ),
          Expanded(
            child: PageView(
              controller: _pages,
              onPageChanged: (i) => setState(() => _index = i),
              children: [for (final s in _slides) PagerSlide(icon: s.icon, title: s.title, body: s.body)],
            ),
          ),
          DnPagerDots(
            count: _slides.length,
            index: _index,
            onTap: (i) => _pages.animateToPage(i, duration: context.motion(DnTokens.grow), curve: DnTokens.easeOut),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 16, DnTokens.gutter, 12),
            child: DnButton(_index == _slides.length - 1 ? 'Get started' : 'Next', block: true, arrow: true, onPressed: _next),
          ),
        ],
      ),
    ),
  );
}

/// One pager slide: tinted illustration slot over a title and a line of copy (S2, F3).
class PagerSlide extends StatelessWidget {
  const PagerSlide({super.key, required this.icon, required this.title, required this.body});
  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) => ListView(
    padding: const EdgeInsets.symmetric(horizontal: DnTokens.gutter, vertical: 8),
    children: [
      Container(
        height: 200,
        decoration: BoxDecoration(color: DnTokens.tint, borderRadius: BorderRadius.circular(DnTokens.rLg)),
        child: Icon(icon, size: 72, color: DnTokens.primary),
      ),
      const SizedBox(height: 24),
      Text(title, textAlign: TextAlign.center, style: DnText.section.copyWith(fontSize: 20)),
      const SizedBox(height: 8),
      Text(body, textAlign: TextAlign.center, style: DnText.body),
    ],
  );
}

/// Board S3 (Main). The worked example comes from the server's quote, never from literals.
class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  late Future<(Quote, List<Quote>)> _data = _load();

  Future<(Quote, List<Quote>)> _load() async => (await api.quote(100000), await api.quotes());

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 24, DnTokens.gutter, 16),
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: GestureDetector(onLongPress: demoControls ? () => context.push('/demo') : null, child: const BrandMark()),
                ),
                const SizedBox(height: 16),
                FutureBuilder(
                  future: _data,
                  builder: (context, snap) {
                    if (snap.data case (final q, final ladder)) return _example(q, ladder);
                    if (snap.hasError) {
                      final e = snap.error;
                      return DnStateView(
                        icon: Icons.cloud_off_rounded,
                        error: true,
                        title: 'Couldn’t load the example',
                        body: e is ApiException ? e.message : null,
                        primary: 'Try again',
                        onPrimary: () => setState(() => _data = _load()),
                      );
                    }
                    return const Column(children: [DnSkeleton(height: 150, radius: 24), SizedBox(height: 16), DnSkeleton(height: 34), SizedBox(height: 16), DnSkeleton(height: 140, radius: 22)]);
                  },
                ),
                const SizedBox(height: 16),
                const Text.rich(
                  TextSpan(
                    children: [
                      TextSpan(
                        text: 'Keep ready: ',
                        style: TextStyle(fontWeight: FontWeight.w600, color: DnTokens.ink),
                      ),
                      TextSpan(text: 'PAN, Aadhaar, salary bank account, last 3 payslips.'),
                    ],
                  ),
                  style: TextStyle(fontSize: 13, height: 1.5, color: DnTokens.slate),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 8),
            child: Column(
              children: [
                DnButton('Apply now', block: true, onPressed: () => context.push('/signup/start')),
                const SizedBox(height: 8),
                DnButton.outline('Log in', block: true, onPressed: () => context.push('/login')),
                const SizedBox(height: 4),
                DnButton.text('How we keep your money and data safe', icon: Icons.verified_user_rounded, onPressed: () => context.push('/trust')),
                DnButton.text('Need help? Call ${Brand.supportPhone}', onPressed: () => context.push('/support')),
              ],
            ),
          ),
        ],
      ),
    ),
  );

  Widget _example(Quote q, List<Quote> ladder) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Container(
        constraints: const BoxConstraints(minHeight: 150),
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(color: DnTokens.tint, borderRadius: BorderRadius.circular(24)),
        alignment: Alignment.bottomCenter,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Container(
                  width: 12,
                  height: 12,
                  decoration: const BoxDecoration(color: DnTokens.ink, shape: BoxShape.circle),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: Container(
                    height: 4,
                    decoration: BoxDecoration(color: DnTokens.primary, borderRadius: BorderRadius.circular(2)),
                  ),
                ),
                const SizedBox(width: 8),
                Container(
                  width: 28,
                  height: 28,
                  decoration: const BoxDecoration(color: DnTokens.primary, shape: BoxShape.circle),
                  child: const Icon(Icons.flag_rounded, size: 16, color: Colors.white),
                ),
              ],
            ),
            const SizedBox(height: 14),
            Wrap(
              alignment: WrapAlignment.spaceBetween,
              children: [
                const Text('Today', style: TextStyle(fontSize: 13, color: DnTokens.slate)),
                Text('Day ${q.tenureDays}', style: const TextStyle(fontSize: 13, color: DnTokens.slate)),
              ],
            ),
          ],
        ),
      ),
      const SizedBox(height: 16),
      Semantics(header: true, child: Text('Short-term loans, repaid in one go.', style: DnText.title)),
      const SizedBox(height: 6),
      Text('Start with ${inr(ladder.first.principalPaise)} and unlock up to ${inr(ladder.last.principalPaise)} as you repay on time.', style: DnText.body),
      const SizedBox(height: 16),
      DnPanel(
        padding: const EdgeInsets.fromLTRB(16, 4, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            DnBreakdown(lines: [DnLine('You borrow', q.principalPaise), DnLine('In your bank today', q.netPaise), DnLine('You repay on day ${q.tenureDays}', q.repayPaise)]),
            const SizedBox(height: 4),
            Text('${inr(q.deductionsPaise)} covers fee, GST and ${q.tenureDays} days’ interest, taken upfront. Nothing else.', style: DnText.hint),
          ],
        ),
      ),
    ],
  );
}
