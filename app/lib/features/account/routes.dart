import 'package:dhannow/core/router.dart';
import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'help_screens.dart';
import 'profile_screen.dart';
import 'settings_screen.dart';

/// Tab root (D1 Profile). Owned by this feature; the shell in core/router.dart mounts it.
final profileTab = GoRoute(path: '/profile', builder: (_, _) => const ProfileScreen());

GoRoute _full(String path, Widget page) => GoRoute(path: path, parentNavigatorKey: rootNav, builder: (_, _) => page);

/// D2–D8, full-screen over the tab bar. `/support` also opens logged out.
final accountRoutes = <RouteBase>[
  _full('/settings', const SettingsScreen()),
  _full('/support', const SupportScreen()),
  _full('/trust', const TrustScreen()),
  _full('/complaints', const ComplaintsScreen()),
  _full('/complaints/new', const NewComplaintScreen()),
  _full('/loan-account', const LoanAccountScreen()),
  _full('/devices', const DevicesScreen()),
  _full('/delete-account', const DeleteAccountScreen()),
];
