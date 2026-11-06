import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import '../money/extension_status_card.dart';

/// C5: loan details bottom sheet. Every figure is a [Loan] / [Quote] field from the server.
Future<void> showLoanDetails(BuildContext context, Loan loan) => showDnSheet<void>(
      context,
      builder: (sheet) {
        final q = loan.quote;
        return Flexible(
          child: SingleChildScrollView(
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Row(children: [
                Expanded(child: Text('Loan #${loan.applicationNo}', style: DnText.section.copyWith(fontSize: 18))),
                DnIconPill(icon: Icons.close_rounded, tooltip: 'Close', size: 34, onPressed: () => Navigator.pop(sheet)),
              ]),
              const SizedBox(height: 16),
              const DnCaps('Disbursal'),
              DnBreakdown(
                lines: [
                  DnLine('Loan amount', q.principalPaise),
                  DnLine('Processing fee', q.feePaise, minus: true),
                  DnLine('GST on the fee', q.gstPaise, minus: true),
                  DnLine('Interest (${q.tenureDays} days)', q.interestPaise, minus: true),
                ],
                total: DnLine('Amount disbursed to you', q.netPaise),
              ),
              const SizedBox(height: 18),
              const DnCaps('Cost'),
              const _Kv('APR', 'As shown in your KFS'),
              _Kv('Total repayable (day ${q.tenureDays})', inr(q.repayPaise)),
              const SizedBox(height: 18),
              const DnCaps('Repayment'),
              _Kv('Disbursed on', dayMonth(loan.disbursedOn, year: true)),
              _Kv('Due by', '${dayMonth(loan.dueAt, year: true)}, ${clock(loan.dueAt)}'),
              _Kv('Paid so far', inr(loan.repaidPaise)),
              if (loan.latePaise > 0) _Kv('Late charge to date', inr(loan.latePaise)),
              _Kv('Outstanding', inr(loan.outstandingPaise), strong: true),
              _Kv('Loan account no.', loan.accountNo),
              if (loan.extensions.isNotEmpty) ...[const SizedBox(height: 14), ExtensionStatusCard(loan: loan)],
              if (loan.isOpen) ...[
                const SizedBox(height: 18),
                DnButton('Repay', block: true, onPressed: () {
                  Navigator.pop(sheet);
                  context.go('/repay');
                }),
              ],
            ]),
          ),
        );
      },
    );

class _Kv extends StatelessWidget {
  const _Kv(this.label, this.value, {this.strong = false});
  final String label;
  final String value;
  final bool strong;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 9),
        decoration: const BoxDecoration(border: Border(bottom: BorderSide(color: DnTokens.line))),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(child: Text(label, style: DnText.body.copyWith(height: 1.4))),
          const SizedBox(width: 12),
          Flexible(fit: FlexFit.tight, child: Text(value, textAlign: TextAlign.end, style: DnText.money.copyWith(fontWeight: strong ? FontWeight.w700 : null))),
        ]),
      );
}
