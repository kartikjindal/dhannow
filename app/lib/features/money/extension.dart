import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/widgets/widgets.dart';
import 'common.dart';
import 'extension_status_card.dart';

/// The charge lines exactly as the server quoted them (no sums in the app).
DnBreakdown extensionBreakdown(ExtensionOffer o) => DnBreakdown(
      lines: [
        DnLine('Processing fee', o.feePaise),
        DnLine('GST on fee', o.gstPaise),
        DnLine('Interest, 14 more days', o.interestPaise),
        if (o.latePaise > 0) DnLine('Late charge so far', o.latePaise),
      ],
      total: DnLine('Pay now to extend', o.totalPaise),
    );

String _due(DateTime d) => '${dayMonth(d, year: true)}, ${clock(d)}';

/// No offer, or it ended: the screen is chosen by server state, never by a notification (EXT-10).
Widget _noOffer(BuildContext context, {bool expired = false}) {
  final loan = api.loan;
  final last = loan?.extensions.lastOrNull;
  if (!expired && last != null && last.status == ExtensionStatus.verified) {
    return ActionBanner(
      tone: DnBannerTone.ok,
      title: 'Already extended to ${dayMonth(last.newDue)}',
      body: 'Extension ${last.n} of ${loan!.extensionCap} is verified. You don’t need to do anything.',
      action: 'Dashboard',
      onAction: () => context.go('/dashboard'),
    );
  }
  return DnStateView(
    icon: expired ? Icons.event_busy_rounded : Icons.event_repeat_rounded,
    title: expired ? 'This offer has expired' : 'No extension to confirm',
    body: expired ? 'Nothing changed on your loan. Call us for a new offer.' : 'There’s no extension offer waiting. If you need more time, call our collections team.',
    primary: 'Call ${api.config.support.phone}',
    onPrimary: () => context.push('/support'),
    secondary: 'Back to dashboard',
    onSecondary: () => context.go('/dashboard'),
  );
}

/// C12 Confirm extension: the officer’s offer, the charge, old → new due, and the OTP.
class ExtensionConfirmScreen extends StatefulWidget {
  const ExtensionConfirmScreen({super.key});

  @override
  State<ExtensionConfirmScreen> createState() => _ExtensionConfirmScreenState();
}

class _ExtensionConfirmScreenState extends State<ExtensionConfirmScreen> {
  final _otp = TextEditingController();
  bool _agree = false;
  bool _busy = false;
  DnOtpState _otpState = DnOtpState.idle;
  String? _error;

  @override
  void initState() {
    super.initState();
    // The code goes out when the borrower opens the offer; errors show on Resend.
    api.sendExtensionOtp().catchError((_) {});
  }

  @override
  void dispose() {
    _otp.dispose();
    super.dispose();
  }

  Future<void> _confirm() async {
    if (_otp.text.length < 6) return setState(() => _error = 'Enter the 6-digit code we sent to your mobile.');
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await api.confirmExtension(_otp.text);
      if (mounted) context.go('/extension/pay');
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'OTP_INVALID' || e.code == 'OTP_LOCKED') {
        setState(() {
          _otpState = DnOtpState.error;
          _error = e.message;
        });
      } else {
        showApiError(context, e);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resend() async {
    try {
      await api.sendExtensionOtp();
      if (!mounted) return;
      _otp.clear();
      setState(() {
        _otpState = DnOtpState.idle;
        _error = null;
      });
      showDnToast(context, 'New code sent');
    } on ApiException catch (e) {
      if (mounted) showApiError(context, e);
    }
  }

  @override
  Widget build(BuildContext context) {
    final loan = api.loan;
    return StepScaffold(
      title: 'Confirm extension',
      subtitle: 'You asked our collections team to extend loan #${loan?.applicationNo ?? ''}. Check the details and confirm.',
      confirmBack: false,
      children: [
        Loaded(
          load: api.extensionOffer,
          builder: (context, o) {
            if (o == null || loan == null) return _noOffer(context);
            if (DateTime.now().isAfter(o.expiresAt) || o.status == ExtensionStatus.expired) return _noOffer(context, expired: true);
            // Already confirmed, paid or ended: the status card says what is next (server state only).
            if (o.status != ExtensionStatus.offered) return ExtensionStatusCard(loan: loan);
            return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              DnPanel(
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  Wrap(spacing: 8, runSpacing: 6, children: [
                    DnPill('Extension ${o.n} of ${o.cap}', tone: DnTone.info),
                    const DnPill('Awaiting your OK', tone: DnTone.warn, dot: true),
                  ]),
                  const SizedBox(height: 14),
                  DnFigure(paise: o.totalPaise, label: 'Pay now to extend'),
                  const SizedBox(height: 4),
                  Text('Offered by ${o.officer ?? 'our collections team'}', style: DnText.hint),
                  const SizedBox(height: 14),
                  extensionBreakdown(o),
                ]),
              ),
              const SizedBox(height: 12),
              DnPanel(
                child: Column(children: [
                  Kv('Current due date', dayMonth(o.oldDue, year: true), first: true),
                  Kv('New due date', _due(o.newDue)),
                  Kv('Still to repay on ${dayMonth(o.newDue)}', inr(loan.quote.repayPaise)),
                  Kv('Offer open until', _due(o.expiresAt)),
                ]),
              ),
              const SizedBox(height: 12),
              DnCheck(
                value: _agree,
                onChanged: (v) => setState(() => _agree = v),
                label: TextSpan(
                  text: 'I understand this extension costs ${inr(o.totalPaise)} and my loan amount of ${inr(loan.quote.repayPaise)} is still due on ${dayMonth(o.newDue)}. Extending doesn’t raise my credit level.',
                ),
              ),
              const SizedBox(height: 8),
              const Text('The late charge keeps growing until you pay. The new date starts only after our team verifies your payment.', style: DnText.hint),
              const SizedBox(height: 16),
              const Text('Code sent to your mobile', style: DnText.label),
              const SizedBox(height: 8),
              DnOtpField(controller: _otp, state: _otpState, enabled: !_busy, onCompleted: (_) => setState(() => _otpState = DnOtpState.idle)),
              if (_error != null) ...[const SizedBox(height: 10), DnBanner(tone: DnBannerTone.err, title: _error!)],
              Align(alignment: Alignment.centerLeft, child: DnButton.text('Resend code', onPressed: _resend)),
              const SizedBox(height: 12),
              DnButton('Confirm and pay ${inr(o.totalPaise)}', block: true, loading: _busy, onPressed: _agree ? _confirm : null),
              const SizedBox(height: 4),
              DnButton.ghost('Not now', block: true, onPressed: () => context.go('/dashboard')),
              const SizedBox(height: 4),
              Center(
                child: DnButton.text('Didn’t ask for this? Tell us', onPressed: () async {
                  try {
                    await api.declineExtension('DID_NOT_ASK');
                  } on ApiException catch (e) {
                    if (context.mounted) return showApiError(context, e);
                  }
                  if (context.mounted) context.push('/complaints/new');
                }),
              ),
            ]);
          },
        ),
      ],
    );
  }
}

/// C12b Pay extension charge: the quoted amount, where to pay, then the reference.
class ExtensionPayScreen extends StatelessWidget {
  const ExtensionPayScreen({super.key});

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Pay extension charge',
        children: [
          Loaded(
            load: () async => (await api.extensionOffer(), await api.payTo()),
            builder: (context, d) {
              final (o, payTo) = d;
              final loan = api.loan;
              if (o == null || loan == null) return _noOffer(context);
              return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                DnPanel(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    Wrap(spacing: 8, runSpacing: 6, children: [
                      DnPill('Extension ${o.n} of ${o.cap} · confirmed', tone: DnTone.info),
                      const DnPill('OTP verified', tone: DnTone.ok, dot: true),
                    ]),
                    const SizedBox(height: 14),
                    DnFigure(paise: o.totalPaise, label: 'Amount to pay today'),
                    const SizedBox(height: 14),
                    extensionBreakdown(o),
                    const SizedBox(height: 8),
                    const Text('Pay on a later day and the late charge goes up.', style: DnText.hint),
                  ]),
                ),
                const SizedBox(height: 12),
                PayToPanel(payTo: payTo, note: 'EXT-${loan.applicationNo}-${o.n}'),
                const SizedBox(height: 12),
                PaidForm(
                  buttonLabel: 'I’ve paid ${inr(o.totalPaise)}',
                  firstDate: o.oldDue.subtract(const Duration(days: 30)),
                  footer: Text(
                    'Paid a different amount? Less than ${inr(o.totalPaise)} doesn’t extend the loan and counts towards what you owe; more is refunded.',
                    textAlign: TextAlign.center,
                    style: DnText.hint,
                  ),
                  onSubmit: ({required utr, required paidOn, paise, required screenshot}) async {
                    await api.payExtension(utr: utr, paidOn: paidOn);
                    if (context.mounted) context.go('/extension/done');
                  },
                ),
              ]);
            },
          ),
        ],
      );
}

/// C12c Extension verified (or, before verification, "checking with the bank").
class ExtensionDoneScreen extends StatelessWidget {
  const ExtensionDoneScreen({super.key});

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: api,
        builder: (context, _) {
          final loan = api.loan;
          final p = loan?.pendingExtension;
          final last = loan?.extensions.lastOrNull;
          final verified = p == null && last?.status == ExtensionStatus.verified;
          final e = p == null ? last : Extension(n: p.n, chargePaise: p.totalPaise, oldDue: p.oldDue, newDue: p.newDue, status: p.status);
          return DnPage(
            title: verified ? 'Extended' : 'Payment received',
            children: [
              if (loan == null || e == null)
                _noOffer(context)
              else ...[
                ResultDisc(icon: verified ? Icons.event_available_rounded : Icons.hourglass_top_rounded, tone: verified ? DnTone.ok : DnTone.warn),
                const SizedBox(height: 14),
                Text(verified ? 'Loan extended to ${dayMonth(e.newDue)}' : 'Payment received, checking with the bank', style: DnText.display(30)),
                const SizedBox(height: 6),
                Text(
                  verified
                      ? 'We verified your ${e.chargePaise == null ? '' : '${inr(e.chargePaise!)} '}payment. Your loan of ${inr(loan.quote.repayPaise)} is now due on ${_due(e.newDue)}.'
                      : 'Your due date stays ${dayMonth(e.oldDue)} until it’s verified, usually the same day.',
                  style: DnText.body,
                ),
                const SizedBox(height: 16),
                DnPanel(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    TitlePill('Extensions used', pill: Text('${loan.extensionsUsed} of ${loan.extensionCap}', style: DnText.display(26))),
                    DnProgressBar(value: loan.extensionCap == 0 ? 1 : loan.extensionsUsed / loan.extensionCap, semanticLabel: '${loan.extensionsUsed} of ${loan.extensionCap} extensions used'),
                    const SizedBox(height: 10),
                    Text(
                      loan.extensionsUsed >= loan.extensionCap
                          ? 'You’ve used every extension. Repay by ${dayMonth(loan.dueAt)}, or call us if you need help.'
                          : 'You can ask collections for up to ${loan.extensionCap - loan.extensionsUsed} more. Each one has a charge, plus any late charge.',
                      style: DnText.hint,
                    ),
                  ]),
                ),
                const SizedBox(height: 12),
                DnPanel(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    if (e.chargePaise != null) Kv('Paid for extension ${e.n}', inr(e.chargePaise!), first: true),
                    Kv('Still to repay', '${inr(loan.outstandingPaise)} by ${dayMonth(loan.dueAt)}', first: e.chargePaise == null),
                    const SizedBox(height: 4),
                    if (verified) DnDocRow(name: 'Extension letter (addendum)', meta: 'New due date, charges and APR', onTap: () => showDnToast(context, 'Demo: extension letter downloaded')),
                  ]),
                ),
              ],
              const SizedBox(height: 18),
              DnButton('Go to dashboard', block: true, onPressed: () => context.go('/dashboard')),
            ],
          );
        },
      );
}
