import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'common.dart';

/// Board A12: the application is with the credit team; optional address proof and UAN.
class SubmittedScreen extends StatefulWidget {
  const SubmittedScreen({super.key});

  @override
  State<SubmittedScreen> createState() => _SubmittedScreenState();
}

class _SubmittedScreenState extends State<SubmittedScreen> with Busy {
  static const _proofs = ['Electricity bill', 'Gas bill', 'Rent agreement'];
  final _files = <String, PickedFile>{};
  final _uan = TextEditingController();
  String? _uanErr;

  @override
  void dispose() {
    _uan.dispose();
    super.dispose();
  }

  void _save() {
    setState(() => _uanErr = _uan.text.isEmpty || _uan.text.length == 12 ? null : 'Enter all 12 digits of your UAN, or leave it empty.');
    if (_uanErr != null) return;
    run(() async {
      await api.saveStep('address-proof', {for (final MapEntry(:key, :value) in _files.entries) key: value.name, if (_uan.text.isNotEmpty) 'uan': _uan.text}, files: _files);
      if (mounted) showDnToast(context, 'Saved');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'With our credit team',
    step: 12,
    of: signupOf,
    confirmBack: false,
    onBack: () => context.go('/dashboard'),
    bottom: StepActions(
      trust: false,
      primary: DnButton('Go to dashboard', block: true, onPressed: () => context.go('/dashboard')),
      secondary: DnButton.ghost('Track my application', block: true, onPressed: () => context.push('/loan/status')),
    ),
    children: [
      const DnPanel(
        child: Column(
          children: [
            _Disc(Icons.hourglass_top_rounded),
            SizedBox(height: 12),
            Text('Your application is with our credit team', textAlign: TextAlign.center, style: DnText.section),
            SizedBox(height: 4),
            Text('We’ll be in touch as soon as it’s reviewed. You can close this page — we’ll notify you.', textAlign: TextAlign.center, style: DnText.body),
            SizedBox(height: 18),
            DnTimeline(
              steps: [
                DnStep('Applied', detail: 'Details and documents received', state: DnStepState.done),
                DnStep('With our credit team', detail: 'We’ll notify you when it’s reviewed', state: DnStepState.now),
                DnStep('Decision', detail: 'Your credit level and amount'),
              ],
            ),
          ],
        ),
      ),
      const SizedBox(height: 14),
      DnPanel(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const DnPanelHeader(
              title: 'Help us process your loan faster',
              meta: 'Optional — adding an address proof can speed up the review. Sit back comfortably; we’ll do the rest.',
            ),
            for (final p in _proofs) ...[
              UploadTile(
                title: p,
                file: _files[p],
                onPick: (photo) async {
                  final f = await pickFile(context, photo ? PickFrom.camera : PickFrom.files, name: p.replaceAll(' ', '_'));
                  if (f != null) setState(() => _files[p] = f);
                },
                onRemove: () => setState(() => _files.remove(p)),
              ),
              const SizedBox(height: 10),
            ],
            const SizedBox(height: 6),
            DnField(
              label: 'EPFO UAN (optional)',
              controller: _uan,
              hint: '12-digit number, printed on your payslip',
              error: _uanErr,
              helper: 'Sharing your Universal Account Number helps us confirm your employment faster. Skipping it changes nothing.',
              keyboardType: TextInputType.number,
              inputFormatters: digits(12),
            ),
            const SizedBox(height: 12),
            Align(
              alignment: Alignment.centerLeft,
              child: DnButton.outline('Save', size: DnButtonSize.sm, loading: busy, onPressed: _save),
            ),
          ],
        ),
      ),
      const SizedBox(height: 14),
      Text.rich(
        TextSpan(
          text: 'Questions? Call us at ',
          children: [TextSpan(text: api.config.support.phone, style: DnText.strong)],
        ),
        textAlign: TextAlign.center,
        style: DnText.body,
      ),
    ],
  );
}

/// Board A13: the bureau's identity question.
class CreditQuestionScreen extends StatefulWidget {
  const CreditQuestionScreen({super.key});

  @override
  State<CreditQuestionScreen> createState() => _CreditQuestionScreenState();
}

class _CreditQuestionScreenState extends State<CreditQuestionScreen> with Busy {
  ({String question, List<String> options})? _q;
  String? _pick;
  String? _done;

  bool get _loaded => _q != null;

  void _answer(String? answer, String endState) => run(() async {
    await api.answerQuestion(answer);
    if (mounted) setState(() => _done = endState);
  });

  @override
  Widget build(BuildContext context) {
    if (_done != null) {
      return _ResultPage(
        icon: Icons.check_rounded,
        title: _done!,
        body: 'Your application is back with our credit team.',
        actions: [DnButton('Application status', block: true, onPressed: () => context.go('/signup/submitted'))],
      );
    }
    return DnPage(
      title: 'One quick security question',
      subtitle: 'The credit bureau needs to confirm it’s really you before releasing your report. Only you know the answer.',
      bottom: !_loaded
          ? DnButton(
              'Get my question',
              block: true,
              loading: busy,
              onPressed: () => run(() async {
                final q = await api.creditQuestion();
                if (!mounted) return;
                // No question waiting (answered elsewhere, or closed by the bureau): the team has it.
                q == null ? setState(() => _done = 'Thanks — we’ll take it from here') : setState(() => _q = q);
              }),
            )
          : Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                DnButton('Confirm answer', block: true, loading: busy, onPressed: _pick == null ? null : () => _answer(_pick!, 'All done — thank you')),
                const SizedBox(height: 4),
                DnButton.ghost(
                  'I don’t recognise any of these',
                  block: true,
                  onPressed: busy ? null : () => _answer(null, 'Thanks — we’ll take it from here'),
                ),
              ],
            ),
      children: [
        if (_loaded)
          DnPanel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text(_q!.question, style: DnText.section),
                const SizedBox(height: 12),
                for (final o in _q!.options) ...[DnChoiceCard(title: o, selected: _pick == o, onTap: () => setState(() => _pick = o)), const SizedBox(height: 8)],
              ],
            ),
          ),
      ],
    );
  }
}

/// Board A14.
class RejectedScreen extends StatelessWidget {
  const RejectedScreen({super.key});

  @override
  Widget build(BuildContext context) => _ResultPage(
    icon: Icons.block_rounded,
    neutral: true,
    title: 'Your application has been rejected',
    body:
        'Thank you for your interest in ${Brand.name}. We’re unable to take your application forward. If you believe this is a mistake, please contact our support team.',
    actions: [
      DnButton.outline('Contact support', block: true, onPressed: () => context.push('/support')),
      const SizedBox(height: 8),
      DnButton('Back to home', block: true, onPressed: () => context.go('/welcome')),
    ],
  );
}

/// Board E13: while applications are paused, a short form records interest instead of sign-up.
/// Only the last 4 digits of Aadhaar are asked for here.
class ApplyGateScreen extends StatefulWidget {
  const ApplyGateScreen({super.key});

  @override
  State<ApplyGateScreen> createState() => _ApplyGateScreenState();
}

class _ApplyGateScreenState extends State<ApplyGateScreen> with Busy {
  final _name = TextEditingController();
  final _mobile = TextEditingController();
  final _email = TextEditingController();
  final _pan = TextEditingController();
  final _aadhaar = TextEditingController();
  final _errs = <String, String?>{};

  @override
  void dispose() {
    for (final c in [_name, _mobile, _email, _pan, _aadhaar]) {
      c.dispose();
    }
    super.dispose();
  }

  void _submit() {
    setState(() {
      _errs
        ..['name'] = need(_name.text, 'Enter your full name.')
        ..['mobile'] = mobileError(_mobile.text)
        ..['email'] = emailError(_email.text)
        ..['pan'] = panError(_pan.text)
        ..['aadhaar'] = RegExp(r'^\d{4}$').hasMatch(_aadhaar.text) ? null : 'Enter the last 4 digits of your Aadhaar.';
    });
    if (_errs.values.any((e) => e != null)) return;
    final data = {
      'name': _name.text.trim(),
      'mobile': _mobile.text,
      'email': _email.text.trim(),
      'pan': _pan.text.toUpperCase(),
      'aadhaarLast4': _aadhaar.text,
    };
    run(() async {
      // ponytail: no gate endpoint yet; the demo records it as a step outside the sign-up list.
      await api.saveStep('apply', data);
      if (mounted) context.go('/apply/submitted', extra: data);
    });
  }

  @override
  Widget build(BuildContext context) => DnPage(
    title: 'Enter your details',
    back: true,
    bottom: DnButton('Submit', block: true, loading: busy, onPressed: _submit),
    children: [
      DnField(
        label: 'Full name',
        controller: _name,
        error: _errs['name'],
        textCapitalization: TextCapitalization.words,
        autofillHints: const [AutofillHints.name],
      ),
      const SizedBox(height: 16),
      DnField(
        label: 'Mobile number',
        controller: _mobile,
        prefix: '+91',
        error: _errs['mobile'],
        keyboardType: TextInputType.phone,
        inputFormatters: digits(10),
      ),
      const SizedBox(height: 16),
      DnField(label: 'Email', controller: _email, error: _errs['email'], keyboardType: TextInputType.emailAddress, autofillHints: const [AutofillHints.email]),
      const SizedBox(height: 16),
      DnField(
        label: 'PAN',
        controller: _pan,
        hint: 'ABCDE1234F',
        error: _errs['pan'],
        textCapitalization: TextCapitalization.characters,
        inputFormatters: upperCode(10),
      ),
      const SizedBox(height: 16),
      DnField(
        label: 'Aadhaar number (last 4 digits)',
        controller: _aadhaar,
        hint: '9012',
        error: _errs['aadhaar'],
        keyboardType: TextInputType.number,
        inputFormatters: digits(4),
      ),
    ],
  );
}

/// Board E13b. Shows what E13 sent (passed as the route's `extra`).
class ApplySubmittedScreen extends StatelessWidget {
  const ApplySubmittedScreen({super.key, this.data});
  final Map<String, String>? data;

  @override
  Widget build(BuildContext context) {
    final d = data;
    final m = d?['mobile'] ?? '';
    return _ResultPage(
      icon: Icons.mark_email_read_outlined,
      title: 'Your application is under review',
      body: 'We’ll get in touch with you on these details.',
      extra: d == null
          ? null
          : DnPanel(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: KvRows({
                'Name': d['name'] ?? '',
                'Email': d['email'] ?? '',
                'Mobile': m.length == 10 ? '${m.substring(0, 5)} ${m.substring(5)}' : m,
                'Aadhaar': '••••${d['aadhaarLast4'] ?? ''}',
              }),
            ),
      actions: [
        DnButton('Done', block: true, onPressed: () => context.go('/welcome')),
        const SizedBox(height: 4),
        DnButton.ghost('Contact support', block: true, onPressed: () => context.push('/support')),
      ],
    );
  }
}

class _Disc extends StatelessWidget {
  const _Disc(this.icon, {this.neutral = false});
  final IconData icon;
  final bool neutral;

  @override
  Widget build(BuildContext context) => Container(
    width: 64,
    height: 64,
    decoration: BoxDecoration(color: neutral ? DnTokens.grey200 : DnTokens.tint, shape: BoxShape.circle),
    child: Icon(icon, size: 28, color: neutral ? DnTokens.ink : DnTokens.primary600),
  );
}

/// Outcome page (A13 end states, A14, E13b): icon disc, Bebas title, body, actions at the bottom.
class _ResultPage extends StatelessWidget {
  const _ResultPage({required this.icon, required this.title, required this.body, required this.actions, this.extra, this.neutral = false});

  final IconData icon;
  final String title;
  final String body;
  final List<Widget> actions;
  final Widget? extra;
  final bool neutral;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(
      child: Column(
        children: [
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 48, DnTokens.gutter, 24),
              children: [
                Align(
                  alignment: Alignment.centerLeft,
                  child: _Disc(icon, neutral: neutral),
                ),
                const SizedBox(height: 18),
                Semantics(header: true, child: Text(title, style: DnText.title)),
                const SizedBox(height: 8),
                Text(body, style: DnText.body),
                if (extra != null) ...[const SizedBox(height: 18), extra!],
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 12),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: actions),
          ),
        ],
      ),
    ),
  );
}
