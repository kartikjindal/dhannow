import 'package:dhannow/core/router.dart';
import 'package:go_router/go_router.dart';

import 'auth.dart';
import 'splash.dart';

/// Boards S1–S6.
final startRoutes = <RouteBase>[
  GoRoute(path: '/', parentNavigatorKey: rootNav, builder: (_, _) => const SplashScreen()),
  GoRoute(path: '/intro', parentNavigatorKey: rootNav, builder: (_, _) => const IntroScreen()),
  GoRoute(path: '/welcome', parentNavigatorKey: rootNav, builder: (_, _) => const WelcomeScreen()),
  GoRoute(path: '/login', parentNavigatorKey: rootNav, builder: (_, _) => const LoginScreen()),
  GoRoute(path: '/forgot-password', parentNavigatorKey: rootNav, builder: (_, _) => const ForgotPasswordScreen()),
  GoRoute(path: '/reset-password', parentNavigatorKey: rootNav, builder: (_, _) => const ResetPasswordScreen()),
];
