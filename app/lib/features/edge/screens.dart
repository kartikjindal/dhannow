import 'dart:async';

import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/brand.dart';
import '../../core/format.dart';
import '../../core/links.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import '../start/splash.dart';

/// Shared layout of the E boards: icon disc, title, body, extras, then a main action and a way out.
class _EdgeView extends StatelessWidget {
  const _EdgeView({
    required this.icon,
    required this.title,
    required this.body,
    this.error = false,
    this.children = const [],
    required this.primary,
    required this.onPrimary,
    this.busy = false,
    this.secondary,
    this.onSecondary,
  });

  final IconData icon;
  final String title;
  final String body;
  final bool error;
  final List<Widget> children;
  final String primary;
  final VoidCallback? onPrimary;
  final bool busy;
  final String? secondary;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 24),
              children: [
                Align(alignment: Alignment.centerLeft, child: context.canPop() ? const DnBackPill() : const SizedBox(height: 34)),
                const SizedBox(height: 32),
                Center(
                  child: Container(
                    width: 76,
                    height: 76,
                    decoration: BoxDecoration(color: error ? DnTokens.errBg : DnTokens.grey100, shape: BoxShape.circle),
                    child: Icon(icon, size: 34, color: error ? DnTokens.err : DnTokens.ink),
                  ),
                ),
                const SizedBox(height: 20),
                Semantics(
                  header: true,
                  child: Text(title, textAlign: TextAlign.center, style: DnText.section.copyWith(fontSize: 22, height: 1.25)),
                ),
                const SizedBox(height: 8),
                Text(body, textAlign: TextAlign.center, style: DnText.body),
                if (children.isNotEmpty) const SizedBox(height: 20),
                ...children,
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 12),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                DnButton(primary, block: true, loading: busy, onPressed: onPrimary),
                if (secondary != null) ...[const SizedBox(height: 8), DnButton.ghost(secondary!, block: true, onPressed: onSecondary)],
              ],
            ),
          ),
        ],
      ),
    ),
  );
}

void _support(BuildContext context) => context.push('/support');

/// EXT-24: this build is below the server's `minVersion` (`GET /api/app/config`), so the app
/// stops here until it is updated. Support stays one tap away.
class UpdateRequiredScreen extends StatelessWidget {
  const UpdateRequiredScreen({super.key});

  @override
  Widget build(BuildContext context) => _EdgeView(
    icon: Icons.system_update_rounded,
    title: 'Update ${Brand.name} to continue',
    body: 'This version of the app is no longer supported. Update it from Google Play; your loan and details are safe.',
    primary: 'Open Google Play',
    onPrimary: () => openUrl(context, 'https://play.google.com/store/apps/details?id=com.dhannow.app'),
    secondary: 'Contact support',
    onSecondary: () => _support(context),
  );
}

/// Board E3, full-screen variant: nothing was sent, so retrying is safe.
class OfflineScreen extends StatefulWidget {
  const OfflineScreen({super.key});

  @override
  State<OfflineScreen> createState() => _OfflineScreenState();
}

class _OfflineScreenState extends State<OfflineScreen> {
  bool _busy = false;

  Future<void> _retry() async {
    // The demo's switch says so at once; the real check is the journey call below.
    if (demoControls && api.offline) return showDnToast(context, 'Still no internet. Check mobile data or Wi-Fi.', error: true);
    setState(() => _busy = true);
    await openJourney(context);
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) => _EdgeView(
    icon: Icons.wifi_off_rounded,
    title: 'No internet connection',
    body: 'Your progress is saved. Turn on mobile data or Wi-Fi and we’ll pick up from here.',
    primary: 'Try again',
    busy: _busy,
    onPrimary: _retry,
  );
}

/// Board E4. [requestId] is the server's reference, passed as `extra` (a String or an
/// [ApiException]).
class ServerErrorScreen extends StatelessWidget {
  const ServerErrorScreen({super.key, this.requestId});
  final String? requestId;

  @override
  Widget build(BuildContext context) => _EdgeView(
    icon: Icons.error_outline_rounded,
    error: true,
    title: 'That didn’t go through',
    body: 'Our server didn’t respond. Your details are safe and nothing was charged.',
    primary: 'Try again',
    onPrimary: () => context.canPop() ? context.pop() : openJourney(context),
    secondary: 'Contact support',
    onSecondary: () => _support(context),
    children: [
      if (requestId != null) ...[
        DnCopyRow(label: 'Reference', value: requestId!),
        const SizedBox(height: 6),
        const Text('Share this with support if it happens again.', textAlign: TextAlign.center, style: DnText.hint),
      ],
    ],
  );
}

/// Board E5: confirm with a code, then carry on where the journey says.
class SessionExpiredScreen extends StatefulWidget {
  const SessionExpiredScreen({super.key});

  @override
  State<SessionExpiredScreen> createState() => _SessionExpiredScreenState();
}

class _SessionExpiredScreenState extends State<SessionExpiredScreen> {
  final _code = TextEditingController();
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _continue() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await api.verifyOtp(_code.text);
      if (mounted) await openJourney(context);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'OTP_LOCKED') return context.go('/otp-locked');
      if (e.code == 'OFFLINE') context.push('/offline');
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
    listenable: _code,
    builder: (context, _) => _EdgeView(
      icon: Icons.lock_clock_rounded,
      title: 'Please confirm it’s you',
      body: 'For your safety you’re logged out after 7 days. Enter the code we sent to your registered mobile number to carry on from the same screen.',
      primary: 'Continue',
      busy: _busy,
      onPrimary: _code.text.length == 6 ? _continue : null,
      secondary: 'Use a different number',
      onSecondary: () => context.go('/login'),
      children: [
        const Text('6-digit code', style: DnText.label),
        const SizedBox(height: 7),
        DnOtpField(controller: _code, enabled: !_busy, state: _error == null ? DnOtpState.idle : DnOtpState.error, onCompleted: (_) => _continue()),
        if (_error != null) ...[const SizedBox(height: 10), DnBanner(tone: DnBannerTone.err, title: _error!)],
      ],
    ),
  );
}

/// Sentence-case labels for `Api.signupSteps`, joined into the E6 lines.
const _stepLabels = {
  'start': 'details',
  'otp': 'mobile',
  'set-password': 'password',
  'employment': 'employment',
  'employer': 'employer',
  'email': 'emails',
  'bank': 'salary account',
  'payslips': 'salary slips',
  'aadhaar': 'Aadhaar card',
  'pan-card': 'PAN card',
  'consent': 'consent',
};

String _join(Iterable<String> steps) {
  final s = steps.map((k) => _stepLabels[k] ?? k).join(', ');
  return s.isEmpty ? s : s[0].toUpperCase() + s.substring(1);
}

/// Board E6: the next sign-up step comes from the server, so it is the same on any phone.
class ResumeScreen extends StatefulWidget {
  const ResumeScreen({super.key});

  @override
  State<ResumeScreen> createState() => _ResumeScreenState();
}

class _ResumeScreenState extends State<ResumeScreen> {
  late final Future<Customer> _me = api.me();

  @override
  Widget build(BuildContext context) => FutureBuilder(
    future: _me,
    builder: (context, me) => ListenableBuilder(
      listenable: api,
      builder: (context, _) {
        const steps = Api.signupSteps;
        final next = api.nextSignupStep;
        final done = steps.where(api.onboardingDone.contains).toList();
        final later = steps.where((s) => s != next && !done.contains(s)).toList();
        return DnPage(
          title: me.hasData ? 'Welcome back, ${me.data!.firstName}' : 'Welcome back',
          bottom: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              DnButton('Continue application', block: true, arrow: true, onPressed: () => context.go('/signup/$next')),
              DnButton.text('Go to dashboard', onPressed: () => context.go('/dashboard')),
            ],
          ),
          children: [
            DnPanel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Your application is ${done.length} of ${steps.length} steps done', style: DnText.section),
                  const SizedBox(height: 12),
                  DnProgressBar(value: done.length / steps.length, semanticLabel: 'Application progress'),
                  const SizedBox(height: 14),
                  if (done.isNotEmpty) _line(_join(done), DnTokens.ok, Icons.check_rounded),
                  if (next != 'submitted') _line('Next: ${_stepLabels[next] ?? next}', DnTokens.primary, null, strong: true),
                  if (later.isNotEmpty) _line(_join(later), DnTokens.track, null),
                  const SizedBox(height: 6),
                  const Text('Keep your last 6 months’ bank statement ready.', style: DnText.hint),
                ],
              ),
            ),
            const SizedBox(height: 12),
            const Text('Steps you finished are saved. They open view-only with Edit.', style: DnText.hint),
          ],
        );
      },
    ),
  );

  Widget _line(String text, Color dot, IconData? icon, {bool strong = false}) => Padding(
    padding: const EdgeInsets.only(bottom: 10),
    child: Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: 18,
          height: 18,
          margin: const EdgeInsets.only(top: 2, right: 10),
          decoration: BoxDecoration(color: dot, shape: BoxShape.circle),
          child: icon == null ? null : Icon(icon, size: 12, color: Colors.white),
        ),
        Expanded(child: Text(text, style: strong ? DnText.strong : DnText.body)),
      ],
    ),
  );
}

/// Board E7: too many codes. The countdown is always shown, never just "try later".
class OtpLockedScreen extends StatefulWidget {
  const OtpLockedScreen({super.key, this.wait});

  /// Time left on the lock, from the server's TOO_MANY_ATTEMPTS. Demo default: 15 minutes.
  final Duration? wait;

  @override
  State<OtpLockedScreen> createState() => _OtpLockedScreenState();
}

class _OtpLockedScreenState extends State<OtpLockedScreen> {
  late Duration _left = widget.wait ?? const Duration(minutes: 15);
  late final Timer _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(seconds: 1), (t) {
      setState(() => _left -= const Duration(seconds: 1));
      if (_left <= Duration.zero) t.cancel();
    });
  }

  @override
  void dispose() {
    _tick.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final mm = _left.inMinutes.toString().padLeft(2, '0');
    final ss = (_left.inSeconds % 60).toString().padLeft(2, '0');
    return _EdgeView(
      icon: Icons.schedule_rounded,
      title: 'Let’s pause for a few minutes',
      body: _left > Duration.zero ? 'We’ve sent several codes to this number. For your safety, you can request a new one in $mm:$ss.' : 'You can request a new code now. Your progress is saved.',
      primary: 'Log in with password instead',
      onPrimary: () => context.go('/login'),
      secondary: 'Contact support',
      onSecondary: () => _support(context),
    );
  }
}

enum UploadProblem { tooBig, wrongType, failed, locked }

/// One failed file on board E8 (the tile that failed says what to fix; other files stay uploaded).
class UploadErrorTile extends StatefulWidget {
  const UploadErrorTile({super.key, required this.file, required this.problem, required this.onAction});

  final String file;
  final UploadProblem problem;

  /// Choose another file / Retry upload; for [UploadProblem.locked] it gets the PDF password.
  final ValueChanged<String?> onAction;

  @override
  State<UploadErrorTile> createState() => _UploadErrorTileState();
}

class _UploadErrorTileState extends State<UploadErrorTile> {
  final _password = TextEditingController();

  @override
  void dispose() {
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final (message, action) = switch (widget.problem) {
      UploadProblem.tooBig => ('File must be under 5 MB. We shrink photos automatically; for PDFs, export a smaller copy.', 'Choose another file'),
      UploadProblem.wrongType => ('Upload a PDF or a photo (JPG, PNG or HEIC).', 'Choose another file'),
      UploadProblem.failed => ('Upload failed. Check your connection and try again.', 'Retry upload'),
      UploadProblem.locked => ('Enter the PDF password so our team can open it. Usually your DOB or customer ID.', 'Save password'),
    };
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: DnTokens.paper,
        borderRadius: BorderRadius.circular(DnTokens.r),
        border: Border.all(color: DnTokens.errLine),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              const Icon(Icons.error_outline_rounded, size: 20, color: DnTokens.err),
              const SizedBox(width: 8),
              Expanded(child: Text(widget.file, style: DnText.strong)),
            ],
          ),
          const SizedBox(height: 6),
          Text(message, style: DnText.hint.copyWith(color: DnTokens.errInk)),
          if (widget.problem == UploadProblem.locked) ...[const SizedBox(height: 10), DnField(label: 'PDF password', controller: _password, obscure: true)],
          const SizedBox(height: 10),
          DnButton.outline(action, size: DnButtonSize.sm, onPressed: () => widget.onAction(widget.problem == UploadProblem.locked ? _password.text : null)),
        ],
      ),
    );
  }
}

/// Board E8. Pushed with `extra: (file: ..., problem: ...)` it shows that file and pops with the
/// tile's result; without it, it shows each kind of failure as on the board.
class UploadErrorScreen extends StatelessWidget {
  const UploadErrorScreen({super.key, this.failure});
  final ({String file, UploadProblem problem})? failure;

  static const _examples = [
    (file: 'Payslip_Sep.pdf', problem: UploadProblem.tooBig),
    (file: 'statement.xlsx', problem: UploadProblem.wrongType),
    (file: 'Aadhaar back', problem: UploadProblem.failed),
    (file: 'Password-protected PDF', problem: UploadProblem.locked),
  ];

  @override
  Widget build(BuildContext context) {
    void done(String? result) => context.canPop() ? context.pop(result ?? true) : showDnToast(context, 'Saved');
    return DnPage(
      title: 'Upload problem',
      subtitle: 'Only the file below needs fixing. Your other files stay uploaded.',
      back: true,
      children: [
        for (final f in failure == null ? _examples : [failure!]) ...[UploadErrorTile(file: f.file, problem: f.problem, onAction: done), const SizedBox(height: 10)],
      ],
    );
  }
}

/// Board E9: camera blocked with "Don't ask again". [selfie] hides the file option (selfie has none).
class CameraDeniedScreen extends StatelessWidget {
  const CameraDeniedScreen({super.key, this.selfie = false});
  final bool selfie;

  @override
  Widget build(BuildContext context) => _EdgeView(
    icon: Icons.no_photography_rounded,
    title: 'Allow camera to continue',
    body: 'We need the camera only for your selfie and document photos. It never turns on by itself.',
    primary: 'Open settings',
    // ponytail: demo has no permission plugin; the release build opens Android app settings here.
    onPrimary: () => showDnToast(context, 'In the release app this opens Android settings.'),
    secondary: selfie ? null : 'Upload a file instead',
    onSecondary: () => context.canPop() ? context.pop('file') : context.go('/dashboard'),
  );
}

/// Board E11. The date to apply again is always shown (server NOT_ELIGIBLE carries it).
class NotEligibleScreen extends StatelessWidget {
  const NotEligibleScreen({super.key, this.applyAgainOn});
  final DateTime? applyAgainOn;

  @override
  Widget build(BuildContext context) {
    // Demo default: the 90-day cool-off for a low credit score.
    final date = dayMonth(applyAgainOn ?? DateTime.now().add(const Duration(days: 90)), year: true);
    return _EdgeView(
      icon: Icons.event_rounded,
      title: 'You can apply again on $date',
      body: 'You’re not eligible for a loan at the moment. We’ll remind you on that date.',
      primary: 'Remind me',
      onPrimary: () => showDnToast(context, 'We’ll remind you on $date'),
      secondary: 'Contact support',
      onSecondary: () => _support(context),
    );
  }
}

/// A payment the Accountant could not match (E12).
typedef RejectedPayment = ({int paise, String utr, String reason});

/// Board E12. Pass the payment as `extra`; the demo falls back to the open loan.
class PaymentRejectedScreen extends StatelessWidget {
  const PaymentRejectedScreen({super.key, this.payment});
  final RejectedPayment? payment;

  @override
  Widget build(BuildContext context) {
    final p = payment ?? (paise: api.loan?.outstandingPaise ?? 0, utr: '429011238867', reason: 'UTR not found in our bank statement');
    final utr = p.utr.replaceAllMapped(RegExp(r'.{4}(?!$)'), (m) => '${m[0]} ');
    return DnPage(
      title: 'We couldn’t match this payment',
      back: true,
      bottom: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          DnButton('Submit correct details', block: true, onPressed: () => context.go('/repay')),
          const SizedBox(height: 8),
          DnButton.ghost('Talk to us', block: true, onPressed: () => _support(context)),
        ],
      ),
      children: [
        DnPanel(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 4),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              if (p.paise > 0) _row('Amount', inr(p.paise), first: true),
              _row('UTR you entered', utr, first: p.paise <= 0),
              _row('Reason', p.reason),
            ],
          ),
        ),
        const SizedBox(height: 12),
        const DnPanel(
          padding: EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('What to do', style: DnText.strong),
              SizedBox(height: 8),
              Text('If money left your account: check the UTR in your UPI app or bank SMS and submit again with a screenshot.', style: DnText.hint),
              SizedBox(height: 6),
              Text('If the payment failed: your balance is unchanged. Pay again from Repay.', style: DnText.hint),
              SizedBox(height: 6),
              Text('Late charges don’t apply for days the payment was under review if it is later verified.', style: DnText.hint),
            ],
          ),
        ),
        const SizedBox(height: 12),
        Text('Call ${api.config.support.phone} (${Brand.supportHours}) or write to ${api.config.support.email}.', style: DnText.hint),
      ],
    );
  }

  Widget _row(String label, String value, {bool first = false}) => Container(
    padding: const EdgeInsets.symmetric(vertical: 10),
    decoration: BoxDecoration(
      border: first ? null : const Border(top: BorderSide(color: DnTokens.line)),
    ),
    child: Wrap(
      alignment: WrapAlignment.spaceBetween,
      spacing: 12,
      runSpacing: 2,
      children: [
        Text(label, style: DnText.body),
        Text(value, style: DnText.strong),
      ],
    ),
  );
}

/// Board F3: a short walkthrough for a first-time borrower, from the board's "loan active",
/// "repay" and "always a person" moments. Finishing opens the dashboard.
class WalkthroughScreen extends StatefulWidget {
  const WalkthroughScreen({super.key});

  @override
  State<WalkthroughScreen> createState() => _WalkthroughScreenState();
}

class _WalkthroughScreenState extends State<WalkthroughScreen> {
  static final _slides = [
    (icon: Icons.home_rounded, title: 'Your loan at a glance', body: 'Home shows the amount, the due date and one Repay button. Everything else sits behind View more.'),
    (icon: Icons.currency_rupee_rounded, title: 'Repay by UPI or bank transfer', body: 'Pay, then enter the UTR. We check it and tell you once it’s verified, then send your closure receipt.'),
    (icon: Icons.trending_up_rounded, title: 'Repay on time, borrow more', body: 'Each full on-time repayment unlocks the next level for your next loan.'),
    (icon: Icons.support_agent_rounded, title: 'A person is always one tap away', body: 'Every screen has a way to reach us. Call ${api.config.support.phone}, ${Brand.supportHours}.'),
  ];

  final _pages = PageController();
  int _index = 0;

  @override
  void dispose() {
    _pages.dispose();
    super.dispose();
  }

  void _next() {
    if (_index == _slides.length - 1) return context.go('/dashboard');
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
              child: DnButton.text('Skip', onPressed: () => context.go('/dashboard')),
            ),
          ),
          Expanded(
            child: PageView(
              controller: _pages,
              onPageChanged: (i) => setState(() => _index = i),
              children: [for (final s in _slides) PagerSlide(icon: s.icon, title: s.title, body: s.body)],
            ),
          ),
          DnPagerDots(count: _slides.length, index: _index),
          Padding(
            padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 16, DnTokens.gutter, 12),
            child: DnButton(_index == _slides.length - 1 ? 'Go to my dashboard' : 'Next', block: true, arrow: true, onPressed: _next),
          ),
        ],
      ),
    ),
  );
}
