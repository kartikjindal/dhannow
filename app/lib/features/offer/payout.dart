import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import 'common.dart';

/// Confirming the account starts disbursal. eNACH flags are OFF, so B11 is skipped.
Future<void> _confirmAndGo(BuildContext context, Future<bool> Function(Future<void> Function()) act) async {
  if (await act(api.confirmDisbursalAccount) && context.mounted) context.go('/loan/status');
}

/// B10 Where to send the money: the salary account from sign-up.
class PayoutScreen extends StatefulWidget {
  const PayoutScreen({super.key});

  @override
  State<PayoutScreen> createState() => _PayoutScreenState();
}

class _PayoutScreenState extends State<PayoutScreen> with Busy {
  // CC-57: FLAG_SECURE (bank details)
  late Future<Customer> _f = api.me();

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _f,
        builder: (context, s) {
          final wait = pending(context, s, () => setState(() { _f = api.me(); }));
          final me = s.data;
          return StepScaffold(
            title: 'Where to send the money',
            step: 10,
            of: 11,
            bottom: me == null ? null : OfferAction('Confirm this account', loading: busy, onPressed: () => _confirmAndGo(context, act)),
            children: wait != null || me == null
                ? [?wait]
                : [
                    DnPanel(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                        const DnPanelHeader(title: 'Where should we send the money?', meta: 'This is the salary account you gave us. Confirm it, or use a different one.'),
                        KvRow('Bank', me.bankName, first: true),
                        KvRow('Account number', me.bankMasked),
                        KvRow('IFSC', me.ifsc),
                        KvRow('Account holder', me.fullName),
                      ]),
                    ),
                    const SizedBox(height: 10),
                    const Text('Confirming will perform a ₹1 account check.', style: DnText.hint),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: DnButton.text('Send it to a different account instead', onPressed: busy ? null : () => context.push('/loan/disbursal-account/other')),
                    ),
                    const SizedBox(height: 8),
                    const BankProofCard(),
                  ],
          );
        },
      );
}

/// "Not able to verify your account?": cancelled cheque or passbook, checked by hand (B10, B10b).
class BankProofCard extends StatefulWidget {
  const BankProofCard({super.key});

  @override
  State<BankProofCard> createState() => _BankProofCardState();
}

class _BankProofCardState extends State<BankProofCard> with Busy {
  bool _added = false;

  @override
  Widget build(BuildContext context) => DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const DnPanelHeader(
            title: 'Not able to verify your account?',
            meta: 'Upload a cancelled cheque or your bank passbook instead — we’ll verify it manually before your loan is sent.',
          ),
          UploadSlot(label: 'Cheque or passbook', added: _added, onTap: () => setState(() => _added = true)),
          const SizedBox(height: 12),
          DnButton.outline('Upload proof & continue', loading: busy, onPressed: _added ? () => _confirmAndGo(context, act) : null),
        ]),
      );
}

/// B10b A different account, checked with ₹1.
class OtherAccountScreen extends StatefulWidget {
  const OtherAccountScreen({super.key});

  @override
  State<OtherAccountScreen> createState() => _OtherAccountScreenState();
}

class _OtherAccountScreenState extends State<OtherAccountScreen> with Busy {
  // CC-57: FLAG_SECURE (bank details)
  final _acct = TextEditingController();
  final _ifsc = TextEditingController();
  final _name = TextEditingController();
  String? _acctErr, _ifscErr, _nameErr;

  @override
  void dispose() {
    _acct.dispose();
    _ifsc.dispose();
    _name.dispose();
    super.dispose();
  }

  bool _validate() {
    setState(() {
      _acctErr = RegExp(r'^\d{9,18}$').hasMatch(_acct.text) ? null : 'Enter your account number (9 to 18 digits)';
      _ifscErr = RegExp(r'^[A-Z]{4}0[A-Z0-9]{6}$').hasMatch(_ifsc.text) ? null : 'Enter an 11-character IFSC, like HDFC0001234';
      _nameErr = _name.text.trim().length < 3 ? 'Enter the name on the account' : null;
    });
    return _acctErr == null && _ifscErr == null && _nameErr == null;
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Where to send the money',
        subtitle: 'Where should we send the money?',
        step: 10,
        of: 11,
        bottom: OfferAction('Verify & confirm', trust: false, loading: busy, onPressed: () {
          if (_validate()) _confirmAndGo(context, act);
        }),
        children: [
          DnField(
            label: 'Account number',
            controller: _acct,
            error: _acctErr,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(18)],
          ),
          const SizedBox(height: 14),
          DnField(
            label: 'IFSC code',
            controller: _ifsc,
            error: _ifscErr,
            textCapitalization: TextCapitalization.characters,
            inputFormatters: [FilteringTextInputFormatter.allow(RegExp('[A-Za-z0-9]')), LengthLimitingTextInputFormatter(11), _Upper()],
          ),
          const SizedBox(height: 14),
          DnField(label: 'Account holder name', controller: _name, error: _nameErr, textCapitalization: TextCapitalization.words),
          const SizedBox(height: 10),
          const Text('We’ll send ₹1 to check this account is real and belongs to you.', style: DnText.hint),
          Align(
            alignment: Alignment.centerLeft,
            child: DnButton.text('Use my salary account instead', onPressed: busy ? null : () => context.canPop() ? context.pop() : context.go('/loan/disbursal-account')),
          ),
          const SizedBox(height: 8),
          const BankProofCard(),
        ],
      );
}

class _Upper extends TextInputFormatter {
  @override
  TextEditingValue formatEditUpdate(TextEditingValue oldValue, TextEditingValue newValue) => newValue.copyWith(text: newValue.text.toUpperCase());
}

/// B11 Auto-repayment (eNACH). Built but unreachable from the journey while the flags are OFF.
class MandateScreen extends StatefulWidget {
  const MandateScreen({super.key});

  @override
  State<MandateScreen> createState() => _MandateScreenState();
}

class _MandateScreenState extends State<MandateScreen> with Busy {
  // CC-57: FLAG_SECURE (bank mandate)
  bool? _ok;

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Auto-repayment setup',
        step: 11,
        of: 11,
        bottom: OfferAction(_ok == false ? 'Try again' : 'Continue to your bank', loading: busy || _ok == true, onPressed: () async {
          final ok = await act(api.simulate);
          if (!context.mounted) return;
          setState(() => _ok = ok);
          if (ok) context.go('/loan/status');
        }),
        children: [
          const DnPanel(
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              DnPanelHeader(title: 'Set up auto-repayment'),
              Text(
                'To receive your loan, authorise an eMandate with your bank so your repayment can be collected automatically on the due date. The mandate allows debits up to a maximum amount (ceiling) that is higher than your loan.',
                style: DnText.body,
              ),
            ]),
          ),
          const SizedBox(height: DnTokens.gap),
          if (busy)
            const DnBanner(title: 'Confirming with your bank', body: 'Your bank’s page opened in a new tab. Authorise the mandate there, then come back — we’ll continue automatically.', icon: Icons.hourglass_top_rounded)
          else if (_ok == false)
            const DnBanner(tone: DnBannerTone.err, title: 'Setup failed', body: 'The setup did not complete. Nothing was charged — please try again.')
          else if (_ok == true)
            const DnBanner(tone: DnBannerTone.ok, title: 'Auto-repayment set up', body: 'Taking you to the next step…'),
        ],
      );
}

/// B12 Application status: the server's milestones, with "money on the way" while confirming.
class StatusScreen extends StatefulWidget {
  const StatusScreen({super.key});

  @override
  State<StatusScreen> createState() => _StatusScreenState();
}

class _StatusScreenState extends State<StatusScreen> {
  late Future<(List<StatusStep>, Customer)> _f = _load();
  int? _doneSteps;

  Future<(List<StatusStep>, Customer)> _load() async {
    final r = await (api.applicationStatus(), api.me()).wait;
    final done = r.$1.where((s) => s.done).length;
    // Board F4 a: a light tick when the server reports a new step.
    if (_doneSteps != null && done > _doneSteps!) HapticFeedback.lightImpact();
    _doneSteps = done;
    return r;
  }

  void _refresh() => setState(() { _f = _load(); });

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _f,
        builder: (context, s) {
          final loading = s.connectionState != ConnectionState.done;
          final wait = s.hasData ? null : pending(context, s, _refresh);
          final stage = api.stage;
          final loan = api.loan;
          final (pill, tone) = switch (stage) {
            Stage.disbursal => ('Disbursal confirming', DnTone.warn),
            Stage.active => ('Active', DnTone.ok),
            Stage.offer || Stage.offerFlow => ('Approved', DnTone.cobalt),
            Stage.closed => ('Closed', DnTone.neutral),
            _ => ('Under review', DnTone.info),
          };
          final cont = stage == Stage.offer || stage == Stage.offerFlow;
          return DnPage(
            title: 'Application status',
            back: true,
            actions: [DnIconPill(icon: Icons.refresh_rounded, tooltip: 'Refresh', size: 34, onPressed: loading ? null : _refresh)],
            onRefresh: () async => _refresh(),
            bottom: Column(mainAxisSize: MainAxisSize.min, children: [
              if (cont) ...[DnButton('Continue your loan', block: true, arrow: true, onPressed: () => context.go('/loan/amount')), const SizedBox(height: 8)],
              cont
                  ? DnButton.ghost('Go to dashboard', block: true, onPressed: () => context.go('/dashboard'))
                  : DnButton('Go to dashboard', block: true, onPressed: () => context.go('/dashboard')),
            ]),
            children: wait != null
                ? [wait]
                : [
                    if (loan != null && (stage == Stage.disbursal || stage == Stage.active)) ...[
                      MoneyOnTheWay(loan: loan, bank: '${s.data!.$2.bankName} ${s.data!.$2.bankMasked}'),
                      const SizedBox(height: DnTokens.gap),
                    ],
                    DnPanel(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                        Wrap(spacing: 8, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, alignment: WrapAlignment.spaceBetween, children: [
                          Text(loan == null ? 'Your application' : 'Application #${loan.applicationNo}', style: DnText.strong.copyWith(fontWeight: FontWeight.w500)),
                          DnPill(pill, tone: tone),
                        ]),
                        const SizedBox(height: 14),
                        DnTimeline(steps: [
                          for (final st in s.data!.$1)
                            DnStep(st.title, detail: st.detail, state: st.current ? DnStepState.now : (st.done ? DnStepState.done : DnStepState.todo)),
                        ]),
                      ]),
                    ),
                  ],
          );
        },
      );
}

/// Board F4 b: a dot travels the dashed arc while the disbursal is confirming; on confirmation a
/// tick pops in with the due date. Reduced motion: a static arc.
class MoneyOnTheWay extends StatefulWidget {
  const MoneyOnTheWay({super.key, required this.loan, required this.bank});
  final Loan loan;
  final String bank;

  @override
  State<MoneyOnTheWay> createState() => _MoneyOnTheWayState();
}

class _MoneyOnTheWayState extends State<MoneyOnTheWay> with SingleTickerProviderStateMixin {
  // 1.6 s of travel, then a pause: the run loops while the status is pending.
  late final _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 2200));

  bool get _confirming => widget.loan.lifecycle == Lifecycle.disbursementPending;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _sync();
  }

  @override
  void didUpdateWidget(MoneyOnTheWay old) {
    super.didUpdateWidget(old);
    _sync();
  }

  void _sync() {
    if (!_confirming || context.reduceMotion) return _c.stop();
    if (!_c.isAnimating) _c.repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final net = inr(widget.loan.quote.netPaise);
    final due = widget.loan.dueAt;
    return DnPanel(
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Semantics(
          label: _confirming ? '$net on the way to ${widget.bank}' : 'Money received',
          excludeSemantics: true,
          child: SizedBox(
            height: 64,
            child: Row(children: [
              const _End(Icons.account_balance_rounded),
              Expanded(
                child: _confirming
                    ? AnimatedBuilder(
                        animation: _c,
                        builder: (_, _) => CustomPaint(painter: _ArcPainter(context.reduceMotion ? null : Curves.easeInOut.transform((_c.value / .73).clamp(0, 1))), size: Size.infinite),
                      )
                    : CustomPaint(painter: _ArcPainter(null, done: true), size: Size.infinite),
              ),
              _confirming
                  ? const _End(Icons.savings_outlined)
                  : TweenAnimationBuilder<double>(
                      tween: Tween(begin: context.reduceMotion ? 1 : 0, end: 1),
                      duration: context.motion(const Duration(milliseconds: 300)),
                      curve: DnTokens.popCurve,
                      builder: (_, v, child) => Transform.scale(scale: v, child: child),
                      child: const _End(Icons.check_rounded, ok: true),
                    ),
            ]),
          ),
        ),
        const SizedBox(height: 10),
        if (_confirming) ...[
          Text('$net on the way to ${widget.bank}', style: DnText.strong),
          const Text('Confirming with the bank', style: DnText.hint),
        ] else ...[
          const Text('Money received', style: DnText.strong),
          Text('Due ${dayMonth(due)}, ${clock(due)}', style: DnText.hint),
        ],
      ]),
    );
  }
}

class _End extends StatelessWidget {
  const _End(this.icon, {this.ok = false});
  final IconData icon;
  final bool ok;

  @override
  Widget build(BuildContext context) => Container(
        width: 40,
        height: 40,
        decoration: BoxDecoration(color: ok ? DnTokens.ok : DnTokens.tint, shape: BoxShape.circle),
        child: Icon(icon, size: 20, color: ok ? Colors.white : DnTokens.primary600),
      );
}

class _ArcPainter extends CustomPainter {
  _ArcPainter(this.t, {this.done = false});

  /// Dot position along the arc, 0–1; null draws no dot.
  final double? t;
  final bool done;

  @override
  void paint(Canvas canvas, Size size) {
    final a = Offset(6, size.height * .62);
    final b = Offset(size.width - 6, size.height * .62);
    final ctrl = Offset(size.width / 2, size.height * .05);
    Offset at(double u) => Offset(
          (1 - u) * (1 - u) * a.dx + 2 * (1 - u) * u * ctrl.dx + u * u * b.dx,
          (1 - u) * (1 - u) * a.dy + 2 * (1 - u) * u * ctrl.dy + u * u * b.dy,
        );
    final dash = Paint()
      ..color = done ? DnTokens.ok : DnTokens.line2
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round;
    for (var u = 0.0; u < 1; u += .08) {
      canvas.drawLine(at(u), at(u + .04), dash);
    }
    if (t != null) canvas.drawCircle(at(t!), 6, Paint()..color = DnTokens.primary);
  }

  @override
  bool shouldRepaint(_ArcPainter old) => old.t != t || old.done != done;
}
