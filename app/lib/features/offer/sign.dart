import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';
import 'choose.dart';
import 'common.dart';

/// B8 Agreement & signature: the agreement summary, then Aadhaar e-sign (simulated) or drawing.
class AgreementScreen extends StatefulWidget {
  const AgreementScreen({super.key});

  @override
  State<AgreementScreen> createState() => _AgreementScreenState();
}

class _AgreementScreenState extends State<AgreementScreen> with Busy {
  // CC-57: FLAG_SECURE (loan agreement)
  late Future<Quote> _f = api.chosenQuote();

  void _openAgreement(Quote q) => showDnSheet<void>(
        context,
        builder: (context) => Flexible(
          child: SingleChildScrollView(
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              Text('Loan agreement', style: DnText.section.copyWith(fontSize: 18)),
              const SizedBox(height: 10),
              QuoteBreakdown(q),
              const SizedBox(height: 12),
              Text(
                'You repay ${inr(q.repayPaise)} by 11:59 pm on day ${q.tenureDays}, counted from the day the money reaches your bank. Late charges, the lender’s details and every other term are in your Key Fact Statement.',
                style: DnText.body,
              ),
              const SizedBox(height: 16),
              DnButton.outline('Close', block: true, onPressed: () => Navigator.pop(context)),
            ]),
          ),
        ),
      );

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _f,
        builder: (context, s) {
          final wait = pending(context, s, () => setState(() { _f = api.chosenQuote(); }));
          final q = s.data;
          return StepScaffold(
            title: 'Agreement & signature',
            step: 8,
            of: 11,
            bottom: q == null
                ? null
                : Column(mainAxisSize: MainAxisSize.min, children: [
                    OfferAction('Sign with Aadhaar', loading: busy, onPressed: () async {
                      final ok = await act(api.sign, quiet: const {'ESIGN_UNAVAILABLE'});
                      if (!context.mounted) return;
                      if (ok) {
                        showDnToast(context, 'Agreement signed');
                        context.go('/loan/sanctioned');
                      } else if (lastError?.code == 'ESIGN_UNAVAILABLE') {
                        // The provider is down: drawing is the fallback the server offers.
                        showDnToast(context, lastError!.message);
                        context.push('/loan/sign-draw');
                      }
                    }),
                    DnButton.text('Can’t use Aadhaar? Sign by drawing', onPressed: busy ? null : () => context.push('/loan/sign-draw')),
                  ]),
            children: wait != null || q == null
                ? [?wait]
                : [
                    DnPanel(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                        const DnPanelHeader(title: 'Your loan agreement', meta: 'The saved PDF includes your signature, name, and the server-recorded date and time.'),
                        KvRow('Loan amount', inr(q.principalPaise), first: true),
                        KvRow('You receive in your bank', inr(q.netPaise)),
                        KvRow('You repay on day ${q.tenureDays}', inr(q.repayPaise)),
                        const SizedBox(height: 8),
                        DnButton.outline('Open agreement', icon: Icons.description_outlined, onPressed: () => _openAgreement(q)),
                      ]),
                    ),
                    const SizedBox(height: DnTokens.gap),
                    DnPanel(
                      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                        const DnPanelHeader(
                          title: 'Sign this agreement',
                          meta: 'Sign with Aadhaar e-sign. You’ll confirm with a one-time password sent to the mobile number registered with your Aadhaar, then come straight back here.',
                        ),
                        const _Tick('Legally valid Aadhaar e-signature'),
                        const _Tick('A signed copy is saved to your documents'),
                        if (busy) ...[const SizedBox(height: 10), const DnBanner(title: 'Confirming your signature', icon: Icons.hourglass_top_rounded)],
                      ]),
                    ),
                  ],
          );
        },
      );
}

class _Tick extends StatelessWidget {
  const _Tick(this.text);
  final String text;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Row(children: [
          const Icon(Icons.check_circle_rounded, size: 18, color: DnTokens.ok),
          const SizedBox(width: 10),
          Expanded(child: Text(text, style: DnText.body)),
        ]),
      );
}

/// B8b Sign by drawing: a finger signature pad.
class SignDrawScreen extends StatefulWidget {
  const SignDrawScreen({super.key});

  @override
  State<SignDrawScreen> createState() => _SignDrawScreenState();
}

class _SignDrawScreenState extends State<SignDrawScreen> with Busy {
  // CC-57: FLAG_SECURE (signature)
  final _strokes = <List<Offset>>[];
  bool _empty = false;

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Agreement & signature',
        step: 8,
        of: 11,
        bottom: Column(mainAxisSize: MainAxisSize.min, children: [
          OfferAction('Sign and continue', trust: false, loading: busy, onPressed: () async {
            if (_strokes.isEmpty) {
              setState(() => _empty = true);
              return;
            }
            final png = demoControls ? Uint8List(0) : await signaturePng(_strokes);
            if (await act(() => api.signDrawn(png)) && context.mounted) context.go('/loan/sanctioned');
          }),
          DnButton.text('Use Aadhaar e-sign instead', onPressed: busy ? null : () => context.canPop() ? context.pop() : context.go('/loan/sanction-letter')),
        ]),
        children: [
          DnPanel(
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              const DnPanelHeader(title: 'Sign this agreement', meta: 'Use your finger to sign inside the box.'),
              const _Tick('Your drawn signature is saved'),
              const _Tick('A signed copy is saved to your documents'),
              const SizedBox(height: 6),
              _SignPad(key: const Key('sign-pad'), strokes: _strokes, error: _empty, onChanged: () => setState(() => _empty = false)),
              if (_empty)
                const Padding(
                  padding: EdgeInsets.only(top: 7),
                  child: Text('Please draw your signature inside the box first', style: TextStyle(fontSize: 12, color: DnTokens.err)),
                ),
              Align(
                alignment: Alignment.centerLeft,
                child: DnButton.text('Clear signature', icon: Icons.refresh_rounded, onPressed: busy ? null : () => setState(_strokes.clear)),
              ),
            ]),
          ),
          if (busy) ...[const SizedBox(height: DnTokens.gap), const DnBanner(title: 'Saving signature…', icon: Icons.hourglass_top_rounded)],
        ],
      );
}

/// The drawn strokes as a PNG on white, cropped to the ink with a margin (`offer/esign`).
Future<Uint8List> signaturePng(List<List<Offset>> strokes) async {
  final points = strokes.expand((s) => s);
  var box = Rect.fromPoints(points.first, points.first);
  for (final p in points) {
    box = box.expandToInclude(Rect.fromPoints(p, p));
  }
  box = box.inflate(16);
  final rec = ui.PictureRecorder();
  final canvas = Canvas(rec)
    ..drawColor(Colors.white, BlendMode.src)
    ..translate(-box.left, -box.top);
  final ink = Paint()
    ..color = Colors.black
    ..strokeWidth = 2.6
    ..strokeCap = StrokeCap.round
    ..strokeJoin = StrokeJoin.round
    ..style = PaintingStyle.stroke;
  for (final s in strokes) {
    s.length == 1 ? canvas.drawCircle(s.first, 1.3, ink) : canvas.drawPath(Path()..addPolygon(s, false), ink);
  }
  final image = await rec.endRecording().toImage(box.width.ceil(), box.height.ceil());
  final data = await image.toByteData(format: ui.ImageByteFormat.png);
  return data!.buffer.asUint8List();
}

/// The drawing box. It claims the pointer at once so strokes don't scroll the page.
class _SignPad extends StatelessWidget {
  const _SignPad({super.key, required this.strokes, required this.error, required this.onChanged});
  final List<List<Offset>> strokes;
  final bool error;
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) => Semantics(
        label: 'Signature box. Draw your signature with your finger.',
        child: RawGestureDetector(
          gestures: {EagerGestureRecognizer: GestureRecognizerFactoryWithHandlers<EagerGestureRecognizer>(EagerGestureRecognizer.new, (_) {})},
          child: Listener(
            onPointerDown: (e) {
              strokes.add([e.localPosition]);
              onChanged();
            },
            onPointerMove: (e) {
              if (strokes.isEmpty) return;
              strokes.last.add(e.localPosition);
              onChanged();
            },
            child: Container(
              height: 180,
              decoration: BoxDecoration(
                color: DnTokens.paper,
                borderRadius: BorderRadius.circular(DnTokens.r),
                border: Border.all(color: error ? DnTokens.err : DnTokens.line2),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(DnTokens.r),
                child: CustomPaint(painter: _InkPainter(strokes, strokes.fold(0, (n, s) => n + s.length)), size: Size.infinite),
              ),
            ),
          ),
        ),
      );
}

class _InkPainter extends CustomPainter {
  _InkPainter(this.strokes, this.points);
  final List<List<Offset>> strokes;

  /// Total point count, so a repaint happens whenever a point is added.
  final int points;

  @override
  void paint(Canvas canvas, Size size) {
    final base = Paint()
      ..color = DnTokens.line2
      ..strokeWidth = 1;
    canvas.drawLine(Offset(16, size.height - 36), Offset(size.width - 16, size.height - 36), base);
    final p = Paint()
      ..color = DnTokens.ink
      ..strokeWidth = 2.6
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;
    for (final s in strokes) {
      if (s.length == 1) {
        canvas.drawCircle(s.first, 1.3, p..style = PaintingStyle.fill);
        p.style = PaintingStyle.stroke;
        continue;
      }
      canvas.drawPath(Path()..addPolygon(s, false), p);
    }
  }

  @override
  bool shouldRepaint(_InkPainter old) => old.points != points || old.strokes.length != strokes.length;
}

/// B9 Sanctioned. No back button: the signature is a legal act and can't be undone.
class SanctionedScreen extends StatefulWidget {
  const SanctionedScreen({super.key});

  @override
  State<SanctionedScreen> createState() => _SanctionedScreenState();
}

class _SanctionedScreenState extends State<SanctionedScreen> {
  late Future<Quote> _f = api.chosenQuote();

  @override
  Widget build(BuildContext context) => PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) context.go('/dashboard');
        },
        child: Scaffold(
          body: SafeArea(
            child: Stack(children: [
              FutureBuilder(
                future: _f,
                builder: (context, s) {
                  final wait = pending(context, s, () => setState(() { _f = api.chosenQuote(); }));
                  return Column(children: [
                    Expanded(
                      child: ListView(padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 0, DnTokens.gutter, 24), children: [
                        const DnStepHeader(title: 'Sanctioned', step: 9, of: 11),
                        const SizedBox(height: 28),
                        wait ?? _body(s.data!),
                      ]),
                    ),
                    if (wait == null)
                      Padding(
                        padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 12),
                        child: OfferAction('Continue', onPressed: () => context.push('/loan/disbursal-account')),
                      ),
                  ]);
                },
              ),
              const Positioned.fill(child: IgnorePointer(child: _Confetti())),
            ]),
          ),
        ),
      );

  Widget _body(Quote q) => Column(children: [
        TweenAnimationBuilder<double>(
          tween: Tween(begin: context.reduceMotion ? 1 : .4, end: 1),
          duration: context.motion(DnTokens.pop),
          curve: DnTokens.popCurve,
          builder: (_, v, child) => Transform.scale(scale: v, child: child),
          child: Container(
            width: 72,
            height: 72,
            decoration: const BoxDecoration(color: DnTokens.okBg, shape: BoxShape.circle),
            child: const Icon(Icons.celebration_rounded, size: 36, color: DnTokens.ok),
          ),
        ),
        const SizedBox(height: 16),
        Text('Your loan has been sanctioned!', textAlign: TextAlign.center, style: DnText.section.copyWith(fontSize: 20)),
        const SizedBox(height: 4),
        const Text('One more step to go.', textAlign: TextAlign.center, style: DnText.body),
        const SizedBox(height: 12),
        FittedBox(fit: BoxFit.scaleDown, child: Text(inr(q.principalPaise), style: DnText.display(56))),
        const SizedBox(height: 20),
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 280),
          child: const Row(children: [
            Expanded(flex: 2, child: _Stage(Icons.check_circle_outline_rounded, 'Sanctioned', DnTokens.ok)),
            Expanded(child: Divider(color: DnTokens.line2, thickness: 2)),
            Expanded(flex: 2, child: _Stage(Icons.payments_outlined, 'Disbursal', DnTokens.slate)),
          ]),
        ),
      ]);
}

class _Stage extends StatelessWidget {
  const _Stage(this.icon, this.label, this.color);
  final IconData icon;
  final String label;
  final Color color;

  @override
  Widget build(BuildContext context) => Column(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 20, color: color),
        const SizedBox(height: 4),
        FittedBox(fit: BoxFit.scaleDown, child: Text(label, style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600, color: color))),
      ]);
}

/// One confetti burst on arrival (B9). Nothing under reduced motion.
class _Confetti extends StatefulWidget {
  const _Confetti();

  @override
  State<_Confetti> createState() => _ConfettiState();
}

class _ConfettiState extends State<_Confetti> with SingleTickerProviderStateMixin {
  late final _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1800));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!context.reduceMotion && !_c.isAnimating && _c.value == 0) _c.forward();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => context.reduceMotion
      ? const SizedBox()
      : AnimatedBuilder(animation: _c, builder: (_, _) => _c.isCompleted ? const SizedBox() : CustomPaint(painter: _ConfettiPainter(_c.value)));
}

class _ConfettiPainter extends CustomPainter {
  _ConfettiPainter(this.t);
  final double t;

  static const _colors = [DnTokens.primary, DnTokens.mint, DnTokens.sun, DnTokens.violet, DnTokens.sky];

  @override
  void paint(Canvas canvas, Size size) {
    final rnd = math.Random(7);
    final p = Paint();
    for (var i = 0; i < 48; i++) {
      final x = rnd.nextDouble() * size.width;
      final drift = (rnd.nextDouble() - .5) * 80;
      final speed = .6 + rnd.nextDouble() * .6;
      final y = -20 + (size.height * .75) * Curves.easeOut.transform((t * speed).clamp(0, 1));
      canvas.save();
      canvas.translate(x + drift * t, y);
      canvas.rotate(t * 8 * (rnd.nextBool() ? 1 : -1));
      canvas.drawRect(const Rect.fromLTWH(-4, -2, 8, 4), p..color = _colors[i % _colors.length].withValues(alpha: 1 - t));
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(_ConfettiPainter old) => old.t != t;
}
