import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/theme.dart';
import '../theme/tokens.dart';

/// Kit `.fld`: label, white control (ink border + soft ring on focus), inline error.
/// [prefix] is the grey "+91" / "₹" cell; [obscure] adds the show/hide toggle.
class DnField extends StatefulWidget {
  const DnField({
    super.key,
    required this.label,
    this.controller,
    this.hint,
    this.prefix,
    this.suffix,
    this.error,
    this.helper,
    this.required = false,
    this.readOnly = false,
    this.obscure = false,
    this.keyboardType,
    this.inputFormatters,
    this.textCapitalization = TextCapitalization.none,
    this.autofillHints,
    this.maxLines = 1,
    this.onChanged,
    this.onSubmitted,
    this.textInputAction,
  });

  final String label;
  final TextEditingController? controller;
  final String? hint;
  final String? prefix;
  final Widget? suffix;
  final String? error;
  final String? helper;
  final bool required;
  final bool readOnly;
  final bool obscure;
  final TextInputType? keyboardType;
  final List<TextInputFormatter>? inputFormatters;
  final TextCapitalization textCapitalization;
  final Iterable<String>? autofillHints;
  final int maxLines;
  final ValueChanged<String>? onChanged;
  final ValueChanged<String>? onSubmitted;
  final TextInputAction? textInputAction;

  @override
  State<DnField> createState() => _DnFieldState();
}

class _DnFieldState extends State<DnField> {
  final _focus = FocusNode();
  bool _hidden = true;

  @override
  void initState() {
    super.initState();
    _focus.addListener(() => setState(() {}));
  }

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final bad = widget.error != null;
    final focused = _focus.hasFocus;
    final border = bad ? DnTokens.err : (focused ? DnTokens.ink : DnTokens.line2);
    return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
      Text.rich(
        TextSpan(text: widget.label, children: [if (widget.required) const TextSpan(text: ' *', style: TextStyle(color: DnTokens.err))]),
        style: DnText.label.copyWith(color: focused ? DnTokens.ink : null),
      ),
      const SizedBox(height: 7),
      AnimatedContainer(
        duration: const Duration(milliseconds: 250),
        curve: DnTokens.easeOut,
        constraints: const BoxConstraints(minHeight: 48),
        decoration: BoxDecoration(
          color: widget.readOnly ? DnTokens.grey100 : DnTokens.paper,
          borderRadius: BorderRadius.circular(DnTokens.rSm),
          border: Border.all(color: border),
          boxShadow: focused ? (bad ? DnTokens.ringErr : DnTokens.ringInk) : const [],
        ),
        clipBehavior: Clip.antiAlias,
        child: IntrinsicHeight(
          child: Row(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            if (widget.prefix != null)
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                alignment: Alignment.center,
                decoration: const BoxDecoration(color: DnTokens.grey100, border: Border(right: BorderSide(color: DnTokens.line))),
                child: Text(widget.prefix!, style: const TextStyle(fontSize: 15, color: DnTokens.slate)),
              ),
            Expanded(
              child: TextField(
                controller: widget.controller,
                focusNode: _focus,
                readOnly: widget.readOnly,
                obscureText: widget.obscure && _hidden,
                keyboardType: widget.keyboardType,
                inputFormatters: widget.inputFormatters,
                textCapitalization: widget.textCapitalization,
                autofillHints: widget.autofillHints,
                maxLines: widget.obscure ? 1 : widget.maxLines,
                minLines: 1,
                onChanged: widget.onChanged,
                onSubmitted: widget.onSubmitted,
                textInputAction: widget.textInputAction,
                style: TextStyle(fontSize: 15, color: widget.readOnly ? DnTokens.slate : DnTokens.ink),
                decoration: InputDecoration(
                  hintText: widget.hint,
                  hintStyle: const TextStyle(color: DnTokens.muted),
                  border: InputBorder.none,
                  isCollapsed: true,
                  contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
                ),
              ),
            ),
            if (widget.obscure)
              IconButton(
                onPressed: () => setState(() => _hidden = !_hidden),
                icon: Icon(_hidden ? Icons.visibility_outlined : Icons.visibility_off_outlined, size: 20, color: DnTokens.slate),
                tooltip: _hidden ? 'Show password' : 'Hide password',
              )
            else if (widget.suffix != null)
              Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Center(child: widget.suffix)),
          ]),
        ),
      ),
      if (bad)
        Padding(
          padding: const EdgeInsets.only(top: 7),
          child: Row(children: [
            const Icon(Icons.error_outline_rounded, size: 15, color: DnTokens.err),
            const SizedBox(width: 5),
            Expanded(child: Text(widget.error!, style: const TextStyle(fontSize: 12, color: DnTokens.err))),
          ]),
        )
      else if (widget.helper != null)
        Padding(padding: const EdgeInsets.only(top: 7), child: Text(widget.helper!, style: DnText.hint)),
    ]);
  }
}

enum DnOtpState { idle, error, ok }

/// Six OTP boxes over one hidden field, so paste and SMS autofill (CC-37, oneTimeCode) fill all
/// six at once. [onCompleted] fires when the sixth digit lands.
class DnOtpField extends StatefulWidget {
  const DnOtpField({super.key, required this.onCompleted, this.state = DnOtpState.idle, this.controller, this.length = 6, this.enabled = true});

  final ValueChanged<String> onCompleted;
  final DnOtpState state;
  final TextEditingController? controller;
  final int length;
  final bool enabled;

  @override
  State<DnOtpField> createState() => _DnOtpFieldState();
}

class _DnOtpFieldState extends State<DnOtpField> with SingleTickerProviderStateMixin {
  late final TextEditingController _c = widget.controller ?? TextEditingController();
  final _focus = FocusNode();
  late final AnimationController _shake = AnimationController(vsync: this, duration: const Duration(milliseconds: 350));

  @override
  void initState() {
    super.initState();
    _focus.addListener(() => setState(() {}));
  }

  @override
  void didUpdateWidget(DnOtpField old) {
    super.didUpdateWidget(old);
    if (widget.state == DnOtpState.error && old.state != DnOtpState.error && !context.reduceMotion) _shake.forward(from: 0);
  }

  @override
  void dispose() {
    if (widget.controller == null) _c.dispose();
    _focus.dispose();
    _shake.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final text = _c.text;
    final hidden = Opacity(
      opacity: 0,
      child: TextField(
        controller: _c,
        focusNode: _focus,
        enabled: widget.enabled,
        keyboardType: TextInputType.number,
        autofillHints: const [AutofillHints.oneTimeCode],
        inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(widget.length)],
        showCursor: false,
        enableInteractiveSelection: false,
        onChanged: (v) {
          setState(() {});
          if (v.length == widget.length) widget.onCompleted(v);
        },
      ),
    );
    final boxes = Row(
      children: [
        for (var i = 0; i < widget.length; i++) ...[
          if (i > 0) const SizedBox(width: 8),
          Expanded(child: _box(i < text.length ? text[i] : '', active: _focus.hasFocus && i == text.length.clamp(0, widget.length - 1))),
        ],
      ],
    );
    return Semantics(
      label: 'One-time code, ${widget.length} digits',
      value: '${text.length} entered',
      textField: true,
      child: AnimatedBuilder(
        animation: _shake,
        builder: (context, child) {
          final t = _shake.value;
          return Transform.translate(offset: Offset(math.sin(t * math.pi * 4) * 4 * (1 - t), 0), child: child);
        },
        child: GestureDetector(
          onTap: () => _focus.requestFocus(),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 340),
            child: Stack(children: [Positioned.fill(child: hidden), boxes]),
          ),
        ),
      ),
    );
  }

  Widget _box(String digit, {required bool active}) {
    final (border, bg) = switch (widget.state) {
      DnOtpState.error => (DnTokens.err, DnTokens.paper),
      DnOtpState.ok => (DnTokens.ok, DnTokens.okBg),
      DnOtpState.idle => (digit.isNotEmpty || active ? DnTokens.ink : DnTokens.line2, DnTokens.paper),
    };
    return AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      height: 56,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(DnTokens.rSm),
        border: Border.all(color: border),
        boxShadow: active && widget.state == DnOtpState.idle ? DnTokens.ringInk : const [],
      ),
      child: Text(digit, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w600, color: DnTokens.ink, fontFeatures: [FontFeature.tabularFigures()])),
    );
  }
}

/// Kit `.chk`: consent checkbox with a rich label.
class DnCheck extends StatelessWidget {
  const DnCheck({super.key, required this.value, required this.onChanged, required this.label});

  final bool value;
  final ValueChanged<bool>? onChanged;
  final InlineSpan label;

  @override
  Widget build(BuildContext context) => MergeSemantics(
        child: InkWell(
          onTap: onChanged == null ? null : () => onChanged!(!value),
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 4),
            child: Row(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Semantics(
                checked: value,
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  margin: const EdgeInsets.only(top: 1),
                  width: 20,
                  height: 20,
                  decoration: BoxDecoration(
                    color: value ? DnTokens.ink : DnTokens.paper,
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: value ? DnTokens.ink : DnTokens.line2, width: 1.5),
                  ),
                  child: value ? const Icon(Icons.check_rounded, size: 14, color: Colors.white) : null,
                ),
              ),
              const SizedBox(width: 11),
              Expanded(child: Text.rich(label, style: const TextStyle(fontSize: 13, height: 1.5, color: DnTokens.slate))),
            ]),
          ),
        ),
      );
}

/// Kit `.opt`: radio choice card (employment type, address proof, reason).
class DnChoiceCard extends StatelessWidget {
  const DnChoiceCard({super.key, required this.title, this.subtitle, this.icon, required this.selected, required this.onTap});

  final String title;
  final String? subtitle;
  final IconData? icon;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Semantics(
        inMutuallyExclusiveGroup: true,
        checked: selected,
        button: true,
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 200),
          decoration: BoxDecoration(
            color: DnTokens.paper,
            borderRadius: BorderRadius.circular(DnTokens.r),
            border: Border.all(color: selected ? DnTokens.ink : DnTokens.line),
            boxShadow: selected ? DnTokens.ringInk : const [],
          ),
          child: Material(
            type: MaterialType.transparency,
            borderRadius: BorderRadius.circular(DnTokens.r),
            clipBehavior: Clip.antiAlias,
            child: InkWell(
              onTap: onTap,
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Row(children: [
                  AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    width: 20,
                    height: 20,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: DnTokens.paper,
                      border: Border.all(color: selected ? DnTokens.ink : DnTokens.line2, width: selected ? 6 : 1.5),
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text(title, style: DnText.strong),
                      if (subtitle != null) Text(subtitle!, style: DnText.hint),
                    ]),
                  ),
                  if (icon != null)
                    Container(
                      width: 38,
                      height: 38,
                      decoration: const BoxDecoration(color: DnTokens.grey100, shape: BoxShape.circle),
                      child: Icon(icon, size: 20, color: DnTokens.ink),
                    ),
                ]),
              ),
            ),
          ),
        ),
      );
}

/// Kit `.sw`: cobalt switch (46 × 28).
class DnSwitch extends StatelessWidget {
  const DnSwitch({super.key, required this.value, required this.onChanged, required this.label});

  final bool value;
  final ValueChanged<bool>? onChanged;
  final String label;

  @override
  Widget build(BuildContext context) => Semantics(
        label: label,
        toggled: value,
        enabled: onChanged != null,
        child: GestureDetector(
          onTap: onChanged == null ? null : () => onChanged!(!value),
          child: Opacity(
            opacity: onChanged == null ? .45 : 1,
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 200),
              width: 46,
              height: 28,
              padding: const EdgeInsets.all(3),
              decoration: BoxDecoration(color: value ? DnTokens.primary : DnTokens.track, borderRadius: BorderRadius.circular(DnTokens.pill)),
              child: AnimatedAlign(
                duration: context.motion(const Duration(milliseconds: 250)),
                curve: DnTokens.easeOut,
                alignment: value ? Alignment.centerRight : Alignment.centerLeft,
                child: Container(
                  width: 22,
                  height: 22,
                  decoration: BoxDecoration(
                    color: Colors.white,
                    shape: BoxShape.circle,
                    boxShadow: [BoxShadow(color: const Color(0xFF111318).withValues(alpha: .25), blurRadius: 3, offset: const Offset(0, 1))],
                  ),
                ),
              ),
            ),
          ),
        ),
      );
}

/// Kit `.seg`: pill segmented control (login: OTP / Password).
class DnSegmented<T> extends StatelessWidget {
  const DnSegmented({super.key, required this.options, required this.value, required this.onChanged});

  final Map<T, String> options;
  final T value;
  final ValueChanged<T> onChanged;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.all(4),
        decoration: BoxDecoration(
          color: DnTokens.paper,
          borderRadius: BorderRadius.circular(DnTokens.pill),
          border: Border.all(color: DnTokens.line),
        ),
        child: Row(children: [
          for (final MapEntry(:key, value: text) in options.entries)
            Expanded(
              child: Semantics(
                selected: key == value,
                button: true,
                child: GestureDetector(
                  onTap: () => onChanged(key),
                  child: AnimatedContainer(
                    duration: const Duration(milliseconds: 200),
                    padding: const EdgeInsets.symmetric(vertical: 9, horizontal: 12),
                    alignment: Alignment.center,
                    decoration: BoxDecoration(color: key == value ? DnTokens.ink : Colors.transparent, borderRadius: BorderRadius.circular(DnTokens.pill)),
                    child: Text(
                      text,
                      style: TextStyle(fontSize: 13, fontWeight: key == value ? FontWeight.w600 : FontWeight.w500, color: key == value ? Colors.white : DnTokens.slate),
                    ),
                  ),
                ),
              ),
            ),
        ]),
      );
}
