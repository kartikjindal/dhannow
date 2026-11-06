import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'common.dart';
import 'details.dart' show ResendTimer, WebLinks;

/// Front and back of a card (A9, A10), with the optional PDF password when either is a PDF.
class _CardSides extends StatelessWidget {
  const _CardSides({required this.name, required this.front, required this.back, required this.onChanged, required this.pdfPw});

  final String name;
  final PickedFile? front;
  final PickedFile? back;
  final void Function(bool front, PickedFile? file) onChanged;
  final TextEditingController pdfPw;

  Future<void> _pick(BuildContext context, bool front, bool photo) async {
    final f = await pickFile(context, photo ? PickFrom.camera : PickFrom.files, name: '${name.split(' ').first}_${front ? 'front' : 'back'}');
    if (f != null) onChanged(front, f);
  }

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      UploadTile(title: 'Front of $name', file: front, onPick: (p) => _pick(context, true, p), onRemove: () => onChanged(true, null)),
      const SizedBox(height: 10),
      UploadTile(title: 'Back of $name', file: back, onPick: (p) => _pick(context, false, p), onRemove: () => onChanged(false, null)),
      if ((front?.pdf ?? false) || (back?.pdf ?? false)) ...[const SizedBox(height: 16), PdfPasswordField(controller: pdfPw, what: 'card file is')],
    ],
  );
}

/// Board A9. The full number goes to the server only; the app keeps the last 4 digits.
class AadhaarScreen extends StatefulWidget {
  const AadhaarScreen({super.key});

  @override
  State<AadhaarScreen> createState() => _AadhaarScreenState();
}

class _AadhaarScreenState extends State<AadhaarScreen> with Busy {
  final _number = TextEditingController();
  final _pdfPw = TextEditingController();
  PickedFile? _front, _back;
  String? _numberErr, _err;

  @override
  void dispose() {
    _number.dispose();
    _pdfPw.dispose();
    super.dispose();
  }

  void _continue() {
    setState(() {
      _numberErr = aadhaarError(_number.text);
      _err = _front == null || _back == null ? 'Upload both the front and the back of your Aadhaar card to continue.' : null;
    });
    if (_numberErr != null || _err != null) return;
    run(() async {
      await api.saveStep('aadhaar', {'aadhaar': _number.text, 'front': _front!.name, 'back': _back!.name, 'pdfPassword': _pdfPw.text}, files: {'front': _front!, 'back': _back!});
      savedSteps['aadhaar'] = {'last4': _number.text.substring(8)};
      if (mounted) context.push('/signup/pan-card');
    });
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Aadhaar card',
    subtitle: 'Enter your Aadhaar number exactly as printed on the card, then upload a clear photo or PDF of both sides.',
    step: 9,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Continue', block: true, loading: busy, onPressed: _continue),
    ),
    children: [
      DnField(
        label: 'Aadhaar number',
        controller: _number,
        hint: '1234 5678 9012',
        error: _numberErr,
        helper: '12 digits. We compare it with the Aadhaar linked to your PAN and DigiLocker.',
        keyboardType: TextInputType.number,
        inputFormatters: digits(12),
      ),
      const SizedBox(height: 16),
      _CardSides(name: 'Aadhaar card', front: _front, back: _back, pdfPw: _pdfPw, onChanged: (front, f) => setState(() => front ? _front = f : _back = f)),
      if (_err != null) ...[const SizedBox(height: 12), DnBanner(tone: DnBannerTone.err, title: _err!)],
    ],
  );
}

/// Board A10.
class PanCardScreen extends StatefulWidget {
  const PanCardScreen({super.key});

  @override
  State<PanCardScreen> createState() => _PanCardScreenState();
}

class _PanCardScreenState extends State<PanCardScreen> with Busy {
  final _pdfPw = TextEditingController();
  PickedFile? _front, _back;
  String? _err;

  @override
  void dispose() {
    _pdfPw.dispose();
    super.dispose();
  }

  void _continue() {
    if (_front == null || _back == null) return setState(() => _err = 'Upload both the front and the back of your PAN card to continue.');
    run(() async {
      await saveStep('pan-card', {'front': _front!.name, 'back': _back!.name, 'pdfPassword': _pdfPw.text}, files: {'front': _front!, 'back': _back!});
      if (mounted) context.push('/signup/consent');
    });
  }

  @override
  Widget build(BuildContext context) {
    final pan = saved('start')['pan'];
    return StepScaffold(
      title: 'PAN card',
      subtitle: 'Upload a clear photo or PDF of both sides of your PAN card.',
      step: 10,
      of: signupOf,
      trailing: const ExitLink(),
      bottom: StepActions(
        primary: DnButton('Continue', block: true, loading: busy, onPressed: _continue),
      ),
      children: [
        if (pan != null) ...[
          Text.rich(
            TextSpan(
              text: 'The card should match the PAN you entered: ',
              children: [TextSpan(text: pan, style: DnText.strong.copyWith(letterSpacing: 1))],
            ),
            style: DnText.body,
          ),
          const SizedBox(height: 16),
        ],
        _CardSides(
          name: 'PAN card',
          front: _front,
          back: _back,
          pdfPw: _pdfPw,
          onChanged: (front, f) => setState(() {
            front ? _front = f : _back = f;
            _err = null;
          }),
        ),
        if (_err != null) ...[const SizedBox(height: 12), DnBanner(tone: DnBannerTone.err, title: _err!)],
      ],
    );
  }
}

/// Board A11: mobile OTP, date of birth and the credit bureau consent, then submit.
class ConsentScreen extends StatefulWidget {
  const ConsentScreen({super.key});

  @override
  State<ConsentScreen> createState() => _ConsentScreenState();
}

class _ConsentScreenState extends State<ConsentScreen> with Busy {
  final _code = TextEditingController();
  final _mobile = saved('start')['mobile'] ?? '';
  var _otp = DnOtpState.idle;
  DateTime? _dob;
  bool _bureau = false;
  bool _submitting = false;
  String? _codeErr, _dobErr;
  late final _resend = ResendTimer(() => mounted ? setState(() {}) : null)..start();

  @override
  void initState() {
    super.initState();
    // The bureau-consent code goes to the mobile on file as the step opens; Resend covers a failure.
    api.sendOtp(_mobile, purpose: OtpFor.consent).catchError((_) {});
  }

  @override
  void dispose() {
    _code.dispose();
    _resend.cancel();
    super.dispose();
  }

  Future<void> _submit() async {
    setState(() {
      _codeErr = _code.text.length == 6 ? null : 'Enter the 6-digit code.';
      _dobErr = _dob == null ? 'Pick your date of birth.' : null;
    });
    if (_codeErr != null || _dobErr != null) return;
    setState(() => _submitting = true);
    await run(() async {
      try {
        await api.verifyOtp(_code.text, purpose: OtpFor.consent);
        await api.submitApplication(dob: _dob);
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
          _codeErr = e.message;
        });
        return;
      }
      savedSteps['consent'] = {'dob': isoDate(_dob!)};
      // The server answers PAN mismatch with A14 and a bureau challenge with A13 via `GET /journey`.
      if (!mounted) return;
      context.go(switch (api.stage) {
        Stage.rejected => '/application-rejected',
        Stage.question => '/credit-question',
        _ => '/signup/submitted',
      });
    });
    if (mounted) setState(() => _submitting = false);
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
    title: 'Consent & verification',
    step: 11,
    of: signupOf,
    trailing: const ExitLink(),
    bottom: StepActions(
      primary: DnButton('Confirm & submit', block: true, loading: busy, onPressed: _bureau ? _submit : null),
    ),
    children: _submitting
        ? [const _Verifying()]
        : [
            const Text('One last confirmation', style: DnText.section),
            const SizedBox(height: 4),
            Text.rich(
              TextSpan(
                text: 'Enter the code we sent to ',
                children: [
                  TextSpan(text: _mobile.isEmpty ? 'your mobile' : _mobile, style: DnText.strong),
                  const TextSpan(text: ' and authorise the credit check.'),
                ],
              ),
              style: DnText.body,
            ),
            const SizedBox(height: 16),
            DnOtpField(
              controller: _code,
              state: _otp,
              onCompleted: (_) => setState(() {
                _otp = DnOtpState.idle;
                _codeErr = null;
              }),
            ),
            if (_codeErr != null)
              Padding(
                padding: const EdgeInsets.only(top: 8),
                child: Text(_codeErr!, style: const TextStyle(fontSize: 12.5, color: DnTokens.err)),
              ),
            const SizedBox(height: 6),
            _resend.view(
              sent: '',
              onResend: () => run(() async {
                await api.sendOtp(_mobile, purpose: OtpFor.consent);
                _resend.used();
              }),
            ),
            const SizedBox(height: 10),
            DateField(
              label: 'Date of birth',
              value: _dob,
              error: _dobErr,
              onPicked: (d) => setState(() {
                _dob = d;
                _dobErr = null;
              }),
            ),
            const SizedBox(height: 14),
            DnCheck(
              value: _bureau,
              onChanged: (v) => setState(() => _bureau = v),
              label: const TextSpan(
                text: 'I authorise ${Brand.name} and its lending partner to fetch my credit report from a credit bureau to assess this application.',
              ),
            ),
            const SizedBox(height: 10),
            const DnTile(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(Icons.location_on_outlined, size: 20, color: DnTokens.ink),
                  SizedBox(width: 10),
                  Expanded(
                    child: Text('We also confirm your current address from your phone’s location. Android may ask you to allow it.', style: DnText.hint),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 14),
            const WebLinks([
              ('Consent and data use', '${Brand.site}/consent.html'),
              ('Privacy policy', '${Brand.site}/privacy.html'),
            ], note: 'The page version is recorded when you accept.'),
          ],
  );
}

class _Verifying extends StatelessWidget {
  const _Verifying();

  @override
  Widget build(BuildContext context) => const DnPanel(
    child: Column(
      children: [
        SizedBox.square(dimension: 28, child: CircularProgressIndicator(strokeWidth: 4, backgroundColor: DnTokens.tint)),
        SizedBox(height: 12),
        Text('Verifying your details…', textAlign: TextAlign.center, style: DnText.section),
        SizedBox(height: 4),
        Text('This takes a few seconds. Please don’t close this page.', textAlign: TextAlign.center, style: DnText.hint),
      ],
    ),
  );
}
