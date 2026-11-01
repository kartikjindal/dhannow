import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';


/// Offer-journey calls the demo [Api] lacks: DigiLocker, Aadhaar and bank-proof uploads and the
/// eNACH hand-off are simulated with the api's latency and offline switch.
extension OfferApi on Api {
  /// The amount picked on B1, or the level maximum when a step is opened directly.
  Future<Quote> chosenQuote() async => quote(chosenPaise ?? (await creditLevel()).maxPaise);

  Future<void> simulate() async {
    if (latency > Duration.zero) await Future<void>.delayed(latency);
    if (offline) throw const ApiException('OFFLINE');
  }
}

/// Primary-button state for one API call: locks while in flight, sends OFFLINE to E3 and shows
/// every other error as a toast (the button stays as the retry; back is the way out).
mixin Busy<T extends StatefulWidget> on State<T> {
  bool busy = false;

  Future<bool> act(Future<void> Function() call) async {
    setState(() => busy = true);
    try {
      await call();
      return true;
    } on ApiException catch (e) {
      if (mounted) {
        e.code == 'OFFLINE' ? context.push('/offline') : showDnToast(context, e.message, error: true);
      }
      return false;
    } finally {
      if (mounted) setState(() => busy = false);
    }
  }
}

/// Skeletons while a screen loads, or the error with a retry and a way out; null once loaded.
Widget? pending(BuildContext context, AsyncSnapshot<Object?> s, VoidCallback retry) {
  if (s.hasData) return null;
  if (s.hasError) {
    final msg = s.error is ApiException ? (s.error as ApiException).message : const ApiException('UNKNOWN').message;
    return DnStateView(
      icon: Icons.cloud_off_rounded,
      error: true,
      title: 'Couldn’t load this step',
      body: msg,
      primary: 'Try again',
      onPrimary: retry,
      secondary: 'Go to dashboard',
      onSecondary: () => context.go('/dashboard'),
    );
  }
  return const Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
    DnSkeleton(height: 120, radius: DnTokens.rLg),
    SizedBox(height: DnTokens.gap),
    DnSkeleton(width: 180),
    SizedBox(height: 8),
    DnSkeleton(height: 60, radius: DnTokens.r),
  ]);
}

/// Label and text value on one line (KFS, summary, bank details). Wraps instead of overflowing.
class KvRow extends StatelessWidget {
  const KvRow(this.label, this.value, {super.key, this.first = false});
  final String label;
  final String value;
  final bool first;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 9),
        decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: DnTokens.line))),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(flex: 5, child: Text(label, style: DnText.body.copyWith(height: 1.4))),
          const SizedBox(width: 10),
          Expanded(flex: 6, child: Text(value, textAlign: TextAlign.end, style: DnText.money)),
        ]),
      );
}

/// Primary action with the boards' trust line under it.
class OfferAction extends StatelessWidget {
  const OfferAction(this.label, {super.key, required this.onPressed, this.loading = false, this.trust = true});
  final String label;
  final VoidCallback? onPressed;
  final bool loading;
  final bool trust;

  @override
  Widget build(BuildContext context) => Column(mainAxisSize: MainAxisSize.min, children: [
        DnButton(label, block: true, loading: loading, onPressed: onPressed),
        if (trust) ...[
          const SizedBox(height: 8),
          Wrap(alignment: WrapAlignment.center, spacing: 14, runSpacing: 2, children: [
            for (final (icon, text) in const [
              (Icons.lock_outline_rounded, 'Secure & encrypted'),
              (Icons.visibility_outlined, 'Transparent terms'),
              (Icons.verified_outlined, 'No fee paid beforehand'),
            ])
              Row(mainAxisSize: MainAxisSize.min, children: [
                Icon(icon, size: 13, color: DnTokens.muted),
                const SizedBox(width: 4),
                Flexible(child: Text(text, style: DnText.hint.copyWith(fontSize: 11, color: DnTokens.muted))),
              ]),
          ]),
        ],
      ]);
}

/// A photo/file slot (E10 Aadhaar sides, B10 bank proof).
///
/// ponytail: no picker plugin yet, so a tap marks the slot added; the system photo picker (CC-56)
/// and client-side compression (CC-49) arrive with the upload API.
class UploadSlot extends StatelessWidget {
  const UploadSlot({super.key, required this.label, required this.added, required this.onTap});
  final String label;
  final bool added;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        label: added ? '$label, added' : 'Add $label',
        excludeSemantics: true,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(DnTokens.r),
          child: Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              color: added ? DnTokens.okBg : DnTokens.paper,
              borderRadius: BorderRadius.circular(DnTokens.r),
              border: Border.all(color: added ? DnTokens.okLine : DnTokens.line2),
            ),
            child: Row(children: [
              Icon(added ? Icons.check_circle_rounded : Icons.add_photo_alternate_outlined, color: added ? DnTokens.ok : DnTokens.slate),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(label, style: DnText.strong),
                  Text(added ? 'Added' : 'Choose file', style: DnText.hint.copyWith(color: added ? DnTokens.okInk : DnTokens.primary600, fontWeight: FontWeight.w600)),
                ]),
              ),
            ]),
          ),
        ),
      );
}
