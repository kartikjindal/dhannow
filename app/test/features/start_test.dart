import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/features/start/auth.dart';
import 'package:dhannow/features/start/routes.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

/// Pumps the start routes; any other route renders `route:<path>` so navigation is checkable.
Future<void> pumpAt(WidgetTester t, String path, {double width = 412, double scale = 1}) async {
  t.view.physicalSize = Size(width * 3, (width < 400 ? 640 : 915) * 3);
  t.view.devicePixelRatio = 3;
  addTearDown(t.view.reset);
  final router = GoRouter(
    navigatorKey: rootNav,
    initialLocation: path,
    routes: startRoutes,
    errorBuilder: (_, s) => Scaffold(body: Text('route:${s.uri}')),
  );
  await t.pumpWidget(
    MaterialApp.router(
      theme: dnTheme(),
      routerConfig: router,
      builder: (context, child) => MediaQuery.withClampedTextScaling(minScaleFactor: scale, maxScaleFactor: scale, child: child!),
    ),
  );
  await t.pump();
}

Future<void> scrollAll(WidgetTester t) async {
  for (final list in find.byType(ListView).evaluate().toList()) {
    for (var i = 0; i < 6; i++) {
      await t.drag(find.byWidget(list.widget).first, const Offset(0, -500), warnIfMissed: false);
      await t.pump();
    }
  }
}

void main() {
  setUp(() {
    demo.latency = Duration.zero;
    demo.offline = false;
    api.firstLaunch = true;
    demo.setScenario(Scenario.newUser);
  });

  // CC-58: no overflow at 320dp × 2.0 and 412dp × 1.0.
  for (final (width, scale) in [(320.0, 2.0), (412.0, 1.0)]) {
    for (final path in ['/', '/intro', '/welcome', '/login', '/forgot-password', '/reset-password']) {
      testWidgets('$path lays out at ${width.toInt()}dp × $scale', (t) async {
        await pumpAt(t, path, width: width, scale: scale);
        await t.pump();
        await scrollAll(t);
      });
    }
  }

  testWidgets('splash goes to intro on first launch', (t) async {
    await pumpAt(t, '/');
    await t.pump(const Duration(seconds: 1));
    await t.pumpAndSettle();
    expect(find.text('Skip'), findsOneWidget);
  });

  testWidgets('splash goes to the server journey route when signed in', (t) async {
    demo.setScenario(Scenario.onboarding);
    await pumpAt(t, '/');
    await t.pump(const Duration(seconds: 1));
    await t.pumpAndSettle();
    expect(find.text('route:/resume'), findsOneWidget);
  });

  testWidgets('splash offline opens E3', (t) async {
    demo.offline = true;
    await pumpAt(t, '/');
    await t.pump(const Duration(seconds: 1));
    await t.pumpAndSettle();
    expect(find.text('route:/offline'), findsOneWidget);
  });

  testWidgets('intro skip ends first launch and opens welcome', (t) async {
    await pumpAt(t, '/intro');
    await t.tap(find.text('Skip'));
    await t.pumpAndSettle();
    expect(api.firstLaunch, isFalse);
    expect(find.text('Apply now'), findsOneWidget);
  });

  testWidgets('welcome shows the server quote and Apply now opens sign-up', (t) async {
    await pumpAt(t, '/welcome');
    await t.pumpAndSettle();
    expect(find.text('₹742'), findsOneWidget);
    expect(find.textContaining('₹258 covers fee'), findsOneWidget);
    expect(find.textContaining('up to ₹10,000'), findsOneWidget);
    await t.tap(find.text('Apply now'));
    await t.pumpAndSettle();
    expect(find.text('route:/signup/start'), findsOneWidget);
  });

  testWidgets('login: wrong password shows the error with a retry, right one opens the journey', (t) async {
    await pumpAt(t, '/login');
    await t.enterText(find.byType(TextField).at(0), '9876543210');
    await t.enterText(find.byType(TextField).at(1), 'short');
    await t.tap(find.text('Sign in'));
    await t.pumpAndSettle();
    expect(find.text(const ApiException('LOGIN_INVALID').message), findsOneWidget);
    expect(find.text('Try again'), findsOneWidget);
    await t.enterText(find.byType(TextField).at(1), 'longenough1!');
    await t.tap(find.text('Sign in'));
    await t.pumpAndSettle();
    expect(find.text('route:/dashboard'), findsOneWidget);
  });

  testWidgets('login checks the mobile number before calling', (t) async {
    await pumpAt(t, '/login');
    await t.enterText(find.byType(TextField).at(0), '12345');
    await t.tap(find.text('Sign in'));
    await t.pump();
    expect(find.text('Enter your 10-digit mobile number.'), findsOneWidget);
  });

  testWidgets('forgot password: mobile, code, then reset', (t) async {
    await pumpAt(t, '/forgot-password');
    await t.enterText(find.byType(TextField), '9876543210');
    await t.tap(find.text('Send code'));
    await t.pumpAndSettle();
    expect(find.textContaining('+91 98765 43210'), findsOneWidget);
    await t.enterText(find.byType(TextField), '000000');
    await t.pumpAndSettle();
    expect(find.text(const ApiException('OTP_INVALID').message), findsOneWidget);
    await t.enterText(find.byType(TextField), '482913');
    await t.pumpAndSettle();
    expect(find.text('Set a new password'), findsOneWidget);
  });

  testWidgets('reset password enforces the rule and the match, then signs in to the journey', (t) async {
    await pumpAt(t, '/reset-password');
    await t.enterText(find.byType(TextField).at(0), 'abcdef');
    await t.tap(find.text('Update password'));
    await t.pump();
    expect(find.textContaining('Password must be 6–10'), findsOneWidget);
    await t.enterText(find.byType(TextField).at(0), 'abc12!');
    await t.enterText(find.byType(TextField).at(1), 'abc12?');
    await t.tap(find.text('Update password'));
    await t.pump();
    expect(find.text('Passwords don’t match.'), findsOneWidget);
    await t.enterText(find.byType(TextField).at(1), 'abc12!');
    await t.tap(find.text('Update password'));
    await t.pumpAndSettle();
    expect(find.text('route:/dashboard'), findsOneWidget);
  });

  test('password rule mirrors the server PasswordPolicy', () {
    expect(passwordProblem('ab1!'), isNotNull);
    expect(passwordProblem('abcdefgh1!x'), isNotNull);
    expect(passwordProblem('abcdef1'), isNotNull);
    expect(passwordProblem('123456!'), isNotNull);
    expect(passwordProblem('abc12!'), isNull);
  });
}
