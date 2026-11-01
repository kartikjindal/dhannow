import 'package:flutter/foundation.dart' show ChangeNotifier;

import '../brand.dart';
import 'demo_api.dart';
import 'http_api.dart';
import 'models.dart';

export 'demo_api.dart' show DemoApi, Scenario;
export 'models.dart';

/// `--dart-define=DEMO=true` runs the in-memory demo server (screenshots, reviews); default is the
/// real backend at `--dart-define=API_BASE` (default `https://api.dhannow.com`).
const demoMode = bool.fromEnvironment('DEMO');
const apiBase = String.fromEnvironment('API_BASE', defaultValue: 'https://api.dhannow.com');

/// The app's one door to the server. Screens call these methods and read the sync getters; they
/// never compute money. [HttpApi] talks to the backend, [DemoApi] mirrors it in memory. Every
/// change of server state calls [notifyListeners], and screens built on `Loaded` refetch then.
abstract class Api extends ChangeNotifier {
  /// Sign-up steps in order, as the server's journey lists them.
  static const signupSteps = ['start', 'otp', 'set-password', 'employment', 'employer', 'email', 'bank', 'payslips', 'aadhaar', 'pan-card', 'consent'];

  bool firstLaunch = true;
  bool get signedIn;

  /// The last call failed for want of a connection (the demo can force it).
  bool get offline;
  Stage get stage;
  Loan? get loan;
  int? get chosenPaise;
  Set<String> get onboardingDone;
  String get nextSignupStep => signupSteps.firstWhere((s) => !onboardingDone.contains(s), orElse: () => 'submitted');

  /// Contacts, legal links, flags. [AppConfig.fallback] until `GET /api/app/config` answers.
  AppConfig config = fallbackConfig;

  /// Refetch server state (app resumed, pull-to-refresh); screens on `Loaded` reload after it.
  Future<void> refresh() async => notifyListeners();

  // ---- auth (S4–S6, A2, A3) ----
  /// [consent]: the bureau-consent code on A11 (sent to the mobile on file, checked on submit).
  Future<void> sendOtp(String mobile, {bool consent = false});

  /// Signs in with the code (sign-up, forgot password, E5). With [consent] it only keeps the code
  /// for [submitApplication].
  Future<void> verifyOtp(String code, {bool consent = false});
  Future<void> login(String mobile, String password);
  Future<void> setPassword(String password);
  Future<void> resetPassword(String password);
  Future<void> logout();

  // ---- journey (E6) ----
  Future<({Stage stage, String route})> journey();

  // ---- onboarding (A1–A13) ----
  Future<void> saveStep(String step, [Map<String, Object?> data = const {}]);
  Future<void> submitApplication({DateTime? dob});
  Future<void> answerQuestion(String answer);

  // ---- profile and level ----
  Future<Customer> me();
  Future<CreditLevel> creditLevel();

  // ---- offer (B1–B12) ----
  Future<List<Quote>> quotes();
  Future<Quote> quote(int principalPaise);
  Future<void> chooseAmount(int paise);
  Future<void> acceptKfs();
  Future<void> sign();
  Future<void> saveReferences(List<({String name, String mobile, String relation})> refs);
  Future<void> uploadSelfie();
  Future<void> saveAddress(String address);
  Future<void> confirmDisbursalAccount();
  Future<List<StatusStep>> applicationStatus();

  /// Offer steps whose provider hand-off (DigiLocker, e-sign, eNACH) is not in the app yet.
  Future<void> simulate();

  // ---- loan, repay, documents (C1–C14) ----
  Future<Loan?> currentLoan();
  Future<List<Loan>> loans();
  Future<List<Txn>> transactions();
  Future<List<LoanDoc>> documents();

  /// A short-lived link to one document (5 minutes), opened in the browser.
  Future<String?> documentUrl(LoanDoc doc);
  Future<PayTo> payTo();
  Future<void> submitPayment({required String utr, required DateTime paidOn, required int paise, bool screenshot = false});
  Future<List<Notice>> notices();

  // ---- extension (C12–C12d), EMI (C13) ----
  Future<ExtensionOffer?> extensionOffer();
  Future<void> sendExtensionOtp();

  /// [reason] `NOT_NOW` or `DID_NOT_ASK` (EXT: the borrower never asked for it).
  Future<void> declineExtension(String reason);
  Future<void> confirmExtension(String otp);
  Future<void> payExtension({required String utr, required DateTime paidOn});
  Future<EmiOffer?> emiOffer();
  Future<void> sendEmiOtp(EmiOffer offer);
  Future<void> acceptEmi(EmiOffer offer, String otp);

  // ---- account (D1–D8) ----
  Future<List<Complaint>> complaints();
  Future<Complaint> raiseComplaint({required String subject, required String body, Loan? loan});
  Future<List<Device>> devices();
  Future<void> signOutDevice(Device device);
  Future<void> requestDeletion();
}

const fallbackConfig = AppConfig(
  minVersion: '0.0.0',
  support: Contact('${Brand.name} support', Brand.supportPhone, Brand.supportEmail),
  grievance: Contact(Brand.grievanceName, Brand.grievancePhone, Brand.grievanceEmail),
);

/// The app's server. Tests swap in a [DemoApi] (see `test/flutter_test_config.dart`).
Api api = demoMode ? DemoApi() : HttpApi.create(apiBase);

/// The demo server, for the demo controls and tests. Only valid when [demoControls] is true.
DemoApi get demo => api as DemoApi;

/// Demo states, the kit gallery and their entry points exist only on the demo server.
bool get demoControls => api is DemoApi;

