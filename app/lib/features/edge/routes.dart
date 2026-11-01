import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/router.dart';
import 'package:go_router/go_router.dart';

import 'screens.dart';

/// Boards E3–E12 and F3. E1 lives in `StepScaffold`; E2 is `StepReviewCard` (step_review.dart).
final edgeRoutes = <RouteBase>[
  GoRoute(path: '/offline', parentNavigatorKey: rootNav, builder: (_, _) => const OfflineScreen()),
  GoRoute(
    path: '/error',
    parentNavigatorKey: rootNav,
    builder: (_, s) => ServerErrorScreen(
      requestId: switch (s.extra) {
        final String id => id,
        final ApiException e => e.requestId,
        _ => null,
      },
    ),
  ),
  GoRoute(path: '/session-expired', parentNavigatorKey: rootNav, builder: (_, _) => const SessionExpiredScreen()),
  GoRoute(path: '/resume', parentNavigatorKey: rootNav, builder: (_, _) => const ResumeScreen()),
  GoRoute(
    path: '/otp-locked',
    parentNavigatorKey: rootNav,
    builder: (_, s) => OtpLockedScreen(wait: s.extra as Duration?),
  ),
  GoRoute(
    path: '/upload-error',
    parentNavigatorKey: rootNav,
    builder: (_, s) => UploadErrorScreen(failure: s.extra as ({String file, UploadProblem problem})?),
  ),
  GoRoute(
    path: '/camera-denied',
    parentNavigatorKey: rootNav,
    builder: (_, s) => CameraDeniedScreen(selfie: s.extra == 'selfie'),
  ),
  GoRoute(
    path: '/not-eligible',
    parentNavigatorKey: rootNav,
    builder: (_, s) => NotEligibleScreen(applyAgainOn: s.extra as DateTime?),
  ),
  GoRoute(
    path: '/payment-rejected',
    parentNavigatorKey: rootNav,
    builder: (_, s) => PaymentRejectedScreen(payment: s.extra as RejectedPayment?),
  ),
  GoRoute(path: '/walkthrough', parentNavigatorKey: rootNav, builder: (_, _) => const WalkthroughScreen()),
];
