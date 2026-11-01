import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/brand.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/features/account/routes.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

GoRoute _stub(String path) => GoRoute(path: path, builder: (_, _) => Scaffold(body: Text('stub $path')));

Future<void> _pump(WidgetTester t, String path, {double width = 412, double scale = 1}) async {
  t.view.physicalSize = Size(width * 3, 900 * 3);
  t.view.devicePixelRatio = 3;
  addTearDown(t.view.reset);
  final r = GoRouter(
    navigatorKey: rootNav,
    initialLocation: path,
    routes: [
      profileTab,
      ...accountRoutes,
      for (final p in ['/welcome', '/demo', '/repay', '/dashboard', '/loans', '/transactions', '/forgot-password']) _stub(p),
    ],
  );
  addTearDown(r.dispose);
  await t.pumpWidget(MaterialApp.router(
    theme: dnTheme(),
    routerConfig: r,
    builder: (context, child) => MediaQuery.withClampedTextScaling(minScaleFactor: scale, maxScaleFactor: scale, child: child!),
  ));
  await t.pumpAndSettle();
}

Finder _inDialog(String text) => find.descendant(of: find.byType(Dialog), matching: find.text(text));

void main() {
  setUp(() {
    demo.latency = Duration.zero;
    demo.offline = false;
    demo.setScenario(Scenario.active);
  });

  // CC-58: every screen lays out at 320dp × 2.0 and 412dp × 1.0; scrolling builds every row.
  const paths = ['/profile', '/settings', '/support', '/trust', '/complaints', '/complaints/new', '/loan-account', '/devices', '/delete-account'];
  for (final path in paths) {
    for (final (width, scale) in [(320.0, 2.0), (412.0, 1.0)]) {
      testWidgets('$path lays out at ${width.toInt()}dp, text ×$scale', (t) async {
        await _pump(t, path, width: width, scale: scale);
        final list = find.byType(Scrollable).first;
        for (var i = 0; i < 30; i++) {
          await t.drag(list, const Offset(0, -500));
          await t.pump();
        }
      });
    }
  }

  testWidgets('profile is view-only and shows the server details', (t) async {
    await _pump(t, '/profile');
    expect(find.text('Aarav Sharma'), findsWidgets);
    expect(find.textContaining('view-only'), findsOneWidget);
    expect(find.textContaining('Edit'), findsNothing);
  });

  testWidgets('log out confirms, calls the API and opens Welcome', (t) async {
    await _pump(t, '/profile');
    await t.scrollUntilVisible(find.text('Log out'), 300, scrollable: find.byType(Scrollable).first);
    await t.tap(find.text('Log out'));
    await t.pumpAndSettle();
    await t.tap(_inDialog('Log out'));
    await t.pumpAndSettle();
    expect(api.signedIn, isFalse);
    expect(find.text('stub /welcome'), findsOneWidget);
  });

  testWidgets('a failed load shows the error with a retry that recovers', (t) async {
    demo.offline = true;
    await _pump(t, '/profile');
    expect(find.text('No internet connection.'), findsOneWidget);
    demo.offline = false;
    await t.tap(find.text('Try again'));
    await t.pumpAndSettle();
    expect(find.text('Customer ID DN000123'), findsOneWidget);
  });

  testWidgets('support renders logged out with contacts from Brand and no NBFC claim', (t) async {
    demo.setScenario(Scenario.newUser);
    await _pump(t, '/support');
    expect(find.textContaining(Brand.supportPhone), findsOneWidget);
    expect(find.text('Grievance officer: ${Brand.grievanceName}'), findsOneWidget);
    expect(find.text('My complaints'), findsNothing);
    expect(find.textContaining('NBFC'), findsNothing);
  });

  testWidgets('delete is blocked while a loan is open', (t) async {
    await _pump(t, '/delete-account');
    await t.tap(find.text('Delete my account'));
    await t.pumpAndSettle();
    await t.tap(_inDialog('Delete my account'));
    await t.pumpAndSettle();
    expect(find.text('Not available while a loan is open'), findsOneWidget);
    expect(find.textContaining('Repay ₹1,000 first'), findsOneWidget);
  });

  testWidgets('delete with no open loan sends the request and logs out', (t) async {
    demo.setScenario(Scenario.closed);
    await _pump(t, '/delete-account');
    await t.tap(find.text('Delete my account'));
    await t.pumpAndSettle();
    await t.tap(_inDialog('Delete my account'));
    await t.pumpAndSettle();
    expect(find.text('Request received'), findsOneWidget);
    expect(api.signedIn, isFalse);
  });

  testWidgets('raising a complaint returns to the list with the reference', (t) async {
    await _pump(t, '/complaints');
    await t.tap(find.text('Raise a new complaint'));
    await t.pumpAndSettle();
    await t.tap(find.text('Documents'));
    await t.enterText(find.byType(TextField), 'My sanction letter will not open.');
    await t.pump();
    await t.tap(find.text('Submit complaint'));
    await t.pumpAndSettle();
    expect(find.textContaining('Complaint raised. Reference GR-'), findsOneWidget);
    expect(find.textContaining('Documents · Loan'), findsOneWidget);
    expect(find.text('My complaints'), findsOneWidget);
  });

  testWidgets('signing out another device removes it', (t) async {
    await _pump(t, '/devices');
    expect(find.text('Redmi Note 12'), findsOneWidget);
    await t.tap(find.text('Log out'));
    await t.pumpAndSettle();
    await t.tap(_inDialog('Log out'));
    await t.pumpAndSettle();
    expect(find.text('Redmi Note 12'), findsNothing);
    expect(find.text('THIS DEVICE'), findsOneWidget);
  });

  testWidgets('loan account shows server amounts and the disbursal UTR', (t) async {
    await _pump(t, '/loan-account');
    expect(find.text('DNL0001207'), findsOneWidget);
    expect(find.text('₹742'), findsOneWidget);
    await t.scrollUntilVisible(find.text('HDFCN52026110412907'), 300, scrollable: find.byType(Scrollable).first);
    expect(find.text('HDFCN52026110412907'), findsOneWidget);
  });
}
