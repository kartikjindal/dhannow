import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/files.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import 'common.dart';

/// B3 Verify with DigiLocker in a Custom Tab; three failures (or the day's sessions used up) open E10.
class DigilockerScreen extends StatefulWidget {
  const DigilockerScreen({super.key});

  @override
  State<DigilockerScreen> createState() => _DigilockerScreenState();
}

class _DigilockerScreenState extends State<DigilockerScreen> with Busy {
  int _fails = 0;

  Future<void> _connect() async {
    if (await act(api.verifyDigilocker, quiet: const {'DIGILOCKER_EXHAUSTED'})) {
      if (mounted) context.push('/loan/references');
      return;
    }
    if (!mounted) return;
    setState(() => _fails++);
    if (_fails >= 3 || lastError?.code == 'DIGILOCKER_EXHAUSTED') context.push('/loan/digilocker/upload');
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Verify with DigiLocker',
        step: 3,
        of: 11,
        bottom: Column(mainAxisSize: MainAxisSize.min, children: [
          OfferAction(_fails > 0 ? 'Try DigiLocker again' : 'Continue with DigiLocker', loading: busy, onPressed: _connect),
          DnButton.text('DigiLocker not working? Upload Aadhaar instead', onPressed: () => context.push('/loan/digilocker/upload')),
        ]),
        children: [
          DnPanel(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Container(
                width: 52,
                height: 52,
                decoration: const BoxDecoration(color: DnTokens.tint, shape: BoxShape.circle),
                child: const Icon(Icons.badge_outlined, color: DnTokens.primary600),
              ),
              const SizedBox(height: 14),
              const Text('Connect DigiLocker', style: DnText.section),
              const SizedBox(height: 6),
              const Text(
                'You’ll head to DigiLocker to share your Aadhaar securely. We only receive the details you approve — never your password.',
                style: DnText.body,
              ),
              const SizedBox(height: 14),
              for (final (icon, text) in const [
                (Icons.account_balance_outlined, 'Government of India digital identity'),
                (Icons.fact_check_outlined, 'Consent-based, read-only access'),
                (Icons.lock_outline_rounded, 'Bank-grade encryption'),
              ])
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Row(children: [
                    Icon(icon, size: 18, color: DnTokens.ok),
                    const SizedBox(width: 10),
                    Expanded(child: Text(text, style: DnText.body)),
                  ]),
                ),
            ]),
          ),
          if (busy) ...[
            const SizedBox(height: DnTokens.gap),
            const DnBanner(title: 'Waiting for you to finish in the DigiLocker tab…', icon: Icons.open_in_new_rounded),
          ] else if (_fails > 0) ...[
            const SizedBox(height: DnTokens.gap),
            const DnBanner(tone: DnBannerTone.warn, title: 'DigiLocker is taking longer than usual.', body: 'Try again, or upload your Aadhaar instead.'),
          ],
        ],
      );
}

/// E10 Upload your Aadhaar instead (DigiLocker fallback, verified by hand).
class AadhaarUploadScreen extends StatefulWidget {
  const AadhaarUploadScreen({super.key});

  @override
  State<AadhaarUploadScreen> createState() => _AadhaarUploadScreenState();
}

class _AadhaarUploadScreenState extends State<AadhaarUploadScreen> with Busy {
  PickedFile? _front;
  PickedFile? _back;

  Future<PickedFile?> _pick(String side) => pickFile(context, PickFrom.files, name: 'Aadhaar_$side');

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Verify Aadhaar',
        step: 3,
        of: 11,
        bottom: OfferAction(
          'Upload & continue',
          trust: false,
          loading: busy,
          onPressed: _front == null || _back == null
              ? null
              : () async {
                  if (await act(() => api.uploadAadhaar(_front!, _back!)) && context.mounted) context.push('/loan/references');
                },
        ),
        children: [
          const Text('Upload your Aadhaar instead', style: DnText.section),
          const SizedBox(height: 6),
          const Text(
            'DigiLocker didn’t connect. Upload clear photos or scans of both sides of your Aadhaar card and we’ll verify it manually before your loan is sent.',
            style: DnText.body,
          ),
          const SizedBox(height: 16),
          UploadSlot(label: 'Front side', added: _front != null, onTap: () async {
            final f = await _pick('front');
            if (f != null && mounted) setState(() => _front = f);
          }),
          const SizedBox(height: 10),
          UploadSlot(label: 'Back side', added: _back != null, onTap: () async {
            final f = await _pick('back');
            if (f != null && mounted) setState(() => _back = f);
          }),
          const SizedBox(height: 6),
          Align(
            alignment: Alignment.centerLeft,
            child: DnButton.text('Try DigiLocker again', onPressed: () => context.canPop() ? context.pop() : context.go('/loan/digilocker')),
          ),
        ],
      );
}

/// B4 Your references.
class ReferencesScreen extends StatefulWidget {
  const ReferencesScreen({super.key});

  @override
  State<ReferencesScreen> createState() => _ReferencesScreenState();
}

class _Ref {
  final name = TextEditingController();
  final mobile = TextEditingController();
  String? relation;
  String? nameErr, mobileErr, relationErr;
}

class _ReferencesScreenState extends State<ReferencesScreen> with Busy {
  static const _relations = ['Parent', 'Spouse', 'Sibling', 'Relative', 'Friend', 'Colleague', 'Manager', 'Neighbour'];
  final _refs = [_Ref(), _Ref()];

  @override
  void dispose() {
    for (final r in _refs) {
      r.name.dispose();
      r.mobile.dispose();
    }
    super.dispose();
  }

  bool _validate() {
    final mobile = RegExp(r'^[6-9]\d{9}$');
    for (final r in _refs) {
      r.nameErr = r.name.text.trim().length < 3 ? 'Enter their full name' : null;
      r.mobileErr = mobile.hasMatch(r.mobile.text) ? null : 'Enter a valid 10-digit mobile number';
      r.relationErr = r.relation == null ? 'Choose a relation' : null;
    }
    if (_refs[1].mobileErr == null && _refs[0].mobile.text == _refs[1].mobile.text) _refs[1].mobileErr = 'Your two references must have different mobile numbers';
    setState(() {});
    return _refs.every((r) => r.nameErr == null && r.mobileErr == null && r.relationErr == null);
  }

  Future<void> _pickRelation(_Ref r) async {
    final v = await showDnSheet<String>(
      context,
      builder: (context) => Flexible(
        child: SingleChildScrollView(
          child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text('Relation', style: DnText.section.copyWith(fontSize: 18)),
            const SizedBox(height: 8),
            for (final (i, rel) in _relations.indexed)
              DnListRow(title: rel, first: i == 0, trailing: rel == r.relation ? const Icon(Icons.check_rounded, color: DnTokens.ink) : const SizedBox(), onTap: () => Navigator.pop(context, rel)),
          ]),
        ),
      ),
    );
    if (v != null) setState(() => r.relation = v);
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Your references',
        subtitle: 'Add two people who know you. We may contact them only if we can’t reach you.',
        step: 4,
        of: 11,
        bottom: OfferAction(
          'Continue',
          loading: busy,
          onPressed: () async {
            if (!_validate()) return;
            final refs = [for (final r in _refs) (name: r.name.text.trim(), mobile: r.mobile.text, relation: r.relation!)];
            if (await act(() => api.saveReferences(refs)) && context.mounted) context.push('/loan/summary');
          },
        ),
        children: [
          for (final (i, r) in _refs.indexed) ...[
            DnPanel(
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                DnCaps('Reference ${i + 1}'),
                const SizedBox(height: 12),
                DnField(label: 'Full name', controller: r.name, error: r.nameErr, textCapitalization: TextCapitalization.words, textInputAction: TextInputAction.next),
                const SizedBox(height: 14),
                DnField(
                  label: 'Mobile number',
                  controller: r.mobile,
                  prefix: '+91',
                  error: r.mobileErr,
                  keyboardType: TextInputType.phone,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(10)],
                ),
                const SizedBox(height: 14),
                _SelectField(label: 'Relation', value: r.relation, error: r.relationErr, onTap: () => _pickRelation(r)),
              ]),
            ),
            const SizedBox(height: DnTokens.gap),
          ],
        ],
      );
}

/// A field-looking button that opens a picker sheet.
class _SelectField extends StatelessWidget {
  const _SelectField({required this.label, required this.value, required this.onTap, this.error});
  final String label;
  final String? value;
  final String? error;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text(label, style: DnText.label),
        const SizedBox(height: 7),
        Semantics(
          button: true,
          label: '$label, ${value ?? 'not chosen'}',
          excludeSemantics: true,
          child: InkWell(
            onTap: onTap,
            borderRadius: BorderRadius.circular(DnTokens.rSm),
            child: Container(
              constraints: const BoxConstraints(minHeight: 48),
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
              decoration: BoxDecoration(
                color: DnTokens.paper,
                borderRadius: BorderRadius.circular(DnTokens.rSm),
                border: Border.all(color: error != null ? DnTokens.err : DnTokens.line2),
              ),
              child: Row(children: [
                Expanded(child: Text(value ?? 'Select…', style: TextStyle(fontSize: 15, color: value == null ? DnTokens.muted : DnTokens.ink))),
                const Icon(Icons.expand_more_rounded, color: DnTokens.slate),
              ]),
            ),
          ),
        ),
        if (error != null)
          Padding(
            padding: const EdgeInsets.only(top: 7),
            child: Row(children: [
              const Icon(Icons.error_outline_rounded, size: 15, color: DnTokens.err),
              const SizedBox(width: 5),
              Expanded(child: Text(error!, style: const TextStyle(fontSize: 12, color: DnTokens.err))),
            ]),
          ),
      ]);
}

/// B6 Take a selfie: what the guided check asks for.
class SelfieIntroScreen extends StatelessWidget {
  const SelfieIntroScreen({super.key});

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Take a selfie',
        step: 6,
        of: 11,
        bottom: Column(mainAxisSize: MainAxisSize.min, children: [
          DnButton('Start camera', block: true, icon: Icons.photo_camera_outlined, onPressed: () => context.push('/loan/selfie/capture')),
          const SizedBox(height: 8),
          const Text('Android asks for camera permission the first time.', textAlign: TextAlign.center, style: DnText.hint),
        ]),
        children: [
          DnPanel(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              const Text('Verify it’s really you', style: DnText.section),
              const SizedBox(height: 6),
              const Text(
                'A 10-second video check. It confirms you’re a real person and matches your face with your Aadhaar photo, so no one else can borrow in your name.',
                style: DnText.body,
              ),
              const SizedBox(height: 8),
              for (final (i, (title, detail)) in _poses.indexed)
                DnListRow(
                  first: i == 0,
                  icon: [Icons.face_rounded, Icons.turn_right_rounded, Icons.turn_left_rounded][i],
                  title: '${i + 1}. $title',
                  detail: detail,
                ),
            ]),
          ),
          const SizedBox(height: DnTokens.gap),
          const DnBanner(icon: Icons.light_mode_outlined, title: 'Good light on your face', body: 'No cap or dark glasses, only you in the frame.'),
        ],
      );
}

const _poses = [
  ('Look straight', 'Hold still for a second'),
  ('Turn right', 'Slowly, until the ring fills'),
  ('Turn left', 'Then you’re done'),
];

/// B6b Guided capture: straight, right, left, then upload for staff review (v1: REVIEW).
///
/// ponytail: each pose is one photo from the phone's own front camera (`image_picker`, no camera
/// plugin or preview); swap in a live preview with the `camera` package if guidance needs it.
class SelfieCaptureScreen extends StatefulWidget {
  const SelfieCaptureScreen({super.key});

  @override
  State<SelfieCaptureScreen> createState() => _SelfieCaptureScreenState();
}

class _SelfieCaptureScreenState extends State<SelfieCaptureScreen> with Busy {
  int _pose = 0;
  final _frames = <PickedFile>[];

  static const _prompts = ['Look straight at the camera', 'Slowly turn your head right', 'Slowly turn your head left'];

  Future<void> _capture() async {
    if (_pose < 3) {
      final f = await pickFile(context, PickFrom.selfie, name: 'selfie-${['straight', 'right', 'left'][_pose]}');
      if (f == null || !mounted) return;
      HapticFeedback.lightImpact();
      setState(() {
        _frames.add(f);
        _pose++;
      });
    }
    if (_pose < 3) return;
    String? status;
    final ok = await act(() async => status = await api.uploadSelfie(_frames), quiet: const {'SELFIE_ATTEMPTS_EXCEEDED'});
    if (!mounted) return;
    // Failed to send (offline, server): the frames stay, and "Try again" sends them again.
    if (!ok && lastError?.code != 'SELFIE_ATTEMPTS_EXCEEDED') return;
    // REVIEW: staff check the photos; out of tries: staff check the last ones. Either way, carry on.
    showDnToast(context, ok && status == 'PASS' ? 'Selfie verified' : (lastError?.message ?? 'Photos sent. Our team will check them.'));
    context.push('/loan/sanction-letter');
  }

  @override
  Widget build(BuildContext context) {
    final done = _pose == 3;
    return StepScaffold(
      title: 'Take a selfie',
      step: 6,
      of: 11,
      bottom: Column(mainAxisSize: MainAxisSize.min, children: [
        DnButton(done ? (busy ? 'Verified, continuing…' : 'Try again') : 'Capture', block: true, loading: busy, icon: Icons.camera_rounded, onPressed: _capture),
        DnButton.text('Camera blocked?', onPressed: () => context.push('/camera-denied')),
      ]),
      children: [
        DnCaps('${math.min(_pose + 1, 3)} of 3'),
        const SizedBox(height: 4),
        Text(done ? 'All done' : _prompts[_pose], style: DnText.section.copyWith(fontSize: 18)),
        const SizedBox(height: 4),
        const Text('Tap Capture, take the photo with your front camera, then come back here.', style: DnText.body),
        const SizedBox(height: 18),
        Center(child: _Oval(progress: _pose / 3, pose: _pose)),
        const SizedBox(height: 18),
        Wrap(alignment: WrapAlignment.center, spacing: 8, runSpacing: 8, children: [
          for (final (i, (title, _)) in _poses.indexed)
            DnPill(title, tone: i < _pose ? DnTone.ok : (i == _pose ? DnTone.cobalt : DnTone.neutral), dot: i <= _pose),
        ]),
        const SizedBox(height: 12),
        const Wrap(alignment: WrapAlignment.center, spacing: 14, children: [
          Text('✓ Good light', style: TextStyle(fontSize: 12, color: DnTokens.okInk)),
          Text('✓ Face in frame', style: TextStyle(fontSize: 12, color: DnTokens.okInk)),
        ]),
      ],
    );
  }
}

/// The framing oval: an ink "viewfinder" whose ring fills one third per pose.
class _Oval extends StatelessWidget {
  const _Oval({required this.progress, required this.pose});
  final double progress;
  final int pose;

  @override
  Widget build(BuildContext context) => Semantics(
        label: 'Camera frame, ${(progress * 3).round()} of 3 poses done',
        excludeSemantics: true,
        child: SizedBox(
          width: 220,
          height: 270,
          child: TweenAnimationBuilder<double>(
            tween: Tween(end: progress),
            duration: context.motion(const Duration(milliseconds: 500)),
            curve: DnTokens.easeOut,
            builder: (_, v, child) => CustomPaint(painter: _OvalPainter(v), child: child),
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: DecoratedBox(
                decoration: const ShapeDecoration(color: DnTokens.charcoal, shape: OvalBorder()),
                child: AnimatedSlide(
                  offset: Offset(switch (pose) { 1 => .12, 2 => -.12, _ => 0 }, 0),
                  duration: context.motion(const Duration(milliseconds: 400)),
                  curve: DnTokens.easeOut,
                  child: Icon(pose == 3 ? Icons.verified_rounded : Icons.face_rounded, size: 96, color: pose == 3 ? DnTokens.mint : DnTokens.onDarkMuted),
                ),
              ),
            ),
          ),
        ),
      );
}

class _OvalPainter extends CustomPainter {
  _OvalPainter(this.value);
  final double value;

  @override
  void paint(Canvas canvas, Size size) {
    final r = (Offset.zero & size).deflate(4);
    final p = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 6
      ..strokeCap = StrokeCap.round;
    canvas.drawOval(r, p..color = DnTokens.line);
    if (value > 0) canvas.drawArc(r, -math.pi / 2, math.pi * 2 * value, false, p..color = DnTokens.primary);
  }

  @override
  bool shouldRepaint(_OvalPainter old) => old.value != value;
}

/// B7 Confirm your address: only when location capture failed.
class AddressScreen extends StatefulWidget {
  const AddressScreen({super.key});

  @override
  State<AddressScreen> createState() => _AddressScreenState();
}

class _AddressScreenState extends State<AddressScreen> with Busy {
  final _c = TextEditingController();
  String? _err;

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Confirm your address',
        step: 7,
        of: 11,
        bottom: OfferAction(
          'Continue',
          trust: false,
          loading: busy,
          onPressed: () async {
            setState(() => _err = _c.text.trim().length < 10 ? 'Enter your full address with PIN code' : null);
            if (_err != null) return;
            if (await act(() => api.saveAddress(_c.text.trim())) && context.mounted) context.push('/loan/sanction-letter');
          },
        ),
        children: [
          const Text('Enter your current address', style: DnText.section),
          const SizedBox(height: 6),
          const Text('We couldn’t get your location, so please type where you live now.', style: DnText.body),
          const SizedBox(height: 16),
          DnField(label: 'Full address', controller: _c, error: _err, maxLines: 4, keyboardType: TextInputType.streetAddress, textCapitalization: TextCapitalization.words),
          const SizedBox(height: 6),
          Align(
            alignment: Alignment.centerLeft,
            // Location capture arrives with the platform work; the demo treats a retry as a fix.
            child: DnButton.text('Use my location instead', icon: Icons.my_location_rounded, onPressed: busy
                ? null
                : () async {
                    if (await act(api.simulate) && context.mounted) context.push('/loan/sanction-letter');
                  }),
          ),
        ],
      );
}
