// Renders every screen to a PNG with real fonts, through the app router and demo server, so the
// app can be reviewed without a phone. Not part of `flutter test` (it lives outside test/):
//   flutter test tool/screens_test.dart --dart-define=SHOTS=<out dir>
import 'dart:io';
import 'dart:ui' as ui;

import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:dhannow/features/dashboard/loan_details.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

const _out = String.fromEnvironment('SHOTS', defaultValue: 'build/screens');

/// (scenario, route, file name). Order is the wireframe's row order.
final _shots = <(Scenario, String, String)>[
  (Scenario.newUser, '/', 'S1-splash'),
  (Scenario.newUser, '/intro', 'S2-intro'),
  (Scenario.newUser, '/welcome', 'S3-welcome'),
  (Scenario.newUser, '/login', 'S4-login'),
  (Scenario.newUser, '/forgot-password', 'S5-forgot-password'),
  (Scenario.newUser, '/reset-password', 'S6-reset-password'),
  (Scenario.newUser, '/signup/start', 'A1-your-details'),
  (Scenario.newUser, '/signup/otp', 'A2-verify-mobile'),
  (Scenario.newUser, '/signup/set-password', 'A3-set-password'),
  (Scenario.newUser, '/signup/employment', 'A4-employment'),
  (Scenario.newUser, '/signup/employer', 'A5-employer'),
  (Scenario.newUser, '/signup/self-employed', 'A5b-self-employed'),
  (Scenario.newUser, '/signup/email', 'A6-email'),
  (Scenario.newUser, '/signup/bank', 'A7-salary-account'),
  (Scenario.newUser, '/signup/payslips', 'A8-payslips'),
  (Scenario.newUser, '/signup/aadhaar', 'A9-aadhaar'),
  (Scenario.newUser, '/signup/pan-card', 'A10-pan'),
  (Scenario.newUser, '/signup/consent', 'A11-consent'),
  (Scenario.inReview, '/signup/submitted', 'A12-submitted'),
  (Scenario.question, '/credit-question', 'A13-security-question'),
  (Scenario.rejected, '/application-rejected', 'A14-rejected'),
  (Scenario.offer, '/loan/amount', 'B1-choose-amount'),
  (Scenario.offer, '/loan/repayment-date', 'B2-repayment-date'),
  (Scenario.offer, '/loan/digilocker', 'B3-digilocker'),
  (Scenario.offer, '/loan/references', 'B4-references'),
  (Scenario.offer, '/loan/summary', 'B5-summary'),
  (Scenario.offer, '/loan/kfs', 'B5b-kfs'),
  (Scenario.offer, '/loan/selfie', 'B6-selfie-intro'),
  (Scenario.offer, '/loan/selfie/capture', 'B6b-selfie-capture'),
  (Scenario.offer, '/loan/address', 'B7-address'),
  (Scenario.offer, '/loan/sanction-letter', 'B8-agreement'),
  (Scenario.offer, '/loan/sign-draw', 'B8b-sign-draw'),
  (Scenario.offer, '/loan/sanctioned', 'B9-sanctioned'),
  (Scenario.offer, '/loan/disbursal-account', 'B10-payout'),
  (Scenario.offer, '/loan/disbursal-account/other', 'B10b-other-account'),
  (Scenario.offer, '/loan/mandate', 'B11-autopay'),
  (Scenario.disbursal, '/loan/status', 'B12-status'),
  (Scenario.active, '/dashboard', 'C1-dashboard-active'),
  (Scenario.dueToday, '/dashboard', 'C1-dashboard-due-today'),
  (Scenario.extended, '/dashboard', 'C1b-dashboard-extended'),
  (Scenario.overdue, '/dashboard', 'C2-dashboard-overdue'),
  (Scenario.onboarding, '/dashboard', 'C3-dashboard-signup'),
  (Scenario.inReview, '/dashboard', 'C3-dashboard-review'),
  (Scenario.disbursal, '/dashboard', 'C3-dashboard-disbursal'),
  (Scenario.offer, '/dashboard', 'C4-dashboard-offer'),
  (Scenario.closed, '/dashboard', 'C4-dashboard-repaid'),
  (Scenario.active, 'sheet:details', 'C5-loan-details'),
  (Scenario.active, '/repay', 'C6-repay'),
  (Scenario.pendingVerification, '/repay/sent', 'C7-payment-sent'),
  (Scenario.active, '/loans', 'C8-loans'),
  (Scenario.active, '/transactions', 'C9-transactions'),
  (Scenario.closed, '/reloan', 'C10-borrow-again'),
  (Scenario.overdue, '/notifications', 'C11-notifications'),
  (Scenario.extensionOffered, '/extension/confirm', 'C12-extension-confirm'),
  (Scenario.extensionOffered, '/extension/pay', 'C12b-extension-pay'),
  (Scenario.extended, '/extension/done', 'C12c-extension-done'),
  (Scenario.closed, '/emi', 'C13-emi-off'),
  (Scenario.closed, '/loans/closed', 'C14-closed-receipt'),
  (Scenario.active, '/profile', 'D1-profile'),
  (Scenario.active, '/settings', 'D2-settings'),
  (Scenario.newUser, '/support', 'D3-support'),
  (Scenario.newUser, '/trust', 'D4-trust'),
  (Scenario.active, '/complaints', 'D5-complaints'),
  (Scenario.active, '/complaints/new', 'D5-new-complaint'),
  (Scenario.active, '/loan-account', 'D6-loan-account'),
  (Scenario.active, '/devices', 'D7-devices'),
  (Scenario.active, '/delete-account', 'D8-delete-account'),
  (Scenario.onboarding, 'sheet:back', 'E1-back-confirm'),
  (Scenario.onboarding, '/signup/employer', 'E2-view-mode'),
  (Scenario.active, '/offline', 'E3-offline'),
  (Scenario.active, '/error', 'E4-error'),
  (Scenario.active, '/session-expired', 'E5-session-expired'),
  (Scenario.onboarding, '/resume', 'E6-resume'),
  (Scenario.newUser, '/otp-locked', 'E7-otp-locked'),
  (Scenario.offer, '/upload-error', 'E8-upload-problem'),
  (Scenario.offer, '/camera-denied', 'E9-camera'),
  (Scenario.offer, '/loan/digilocker/upload', 'E10-digilocker-fallback'),
  (Scenario.rejected, '/not-eligible', 'E11-not-eligible'),
  (Scenario.active, '/payment-rejected', 'E12-payment-not-matched'),
  (Scenario.newUser, '/apply', 'E13-apply-gate'),
  (Scenario.active, '/walkthrough', 'F3-walkthrough'),
  (Scenario.active, '/demo', 'X-demo-states'),
];

Future<void> _loadFonts() async {
  Future<void> family(String name, List<String> assets) async {
    final l = FontLoader(name);
    for (final a in assets) {
      l.addFont(rootBundle.load(a));
    }
    await l.load();
  }

  await family('Inter', ['Regular', 'Medium', 'SemiBold', 'Bold'].map((w) => 'assets/fonts/Inter-$w.ttf').toList());
  await family('BebasNeue', ['assets/fonts/BebasNeue-Regular.ttf']);
  const sdk = String.fromEnvironment('FLUTTER_ROOT', defaultValue: 'C:/dev/flutter');
  final icons = File('$sdk/bin/cache/artifacts/material_fonts/materialicons-regular.otf').readAsBytesSync();
  await (FontLoader('MaterialIcons')..addFont(Future.value(ByteData.sublistView(icons)))).load();
}

void main() {
  api = DemoApi();
  testWidgets('render every screen', (t) async {
    await t.runAsync(_loadFonts);
    t.view.physicalSize = const Size(412 * 2, 915 * 2);
    t.view.devicePixelRatio = 2;
    addTearDown(t.view.reset);
    demo.latency = Duration.zero;
    Directory(_out).createSync(recursive: true);
    final frame = GlobalKey();
    await t.pumpWidget(RepaintBoundary(
      key: frame,
      child: MaterialApp.router(debugShowCheckedModeBanner: false, theme: dnTheme(), routerConfig: router),
    ));

    for (final (scenario, route, name) in _shots) {
      demo.setScenario(scenario);
      api.firstLaunch = scenario == Scenario.newUser;
      rootNav.currentState!.popUntil((r) => r is! PopupRoute);
      if (route == 'sheet:details') {
        router.go('/dashboard');
        await _settle(t);
        showLoanDetails(rootNav.currentContext!, api.loan!);
      } else if (route == 'sheet:back') {
        router.go('/signup/email');
        await _settle(t);
        showBackConfirm(rootNav.currentContext!);
      } else {
        router.go(route);
      }
      await _settle(t, splash: route == '/');
      await t.runAsync(() async {
        final img = await (frame.currentContext!.findRenderObject()! as RenderRepaintBoundary).toImage(pixelRatio: 1.5);
        final png = await img.toByteData(format: ui.ImageByteFormat.png);
        File('$_out/$name.png').writeAsBytesSync(png!.buffer.asUint8List());
      });
    }
    rootNav.currentState!.popUntil((r) => r is! PopupRoute);
    router.go('/kit');
    await t.pump(const Duration(seconds: 30)); // let the splash timer and toasts finish
  });
}

/// Looping animations (money on the way, skeleton shimmer) never settle, so step a fixed time.
Future<void> _settle(WidgetTester t, {bool splash = false}) async {
  for (var i = 0; i < (splash ? 3 : 15); i++) {
    await t.pump(const Duration(milliseconds: 100));
  }
}
