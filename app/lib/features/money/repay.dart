import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/brand.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import 'common.dart';
import 'extension_status_card.dart';

/// C6 Repay (tab root): what you owe, where to pay, then the "I’ve paid" form.
class RepayScreen extends StatelessWidget {
  const RepayScreen({super.key});

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: api,
        builder: (context, _) {
          final loan = api.loan;
          return DnPage(
            title: 'Repay your loan',
            subtitle: 'One repayment of the loan amount on day 14. Pay any day before that; the amount stays the same.',
            children: [
              if (loan == null || !loan.isOpen)
                DnStateView(
                  icon: Icons.task_alt_rounded,
                  title: 'Nothing to repay',
                  body: 'You have no open loan right now.',
                  primary: 'Borrow',
                  onPrimary: () => context.go('/dashboard'),
                )
              else if (loan.lifecycle == Lifecycle.disbursementPending)
                DnStateView(
                  icon: Icons.hourglass_top_rounded,
                  title: 'Nothing to repay yet',
                  body: 'Your 14 days start once the bank confirms your money arrived.',
                  primary: 'Back to Home',
                  onPrimary: () => context.go('/dashboard'),
                )
              else ...[
                if (api.scenario == Scenario.extensionOffered) ...[ExtensionStatusCard(loan: loan), const SizedBox(height: 12)],
                _Owe(loan: loan),
                const SizedBox(height: 12),
                if (loan.repayment == Repayment.pendingVerification)
                  ActionBanner(
                    tone: DnBannerTone.warn,
                    title: 'Your payment is being verified',
                    body: 'Our accounts team is checking it with the bank, usually the same day. Your balance updates once it’s verified. On-time is judged by the date your bank sent the money.',
                    action: 'Payments',
                    onAction: () => context.push('/transactions'),
                  )
                else
                  Loaded(
                    load: () async => (await api.payTo(), await api.creditLevel()),
                    builder: (context, d) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                      _NextLevelHint(loan: loan, level: d.$2),
                      PayToPanel(payTo: d.$1),
                      const SizedBox(height: 12),
                      PaidForm(
                        buttonLabel: 'I’ve paid',
                        maxPaise: loan.outstandingPaise,
                        firstDate: loan.disbursedOn,
                        onSubmit: ({required utr, required paidOn, paise, required screenshot}) async {
                          await api.submitPayment(utr: utr, paidOn: paidOn, paise: paise!, screenshot: screenshot);
                          if (context.mounted) context.go('/repay/sent');
                        },
                      ),
                    ]),
                  ),
              ],
              const SizedBox(height: 12),
              const PolicyLinks(),
            ],
          );
        },
      );
}

class _Owe extends StatelessWidget {
  const _Owe({required this.loan});
  final Loan loan;

  @override
  Widget build(BuildContext context) => DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          TitlePill('What you owe', pill: loan.isOverdue ? DnPill('${-loan.daysLeft} days late', tone: DnTone.err, dot: true) : null),
          Kv('Loan amount', inr(loan.quote.principalPaise), first: true),
          Kv('Received on', dayMonth(loan.disbursedOn, year: true)),
          Kv(loan.extensionsUsed > 0 ? 'Due date (extended)' : 'Due date (day 14)', dayMonth(loan.dueAt, year: true)),
          const Kv('Interest', 'Already deducted'),
          if (loan.latePaise > 0) Kv('Late charge so far', inr(loan.latePaise), valueColor: DnTokens.err),
          if (loan.repaidPaise > 0) Kv('Paid so far', inr(loan.repaidPaise)),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: BoxDecoration(color: loan.isOverdue ? DnTokens.errBg : DnTokens.tint, borderRadius: BorderRadius.circular(DnTokens.rSm)),
            child: Row(children: [
              Expanded(child: Text(loan.isOverdue ? 'Pay now' : 'Pay by ${dayMonth(loan.dueAt)}', style: DnText.strong)),
              const SizedBox(width: 8),
              Flexible(
                fit: FlexFit.tight,
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  alignment: Alignment.centerRight,
                  child: Text(inr(loan.outstandingPaise), style: DnText.display(32, color: loan.isOverdue ? DnTokens.errInk : DnTokens.primary600)),
                ),
              ),
            ]),
          ),
          if (loan.isOverdue) ...[
            const SizedBox(height: 8),
            const Text('The late charge grows each day until you pay. Need more time? Call our collections team.', style: DnText.hint),
          ],
        ]),
      );
}

class _NextLevelHint extends StatelessWidget {
  const _NextLevelHint({required this.loan, required this.level});
  final Loan loan;
  final CreditLevel level;

  @override
  Widget build(BuildContext context) {
    final String text;
    final Color color;
    if (loan.extensionsUsed > 0) {
      (text, color) = ('Extended loans keep your level. Your next loan repaid on time moves you up.', DnTokens.slate);
    } else if (loan.isOverdue || level.nextLevelPaise == null) {
      return const SizedBox(height: 0);
    } else {
      (text, color) = ('Pay in full on time to unlock ${inr(level.nextLevelPaise!)} next time.', DnTokens.okInk);
    }
    return Padding(padding: const EdgeInsets.fromLTRB(4, 0, 4, 12), child: Text(text, style: DnText.hint.copyWith(color: color)));
  }
}

/// Website policy links under the repay screen (opened in an in-app tab once custom tabs land).
class PolicyLinks extends StatelessWidget {
  const PolicyLinks({super.key});

  @override
  Widget build(BuildContext context) {
    void open(String page) => showDnToast(context, 'Opens ${Brand.site}/$page');
    return DnPanel(
      padding: EdgeInsets.zero,
      child: Column(children: [
        DnListRow(first: true, title: 'Repayment policy', detail: '${Brand.site}/repayment-policy.html', trailing: const Icon(Icons.open_in_new_rounded, size: 16, color: DnTokens.primary600), onTap: () => open('repayment-policy.html')),
        DnListRow(title: 'Payment and refund policy', detail: '${Brand.site}/payment-refund-policy.html', trailing: const Icon(Icons.open_in_new_rounded, size: 16, color: DnTokens.primary600), onTap: () => open('payment-refund-policy.html')),
      ]),
    );
  }
}

/// C7 Payment recorded: confirmation, then the payments list with their verification status.
class PaymentSentScreen extends StatelessWidget {
  const PaymentSentScreen({super.key});

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Repay your loan',
        back: true,
        children: [
          const DnBanner(
            tone: DnBannerTone.ok,
            title: 'Payment recorded',
            body: 'Pending verification by our accounts team. Your balance updates once the transfer is confirmed.',
          ),
          const SizedBox(height: 12),
          Loaded(
            load: api.transactions,
            builder: (context, txns) {
              final paid = [for (final t in txns) if (!t.credit) t];
              return DnPanel(
                padding: EdgeInsets.zero,
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  const Padding(padding: EdgeInsets.fromLTRB(16, 16, 16, 0), child: DnPanelHeader(title: 'Payments')),
                  if (paid.isEmpty) const Padding(padding: EdgeInsets.fromLTRB(16, 0, 16, 16), child: Text('No payments yet', style: DnText.body)),
                  for (var i = 0; i < paid.length; i++) TxnRow(txn: paid[i], first: i == 0),
                ]),
              );
            },
          ),
          const SizedBox(height: 18),
          DnButton('Back to Home', block: true, onPressed: () => context.go('/dashboard')),
          const SizedBox(height: 8),
          DnButton.ghost('See all transactions', block: true, onPressed: () => context.push('/transactions')),
        ],
      );
}

/// One payment or disbursal row (C7, C9). Rejected rows open board E12.
class TxnRow extends StatelessWidget {
  const TxnRow({super.key, required this.txn, this.first = false});
  final Txn txn;
  final bool first;

  @override
  Widget build(BuildContext context) {
    final s = txn.status.toLowerCase();
    final (tone, label) = s.contains('reject')
        ? (DnTone.err, 'Rejected')
        : s.contains('being') || s.contains('pending')
            ? (DnTone.warn, 'Pending verification')
            : (DnTone.ok, txn.credit ? 'Completed' : 'Verified');
    final rejected = tone == DnTone.err;
    return InkWell(
      onTap: rejected ? () => context.push('/payment-rejected') : null,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
        decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: DnTokens.line))),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(txn.credit ? Icons.south_west_rounded : Icons.north_east_rounded, size: 20, color: txn.credit ? DnTokens.ok : DnTokens.slate),
          const SizedBox(width: 12),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(child: Text(txn.title, style: DnText.strong)),
                const SizedBox(width: 8),
                Text('${txn.credit ? '+' : ''}${inr(txn.paise)}', style: DnText.money.copyWith(color: txn.credit ? DnTokens.okInk : null)),
              ]),
              const SizedBox(height: 2),
              Text([dayMonth(txn.at, year: true), if (txn.ref != null) 'Ref ${txn.ref}'].join(' · '), style: DnText.hint),
              const SizedBox(height: 6),
              Wrap(spacing: 8, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
                DnPill(label, tone: tone),
                if (rejected) const Text('See why', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: DnTokens.primary600)),
              ]),
            ]),
          ),
        ]),
      ),
    );
  }
}
