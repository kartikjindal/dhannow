import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/router.dart';
import 'emi.dart';
import 'extension.dart';
import 'loans.dart';
import 'repay.dart';

/// Tab roots (C6 Repay, C8 Loans). Owned by this feature; the shell in core/router.dart mounts them.
/// C7 stays inside the Repay tab (the board keeps the bottom bar); C14 covers it.
final repayTab = GoRoute(
  path: '/repay',
  builder: (_, _) => const RepayScreen(),
  routes: [GoRoute(path: 'sent', builder: (_, _) => const PaymentSentScreen())],
);
final loansTab = GoRoute(
  path: '/loans',
  builder: (_, _) => const LoansScreen(),
  routes: [GoRoute(path: 'closed', parentNavigatorKey: rootNav, builder: (_, s) => ClosureScreen(loan: s.extra is Loan ? s.extra as Loan : null))],
);

final moneyRoutes = <RouteBase>[
  GoRoute(path: '/transactions', parentNavigatorKey: rootNav, builder: (_, _) => const TransactionsScreen()),
  GoRoute(path: '/reloan', parentNavigatorKey: rootNav, builder: (_, _) => const ReloanScreen()),
  GoRoute(path: '/extension/confirm', parentNavigatorKey: rootNav, builder: (_, _) => const ExtensionConfirmScreen()),
  GoRoute(path: '/extension/pay', parentNavigatorKey: rootNav, builder: (_, _) => const ExtensionPayScreen()),
  GoRoute(path: '/extension/done', parentNavigatorKey: rootNav, builder: (_, _) => const ExtensionDoneScreen()),
  GoRoute(path: '/emi', parentNavigatorKey: rootNav, builder: (_, s) => EmiScreen(offer: s.extra is EmiOffer ? s.extra as EmiOffer : null)),
];
