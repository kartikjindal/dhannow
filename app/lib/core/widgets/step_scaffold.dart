import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'buttons.dart';
import 'feedback.dart';
import 'nav.dart';
import 'surface.dart';

/// Every journey step (wireframe rules E1–E2): step header, scrolling body, a sticky bottom
/// action, and back goes through the "Leave this step?" sheet instead of dropping input.
class StepScaffold extends StatelessWidget {
  const StepScaffold({
    super.key,
    required this.title,
    this.subtitle,
    this.step,
    this.of,
    required this.children,
    this.bottom,
    this.confirmBack = true,
    this.trailing,
    this.onBack,
  });

  final String title;
  final String? subtitle;
  final int? step;
  final int? of;
  final List<Widget> children;

  /// Usually a block [DnButton]; pinned above the keyboard.
  final Widget? bottom;

  /// False on screens with nothing to lose (results, read-only views).
  final bool confirmBack;
  final Widget? trailing;

  /// Defaults to popping, or going home when there is nothing to pop.
  final VoidCallback? onBack;

  void _back(BuildContext context) {
    if (onBack != null) return onBack!();
    if (context.canPop()) return context.pop();
    context.go('/dashboard');
  }

  Future<void> _maybeBack(BuildContext context) async {
    if (!confirmBack) return _back(context);
    final choice = await showBackConfirm(context);
    if (!context.mounted) return;
    if (choice == BackChoice.back) _back(context);
    if (choice == BackChoice.exit) context.go('/dashboard');
  }

  @override
  Widget build(BuildContext context) => PopScope(
        canPop: false,
        onPopInvokedWithResult: (didPop, _) {
          if (!didPop) _maybeBack(context);
        },
        child: Scaffold(
          body: SafeArea(
            child: Column(children: [
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 0, DnTokens.gutter, 24),
                  children: [
                    DnStepHeader(title: title, subtitle: subtitle, step: step, of: of, onBack: () => _maybeBack(context), trailing: trailing),
                    const SizedBox(height: 20),
                    ...children,
                  ],
                ),
              ),
              if (bottom != null)
                Padding(padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 12), child: bottom),
            ]),
          ),
        ),
      );
}

enum BackChoice { stay, back, exit }

/// Board E1.
Future<BackChoice> showBackConfirm(BuildContext context) async =>
    await showDnSheet<BackChoice>(
      context,
      builder: (context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('Leave this step?', style: DnText.section.copyWith(fontSize: 18)),
        const SizedBox(height: 6),
        const Text('Steps you finished are saved. You won’t need to verify them again, and you can continue from Home any time.', style: DnText.body),
        const SizedBox(height: 18),
        DnButton('Stay on this step', block: true, onPressed: () => Navigator.pop(context, BackChoice.stay)),
        const SizedBox(height: 8),
        DnButton.outline('Go back', block: true, onPressed: () => Navigator.pop(context, BackChoice.back)),
        const SizedBox(height: 4),
        DnButton.ghost('Save and exit to dashboard', block: true, onPressed: () => Navigator.pop(context, BackChoice.exit)),
      ]),
    ) ??
    BackChoice.stay;

/// Plain page for tab roots and read-only screens: Bebas title, optional back pill and actions.
class DnPage extends StatelessWidget {
  const DnPage({super.key, required this.title, this.subtitle, required this.children, this.back = false, this.actions = const [], this.bottom, this.onRefresh});

  final String title;
  final String? subtitle;
  final List<Widget> children;
  final bool back;
  final List<Widget> actions;
  final Widget? bottom;
  final Future<void> Function()? onRefresh;

  @override
  Widget build(BuildContext context) {
    final list = ListView(
      padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 24),
      children: [
        Row(children: [
          if (back) ...[const DnBackPill(), const SizedBox(width: 10)],
          Expanded(child: Semantics(header: true, child: Text(title, style: DnText.title))),
          ...actions,
        ]),
        if (subtitle != null) ...[const SizedBox(height: 6), Text(subtitle!, style: DnText.body)],
        const SizedBox(height: 18),
        ...children,
      ],
    );
    return Scaffold(
      body: SafeArea(
        child: Column(children: [
          Expanded(child: onRefresh == null ? list : RefreshIndicator(onRefresh: onRefresh!, child: list)),
          if (bottom != null) Padding(padding: const EdgeInsets.fromLTRB(DnTokens.gutter, 8, DnTokens.gutter, 12), child: bottom),
        ]),
      ),
    );
  }
}

/// Back pill that pops, or goes home when there is nothing to pop.
class DnBackPill extends StatelessWidget {
  const DnBackPill({super.key});

  @override
  Widget build(BuildContext context) =>
      DnIconPill(icon: Icons.arrow_back_rounded, tooltip: 'Back', size: 34, onPressed: () => context.canPop() ? context.pop() : context.go('/dashboard'));
}
