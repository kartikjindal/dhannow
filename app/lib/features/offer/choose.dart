import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/brand.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import 'common.dart';

/// B1 Choose your amount: ladder chips up to the credit level, the server's quote below.
class AmountScreen extends StatefulWidget {
  const AmountScreen({super.key});

  @override
  State<AmountScreen> createState() => _AmountScreenState();
}

class _AmountScreenState extends State<AmountScreen> with Busy {
  late Future<(List<Quote>, CreditLevel)> _f = _load();
  int? _picked;

  Future<(List<Quote>, CreditLevel)> _load() => (api.quotes(), api.creditLevel()).wait;

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _f,
        builder: (context, s) {
          final wait = pending(context, s, () => setState(() { _f = _load(); }));
          final (quotes, level) = s.data ?? (const <Quote>[], const CreditLevel(level: 1, maxPaise: 0, nextLevelPaise: null, ladderPaise: []));
          final picked = _picked ?? (api.chosenPaise != null && api.chosenPaise! <= level.maxPaise ? api.chosenPaise : level.maxPaise);
          final q = quotes.where((q) => q.principalPaise == picked).firstOrNull;
          return StepScaffold(
            title: 'Choose your amount',
            step: 1,
            of: 11,
            bottom: q == null
                ? null
                : OfferAction(
                    'Continue with ${inr(q.principalPaise)}',
                    loading: busy,
                    onPressed: () async {
                      if (await act(() => api.chooseAmount(q.principalPaise)) && context.mounted) context.push('/loan/repayment-date');
                    },
                  ),
            children: wait != null || q == null
                ? [?wait]
                : [
                    DnBanner(
                      tone: DnBannerTone.ok,
                      icon: Icons.verified_rounded,
                      title: 'You’re approved for up to ${inr(level.maxPaise)} (Level ${level.level}).',
                      body: 'Repay on time to unlock the next level. Amounts follow your credit level.',
                    ),
                    const SizedBox(height: DnTokens.gap),
                    DnPanel(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        DnFigure(paise: q.principalPaise, label: 'Loan amount'),
                        const SizedBox(height: 16),
                        DnLadderChips(
                          amountsPaise: [for (final q in quotes) q.principalPaise],
                          maxPaise: level.maxPaise,
                          selected: picked,
                          onSelected: (a) => setState(() => _picked = a),
                        ),
                        const SizedBox(height: 12),
                        Text('Levels: ${level.ladderPaise.map(inr).join(' → ')}. Only your current level and below can be picked.', style: DnText.hint),
                      ]),
                    ),
                    const SizedBox(height: DnTokens.gap),
                    DnPanel(child: QuoteBreakdown(q)),
                    const SizedBox(height: DnTokens.gap),
                    DnTile(
                      child: Row(children: [
                        Expanded(
                          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                            Text('You repay on day ${q.tenureDays}', style: DnText.strong),
                            const Text('Only the loan amount', style: DnText.hint),
                          ]),
                        ),
                        Flexible(fit: FlexFit.tight, child: FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerRight, child:Text(inr(q.repayPaise), style: DnText.display(30)))),
                      ]),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      'Day ${q.tenureDays} is counted from the day the money reaches your bank. APR and all charges are in the Key Fact Statement before you sign.',
                      style: DnText.hint,
                    ),
                  ],
          );
        },
      );
}

/// The quote's upfront deductions ending in "You receive in your bank" (B1, B8).
class QuoteBreakdown extends StatelessWidget {
  const QuoteBreakdown(this.q, {super.key});
  final Quote q;

  @override
  Widget build(BuildContext context) => DnBreakdown(
        lines: [
          DnLine('Loan amount', q.principalPaise),
          DnLine('Processing fee\n${pct(q.feeRateBps)} of loan amount', q.feePaise, minus: true),
          DnLine('GST on fee\n${pct(q.gstRateBps)} of processing fee', q.gstPaise, minus: true),
          DnLine('Interest, deducted now\n${pct(q.dailyRateBps)} per day × ${q.tenureDays} days', q.interestPaise, minus: true),
        ],
        total: DnLine('You receive in your bank', q.netPaise),
      );
}

/// B2 Repayment date: fixed tenure from the confirmed disbursal date, no picker.
class RepaymentDateScreen extends StatefulWidget {
  const RepaymentDateScreen({super.key});

  @override
  State<RepaymentDateScreen> createState() => _RepaymentDateScreenState();
}

class _RepaymentDateScreenState extends State<RepaymentDateScreen> {
  late Future<Quote> _f = api.chosenQuote();

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _f,
        builder: (context, s) {
          final wait = pending(context, s, () => setState(() { _f = api.chosenQuote(); }));
          final q = s.data;
          // An example only: the real day 0 is the date the Accountant confirms the disbursal.
          final now = DateTime.now();
          final day0 = DateTime(now.year, now.month, now.day);
          final due = DateTime(day0.year, day0.month, day0.day + (q?.tenureDays ?? 14), 23, 59);
          return StepScaffold(
            title: 'Repayment date',
            step: 2,
            of: 11,
            bottom: q == null ? null : OfferAction('Continue', onPressed: () => context.push('/loan/digilocker')),
            children: wait != null || q == null
                ? [?wait]
                : [
                    DnPanel(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('You repay ${q.tenureDays} days after the money reaches your bank.', style: DnText.section),
                        const SizedBox(height: 6),
                        Text(
                          'If it reaches you on ${dayMonth(day0)}, you repay ${inr(q.repayPaise)} on ${dayMonth(due)} by ${clock(due)}. The exact date is confirmed when the money is sent.',
                          style: DnText.body,
                        ),
                        const SizedBox(height: 16),
                        _Calendar(day0: day0, due: due),
                      ]),
                    ),
                    const SizedBox(height: DnTokens.gap),
                    DnTile(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('Day ${q.tenureDays} · ${inr(q.repayPaise)}', style: DnText.strong),
                        const SizedBox(height: 2),
                        Text(
                          'Interest for these ${q.tenureDays} days was already deducted. Paying early doesn’t change the amount unless your KFS says so.',
                          style: DnText.hint,
                        ),
                      ]),
                    ),
                    const SizedBox(height: DnTokens.gap),
                    DnBanner(
                      icon: Icons.schedule_rounded,
                      title: 'Due by ${clock(due)} IST on day ${q.tenureDays}',
                      body: 'Weekends and holidays don’t move the date. Extension is not automatic.',
                    ),
                  ],
          );
        },
      );
}

/// The weeks from day 0 to the due day: money in bank, the days between, repay.
class _Calendar extends StatelessWidget {
  const _Calendar({required this.day0, required this.due});
  final DateTime day0;
  final DateTime due;

  @override
  Widget build(BuildContext context) {
    final start = day0.subtract(Duration(days: day0.weekday % 7));
    final last = DateTime(due.year, due.month, due.day);
    final days = [for (var d = start; !d.isAfter(last) || d.weekday % 7 != 0; d = DateTime(d.year, d.month, d.day + 1)) d];
    Widget cell(DateTime d) {
      final inRange = !d.isBefore(day0) && !d.isAfter(last);
      final (bg, fg) = d == day0
          ? (DnTokens.primary, Colors.white)
          : d == last
              ? (DnTokens.ink, Colors.white)
              : inRange
                  ? (DnTokens.tint, DnTokens.primary700)
                  : (Colors.transparent, DnTokens.muted);
      return Container(
        margin: const EdgeInsets.all(2),
        height: 34,
        alignment: Alignment.center,
        decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(DnTokens.pill)),
        child: FittedBox(child: Text('${d.day}', style: TextStyle(fontSize: 13, fontWeight: inRange ? FontWeight.w600 : FontWeight.w400, color: fg))),
      );
    }

    Widget key(Color c, String t) => Row(mainAxisSize: MainAxisSize.min, children: [
          Container(width: 10, height: 10, decoration: BoxDecoration(color: c, shape: BoxShape.circle)),
          const SizedBox(width: 5),
          Flexible(child: Text(t, style: DnText.hint)),
        ]);

    return Semantics(
      label: 'Money in bank on ${dayMonth(day0)}, repay on ${dayMonth(last)}',
      excludeSemantics: true,
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('${dayMonth(day0)} to ${dayMonth(last, year: true)}', style: DnText.strong),
        const SizedBox(height: 8),
        Row(children: [
          for (final l in const ['S', 'M', 'T', 'W', 'T', 'F', 'S'])
            Expanded(child: Center(child: Text(l, style: DnText.hint.copyWith(color: DnTokens.muted)))),
        ]),
        for (var w = 0; w < days.length; w += 7) Row(children: [for (final d in days.sublist(w, w + 7)) Expanded(child: cell(d))]),
        const SizedBox(height: 10),
        Wrap(spacing: 14, runSpacing: 4, children: [
          key(DnTokens.primary, 'Money in bank'),
          key(DnTokens.tint, '${last.difference(day0).inDays} days'),
          key(DnTokens.ink, 'Repay'),
        ]),
      ]),
    );
  }
}

/// B5 Loan summary, then on to the Key Fact Statement.
class SummaryScreen extends StatefulWidget {
  const SummaryScreen({super.key});

  @override
  State<SummaryScreen> createState() => _SummaryScreenState();
}

class _SummaryScreenState extends State<SummaryScreen> {
  late Future<Quote> _f = api.chosenQuote();

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _f,
        builder: (context, s) {
          final wait = pending(context, s, () => setState(() { _f = api.chosenQuote(); }));
          final q = s.data;
          return StepScaffold(
            title: 'Loan summary',
            subtitle: 'Here’s everything about your loan, before you confirm.',
            step: 5,
            of: 11,
            bottom: q == null ? null : OfferAction('Looks right, continue', onPressed: () => context.push('/loan/kfs')),
            children: wait != null || q == null
                ? [?wait]
                : [
                    DnPanel(
                      child: Column(children: [
                        KvRow('Loan amount', inr(q.principalPaise), first: true),
                        KvRow('Interest rate', '${pct(q.dailyRateBps)} per day'),
                        KvRow('Tenure', '${q.tenureDays} days'),
                        KvRow('Processing fee', '− ${inr(q.feePaise)}'),
                        KvRow('GST on fee', '− ${inr(q.gstPaise)}'),
                        KvRow('Interest (deducted now)', '− ${inr(q.interestPaise)}'),
                        const KvRow('APR', 'As per your KFS'),
                        KvRow('Repayment on day ${q.tenureDays}', inr(q.repayPaise)),
                      ]),
                    ),
                    const SizedBox(height: DnTokens.gap),
                    DnPanel(
                      dark: true,
                      child: DnFigure(paise: q.netPaise, label: 'You will receive', color: Colors.white),
                    ),
                    const SizedBox(height: 10),
                    Text(
                      'The fee, GST and ${q.tenureDays} days’ interest are deducted upfront, which is why you receive ${inr(q.netPaise)} of the ${inr(q.principalPaise)} you are borrowing. Full details and the lender’s details are in the Key Fact Statement next.',
                      style: DnText.hint,
                    ),
                    const SizedBox(height: 4),
                    Align(alignment: Alignment.centerLeft, child: DnButton.text('Read the Key Fact Statement', arrow: true, onPressed: () => context.push('/loan/kfs'))),
                  ],
          );
        },
      );
}

/// B5b Key Fact Statement. Every figure is the server's quote; the lender is server config.
class KfsScreen extends StatefulWidget {
  const KfsScreen({super.key});

  @override
  State<KfsScreen> createState() => _KfsScreenState();
}

class _KfsScreenState extends State<KfsScreen> with Busy {
  // CC-57: FLAG_SECURE (KFS)
  late Future<(Quote, PayTo)> _f = _load();
  bool _read = false;

  Future<(Quote, PayTo)> _load() => (api.chosenQuote(), api.payTo()).wait;

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _f,
        builder: (context, s) {
          final wait = pending(context, s, () => setState(() { _f = _load(); }));
          final data = s.data;
          return StepScaffold(
            title: 'Key Fact Statement',
            subtitle: 'Read the key terms of this loan before you sign. You can download a copy now and any time later from Your loans.',
            step: 5,
            of: 11,
            bottom: data == null
                ? null
                : OfferAction(
                    'Continue',
                    trust: false,
                    loading: busy,
                    onPressed: !_read
                        ? null
                        : () async {
                            if (await act(api.acceptKfs) && context.mounted) context.push('/loan/selfie');
                          },
                  ),
            children: wait != null || data == null
                ? [?wait]
                : [
                    _section('Lender and loan', [
                      const KvRow('Lender', 'Lender of record (from config)', first: true),
                      const KvRow('Platform', Brand.name),
                      KvRow('Application no.', api.loan?.applicationNo ?? 'Given when you sign'),
                      KvRow('Loan amount', inr(data.$1.principalPaise)),
                      KvRow('Tenure', '${data.$1.tenureDays} days'),
                      KvRow('Interest', '${pct(data.$1.dailyRateBps)} per day, simple, on loan amount'),
                    ]),
                    _section('Charges and amounts', [
                      KvRow('Processing fee', inr(data.$1.feePaise), first: true),
                      KvRow('GST on fee', inr(data.$1.gstPaise)),
                      KvRow('Interest for ${data.$1.tenureDays} days', inr(data.$1.interestPaise)),
                      const KvRow('Other charges', 'None'),
                      KvRow('Net amount to you', inr(data.$1.netPaise)),
                      const KvRow('Annual Percentage Rate', 'As calculated by the RBI method (from config)'),
                      KvRow('Total repayable', '${inr(data.$1.repayPaise)} on day ${data.$1.tenureDays}'),
                    ]),
                    _section('If things change', [
                      const KvRow('Late / penal charges', 'Approved terms (from config)', first: true),
                      const KvRow('Cooling-off period', 'Not offered'),
                      const KvRow('Prepayment', 'Approved terms (from config)'),
                      KvRow('Repay to', data.$2.upiId),
                    ]),
                    _section('Grievance', [
                      KvRow('DhanNow officer', '${api.config.grievance.name}, ${api.config.grievance.phone}, ${api.config.grievance.email}', first: true),
                      const KvRow('Lender officer', 'From config'),
                    ]),
                    DnPanel(
                      padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                      child: DnDocRow(
                        name: 'Download KFS (PDF)',
                        meta: 'Also saved in Your loans',
                        first: true,
                        onTap: () => showDnToast(context, 'Your KFS is saved in Your loans'),
                      ),
                    ),
                    const SizedBox(height: DnTokens.gap),
                    DnCheck(
                      value: _read,
                      onChanged: (v) => setState(() => _read = v),
                      label: const TextSpan(text: 'I have read the Key Fact Statement and understand the cost of this loan.'),
                    ),
                    const SizedBox(height: 8),
                    Text('KFS information policy: ${Brand.site}/kfs-policy.html. Your own KFS is the PDF above; that page explains it.', style: DnText.hint),
                  ],
          );
        },
      );

  Widget _section(String title, List<Widget> rows) => Padding(
        padding: const EdgeInsets.only(bottom: DnTokens.gap),
        child: DnPanel(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [DnCaps(title), const SizedBox(height: 6), ...rows]),
        ),
      );
}
