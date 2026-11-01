import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/brand.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import '../money/extension_status_card.dart';
import 'loan_details.dart';
import 'milestones.dart';

/// What the dashboard loads besides the sync [Api.stage] / [Api.loan].
typedef _Home = ({Customer me, CreditLevel level, Quote offer, List<Notice> notices, List<Loan> loans, List<StatusStep> status});

const _progressStages = {Stage.inReview, Stage.disbursal, Stage.offerFlow};

Future<_Home> _fetch() async {
  final r = await Future.wait<Object>([api.me(), api.creditLevel(), api.notices(), api.loans()]);
  final (me, level, notices, loans) = (r[0] as Customer, r[1] as CreditLevel, r[2] as List<Notice>, r[3] as List<Loan>);
  final offer = await api.quote(level.maxPaise);
  final status = _progressStages.contains(api.stage) ? await api.applicationStatus() : const <StatusStep>[];
  return (me: me, level: level, offer: offer, notices: notices, loans: loans, status: status);
}

/// C1–C4 + C1b: the Home tab. One screen that follows the server's stage and loan state.
class DashboardScreen extends StatefulWidget {
  const DashboardScreen({super.key});

  @override
  State<DashboardScreen> createState() => _DashboardScreenState();
}

class _DashboardScreenState extends State<DashboardScreen> {
  late Future<_Home> _home = _fetch();

  /// "View more" on the active dashboard: collapsed by default, kept while the tab lives.
  bool _more = false;

  @override
  void initState() {
    super.initState();
    api.addListener(_reload);
  }

  @override
  void dispose() {
    api.removeListener(_reload);
    super.dispose();
  }

  // Errors reach the FutureBuilder on the next frame; `ignore` keeps them from counting as unhandled.
  void _reload() => setState(() {
        _home = _fetch()..ignore();
      });

  Future<void> _refresh() {
    _reload();
    return _home.then((_) {}, onError: (_) {});
  }

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: api,
        builder: (context, _) => FutureBuilder(
          future: _home,
          builder: (context, snap) {
            final h = snap.data;
            return DnPage(
              title: h == null ? 'Hi' : 'Hi, ${h.me.firstName}',
              subtitle: 'Here’s where your loan stands.',
              onRefresh: _refresh,
              actions: [
                DnIconPill(
                  icon: Icons.notifications_none_rounded,
                  tooltip: 'Notifications',
                  badge: h?.notices.any((n) => !n.read) ?? false,
                  onPressed: () => context.push('/notifications'),
                ),
              ],
              children: snap.hasError
                  ? [
                      DnStateView(
                        icon: Icons.cloud_off_rounded,
                        error: true,
                        title: 'Couldn’t load your dashboard',
                        body: snap.error is ApiException ? (snap.error as ApiException).message : null,
                        primary: 'Try again',
                        onPrimary: _reload,
                        secondary: 'Get help',
                        onSecondary: () => context.push('/support'),
                      ),
                    ]
                  : h == null
                      ? const [DnSkeleton(height: 220, radius: 22), SizedBox(height: 12), DnSkeleton(height: 96, radius: 22)]
                      : _gap(_body(context, h)),
            );
          },
        ),
      );

  List<Widget> _body(BuildContext context, _Home h) {
    final loan = api.loan;
    final stage = api.stage;
    final checking = loan?.repayment == Repayment.pendingVerification;
    return [
      if (checking)
        DnBanner(
          title: 'We’re checking your payment',
          body: 'Our team verifies payments, usually the same day. Your loan updates once it’s verified.',
          action: 'View',
          onAction: () => context.push('/transactions'),
        ),
      ...switch (stage) {
        Stage.active when loan != null && loan.isOverdue => _overdue(context, loan, h),
        Stage.active when loan != null => _active(context, loan, h),
        Stage.offer || Stage.closed => _offer(context, h, closed: stage == Stage.closed),
        _ => [_ProgressCard(stage: stage, loan: loan, status: h.status)],
      },
    ];
  }

  List<Widget> _active(BuildContext context, Loan loan, _Home h) {
    final dueToday = loan.lifecycle == Lifecycle.dueToday || loan.daysLeft == 0;
    final extended = loan.extensionsUsed > 0;
    final lv = h.level;
    return [
      if (dueToday)
        DnBanner(
          tone: DnBannerTone.warn,
          title: 'Your repayment is due today',
          body: 'Repay ${inr(loan.outstandingPaise)} by ${clock(loan.dueAt)} today to stay on time.',
        ),
      _LoanCard(loan: loan),
      if (extended) ExtensionStatusCard(loan: loan),
      DnPanel(
        child: Row(children: [
          SweepRing(daysLeft: loan.daysLeft, tenureDays: loan.quote.tenureDays),
          const SizedBox(width: 16),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Row(children: [
                const Icon(Icons.flag_rounded, size: 15, color: DnTokens.ink),
                const SizedBox(width: 6),
                Flexible(child: Text('Next milestone', style: DnText.hint.copyWith(fontWeight: FontWeight.w600))),
              ]),
              const SizedBox(height: 4),
              Text(
                dueToday ? 'Repay ${inr(loan.outstandingPaise)} by ${clock(loan.dueAt)} today' : 'Repay ${inr(loan.outstandingPaise)} by ${dayMonth(loan.dueAt)}',
                style: DnText.strong,
              ),
              Text(
                extended
                    ? 'Before ${clock(loan.dueAt)}. Extended loans keep your level at ${inr(lv.maxPaise)}.'
                    : lv.nextLevelPaise == null
                        ? 'Before ${clock(loan.dueAt)}. On time keeps you at the top level.'
                        : 'Before ${clock(loan.dueAt)}. On time unlocks ${inr(lv.nextLevelPaise!)}.',
                style: DnText.hint,
              ),
            ]),
          ),
        ]),
      ),
      DnPanel(
        dark: true,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Wrap(spacing: 8, runSpacing: 4, alignment: WrapAlignment.spaceBetween, crossAxisAlignment: WrapCrossAlignment.center, children: [
            Text('Your credit level', style: DnText.section.copyWith(color: Colors.white)),
            Text('Level ${lv.level} of ${lv.ladderPaise.length}', style: DnText.hint.copyWith(color: DnTokens.onDarkMuted)),
          ]),
          const SizedBox(height: 16),
          DnLevelBar(level: lv.level, labels: levelLabels(lv), dark: true),
          const SizedBox(height: 14),
          Text(
            extended
                ? 'Stays at Level ${lv.level} because this loan was extended. Your next loan on time moves you up.'
                : lv.nextLevelPaise == null
                    ? 'You’re at the top level.'
                    : 'Repay on time to unlock ${inr(lv.nextLevelPaise!)} next time.',
            style: DnText.body.copyWith(color: DnTokens.onDarkMuted),
          ),
        ]),
      ),
      Align(
        alignment: Alignment.centerLeft,
        child: DnButton.text(_more ? 'View less' : 'View more', icon: _more ? Icons.expand_less_rounded : Icons.expand_more_rounded, onPressed: () => setState(() => _more = !_more)),
      ),
      if (_more) ..._moreLinks(context, loan, h.me),
    ];
  }

  List<Widget> _moreLinks(BuildContext context, Loan loan, Customer me) => [
        const DnCaps('Your loan'),
        DnPanel(
          padding: EdgeInsets.zero,
          child: Column(children: [
            DnListRow(first: true, icon: Icons.account_balance_wallet_rounded, title: 'How to repay', detail: 'UPI or bank transfer, then add the reference', onTap: () => context.go('/repay')),
            DnListRow(icon: Icons.description_rounded, title: 'Loan documents', detail: 'KFS, sanction letter, agreement', onTap: () => context.go('/loans')),
            DnListRow(
              icon: Icons.swap_horiz_rounded,
              title: 'Payments and transactions',
              detail: '${inr(loan.quote.netPaise)} received on ${dayMonth(loan.disbursedOn)}',
              onTap: () => context.push('/transactions'),
            ),
          ]),
        ),
        const DnCaps('Help'),
        DnPanel(
          padding: EdgeInsets.zero,
          child: Column(children: [
            DnListRow(first: true, icon: Icons.chat_bubble_outline_rounded, title: 'Payment not showing?', detail: 'Payments are verified by our team, usually the same day', onTap: () => context.push('/support')),
            DnListRow(icon: Icons.shield_outlined, title: 'My complaints', detail: 'Raise or track a complaint', onTap: () => context.push('/complaints')),
          ]),
        ),
        const DnCaps('Invite friends'),
        DnPanel(
          child: Row(children: [
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Text('Refer and earn', style: DnText.strong),
                Text('Code ${me.referralCode} · paid after their first loan', style: DnText.hint),
              ]),
            ),
            const SizedBox(width: 10),
            DnButton.outline('Share', size: DnButtonSize.sm, onPressed: () async {
              await Clipboard.setData(ClipboardData(text: me.referralCode));
              if (context.mounted) showDnToast(context, 'Referral code copied');
            }),
          ]),
        ),
      ];

  List<Widget> _overdue(BuildContext context, Loan loan, _Home h) => [
        DnBanner(
          tone: DnBannerTone.err,
          title: 'Your repayment is late',
          body: 'A late charge is added each day until you pay. Pay today to stop it growing.',
        ),
        _LoanCard(loan: loan),
        ExtensionStatusCard(loan: loan),
        DnPanel(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Your credit level stays at ${inr(h.level.maxPaise)}', style: DnText.strong),
            const Text('Paying late doesn’t unlock the next level. Pay in full to keep borrowing.', style: DnText.hint),
          ]),
        ),
        DnPanel(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Need more time?', style: DnText.strong),
            Text(
              'Call our collections team. They can extend your loan by ${loan.quote.tenureDays} days for a charge plus any late charge '
              '(up to ${loan.extensionCap} times). You confirm in the app before paying.',
              style: DnText.hint,
            ),
            DnButton.text('Call ${Brand.supportPhone}', icon: Icons.call_rounded, onPressed: () => context.push('/support')),
          ]),
        ),
        DnPanel(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            const Text('Already paid?', style: DnText.strong),
            const Text('If you paid in the last few hours it may still be under verification. Check Payments in Your loans.', style: DnText.hint),
            DnButton.text('See payments', onPressed: () => context.push('/transactions')),
          ]),
        ),
        DnButton.outline('Talk to us', block: true, onPressed: () => context.push('/support')),
      ];

  List<Widget> _offer(BuildContext context, _Home h, {required bool closed}) {
    final lv = h.level;
    final closedLoan = h.loans.where((l) => !l.isOpen).firstOrNull;
    return [
      DnPanel(
        dark: true,
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          DnPill('Up to ${inr(lv.maxPaise)} pre-approved', tone: DnTone.cobalt),
          const SizedBox(height: 12),
          Text('Pre-approved personal loan', style: DnText.display(34, color: Colors.white)),
          const SizedBox(height: 8),
          Text('No paperwork · One repayment on day ${h.offer.tenureDays} · Money straight to your bank', style: DnText.body.copyWith(color: DnTokens.onDarkMuted)),
          if (closed) ...[
            const SizedBox(height: 8),
            Text('Your previous loan is fully repaid. You’re in good standing.', style: DnText.body.copyWith(color: Colors.white)),
          ],
          const SizedBox(height: 16),
          DnButton('Get ${inr(lv.maxPaise)}', block: true, arrow: true, onPressed: () => closed ? context.push('/reloan') : context.push('/loan/amount')),
          const SizedBox(height: 8),
          Text('*T&C apply', style: DnText.hint.copyWith(color: DnTokens.onDarkMuted)),
        ]),
      ),
      if (closed) LevelUpCard(level: lv, closed: closedLoan),
    ];
  }

  static List<Widget> _gap(List<Widget> ws) => [
        for (final (i, w) in ws.indexed) ...[if (i > 0) const SizedBox(height: DnTokens.gap), w],
      ];
}

/// C1 / C1b / C2 loan card: outstanding, due, principal and the third tile for the state.
class _LoanCard extends StatelessWidget {
  const _LoanCard({required this.loan});
  final Loan loan;

  @override
  Widget build(BuildContext context) {
    final l = loan;
    final q = l.quote;
    final late = l.isOverdue;
    final days = l.daysLeft.abs();
    final (title, pill, tone) = late
        ? ('Repayment overdue', '$days ${days == 1 ? 'day' : 'days'} past due', DnTone.err)
        : l.daysLeft == 0
            ? ('Active loan', 'Due today', DnTone.warn)
            : ('Active loan', 'Due in $days ${days == 1 ? 'day' : 'days'}', DnTone.cobalt);
    final lastExt = l.extensions.lastOrNull;
    return DnPanel(
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Wrap(spacing: 8, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, alignment: WrapAlignment.spaceBetween, children: [
          Wrap(spacing: 8, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [
            Text(title, style: DnText.section),
            if (l.extensionsUsed > 0) DnInkChip('Extended ${l.extensionsUsed} of ${l.extensionCap}', icon: Icons.event_repeat_rounded),
          ]),
          DnPill(pill, tone: tone),
        ]),
        const SizedBox(height: 14),
        DnFigure(paise: l.outstandingPaise, label: 'Total outstanding', color: late ? DnTokens.err : null),
        const SizedBox(height: 10),
        if (late)
          Text('Includes ${inr(l.latePaise)} late charge to date', style: DnText.hint.copyWith(color: DnTokens.errInk))
        else
          Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            DnGoalProgress(value: q.repayPaise == 0 ? 0 : l.repaidPaise / q.repayPaise, light: true),
            const SizedBox(height: 6),
            Wrap(spacing: 8, alignment: WrapAlignment.spaceBetween, children: [
              Text('${inr(l.repaidPaise)} repaid', style: DnText.hint),
              Text('${inr(q.repayPaise)} total', style: DnText.hint),
            ]),
          ]),
        const SizedBox(height: 14),
        _Tiles([
          (late ? 'Was due' : l.extensionsUsed > 0 ? 'New due date' : 'Due date', dayMonth(l.dueAt)),
          ('Principal', inr(q.principalPaise)),
          if (late)
            ('Late charge', inr(l.latePaise))
          else if (lastExt != null)
            ('Extension ${lastExt.n} paid', inr(lastExt.chargePaise))
          else
            ('Repaid', '${q.repayPaise == 0 ? 0 : (l.repaidPaise * 100 ~/ q.repayPaise)}%'),
        ]),
        const SizedBox(height: 16),
        DnButton(late ? 'Pay ${inr(l.outstandingPaise)} now' : 'Repay now', block: true, icon: Icons.account_balance_wallet_rounded, onPressed: () => context.go('/repay')),
        const SizedBox(height: 8),
        DnButton.ghost('View full breakdown', block: true, onPressed: () => showLoanDetails(context, l)),
        if (!late) ...[
          const SizedBox(height: 8),
          Wrap(crossAxisAlignment: WrapCrossAlignment.center, children: [
            const Text('Payment not reflecting? ', style: DnText.hint),
            DnButton.text('Call or email us', onPressed: () => context.push('/support')),
          ]),
        ],
      ]),
    );
  }
}

/// Three label/value tiles in a row; stacked when the row would be too tight (CC-58).
class _Tiles extends StatelessWidget {
  const _Tiles(this.items);
  final List<(String, String)> items;

  @override
  Widget build(BuildContext context) => LayoutBuilder(builder: (context, c) {
        final narrow = c.maxWidth / MediaQuery.textScalerOf(context).scale(1) < 300;
        final tiles = [
          for (final (label, value) in items)
            DnTile(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              child: narrow
                  ? Row(children: [Expanded(child: Text(label, style: DnText.hint)), Text(value, style: DnText.money)])
                  : Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(label, style: DnText.hint, maxLines: 1, overflow: TextOverflow.ellipsis),
                      FittedBox(fit: BoxFit.scaleDown, alignment: Alignment.centerLeft, child: Text(value, style: DnText.money)),
                    ]),
            ),
        ];
        if (narrow) return Column(children: [for (final (i, t) in tiles.indexed) Padding(padding: EdgeInsets.only(top: i == 0 ? 0 : 8), child: t)]);
        return Row(children: [for (final (i, t) in tiles.indexed) ...[if (i > 0) const SizedBox(width: 8), Expanded(child: t)]]);
      });
}

/// C3: application in progress (sign-up, review, approved, disbursal, and the side states).
class _ProgressCard extends StatelessWidget {
  const _ProgressCard({required this.stage, required this.loan, required this.status});

  final Stage stage;
  final Loan? loan;
  final List<StatusStep> status;

  @override
  Widget build(BuildContext context) {
    final done = api.onboardingDone.length;
    final total = Api.signupSteps.length;
    final (chip, tone, title, body, action, route, mTitle, mBody) = switch (stage) {
      Stage.inReview => (
          'Under review',
          DnTone.cobalt,
          'Credit decision in progress',
          'You’ve finished sign-up. Our credit team is checking your details.',
          'Track my application',
          '/loan/status',
          'Your loan offer',
          'Usually within 2–3 working hours. We’ll notify you. You can close the app.',
        ),
      Stage.offerFlow => (
          'Approved',
          DnTone.ok,
          'You’re approved',
          'Our credit team has approved your loan. A few steps left to receive your money.',
          'Continue your loan',
          '/loan/amount',
          'Complete your offer steps',
          'Choose your amount, sign, and tell us where to send the money.',
        ),
      Stage.disbursal => (
          'Processing',
          DnTone.warn,
          'Money on the way',
          'We’ve sent your money. We’re confirming with the bank.',
          'Track my application',
          '/loan/status',
          'Disbursal in progress',
          'Your approved loan is moving through the final release checks.',
        ),
      Stage.question => (
          'Action needed',
          DnTone.warn,
          'One more question',
          'Our credit team needs you to answer a security question.',
          'Answer now',
          '/credit-question',
          null,
          null,
        ),
      Stage.rejected => (
          'Not approved',
          DnTone.err,
          'Application not approved',
          'Your application didn’t meet current credit policy. See your status page for details.',
          'View status',
          '/application-rejected',
          null,
          null,
        ),
      _ => (
          'Sign-up',
          DnTone.neutral,
          'Finish signing up',
          'You’ve done $done of $total steps. Pick up where you left off.',
          'Continue sign-up',
          '/resume',
          'Your loan offer',
          'Finish sign-up and our credit team will check your details.',
        ),
    };
    final window = _window(status);
    return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Wrap(spacing: 8, runSpacing: 6, alignment: WrapAlignment.spaceBetween, crossAxisAlignment: WrapCrossAlignment.center, children: [
            Text(loan == null ? 'Application' : 'Application #${loan!.applicationNo}', style: DnText.hint.copyWith(fontWeight: FontWeight.w600)),
            DnPill(chip, tone: tone),
          ]),
          const SizedBox(height: 10),
          Text(title, style: DnText.display(28)),
          const SizedBox(height: 6),
          Text(body, style: DnText.body),
          if (stage == Stage.onboarding || stage == Stage.signedOut) ...[
            const SizedBox(height: 14),
            DnProgressBar(value: total == 0 ? 0 : done / total, semanticLabel: 'Sign-up progress'),
          ],
          if (window.isNotEmpty) ...[const SizedBox(height: 16), DnTimeline(steps: window)],
          const SizedBox(height: 16),
          DnButton(action, block: true, onPressed: () => context.push(route)),
        ]),
      ),
      if (mTitle != null) ...[
        const SizedBox(height: DnTokens.gap),
        DnPanel(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text('Next milestone', style: DnText.hint.copyWith(fontWeight: FontWeight.w600)),
            const SizedBox(height: 4),
            Text(mTitle, style: DnText.strong),
            if (mBody != null) Text(mBody, style: DnText.hint),
          ]),
        ),
      ],
    ]);
  }

  /// Compact timeline: the last finished step, the current one and the next.
  static List<DnStep> _window(List<StatusStep> s) {
    final cur = s.indexWhere((x) => x.current);
    if (cur < 0) return const [];
    final last = cur + 1 < s.length ? cur + 1 : cur;
    return [
      for (var i = cur > 0 ? cur - 1 : 0; i <= last; i++)
        DnStep(s[i].title, detail: s[i].detail, state: s[i].done ? DnStepState.done : s[i].current ? DnStepState.now : DnStepState.todo),
    ];
  }
}
