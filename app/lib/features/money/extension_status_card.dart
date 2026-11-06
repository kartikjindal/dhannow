import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
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
    final done = [for (final e in loan.extensions) if (e.status == ExtensionStatus.verified) e];
    final p = loan.pendingExtension;

    final Widget top = switch (p?.status) {
      null || ExtensionStatus.verified || ExtensionStatus.ended => const SizedBox.shrink(),
      // S1: approved by the Collection Head, waiting for the borrower's OTP.
      ExtensionStatus.offered => ActionBanner(
          tone: DnBannerTone.info,
          icon: Icons.event_repeat_rounded,
          title: 'Extension offered by our team',
          body: 'Our collections team set up a 14-day extension for this loan. Check the charge and confirm with a code.',
          action: 'Review',
          onAction: () => context.push('/extension/confirm'),
        ),
      ExtensionStatus.confirmed => ActionBanner(
          tone: DnBannerTone.info,
          title: 'Extension ${p!.n} confirmed, payment due',
          body: 'Pay ${inr(p.totalPaise)} to move your due date to ${dayMonth(p.newDue)}.',
          action: 'Pay',
          onAction: () => context.push('/extension/pay'),
        ),
      ExtensionStatus.paymentPending => ActionBanner(
          tone: DnBannerTone.warn,
          title: 'Payment received, checking with the bank',
          body: '${inr(p!.totalPaise)}. Your due date stays ${dayMonth(p.oldDue)} until it’s verified, usually the same day. On-time is judged by the date your bank sent the money.',
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
          body: 'Nothing changed on your loan. Call ${api.config.support.phone} for a new offer.',
          action: 'Call us',
          onAction: () => context.push('/support'),
        ),
      // A state this build doesn't know: say so safely, never guess.
      ExtensionStatus.unknown => ActionBanner(
          title: 'Your extension is being updated',
          body: 'Pull down to refresh, or call ${api.config.support.phone} if it doesn’t change.',
          action: 'Call us',
          onAction: () => context.push('/support'),
        ),
    };

    if (done.isEmpty) return top;
    final capped = loan.extensionsUsed >= loan.extensionCap;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      if (p != null) ...[top, const SizedBox(height: 12)],
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
                if (done[i].chargePaise != null) Text(inr(done[i].chargePaise!), style: DnText.money),
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
}
