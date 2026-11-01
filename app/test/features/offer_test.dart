import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:dhannow/features/offer/routes.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

const _screens = [
  '/loan/amount',
  '/loan/repayment-date',
  '/loan/digilocker',
  '/loan/digilocker/upload',
  '/loan/references',
  '/loan/summary',
  '/loan/kfs',
  '/loan/selfie',
  '/loan/selfie/capture',
  '/loan/address',
  '/loan/sanction-letter',
  '/loan/sign-draw',
  '/loan/sanctioned',
  '/loan/disbursal-account',
  '/loan/disbursal-account/other',
  '/loan/mandate',
  '/loan/status',
];

Future<GoRouter> _pump(WidgetTester t, String at, {double width = 412, double scale = 1, bool reduceMotion = false}) async {
  t.view.physicalSize = Size(width * 3, 900 * 3);
  t.view.devicePixelRatio = 3;
  addTearDown(t.view.reset);
  final router = GoRouter(navigatorKey: rootNav, initialLocation: at, routes: [
    ...offerRoutes,
    for (final p in ['/dashboard', '/offline', '/camera-denied']) GoRoute(path: p, builder: (_, _) => Scaffold(body: Text('stub $p'))),
  ]);
  await t.pumpWidget(MaterialApp.router(
    theme: dnTheme(),
    routerConfig: router,
    builder: (context, child) => MediaQuery(
      data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale), disableAnimations: reduceMotion),
      child: child!,
    ),
  ));
  await _settle(t);
  return router;
}

/// Some screens loop an animation while waiting (money on the way), so pump frames instead of settling.
Future<void> _settle(WidgetTester t) async {
  for (var i = 0; i < 25; i++) {
    await t.pump(const Duration(milliseconds: 100));
  }
}

String _path(GoRouter r) => r.routerDelegate.currentConfiguration.last.matchedLocation;

Future<void> _tap(WidgetTester t, Finder f) async {
  if (f.evaluate().isEmpty) await t.scrollUntilVisible(f, 300, scrollable: find.byType(Scrollable).first);
  await t.ensureVisible(f);
  await t.pump();
  await t.tap(f);
  await _settle(t);
}

void main() {
  setUp(() {
    api.latency = Duration.zero;
    api.offline = false;
    api.setScenario(Scenario.offer);
  });

  // CC-58: every screen at 320dp × 2.0 and 412dp × 1.0, scrolled through so overflows fail.
  for (final (width, scale) in const [(320.0, 2.0), (412.0, 1.0)]) {
    for (final path in _screens) {
      testWidgets('$path lays out at ${width.toInt()}dp, text ×$scale', (t) async {
        await _pump(t, path, width: width, scale: scale);
        expect(find.byType(DnStateView), findsNothing);
        final list = find.byType(Scrollable).first;
        for (var i = 0; i < 8; i++) {
          await t.drag(list, const Offset(0, -500), warnIfMissed: false);
          await t.pump();
        }
      });
    }
    for (final s in [Scenario.disbursal, Scenario.active]) {
      testWidgets('status in ${s.name} lays out at ${width.toInt()}dp, text ×$scale', (t) async {
        api.setScenario(s);
        await _pump(t, '/loan/status', width: width, scale: scale);
        expect(find.textContaining(s == Scenario.disbursal ? 'on the way' : 'Money received'), findsOneWidget);
      });
    }
  }

  testWidgets('B9 and B12 settle under reduced motion', (t) async {
    api.setScenario(Scenario.disbursal);
    await _pump(t, '/loan/status', reduceMotion: true);
    await t.pumpAndSettle();
    expect(find.text('Confirming with the bank'), findsWidgets);
    await _pump(t, '/loan/sanctioned', reduceMotion: true);
    await t.pumpAndSettle();
    expect(find.text('Your loan has been sanctioned!'), findsOneWidget);
  });

  testWidgets('B1 shows the server quote, locks amounts above the level and saves the choice', (t) async {
    final r = await _pump(t, '/loan/amount');
    expect(find.text('₹742'), findsOneWidget);
    expect(find.text('− ₹100'), findsOneWidget);
    await _tap(t, find.text('₹2,000'));
    expect(find.text('Continue with ₹1,000'), findsOneWidget);
    await _tap(t, find.text('Continue with ₹1,000'));
    expect(api.chosenPaise, 100000);
    expect(_path(r), '/loan/repayment-date');
  });

  testWidgets('a failed load shows the error with a retry', (t) async {
    api.offline = true;
    await _pump(t, '/loan/amount');
    expect(find.text('Couldn’t load this step'), findsOneWidget);
    api.offline = false;
    await _tap(t, find.text('Try again'));
    expect(find.text('Continue with ₹1,000'), findsOneWidget);
  });

  testWidgets('references are validated, then saved', (t) async {
    final r = await _pump(t, '/loan/references');
    await _tap(t, find.text('Continue'));
    expect(find.text('Enter their full name'), findsNWidgets(2));
    expect(find.text('Choose a relation'), findsNWidgets(2));
    final fields = find.byType(TextField);
    await t.enterText(fields.at(0), 'Ravi Kumar');
    await t.enterText(fields.at(1), '9876543210');
    await t.enterText(fields.at(2), 'Neha Singh');
    await t.enterText(fields.at(3), '9876543210');
    for (final rel in ['Friend', 'Sibling']) {
      await _tap(t, find.text('Select…').first);
      await _tap(t, find.text(rel));
    }
    await _tap(t, find.text('Continue'));
    expect(find.text('Your two references must have different mobile numbers'), findsOneWidget);
    await t.enterText(fields.at(3), '9123456780');
    await _tap(t, find.text('Continue'));
    expect(_path(r), '/loan/summary');
  });

  testWidgets('KFS must be acknowledged before continuing', (t) async {
    final r = await _pump(t, '/loan/kfs');
    expect(find.text('Lender of record (from config)'), findsOneWidget);
    await _tap(t, find.text('Continue'));
    expect(_path(r), '/loan/kfs');
    await _tap(t, find.byType(DnCheck));
    await _tap(t, find.text('Continue'));
    expect(_path(r), '/loan/selfie');
  });

  testWidgets('selfie steps through three poses, then uploads', (t) async {
    final r = await _pump(t, '/loan/selfie/capture');
    for (var i = 0; i < 3; i++) {
      await _tap(t, find.text('Capture'));
    }
    expect(_path(r), '/loan/sanction-letter');
  });

  testWidgets('signing offline goes to the offline screen', (t) async {
    final r = await _pump(t, '/loan/sanction-letter');
    api.offline = true;
    await _tap(t, find.text('Sign with Aadhaar'));
    expect(_path(r), '/offline');
  });

  testWidgets('drawn signature is required, then signs', (t) async {
    final r = await _pump(t, '/loan/sign-draw');
    await _tap(t, find.text('Sign and continue'));
    expect(find.text('Please draw your signature inside the box first'), findsOneWidget);
    await t.drag(find.byKey(const Key('sign-pad')), const Offset(120, 30));
    await _tap(t, find.text('Sign and continue'));
    expect(_path(r), '/loan/sanctioned');
  });

  testWidgets('confirming the account starts disbursal and skips eNACH', (t) async {
    final r = await _pump(t, '/loan/disbursal-account');
    expect(find.text('HDFC Bank'), findsOneWidget);
    await _tap(t, find.text('Confirm this account'));
    expect(api.scenario, Scenario.disbursal);
    expect(_path(r), '/loan/status');
    expect(find.text('DISBURSAL CONFIRMING'), findsOneWidget);
    expect(find.text('₹742 on the way to HDFC Bank ••5678'), findsOneWidget);
  });
}
