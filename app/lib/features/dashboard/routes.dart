import 'package:go_router/go_router.dart';

import '../../core/router.dart';
import 'dashboard_screen.dart';
import 'notifications_screen.dart';

/// Tab root (C1–C4). Owned by this feature; the shell in core/router.dart mounts it.
final dashboardTab = GoRoute(path: '/dashboard', builder: (_, _) => const DashboardScreen());

final dashboardRoutes = <RouteBase>[
  GoRoute(path: '/notifications', parentNavigatorKey: rootNav, builder: (_, _) => const NotificationsScreen()),
];
