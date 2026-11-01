import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/format.dart';
import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/core/widgets/widgets.dart';
import 'package:dhannow/features/dashboard/milestones.dart';
import 'package:dhannow/features/dashboard/notifications_screen.dart';
import 'package:dhannow/features/dashboard/routes.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';

const _stubs = [
  '/repay', '/loans', '/transactions', '/support', '/complaints', '/resume', '/loan/status', '/loan/amount', //
  '/reloan', '/credit-question', '/application-rejected', '/extension/confirm',
];

Widget _app({String at = '/dashboard', double scale = 1, bool reduceMotion = false}) => MaterialApp.router(
      theme: dnTheme(),
      routerConfig: GoRouter(navigatorKey: rootNav, initialLocation: at, routes: [
        dashboardTab,
        ...dashboardRoutes,
        for (final p in _stubs) GoRoute(path: p, builder: (_, _) => Scaffold(body: Text('stub $p'))),
        GoRoute(
          path: '/probe',
          builder: (_, _) => Scaffold(body: Builder(builder: (c) => TextButton(onPressed: () => openNoticeRoute(c, '/gone/123'), child: const Text('probe')))),
        ),
      ]),
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(textScaler: TextScaler.linear(scale), disableAnimations: reduceMotion),
        child: child!,
      ),
    );

void _size(WidgetTester t, double width) {
  t.view.physicalSize = Size(width * 3, 1600 * 3);
  t.view.devicePixelRatio = 3;
  addTearDown(t.view.reset);
}

Future<void> _scrollAll(WidgetTester t, Finder list) async {
  for (var i = 0; i < 12; i++) {
    await t.drag(list, const Offset(0, -500));
    await t.pump();
  }
}

Future<void> _tap(WidgetTester t, String text) async {
  await t.scrollUntilVisible(find.text(text), 200, scrollable: find.byType(Scrollable).first);
  await t.pumpAndSettle();
  await t.tap(find.text(text).first);
  await t.pumpAndSettle();
}

/// Pumps frame by frame until a [W] is first on screen: frame 0 of its animation.
Future<void> _firstFrameWith<W extends Widget>(WidgetTester t) async {
  for (var i = 0; i < 10 && find.byType(W).evaluate().isEmpty; i++) {
    await t.pump();
  }
}

void main() {
  setUp(() {
    LevelUpCard.played.clear();
    demo.latency = Duration.zero;
    demo.offline = false;
    demo.setScenario(Scenario.active);
  });

  // CC-58: every dashboard state at 320dp × 2.0 and 412dp × 1.0, scrolled through.
  for (final (width, scale) in [(320.0, 2.0), (412.0, 1.0)]) {
    for (final s in [
      Scenario.active, Scenario.dueToday, Scenario.overdue, Scenario.extensionOffered, Scenario.extended, Scenario.pendingVerification, //
      Scenario.onboarding, Scenario.inReview, Scenario.question, Scenario.rejected, Scenario.offer, Scenario.disbursal, Scenario.closed,
    ]) {
      testWidgets('dashboard ${s.name} lays out at ${width.toInt()}dp, text ×$scale', (t) async {
        _size(t, width);
        demo.setScenario(s);
        await t.pumpWidget(_app(scale: scale));
        await t.pumpAndSettle();
        expect(find.text('Hi, Aarav'), findsOneWidget);
        if (find.text('View more').evaluate().isNotEmpty) await _tap(t, 'View more');
        await _scrollAll(t, find.byType(Scrollable).first);
      });
    }

    testWidgets('loan details sheet lays out at ${width.toInt()}dp, text ×$scale', (t) async {
      _size(t, width);
      demo.setScenario(Scenario.extended);
      await t.pumpWidget(_app(scale: scale));
      await t.pumpAndSettle();
      await _tap(t, 'View full breakdown');
      expect(find.text('Amount disbursed to you'), findsOneWidget);
      await _scrollAll(t, find.byType(Scrollable).last);
    });

    testWidgets('notifications lay out at ${width.toInt()}dp, text ×$scale', (t) async {
      _size(t, width);
      await t.pumpWidget(_app(at: '/notifications', scale: scale));
      await t.pumpAndSettle();
      expect(find.text('Due in 7 days'), findsOneWidget);
      await _scrollAll(t, find.byType(Scrollable).first);
    });
  }

  testWidgets('C1: repay now opens Repay; links stay folded until View more', (t) async {
    _size(t, 412);
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    expect(find.text('How to repay'), findsNothing);
    await _tap(t, 'View more');
    expect(find.text('View less'), findsOneWidget);
    expect(find.text('₹742 received on ${dayMonth(api.loan!.disbursedOn)}'), findsOneWidget);
    await _tap(t, 'Repay now');
    expect(find.text('stub /repay'), findsOneWidget);
  });

  testWidgets('C5: breakdown shows the server quote and the account number', (t) async {
    _size(t, 412);
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    await _tap(t, 'View full breakdown');
    expect(find.text('Loan #20417'), findsOneWidget);
    expect(find.text('₹742'), findsOneWidget);
    expect(find.text('− ₹100'), findsOneWidget);
    expect(find.text('DNL0001207'), findsOneWidget);
    expect(find.textContaining('11:59 pm'), findsWidgets);
  });

  testWidgets('C2: overdue shows the late charge and pays the outstanding', (t) async {
    _size(t, 412);
    demo.setScenario(Scenario.overdue);
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    expect(find.text('Your repayment is late'), findsOneWidget);
    expect(find.text('3 days past due'.toUpperCase()), findsOneWidget);
    expect(find.text('Includes ₹60 late charge to date'), findsOneWidget);
    expect(find.byType(DnCountdownRing), findsNothing);
    await _tap(t, 'Pay ₹1,060 now');
    expect(find.text('stub /repay'), findsOneWidget);
  });

  testWidgets('C1b: extended loan shows the chip and the new due date', (t) async {
    _size(t, 412);
    demo.setScenario(Scenario.extended);
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    expect(find.text('Extended 1 of 3'), findsWidgets);
    expect(find.text('New due date'), findsOneWidget);
    expect(find.textContaining('Extended loans keep your level'), findsOneWidget);
  });

  testWidgets('payment being verified shows the banner', (t) async {
    _size(t, 412);
    demo.setScenario(Scenario.pendingVerification);
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    expect(find.text('We’re checking your payment'), findsOneWidget);
  });

  for (final (s, button, route) in [
    (Scenario.onboarding, 'Continue sign-up', '/resume'),
    (Scenario.inReview, 'Track my application', '/loan/status'),
    (Scenario.disbursal, 'Track my application', '/loan/status'),
    (Scenario.offer, 'Get ₹1,000', '/loan/amount'),
    (Scenario.closed, 'Get ₹2,000', '/reloan'),
  ]) {
    testWidgets('${s.name}: "$button" opens $route', (t) async {
    _size(t, 412);
      demo.setScenario(s);
      await t.pumpWidget(_app());
      await t.pumpAndSettle();
      await _tap(t, button);
      expect(find.text('stub $route'), findsOneWidget);
    });
  }

  testWidgets('load error shows retry, and retry recovers', (t) async {
    _size(t, 412);
    demo.offline = true;
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    expect(find.text('Couldn’t load your dashboard'), findsOneWidget);
    expect(find.text('Get help'), findsOneWidget);
    await _tap(t, 'Try again');
    expect(find.text('Couldn’t load your dashboard'), findsOneWidget);
    demo.offline = false;
    await t.pumpAndSettle();
    expect(find.text('Hi, Aarav'), findsOneWidget);
  });

  testWidgets('C11: bell opens notifications and a notice opens its screen', (t) async {
    _size(t, 412);
    demo.setScenario(Scenario.overdue);
    await t.pumpWidget(_app());
    await t.pumpAndSettle();
    await t.tap(find.byTooltip('Notifications'));
    await t.pumpAndSettle();
    await _tap(t, 'Your repayment is late');
    expect(find.text('stub /repay'), findsOneWidget);
  });

  testWidgets('CC-53: a notice route that no longer resolves lands on the dashboard with a toast', (t) async {
    _size(t, 412);
    await t.pumpWidget(_app(at: '/probe'));
    await t.pumpAndSettle();
    await t.tap(find.text('probe'));
    await t.pumpAndSettle();
    expect(find.text('Hi, Aarav'), findsOneWidget);
    expect(find.text('That item is no longer available.'), findsOneWidget);
  });

  group('F4 motion', () {
    testWidgets('ring sweeps from 0 on open', (t) async {
    _size(t, 412);
      await t.pumpWidget(_app());
      await _firstFrameWith<DnCountdownRing>(t);
      expect(t.widget<DnCountdownRing>(find.byType(DnCountdownRing)).tenureDays, 1 << 30);
      await t.pumpAndSettle();
      expect(t.widget<DnCountdownRing>(find.byType(DnCountdownRing)).tenureDays, 14);
    });

    testWidgets('level-up fills the next segment once', (t) async {
    _size(t, 412);
      demo.setScenario(Scenario.closed);
      await t.pumpWidget(_app());
      await _firstFrameWith<DnLevelBar>(t);
      expect(t.widget<DnLevelBar>(find.byType(DnLevelBar)).level, 1);
      await t.pumpAndSettle();
      expect(t.widget<DnLevelBar>(find.byType(DnLevelBar)).level, 2);
      expect(find.text('Level 2 unlocked'), findsOneWidget);
      expect(LevelUpCard.played, {2});
    });

    testWidgets('reduced motion: final state on the first frame', (t) async {
    _size(t, 412);
      demo.setScenario(Scenario.closed);
      await t.pumpWidget(_app(reduceMotion: true));
      await _firstFrameWith<DnLevelBar>(t);
      expect(t.widget<DnLevelBar>(find.byType(DnLevelBar)).level, 2);
      demo.setScenario(Scenario.active);
      await _firstFrameWith<DnCountdownRing>(t);
      expect(t.widget<DnCountdownRing>(find.byType(DnCountdownRing)).tenureDays, 14);
    });

    testWidgets('late repayment: plain "Loan closed", no level-up', (t) async {
    _size(t, 412);
      const q = Quote(principalPaise: 100000, feePaise: 10000, gstPaise: 1800, interestPaise: 14000, netPaise: 74200, repayPaise: 100000, feeRateBps: 1000, gstRateBps: 1800, dailyRateBps: 100);
      final late = Loan(
        id: 'L-1', accountNo: 'DNL1', applicationNo: '1', quote: q, disbursedOn: DateTime(2026, 10, 1), dueAt: DateTime(2026, 10, 15, 23, 59), //
        daysLeft: 0, lifecycle: Lifecycle.closed, closedOnTime: false,
      );
      const lv = CreditLevel(level: 2, maxPaise: 200000, nextLevelPaise: 300000, ladderPaise: [100000, 200000]);
      await t.pumpWidget(MaterialApp(theme: dnTheme(), home: Scaffold(body: LevelUpCard(level: lv, closed: late))));
      expect(find.text('Loan closed'), findsOneWidget);
      expect(find.byType(DnLevelBar), findsNothing);
      expect(LevelUpCard.played, isEmpty);
    });
  });
}
