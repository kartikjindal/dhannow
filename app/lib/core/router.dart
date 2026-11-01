import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../dev/demo_controls.dart';
import '../dev/kit_gallery.dart';
import '../features/account/routes.dart';
import '../features/dashboard/routes.dart';
import '../features/edge/routes.dart';
import '../features/money/routes.dart';
import '../features/offer/routes.dart';
import '../features/onboarding/routes.dart';
import '../features/start/routes.dart';
import 'api/api.dart';
import 'widgets/widgets.dart';

/// Full-screen routes set `parentNavigatorKey: rootNav` so they cover the bottom bar.
final rootNav = GlobalKey<NavigatorState>();

/// Paths follow the wireframe boards (`/login`, `/signup/otp`, `/loan/amount`, ...). Each feature
/// owns its `routes.dart`; the four tab roots live in the shell below.
final router = GoRouter(
  navigatorKey: rootNav,
  initialLocation: '/',
  routes: [
    StatefulShellRoute.indexedStack(
      builder: (context, state, shell) => _TabShell(shell: shell),
      branches: [
        StatefulShellBranch(routes: [dashboardTab]),
        StatefulShellBranch(routes: [repayTab]),
        StatefulShellBranch(routes: [loansTab]),
        StatefulShellBranch(routes: [profileTab]),
      ],
    ),
    ...startRoutes,
    ...edgeRoutes,
    ...onboardingRoutes,
    ...offerRoutes,
    ...dashboardRoutes,
    ...moneyRoutes,
    ...accountRoutes,
    // Demo server only (`--dart-define=DEMO=true`): a deep link in a real build lands on the journey.
    GoRoute(path: '/demo', redirect: (_, _) => demoControls ? null : '/', builder: (_, _) => const DemoControls()),
    GoRoute(path: '/kit', redirect: (_, _) => demoControls ? null : '/', builder: (_, _) => const KitGallery()),
  ],
);

class _TabShell extends StatelessWidget {
  const _TabShell({required this.shell});
  final StatefulNavigationShell shell;

  static const _items = [
    DnNavItem('Home', Icons.home_rounded),
    DnNavItem('Repay', Icons.currency_rupee_rounded),
    DnNavItem('Loans', Icons.receipt_long_rounded),
    DnNavItem('Profile', Icons.person_rounded),
  ];

  @override
  Widget build(BuildContext context) => Scaffold(
        body: shell,
        bottomNavigationBar: DnBottomNav(
          items: _items,
          index: shell.currentIndex,
          onTap: (i) => shell.goBranch(i, initialLocation: i == shell.currentIndex),
        ),
      );
}
