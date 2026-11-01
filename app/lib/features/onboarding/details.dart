import 'dart:async';

import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/links.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'common.dart';

/// The version of `dhannow.com/terms.html` (its `dn-doc-version`) recorded with the consent.
const termsVersion = 'terms@v1';

/// Board A1: PAN, mobile, Terms (A1b sheet) and the PEP declaration.
class YourDetailsScreen extends StatefulWidget {
  const YourDetailsScreen({super.key});

  @override
  State<YourDetailsScreen> createState() => _YourDetailsScreenState();
}

class _YourDetailsScreenState extends State<YourDetailsScreen> with Busy {
  final _pan = TextEditingController(text: saved('start')['pan']);
  final _mobile = TextEditingController(text: saved('start')['mobile']);
  late bool _terms = saved('start').isNotEmpty;
  late bool _pep = _terms;
  String? _panErr, _mobileErr;

  @override
  void dispose() {
    _pan.dispose();
    _mobile.dispose();
    super.dispose();
  }

  /// Ticking opens the Terms sheet; it only ticks on Agree.
  Future<void> _toggleTerms(bool on) async {
    if (!on) return setState(() => _terms = false);
    final agreed = await showTermsSheet(context);
    if (mounted && agreed) setState(() => _terms = true);
  }

  void _continue() {
    setState(() {
      _panErr = panError(_pan.text);
      _mobileErr = mobileError(_mobile.text);
    });
    if (_panErr != null || _mobileErr != null) return;
    run(() async {
      await saveStep('start', {'pan': _pan.text.toUpperCase(), 'mobile': _mobile.text, 'termsVersion': termsVersion, 'notPep': 'true'});
      await api.sendOtp(_mobile.text);
      if (mounted) context.push('/signup/otp');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Your details',
    subtitle: 'Let’s start with your PAN and mobile number. It takes about two minutes.',
    step: 1,
    of: signupOf,
    trailing: const ExitLink(signedOut: true),
    onBack: () => context.canPop() ? context.pop() : context.go('/welcome'),
    bottom: StepActions(
      primary: DnButton('Continue', block: true, loading: busy, onPressed: _terms && _pep ? _continue : null),
    ),
    children: [
      DnField(
        label: 'PAN',
        controller: _pan,
        hint: 'ABCDE1234F',
        error: _panErr,
        textCapitalization: TextCapitalization.characters,
        inputFormatters: upperCode(10),
        textInputAction: TextInputAction.next,
      ),
      const SizedBox(height: 16),
      DnField(
        label: 'Mobile number',
        controller: _mobile,
        prefix: '+91',
        hint: '9876543210',
        error: _mobileErr,
        helper: 'We’ll send a verification code to this number.',
        keyboardType: TextInputType.phone,
        autofillHints: const [AutofillHints.telephoneNumberNational],
        inputFormatters: digits(10),
      ),
      const SizedBox(height: 16),
      DnCheck(
        value: _terms,
        onChanged: _toggleTerms,
        label: const TextSpan(
          children: [
            TextSpan(text: 'I agree to the '),
            TextSpan(
              text: 'Terms & Conditions',
              style: TextStyle(color: DnTokens.primary600, fontWeight: FontWeight.w600),
            ),
          ],
        ),
      ),
      DnCheck(
        value: _pep,
        onChanged: (v) => setState(() => _pep = v),
        label: const TextSpan(text: 'I confirm that I am not a Politically Exposed Person, nor related to one.'),
      ),
    ],
  );
}

/// Board A1b. Resolves true on Agree.
Future<bool> showTermsSheet(BuildContext context) async => await showDnSheet<bool>(context, builder: (_) => const _TermsSheet()) ?? false;

class _TermsSheet extends StatefulWidget {
  const _TermsSheet();

  @override
  State<_TermsSheet> createState() => _TermsSheetState();
}

class _TermsSheetState extends State<_TermsSheet> {
  // ponytail: demo stand-in for loading the terms document; the real app fetches the page text.
  late final Future<String> _text = Future.delayed(demoControls ? demo.latency : Duration.zero, () => _demoTerms);

  @override
  Widget build(BuildContext context) => ConstrainedBox(
    constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * .8),
    child: SingleChildScrollView(
      child: FutureBuilder<String>(
        future: _text,
        builder: (context, snap) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('DhanNow borrower agreement', style: DnText.hint),
            Text('Terms & Conditions', style: DnText.section.copyWith(fontSize: 20)),
            const Text('Review the agreement before recording your consent.', style: DnText.hint),
            const SizedBox(height: 12),
            DnPanel(
              padding: const EdgeInsets.all(14),
              child: SizedBox(
                height: 220,
                child: SingleChildScrollView(child: Text(snap.data ?? 'Loading…', style: DnText.body.copyWith(fontSize: 13))),
              ),
            ),
            const SizedBox(height: 12),
            const WebLinks([
              ('Read the full Terms on our website', '${Brand.site}/terms.html'),
            ], note: 'Version $termsVersion is saved with your consent when you tap Agree.'),
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(child: DnButton.outline('Decline', onPressed: () => Navigator.pop(context, false))),
                const SizedBox(width: 10),
                Expanded(child: DnButton('Agree', onPressed: snap.hasData ? () => Navigator.pop(context, true) : null)),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}

const _demoTerms =
    'These Terms & Conditions govern access to and use of the DhanNow website, digital platform and associated services.\n\n'
    'By submitting an application or using any available service, you acknowledge that you have read and understood the applicable terms and agree to comply with them. '
    'If you do not agree, please do not continue.\n\n'
    'These terms should be read together with the Privacy Policy, Loan Information & Repayment Policy, Customer Consent Information, Fair Practices Code and Grievance Redressal Policy.\n\n'
    'They do not replace the loan agreement, Key Fact Statement (KFS) or other documents for an individual loan.';

/// Links to dhannow.com pages. ponytail: no browser plugin yet, so a tap copies the link.
class WebLinks extends StatelessWidget {
  const WebLinks(this.links, {super.key, this.note});
  final List<(String title, String url)> links;
  final String? note;

  @override
  Widget build(BuildContext context) => DnPanel(
    padding: const EdgeInsets.only(bottom: 10),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        for (final (i, (title, url)) in links.indexed)
          DnListRow(
            first: i == 0,
            title: title,
            detail: url,
            trailing: const Icon(Icons.open_in_new_rounded, size: 18, color: DnTokens.slate),
            onTap: () => openUrl(context, 'https://$url'),
          ),
        if (note != null)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 2, 16, 0),
            child: Text(note!, style: DnText.hint.copyWith(fontSize: 11)),
          ),
      ],
    ),
  );
}

/// Board A2. The code fills from SMS; a wrong code shakes the boxes; too many tries opens E7.
class VerifyMobileScreen extends StatefulWidget {
  const VerifyMobileScreen({super.key});

  @override
  State<VerifyMobileScreen> createState() => _VerifyMobileScreenState();
}

class _VerifyMobileScreenState extends State<VerifyMobileScreen> with Busy {
  final _code = TextEditingController();
  final _mobile = saved('start')['mobile'] ?? '';
  var _state = DnOtpState.idle;
  String? _error;
  late final resend = ResendTimer(() => mounted ? setState(() {}) : null);

  @override
  void initState() {
    super.initState();
    _code.addListener(() {
      if (_state == DnOtpState.error && _code.text.isNotEmpty) {
        setState(() {
          _state = DnOtpState.idle;
          _error = null;
        });
      }
    });
    resend.start();
  }

  @override
  void dispose() {
    _code.dispose();
    resend.cancel();
    super.dispose();
  }

  void _verify() {
    if (_code.text.length < 6) return setState(() => _error = 'Enter the 6-digit code.');
    run(() async {
      try {
        await api.verifyOtp(_code.text);
      } on ApiException catch (e) {
        if (!mounted) return;
        if (e.code == 'OTP_LOCKED') {
          context.push('/otp-locked');
          return;
        }
        if (e.code != 'OTP_INVALID') rethrow;
        _code.clear();
        setState(() {
          _state = DnOtpState.error;
          _error = e.message;
        });
        return;
      }
      setState(() => _state = DnOtpState.ok);
      await saveStep('otp', const {});
      if (mounted) context.push('/signup/set-password');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Verify your mobile',
    step: 2,
    of: signupOf,
    trailing: const ExitLink(signedOut: true),
    bottom: StepActions(
      primary: DnButton('Verify', block: true, loading: busy, onPressed: _verify),
    ),
    children: [
      const Text('Enter the 6-digit code', style: DnText.section),
      const SizedBox(height: 4),
      Text.rich(
        TextSpan(
          text: 'Sent to ',
          children: [TextSpan(text: _mobile.isEmpty ? 'your mobile' : _mobile, style: DnText.strong)],
        ),
        style: DnText.body,
      ),
      const SizedBox(height: 18),
      DnOtpField(controller: _code, state: _state, enabled: !busy, onCompleted: (_) => _verify()),
      if (_error != null) ...[const SizedBox(height: 8), Text(_error!, style: const TextStyle(fontSize: 12.5, color: DnTokens.err))],
      const SizedBox(height: 14),
      resend.view(
        onResend: () => run(() async {
          await api.sendOtp(_mobile);
          resend.used();
          if (mounted) showDnToast(this.context, 'Code sent');
        }),
      ),
    ],
  );
}

/// "Resend in 0:27" → "Code sent. Resend (3 left)" → after 3 resends, contact support.
class ResendTimer {
  ResendTimer(this.onTick);
  final VoidCallback onTick;
  int left = 3;
  int wait = 0;
  Timer? _t;

  void start() {
    wait = 30;
    _t?.cancel();
    _t = Timer.periodic(const Duration(seconds: 1), (t) {
      if (--wait <= 0) t.cancel();
      onTick();
    });
  }

  void used() {
    left--;
    start();
  }

  void cancel() => _t?.cancel();

  Widget view({required VoidCallback onResend, String sent = 'Code sent.'}) {
    if (left <= 0) return Text('No resends left. Please contact support on ${api.config.support.phone}.', style: DnText.body);
    if (wait > 0) return Text('$sent Resend in 0:${wait.toString().padLeft(2, '0')}', style: DnText.body);
    return Wrap(
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        Text('$sent ', style: DnText.body),
        DnButton.text('Resend ($left left)', onPressed: onResend),
      ],
    );
  }
}

/// Board A3. Optional: Skip for now moves on without one.
class SetPasswordScreen extends StatefulWidget {
  const SetPasswordScreen({super.key});

  @override
  State<SetPasswordScreen> createState() => _SetPasswordScreenState();
}

class _SetPasswordScreenState extends State<SetPasswordScreen> with Busy {
  final _pw = TextEditingController();
  final _again = TextEditingController();
  String? _pwErr, _againErr;

  @override
  void dispose() {
    _pw.dispose();
    _again.dispose();
    super.dispose();
  }

  void _set() {
    setState(() {
      _pwErr = passwordError(_pw.text);
      _againErr = _pwErr == null && _again.text != _pw.text ? 'Passwords don’t match.' : null;
    });
    if (_pwErr != null || _againErr != null) return;
    run(() async {
      await api.setPassword(_pw.text);
      await saveStep('set-password', const {'password': 'set'});
      if (mounted) context.push('/signup/employment');
    });
  }

  void _skip() => run(() async {
    await saveStep('set-password', const {'password': 'skipped'});
    if (mounted) context.push('/signup/employment');
  });

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Set a password',
    subtitle: 'Add a password so you can sign in without an OTP next time. This is optional — you can skip it now and add one later from your profile.',
    step: 3,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      trust: false,
      primary: DnButton('Set password & continue', block: true, loading: busy, onPressed: _set),
      secondary: DnButton.ghost('Skip for now', block: true, onPressed: busy ? null : _skip),
    ),
    children: [
      DnField(
        label: 'Password',
        controller: _pw,
        obscure: true,
        error: _pwErr,
        helper: '6–10 characters with a letter, a digit and a special character',
        autofillHints: const [AutofillHints.newPassword],
      ),
      const SizedBox(height: 16),
      DnField(label: 'Confirm password', controller: _again, obscure: true, hint: 'Re-enter your password', error: _againErr),
    ],
  );
}
