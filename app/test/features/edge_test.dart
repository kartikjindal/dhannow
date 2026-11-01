import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/features/edge/routes.dart';
import 'package:dhannow/features/edge/step_review.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

/// Pumps the edge routes; any other route renders `route:<path>` so navigation is checkable.
Future<void> pumpAt(WidgetTester t, String path, {Object? extra, double width = 412, double scale = 1}) async {
  t.view.physicalSize = Size(width * 3, (width < 400 ? 640 : 915) * 3);
  t.view.devicePixelRatio = 3;
  addTearDown(t.view.reset);
  final router = GoRouter(
    navigatorKey: rootNav,
    initialLocation: path,
    initialExtra: extra,
    routes: [
      ...edgeRoutes,
      GoRoute(
        path: '/review',
        builder: (_, _) => Scaffold(
          body: ListView(
            children: [
              StepReviewCard(rows: const [('Company', 'Infosys Limited'), ('Monthly salary', '₹40,000')], onEdit: () {}),
            ],
          ),
        ),
      ),
    ],
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
    api.latency = Duration.zero;
    api.offline = false;
    api.setScenario(Scenario.onboarding);
  });

  const paths = ['/offline', '/error', '/session-expired', '/resume', '/otp-locked', '/upload-error', '/camera-denied', '/not-eligible', '/payment-rejected', '/walkthrough', '/review'];

  // CC-58: no overflow at 320dp × 2.0 and 412dp × 1.0.
  for (final (width, scale) in [(320.0, 2.0), (412.0, 1.0)]) {
    for (final path in paths) {
      testWidgets('$path lays out at ${width.toInt()}dp × $scale', (t) async {
        if (path == '/payment-rejected') api.setScenario(Scenario.overdue);
        await pumpAt(t, path, extra: path == '/error' ? 'ERR-7F3A-1029' : null, width: width, scale: scale);
        await t.pump();
        await scrollAll(t);
      });
    }
  }

  testWidgets('offline: retry waits for the connection, then opens the journey', (t) async {
    api.setScenario(Scenario.active);
    api.offline = true;
    await pumpAt(t, '/offline');
    await t.tap(find.text('Try again'));
    await t.pump();
    expect(find.textContaining('Still no internet'), findsOneWidget);
    api.offline = false;
    await t.tap(find.text('Try again'));
    await t.pumpAndSettle();
    expect(find.text('route:/dashboard'), findsOneWidget);
  });

  testWidgets('server error shows the request ID from extra', (t) async {
    await pumpAt(t, '/error', extra: const ApiException('INTERNAL', 'ERR-7F3A-1029'));
    expect(find.text('ERR-7F3A-1029'), findsOneWidget);
    await t.tap(find.text('Contact support'));
    await t.pumpAndSettle();
    expect(find.text('route:/support'), findsOneWidget);
  });

  testWidgets('resume lists the next step from the server and continues there', (t) async {
    await pumpAt(t, '/resume');
    await t.pumpAndSettle();
    expect(find.text('Welcome back, Aarav'), findsOneWidget);
    expect(find.text('Your application is 5 of ${Api.signupSteps.length} steps done'), findsOneWidget);
    expect(find.text('Next: emails'), findsOneWidget);
    await t.tap(find.text('Continue application'));
    await t.pumpAndSettle();
    expect(find.text('route:/signup/${api.nextSignupStep}'), findsOneWidget);
  });

  testWidgets('session expired: a wrong code shows the error, a right one carries on', (t) async {
    await pumpAt(t, '/session-expired');
    await t.enterText(find.byType(TextField), '000000');
    await t.pumpAndSettle();
    expect(find.text(const ApiException('OTP_INVALID').message), findsOneWidget);
    await t.enterText(find.byType(TextField), '482913');
    await t.pumpAndSettle();
    expect(find.text('Continue application'), findsOneWidget);
  });

  testWidgets('OTP lock counts down', (t) async {
    await pumpAt(t, '/otp-locked', extra: const Duration(minutes: 12, seconds: 40));
    expect(find.textContaining('12:40'), findsOneWidget);
    await t.pump(const Duration(seconds: 1));
    expect(find.textContaining('12:39'), findsOneWidget);
  });

  testWidgets('payment rejected: submit correct details goes to Repay', (t) async {
    api.setScenario(Scenario.overdue);
    await pumpAt(t, '/payment-rejected', extra: (paise: 100000, utr: '429011238867', reason: 'UTR not found'));
    expect(find.text('4290 1123 8867'), findsOneWidget);
    expect(find.text('₹1,000'), findsOneWidget);
    await t.tap(find.text('Submit correct details'));
    await t.pumpAndSettle();
    expect(find.text('route:/repay'), findsOneWidget);
  });

  testWidgets('walkthrough finishes on the dashboard', (t) async {
    await pumpAt(t, '/walkthrough');
    for (var i = 0; i < 3; i++) {
      await t.tap(find.text('Next'));
      await t.pumpAndSettle();
    }
    await t.tap(find.text('Go to my dashboard'));
    await t.pumpAndSettle();
    expect(find.text('route:/dashboard'), findsOneWidget);
  });

  testWidgets('step review: Edit when editable, Verified when locked', (t) async {
    var edits = 0;
    await t.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              StepReviewCard(rows: const [('Company', 'Infosys Limited')], onEdit: () => edits++),
              const StepReviewCard(rows: [('Mobile', '+91 98765 43210')]),
            ],
          ),
        ),
      ),
    );
    await t.tap(find.text('Edit'));
    expect(edits, 1);
    expect(find.text('VERIFIED'), findsOneWidget);
    expect(find.text('SAVED'), findsOneWidget);
  });
}
