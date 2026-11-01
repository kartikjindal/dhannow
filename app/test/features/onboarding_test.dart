import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:dhannow/features/onboarding/common.dart';
import 'package:dhannow/features/onboarding/routes.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

/// Pumps the onboarding routes; any other route renders `route:<path>` so navigation is checkable.
Future<void> pumpAt(WidgetTester t, String path, {double width = 412, double scale = 1, Object? extra}) async {
  t.view.physicalSize = Size(width * 3, (width < 400 ? 640 : 915) * 3);
  t.view.devicePixelRatio = 3;
  addTearDown(t.view.reset);
  final router = GoRouter(
    navigatorKey: rootNav,
    initialLocation: path,
    initialExtra: extra,
    routes: onboardingRoutes,
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
  await t.pump(const Duration(milliseconds: 600));
}

Future<void> scrollAll(WidgetTester t) async {
  for (final list in find.byType(ListView).evaluate().toList()) {
    for (var i = 0; i < 8; i++) {
      await t.drag(find.byWidget(list.widget).first, const Offset(0, -500), warnIfMissed: false);
      await t.pump();
    }
  }
}

/// Lets the in-flight demo call and the navigation finish (the resend timer keeps ticking, so no
/// pumpAndSettle on OTP screens).
Future<void> settle(WidgetTester t) async {
  for (var i = 0; i < 6; i++) {
    await t.pump(const Duration(milliseconds: 100));
  }
}

Future<void> tapText(WidgetTester t, String text) async {
  await t.ensureVisible(find.text(text).last);
  await t.tap(find.text(text).last);
  await settle(t);
}

const _paths = [
  '/signup/start',
  '/signup/otp',
  '/signup/set-password',
  '/signup/employment',
  '/signup/employer',
  '/signup/self-employed',
  '/signup/email',
  '/signup/bank',
  '/signup/payslips',
  '/signup/aadhaar',
  '/signup/pan-card',
  '/signup/consent',
  '/signup/submitted',
  '/credit-question',
  '/application-rejected',
  '/apply',
  '/apply/submitted',
];

void main() {
  setUp(() async {
    api.latency = Duration.zero;
    api.offline = false;
    api.setScenario(Scenario.newUser);
    await api.sendOtp('9876543210'); // resets the demo's wrong-code counter
    savedSteps.clear();
  });

  // CC-58: no overflow at 320dp × 2.0 and 412dp × 1.0.
  for (final (width, scale) in [(320.0, 2.0), (412.0, 1.0)]) {
    for (final path in _paths) {
      testWidgets('$path lays out at ${width.toInt()}dp × $scale', (t) async {
        await pumpAt(
          t,
          path,
          width: width,
          scale: scale,
          extra: const {'name': 'Aarav Sharma', 'email': 'a@b.in', 'mobile': '9876543210', 'aadhaarLast4': '9012'},
        );
        await scrollAll(t);
      });
    }
    testWidgets('view mode (E2) lays out at ${width.toInt()}dp × $scale', (t) async {
      api.setScenario(Scenario.onboarding);
      await pumpAt(t, '/signup/employer', width: width, scale: scale);
      expect(find.text('You’ve already done this step'), findsOneWidget);
      await scrollAll(t);
    });
  }

  test('validators follow the boards', () {
    expect(mobileError('9876543210'), isNull);
    expect(mobileError('5876543210'), isNotNull);
    expect(mobileError('98765'), isNotNull);
    expect(panError('abcde1234f'), isNull);
    expect(panError('ABCD1234F'), isNotNull);
    expect(ifscError('HDFC0001234'), isNull);
    expect(ifscError('HDFC1001234'), isNotNull);
    expect(emailError('a.b@work.co.in'), isNull);
    expect(emailError('a.b@work'), isNotNull);
    expect(aadhaarError('234123412346'), isNull);
    expect(aadhaarError('234123412347'), contains('doesn’t look like a valid'));
    expect(aadhaarError('1234'), 'Enter your 12-digit Aadhaar number.');
    expect(passwordError('abc12345!'), isNull);
    expect(passwordError('abcdefgh'), isNotNull);
    // CC-49: PDFs over 5 MB are refused; photos are compressed instead.
    expect(uploadError('slip.pdf', 6 * 1024 * 1024), isNotNull);
    expect(uploadError('slip.pdf', 4 * 1024 * 1024), isNull);
    expect(uploadError('slip.jpg', 6 * 1024 * 1024), isNull);
  });

  testWidgets('A1: Terms only tick on Agree, PAN is checked, Continue saves and sends the code', (t) async {
    await pumpAt(t, '/signup/start');
    DnButton continueBtn() => t.widget<DnButton>(find.widgetWithText(DnButton, 'Continue'));
    expect(continueBtn().onPressed, isNull);

    await t.tap(find.textContaining('I agree to the'));
    await t.pumpAndSettle();
    expect(find.text('Terms & Conditions'), findsWidgets);
    await t.tap(find.text('Decline'));
    await t.pumpAndSettle();
    await t.tap(find.textContaining('Politically Exposed'));
    await t.pump();
    expect(continueBtn().onPressed, isNull);

    await t.tap(find.textContaining('I agree to the'));
    await t.pumpAndSettle();
    await t.tap(find.text('Agree'));
    await t.pumpAndSettle();
    expect(continueBtn().onPressed, isNotNull);

    await t.enterText(find.byType(TextField).at(0), 'ABC12');
    await t.enterText(find.byType(TextField).at(1), '9876543210');
    await tapText(t, 'Continue');
    expect(find.text('Enter a valid PAN, like ABCDE1234F.'), findsOneWidget);

    await t.enterText(find.byType(TextField).at(0), 'abcde1234f');
    await tapText(t, 'Continue');
    expect(find.text('Verify your mobile'), findsOneWidget);
    expect(find.textContaining('Sent to 9876543210', findRichText: true), findsOneWidget);
    expect(api.onboardingDone, contains('start'));
    expect(savedSteps['start']?['pan'], 'ABCDE1234F');
  });

  testWidgets('A2: a wrong code shows the message; five wrong codes open E7', (t) async {
    await pumpAt(t, '/signup/otp');
    for (var i = 0; i < 4; i++) {
      await t.enterText(find.byType(TextField), '000000');
      await settle(t);
      expect(find.text(const ApiException('OTP_INVALID').message), findsOneWidget);
    }
    await t.enterText(find.byType(TextField), '000000');
    await settle(t);
    expect(find.text('route:/otp-locked'), findsOneWidget);
  });

  testWidgets('A2: the right code goes on to set a password', (t) async {
    await pumpAt(t, '/signup/otp');
    await t.enterText(find.byType(TextField), '482913');
    await settle(t);
    expect(find.text('Set a password'), findsOneWidget);
    expect(api.onboardingDone, contains('otp'));
  });

  testWidgets('A4: nothing chosen asks for a choice; self-employed opens A5b', (t) async {
    await pumpAt(t, '/signup/employment');
    await tapText(t, 'Continue');
    expect(find.text('Select your employment status'), findsOneWidget);
    await t.tap(find.text('Self-employed'));
    await tapText(t, 'Continue');
    expect(find.text('A few more details and we’ll review your application.'), findsOneWidget);
  });

  testWidgets('an offline save opens E3 and keeps the step', (t) async {
    await pumpAt(t, '/signup/employment');
    await t.tap(find.text('Salaried'));
    api.offline = true;
    await tapText(t, 'Continue');
    expect(find.text('route:/offline'), findsOneWidget);
    expect(api.onboardingDone, isNot(contains('employment')));
  });

  testWidgets('A7: Continue waits for a statement and checks IFSC', (t) async {
    await pumpAt(t, '/signup/bank');
    expect(t.widget<DnButton>(find.widgetWithText(DnButton, 'Continue')).onPressed, isNull);
    await t.enterText(find.byType(TextField).at(0), 'HDFC Bank');
    await t.enterText(find.byType(TextField).at(1), '50100012345678');
    await t.enterText(find.byType(TextField).at(2), 'HDFC1234');
    await t.enterText(find.byType(TextField).at(3), '9876543210');
    await tapText(t, 'Bank statements (last 6 months)');
    expect(find.text('Bank_statement_1.pdf'), findsOneWidget);
    await t.scrollUntilVisible(find.text('PDF password (optional)'), 200, scrollable: find.byType(Scrollable).first);
    await tapText(t, 'Continue');
    expect(find.text('Enter a valid IFSC code, like HDFC0001234.'), findsOneWidget);
    await t.enterText(find.byType(TextField).at(2), 'hdfc0001234');
    await tapText(t, 'Continue');
    expect(find.text('Salary slips'), findsOneWidget);
    expect(savedSteps['bank']?['account'], '••••5678');
  });

  testWidgets('A8: all three payslips are required; a PDF shows the password field (CC-50)', (t) async {
    await pumpAt(t, '/signup/payslips');
    await tapText(t, 'Continue');
    expect(find.text('Upload all 3 salary slips to continue.'), findsOneWidget);
    for (var i = 0; i < 3; i++) {
      await tapText(t, 'Choose file');
    }
    expect(find.text('PDF password (optional)'), findsOneWidget);
    await tapText(t, 'Continue');
    expect(find.text('Aadhaar card'), findsOneWidget);
  });

  testWidgets('A11: Confirm & submit verifies the code and hands over to the credit team', (t) async {
    savedSteps['start'] = {'mobile': '9876543210'};
    await pumpAt(t, '/signup/consent');
    await t.enterText(find.byType(TextField).first, '123456');
    await t.pump();
    await tapText(t, 'I authorise DhanNow and its lending partner to fetch my credit report from a credit bureau to assess this application.');
    await tapText(t, 'Confirm & submit');
    expect(find.text('Pick your date of birth.'), findsOneWidget);
    await tapText(t, 'Date of birth');
    await t.tap(find.text('OK'));
    await settle(t);
    await tapText(t, 'Confirm & submit');
    expect(find.text('Your application is with our credit team'), findsOneWidget);
    expect(api.scenario, Scenario.inReview);
  });

  testWidgets('E2: a finished step opens read-only and Edit makes it a form', (t) async {
    api.setScenario(Scenario.onboarding);
    await pumpAt(t, '/signup/employer');
    expect(find.text('Acme Logistics Pvt Ltd'), findsOneWidget);
    await tapText(t, 'Edit');
    expect(find.widgetWithText(DnField, 'Company name'), findsOneWidget);
    expect(find.text('Acme Logistics Pvt Ltd'), findsOneWidget); // prefilled
  });

  testWidgets('E2: a locked step shows Verified and no Edit', (t) async {
    api.setScenario(Scenario.onboarding);
    await pumpAt(t, '/signup/start');
    expect(find.text('VERIFIED'), findsOneWidget); // DnPill upper-cases
    expect(find.text('Edit'), findsNothing);
    await tapText(t, 'Continue where I left off');
    expect(find.text('Email addresses'), findsOneWidget);
  });

  testWidgets('A13: answering returns the application to the credit team', (t) async {
    api.setScenario(Scenario.question);
    await pumpAt(t, '/credit-question');
    await tapText(t, 'Get my question');
    await t.tap(find.text('ICICI Bank'));
    await t.pump();
    await tapText(t, 'Confirm answer');
    expect(find.text('All done — thank you'), findsOneWidget);
    expect(api.stage, Stage.inReview);
  });

  testWidgets('E13: the gate takes the last 4 Aadhaar digits and shows them masked', (t) async {
    await pumpAt(t, '/apply');
    final fields = find.byType(TextField);
    await t.enterText(fields.at(0), 'Aarav Sharma');
    await t.enterText(fields.at(1), '9876543210');
    await t.enterText(fields.at(2), 'aarav@example.com');
    await t.enterText(fields.at(3), 'ABCDE1234F');
    await t.enterText(fields.at(4), '90');
    await tapText(t, 'Submit');
    expect(find.text('Enter the last 4 digits of your Aadhaar.'), findsOneWidget);
    await t.enterText(fields.at(4), '9012');
    await tapText(t, 'Submit');
    expect(find.text('Your application is under review'), findsOneWidget);
    expect(find.text('••••9012'), findsOneWidget);
    expect(find.text('98765 43210'), findsOneWidget);
  });
}
