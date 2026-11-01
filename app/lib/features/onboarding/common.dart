import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/features/start/auth.dart' show passwordProblem;
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:dhannow/features/edge/step_review.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

/// Sign-up has 12 steps on the boards (A1 to A12).
const signupOf = 12;

/// ponytail: the demo server only records that a step is done, so the app keeps what each step
/// sent here for view mode (E2) and Edit prefill. The real app reads it back from `GET /journey`.
final savedSteps = <String, Map<String, String>>{};

/// What the "Sign-up half done" scenario already finished, so its view mode has something to show.
const _demoSaved = {
  'start': {'pan': 'ABCPS1234K', 'mobile': '9876543210'},
  'employment': {'type': 'salaried'},
  'employer': {'type': 'salaried', 'company': 'Acme Logistics Pvt Ltd', 'salary': '35000'},
};

Map<String, String> saved(String step) => savedSteps[step] ?? (api.onboardingDone.contains(step) ? _demoSaved[step] : null) ?? const {};

/// `api.saveStep` that also remembers the data for view mode.
Future<void> saveStep(String step, Map<String, String> data) async {
  await api.saveStep(step, data);
  savedSteps[step] = data;
}

/// Route of each sign-up step key ([Api.signupSteps] plus the end).
String signupRoute(String step) => step == 'employer' && saved('employment')['type'] == 'self-employed' ? '/signup/self-employed' : '/signup/$step';

/// Busy flag + the one error path every action shares: OFFLINE opens E3, anything else shows the
/// server's message; the button stays as the retry.
mixin Busy<T extends StatefulWidget> on State<T> {
  bool busy = false;

  Future<void> run(Future<void> Function() body) async {
    if (busy) return;
    setState(() => busy = true);
    try {
      await body();
    } on ApiException catch (e) {
      if (!mounted) return;
      e.code == 'OFFLINE' ? context.push('/offline') : showDnToast(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}

// ---- validation (inline DnField errors) ----

String? need(String v, String message) => v.trim().isEmpty ? message : null;

String? mobileError(String v) => v.isEmpty
    ? 'Enter your mobile number.'
    : RegExp(r'^[6-9]\d{9}$').hasMatch(v)
    ? null
    : 'Enter a 10-digit mobile number starting with 6, 7, 8 or 9.';

String? panError(String v) => v.isEmpty
    ? 'Enter your PAN.'
    : RegExp(r'^[A-Z]{5}\d{4}[A-Z]$').hasMatch(v.toUpperCase())
    ? null
    : 'Enter a valid PAN, like ABCDE1234F.';

String? ifscError(String v) => v.isEmpty
    ? 'Enter the IFSC code.'
    : RegExp(r'^[A-Z]{4}0[A-Z0-9]{6}$').hasMatch(v.toUpperCase())
    ? null
    : 'Enter a valid IFSC code, like HDFC0001234.';

String? emailError(String v) => v.isEmpty
    ? 'Enter your email address.'
    : RegExp(r'^[^@\s]+@[^@\s]+\.[A-Za-z]{2,}$').hasMatch(v.trim())
    ? null
    : 'Enter a valid email address.';

String? accountError(String v) => RegExp(r'^\d{9,18}$').hasMatch(v) ? null : 'Enter your account number (9 to 18 digits).';

String? passwordError(String v) => passwordProblem(v);

/// Board A9 errors; the number must also pass the Verhoeff check digit printed Aadhaars carry.
String? aadhaarError(String v) {
  if (!RegExp(r'^\d{12}$').hasMatch(v)) return 'Enter your 12-digit Aadhaar number.';
  if (v.startsWith('0') || v.startsWith('1') || !_verhoeff(v)) return 'That doesn’t look like a valid Aadhaar number — please check it against your card.';
  return null;
}

bool _verhoeff(String n) {
  const d = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
    [1, 2, 3, 4, 0, 6, 7, 8, 9, 5],
    [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
    [3, 4, 0, 1, 2, 8, 9, 5, 6, 7],
    [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], //
    [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
    [6, 5, 9, 8, 7, 1, 0, 4, 3, 2],
    [7, 6, 5, 9, 8, 2, 1, 0, 4, 3],
    [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
    [9, 8, 7, 6, 5, 4, 3, 2, 1, 0],
  ];
  const p = [
    [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4], [5, 8, 0, 3, 7, 9, 6, 1, 4, 2], [8, 9, 1, 6, 0, 4, 3, 5, 2, 7], //
    [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1], [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8],
  ];
  var c = 0;
  final digits = n.split('').reversed.map(int.parse).toList();
  for (var i = 0; i < digits.length; i++) {
    c = d[c][p[i % 8][digits[i]]];
  }
  return c == 0;
}

/// CC-49: images are compressed to 1.5 MB on the phone; a PDF over 5 MB is refused before upload.
const uploadHint = 'PDF or photo (JPG, PNG, HEIC) up to 5 MB';

String? uploadError(String name, int bytes) =>
    name.toLowerCase().endsWith('.pdf') && bytes > 5 * 1024 * 1024 ? 'This PDF is over 5 MB. Choose a smaller file or take a photo instead.' : null;

/// A picked file. The demo has no picker: a tap adds a named fake file.
class PickedFile {
  const PickedFile(this.name, {this.bytes = 420 * 1024});
  final String name;
  final int bytes;
  bool get pdf => name.toLowerCase().endsWith('.pdf');
}

final digitsOnly = [FilteringTextInputFormatter.digitsOnly];

List<TextInputFormatter> digits(int max) => [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(max)];

/// Letters and digits, upper-cased as typed (PAN, IFSC).
List<TextInputFormatter> upperCode(int max) => [
  FilteringTextInputFormatter.allow(RegExp('[A-Za-z0-9]')),
  LengthLimitingTextInputFormatter(max),
  TextInputFormatter.withFunction((_, v) => v.copyWith(text: v.text.toUpperCase())),
];

String maskTail(String v, [int keep = 4]) => v.length <= keep ? v : '••••${v.substring(v.length - keep)}';

// ---- shared pieces ----

/// "Exit" (A1, A2, signed out) or "Save & exit" in the step header.
class ExitLink extends StatelessWidget {
  const ExitLink({super.key, this.signedOut = false});
  final bool signedOut;

  @override
  Widget build(BuildContext context) {
    final label = signedOut ? 'Exit' : 'Save & exit';
    void exit() => context.go(signedOut ? '/welcome' : '/dashboard');
    // At large font sizes the words no longer fit beside "Step n of 12" on a 320dp screen (CC-58).
    if (!signedOut && MediaQuery.textScalerOf(context).scale(10) > 15) return DnIconPill(icon: Icons.logout_rounded, tooltip: label, size: 34, onPressed: exit);
    return DnButton.text(label, onPressed: exit);
  }
}

/// Primary action (plus an optional second one) over the trust line the boards end with.
class StepActions extends StatelessWidget {
  const StepActions({super.key, required this.primary, this.secondary, this.trust = true});
  final Widget primary;
  final Widget? secondary;
  final bool trust;

  @override
  Widget build(BuildContext context) => Column(
    mainAxisSize: MainAxisSize.min,
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      primary,
      if (secondary != null) ...[const SizedBox(height: 4), secondary!],
      if (trust) ...[
        const SizedBox(height: 10),
        const Wrap(
          alignment: WrapAlignment.center,
          spacing: 14,
          runSpacing: 4,
          children: [
            _Trust(Icons.lock_outline_rounded, 'Secure & encrypted'),
            _Trust(Icons.visibility_outlined, 'Transparent terms'),
            _Trust(Icons.verified_outlined, 'No fee paid beforehand'),
          ],
        ),
      ],
    ],
  );
}

class _Trust extends StatelessWidget {
  const _Trust(this.icon, this.text);
  final IconData icon;
  final String text;

  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(icon, size: 14, color: DnTokens.muted),
      const SizedBox(width: 4),
      Flexible(child: Text(text, style: DnText.hint.copyWith(fontSize: 11.5))),
    ],
  );
}

/// Label / value rows inside a panel (E2 view mode, E13b).
class KvRows extends StatelessWidget {
  const KvRows(this.rows, {super.key});
  final Map<String, String> rows;

  @override
  Widget build(BuildContext context) => Column(
    children: [
      for (final (i, MapEntry(:key, :value)) in rows.entries.indexed)
        Container(
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            border: i == 0 ? null : const Border(top: BorderSide(color: DnTokens.line)),
          ),
          child: Wrap(
            alignment: WrapAlignment.spaceBetween,
            spacing: 12,
            children: [
              Text(key, style: DnText.body),
              Text(value, style: DnText.strong),
            ],
          ),
        ),
    ],
  );
}

/// Board E2: a finished step reopens read-only. Continue sends nothing; Edit turns it back into
/// the form (only then does Save call the API). Locked steps (mobile OTP, credit consent) show a
/// Verified badge instead of Edit.
class StepGate extends StatefulWidget {
  const StepGate({super.key, required this.step, required this.n, required this.title, required this.rows, this.locked = false, required this.child});

  final String step;
  final int n;
  final String title;
  final Map<String, String> Function(Map<String, String> data) rows;
  final bool locked;
  final Widget child;

  @override
  State<StepGate> createState() => _StepGateState();
}

class _StepGateState extends State<StepGate> {
  late bool _view = api.onboardingDone.contains(widget.step);

  @override
  Widget build(BuildContext context) {
    if (!_view) return widget.child;
    final rows = widget.rows(saved(widget.step));
    return StepScaffold(
      title: widget.title,
      step: widget.n,
      of: signupOf,
      confirmBack: false,
      bottom: DnButton('Continue where I left off', block: true, onPressed: () => context.push(signupRoute(api.nextSignupStep))),
      children: [
        StepReviewCard(
          rows: [for (final MapEntry(:key, :value) in rows.entries) (key, value)],
          onEdit: widget.locked ? null : () => setState(() => _view = false),
        ),
      ],
    );
  }
}

/// Upload tile (A8–A10, A12): empty shows the file rule and Take photo / Choose file; filled shows
/// the file with Replace and remove.
class UploadTile extends StatelessWidget {
  const UploadTile({super.key, required this.title, required this.file, required this.onPick, this.onRemove, this.error});

  final String title;
  final PickedFile? file;

  /// `photo` true for Take photo, false for Choose file.
  final ValueChanged<bool> onPick;
  final VoidCallback? onRemove;
  final String? error;

  @override
  Widget build(BuildContext context) {
    final f = file;
    return Container(
      padding: const EdgeInsets.fromLTRB(14, 12, 6, 12),
      decoration: BoxDecoration(
        color: f != null ? DnTokens.okBg : DnTokens.paper,
        borderRadius: BorderRadius.circular(DnTokens.r),
        border: Border.all(color: error != null ? DnTokens.err : (f != null ? DnTokens.okLine : DnTokens.line2)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(f != null ? Icons.check_circle_rounded : Icons.upload_file_rounded, size: 20, color: f != null ? DnTokens.ok : DnTokens.slate),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: DnText.strong),
                    Text(f?.name ?? uploadHint, style: DnText.hint),
                  ],
                ),
              ),
              if (f != null && onRemove != null)
                IconButton(
                  tooltip: 'Remove ${f.name}',
                  onPressed: onRemove,
                  icon: const Icon(Icons.close_rounded, size: 20, color: DnTokens.slate),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 6,
            children: [
              if (f != null)
                DnButton.outline('Replace', size: DnButtonSize.xs, onPressed: () => onPick(false))
              else ...[
                DnButton.outline('Take photo', size: DnButtonSize.xs, icon: Icons.photo_camera_outlined, onPressed: () => onPick(true)),
                DnButton.outline('Choose file', size: DnButtonSize.xs, icon: Icons.folder_open_rounded, onPressed: () => onPick(false)),
              ],
            ],
          ),
          if (error != null)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(error!, style: const TextStyle(fontSize: 12, color: DnTokens.err)),
            ),
        ],
      ),
    );
  }
}

/// CC-50: optional password for a protected PDF.
class PdfPasswordField extends StatelessWidget {
  const PdfPasswordField({super.key, required this.controller, required this.what});
  final TextEditingController controller;
  final String what;

  @override
  Widget build(BuildContext context) => DnField(
    label: 'PDF password (optional)',
    controller: controller,
    obscure: true,
    helper: 'If your $what password-protected, enter the password so our team can open it.',
  );
}

/// Tappable date field (DOB pickers). Dates never go past today.
class DateField extends StatelessWidget {
  const DateField({super.key, required this.label, required this.value, required this.onPicked, this.error, this.adult = true});

  final String label;
  final DateTime? value;
  final ValueChanged<DateTime> onPicked;
  final String? error;
  final bool adult;

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final last = adult ? DateTime(now.year - 18, now.month, now.day) : now;
    return GestureDetector(
      onTap: () async {
        final d = await showDatePicker(context: context, firstDate: DateTime(1940), lastDate: last, initialDate: value ?? DateTime(last.year - 7));
        if (d != null) onPicked(d);
      },
      child: AbsorbPointer(
        child: DnField(
          label: label,
          controller: TextEditingController(text: value == null ? '' : _fmt(value!)),
          hint: 'Pick a date',
          error: error,
          suffix: const Icon(Icons.calendar_today_rounded, size: 18, color: DnTokens.slate),
        ),
      ),
    );
  }
}

String _fmt(DateTime d) => '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';

String isoDate(DateTime d) => d.toIso8601String().substring(0, 10);
