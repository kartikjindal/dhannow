import 'package:flutter/material.dart';

import '../../core/theme/theme.dart';
import '../../core/theme/tokens.dart';
import '../../core/widgets/widgets.dart';

/// Board E2: a finished step reopened in view mode. Read-only rows with Edit; Edit turns the screen
/// back into its form, and only then does Save call the API. Locked steps (OTP, consent,
/// DigiLocker, selfie, penny-drop, e-sign, AutoPay) pass no [onEdit] and show Verified instead,
/// because re-running them burns a limit. Continue on the host screen sends nothing when nothing
/// changed; it just moves to the step the server says is next.
class StepReviewCard extends StatelessWidget {
  const StepReviewCard({super.key, required this.rows, this.title = 'Your details', this.onEdit});

  final List<(String label, String value)> rows;
  final String title;
  final VoidCallback? onEdit;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      DnPill(onEdit == null ? 'Verified' : 'Saved', tone: DnTone.ok, dot: true),
      const SizedBox(height: 10),
      Text('You’ve already done this step', style: DnText.section.copyWith(fontSize: 20)),
      const SizedBox(height: 14),
      DnPanel(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(child: Text(title, style: DnText.strong)),
                if (onEdit != null) DnButton.text('Edit', icon: Icons.edit_rounded, onPressed: onEdit),
              ],
            ),
            for (final (label, value) in rows)
              Container(
                padding: const EdgeInsets.symmetric(vertical: 10),
                decoration: const BoxDecoration(
                  border: Border(top: BorderSide(color: DnTokens.line)),
                ),
                child: Wrap(
                  alignment: WrapAlignment.spaceBetween,
                  spacing: 12,
                  runSpacing: 2,
                  children: [
                    Text(label, style: DnText.body),
                    Text(value, style: DnText.strong),
                  ],
                ),
              ),
          ],
        ),
      ),
    ],
  );
}
