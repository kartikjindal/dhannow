import 'package:flutter/material.dart';

import '../core/format.dart';
import '../core/theme/theme.dart';
import '../core/theme/tokens.dart';
import '../core/widgets/widgets.dart';

/// Every kit widget on one scrolling screen, matching docs/wireframes/ui-kit.html, so the kit can
/// be checked on a phone. Development only; journeys replace it as the home route.
class KitGallery extends StatefulWidget {
  const KitGallery({super.key});

  @override
  State<KitGallery> createState() => _KitGalleryState();
}

/// Sample offer figures as the server returns them (PLAN worked table, paise). The app never
/// derives these itself.
const _sampleQuotes = {
  100000: (fee: 10000, gst: 1800, interest: 14000, net: 74200),
  200000: (fee: 20000, gst: 3600, interest: 28000, net: 148400),
  300000: (fee: 30000, gst: 5400, interest: 42000, net: 222600),
  400000: (fee: 40000, gst: 7200, interest: 56000, net: 296800),
  500000: (fee: 50000, gst: 9000, interest: 70000, net: 371000),
  700000: (fee: 70000, gst: 12600, interest: 98000, net: 519400),
  1000000: (fee: 100000, gst: 18000, interest: 140000, net: 742000),
};

class _KitGalleryState extends State<KitGallery> {
  int _nav = 0;
  int _amount = 200000;
  int _tab = 0;
  int _level = 2;
  int _days = 9;
  bool _consent = false;
  bool _push = true;
  String _login = 'otp';
  String _employment = 'salaried';
  DnOtpState _otp = DnOtpState.idle;
  bool _busy = false;
  final _mobile = TextEditingController();
  String? _mobileError;

  @override
  void dispose() {
    _mobile.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final q = _sampleQuotes[_amount]!;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 32),
          children: [
            DnStepHeader(title: 'DhanNow UI kit', subtitle: 'Every common component, as approved on 1 Nov 2026.', step: 3, of: 9, onBack: () {}),
            _section('Dashboard'),
            DnPanel(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                DnPanelHeader(title: 'Active advance', meta: 'Loan DN-LN-000184', trailing: const DnPill('Active', tone: DnTone.ok, dot: true)),
                Row(children: [
                  const Expanded(child: DnFigure(paise: 100000, label: 'Repay by 19 Oct, 11:59 pm')),
                  DnCountdownRing(daysLeft: _days),
                ]),
                const SizedBox(height: 14),
                Slider(value: _days.toDouble(), min: -3, max: 14, divisions: 17, label: '$_days', onChanged: (v) => setState(() => _days = v.round())),
                DnButton('Pay ₹1,000', block: true, arrow: true, onPressed: () {}),
              ]),
            ),
            const SizedBox(height: DnTokens.gap),
            DnPanel(
              dark: true,
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                DnPanelHeader(title: 'Credit level $_level', meta: 'Repay on time to reach the next level', dark: true, trailing: const DnDeltaChip('+1 level', sign: 1, onDark: true)),
                DnLevelBar(level: _level, labels: const ['1k', '2k', '3k', '4k', '5k', '7k', '10k'], dark: true),
                const SizedBox(height: 12),
                Wrap(spacing: 8, runSpacing: 8, children: [
                  DnButton.outline('Level down', size: DnButtonSize.xs, onPressed: _level > 1 ? () => setState(() => _level--) : null),
                  DnButton.outline('Level up', size: DnButtonSize.xs, onPressed: _level < 7 ? () => setState(() => _level++) : null),
                ]),
              ]),
            ),
            _section('Choose amount'),
            DnPanel(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                DnLadderChips(amountsPaise: _sampleQuotes.keys.toList(), maxPaise: 300000, selected: _amount, onSelected: (a) => setState(() => _amount = a)),
                const SizedBox(height: 16),
                DnBreakdown(lines: [
                  DnLine('Loan amount', _amount),
                  DnLine('Processing fee (10%)', q.fee, minus: true),
                  DnLine('GST on fee (18%)', q.gst, minus: true),
                  DnLine('Interest, 14 days at 1% a day', q.interest, minus: true),
                ], total: DnLine('You get in your bank', q.net)),
                const SizedBox(height: 8),
                Text('You repay ${inr(_amount)} on day 14.', style: DnText.hint),
              ]),
            ),
            _section('Buttons'),
            Wrap(spacing: 8, runSpacing: 8, children: [
              DnButton('Continue', arrow: true, loading: _busy, onPressed: () async {
                setState(() => _busy = true);
                await Future<void>.delayed(const Duration(seconds: 2));
                if (mounted) setState(() => _busy = false);
              }),
              DnButton.dark('Dark', onPressed: () {}),
              DnButton.outline('Outline', onPressed: () {}),
              DnButton.ghost('Ghost', onPressed: () {}),
              DnButton.danger('Delete', onPressed: () {}),
              const DnButton('Disabled', onPressed: null),
              DnButton.text('Text link', onPressed: () {}),
            ]),
            _section('Inputs'),
            DnPanel(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                DnSegmented(options: const {'otp': 'OTP', 'password': 'Password'}, value: _login, onChanged: (v) => setState(() => _login = v)),
                const SizedBox(height: 16),
                DnField(
                  label: 'Mobile number',
                  required: true,
                  prefix: '+91',
                  controller: _mobile,
                  keyboardType: TextInputType.phone,
                  error: _mobileError,
                  onChanged: (v) => setState(() => _mobileError = v.isEmpty || RegExp(r'^[6-9]\d{9}$').hasMatch(v) ? null : 'Enter a 10-digit mobile number'),
                ),
                const SizedBox(height: 14),
                const DnField(label: 'Password', obscure: true, helper: '6 to 10 characters with a letter, a digit and a symbol'),
                const SizedBox(height: 14),
                const DnField(label: 'PAN', readOnly: true, hint: 'ABCDE1234F', suffix: Icon(Icons.lock_outline_rounded, size: 18, color: DnTokens.slate)),
                const SizedBox(height: 16),
                Text('One-time code (try 482913)', style: DnText.label),
                const SizedBox(height: 7),
                DnOtpField(state: _otp, onCompleted: (v) => setState(() => _otp = v == '482913' ? DnOtpState.ok : DnOtpState.error)),
                const SizedBox(height: 16),
                DnCheck(
                  value: _consent,
                  onChanged: (v) => setState(() => _consent = v),
                  label: const TextSpan(children: [TextSpan(text: 'I agree to the '), TextSpan(text: 'terms', style: TextStyle(fontWeight: FontWeight.w600, color: DnTokens.ink)), TextSpan(text: ' and to a credit check.')]),
                ),
                const SizedBox(height: 12),
                for (final (k, t, s, i) in const [('salaried', 'Salaried', 'Monthly salary to a bank account', Icons.badge_outlined), ('self', 'Self-employed', 'Own business or practice', Icons.storefront_outlined)]) ...[
                  DnChoiceCard(title: t, subtitle: s, icon: i, selected: _employment == k, onTap: () => setState(() => _employment = k)),
                  const SizedBox(height: 8),
                ],
                Row(children: [
                  const Expanded(child: Text('Push notifications', style: DnText.strong)),
                  DnSwitch(value: _push, label: 'Push notifications', onChanged: (v) => setState(() => _push = v)),
                ]),
              ]),
            ),
            _section('Status'),
            DnPanel(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                const Wrap(spacing: 6, runSpacing: 6, children: [
                  DnPill('Active', tone: DnTone.ok, dot: true),
                  DnPill('Due today', tone: DnTone.warn),
                  DnPill('Overdue', tone: DnTone.err),
                  DnPill('Extended', tone: DnTone.info),
                  DnPill('Under review', tone: DnTone.cobalt),
                  DnPill('Closed', tone: DnTone.neutral),
                  DnInkChip('Extended 1 of 3', icon: Icons.event_repeat_rounded),
                  DnDeltaChip('−2 days', sign: -1),
                ]),
                const SizedBox(height: 18),
                const DnTimeline(steps: [
                  DnStep('Applied', detail: '12 Oct, 10:42 am', state: DnStepState.done),
                  DnStep('Approved', detail: '12 Oct, 4:10 pm', state: DnStepState.done),
                  DnStep('Disbursal sent', detail: 'UTR 4521 8890 1234', state: DnStepState.done),
                  DnStep('Disbursal confirmed', detail: 'Day 0 starts now', state: DnStepState.now),
                  DnStep('On hold: bank details check', state: DnStepState.branch),
                  DnStep('Active'),
                  DnStep('Repaid and closed'),
                ]),
                const SizedBox(height: 16),
                const DnGoalProgress(value: .64, left: 'Paid ₹640', right: 'of ₹1,000'),
              ]),
            ),
            _section('Lists and documents'),
            DnPanel(
              padding: EdgeInsets.zero,
              child: Column(children: [
                DnListRow(first: true, title: 'Loan account details', detail: 'Lender, level, documents', icon: Icons.account_balance_outlined, onTap: () {}),
                DnListRow(title: 'Devices and sessions', detail: 'This phone and 1 other', icon: Icons.devices_outlined, onTap: () {}),
                DnListRow(title: 'Help and support', detail: '9211331169', icon: Icons.support_agent_outlined, onTap: () {}),
              ]),
            ),
            const SizedBox(height: DnTokens.gap),
            DnPanel(
              child: Column(children: [
                DnDocRow(first: true, name: 'Key Fact Statement', meta: 'Signed 12 Oct', onTap: () {}),
                DnDocRow(name: 'Loan agreement', meta: 'Signed 12 Oct', onTap: () {}),
                const SizedBox(height: 8),
                const DnCopyRow(label: 'UPI ID', value: 'dhannow@upi'),
              ]),
            ),
            _section('Tiles'),
            const Row(children: [
              Expanded(child: DnMetricTile(label: 'Loans repaid', value: '3', caption: 'All on time')),
              SizedBox(width: 10),
              Expanded(child: DnMetricTile(label: 'Next level', value: '₹4,000')),
            ]),
            _section('Feedback'),
            const DnBanner(title: 'Pay manually by 19 Oct', body: 'Autopay is not available yet.', tone: DnBannerTone.warn),
            const SizedBox(height: 8),
            const DnBanner(title: 'Money on the way', body: 'Your bank should show it within a few hours.', tone: DnBannerTone.ok),
            const SizedBox(height: 8),
            DnBanner(title: 'You are offline', body: 'Showing your last saved details.', tone: DnBannerTone.dark, action: 'Retry', onAction: () {}),
            const SizedBox(height: 12),
            Wrap(spacing: 8, runSpacing: 8, children: [
              DnButton.outline('Sheet', size: DnButtonSize.sm, onPressed: () => showDnSheet<void>(context, builder: (c) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    Text('Leave this step?', style: DnText.section.copyWith(fontSize: 17)),
                    const SizedBox(height: 6),
                    const Text('What you entered on this screen is saved.', style: DnText.body),
                    const SizedBox(height: 16),
                    DnButton('Stay', block: true, onPressed: () => Navigator.pop(c)),
                    const SizedBox(height: 8),
                    DnButton.ghost('Leave', block: true, onPressed: () => Navigator.pop(c)),
                  ]))),
              DnButton.outline('Dialog', size: DnButtonSize.sm, onPressed: () => showDnConfirm(context, title: 'Log out of all devices?', body: 'You will need to log in again on each phone.', confirm: 'Log out', destructive: true)),
              DnButton.outline('Toast', size: DnButtonSize.sm, onPressed: () => showDnToast(context, 'UPI ID copied')),
            ]),
            const SizedBox(height: 12),
            DnPanel(child: DnStateView(icon: Icons.wifi_off_rounded, title: 'Something went wrong', body: 'We could not load your loan. Try again, or call support.', error: true, primary: 'Try again', onPrimary: () {}, secondary: 'Call support', onSecondary: () {})),
            const SizedBox(height: 12),
            const DnPanel(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [DnSkeleton(width: 120), SizedBox(height: 10), DnSkeleton(height: 40, radius: 12), SizedBox(height: 10), DnSkeleton(width: 200)])),
            _section('Tabs and pager'),
            DnUnderlineTabs(labels: const ['Active', 'Closed'], counts: const [1, 3], index: _tab, onTap: (i) => setState(() => _tab = i)),
            const SizedBox(height: 16),
            Center(child: DnPagerDots(count: 3, index: _tab.clamp(0, 2))),
          ],
        ),
      ),
      bottomNavigationBar: DnBottomNav(
        items: const [DnNavItem('Home', Icons.home_outlined), DnNavItem('Loans', Icons.receipt_long_outlined), DnNavItem('Help', Icons.support_agent_outlined), DnNavItem('Profile', Icons.person_outline_rounded)],
        index: _nav,
        onTap: (i) => setState(() => _nav = i),
      ),
    );
  }

  Widget _section(String t) => Padding(padding: const EdgeInsets.fromLTRB(4, 28, 4, 10), child: DnCaps(t));
}
