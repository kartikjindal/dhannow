import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';

/// OFFLINE goes to board E3; anything else is a toast. The button that called stays tappable,
/// so the retry is one tap away.
void showApiError(BuildContext context, ApiException e) {
  if (e.code == 'OFFLINE') {
    context.push('/offline');
  } else {
    showDnToast(context, e.message, error: true);
  }
}

/// Loads [load] once, and again whenever the demo server notifies (server state is the only
/// truth: C12d rendering rule). Keeps the last good data on screen while it refetches.
class Loaded<T> extends StatefulWidget {
  const Loaded({super.key, required this.load, required this.builder});
  final Future<T> Function() load;
  final Widget Function(BuildContext context, T data) builder;

  @override
  State<Loaded<T>> createState() => _LoadedState<T>();
}

class _LoadedState<T> extends State<Loaded<T>> {
  late T _data;
  bool _has = false;
  ApiException? _error;
  int _seq = 0;

  @override
  void initState() {
    super.initState();
    _run();
    api.addListener(_run);
  }

  @override
  void dispose() {
    api.removeListener(_run);
    super.dispose();
  }

  Future<void> _run() async {
    final seq = ++_seq;
    try {
      final d = await widget.load();
      if (!mounted || seq != _seq) return;
      setState(() {
        _data = d;
        _has = true;
        _error = null;
      });
    } on ApiException catch (e) {
      if (mounted && seq == _seq) setState(() => _error = e);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_has) return widget.builder(context, _data);
    if (_error != null) {
      return DnStateView(
        icon: _error!.code == 'OFFLINE' ? Icons.cloud_off_rounded : Icons.error_outline_rounded,
        error: true,
        title: _error!.message,
        primary: 'Try again',
        onPrimary: () {
          setState(() => _error = null);
          _run();
        },
        secondary: 'Back to dashboard',
        onSecondary: () => context.go('/dashboard'),
      );
    }
    return const DnPanel(
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        DnSkeleton(width: 120, height: 14),
        SizedBox(height: 14),
        DnSkeleton(height: 40),
        SizedBox(height: 12),
        DnSkeleton(width: 200),
        SizedBox(height: 8),
        DnSkeleton(width: 160),
      ]),
    );
  }
}

/// Kit `.row` key/value line inside a panel ("Loan amount ₹1,000").
class Kv extends StatelessWidget {
  const Kv(this.label, this.value, {super.key, this.first = false, this.valueColor});
  final String label;
  final String value;
  final bool first;
  final Color? valueColor;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 9),
        decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: DnTokens.line))),
        child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Expanded(child: Text(label, style: DnText.body)),
          const SizedBox(width: 12),
          Flexible(fit: FlexFit.tight, child: Text(value, textAlign: TextAlign.end, style: DnText.money.copyWith(color: valueColor))),
        ]),
      );
}

/// Round icon disc that heads result screens (C7, C12c, C14).
class ResultDisc extends StatelessWidget {
  const ResultDisc({super.key, this.icon = Icons.check_rounded, this.tone = DnTone.ok});
  final IconData icon;
  final DnTone tone;

  @override
  Widget build(BuildContext context) {
    final (bg, fg) = switch (tone) {
      DnTone.warn => (DnTokens.warnBg, DnTokens.warn),
      DnTone.info => (DnTokens.infoBg, DnTokens.info),
      _ => (DnTokens.okBg, DnTokens.ok),
    };
    return Align(
      alignment: Alignment.centerLeft,
      child: TweenAnimationBuilder<double>(
        tween: Tween(begin: context.reduceMotion ? 1 : .6, end: 1),
        duration: context.motion(DnTokens.pop),
        curve: DnTokens.popCurve,
        builder: (_, s, child) => Transform.scale(scale: s, child: child),
        child: Container(width: 64, height: 64, decoration: BoxDecoration(color: bg, shape: BoxShape.circle), child: Icon(icon, size: 32, color: fg)),
      ),
    );
  }
}

/// C6 / C12b: where to pay. Values come from `GET /pay-to` (config), never typed in the app.
class PayToPanel extends StatelessWidget {
  const PayToPanel({super.key, required this.payTo, this.note, this.extra = const []});
  final PayTo payTo;

  /// A payment note that helps the accountant match the transfer (C12b).
  final String? note;
  final List<Widget> extra;

  @override
  Widget build(BuildContext context) => DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const DnPanelHeader(title: 'How to pay', meta: 'UPI or bank transfer to the lender account below'),
          DnCopyRow(label: 'UPI ID', value: payTo.upiId),
          const SizedBox(height: 8),
          DnCopyRow(label: 'Account name', value: payTo.accountName),
          const SizedBox(height: 8),
          DnCopyRow(label: 'Account number', value: payTo.accountNo),
          const SizedBox(height: 8),
          DnCopyRow(label: 'IFSC', value: '${payTo.ifsc} · ${payTo.bank}'),
          if (note != null) ...[const SizedBox(height: 8), DnCopyRow(label: 'Payment note', value: note!)],
          const SizedBox(height: 14),
          DnButton.outline('Pay with UPI app', icon: Icons.open_in_new_rounded, block: true, onPressed: () => showDnToast(context, 'Demo: your UPI app would open here')),
          const SizedBox(height: 10),
          const Text('Pay only to the verified lender account shown here. Never share an OTP or UPI PIN.', style: DnText.hint),
          ...extra,
        ]),
      );
}

final _utr = RegExp(r'^[A-Za-z0-9]{12,22}$');

/// UTR / UPI reference: 12–22 letters or digits.
String? utrError(String v) => _utr.hasMatch(v.trim()) ? null : 'Enter the 12 to 22 letter or number reference from your bank or UPI app';

/// Parses "1000" or "1000.50" into paise. Input parsing only; no amount is derived.
int? rupeesToPaise(String v) {
  final m = RegExp(r'^(\d{1,7})(?:\.(\d{1,2}))?$').firstMatch(v.trim());
  if (m == null) return null;
  return int.parse(m[1]!) * 100 + int.parse((m[2] ?? '0').padRight(2, '0'));
}

String _paiseText(int p) => p % 100 == 0 ? '${p ~/ 100}' : '${p ~/ 100}.${(p % 100).toString().padLeft(2, '0')}';

/// The "I’ve paid" form (C6, C12b): reference, bank credit date, amount (C6 only), screenshot.
/// [onSubmit] does the API call and the navigation; errors land on the field or in a toast.
class PaidForm extends StatefulWidget {
  const PaidForm({super.key, required this.buttonLabel, required this.onSubmit, this.maxPaise, this.firstDate, this.footer});

  final String buttonLabel;

  /// When set, an amount field is shown, prefilled with this and capped at it.
  final int? maxPaise;
  final DateTime? firstDate;
  final Widget? footer;
  final Future<void> Function({required String utr, required DateTime paidOn, int? paise, required bool screenshot}) onSubmit;

  @override
  State<PaidForm> createState() => _PaidFormState();
}

class _PaidFormState extends State<PaidForm> {
  final _ref = TextEditingController();
  late final _amount = TextEditingController(text: widget.maxPaise == null ? null : _paiseText(widget.maxPaise!));
  final _date = TextEditingController();
  late DateTime _paidOn = _today;
  bool _shot = false;
  bool _busy = false;
  String? _refError;
  String? _amountError;

  static DateTime get _today {
    final n = DateTime.now();
    return DateTime(n.year, n.month, n.day);
  }

  @override
  void initState() {
    super.initState();
    _date.text = dayMonth(_paidOn, year: true);
  }

  @override
  void dispose() {
    _ref.dispose();
    _amount.dispose();
    _date.dispose();
    super.dispose();
  }

  Future<void> _pickDate() async {
    final first = widget.firstDate ?? _today.subtract(const Duration(days: 60));
    final d = await showDatePicker(
      context: context,
      initialDate: _paidOn,
      firstDate: first.isAfter(_today) ? _today : first,
      lastDate: _today,
      helpText: 'Date the money left your bank',
    );
    if (d == null) return;
    setState(() {
      _paidOn = d;
      _date.text = dayMonth(d, year: true);
    });
  }

  Future<void> _submit() async {
    final refError = utrError(_ref.text);
    int? paise;
    String? amountError;
    if (widget.maxPaise != null) {
      paise = rupeesToPaise(_amount.text);
      if (paise == null || paise <= 0) {
        amountError = 'Enter the amount you paid';
      } else if (paise > widget.maxPaise!) {
        amountError = 'You owe ${inr(widget.maxPaise!)}. Enter up to that amount';
      }
    }
    setState(() {
      _refError = refError;
      _amountError = amountError;
    });
    if (refError != null || amountError != null) return;
    setState(() => _busy = true);
    try {
      await widget.onSubmit(utr: _ref.text.trim().toUpperCase(), paidOn: _paidOn, paise: paise, screenshot: _shot);
    } on ApiException catch (e) {
      if (!mounted) return;
      if (e.code == 'UTR_DUPLICATE') {
        setState(() => _refError = e.message);
      } else {
        showApiError(context, e);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => DnPanel(
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const DnPanelHeader(title: 'I’ve paid', meta: 'Tell us about your payment so our accounts team can verify it'),
          DnField(
            label: 'UPI reference / UTR',
            controller: _ref,
            required: true,
            hint: 'e.g. 429011238876',
            error: _refError,
            helper: 'We verify your payment against this reference before it reduces your balance',
            textCapitalization: TextCapitalization.characters,
            inputFormatters: [FilteringTextInputFormatter.allow(RegExp('[A-Za-z0-9]')), LengthLimitingTextInputFormatter(22)],
            onChanged: (_) {
              if (_refError != null) setState(() => _refError = null);
            },
          ),
          const SizedBox(height: 14),
          DnField(
            label: 'Paid on',
            controller: _date,
            readOnly: true,
            required: true,
            helper: 'The date your bank sent the money. This decides if you paid on time.',
            suffix: IconButton(tooltip: 'Choose date', icon: const Icon(Icons.calendar_today_rounded, size: 18, color: DnTokens.slate), onPressed: _pickDate),
          ),
          if (widget.maxPaise != null) ...[
            const SizedBox(height: 14),
            DnField(
              label: 'Amount',
              controller: _amount,
              prefix: '₹',
              required: true,
              error: _amountError,
              keyboardType: const TextInputType.numberWithOptions(decimal: true),
              inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9.]'))],
              helper: 'Up to ${inr(widget.maxPaise!)}. A part payment keeps the loan open and doesn’t count for the next level.',
            ),
          ],
          const SizedBox(height: 14),
          const Text('Payment screenshot (optional)', style: DnText.label),
          const SizedBox(height: 7),
          _ShotTile(attached: _shot, onTap: () => setState(() => _shot = !_shot)),
          const SizedBox(height: 18),
          DnButton(widget.buttonLabel, block: true, loading: _busy, onPressed: _submit),
          const SizedBox(height: 10),
          widget.footer ?? const Text('Payments are confirmed manually by our accounts team. Your balance updates once verified.', textAlign: TextAlign.center, style: DnText.hint),
        ]),
      );
}

/// ponytail: simulated attach (no file_picker in pubspec yet); add the system photo picker (CC-56)
/// when uploads land.
class _ShotTile extends StatelessWidget {
  const _ShotTile({required this.attached, required this.onTap});
  final bool attached;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        button: true,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(DnTokens.rSm),
          child: Container(
            constraints: const BoxConstraints(minHeight: 64),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: attached ? DnTokens.okBg : DnTokens.paper,
              borderRadius: BorderRadius.circular(DnTokens.rSm),
              border: Border.all(color: attached ? DnTokens.okLine : DnTokens.line2, width: 1.5),
            ),
            child: Row(mainAxisAlignment: MainAxisAlignment.center, children: [
              Icon(attached ? Icons.check_circle_rounded : Icons.add_photo_alternate_outlined, size: 20, color: attached ? DnTokens.ok : DnTokens.slate),
              const SizedBox(width: 8),
              Flexible(child: Text(attached ? 'Screenshot attached · tap to remove' : 'Attach screenshot · image under 8 MB', style: DnText.strong)),
            ]),
          ),
        ),
      );
}

/// Panel title with a status pill that wraps under it on narrow screens (CC-58), instead of a
/// [DnPanelHeader] trailing slot that cannot shrink.
class TitlePill extends StatelessWidget {
  const TitlePill(this.title, {super.key, this.pill});
  final String title;
  final Widget? pill;

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.only(bottom: 14),
        child: Wrap(spacing: 10, runSpacing: 6, crossAxisAlignment: WrapCrossAlignment.center, children: [Text(title, style: DnText.section), ?pill]),
      );
}

/// Banner with its action as a button underneath; the kit banner's inline action has no room at
/// 320dp × 2.0.
class ActionBanner extends StatelessWidget {
  const ActionBanner({super.key, required this.title, this.body, this.tone = DnBannerTone.info, this.icon, this.action, this.onAction});
  final String title;
  final String? body;
  final DnBannerTone tone;
  final IconData? icon;
  final String? action;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        DnBanner(title: title, body: body, tone: tone, icon: icon),
        if (action != null) ...[const SizedBox(height: 8), Align(alignment: Alignment.centerLeft, child: DnButton(action!, size: DnButtonSize.sm, onPressed: onAction))],
      ]);
}
