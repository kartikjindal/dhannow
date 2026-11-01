import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../core/api/api.dart';
import '../core/theme/theme.dart';
import '../core/widgets/widgets.dart';

/// Demo-only: jump the in-memory server to any journey state so every board can be shown.
/// Reached from Settings (D2) and by long-pressing the logo on Welcome. Removed with the demo API.
class DemoControls extends StatelessWidget {
  const DemoControls({super.key});

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: api,
        builder: (context, _) => DnPage(
          title: 'Demo states',
          subtitle: 'Pick a state, then the app opens where the server journey sends it.',
          back: true,
          children: [
            DnTile(
              padding: EdgeInsets.zero,
              child: Column(children: [
                for (final s in Scenario.values)
                  DnListRow(
                    first: s.index == 0,
                    title: s.label,
                    trailing: s == api.scenario ? const DnPill('Now', tone: DnTone.cobalt) : null,
                    onTap: () {
                      api.setScenario(s);
                      context.go('/');
                    },
                  ),
              ]),
            ),
            const SizedBox(height: 16),
            Row(children: [
              const Expanded(child: Text('Simulate no internet', style: DnText.strong)),
              DnSwitch(value: api.offline, label: 'Simulate no internet', onChanged: (v) => api.offline = v),
            ]),
            if (api.loan?.repayment == Repayment.pendingVerification) ...[
              const SizedBox(height: 12),
              DnButton.dark('Accountant verifies the payment', block: true, onPressed: () {
                api.demoVerifyPayment();
                context.go('/dashboard');
              }),
            ],
            const SizedBox(height: 12),
            DnButton.outline('UI kit gallery', block: true, onPressed: () => context.push('/kit')),
            const SizedBox(height: 12),
            const Text('Codes: any 6 digits pass, 000000 fails. Passwords: 6–10 characters with a letter, a digit and a symbol.', style: DnText.hint),
          ],
        ),
      );
}
