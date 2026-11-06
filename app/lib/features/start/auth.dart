import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/theme/theme.dart';
import '../../core/widgets/widgets.dart';
import 'splash.dart';

final _mobileRule = RegExp(r'^[6-9]\d{9}$');
final _mobileInput = [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(10)];

String _spaced(String mobile) => '+91 ${mobile.substring(0, 5)} ${mobile.substring(5)}';

/// The server's PasswordPolicy (backend `com.navix.auth.PasswordPolicy`): 6–10 characters with a
/// letter, a digit and a special character. Null when the password passes.
/// ponytail: mirrored by hand; read it from `/api/app/config` once that endpoint exists (CC-52).
String? passwordProblem(String p) {
  final ok = p.length >= 6 && p.length <= 10 && p.contains(RegExp('[A-Za-z]')) && p.contains(RegExp(r'\d')) && p.contains(RegExp(r'[^A-Za-z0-9\s]'));
  return ok ? null : 'Password must be 6–10 characters with a letter, a digit and a special character.';
}

/// Board S4: mobile and password, then wherever the server journey says.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _mobile = TextEditingController();
  final _password = TextEditingController();
  String? _mobileError;
  String? _passwordError;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _mobile.dispose();
    _password.dispose();
    super.dispose();
  }

  Future<void> _signIn() async {
    setState(() {
      _mobileError = _mobileRule.hasMatch(_mobile.text) ? null : 'Enter your 10-digit mobile number.';
      _passwordError = _password.text.isEmpty ? 'Enter your password.' : null;
      _error = null;
    });
    if (_mobileError != null || _passwordError != null) return;
    setState(() => _busy = true);
    try {
      await api.login(_mobile.text, _password.text);
      if (mounted) await openJourney(context);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'OFFLINE') context.push('/offline');
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => DnPage(
    title: 'Welcome back',
    subtitle: 'Sign in to manage your loan or finish your application.',
    back: context.canPop(),
    bottom: DnButton('Sign in', block: true, loading: _busy, onPressed: _signIn),
    children: [
      DnField(
        label: 'Mobile number',
        prefix: '+91',
        controller: _mobile,
        error: _mobileError,
        keyboardType: TextInputType.phone,
        inputFormatters: _mobileInput,
        autofillHints: const [AutofillHints.telephoneNumberNational],
        textInputAction: TextInputAction.next,
      ),
      const SizedBox(height: 14),
      DnField(label: 'Password', controller: _password, error: _passwordError, obscure: true, autofillHints: const [AutofillHints.password], onSubmitted: (_) => _signIn()),
      Align(
        alignment: Alignment.centerRight,
        child: DnButton.text('Forgot password?', onPressed: () => context.push('/forgot-password')),
      ),
      if (_error != null) ...[const SizedBox(height: 4), DnBanner(tone: DnBannerTone.err, title: 'Couldn’t sign you in', body: _error, action: 'Try again', onAction: _busy ? null : _signIn)],
      const SizedBox(height: 16),
      Wrap(
        alignment: WrapAlignment.center,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          const Text('New to DhanNow?', style: DnText.body),
          const SizedBox(width: 6),
          DnButton.text('Apply for a loan', onPressed: () => context.go('/signup/start')),
        ],
      ),
      const SizedBox(height: 16),
      const Wrap(
        alignment: WrapAlignment.center,
        spacing: 8,
        runSpacing: 8,
        children: [
          DnPill('Secure & encrypted', tone: DnTone.line),
          DnPill('Transparent terms', tone: DnTone.line),
        ],
      ),
    ],
  );
}

/// Board S5: mobile, then the 6-digit code, then a new password (S6).
class ForgotPasswordScreen extends StatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  State<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends State<ForgotPasswordScreen> {
  final _mobile = TextEditingController();
  final _code = TextEditingController();
  bool _sent = false;
  bool _busy = false;
  String? _mobileError;
  String? _error;

  @override
  void dispose() {
    _mobile.dispose();
    _code.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() call) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await call();
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'OTP_LOCKED') return context.go('/otp-locked');
      if (e.code == 'OFFLINE') context.push('/offline');
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _send() async {
    setState(() => _mobileError = _mobileRule.hasMatch(_mobile.text) ? null : 'Enter your 10-digit mobile number.');
    if (_mobileError != null) return;
    await _run(() async {
      await api.sendOtp(_mobile.text, purpose: OtpFor.reset);
      if (!mounted) return;
      if (_sent) showDnToast(context, 'Code sent');
      _code.clear();
      setState(() => _sent = true);
    });
  }

  Future<void> _verify() => _run(() async {
    await api.verifyOtp(_code.text, purpose: OtpFor.reset);
    if (mounted) context.go('/reset-password');
  });

  @override
  Widget build(BuildContext context) => DnPage(
    title: 'Reset your password',
    subtitle: _sent ? 'Enter the 6-digit code sent to ${_spaced(_mobile.text)}.' : 'Enter the mobile number on your account and we’ll send you a code.',
    back: context.canPop(),
    bottom: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        _sent
            ? ListenableBuilder(
                listenable: _code,
                builder: (context, _) => DnButton('Continue', block: true, loading: _busy, onPressed: _code.text.length == 6 ? _verify : null),
              )
            : DnButton('Send code', block: true, loading: _busy, onPressed: _send),
        DnButton.text('Back to sign in', onPressed: () => context.go('/login')),
      ],
    ),
    children: [
      if (!_sent)
        DnField(label: 'Mobile number', prefix: '+91', controller: _mobile, error: _mobileError, keyboardType: TextInputType.phone, inputFormatters: _mobileInput, onSubmitted: (_) => _send())
      else ...[
        DnOtpField(controller: _code, state: _error == null ? DnOtpState.idle : DnOtpState.error, enabled: !_busy, onCompleted: (_) => _verify()),
        const SizedBox(height: 8),
        Wrap(
          spacing: 12,
          children: [
            DnButton.text('Change number', onPressed: _busy ? null : () => setState(() => _sent = false)),
            DnButton.text('Resend code', onPressed: _busy ? null : _send),
          ],
        ),
      ],
      if (_error != null) ...[const SizedBox(height: 8), DnBanner(tone: DnBannerTone.err, title: _error!, action: 'Try again', onAction: _busy ? null : (_sent ? _verify : _send))],
    ],
  );
}

/// Board S6: new password under the server's rule; done goes back to sign in.
class ResetPasswordScreen extends StatefulWidget {
  const ResetPasswordScreen({super.key});

  @override
  State<ResetPasswordScreen> createState() => _ResetPasswordScreenState();
}

class _ResetPasswordScreenState extends State<ResetPasswordScreen> {
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  String? _passwordError;
  String? _confirmError;
  String? _error;
  bool _busy = false;

  @override
  void dispose() {
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _update() async {
    setState(() {
      _passwordError = passwordProblem(_password.text);
      _confirmError = _passwordError == null && _confirm.text != _password.text ? 'Passwords don’t match.' : null;
      _error = null;
    });
    if (_passwordError != null || _confirmError != null) return;
    setState(() => _busy = true);
    try {
      await api.resetPassword(_password.text);
      if (!mounted) return;
      showDnToast(context, 'Your password has been updated.');
      await openJourney(context); // the reset signs the borrower in
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'OFFLINE') context.push('/offline');
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => DnPage(
    title: 'Set a new password',
    subtitle: 'Choose a password of 6–10 characters with a letter, a digit and a special character.',
    bottom: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        DnButton('Update password', block: true, loading: _busy, onPressed: _update),
        DnButton.text('Back to sign in', onPressed: () => context.go('/login')),
      ],
    ),
    children: [
      DnField(label: 'New password', controller: _password, error: _passwordError, obscure: true, autofillHints: const [AutofillHints.newPassword], textInputAction: TextInputAction.next),
      const SizedBox(height: 14),
      DnField(label: 'Confirm new password', controller: _confirm, error: _confirmError, obscure: true, autofillHints: const [AutofillHints.newPassword], onSubmitted: (_) => _update()),
      if (_error != null) ...[
        const SizedBox(height: 12),
        DnBanner(tone: DnBannerTone.err, title: 'Couldn’t update your password', body: _error, action: 'Try again', onAction: _busy ? null : _update),
      ],
    ],
  );
}
