import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/format.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/theme/tokens.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'common.dart';
import 'profile_screen.dart' show loanStatus;

const _escalation = 'Not resolved? Escalate to the lender’s grievance officer (named in your KFS), then RBI CMS (cms.rbi.org.in).';

/// Answers are the dhannow.com/faq.html text, so app and website never differ.
const _faqs = [
  ('How much can I borrow?', 'The first loan may be up to ₹1,000, subject to successful verification, underwriting, and approval. Progression requires full repayment on time or earlier, along with the applicable eligibility and underwriting checks. A higher limit is not automatic or guaranteed.'),
  ('What does it cost?', 'Under the stated product structure, applicable processing fees, GST on the processing fee, and 14-day interest are deducted upfront. The final amounts applicable to your loan will be displayed in your approved offer and KFS before acceptance.'),
  ('How is interest charged?', 'Under the stated 14-day product structure, interest is calculated at 1% per day on the original principal for 14 calendar days, equivalent to 14% of the principal for the full 14-day period.'),
  ('When do I repay?', 'Your repayment due date will be communicated in your loan documents, KFS, repayment schedule, or applicable digital loan records. Customers should verify the exact due date and pay by the specified deadline.'),
  ('Can I pay early?', 'Early repayment may be made through the available repayment facility, subject to the applicable loan terms and payment instructions.'),
  ('What happens if I’m late?', 'Missing the due date may result in an overdue account and may affect your future borrowing eligibility. Any applicable overdue charges, collection procedures, or credit bureau reporting will be governed by your loan agreement and applicable law. Please contact customer support promptly if you anticipate difficulty making a payment.'),
  ('How do I make a payment?', 'Pay by UPI or bank transfer and submit the transaction reference. Our accounts team verifies it before your balance updates.'),
];

/// Board D3 Support. Renders with no API call, so it works logged out.
class SupportScreen extends StatelessWidget {
  const SupportScreen({super.key});

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'Support',
        subtitle: 'We’re here to help. Reach us directly, raise a grievance, or browse the FAQ.',
        back: true,
        children: [
          Section(null, children: [
            DnListRow(
              first: true,
              icon: Icons.call_rounded,
              title: 'Call or WhatsApp',
              detail: '${Brand.supportPhone} · ${Brand.supportHours}',
              trailing: const Icon(Icons.copy_rounded, size: 18, color: DnTokens.slate),
              onTap: () => copyText(context, 'Phone number', Brand.supportPhone),
            ),
            DnListRow(
              icon: Icons.mail_rounded,
              title: 'Email us',
              detail: '${Brand.supportEmail} · We reply within one business day.',
              trailing: const Icon(Icons.copy_rounded, size: 18, color: DnTokens.slate),
              onTap: () => copyText(context, 'Email', Brand.supportEmail),
            ),
          ]),
          const Padding(padding: EdgeInsets.only(bottom: 8, left: 4), child: DnCaps('Raise a grievance')),
          DnPanel(
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              const Text('Acknowledged within 2 working days, reviewed within 5, resolved within 15 calendar days where practicable.', style: DnText.body),
              const SizedBox(height: 12),
              const Text('Grievance officer: ${Brand.grievanceName}', style: DnText.strong),
              const SizedBox(height: 8),
              const DnCopyRow(label: 'Phone', value: Brand.grievancePhone),
              const SizedBox(height: 8),
              const DnCopyRow(label: 'Email', value: Brand.grievanceEmail),
              const SizedBox(height: 12),
              const Text(_escalation, style: DnText.hint),
              if (api.signedIn) ...[
                const SizedBox(height: 14),
                DnButton.outline('My complaints', block: true, onPressed: () => context.push('/complaints')),
              ],
            ]),
          ),
          const SizedBox(height: 18),
          Section('Help & FAQ', children: [for (final (i, (q, a)) in _faqs.indexed) _Faq(q, a, first: i == 0)]),
          Section(null, children: siteLinks(context, const [
            ('Help and FAQ', 'faq.html'),
            ('Grievance redressal', 'grievance.html'),
            ('Contact us', 'contact.html'),
          ])),
        ],
      );
}

class _Faq extends StatelessWidget {
  const _Faq(this.q, this.a, {this.first = false});
  final String q;
  final String a;
  final bool first;

  @override
  Widget build(BuildContext context) => DecoratedBox(
        decoration: BoxDecoration(border: first ? null : const Border(top: BorderSide(color: DnTokens.line))),
        child: ExpansionTile(
          title: Text(q, style: DnText.strong),
          shape: const Border(),
          collapsedShape: const Border(),
          iconColor: DnTokens.ink,
          collapsedIconColor: DnTokens.slate,
          expandedAlignment: Alignment.centerLeft,
          childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
          expansionAnimationStyle: context.reduceMotion ? AnimationStyle.noAnimation : null,
          children: [Text(a, style: DnText.body)],
        ),
      );
}

/// Board D4 How we keep you safe.
class TrustScreen extends StatelessWidget {
  const TrustScreen({super.key});

  static const _points = [
    (Icons.lock_rounded, 'Only you can log in', 'One-time codes expire in 5 minutes and work once. Repeated wrong tries are blocked. Fingerprint unlock on this phone.'),
    (Icons.verified_user_rounded, 'Identity checked with official sources', 'Aadhaar through DigiLocker, PAN validation, and a selfie matched to your Aadhaar photo, so no one can borrow in your name.'),
    (Icons.account_balance_rounded, 'Money goes only to your verified account', 'We send ₹1 first and check the account holder’s name matches yours.'),
    (Icons.draw_rounded, 'You sign with Aadhaar e-Sign', 'Every cost is shown in the Key Fact Statement before you sign. Copies stay in the app.'),
    (Icons.people_alt_rounded, 'Approval and payout are done by different people', 'The person who approves a loan can’t also release the money, and every action is logged with who and when.'),
    (Icons.folder_rounded, 'Your documents are stored encrypted', 'Download links expire after 15 minutes. Your consent is recorded with date and time.'),
    (Icons.phonelink_lock_rounded, 'We never ask for your OTP or PIN', 'We don’t read your contacts, messages or gallery. Report suspicious calls to support.'),
  ];

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'How we keep you safe',
        back: true,
        children: [
          Section(null, children: [for (final (i, (icon, t, d)) in _points.indexed) DnListRow(first: i == 0, icon: icon, title: t, detail: d)]),
          Section(null, children: siteLinks(context, const [('Privacy policy', 'privacy.html'), ('Fair practices code', 'fair-practices.html')])),
        ],
      );
}

/// Board D5 My complaints.
class ComplaintsScreen extends StatefulWidget {
  const ComplaintsScreen({super.key});

  @override
  State<ComplaintsScreen> createState() => _ComplaintsScreenState();
}

class _ComplaintsScreenState extends State<ComplaintsScreen> {
  int _gen = 0; // bumped to reload after a new complaint

  Future<void> _new() async {
    await context.push('/complaints/new');
    if (mounted) setState(() => _gen++);
  }

  static List<DnStep> _steps(String status) {
    final review = status == 'In review', done = status == 'Resolved';
    return [
      DnStep('Acknowledged', detail: 'Within 2 working days', state: review || done ? DnStepState.done : DnStepState.now),
      DnStep('Under review', detail: 'Within 5 working days', state: done ? DnStepState.done : (review ? DnStepState.now : DnStepState.todo)),
      DnStep('Resolved', detail: 'Within 15 calendar days', state: done ? DnStepState.done : DnStepState.todo),
    ];
  }

  @override
  Widget build(BuildContext context) => DnPage(
        title: 'My complaints',
        back: true,
        bottom: DnButton('Raise a new complaint', block: true, icon: Icons.add_rounded, onPressed: _new),
        children: [
          Loaded<List<Complaint>>(
            key: ValueKey(_gen),
            load: api.complaints,
            builder: (context, list) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              if (list.isEmpty)
                const DnStateView(icon: Icons.forum_rounded, title: 'No complaints', body: 'If something isn’t right, raise a complaint and we’ll get back to you.'),
              for (final c in list) ...[
                DnPanel(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                    Row(children: [
                      Expanded(child: Text(c.id, style: DnText.strong)),
                      DnPill(c.status, tone: switch (c.status) { 'Resolved' => DnTone.ok, 'In review' => DnTone.info, _ => DnTone.cobalt }),
                    ]),
                    const SizedBox(height: 4),
                    Text('${c.subject}${c.loanNo == null ? '' : ' · Loan ${c.loanNo}'} · raised ${dayMonth(c.at)}, ${clock(c.at)}', style: DnText.hint),
                    const SizedBox(height: 14),
                    DnTimeline(steps: _steps(c.status)),
                  ]),
                ),
                const SizedBox(height: 12),
              ],
              const Text(
                'Not happy with the answer? Escalate to the Grievance Officer, ${Brand.grievanceName} (${Brand.grievanceEmail}), then to the lender, then RBI CMS (cms.rbi.org.in).',
                style: DnText.hint,
              ),
            ]),
          ),
        ],
      );
}

/// Board D5 "Raise a new complaint".
class NewComplaintScreen extends StatefulWidget {
  const NewComplaintScreen({super.key});

  @override
  State<NewComplaintScreen> createState() => _NewComplaintScreenState();
}

class _NewComplaintScreenState extends State<NewComplaintScreen> {
  static const _categories = ['Payment not reflecting', 'Charges or amount', 'Collection call', 'Documents', 'Something else'];

  final _message = TextEditingController();
  late final Future<List<Loan>> _loans = api.loans();
  String? _category;
  Loan? _loan;
  bool _busy = false;

  @override
  void dispose() {
    _message.dispose();
    super.dispose();
  }

  bool get _ready => _category != null && _message.text.trim().length >= 10;

  Future<void> _submit() async {
    setState(() => _busy = true);
    try {
      // ponytail: raiseComplaint has no loan field yet, so the picked loan leads the message.
      final text = _message.text.trim();
      final c = await api.raiseComplaint(subject: _category!, body: _loan == null ? text : 'Loan ${_loan!.accountNo}\n$text');
      if (!mounted) return;
      showDnToast(context, 'Complaint raised. Reference ${c.id}');
      context.canPop() ? context.pop() : context.go('/complaints');
    } on ApiException catch (e) {
      if (mounted) showDnToast(context, '${e.message} Tap Submit to retry.', error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => StepScaffold(
        title: 'Raise a new complaint',
        confirmBack: false,
        bottom: DnButton('Submit complaint', block: true, loading: _busy, onPressed: _ready ? _submit : null),
        children: [
          const DnCaps('What is it about?'),
          const SizedBox(height: 8),
          for (final cat in _categories) ...[
            DnChoiceCard(title: cat, selected: _category == cat, onTap: () => setState(() => _category = cat)),
            const SizedBox(height: 8),
          ],
          FutureBuilder<List<Loan>>(
            future: _loans,
            builder: (context, s) {
              final loans = s.data ?? const <Loan>[];
              if (loans.isEmpty) return const SizedBox.shrink();
              return Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
                const SizedBox(height: 10),
                const DnCaps('Which loan? (optional)'),
                const SizedBox(height: 8),
                for (final l in loans) ...[
                  DnChoiceCard(
                    title: 'Loan ${l.accountNo}',
                    subtitle: '${loanStatus(l).$1} · ${inr(l.quote.principalPaise)}',
                    selected: _loan == l,
                    onTap: () => setState(() => _loan = _loan == l ? null : l),
                  ),
                  const SizedBox(height: 8),
                ],
              ]);
            },
          ),
          const SizedBox(height: 10),
          DnField(
            label: 'Tell us what happened',
            controller: _message,
            required: true,
            maxLines: 5,
            helper: 'At least 10 characters.',
            textCapitalization: TextCapitalization.sentences,
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 16),
          const Text('A complaint doesn’t pause your repayment. You get a reference number and updates by SMS and email.', style: DnText.hint),
        ],
      );
}
