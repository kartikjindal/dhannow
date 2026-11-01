import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/format.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'common.dart';

/// Board D2 Settings.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  // ponytail: local state in the demo; becomes a preferences endpoint (switch flips back on a failed save).
  bool _email = true, _sms = true, _offers = false, _biometric = true;

  Widget _switch(String title, String detail, bool value, ValueChanged<bool> set, {bool first = false}) => DnListRow(
        first: first,
        title: title,
        detail: detail,
        onTap: () => setState(() => set(!value)),
        trailing: Padding(
          padding: const EdgeInsets.only(left: 10),
          child: DnSwitch(label: title, value: value, onChanged: (v) => setState(() => set(v))),
        ),
      );

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Account settings',
        subtitle: 'Manage your preferences for this app.',
        back: true,
        children: [
          Section('Notifications', children: [
            _switch('Email notifications', 'Payment reminders and status updates by email.', _email, (v) => _email = v, first: true),
            _switch('SMS & WhatsApp notifications', 'Due-date and repayment alerts by text message and WhatsApp.', _sms, (v) => _sms = v),
            _switch('Product offers', 'Occasional pre-approved offers and product news.', _offers, (v) => _offers = v),
          ]),
          Section('Device preferences', children: [
            _switch('Biometric unlock', 'Use your fingerprint or face to open the app.', _biometric, (v) => _biometric = v, first: true),
          ]),
          Section('Security', children: [
            DnListRow(first: true, icon: Icons.key_rounded, title: 'Reset your password', detail: 'We’ll email you a secure reset link.', onTap: () => context.push('/forgot-password')),
            DnListRow(icon: Icons.devices_rounded, title: 'Devices and sessions', detail: 'See where you’re signed in, log out of all devices.', onTap: () => context.push('/devices')),
            DnListRow(icon: Icons.person_remove_rounded, title: 'Delete account', detail: 'Close your ${Brand.name} account.', onTap: () => context.push('/delete-account')),
          ]),
          const Section('App permissions', children: [
            DnListRow(first: true, icon: Icons.photo_camera_rounded, title: 'Camera', detail: 'Selfie and document photos, only when you tap'),
            DnListRow(icon: Icons.location_on_rounded, title: 'Location', detail: 'Read once at the credit check to confirm your address'),
            DnListRow(icon: Icons.notifications_rounded, title: 'Notifications', detail: 'Loan status and due-date reminders'),
          ]),
          Section('Legal', children: siteLinks(context, legalPages)),
          Section('About', children: [
            const DnListRow(first: true, title: 'App version', trailing: Text(appVersion, style: DnText.body)),
            if (demoControls) DnListRow(icon: Icons.tune_rounded, title: 'Demo states', detail: 'Switch the demo server to any journey state.', onTap: () => context.push('/demo')),
          ]),
        ],
      );
}

/// Board D7 Devices and sessions.
class DevicesScreen extends StatefulWidget {
  const DevicesScreen({super.key});

  @override
  State<DevicesScreen> createState() => _DevicesScreenState();
}

class _DevicesScreenState extends State<DevicesScreen> {
  final _signedOut = <String>{};
  String? _busy; // device name, or '*' for all

  Future<void> _signOut(List<Device> devices, {bool all = false}) async {
    final ok = await showDnConfirm(
      context,
      title: all ? 'Log out of all other devices?' : 'Log out ${devices.first.name}?',
      body: 'It will need your mobile number and password to sign in again.',
      confirm: 'Log out',
      destructive: true,
      icon: Icons.logout_rounded,
    );
    if (!ok || !mounted) return;
    setState(() => _busy = all ? '*' : devices.first.name);
    try {
      for (final d in devices) {
        await api.signOutDevice(d);
        if (mounted) setState(() => _signedOut.add(d.name));
      }
      if (mounted) showDnToast(context, all ? 'Other devices logged out' : 'Device logged out');
    } on ApiException catch (e) {
      if (mounted) showDnToast(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busy = null);
    }
  }

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Devices and sessions',
        subtitle: 'Phones and browsers signed in to your ${Brand.name} account.',
        back: true,
        children: [
          Loaded<List<Device>>(
            load: api.devices,
            builder: (context, all) {
              final list = all.where((d) => !_signedOut.contains(d.name)).toList();
              final others = list.where((d) => !d.current).toList();
              return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                Section(null, children: [
                  for (final (i, d) in list.indexed)
                    // Action under the text, not trailing, so it fits at 320dp × 2.0.
                    DecoratedBox(
                      decoration: BoxDecoration(border: i == 0 ? null : const Border(top: BorderSide(color: DnTokens.line))),
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(16, 13, 16, 13),
                        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
                          Icon(d.current ? Icons.smartphone_rounded : Icons.devices_other_rounded, color: DnTokens.ink),
                          const SizedBox(width: 12),
                          Expanded(
                            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                              Text(d.name, style: DnText.strong),
                              Text(d.current ? 'Active now · ${d.where}' : 'Last active ${dayMonth(d.lastActive)}, ${d.where}', style: DnText.hint),
                              const SizedBox(height: 8),
                              d.current
                                  ? const DnPill('This device', tone: DnTone.cobalt)
                                  : DnButton.outline('Log out', size: DnButtonSize.xs, loading: _busy == d.name, onPressed: _busy == null ? () => _signOut([d]) : null),
                            ]),
                          ),
                        ]),
                      ),
                    ),
                ]),
                if (others.isNotEmpty) ...[
                  DnButton.danger('Log out of all devices', block: true, loading: _busy == '*', onPressed: _busy == null ? () => _signOut(others, all: true) : null),
                  const SizedBox(height: 16),
                ],
                const Text('We send an SMS whenever a new phone signs in. Don’t recognise one? Log it out and call us.', style: DnText.hint),
                Align(alignment: Alignment.centerLeft, child: DnButton.text('Contact support', onPressed: () => context.push('/support'))),
              ]);
            },
          ),
        ],
      );
}

/// Board D8 Delete account. The server decides: `LOAN_OPEN` shows the blocked state.
class DeleteAccountScreen extends StatefulWidget {
  const DeleteAccountScreen({super.key});

  @override
  State<DeleteAccountScreen> createState() => _DeleteAccountScreenState();
}

class _DeleteAccountScreenState extends State<DeleteAccountScreen> {
  bool _busy = false, _blocked = false, _done = false;

  Future<void> _delete() async {
    final ok = await showDnConfirm(
      context,
      title: 'Delete your account?',
      body: 'You can’t undo this. You won’t be able to log in or apply again with this account.',
      confirm: 'Delete my account',
      cancel: 'Keep my account',
      destructive: true,
      icon: Icons.person_remove_rounded,
    );
    if (!ok || !mounted) return;
    setState(() => _busy = true);
    try {
      await api.requestDeletion();
      await api.logout();
      if (mounted) setState(() => _done = true);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'LOAN_OPEN') {
        setState(() => _blocked = true);
      } else {
        showDnToast(context, e.message, error: true);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String get _blockedBody {
    final l = api.loan;
    return l == null
        ? 'Repay your open loan first, then come back here.'
        : 'Loan #${l.applicationNo} is active. Repay ${inr(l.outstandingPaise)} first, then come back here.';
  }

  Widget _point(bool ok, String text) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Icon(ok ? Icons.check_circle_rounded : Icons.error_rounded, size: 20, color: ok ? DnTokens.ok : DnTokens.warn),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: DnText.body.copyWith(color: DnTokens.ink))),
        ]),
      );

  @override
  Widget build(BuildContext context) {
    if (_done) {
      return DnPage(title: 'Delete account', children: [
        DnStateView(
          icon: Icons.check_rounded,
          title: 'Request received',
          body: 'We’ll confirm by SMS within 7 days. All devices are logged out.',
          primary: 'Done',
          onPrimary: () => context.go('/welcome'),
        ),
      ]);
    }
    return DnPage(
      title: 'Delete account',
      back: true,
      bottom: Column(mainAxisSize: MainAxisSize.min, children: [
        DnButton.danger('Delete my account', block: true, loading: _busy, onPressed: _blocked ? null : _delete),
        const SizedBox(height: 8),
        DnButton.ghost('Keep my account', block: true, onPressed: () => context.canPop() ? context.pop() : context.go('/profile')),
      ]),
      children: [
        const Text('Delete your account', style: DnText.section),
        const SizedBox(height: 4),
        const Text('You can ask us to close your ${Brand.name} account. Here’s what happens.', style: DnText.body),
        const SizedBox(height: 14),
        DnPanel(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: Column(children: [
            _point(true, 'You can’t log in or apply again with this account.'),
            _point(true, 'Marketing data and preferences are erased.'),
            _point(false, 'KYC and loan records are kept for the period the law requires, then deleted.'),
          ]),
        ),
        const SizedBox(height: 16),
        if (_blocked) ...[
          DnBanner(
            tone: DnBannerTone.warn,
            icon: Icons.lock_outline_rounded,
            title: 'Not available while a loan is open',
            body: _blockedBody,
            action: 'Repay now',
            onAction: () => context.go('/repay'),
          ),
          const SizedBox(height: 16),
        ],
        Section(null, children: siteLinks(context, const [('What we keep and for how long', 'privacy.html#retention')])),
      ],
    );
  }
}
