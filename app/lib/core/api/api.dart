import 'package:flutter/foundation.dart';

import '../format.dart';
import 'models.dart';

export 'models.dart';

/// Demo scenarios: each one is a state `GET /journey` can return, so every board can be shown.
enum Scenario {
  newUser('New user, signed out'),
  onboarding('Sign-up half done'),
  inReview('With the credit team'),
  question('Security question'),
  rejected('Not approved'),
  offer('Approved, choose amount'),
  disbursal('Money sent, confirming'),
  active('Active, 7 days left'),
  dueToday('Due today'),
  overdue('3 days late'),
  extensionOffered('Extension offered (OTP)'),
  extended('Extended 1 of 3'),
  pendingVerification('Payment being verified'),
  closed('Repaid, borrow again');

  const Scenario(this.label);
  final String label;
}

/// The app's one door to the server.
///
/// ponytail: in-memory demo server so every journey runs on a phone before Phases 2–8 ship their
/// endpoints. Each method mirrors one planned endpoint; swapping the bodies for `dio` calls is the
/// whole change. Prices come from [_quote], which mirrors the server's LoanMath (PLAN Phase 1 table:
/// ₹1,000 → ₹742 net, ₹1,000 repaid); screens never compute money.
class Api extends ChangeNotifier {
  Api._();
  static final instance = Api._();

  /// Simulated network round trip. Tests set it to zero.
  Duration latency = const Duration(milliseconds: 650);

  /// When true every call fails with OFFLINE (board E3).
  bool _offline = false;
  bool get offline => _offline;
  set offline(bool v) {
    _offline = v;
    notifyListeners();
  }

  Scenario _scenario = Scenario.newUser;
  Scenario get scenario => _scenario;
  bool _signedIn = false;
  bool get signedIn => _signedIn;
  bool firstLaunch = true;
  int _otpTries = 0;
  int? _chosenPaise;
  Loan? _loan;
  final List<Loan> _closedLoans = [];
  final List<Txn> _txns = [];
  final List<Complaint> _complaints = [
    Complaint(id: 'GR-1042', subject: 'Payment not showing', status: 'Resolved', at: DateTime(2026, 9, 21), loanNo: 'DNL0000881'),
  ];
  final Set<String> _onboardingDone = {};

  // ---- demo controls ----

  void setScenario(Scenario s) {
    _scenario = s;
    _signedIn = s != Scenario.newUser;
    firstLaunch = s == Scenario.newUser && firstLaunch;
    _chosenPaise = null;
    _loan = _loanFor(s);
    _closedLoans
      ..clear()
      ..addAll(s == Scenario.closed ? [_closedLoan(_d(-20), 100000)] : [if (s.index >= Scenario.offer.index) _closedLoan(_d(-60), 100000)]);
    _txns
      ..clear()
      ..addAll(_txnsFor());
    _onboardingDone
      ..clear()
      ..addAll(s == Scenario.onboarding ? const ['start', 'otp', 'set-password', 'employment', 'employer'] : const []);
    notifyListeners();
  }

  /// Staff side, for the demo: the Accountant verifies the borrower's payment.
  void demoVerifyPayment() {
    final l = _loan;
    if (l == null) return;
    _closedLoans.insert(0, _copy(l, lifecycle: Lifecycle.closed, repayment: Repayment.paid, repaidPaise: l.outstandingPaise + l.repaidPaise, closedAt: DateTime.now(), closedOnTime: !l.isOverdue));
    _loan = null;
    _scenario = Scenario.closed;
    notifyListeners();
  }

  // ---- auth (S4–S6, A2, A3) ----

  Future<void> sendOtp(String mobile) => _call(() => _otpTries = 0);

  /// Demo: any six digits pass except 000000. Five wrong tries lock (E7).
  Future<void> verifyOtp(String code) => _call(() {
        if (_otpTries >= 5) throw const ApiException('OTP_LOCKED');
        if (code == '000000') {
          _otpTries++;
          throw ApiException(_otpTries >= 5 ? 'OTP_LOCKED' : 'OTP_INVALID');
        }
      });

  Future<void> login(String mobile, String password) => _call(() {
        // Demo: anything under the backend PasswordPolicy minimum (6) is a wrong password.
        if (password.length < 6) throw const ApiException('LOGIN_INVALID');
        if (_scenario == Scenario.newUser) setScenario(Scenario.active);
        _signedIn = true;
        notifyListeners();
      });

  Future<void> setPassword(String password) => _call(() {
        _signedIn = true;
        _onboardingDone.add('set-password');
        if (_scenario == Scenario.newUser) _scenario = Scenario.onboarding;
        notifyListeners();
      });

  Future<void> resetPassword(String password) => _call(() {});

  Future<void> logout() => _call(() {
        _signedIn = false;
        notifyListeners();
      });

  // ---- journey (E6) ----

  /// `GET /journey`: the stage and the route the app should open.
  Future<({Stage stage, String route})> journey() => _call(() {
        if (!_signedIn) return (stage: Stage.signedOut, route: firstLaunch ? '/intro' : '/welcome');
        return switch (_scenario) {
          Scenario.newUser || Scenario.onboarding => (stage: Stage.onboarding, route: '/resume'),
          Scenario.inReview => (stage: Stage.inReview, route: '/dashboard'),
          Scenario.question => (stage: Stage.question, route: '/credit-question'),
          Scenario.rejected => (stage: Stage.rejected, route: '/application-rejected'),
          Scenario.offer => (stage: Stage.offer, route: '/dashboard'),
          Scenario.disbursal => (stage: Stage.disbursal, route: '/dashboard'),
          Scenario.closed => (stage: Stage.closed, route: '/dashboard'),
          _ => (stage: Stage.active, route: '/dashboard'),
        };
      });

  /// Sync read of the stage for widgets that already loaded once.
  Stage get stage => switch (_scenario) {
        _ when !_signedIn => Stage.signedOut,
        Scenario.newUser || Scenario.onboarding => Stage.onboarding,
        Scenario.inReview => Stage.inReview,
        Scenario.question => Stage.question,
        Scenario.rejected => Stage.rejected,
        Scenario.offer => _chosenPaise == null ? Stage.offer : Stage.offerFlow,
        Scenario.disbursal => Stage.disbursal,
        Scenario.closed => Stage.closed,
        _ => Stage.active,
      };

  // ---- onboarding (A1–A12) ----

  /// Sign-up steps in order, as the server's journey lists them.
  static const signupSteps = ['start', 'otp', 'set-password', 'employment', 'employer', 'email', 'bank', 'payslips', 'aadhaar', 'pan-card', 'consent'];

  Set<String> get onboardingDone => Set.unmodifiable(_onboardingDone);

  String get nextSignupStep => signupSteps.firstWhere((s) => !_onboardingDone.contains(s), orElse: () => 'submitted');

  /// Saves one sign-up step. Data stays on the server; the demo only records that it is done.
  Future<void> saveStep(String step, [Map<String, Object?> data = const {}]) => _call(() {
        _onboardingDone.add(step);
        notifyListeners();
      });

  Future<void> submitApplication() => _call(() {
        _onboardingDone.add('consent');
        _scenario = Scenario.inReview;
        notifyListeners();
      });

  Future<void> answerQuestion(String answer) => _call(() {
        _scenario = Scenario.inReview;
        notifyListeners();
      });

  // ---- profile and credit level ----

  Future<Customer> me() => _call(() => _customer);

  Future<CreditLevel> creditLevel() => _call(() => _level);

  CreditLevel get _level {
    // First loan = L1; the demo's repaid scenario shows the step up to L2.
    final level = _scenario == Scenario.closed ? 2 : 1;
    final max = _ladder[level - 1];
    return CreditLevel(level: level, maxPaise: max, nextLevelPaise: level < 7 ? _ladder[level] : null, ladderPaise: _ladder);
  }

  // ---- offer (B1–B12) ----

  /// `product_config.allowed_amounts_paise`.
  static const _ladder = [100000, 200000, 300000, 400000, 500000, 700000, 1000000];

  Future<List<Quote>> quotes() => _call(() => [for (final p in _ladder) _quote(p)]);

  Future<Quote> quote(int principalPaise) => _call(() => _quote(principalPaise));

  int? get chosenPaise => _chosenPaise;

  Future<void> chooseAmount(int paise) => _call(() {
        if (paise > _level.maxPaise) throw const ApiException('AMOUNT_ABOVE_LEVEL');
        _chosenPaise = paise;
        notifyListeners();
      });

  Future<void> acceptKfs() => _call(() {});
  Future<void> sign() => _call(() {});
  Future<void> saveReferences(List<({String name, String mobile, String relation})> refs) => _call(() {});
  Future<void> uploadSelfie() => _call(() {});
  Future<void> saveAddress(String address) => _call(() {});

  /// B10: confirm where the money goes. Moves the application to disbursal (B12).
  Future<void> confirmDisbursalAccount() => _call(() {
        final p = _chosenPaise ?? _level.maxPaise;
        _loan = _loanAt(Lifecycle.disbursementPending, principal: p, daysLeft: 14);
        _scenario = Scenario.disbursal;
        notifyListeners();
      });

  Future<List<StatusStep>> applicationStatus() => _call(() {
        final net = inr(_quote(_loan?.quote.principalPaise ?? 100000).netPaise);
        final s = _scenario;
        final approved = s.index >= Scenario.offer.index;
        final sent = s.index >= Scenario.disbursal.index;
        final active = s.index > Scenario.disbursal.index;
        return [
          const StatusStep('Applied', detail: '4 Oct, 10:12', done: true),
          StatusStep('Under review', detail: 'Credit team checked your details', done: approved, current: !approved),
          StatusStep('Approved', detail: 'Up to ${inr(_level.maxPaise)}', done: approved),
          StatusStep('KFS accepted', detail: 'You read the Key Fact Statement', done: sent),
          StatusStep('Agreement signed', detail: 'Aadhaar e-sign', done: sent),
          StatusStep('Money sent', detail: '$net to ${_customer.bankName} ${_customer.bankMasked}', done: sent),
          StatusStep('Confirming with the bank', detail: 'Usually within a few hours. Your 14 days start once confirmed', done: active, current: s == Scenario.disbursal),
          StatusStep('Active', detail: 'Repay by day 14, 11:59 pm', done: active, current: active),
          const StatusStep('Repaid and closed', detail: 'Unlocks your next level'),
        ];
      });

  // ---- loan, repay, documents (C1–C14) ----

  /// The open loan, or null.
  Future<Loan?> currentLoan() => _call(() => _loan);

  /// Sync read for widgets rebuilt by [notifyListeners].
  Loan? get loan => _loan;

  Future<List<Loan>> loans() => _call(() => [?_loan, ..._closedLoans]);

  Future<List<Txn>> transactions() => _call(() => List.of(_txns));

  Future<List<LoanDoc>> documents() => _call(() => const [
        LoanDoc('Key Fact Statement', 'PDF · 2 pages · accepted 5 Oct'),
        LoanDoc('Sanction letter', 'PDF · 3 pages · SL-20417'),
        LoanDoc('Loan agreement', 'PDF · 6 pages · e-signed 5 Oct'),
      ]);

  Future<PayTo> payTo() => _call(() => const PayTo(upiId: 'dhannow.collect@hdfcbank', accountName: 'DhanNow collections', accountNo: '50200091234567', ifsc: 'HDFC0000123', bank: 'HDFC Bank'));

  /// C6: the borrower reports a payment. The Accountant verifies it later (on-time is judged by
  /// [paidOn], the bank credit date, not the verification time).
  Future<void> submitPayment({required String utr, required DateTime paidOn, required int paise, bool screenshot = false}) => _call(() {
        if (utr == '000000000000') throw const ApiException('UTR_DUPLICATE');
        final l = _loan;
        if (l == null) return;
        _loan = _copy(l, repayment: Repayment.pendingVerification);
        _txns.insert(0, Txn(at: paidOn, title: 'Repayment sent', paise: paise, credit: false, ref: utr, status: 'Being verified'));
        _scenario = Scenario.pendingVerification;
        notifyListeners();
      });

  Future<List<Notice>> notices() => _call(() => [
        if (_loan != null && _loan!.isOverdue) Notice(at: _d(0), title: 'Your repayment is late', body: 'Pay ${inr(_loan!.outstandingPaise)} today to stop the late charge growing.', route: '/repay'),
        if (_scenario == Scenario.extensionOffered) Notice(at: _d(0), title: 'Extension ready to confirm', body: 'Confirm with the code we sent to move your due date by 14 days.', route: '/extension/confirm'),
        Notice(at: _d(-1), title: 'Due in 7 days', body: 'Repay ${inr(100000)} by 11:59 pm on the due date.', route: '/repay'),
        Notice(at: _d(-7), title: 'Money sent', body: '${inr(74200)} is on its way to your bank.', read: true, route: '/loans'),
        Notice(at: _d(-8), title: 'Loan approved', body: 'You can borrow up to ${inr(100000)}.', read: true),
      ]);

  // ---- extension (C12–C12d), EMI (C13) ----

  Future<ExtensionOffer?> extensionOffer() => _call(() {
        final l = _loan;
        if (_scenario != Scenario.extensionOffered || l == null) return null;
        final q = _quote(l.quote.principalPaise);
        return ExtensionOffer(
          n: l.extensionsUsed + 1,
          cap: l.extensionCap,
          feePaise: q.feePaise,
          gstPaise: q.gstPaise,
          interestPaise: q.interestPaise,
          latePaise: l.latePaise,
          totalPaise: q.deductionsPaise + l.latePaise,
          oldDue: l.dueAt,
          newDue: l.dueAt.add(const Duration(days: 14)),
          expiresAt: _d(1),
          officer: 'Priya (collections)',
        );
      });

  Future<void> confirmExtension(String otp) => verifyOtp(otp);

  /// C12b: the borrower pays the extension charge; the Accountant verifies, then due moves (C12c).
  Future<void> payExtension({required String utr, required DateTime paidOn}) => _call(() {
        final l = _loan!;
        final q = _quote(l.quote.principalPaise);
        final ext = Extension(n: l.extensionsUsed + 1, chargePaise: q.deductionsPaise + l.latePaise, oldDue: l.dueAt, newDue: l.dueAt.add(const Duration(days: 14)), status: ExtensionStatus.verified, utr: utr);
        _loan = _copy(l, lifecycle: Lifecycle.active, dueAt: ext.newDue, daysLeft: l.daysLeft + 14, latePaise: 0, dpd: 0, extensionsUsed: ext.n, extensions: [...l.extensions, ext]);
        _txns.insert(0, Txn(at: paidOn, title: 'Extension ${ext.n} charge', paise: ext.chargePaise, credit: false, ref: utr));
        _scenario = Scenario.extended;
        notifyListeners();
      });

  /// Flag `emi-conversion` is OFF, so the app never offers this in production yet.
  Future<EmiOffer?> emiOffer() => _call(() => null);

  // ---- account (D1–D8) ----

  Future<List<Complaint>> complaints() => _call(() => List.of(_complaints));

  Future<Complaint> raiseComplaint({required String subject, required String body}) => _call(() {
        final c = Complaint(id: 'GR-${1043 + _complaints.length}', subject: subject, status: 'Open', at: DateTime.now(), loanNo: _loan?.accountNo);
        _complaints.insert(0, c);
        return c;
      });

  Future<List<Device>> devices() => _call(() => [
        Device(name: 'This phone · Pixel 6', where: 'Delhi', lastActive: DateTime.now(), current: true),
        Device(name: 'Redmi Note 12', where: 'Gurugram', lastActive: _d(-12)),
      ]);

  Future<void> signOutDevice(String name) => _call(() {});

  /// D8: blocked while a loan is open.
  Future<void> requestDeletion() => _call(() {
        if (_loan != null) throw const ApiException('LOAN_OPEN');
      });

  // ---- internals ----

  Future<T> _call<T>(T Function() body) async {
    if (latency > Duration.zero) await Future<void>.delayed(latency);
    if (_offline) throw const ApiException('OFFLINE');
    return body();
  }

  /// Mirrors LoanMath (UPFRONT): fee 10%, GST 18% of fee, 14 × 1% interest, all deducted upfront;
  /// the borrower repays the principal.
  static Quote _quote(int p) {
    final fee = p * 10 ~/ 100;
    final gst = fee * 18 ~/ 100;
    final interest = p * 14 ~/ 100;
    return Quote(principalPaise: p, feePaise: fee, gstPaise: gst, interestPaise: interest, netPaise: p - fee - gst - interest, repayPaise: p, feeRateBps: 1000, gstRateBps: 1800, dailyRateBps: 100);
  }

  static DateTime _d(int days) {
    final n = DateTime.now();
    return DateTime(n.year, n.month, n.day).add(Duration(days: days));
  }

  static DateTime _dueAt(DateTime day) => DateTime(day.year, day.month, day.day, 23, 59, 59);

  Loan? _loanFor(Scenario s) => switch (s) {
        Scenario.disbursal => _loanAt(Lifecycle.disbursementPending, daysLeft: 14),
        Scenario.active => _loanAt(Lifecycle.active, daysLeft: 7),
        Scenario.dueToday => _loanAt(Lifecycle.dueToday, daysLeft: 0),
        Scenario.overdue || Scenario.extensionOffered => _loanAt(Lifecycle.overdueD1, daysLeft: -3, latePaise: 6000, dpd: 3),
        Scenario.extended => _extendedLoan(),
        Scenario.pendingVerification => _copy(_loanAt(Lifecycle.active, daysLeft: 7), repayment: Repayment.pendingVerification),
        _ => null,
      };

  Loan _loanAt(Lifecycle lc, {int principal = 100000, required int daysLeft, int latePaise = 0, int dpd = 0}) {
    final disbursed = _d(daysLeft - 14);
    return Loan(
      id: 'L-20417',
      accountNo: 'DNL0001207',
      applicationNo: '20417',
      quote: _quote(principal),
      disbursedOn: disbursed,
      dueAt: _dueAt(disbursed.add(const Duration(days: 14))),
      daysLeft: daysLeft,
      lifecycle: lc,
      latePaise: latePaise,
      dpd: dpd,
      disbursalUtr: 'HDFCN52026110412907',
    );
  }

  Loan _extendedLoan() {
    final base = _loanAt(Lifecycle.active, daysLeft: 9);
    final oldDue = base.dueAt.subtract(const Duration(days: 14));
    return _copy(base, extensionsUsed: 1, extensions: [
      Extension(n: 1, chargePaise: 25800 + 6000, oldDue: oldDue, newDue: base.dueAt, status: ExtensionStatus.verified, utr: '429017733201'),
    ]);
  }

  Loan _closedLoan(DateTime disbursed, int principal) => Loan(
        id: 'L-19003',
        accountNo: 'DNL0000881',
        applicationNo: '19003',
        quote: _quote(principal),
        disbursedOn: disbursed,
        dueAt: _dueAt(disbursed.add(const Duration(days: 14))),
        daysLeft: 0,
        lifecycle: Lifecycle.closed,
        repayment: Repayment.paid,
        repaidPaise: principal,
        closedAt: disbursed.add(const Duration(days: 12)),
        closedOnTime: true,
      );

  List<Txn> _txnsFor() => [
        if (_loan != null && _loan!.lifecycle != Lifecycle.disbursementPending) Txn(at: _loan!.disbursedOn, title: 'Loan money received', paise: _loan!.quote.netPaise, credit: true, ref: _loan!.disbursalUtr),
        for (final c in _closedLoans) ...[
          Txn(at: c.closedAt!, title: 'Repayment', paise: c.quote.repayPaise, credit: false, ref: '42901${c.applicationNo}'),
          Txn(at: c.disbursedOn, title: 'Loan money received', paise: c.quote.netPaise, credit: true, ref: 'HDFCN5${c.applicationNo}'),
        ],
      ];

  Loan _copy(Loan l, {Lifecycle? lifecycle, Repayment? repayment, int? repaidPaise, DateTime? closedAt, bool? closedOnTime, DateTime? dueAt, int? daysLeft, int? latePaise, int? dpd, int? extensionsUsed, List<Extension>? extensions}) => Loan(
        id: l.id,
        accountNo: l.accountNo,
        applicationNo: l.applicationNo,
        quote: l.quote,
        disbursedOn: l.disbursedOn,
        dueAt: dueAt ?? l.dueAt,
        daysLeft: daysLeft ?? l.daysLeft,
        lifecycle: lifecycle ?? l.lifecycle,
        hold: l.hold,
        repayment: repayment ?? l.repayment,
        repaidPaise: repaidPaise ?? l.repaidPaise,
        latePaise: latePaise ?? l.latePaise,
        dpd: dpd ?? l.dpd,
        extensionsUsed: extensionsUsed ?? l.extensionsUsed,
        extensionCap: l.extensionCap,
        extensions: extensions ?? l.extensions,
        closedAt: closedAt ?? l.closedAt,
        closedOnTime: closedOnTime ?? l.closedOnTime,
        disbursalUtr: l.disbursalUtr,
      );

  static const _customer = Customer(
    id: 'DN000123',
    firstName: 'Aarav',
    fullName: 'Aarav Sharma',
    mobileMasked: '+91 ••••• •3210',
    email: 'aarav.s@example.com',
    panMasked: 'ABCPS••••K',
    aadhaarMasked: 'XXXX XXXX 4821',
    dob: '12 Mar 1996',
    address: '14, Sector 21, Dwarka, New Delhi 110075',
    employer: 'Acme Logistics Pvt Ltd',
    monthlyIncomePaise: 3500000,
    bankName: 'HDFC Bank',
    bankMasked: '••5678',
    ifsc: 'HDFC0001234',
    referralCode: 'AARAV50',
  );
}

/// Short global handle, like a provider would give.
Api get api => Api.instance;

/// Debug-only guard for the demo controls screen.
bool get demoControls => kDebugMode || const bool.fromEnvironment('DEMO', defaultValue: true);
