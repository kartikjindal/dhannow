import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';
import 'feedback.dart';

/// Kit `.row`: icon disc, title, optional detail, trailing (chevron by default). Rows stack inside
/// a `DnPanel(padding: EdgeInsets.zero)` with hairlines between them.
class DnListRow extends StatelessWidget {
  const DnListRow({super.key, required this.title, this.detail, this.icon, this.trailing, this.onTap, this.first = false});

  final String title;
  final String? detail;
  final IconData? icon;
  final Widget? trailing;
  final VoidCallback? onTap;

  /// The first row in a group draws no top hairline.
  final bool first;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: DnTokens.line))),
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
            child: Row(children: [
              if (icon != null) ...[
                Container(
                  width: 38,
                  height: 38,
                  decoration: BoxDecoration(color: DnTokens.paper, shape: BoxShape.circle, border: Border.all(color: DnTokens.line), boxShadow: DnTokens.shXs),
                  child: Icon(icon, size: 20, color: DnTokens.ink),
                ),
                const SizedBox(width: 12),
              ],
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(title, style: DnText.strong.copyWith(height: 1.3)),
                  if (detail != null) Padding(padding: const EdgeInsets.only(top: 2), child: Text(detail!, style: DnText.hint)),
                ]),
              ),
              trailing ?? (onTap != null ? const Icon(Icons.chevron_right_rounded, color: DnTokens.muted) : const SizedBox()),
            ]),
          ),
        ),
      );
}

/// Document row (D6, KFS, receipts): tinted file badge, name, meta, trailing action.
class DnDocRow extends StatelessWidget {
  const DnDocRow({super.key, required this.name, this.meta, this.kind = 'PDF', this.onTap, this.first = false});

  final String name;
  final String? meta;
  final String kind;
  final VoidCallback? onTap;
  final bool first;

  @override
  Widget build(BuildContext context) => InkWell(
        onTap: onTap,
        child: Container(
          padding: EdgeInsets.only(top: first ? 0 : 12, bottom: 12),
          decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: DnTokens.line))),
          child: Row(children: [
            Container(
              width: 40,
              height: 48,
              alignment: Alignment.center,
              decoration: BoxDecoration(color: DnTokens.tint, borderRadius: BorderRadius.circular(10)),
              child: Text(kind, style: const TextStyle(fontSize: 9, fontWeight: FontWeight.w700, letterSpacing: .54, color: DnTokens.primary600)),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(name, style: DnText.strong),
                if (meta != null) Text(meta!, style: DnText.hint),
              ]),
            ),
            if (onTap != null) const Icon(Icons.download_rounded, size: 20, color: DnTokens.slate),
          ]),
        ),
      );
}

/// Kit `.copyrow`: dashed value box with a copy button (UPI ID, account no., IFSC).
class DnCopyRow extends StatelessWidget {
  const DnCopyRow({super.key, required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
        decoration: BoxDecoration(color: DnTokens.paper, borderRadius: BorderRadius.circular(DnTokens.rSm), border: Border.all(color: DnTokens.line2)),
        child: Row(children: [
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(label.toUpperCase(), style: const TextStyle(fontSize: 11, letterSpacing: .66, color: DnTokens.muted)),
              SelectableText(value, style: DnText.strong),
            ]),
          ),
          IconButton(
            tooltip: 'Copy $label',
            icon: const Icon(Icons.copy_rounded, size: 18, color: DnTokens.slate),
            onPressed: () async {
              await Clipboard.setData(ClipboardData(text: value));
              if (context.mounted) showDnToast(context, '$label copied');
            },
          ),
        ]),
      );
}
