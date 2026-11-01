import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/links.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

/// ponytail: build-time constant; swap for package_info_plus if the version must come from the APK.
const appVersion = String.fromEnvironment('APP_VERSION', defaultValue: '0.1.0');

/// Loads once (future kept in state), shows skeletons, and on failure the error with a retry and
/// a way out.
class Loaded<T> extends StatefulWidget {
  const Loaded({super.key, required this.load, required this.builder});

  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data) builder;

  @override
  State<Loaded<T>> createState() => _LoadedState<T>();
}

class _LoadedState<T> extends State<Loaded<T>> {
  late Future<T> _future = widget.load();

  @override
  Widget build(BuildContext context) => FutureBuilder<T>(
        future: _future,
        builder: (context, s) {
          if (s.hasError) {
            final e = s.error;
            return DnStateView(
              icon: Icons.cloud_off_rounded,
              error: true,
              title: 'Couldn’t load this',
              body: e is ApiException ? e.message : 'Something went wrong. Try again.',
              primary: 'Try again',
              onPrimary: () => setState(() {
                _future = widget.load();
              }),
              secondary: 'Go back',
              onSecondary: () => context.canPop() ? context.pop() : context.go('/dashboard'),
            );
          }
          if (s.connectionState != ConnectionState.done) {
            return const Column(children: [
              DnSkeleton(height: 64, radius: 22),
              SizedBox(height: 12),
              DnSkeleton(height: 140, radius: 22),
              SizedBox(height: 12),
              DnSkeleton(height: 96, radius: 22),
            ]);
          }
          return widget.builder(context, s.data as T);
        },
      );
}

/// Caps label over a panel of rows (DnListRow or [InfoRow]); rows set `first` themselves.
class Section extends StatelessWidget {
  const Section(this.title, {super.key, required this.children, this.padRows = false});

  final String? title;
  final List<Widget> children;

  /// Key/value rows need side padding; DnListRows bring their own.
  final bool padRows;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 18),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          if (title != null) Padding(padding: const EdgeInsets.only(bottom: 8, left: 4), child: DnCaps(title!)),
          DnPanel(
            padding: padRows ? const EdgeInsets.symmetric(horizontal: 16, vertical: 2) : EdgeInsets.zero,
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: children),
          ),
        ]),
      );
}

/// Board `.row`: label left, bold value right; wraps at large text.
class InfoRow extends StatelessWidget {
  const InfoRow(this.label, this.value, {super.key, this.first = false});

  final String label;
  final String value;
  final bool first;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: DnTokens.line))),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Expanded(flex: 2, child: Text(label, style: DnText.body)),
            const SizedBox(width: 12),
            Expanded(flex: 3, child: Text(value, textAlign: TextAlign.end, style: DnText.strong.copyWith(height: 1.5))),
          ]),
        ),
      );
}

/// Rows from (label, value) pairs.
List<Widget> infoRows(List<(String, String)> rows) => [for (final (i, (l, v)) in rows.indexed) InfoRow(l, v, first: i == 0)];

void openSite(BuildContext context, String path) => openSitePage(context, path);

Future<void> copyText(BuildContext context, String label, String value) async {
  await Clipboard.setData(ClipboardData(text: value));
  if (context.mounted) showDnToast(context, '$label copied');
}

/// dhannow.com page rows ("Opens dhannow.com in an in-app browser tab").
List<Widget> siteLinks(BuildContext context, List<(String, String)> pages) => [
      for (final (i, (title, path)) in pages.indexed)
        DnListRow(
          first: i == 0,
          title: title,
          detail: '${Brand.site}/$path',
          trailing: const Icon(Icons.open_in_new_rounded, size: 18, color: DnTokens.muted),
          onTap: () => openSite(context, path),
        ),
    ];

const legalPages = [
  ('Terms and conditions', 'terms.html'),
  ('Privacy policy', 'privacy.html'),
  ('Fair practices code', 'fair-practices.html'),
  ('Cookie policy', 'cookie-policy.html'),
];
