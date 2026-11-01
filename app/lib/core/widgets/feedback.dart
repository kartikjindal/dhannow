import 'package:flutter/material.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'buttons.dart';

enum DnBannerTone { info, ok, warn, err, extension, dark }

/// Kit `.bn` banner: icon, bold title, body, optional action.
class DnBanner extends StatelessWidget {
  const DnBanner({super.key, required this.title, this.body, this.tone = DnBannerTone.info, this.icon, this.action, this.onAction});

  final String title;
  final String? body;
  final DnBannerTone tone;
  final IconData? icon;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final (bg, line, ic, titleColor, bodyColor, defIcon) = switch (tone) {
      DnBannerTone.info => (DnTokens.tint, DnTokens.primarySoft, DnTokens.primary600, DnTokens.ink, DnTokens.slate, Icons.info_outline_rounded),
      DnBannerTone.ok => (DnTokens.okBg, DnTokens.okLine, DnTokens.ok, DnTokens.ink, DnTokens.slate, Icons.check_circle_outline_rounded),
      DnBannerTone.warn => (DnTokens.warnBg, DnTokens.warnLine, DnTokens.warn, DnTokens.warnInk, DnTokens.slate, Icons.schedule_rounded),
      DnBannerTone.err => (DnTokens.errBg, DnTokens.errLine, DnTokens.err, DnTokens.errInk, DnTokens.slate, Icons.error_outline_rounded),
      DnBannerTone.extension => (DnTokens.infoBg, DnTokens.infoLine, DnTokens.info, DnTokens.infoInk, DnTokens.slate, Icons.event_repeat_rounded),
      DnBannerTone.dark => (DnTokens.ink, DnTokens.ink, Colors.white, Colors.white, DnTokens.onDarkMuted, Icons.info_outline_rounded),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(color: bg, borderRadius: BorderRadius.circular(DnTokens.r), border: Border.all(color: line)),
      child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Padding(padding: const EdgeInsets.only(top: 1), child: Icon(icon ?? defIcon, size: 20, color: ic)),
        const SizedBox(width: 11),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(title, style: TextStyle(fontSize: 13.5, fontWeight: FontWeight.w600, color: titleColor)),
            if (body != null) Text(body!, style: TextStyle(fontSize: 13, height: 1.45, color: bodyColor)),
            // Under the text, not beside it, so it never squeezes the copy at 320dp × 2.0.
            if (action != null)
              TextButton(
                onPressed: onAction,
                style: TextButton.styleFrom(
                  padding: EdgeInsets.zero,
                  minimumSize: const Size(0, 36),
                  foregroundColor: tone == DnBannerTone.dark ? Colors.white : DnTokens.primary600,
                  textStyle: const TextStyle(fontSize: 13, fontWeight: FontWeight.w600),
                ),
                child: Text(action!),
              ),
          ]),
        ),
      ]),
    );
  }
}

/// Kit bottom sheet: surface, 28px top radius, drag handle. Used for BackConfirm (E1), pickers
/// and explainers.
Future<T?> showDnSheet<T>(BuildContext context, {required WidgetBuilder builder, bool dismissible = true}) => showModalBottomSheet<T>(
      context: context,
      isDismissible: dismissible,
      enableDrag: dismissible,
      isScrollControlled: true,
      useSafeArea: true,
      backgroundColor: DnTokens.surface,
      barrierColor: DnTokens.scrim,
      shape: const RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(DnTokens.rXl))),
      builder: (context) => Padding(
        padding: EdgeInsets.fromLTRB(18, 10, 18, 18 + MediaQuery.viewInsetsOf(context).bottom),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              margin: const EdgeInsets.only(bottom: 16),
              decoration: BoxDecoration(color: DnTokens.line2, borderRadius: BorderRadius.circular(4)),
            ),
          ),
          builder(context),
        ]),
      ),
    );

/// Kit confirm dialog: icon disc, title, body, a primary and a secondary action. Resolves true
/// when [confirm] is tapped.
Future<bool> showDnConfirm(
  BuildContext context, {
  required String title,
  String? body,
  required String confirm,
  String cancel = 'Cancel',
  bool destructive = false,
  IconData icon = Icons.warning_amber_rounded,
}) async {
  final ok = await showDialog<bool>(
    context: context,
    barrierColor: DnTokens.scrim,
    builder: (context) => Dialog(
      backgroundColor: DnTokens.surface,
      insetPadding: const EdgeInsets.symmetric(horizontal: 18),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(DnTokens.rXl)),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 22, 20, 18),
        child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Center(
            child: Container(
              width: 52,
              height: 52,
              decoration: BoxDecoration(color: destructive ? DnTokens.errBg : DnTokens.tint, shape: BoxShape.circle),
              child: Icon(icon, color: destructive ? DnTokens.err : DnTokens.primary600),
            ),
          ),
          const SizedBox(height: 14),
          Text(title, textAlign: TextAlign.center, style: DnText.section.copyWith(fontSize: 17)),
          if (body != null) ...[const SizedBox(height: 6), Text(body, textAlign: TextAlign.center, style: DnText.body)],
          const SizedBox(height: 18),
          destructive
              ? DnButton.danger(confirm, block: true, onPressed: () => Navigator.pop(context, true))
              : DnButton(confirm, block: true, onPressed: () => Navigator.pop(context, true)),
          const SizedBox(height: 8),
          DnButton.ghost(cancel, block: true, onPressed: () => Navigator.pop(context, false)),
        ]),
      ),
    ),
  );
  return ok ?? false;
}

/// Kit toast: ink pill at the bottom with a mint tick (or a red mark for errors).
void showDnToast(BuildContext context, String message, {bool error = false}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(SnackBar(
      behavior: SnackBarBehavior.floating,
      backgroundColor: Colors.transparent,
      elevation: 0,
      padding: EdgeInsets.zero,
      content: Center(
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(color: DnTokens.ink, borderRadius: BorderRadius.circular(DnTokens.pill), boxShadow: DnTokens.shMd),
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            Icon(error ? Icons.error_outline_rounded : Icons.check_circle_rounded, size: 18, color: error ? const Color(0xFFFF9EA1) : DnTokens.mint),
            const SizedBox(width: 8),
            Flexible(child: Text(message, style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w500, color: Colors.white))),
          ]),
        ),
      ),
    ));
}

/// Empty or error state: icon disc, title, body, and the way out (wireframe rule: every error
/// has a retry and a way out).
class DnStateView extends StatelessWidget {
  const DnStateView({super.key, required this.icon, required this.title, this.body, this.error = false, this.primary, this.onPrimary, this.secondary, this.onSecondary});

  final IconData icon;
  final String title;
  final String? body;
  final bool error;
  final String? primary;
  final VoidCallback? onPrimary;
  final String? secondary;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 10),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Container(
            width: 60,
            height: 60,
            margin: const EdgeInsets.only(bottom: 12),
            decoration: BoxDecoration(color: error ? DnTokens.errBg : DnTokens.grey100, shape: BoxShape.circle),
            child: Icon(icon, size: 26, color: error ? DnTokens.err : DnTokens.ink),
          ),
          Text(title, textAlign: TextAlign.center, style: DnText.section.copyWith(fontSize: 16)),
          if (body != null) ...[
            const SizedBox(height: 6),
            ConstrainedBox(constraints: const BoxConstraints(maxWidth: 260), child: Text(body!, textAlign: TextAlign.center, style: DnText.body.copyWith(fontSize: 13))),
          ],
          if (primary != null) ...[const SizedBox(height: 16), DnButton(primary!, size: DnButtonSize.sm, onPressed: onPrimary)],
          if (secondary != null) ...[const SizedBox(height: 4), DnButton.text(secondary!, onPressed: onSecondary)],
        ]),
      );
}

/// Kit `.sk` shimmer block. Static grey under reduced motion.
class DnSkeleton extends StatefulWidget {
  const DnSkeleton({super.key, this.width, this.height = 12, this.radius = 8});

  final double? width;
  final double height;
  final double radius;

  @override
  State<DnSkeleton> createState() => _DnSkeletonState();
}

class _DnSkeletonState extends State<DnSkeleton> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: const Duration(milliseconds: 1400));

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    context.reduceMotion ? _c.stop() : _c.repeat();
  }

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Semantics(
        label: 'Loading',
        child: AnimatedBuilder(
          animation: _c,
          builder: (_, _) => Container(
            width: widget.width,
            height: widget.height,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(widget.radius),
              gradient: LinearGradient(
                begin: Alignment(-3 + 4 * (1 - _c.value), 0),
                end: Alignment(-1 + 4 * (1 - _c.value), 0),
                colors: const [DnTokens.grey200, DnTokens.grey100, DnTokens.grey200],
              ),
            ),
          ),
        ),
      );
}
