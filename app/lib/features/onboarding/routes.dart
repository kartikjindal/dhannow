import 'package:dhannow/core/router.dart';
import 'package:dhannow/core/format.dart';
import 'package:flutter/widgets.dart';
import 'package:go_router/go_router.dart';

import 'after.dart';
import 'common.dart';
import 'details.dart';
import 'kyc.dart';
import 'work.dart';

/// Sign-up journey A1–A14 and the paused-applications gate E13/E13b. All full screen.
final onboardingRoutes = <RouteBase>[
  _step('/signup/start', 'start', 1, 'Your details', const YourDetailsScreen(), (d) => {'PAN': d['pan'] ?? '', 'Mobile': d['mobile'] ?? ''}, locked: true),
  _step('/signup/otp', 'otp', 2, 'Verify your mobile', const VerifyMobileScreen(), (_) => {'Mobile': saved('start')['mobile'] ?? ''}, locked: true),
  _step(
    '/signup/set-password',
    'set-password',
    3,
    'Set a password',
    const SetPasswordScreen(),
    (d) => d.isEmpty ? {} : {'Password': d['password'] == 'set' ? 'Set' : 'Not set yet'},
  ),
  _step(
    '/signup/employment',
    'employment',
    4,
    'Employment status',
    const EmploymentScreen(),
    (d) => {'Employment': d['type'] == 'self-employed' ? 'Self-employed' : 'Salaried'},
  ),
  _step(
    '/signup/employer',
    'employer',
    5,
    'Employer & salary',
    const EmployerScreen(),
    (d) => {'Company': d['company'] ?? '', 'Monthly salary': inr((int.tryParse(d['salary'] ?? '') ?? 0) * 100)},
  ),
  _step(
    '/signup/self-employed',
    'employer',
    5,
    'Employer & salary',
    const SelfEmployedScreen(),
    (d) => {'Name': d['name'] ?? '', 'Annual income': '₹${d['annualIncome'] ?? ''}'},
  ),
  _step(
    '/signup/email',
    'email',
    6,
    'Email addresses',
    const EmailsScreen(),
    (d) => {'Personal email': d['personal'] ?? '', 'Work email': '${d['work'] ?? ''}${d['workStatus'] == 'undeliverable' ? ' (our team will confirm)' : ''}'},
  ),
  _step(
    '/signup/bank',
    'bank',
    7,
    'Salary account',
    const SalaryAccountScreen(),
    (d) => {'Bank': d['bank'] ?? '', 'Account': d['account'] ?? '', 'IFSC': d['ifsc'] ?? '', 'Statements': '${d['statements'] ?? ''} added'},
  ),
  _step('/signup/payslips', 'payslips', 8, 'Salary slips', const PayslipsScreen(), (d) => {for (final v in d.values) v: 'Already uploaded'}),
  _step(
    '/signup/aadhaar',
    'aadhaar',
    9,
    'Aadhaar card',
    const AadhaarScreen(),
    (d) => {'Aadhaar': 'XXXX XXXX ${d['last4'] ?? '••••'}', 'Front and back': 'Already uploaded'},
  ),
  _step('/signup/pan-card', 'pan-card', 10, 'PAN card', const PanCardScreen(), (_) => {'Front and back': 'Already uploaded'}),
  _step('/signup/consent', 'consent', 11, 'Consent & verification', const ConsentScreen(), (_) => {'Credit check': 'Authorised'}, locked: true),
  _full('/signup/submitted', (_) => const SubmittedScreen()),
  _full('/credit-question', (_) => const CreditQuestionScreen()),
  _full('/application-rejected', (_) => const RejectedScreen()),
  _full('/apply', (_) => const ApplyGateScreen()),
  _full('/apply/submitted', (s) => ApplySubmittedScreen(data: s.extra as Map<String, String>?)),
];

GoRoute _full(String path, Widget Function(GoRouterState s) build) => GoRoute(path: path, parentNavigatorKey: rootNav, builder: (_, s) => build(s));

GoRoute _step(String path, String step, int n, String title, Widget form, Map<String, String> Function(Map<String, String> d) rows, {bool locked = false}) =>
    _full(path, (_) => StepGate(step: step, n: n, title: title, rows: rows, locked: locked, child: form));
