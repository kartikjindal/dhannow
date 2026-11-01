import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/format.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'common.dart';

/// Board D1 Profile (tab root). View-only: no edit for mobile, email or any detail (CLAUDE.md).
class ProfileScreen extends StatelessWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Profile',
        children: [
          Loaded<Customer>(load: api.me, builder: (context, c) => _Details(c)),
          Section('Account', children: [
            DnListRow(first: true, icon: Icons.account_balance_rounded, title: 'Loan account details', onTap: () => context.push('/loan-account')),
            DnListRow(icon: Icons.receipt_long_rounded, title: 'My loans and documents', onTap: () => context.go('/loans')),
            DnListRow(icon: Icons.swap_vert_rounded, title: 'Transactions', onTap: () => context.push('/transactions')),
            DnListRow(icon: Icons.settings_rounded, title: 'Settings and security', onTap: () => context.push('/settings')),
            DnListRow(icon: Icons.devices_rounded, title: 'Devices and sessions', onTap: () => context.push('/devices')),
            DnListRow(icon: Icons.support_agent_rounded, title: 'Help and support', onTap: () => context.push('/support')),
            DnListRow(icon: Icons.forum_rounded, title: 'My complaints', onTap: () => context.push('/complaints')),
            DnListRow(icon: Icons.shield_rounded, title: 'How we keep you safe', onTap: () => context.push('/trust')),
            DnListRow(
              icon: Icons.gavel_rounded,
              title: 'Terms, privacy and fair practices',
              onTap: () => showDnSheet<void>(
                context,
                builder: (context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: siteLinks(context, legalPages)),
              ),
            ),
          ]),
          const _LogOut(),
          const Text('App version $appVersion', textAlign: TextAlign.center, style: DnText.hint),
        ],
      );
}

class _Details extends StatelessWidget {
  const _Details(this.c);
  final Customer c;

  @override
  Widget build(BuildContext context) {
    final initials = c.fullName.split(' ').where((w) => w.isNotEmpty).take(2).map((w) => w[0]).join();
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Row(children: [
        Container(
          width: 64,
          height: 64,
          alignment: Alignment.center,
          decoration: const BoxDecoration(color: DnTokens.tint, shape: BoxShape.circle),
          child: Text(initials, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600, color: DnTokens.primary600)),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(c.fullName, style: const TextStyle(fontSize: 20, fontWeight: FontWeight.w600, color: DnTokens.ink)),
            Text(c.mobileMasked, style: DnText.hint),
            Text('Customer ID ${c.id}', style: DnText.hint),
          ]),
        ),
      ]),
      const SizedBox(height: 16),
      DnBanner(
        icon: Icons.lock_outline_rounded,
        title: 'Your details are view-only',
        body: 'They come from your verified documents and can’t be changed. If something is wrong, contact support.',
      ),
      Align(alignment: Alignment.centerLeft, child: DnButton.text('Contact support', onPressed: () => context.push('/support'))),
      const SizedBox(height: 18),
      Section('Personal', padRows: true, children: infoRows([
        ('Customer ID', c.id),
        ('Full name', c.fullName),
        ('Date of birth', c.dob),
        ('PAN', c.panMasked),
        ('Aadhaar', c.aadhaarMasked),
      ])),
      Section('Contact', padRows: true, children: infoRows([
        ('Mobile', c.mobileMasked),
        ('Email', c.email),
        ('Current address', c.address),
      ])),
      Section('Work and income', padRows: true, children: infoRows([
        ('Employer', c.employer),
        ('Monthly salary', inr(c.monthlyIncomePaise)),
      ])),
      Section('Bank account', padRows: true, children: infoRows([
        ('Salary account', '${c.bankName} ${c.bankMasked}'),
        ('IFSC', c.ifsc),
      ])),
    ]);
  }
}

class _LogOut extends StatefulWidget {
  const _LogOut();

  @override
  State<_LogOut> createState() => _LogOutState();
}

class _LogOutState extends State<_LogOut> {
  bool _busy = false;

  Future<void> _logOut() async {
    final ok = await showDnConfirm(
      context,
      title: 'Log out?',
      body: 'You’ll need your mobile number and password to log in again.',
      confirm: 'Log out',
      icon: Icons.logout_rounded,
    );
    if (!ok || !mounted) return;
    setState(() => _busy = true);
    try {
      await api.logout();
      if (mounted) context.go('/welcome');
    } on ApiException catch (e) {
      if (mounted) showDnToast(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: DnButton.ghost('Log out', block: true, icon: Icons.logout_rounded, loading: _busy, onPressed: _logOut),
      );
}

/// Board D6 Loan account details: the open loan, else the latest closed one.
class LoanAccountScreen extends StatelessWidget {
  const LoanAccountScreen({super.key});

  static Future<(List<Loan>, Customer, CreditLevel, List<LoanDoc>)> _load() => (api.loans(), api.me(), api.creditLevel(), api.documents()).wait;

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Loan account details',
        back: true,
        children: [
          Loaded(
            load: _load,
            builder: (context, d) {
              final (loans, me, level, docs) = d;
              final loan = loans.where((l) => l.isOpen).firstOrNull ?? loans.firstOrNull;
              if (loan == null) {
                return DnStateView(
                  icon: Icons.receipt_long_rounded,
                  title: 'No loan account yet',
                  body: 'Your loan account details show here once your first loan is sent.',
                  primary: 'Go home',
                  onPrimary: () => context.go('/dashboard'),
                );
              }
              return _LoanAccount(loan: loan, me: me, level: level, docs: docs);
            },
          ),
        ],
      );
}

class _LoanAccount extends StatelessWidget {
  const _LoanAccount({required this.loan, required this.me, required this.level, required this.docs});
  final Loan loan;
  final Customer me;
  final CreditLevel level;
  final List<LoanDoc> docs;

  @override
  Widget build(BuildContext context) {
    final q = loan.quote;
    final (status, tone) = loanStatus(loan);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [const Expanded(child: DnCaps('Loan account')), DnPill(status, tone: tone)]),
          const SizedBox(height: 8),
          Text(loan.accountNo, style: DnText.display(30)),
          const SizedBox(height: 4),
          Text('Opened ${dayMonth(loan.disbursedOn, year: true)} · Application #${loan.applicationNo}', style: DnText.hint),
        ]),
      ),
      const SizedBox(height: 18),
      Section('Loan terms', padRows: true, children: infoRows([
        ('Lender', 'As named in your KFS'),
        ('Platform', Brand.name),
        ('Amount', inr(q.principalPaise)),
        ('Processing fee', inr(q.feePaise)),
        ('GST on fee', inr(q.gstPaise)),
        ('Interest', inr(q.interestPaise)),
        ('Received in bank', inr(q.netPaise)),
        ('Repay', inr(q.repayPaise)),
        if (loan.latePaise > 0) ('Late charge to date', inr(loan.latePaise)),
        ('Tenure', '${q.tenureDays} days'),
        ('Due', '${dayMonth(loan.dueAt, year: true)}, ${clock(loan.dueAt)}'),
        ('Extensions used', '${loan.extensionsUsed} of ${loan.extensionCap}'),
        if (loan.closedAt != null) ('Closed on', dayMonth(loan.closedAt!, year: true)),
        ('Repayment method', 'UPI or bank transfer'),
        ('Payout account', '${me.bankName} ${me.bankMasked}'),
      ])),
      if (loan.disbursalUtr != null) ...[
        DnCopyRow(label: 'Disbursal UTR', value: loan.disbursalUtr!),
        const SizedBox(height: 18),
      ],
      const Padding(padding: EdgeInsets.only(bottom: 8, left: 4), child: DnCaps('Credit level')),
      DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Wrap(alignment: WrapAlignment.spaceBetween, spacing: 12, children: [
            Text('Level ${level.level} · ${inr(level.maxPaise)}', style: DnText.strong),
            if (level.nextLevelPaise != null) Text('Next: ${inr(level.nextLevelPaise!)}', style: DnText.hint),
          ]),
          const SizedBox(height: 12),
          DnLevelBar(level: level.level, labels: [for (final p in level.ladderPaise) '${p ~/ 100000}k']),
        ]),
      ),
      const SizedBox(height: 18),
      Section('Documents', children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 2),
          child: Column(children: [for (final (i, doc) in docs.indexed) DnDocRow(name: doc.name, meta: doc.meta, first: i == 0)]),
        ),
      ]),
      const Text('View-only. Documents open after a quick fingerprint or OTP check; links expire in 5 minutes.', style: DnText.hint),
    ]);
  }
}

/// Status pill for a loan's lifecycle.
(String, DnTone) loanStatus(Loan l) => switch (l.lifecycle) {
      Lifecycle.closed => ('Closed', DnTone.ok),
      Lifecycle.disbursementPending => ('Confirming', DnTone.warn),
      Lifecycle.dueToday => ('Due today', DnTone.warn),
      Lifecycle.overdueD1 || Lifecycle.overdueReview => ('Late', DnTone.err),
      _ => ('Active', DnTone.cobalt),
    };
