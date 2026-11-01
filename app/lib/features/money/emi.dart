import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/widgets/widgets.dart';
import 'common.dart';

/// Demo offer for previewing C13 while the `emi-conversion` flag is OFF: `context.push('/emi', extra: demoEmiOffer)`.
final demoEmiOffer = EmiOffer(instalments: 2, instalmentPaise: 50000, firstDue: DateTime(2026, 11, 5), totalPaise: 100000);

/// C13 Repayment plan (EMI). Built behind the OFF flag: the server returns no offer, so the empty
/// state shows unless an offer is passed in.
class EmiScreen extends StatelessWidget {
  const EmiScreen({super.key, this.offer});
  final EmiOffer? offer;

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Repayment plan offer',
        back: true,
        children: [
          if (offer != null)
            _Offer(offer: offer!)
          else
            Loaded(
              load: api.emiOffer,
              builder: (context, o) => o != null
                  ? _Offer(offer: o)
                  : DnStateView(
                      icon: Icons.event_note_rounded,
                      title: 'No repayment plan offered',
                      body: 'Plans are offered only after our team approves one for an overdue loan. You can’t request one from the app; talk to us if you need help paying.',
                      primary: 'Back to dashboard',
                      onPrimary: () => context.go('/dashboard'),
                      secondary: 'Talk to us',
                      onSecondary: () => context.push('/support'),
                    ),
            ),
        ],
      );
}

class _Offer extends StatefulWidget {
  const _Offer({required this.offer});
  final EmiOffer offer;

  @override
  State<_Offer> createState() => _OfferState();
}

class _OfferState extends State<_Offer> {
  bool _agree = false;

  @override
  Widget build(BuildContext context) {
    final o = widget.offer;
    final loan = api.loan;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const Text('Our team has approved splitting what you owe into instalments, to help you close this loan. Nothing changes until you accept.', style: DnText.body),
      const SizedBox(height: 16),
      DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          DnFigure(paise: o.totalPaise, label: 'Total you’ll pay'),
          const SizedBox(height: 14),
          if (loan != null) Kv('Outstanding today', inr(loan.outstandingPaise), first: true),
          Kv('Instalments', '${o.instalments} × ${inr(o.instalmentPaise)}', first: loan == null),
          Kv('First instalment due', dayMonth(o.firstDue, year: true)),
          const Kv('Interest and charges', 'Only as allowed by your agreement'),
        ]),
      ),
      const SizedBox(height: 12),
      DnCheck(value: _agree, onChanged: (v) => setState(() => _agree = v), label: const TextSpan(text: 'I accept this repayment plan. My original schedule is kept on record.')),
      const SizedBox(height: 8),
      const Text('Offered only after our team approves it for an overdue loan. You can’t request it from the app; talk to us if you need help paying.', style: DnText.hint),
      const SizedBox(height: 18),
      DnButton('Accept plan', block: true, onPressed: _agree ? () => showDnToast(context, 'Demo: plan accepted') : null),
      const SizedBox(height: 4),
      DnButton.ghost('Talk to us first', block: true, onPressed: () => context.push('/support')),
    ]);
  }
}
