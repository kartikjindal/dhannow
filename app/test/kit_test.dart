import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:dhannow/dev/kit_gallery.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

Widget _wrap(Widget child) => MaterialApp(theme: dnTheme(), home: Scaffold(body: Padding(padding: const EdgeInsets.all(16), child: child)));

void main() {
  testWidgets('a loading button ignores taps (no double submit)', (t) async {
    var taps = 0;
    await t.pumpWidget(_wrap(DnButton('Continue', loading: true, onPressed: () => taps++)));
    await t.tap(find.byType(DnButton));
    expect(taps, 0);
    expect(find.byType(CircularProgressIndicator), findsOneWidget);
  });

  testWidgets('OTP fires once all six digits are in, including a paste', (t) async {
    String? code;
    await t.pumpWidget(_wrap(DnOtpField(onCompleted: (v) => code = v)));
    await t.enterText(find.byType(TextField), '4829');
    expect(code, isNull);
    await t.enterText(find.byType(TextField), '482913');
    await t.pump();
    expect(code, '482913');
    expect(find.text('3'), findsOneWidget);
  });

  testWidgets('OTP keeps digits only', (t) async {
    final c = TextEditingController();
    await t.pumpWidget(_wrap(DnOtpField(controller: c, onCompleted: (_) {})));
    await t.enterText(find.byType(TextField), '12a4');
    expect(c.text, isNot(contains('a')));
  });

  testWidgets('ladder chips above the credit level are locked', (t) async {
    int? picked;
    await t.pumpWidget(_wrap(DnLadderChips(amountsPaise: const [100000, 200000, 300000], maxPaise: 200000, selected: null, onSelected: (a) => picked = a)));
    await t.tap(find.text('₹3,000'));
    expect(picked, isNull);
    await t.tap(find.text('₹2,000'));
    expect(picked, 200000);
    expect(find.byIcon(Icons.lock_outline_rounded), findsOneWidget);
  });

  testWidgets('countdown ring words due today and late from server days', (t) async {
    await t.pumpWidget(_wrap(const Column(children: [DnCountdownRing(daysLeft: 9), DnCountdownRing(daysLeft: 0), DnCountdownRing(daysLeft: -3)])));
    expect(find.text('days left'), findsOneWidget);
    expect(find.text('due today'), findsOneWidget);
    expect(find.text('days late'), findsOneWidget);
  });

  // CC-58: small screens and large font scale must not overflow.
  for (final width in [320.0, 412.0]) {
    for (final scale in [1.0, 1.3, 2.0]) {
      testWidgets('kit gallery lays out at ${width.toInt()}dp, text ×$scale', (t) async {
        t.view.physicalSize = Size(width * 3, 2400 * 3);
        t.view.devicePixelRatio = 3;
        addTearDown(t.view.reset);
        await t.pumpWidget(MaterialApp(
          theme: dnTheme(),
          home: MediaQuery.withClampedTextScaling(minScaleFactor: scale, maxScaleFactor: scale, child: const KitGallery()),
        ));
        await t.pumpAndSettle();
        // Scroll through the whole list so every section builds and lays out.
        final list = find.byType(Scrollable).first;
        for (var i = 0; i < 40; i++) {
          await t.drag(list, const Offset(0, -600));
          await t.pump();
        }
        // Any overflow is a FlutterError, which fails the test with the offending widget named.
      });
    }
  }

  testWidgets('copy row puts the value on the clipboard', (t) async {
    String? copied;
    t.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
      if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String;
      return null;
    });
    await t.pumpWidget(_wrap(const DnCopyRow(label: 'UPI ID', value: 'dhannow@upi')));
    await t.tap(find.byIcon(Icons.copy_rounded));
    await t.pump();
    expect(copied, 'dhannow@upi');
    expect(find.text('UPI ID copied'), findsOneWidget);
  });
}
