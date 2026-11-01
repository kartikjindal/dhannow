import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/links.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import '../dashboard/loan_details.dart';
import 'common.dart';
import 'repay.dart';

/// Status chip for a loan, read from server fields only.
DnPill loanPill(Loan l) => switch (l.lifecycle) {
      Lifecycle.closed => DnPill(l.closedOnTime == false ? 'Closed late' : 'Closed', tone: DnTone.neutral),
      Lifecycle.disbursementPending => const DnPill('Being confirmed', tone: DnTone.warn, dot: true),
      Lifecycle.dueToday => const DnPill('Due today', tone: DnTone.warn, dot: true),
      Lifecycle.overdueD1 || Lifecycle.overdueReview => const DnPill('Late', tone: DnTone.err, dot: true),
      _ when l.repayment == Repayment.pendingVerification => const DnPill('Payment being verified', tone: DnTone.warn, dot: true),
      _ when l.extensionsUsed > 0 => DnPill('Extended ${l.extensionsUsed} of ${l.extensionCap}', tone: DnTone.info, dot: true),
      _ => const DnPill('Active', tone: DnTone.ok, dot: true),
    };

/// C8 Your loans (tab root): Active / Closed, documents, links to transactions and account details.
class LoansScreen extends StatefulWidget {
  const LoansScreen({super.key});

  @override
  State<LoansScreen> createState() => _LoansScreenState();
}

class _LoansScreenState extends State<LoansScreen> {
  int _tab = 0;

  @override
  Widget build(BuildContext context) => DnPage(
        onRefresh: api.refresh,
        title: 'Your loans',
        subtitle: 'Every loan you’ve applied for, with its current status.',
        children: [
          Loaded(
            load: () async => (await api.loans(), await api.documents()),
            builder: (context, d) {
              final (loans, docs) = d;
              if (loans.isEmpty) {
                return DnStateView(
                  icon: Icons.receipt_long_rounded,
                  title: 'No loans yet',
                  body: 'When you apply for a loan it’ll show up here with live status.',
                  primary: 'Apply',
                  onPrimary: () => context.go('/dashboard'),
                );
              }
              final open = [for (final l in loans) if (l.isOpen) l];
              final closed = [for (final l in loans) if (!l.isOpen) l];
              return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                DnUnderlineTabs(labels: const ['Active', 'Closed'], index: _tab, counts: [open.length, closed.length], onTap: (i) => setState(() => _tab = i)),
                const SizedBox(height: 14),
                if (_tab == 0) ...[
                  if (open.isEmpty) const _Empty('No active loan right now.'),
                  for (final l in open) ...[_ActiveLoan(loan: l), const SizedBox(height: 12)],
                ] else ...[
                  if (closed.isEmpty) const _Empty('No past loans yet.'),
                  if (closed.isNotEmpty)
                    DnPanel(
                      padding: EdgeInsets.zero,
                      child: Column(children: [
                        for (var i = 0; i < closed.length; i++)
                          DnListRow(
                            first: i == 0,
                            icon: Icons.task_alt_rounded,
                            title: 'Loan #${closed[i].applicationNo} · ${inr(closed[i].quote.principalPaise)}',
                            detail: [
                              if (closed[i].closedAt != null) 'Closed ${dayMonth(closed[i].closedAt!, year: true)}',
                              closed[i].closedOnTime == false ? 'repaid late' : 'repaid on time',
                            ].join(' · '),
                            onTap: () => context.push('/loans/closed', extra: closed[i]),
                          ),
                      ]),
                    ),
                ],
                const SizedBox(height: 12),
                _Documents(docs: docs),
                const SizedBox(height: 12),
                DnPanel(
                  padding: EdgeInsets.zero,
                  child: Column(children: [
                    DnListRow(first: true, icon: Icons.swap_vert_rounded, title: 'See all transactions', detail: 'Money sent to you and your repayments', onTap: () => context.push('/transactions')),
                    DnListRow(icon: Icons.account_balance_rounded, title: 'Loan account details', detail: 'Account number, lender and dates', onTap: () => context.push('/loan-account')),
                  ]),
                ),
              ]);
            },
          ),
        ],
      );
}

class _Empty extends StatelessWidget {
  const _Empty(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => DnPanel(child: Text(text, style: DnText.body));
}

class _ActiveLoan extends StatelessWidget {
  const _ActiveLoan({required this.loan});
  final Loan loan;

  @override
  Widget build(BuildContext context) => DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          TitlePill('Loan #${loan.applicationNo}', pill: loanPill(loan)),
          Kv('Principal', inr(loan.quote.principalPaise), first: true),
          Kv('Net disbursed', inr(loan.quote.netPaise)),
          Kv('Total repayable', inr(loan.quote.repayPaise)),
          Kv('Outstanding', inr(loan.outstandingPaise)),
          Kv('Disbursed on', dayMonth(loan.disbursedOn, year: true)),
          Kv('Due date', dayMonth(loan.dueAt, year: true)),
          const SizedBox(height: 12),
          DnButton.outline('View full details', block: true, onPressed: () => showLoanDetails(context, loan)),
        ]),
      );
}

/// Loan documents. Per the Specs board, opening one first asks the borrower to confirm it’s them.
class _Documents extends StatelessWidget {
  const _Documents({required this.docs});
  final List<LoanDoc> docs;

  Future<void> _open(BuildContext context, LoanDoc doc) async {
    final ok = await showDnConfirm(
      context,
      title: 'Confirm it’s you',
      body: 'Loan documents have your personal details, so we check it’s you before opening one.',
      confirm: 'Continue',
      icon: Icons.lock_outline_rounded,
    );
    if (!ok || !context.mounted) return;
    await showDnSheet<void>(
      context,
      builder: (context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        DnDocRow(name: doc.name, meta: doc.meta, first: true),
        const SizedBox(height: 14),
        DnButton('Download', icon: Icons.download_rounded, block: true, onPressed: () async {
          if (demoControls) return showDnToast(context, 'Demo: ${doc.name} downloaded');
          try {
            // A 5-minute link from the server, opened in the browser (it downloads or shares from there).
            final url = await api.documentUrl(doc);
            if (url != null && context.mounted) await openUrl(context, url);
          } on ApiException catch (e) {
            if (context.mounted) showApiError(context, e);
          }
        }),
        if (demoControls) ...[
          const SizedBox(height: 8),
          DnButton.outline('Share', icon: Icons.share_rounded, block: true, onPressed: () => showDnToast(context, 'Demo: share sheet opens here')),
        ],
      ]),
    );
  }

  @override
  Widget build(BuildContext context) => DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const DnPanelHeader(title: 'Loan documents', meta: 'Open in the app, then download or share'),
          for (var i = 0; i < docs.length; i++) DnDocRow(first: i == 0, name: docs[i].name, meta: docs[i].meta, onTap: () => _open(context, docs[i])),
        ]),
      );
}

/// C9 Transactions: money sent to the borrower and their repayments.
class TransactionsScreen extends StatelessWidget {
  const TransactionsScreen({super.key});

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Transactions',
        subtitle: 'Money sent to you and the repayments you’ve made.',
        back: true,
        children: [
          Loaded(
            load: api.transactions,
            builder: (context, txns) => txns.isEmpty
                ? const DnStateView(icon: Icons.swap_vert_rounded, title: 'No transactions yet', body: 'Once a loan is disbursed, your disbursal and repayments appear here.')
                : DnPanel(
                    padding: EdgeInsets.zero,
                    child: Column(children: [for (var i = 0; i < txns.length; i++) TxnRow(txn: txns[i], first: i == 0)]),
                  ),
          ),
        ],
      );
}

/// C10 Borrow again: the level the server unlocked and a way into B1.
class ReloanScreen extends StatelessWidget {
  const ReloanScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final open = api.loan?.isOpen ?? false;
    return DnPage(
      title: 'Borrow again',
      subtitle: 'Your details are already verified, so a fresh loan takes just a few taps.',
      back: true,
      children: [
        if (open)
          DnStateView(
            icon: Icons.currency_rupee_rounded,
            title: 'Repay your current loan first',
            body: 'You can borrow again once your open loan is repaid and closed.',
            primary: 'Repay',
            onPrimary: () => context.go('/repay'),
          )
        else
          Loaded(
            load: api.creditLevel,
            builder: (context, level) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              DnPanel(
                dark: true,
                child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                  DnFigure(paise: level.maxPaise, label: 'Available to borrow now', color: Colors.white, size: DnFigureSize.xl),
                  const SizedBox(height: 6),
                  Text(
                    level.level > 1 ? 'Level ${level.level} of ${level.ladderPaise.length}, unlocked by repaying on time' : 'Level ${level.level} of ${level.ladderPaise.length}',
                    style: DnText.hint.copyWith(color: DnTokens.onDarkMuted),
                  ),
                  const SizedBox(height: 14),
                  DnLevelBar(level: level.level, labels: [for (final p in level.ladderPaise) '${p ~/ 100000}k'], dark: true),
                  const SizedBox(height: 18),
                  const _BorrowAgain(),
                ]),
              ),
              const SizedBox(height: 12),
              const DnPanel(
                padding: EdgeInsets.zero,
                child: Column(children: [
                  DnListRow(first: true, icon: Icons.verified_user_outlined, title: 'No re-KYC', detail: 'Your verified details carry over'),
                  DnListRow(icon: Icons.bolt_rounded, title: 'Clean record, fewer steps', detail: 'Pre-approved, so you go from amount to agreement quickly'),
                  DnListRow(icon: Icons.account_balance_wallet_outlined, title: 'Same fast flow', detail: 'Amount → money in your account'),
                ]),
              ),
              const SizedBox(height: 12),
              const Text('A late repayment keeps you at the same level.', style: DnText.hint),
            ]),
          ),
      ],
    );
  }
}

/// C14 Loan closed receipt. Takes the loan as `extra`, or shows the latest closed loan.
class ClosureScreen extends StatelessWidget {
  const ClosureScreen({super.key, this.loan});
  final Loan? loan;

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Receipt',
        back: true,
        children: [
          Loaded(
            load: () async => (loan ?? (await api.loans()).where((l) => !l.isOpen).firstOrNull, await api.creditLevel()),
            builder: (context, d) {
              final (l, level) = d;
              if (l == null) {
                return DnStateView(icon: Icons.receipt_long_rounded, title: 'No closed loans yet', body: 'Your receipt appears here once a loan is repaid.', primary: 'Back to dashboard', onPrimary: () => context.go('/dashboard'));
              }
              return _Receipt(loan: l, level: level);
            },
          ),
        ],
      );
}

class _Receipt extends StatelessWidget {
  const _Receipt({required this.loan, required this.level});
  final Loan loan;
  final CreditLevel level;

  @override
  Widget build(BuildContext context) {
    final onTime = loan.closedOnTime ?? false;
    final levelUp = onTime && loan.extensionsUsed == 0;
    final closed = loan.closedAt;
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      const ResultDisc(),
      const SizedBox(height: 14),
      Text(onTime ? 'Loan closed. Thank you!' : 'Loan closed', style: DnText.display(30)),
      const SizedBox(height: 6),
      Text('Your payment is verified and loan #${loan.applicationNo} is fully repaid.', style: DnText.body),
      const SizedBox(height: 16),
      DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          TitlePill('Closure receipt', pill: DnPill(onTime ? 'On time' : 'Paid late', tone: onTime ? DnTone.ok : DnTone.warn, dot: true)),
          Kv('Amount received', inr(loan.repaidPaise), first: true),
          if (closed != null) Kv('Paid on', '${dayMonth(closed, year: true)} (${onTime ? 'on time' : 'late'})'),
          Kv('Loan account', loan.accountNo),
          Kv('Balance', inr(loan.outstandingPaise)),
          if (closed != null) Kv('Closed on', dayMonth(closed, year: true)),
        ]),
      ),
      const SizedBox(height: 12),
      if (levelUp)
        DnPanel(
          dark: true,
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('Your next loan can be up to ${inr(level.maxPaise)}', style: DnText.section.copyWith(color: Colors.white)),
            const SizedBox(height: 4),
            Text('Level ${level.level} of ${level.ladderPaise.length}, after fresh checks.', style: DnText.hint.copyWith(color: DnTokens.onDarkMuted)),
            const SizedBox(height: 14),
            DnLevelBar(level: level.level, labels: [for (final p in level.ladderPaise) '${p ~/ 100000}k'], dark: true),
          ]),
        )
      else
        DnBanner(
          title: 'Your level stays the same',
          body: loan.extensionsUsed > 0 ? 'Extended loans keep your level. Your next loan repaid on time moves you up.' : 'Late repayments keep your level. Your next loan repaid on time moves you up.',
        ),
      const SizedBox(height: 8),
      const Text('The receipt is also saved under Your loans and emailed to you.', style: DnText.hint),
      const SizedBox(height: 18),
      DnButton.outline('Download receipt (PDF)', icon: Icons.download_rounded, block: true, onPressed: () => showDnToast(context, 'Demo: receipt downloaded')),
      if (api.loan == null) ...[const SizedBox(height: 8), DnButton('Borrow again', block: true, onPressed: () => context.push('/reloan'))],
      const SizedBox(height: 4),
      DnButton.ghost('Back to dashboard', block: true, onPressed: () => context.go('/dashboard')),
    ]);
  }
}

/// C10: starts the new application on the saved KYC (`POST /api/applications/reborrow`), then the
/// offer journey; past delinquency lands on A14.
class _BorrowAgain extends StatefulWidget {
  const _BorrowAgain();

  @override
  State<_BorrowAgain> createState() => _BorrowAgainState();
}

class _BorrowAgainState extends State<_BorrowAgain> {
  bool _busy = false;

  Future<void> _go() async {
    setState(() => _busy = true);
    try {
      final stage = await api.reborrow();
      if (mounted) context.go(stage == Stage.rejected ? '/application-rejected' : '/loan/amount');
    } on ApiException catch (e) {
      if (mounted) showApiError(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => DnButton('Borrow again', arrow: true, block: true, loading: _busy, onPressed: _go);
}
