import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/format.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'common.dart';
import 'details.dart' show ResendTimer;

/// Board A4: salaried goes to A5, self-employed to A5b.
class EmploymentScreen extends StatefulWidget {
  const EmploymentScreen({super.key});

  @override
  State<EmploymentScreen> createState() => _EmploymentScreenState();
}

class _EmploymentScreenState extends State<EmploymentScreen> with Busy {
  String? _type = saved('employment')['type'];
  String? _err;

  void _continue() {
    final t = _type;
    if (t == null) return setState(() => _err = 'Select your employment status');
    run(() async {
      await saveStep('employment', {'type': t});
      if (mounted) context.push(t == 'salaried' ? '/signup/employer' : '/signup/self-employed');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Employment status',
    subtitle: 'What is your employment status?',
    step: 4,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Continue', block: true, loading: busy, onPressed: _continue),
    ),
    children: [
      for (final (key, title, icon) in const [
        ('salaried', 'Salaried', Icons.badge_outlined),
        ('self-employed', 'Self-employed', Icons.storefront_outlined),
      ]) ...[
        DnChoiceCard(
          title: title,
          icon: icon,
          selected: _type == key,
          onTap: () => setState(() {
            _type = key;
            _err = null;
          }),
        ),
        const SizedBox(height: 10),
      ],
      if (_err != null) Text(_err!, style: const TextStyle(fontSize: 12.5, color: DnTokens.err)),
    ],
  );
}

/// Board A5. The board's "previous salary date" field is left out: the due date is always day 14
/// from disbursal, never tied to a salary day.
class EmployerScreen extends StatefulWidget {
  const EmployerScreen({super.key});

  @override
  State<EmployerScreen> createState() => _EmployerScreenState();
}

class _EmployerScreenState extends State<EmployerScreen> with Busy {
  final _company = TextEditingController(text: saved('employer')['company']);
  final _salary = TextEditingController(text: saved('employer')['salary']);
  String? _companyErr, _salaryErr;

  @override
  void dispose() {
    _company.dispose();
    _salary.dispose();
    super.dispose();
  }

  void _continue() {
    setState(() {
      _companyErr = need(_company.text, 'Enter your company name.');
      _salaryErr = (int.tryParse(_salary.text) ?? 0) > 0 ? null : 'Enter your monthly in-hand salary.';
    });
    if (_companyErr != null || _salaryErr != null) return;
    run(() async {
      await saveStep('employer', {'type': 'salaried', 'company': _company.text.trim(), 'salary': _salary.text});
      if (mounted) context.push('/signup/email');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Employer & salary',
    subtitle: 'Tell us where you work and what you earn.',
    step: 5,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Continue', block: true, loading: busy, onPressed: _continue),
    ),
    children: [
      DnField(label: 'Company name', controller: _company, hint: 'Infosys Limited', error: _companyErr, textCapitalization: TextCapitalization.words),
      const SizedBox(height: 16),
      DnField(
        label: 'Monthly salary (in-hand)',
        controller: _salary,
        prefix: '₹',
        hint: '45000',
        error: _salaryErr,
        helper: 'The amount credited to your bank each month.',
        keyboardType: TextInputType.number,
        inputFormatters: digits(8),
      ),
    ],
  );
}

/// Board A5b. v1 lends to salaried borrowers only, so Submit records the details and opens E11.
class SelfEmployedScreen extends StatefulWidget {
  const SelfEmployedScreen({super.key});

  @override
  State<SelfEmployedScreen> createState() => _SelfEmployedScreenState();
}

class _SelfEmployedScreenState extends State<SelfEmployedScreen> with Busy {
  final _name = TextEditingController();
  final _income = TextEditingController();
  DateTime? _dob;
  String? _nameErr, _dobErr, _incomeErr;

  @override
  void dispose() {
    _name.dispose();
    _income.dispose();
    super.dispose();
  }

  void _submit() {
    setState(() {
      _nameErr = need(_name.text, 'Enter your full name.');
      _dobErr = _dob == null ? 'Pick your date of birth.' : null;
      _incomeErr = (int.tryParse(_income.text) ?? 0) > 0 ? null : 'Enter your annual income.';
    });
    if (_nameErr != null || _dobErr != null || _incomeErr != null) return;
    run(() async {
      await saveStep('employer', {'type': 'self-employed', 'name': _name.text.trim(), 'dob': isoDate(_dob!), 'annualIncome': _income.text});
      if (mounted) context.go('/not-eligible');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Employer & salary',
    subtitle: 'A few more details and we’ll review your application.',
    step: 5,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Submit', block: true, loading: busy, onPressed: _submit),
    ),
    children: [
      DnField(
        label: 'Full name',
        controller: _name,
        hint: 'Aarav Sharma',
        error: _nameErr,
        textCapitalization: TextCapitalization.words,
        autofillHints: const [AutofillHints.name],
      ),
      const SizedBox(height: 16),
      DateField(
        label: 'Date of birth',
        value: _dob,
        error: _dobErr,
        onPicked: (d) => setState(() {
          _dob = d;
          _dobErr = null;
        }),
      ),
      const SizedBox(height: 16),
      DnField(
        label: 'Annual income',
        controller: _income,
        prefix: '₹',
        hint: '600000',
        error: _incomeErr,
        keyboardType: TextInputType.number,
        inputFormatters: digits(9),
      ),
    ],
  );
}

enum EmailStatus { none, sent, verified, undeliverable }

/// Board A6: personal email must verify; the work email verifies or is marked undeliverable.
class EmailsScreen extends StatefulWidget {
  const EmailsScreen({super.key});

  @override
  State<EmailsScreen> createState() => _EmailsScreenState();
}

class _EmailsScreenState extends State<EmailsScreen> with Busy {
  late var _personal = (saved('email')['personal'] ?? '', saved('email').isEmpty ? EmailStatus.none : EmailStatus.verified);
  late var _work = (
    saved('email')['work'] ?? '',
    saved('email').isEmpty ? EmailStatus.none : EmailStatus.values.byName(saved('email')['workStatus'] ?? 'verified'),
  );

  bool get _ready => _personal.$2 == EmailStatus.verified && (_work.$2 == EmailStatus.verified || _work.$2 == EmailStatus.undeliverable);

  void _continue() => run(() async {
    await saveStep('email', {'personal': _personal.$1, 'work': _work.$1, 'workStatus': _work.$2.name});
    if (mounted) context.push('/signup/bank');
  });

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Email addresses',
    subtitle:
        'We send your sanction letter and statements to your personal email, and confirm your employer from your official one. '
        'We’ll email a code to each to check it reaches you.',
    step: 6,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Continue', block: true, loading: busy, onPressed: _ready ? _continue : null),
    ),
    children: [
      _EmailBlock(label: 'Personal email address', initial: _personal, onChanged: (v) => setState(() => _personal = v)),
      const SizedBox(height: 12),
      _EmailBlock(
        label: 'Official work email address',
        helper: 'Used to confirm your employer. We send a 6-digit code to this address.',
        work: true,
        initial: _work,
        onChanged: (v) => setState(() => _work = v),
      ),
    ],
  );
}

class _EmailBlock extends StatefulWidget {
  const _EmailBlock({required this.label, this.helper, this.work = false, required this.initial, required this.onChanged});

  final String label;
  final String? helper;
  final bool work;
  final (String, EmailStatus) initial;
  final ValueChanged<(String, EmailStatus)> onChanged;

  @override
  State<_EmailBlock> createState() => _EmailBlockState();
}

class _EmailBlockState extends State<_EmailBlock> with Busy {
  late final _email = TextEditingController(text: widget.initial.$1);
  final _code = TextEditingController();
  late var _status = widget.initial.$2;
  var _otp = DnOtpState.idle;
  String? _err;
  late final _resend = ResendTimer(() => mounted ? setState(() {}) : null);

  @override
  void dispose() {
    _email.dispose();
    _code.dispose();
    _resend.cancel();
    super.dispose();
  }

  void _set(EmailStatus s) {
    setState(() => _status = s);
    widget.onChanged((_email.text.trim(), s));
  }

  void _send() {
    setState(() => _err = emailError(_email.text));
    if (_err != null) return;
    run(() async {
      // A work address that can't receive mail is recorded as undeliverable (the credit team checks
      // the employer another way); a personal one must verify.
      final delivered = await api.sendEmailCode(_email.text.trim(), work: widget.work);
      if (!delivered) {
        if (widget.work) return _set(EmailStatus.undeliverable);
        throw const ApiException('EMAIL_UNDELIVERABLE');
      }
      _status == EmailStatus.sent ? _resend.used() : _resend.start();
      _set(EmailStatus.sent);
    });
  }

  void _verify(String code) => run(() async {
    try {
      await api.verifyEmailCode(code, work: widget.work);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'OTP_LOCKED') {
        context.push('/otp-locked');
        return;
      }
      if (e.code != 'OTP_INVALID') rethrow;
      _code.clear();
      setState(() {
        _otp = DnOtpState.error;
        _err = e.message;
      });
      return;
    }
    _resend.cancel();
    _set(EmailStatus.verified);
  });

  @override
  Widget build(BuildContext context) => DnPanel(
    padding: const EdgeInsets.all(16),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        DnField(
          label: widget.label,
          controller: _email,
          readOnly: _status == EmailStatus.verified,
          keyboardType: TextInputType.emailAddress,
          autofillHints: const [AutofillHints.email],
          helper: widget.helper,
          error: _status == EmailStatus.sent ? null : _err,
          onChanged: (_) {
            if (_status != EmailStatus.none) _set(EmailStatus.none);
          },
        ),
        const SizedBox(height: 12),
        switch (_status) {
          EmailStatus.verified => const Row(
            children: [
              Icon(Icons.check_circle_rounded, size: 18, color: DnTokens.ok),
              SizedBox(width: 6),
              Expanded(
                child: Text(
                  'Email verified',
                  style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600, color: DnTokens.okInk),
                ),
              ),
            ],
          ),
          EmailStatus.undeliverable => const DnBanner(
            tone: DnBannerTone.warn,
            title: 'We couldn’t deliver a code to this address',
            body: 'Some workplaces block outside email. You can carry on; our team will confirm it with you.',
          ),
          EmailStatus.sent => Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('Enter the 6-digit code we sent to this address', style: DnText.hint),
              const SizedBox(height: 8),
              DnOtpField(
                controller: _code,
                state: _otp,
                enabled: !busy,
                onCompleted: (c) {
                  setState(() {
                    _otp = DnOtpState.idle;
                    _err = null;
                  });
                  _verify(c);
                },
              ),
              if (_err != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_err!, style: const TextStyle(fontSize: 12.5, color: DnTokens.err)),
                ),
              const SizedBox(height: 6),
              _resend.view(onResend: _send, sent: ''),
            ],
          ),
          EmailStatus.none => Align(
            alignment: Alignment.centerLeft,
            child: DnButton.outline('Send code to verify', size: DnButtonSize.sm, loading: busy, onPressed: _send),
          ),
        },
      ],
    ),
  );
}

/// Board A7: salary account plus one or more bank statements.
class SalaryAccountScreen extends StatefulWidget {
  const SalaryAccountScreen({super.key});

  @override
  State<SalaryAccountScreen> createState() => _SalaryAccountScreenState();
}

class _SalaryAccountScreenState extends State<SalaryAccountScreen> with Busy {
  final _bank = TextEditingController(text: saved('bank')['bank']);
  final _account = TextEditingController();
  final _ifsc = TextEditingController(text: saved('bank')['ifsc']);
  final _mobile = TextEditingController(text: saved('bank')['mobile']);
  final _pdfPw = TextEditingController();
  final _files = <PickedFile>[];
  String? _bankErr, _accountErr, _ifscErr, _mobileErr;

  @override
  void dispose() {
    for (final c in [_bank, _account, _ifsc, _mobile, _pdfPw]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _add() async {
    final f = await pickFile(context, PickFrom.files, name: 'Bank_statement_${_files.length + 1}');
    if (f != null) setState(() => _files.add(f));
  }

  void _continue() {
    setState(() {
      _bankErr = need(_bank.text, 'Enter your bank’s name.');
      _accountErr = accountError(_account.text);
      _ifscErr = ifscError(_ifsc.text);
      _mobileErr = mobileError(_mobile.text);
    });
    if ([_bankErr, _accountErr, _ifscErr, _mobileErr].any((e) => e != null)) return;
    final data = {
      'bank': _bank.text.trim(),
      'account': _account.text,
      'ifsc': _ifsc.text.toUpperCase(),
      'mobile': _mobile.text,
      'statements': '${_files.length}',
      'pdfPassword': _pdfPw.text,
    };
    run(() async {
      await api.saveStep('bank', data, files: {for (final (i, f) in _files.indexed) 'statement$i': f});
      savedSteps['bank'] = {...data, 'account': maskTail(_account.text), 'pdfPassword': ''};
      if (mounted) context.push('/signup/payslips');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Salary account',
    subtitle: 'Add the salary account your employer credits each month, and upload your bank statements (ideally the last 6 months).',
    step: 7,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Continue', block: true, loading: busy, onPressed: _files.isEmpty ? null : _continue),
    ),
    children: [
      DnField(label: 'Salary bank', controller: _bank, hint: 'Your bank’s name', error: _bankErr, textCapitalization: TextCapitalization.words),
      const SizedBox(height: 16),
      DnField(
        label: 'Account number',
        controller: _account,
        hint: '00123456789012',
        error: _accountErr,
        keyboardType: TextInputType.number,
        inputFormatters: digits(18),
      ),
      const SizedBox(height: 16),
      DnField(
        label: 'IFSC code',
        controller: _ifsc,
        hint: 'HDFC0001234',
        error: _ifscErr,
        textCapitalization: TextCapitalization.characters,
        inputFormatters: upperCode(11),
      ),
      const SizedBox(height: 16),
      DnField(
        label: 'Mobile number linked to this account',
        controller: _mobile,
        prefix: '+91',
        hint: '9876543210',
        error: _mobileErr,
        keyboardType: TextInputType.phone,
        inputFormatters: digits(10),
      ),
      const SizedBox(height: 16),
      _DropBox(
        title: _files.isEmpty ? 'Bank statements (last 6 months)' : '${_files.length} bank statement${_files.length == 1 ? '' : 's'} added',
        hint: _files.isEmpty ? '$uploadHint each — upload one or several' : 'Tap to add more',
        done: _files.isNotEmpty,
        onTap: _add,
      ),
      for (final f in _files)
        Padding(
          padding: const EdgeInsets.only(top: 8),
          child: DnTile(
            padding: const EdgeInsets.only(left: 14),
            child: Row(
              children: [
                const Icon(Icons.picture_as_pdf_outlined, size: 18, color: DnTokens.slate),
                const SizedBox(width: 8),
                Expanded(child: Text(f.name, style: DnText.strong)),
                IconButton(tooltip: 'Remove ${f.name}', onPressed: () => setState(() => _files.remove(f)), icon: const Icon(Icons.close_rounded, size: 18)),
              ],
            ),
          ),
        ),
      if (_files.any((f) => f.pdf)) ...[const SizedBox(height: 16), PdfPasswordField(controller: _pdfPw, what: 'statement is')],
    ],
  );
}

class _DropBox extends StatelessWidget {
  const _DropBox({required this.title, required this.hint, required this.done, required this.onTap});
  final String title;
  final String hint;
  final bool done;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Material(
    color: done ? DnTokens.okBg : DnTokens.paper,
    shape: RoundedRectangleBorder(
      borderRadius: BorderRadius.circular(DnTokens.r),
      side: BorderSide(color: done ? DnTokens.okLine : DnTokens.line2),
    ),
    clipBehavior: Clip.antiAlias,
    child: InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            Icon(done ? Icons.check_circle_rounded : Icons.upload_file_rounded, color: done ? DnTokens.ok : DnTokens.slate),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title, style: DnText.strong.copyWith(color: done ? DnTokens.okInk : null)),
                  Text(hint, style: DnText.hint),
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

/// Board A8: the last 3 months' salary slips, all required.
class PayslipsScreen extends StatefulWidget {
  const PayslipsScreen({super.key});

  @override
  State<PayslipsScreen> createState() => _PayslipsScreenState();
}

class _PayslipsScreenState extends State<PayslipsScreen> with Busy {
  static const _titles = ['Most recent month', 'Previous month', 'Month before that'];
  final _files = List<PickedFile?>.filled(3, null);
  final _errs = List<String?>.filled(3, null);
  final _pdfPw = TextEditingController();
  String? _err;

  @override
  void dispose() {
    _pdfPw.dispose();
    super.dispose();
  }

  Future<void> _pick(int i, bool photo) async {
    final now = DateTime.now();
    final month = dayMonth(DateTime(now.year, now.month - 1 - i)).split(' ').last;
    // The picker refuses a file over the limit with a toast; the tile error is for the demo's stand-ins.
    final f = await pickFile(context, photo ? PickFrom.camera : PickFrom.files, name: 'Payslip_$month');
    if (f == null || !mounted) return;
    setState(() {
      _errs[i] = uploadProblem(f);
      if (_errs[i] == null) _files[i] = f;
      _err = null;
    });
  }

  void _continue() {
    if (_files.contains(null)) return setState(() => _err = 'Upload all 3 salary slips to continue.');
    run(() async {
      await saveStep('payslips', {for (var i = 0; i < 3; i++) 'slip${i + 1}': _files[i]!.name, 'pdfPassword': _pdfPw.text}, files: {for (var i = 0; i < 3; i++) 'slip${i + 1}': _files[i]!});
      if (mounted) context.push('/signup/aadhaar');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Salary slips',
    subtitle: 'Upload your last 3 months’ salary slips so we can verify your income.',
    step: 8,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Continue', block: true, loading: busy, onPressed: _continue),
    ),
    children: [
      for (var i = 0; i < 3; i++) ...[
        UploadTile(title: _titles[i], file: _files[i], error: _errs[i], onPick: (photo) => _pick(i, photo), onRemove: () => setState(() => _files[i] = null)),
        const SizedBox(height: 10),
      ],
      if (_files.any((f) => f?.pdf ?? false)) ...[const SizedBox(height: 6), PdfPasswordField(controller: _pdfPw, what: 'payslips are')],
      if (_err != null) ...[const SizedBox(height: 12), DnBanner(tone: DnBannerTone.err, title: _err!)],
    ],
  );
}
