import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import '../../core/router.dart';
import 'choose.dart';
import 'payout.dart';
import 'sign.dart';
import 'verify.dart';

GoRoute _full(String path, Widget Function() screen) => GoRoute(path: path, parentNavigatorKey: rootNav, builder: (_, _) => screen());

/// The loan offer journey B1–B12 (full-screen, above the tab bar).
final offerRoutes = <RouteBase>[
  _full('/loan/amount', AmountScreen.new),
  _full('/loan/repayment-date', RepaymentDateScreen.new),
  _full('/loan/digilocker', DigilockerScreen.new),
  _full('/loan/digilocker/upload', AadhaarUploadScreen.new),
  _full('/loan/references', ReferencesScreen.new),
  _full('/loan/summary', SummaryScreen.new),
  _full('/loan/kfs', KfsScreen.new),
  _full('/loan/selfie', SelfieIntroScreen.new),
  _full('/loan/selfie/capture', SelfieCaptureScreen.new),
  _full('/loan/address', AddressScreen.new),
  _full('/loan/sanction-letter', AgreementScreen.new),
  _full('/loan/sign-draw', SignDrawScreen.new),
  _full('/loan/sanctioned', SanctionedScreen.new),
  _full('/loan/disbursal-account', PayoutScreen.new),
  _full('/loan/disbursal-account/other', OtherAccountScreen.new),
  _full('/loan/mandate', MandateScreen.new),
  _full('/loan/status', StatusScreen.new),
];
