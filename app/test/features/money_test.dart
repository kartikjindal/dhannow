import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:dhannow/features/money/emi.dart';
import 'package:dhannow/features/money/extension_status_card.dart';
import 'package:dhannow/features/money/routes.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

/// Routes owned by other features, stubbed so cross-links resolve.
const _others = ['/dashboard', '/offline', '/payment-rejected', '/support', '/loan/amount', '/complaints/new', '/loan-account'];

late GoRouter _router;

Future<void> _pump(WidgetTester t, String path, {double width = 412, double scale = 1, Object? extra}) async {
  t.view.physicalSize = Size(width * 3, 800 * 3);
  t.view.devicePixelRatio = 3;
  addTearDown(t.view.reset);
  _router = GoRouter(
    navigatorKey: rootNav,
    initialLocation: path,
    initialExtra: extra,
    routes: [
      repayTab,
      loansTab,
      ...moneyRoutes,
      GoRoute(path: '/card', builder: (_, _) => Scaffold(body: ListView(padding: const EdgeInsets.all(16), children: [ExtensionStatusCard(loan: api.loan!)]))),
      for (final p in _others) GoRoute(path: p, builder: (_, _) => Scaffold(body: Text('stub $p'))),
    ],
  );
  await t.pumpWidget(MaterialApp.router(
    theme: dnTheme(),
    routerConfig: _router,
    builder: (context, child) => MediaQuery.withClampedTextScaling(minScaleFactor: scale, maxScaleFactor: scale, child: child!),
  ));
  await t.pumpAndSettle();
}

/// Scrolls the page top to bottom so every widget lays out; an overflow fails the test.
Future<void> _scroll(WidgetTester t) async {
  final list = find.byType(Scrollable).first;
  for (var i = 0; i < 12; i++) {
    await t.drag(list, const Offset(0, -500), warnIfMissed: false);
    await t.pump();
  }
}

/// Scrolls the lazy page list until [f] is built, then brings it on screen.
Future<Finder> _show(WidgetTester t, Finder f) async {
  if (f.evaluate().isEmpty) await t.scrollUntilVisible(f, 300, scrollable: find.byType(Scrollable).first);
  await t.ensureVisible(f.first);
  await t.pumpAndSettle();
  return f.first;
}

Future<void> _enter(WidgetTester t, String label, String text) async {
  await t.enterText(await _show(t, find.byWidgetPredicate((w) => w is DnField && w.label == label)), text);
}

Future<void> _tap(WidgetTester t, Finder f) async {
  f = await _show(t, f);
  await t.pumpAndSettle();
  await t.tap(f);
  await t.pumpAndSettle();
}

String get _path => _router.routerDelegate.currentConfiguration.last.matchedLocation;

void main() {
  setUp(() {
    api.latency = Duration.zero;
    api.offline = false;
    api.setScenario(Scenario.active);
  });

  // CC-58: every screen and state at 320dp × 2.0 and 412dp × 1.0.
  final screens = <(String, Scenario, Object?)>[
    ('/repay', Scenario.active, null),
    ('/repay', Scenario.overdue, null),
    ('/repay', Scenario.extensionOffered, null),
    ('/repay', Scenario.extended, null),
    ('/repay', Scenario.pendingVerification, null),
    ('/repay', Scenario.closed, null),
    ('/repay/sent', Scenario.pendingVerification, null),
    ('/loans', Scenario.active, null),
    ('/loans', Scenario.extended, null),
    ('/loans', Scenario.newUser, null),
    ('/loans/closed', Scenario.closed, null),
    ('/loans/closed', Scenario.overdue, null),
    ('/transactions', Scenario.active, null),
    ('/transactions', Scenario.newUser, null),
    ('/reloan', Scenario.closed, null),
    ('/reloan', Scenario.active, null),
    ('/extension/confirm', Scenario.extensionOffered, null),
    ('/extension/confirm', Scenario.active, null),
    ('/extension/pay', Scenario.extensionOffered, null),
    ('/extension/done', Scenario.extended, null),
    ('/emi', Scenario.overdue, null),
    ('/emi', Scenario.overdue, demoEmiOffer),
    ('/card', Scenario.extended, null),
    ('/card', Scenario.extensionOffered, null),
  ];
  for (final (path, scenario, extra) in screens) {
    for (final (w, s) in [(320.0, 2.0), (412.0, 1.0)]) {
      testWidgets('$path (${scenario.name}${extra == null ? '' : ', preview'}) lays out at ${w.toInt()}dp × $s', (t) async {
        api.setScenario(scenario);
        await _pump(t, path, width: w, scale: s, extra: extra);
        await _scroll(t);
      });
    }
  }

  testWidgets('C6: no open loan shows Nothing to repay with Borrow', (t) async {
    api.setScenario(Scenario.closed);
    await _pump(t, '/repay');
    expect(find.text('Nothing to repay'), findsOneWidget);
    await _tap(t, find.widgetWithText(DnButton, 'Borrow'));
    expect(_path, '/dashboard');
  });

  testWidgets('C6: payment being verified replaces the form', (t) async {
    api.setScenario(Scenario.pendingVerification);
    await _pump(t, '/repay');
    expect(find.text('Your payment is being verified'), findsOneWidget);
    expect(find.text('UPI reference / UTR'), findsNothing);
  });

  testWidgets('C6 → C7: a valid reference records the payment', (t) async {
    await _pump(t, '/repay');
    await _enter(t, 'UPI reference / UTR', '429011238876');
    await _tap(t, find.widgetWithText(DnButton, 'I’ve paid'));
    expect(_path, '/repay/sent');
    expect(api.loan!.repayment, Repayment.pendingVerification);
    expect(find.text('Payment recorded'), findsOneWidget);
    expect(find.text('PENDING VERIFICATION'), findsOneWidget);
  });

  testWidgets('C6: a short reference is refused before any call', (t) async {
    await _pump(t, '/repay');
    await _enter(t, 'UPI reference / UTR', '4290');
    await _tap(t, find.widgetWithText(DnButton, 'I’ve paid'));
    expect(_path, '/repay');
    expect(find.textContaining('12 to 22'), findsOneWidget);
    expect(api.loan!.repayment, Repayment.unpaid);
  });

  testWidgets('C6: an already used reference shows the message on the field', (t) async {
    await _pump(t, '/repay');
    await _enter(t, 'UPI reference / UTR', '000000000000');
    await _tap(t, find.widgetWithText(DnButton, 'I’ve paid'));
    expect(_path, '/repay');
    expect(find.text(const ApiException('UTR_DUPLICATE').message), findsOneWidget);
  });

  testWidgets('C6: an amount above what is owed is refused', (t) async {
    await _pump(t, '/repay');
    await _enter(t, 'UPI reference / UTR', '429011238876');
    await _enter(t, 'Amount', '5000');
    await _tap(t, find.widgetWithText(DnButton, 'I’ve paid'));
    expect(find.textContaining('Enter up to that amount'), findsOneWidget);
    expect(api.loan!.repayment, Repayment.unpaid);
  });

  testWidgets('C12 → C12b → C12c: OTP, pay, extended', (t) async {
    api.setScenario(Scenario.extensionOffered);
    await _pump(t, '/extension/confirm');
    expect(find.text('EXTENSION 1 OF 3'), findsOneWidget);
    final confirm = find.byWidgetPredicate((w) => w is DnButton && w.label.startsWith('Confirm and pay'));
    await _show(t, confirm);
    expect(t.widget<DnButton>(confirm).onPressed, isNull, reason: 'consent first');
    await _tap(t, find.byType(DnCheck));
    await t.enterText(await _show(t, find.descendant(of: find.byType(DnOtpField), matching: find.byType(TextField))), '482913');
    await _tap(t, confirm);
    expect(_path, '/extension/pay');
    await _enter(t, 'UPI reference / UTR', '429017733201');
    await _tap(t, find.byWidgetPredicate((w) => w is DnButton && w.label.startsWith('I’ve paid')));
    expect(_path, '/extension/done');
    expect(find.textContaining('Loan extended to'), findsOneWidget);
    expect(api.loan!.extensionsUsed, 1);
  });

  testWidgets('C12: a wrong code shows the error and stays', (t) async {
    api.setScenario(Scenario.extensionOffered);
    await _pump(t, '/extension/confirm');
    await _tap(t, find.byType(DnCheck));
    await t.enterText(await _show(t, find.descendant(of: find.byType(DnOtpField), matching: find.byType(TextField))), '000000');
    await _tap(t, find.byWidgetPredicate((w) => w is DnButton && w.label.startsWith('Confirm and pay')));
    expect(_path, '/extension/confirm');
    expect(find.text(const ApiException('OTP_INVALID').message), findsOneWidget);
  });

  testWidgets('C12: no offer waiting (there is no borrower extend button)', (t) async {
    await _pump(t, '/extension/confirm');
    expect(find.text('No extension to confirm'), findsOneWidget);
  });

  testWidgets('C12d: renders nothing without extension activity', (t) async {
    await _pump(t, '/card');
    expect(find.byType(DnBanner), findsNothing);
    expect(find.byType(DnPanel), findsNothing);
  });

  testWidgets('C12d: offered state opens the confirm screen; extended shows history', (t) async {
    api.setScenario(Scenario.extensionOffered);
    await _pump(t, '/card');
    await _tap(t, find.text('Review'));
    expect(_path, '/extension/confirm');

    api.setScenario(Scenario.extended);
    await _pump(t, '/card');
    expect(find.text('Extended 1 of 3'), findsOneWidget);
    expect(find.text('Extension 1'), findsOneWidget);
  });

  testWidgets('C9: offline shows a retry that recovers', (t) async {
    api.offline = true;
    await _pump(t, '/transactions');
    expect(find.text('Try again'), findsOneWidget);
    api.offline = false;
    await t.pumpAndSettle();
    expect(find.text('Loan money received'), findsWidgets);
  });

  testWidgets('C8: opening a document asks to confirm it’s you; closed loans open the receipt', (t) async {
    await _pump(t, '/loans');
    await _tap(t, find.text('Key Fact Statement'));
    expect(find.text('Confirm it’s you'), findsOneWidget);
    await _tap(t, find.widgetWithText(DnButton, 'Continue'));
    expect(find.widgetWithText(DnButton, 'Download'), findsOneWidget);
    Navigator.of(t.element(find.widgetWithText(DnButton, 'Download'))).pop();
    await t.pumpAndSettle();

    await _tap(t, find.text('Closed'));
    await _tap(t, find.textContaining('Loan #19003'));
    expect(_path, '/loans/closed');
    expect(find.text('Loan closed. Thank you!'), findsOneWidget);
  });

  testWidgets('C10: borrow again goes to choose amount', (t) async {
    api.setScenario(Scenario.closed);
    await _pump(t, '/reloan');
    await _tap(t, find.widgetWithText(DnButton, 'Borrow again'));
    expect(_path, '/loan/amount');
  });

  testWidgets('C13: flag off shows the empty state', (t) async {
    api.setScenario(Scenario.overdue);
    await _pump(t, '/emi');
    expect(find.text('No repayment plan offered'), findsOneWidget);
  });
}
