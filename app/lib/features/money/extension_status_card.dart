import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/brand.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import 'common.dart';

/// C12d: the borrower-facing extension state for a loan ("Extended 1 of 3", pending OTP, payment
/// being verified, ...). Shown on the dashboard and in loan details. Renders nothing when the loan
/// has no extension activity. Chosen from server state only, never from a notification.
class ExtensionStatusCard extends StatelessWidget {
  const ExtensionStatusCard({super.key, required this.loan});
  final Loan loan;

  @override
  Widget build(BuildContext context) {
    final last = loan.extensions.lastOrNull;
    final done = [for (final e in loan.extensions) if (e.status == ExtensionStatus.verified) e];

    // S1: approved by the Collection Head, waiting for the borrower's OTP.
    if (api.scenario == Scenario.extensionOffered && (last == null || last.status == ExtensionStatus.verified)) {
      return ActionBanner(
        tone: DnBannerTone.info,
        icon: Icons.event_repeat_rounded,
        title: 'Extension offered by our team',
        body: 'Our collections team set up a 14-day extension for this loan. Check the charge and confirm with a code.',
        action: 'Review',
        onAction: () => context.push('/extension/confirm'),
      );
    }
    if (last == null) return const SizedBox.shrink();

    final Widget top = switch (last.status) {
      ExtensionStatus.offered || ExtensionStatus.confirmed => ActionBanner(
          tone: DnBannerTone.info,
          title: 'Extension ${last.n} confirmed, payment due',
          body: 'Pay ${inr(last.chargePaise)} to move your due date to ${dayMonth(last.newDue)}.',
          action: 'Pay',
          onAction: () => context.push('/extension/pay'),
        ),
      ExtensionStatus.paymentPending => ActionBanner(
          tone: DnBannerTone.warn,
          title: 'Payment received, checking with the bank',
          body: '${inr(last.chargePaise)}${last.utr == null ? '' : ' · UTR ${_mask(last.utr!)}'}. Your due date stays ${dayMonth(last.oldDue)} until it’s verified, usually the same day. On-time is judged by the date your bank sent the money.',
        ),
      ExtensionStatus.rejected => ActionBanner(
          tone: DnBannerTone.err,
          title: 'We couldn’t match your payment',
          body: 'Check the reference and send it again before the offer ends.',
          action: 'Correct it',
          onAction: () => context.push('/extension/pay'),
        ),
      ExtensionStatus.expired => ActionBanner(
          title: 'This offer has expired',
          body: 'Nothing changed on your loan. Call ${Brand.supportPhone} for a new offer.',
          action: 'Call us',
          onAction: () => context.push('/support'),
        ),
      ExtensionStatus.verified => const SizedBox.shrink(),
    };

    if (done.isEmpty) return top;
    final capped = loan.extensionsUsed >= loan.extensionCap;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (last.status != ExtensionStatus.verified) ...[top, const SizedBox(height: 12)],
      DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          DnPanelHeader(
            title: 'Extended ${loan.extensionsUsed} of ${loan.extensionCap}',
            meta: 'New due date ${dayMonth(loan.dueAt, year: true)}, ${clock(loan.dueAt)}',
                      ),
          for (var i = 0; i < done.length; i++)
            Container(
              padding: const EdgeInsets.symmetric(vertical: 9),
              decoration: BoxDecoration(border: i == 0 ? null : const Border(top: BorderSide(color: DnTokens.line))),
              child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Expanded(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('Extension ${done[i].n}', style: DnText.strong),
                    Text('${dayMonth(done[i].oldDue)} → ${dayMonth(done[i].newDue)}', style: DnText.hint),
                  ]),
                ),
                const SizedBox(width: 8),
                Text(inr(done[i].chargePaise), style: DnText.money),
              ]),
            ),
          const SizedBox(height: 6),
          Text(
            capped
                ? 'You’ve used every extension. Repay ${inr(loan.outstandingPaise)} by ${dayMonth(loan.dueAt)}, or call us if you need help.'
                : 'Extended loans keep your credit level. Repay ${inr(loan.outstandingPaise)} by ${dayMonth(loan.dueAt)}.',
            style: DnText.hint.copyWith(color: capped ? DnTokens.warnInk : null),
          ),
        ]),
      ),
    ]);
  }

  static String _mask(String utr) => utr.length <= 8 ? utr : '${utr.substring(0, 4)}…${utr.substring(utr.length - 4)}';
}
