// Hand-written response models (PLAN Phase 11: no codegen). Field names follow the planned API
// (`GET /journey`, `/api/me/credit-level`, loan and payment endpoints). All money is integer paise
// sent by the server; the app never derives a fee, interest or total.

/// Where `GET /journey` sends the borrower (wireframe rule E6: the app always opens here).
enum Stage {
  signedOut,
  onboarding, // sign-up steps A1–A11 not finished
  inReview, // A12
  question, // A13 security question
  rejected, // A14
  offer, // pre-approved, choose amount (B1 / C4)
  offerFlow, // inside B2–B11
  disbursal, // B12, money sent and being confirmed
  active,
  closed, // repaid, can borrow again (C4 / C10)
}

/// Server enum name (`OVERDUE_D1`) to the app's value; anything new decodes to [fallback] so an
/// older app renders a safe state instead of crashing (Phase 11 rule).
T decodeEnum<T extends Enum>(Object? wire, Map<String, T> names, T fallback) => names['$wire'] ?? fallback;

/// Loan `lifecycle_status` (PLAN Phase 3).
enum Lifecycle {
  disbursementPending,
  active,
  dueToday,
  overdueD1,
  overdueReview,
  restructurePending,
  restructured,
  closed,
  unknown;

  static Lifecycle of(Object? wire) => decodeEnum(wire, const {
        'DISBURSEMENT_PENDING': disbursementPending,
        'ACTIVE': active,
        'DUE_TODAY': dueToday,
        'OVERDUE_D1': overdueD1,
        'OVERDUE_REVIEW': overdueReview,
        'RESTRUCTURE_PENDING': restructurePending,
        'RESTRUCTURED': restructured,
        'CLOSED': closed,
      }, unknown);
}

enum Hold {
  none,
  disputed,
  fraudReview;

  // An unknown hold reads as none: holds pause staff actions; the borrower view is the same.
  static Hold of(Object? wire) => decodeEnum(wire, const {'DISPUTED': disputed, 'FRAUD_REVIEW': fraudReview}, none);
}

/// Loan `repayment_status` (PLAN Phase 3).
enum Repayment {
  unpaid,
  partial,
  pendingVerification,
  paid,
  unknown;

  static Repayment of(Object? wire) => decodeEnum(wire, const {
        'UNPAID': unpaid,
        'PARTIAL': partial,
        'PAID_PENDING_VERIFICATION': pendingVerification,
        'PAID': paid,
      }, unknown);
}

/// One row of the 14-day price for an amount, as the server quotes it (PricingService snapshot).
class Quote {
  const Quote({
    required this.principalPaise,
    required this.feePaise,
    required this.gstPaise,
    required this.interestPaise,
    required this.netPaise,
    required this.repayPaise,
    required this.feeRateBps,
    required this.gstRateBps,
    required this.dailyRateBps,
    this.tenureDays = 14,
  });
  final int principalPaise;
  final int feePaise;
  final int gstPaise;
  final int interestPaise;
  final int netPaise;
  final int repayPaise;
  final int tenureDays;

  /// Rates from the frozen pricing snapshot, in basis points (1000 = 10%), for disclosure copy.
  final int feeRateBps;
  final int gstRateBps;
  final int dailyRateBps;

  int get deductionsPaise => feePaise + gstPaise + interestPaise;
}

class CreditLevel {
  const CreditLevel({required this.level, required this.maxPaise, required this.nextLevelPaise, required this.ladderPaise});
  final int level; // 1–7
  final int maxPaise;
  final int? nextLevelPaise; // null at L7
  final List<int> ladderPaise;
}

class Customer {
  const Customer({
    required this.id,
    required this.firstName,
    required this.fullName,
    required this.mobileMasked,
    required this.email,
    required this.panMasked,
    required this.aadhaarMasked,
    required this.dob,
    required this.address,
    required this.employer,
    required this.monthlyIncomePaise,
    required this.bankName,
    required this.bankMasked,
    required this.ifsc,
    required this.referralCode,
  });
  final String id; // server-generated, DN + sequence
  final String firstName;
  final String fullName;
  final String mobileMasked;
  final String email;
  final String panMasked;
  final String aadhaarMasked;
  final String dob;
  final String address;
  final String employer;
  final int monthlyIncomePaise;
  final String bankName;
  final String bankMasked;
  final String ifsc;
  final String referralCode;
}

/// A collections-led extension (PLAN Phase 6). Borrower sees "Extended n of 3".
class Extension {
  const Extension({required this.n, required this.chargePaise, required this.oldDue, required this.newDue, required this.status, this.utr});
  final int n;

  /// Null from the real API: its `extensions[]` lines carry no amount yet.
  final int? chargePaise;
  final DateTime oldDue;
  final DateTime newDue;
  final ExtensionStatus status;
  final String? utr;
}

/// Borrower-side extension state. Server `APPROVED` (Head approved, waiting for the OTP) is
/// [offered]; `ACCEPTED` is [confirmed] (payment due); `ACTIVE` is [verified]. A rejected payment
/// sends the server back to `ACCEPTED`, so [rejected] only appears in the demo.
enum ExtensionStatus {
  offered,
  confirmed,
  paymentPending,
  verified,
  rejected,
  expired,

  /// Cancelled, declined, or refused before the borrower saw it: nothing changed on the loan.
  ended,
  unknown;

  static ExtensionStatus of(Object? wire) => decodeEnum(wire, const {
        'OFFERED': ended, // not yet approved by the Collection Head: never shown to the borrower
        'APPROVED': offered,
        'ACCEPTED': confirmed,
        'PAYMENT_PENDING': paymentPending,
        'ACTIVE': verified,
        'EXPIRED': expired,
        'REJECTED': ended,
        'CANCELLED': ended,
        'DECLINED': ended,
      }, unknown);
}

/// The open extension on a loan (`pendingExtension`): offered by an officer, approved by the
/// Collection Head (C12). [status] drives the screens; [version] goes back on accept/decline/pay.
class ExtensionOffer {
  const ExtensionOffer({
    this.id = '',
    this.version = 0,
    this.status = ExtensionStatus.offered,
    required this.n,
    required this.cap,
    required this.feePaise,
    required this.gstPaise,
    required this.interestPaise,
    required this.latePaise,
    required this.totalPaise,
    required this.oldDue,
    required this.newDue,
    required this.expiresAt,
    this.officer,
  });
  final String id;
  final int version;
  final ExtensionStatus status;
  final int n;
  final int cap;
  final int feePaise;
  final int gstPaise;
  final int interestPaise;
  final int latePaise;
  final int totalPaise;
  final DateTime oldDue;
  final DateTime newDue;
  final DateTime expiresAt;

  /// Who offered it. The API sends no name, so the screen then says "our collections team".
  final String? officer;
}

enum EmiStatus {
  requested,
  approved,
  active,
  ended,
  unknown;

  static EmiStatus of(Object? wire) => decodeEnum(wire, const {
        'REQUESTED': requested,
        'APPROVED': approved,
        'ACTIVE': active,
        'REJECTED': ended,
        'CANCELLED': ended,
        'DECLINED': ended,
        'EXPIRED': ended,
      }, unknown);
}

/// Recovery EMI offer (C13). Built, flag `emi-conversion` OFF. Only [EmiStatus.approved] can be
/// accepted; [version] goes back on accept/decline.
class EmiOffer {
  const EmiOffer({required this.instalments, required this.instalmentPaise, required this.firstDue, required this.totalPaise, this.id = '', this.version = 0, this.status = EmiStatus.approved});
  final String id;
  final int version;
  final EmiStatus status;
  final int instalments;
  final int instalmentPaise;
  final DateTime firstDue;
  final int totalPaise;
}

class Loan {
  const Loan({
    required this.id,
    required this.accountNo,
    required this.applicationNo,
    required this.quote,
    required this.disbursedOn,
    required this.dueAt,
    required this.daysLeft,
    required this.lifecycle,
    this.hold = Hold.none,
    this.repayment = Repayment.unpaid,
    this.repaidPaise = 0,
    this.latePaise = 0,
    this.dpd = 0,
    this.extensionsUsed = 0,
    this.extensionCap = 3,
    this.extensions = const [],
    this.closedAt,
    this.closedOnTime,
    this.disbursalUtr,
    this.pendingExtension,
  });
  final String id;
  final String accountNo;
  final String applicationNo;
  final Quote quote;
  final DateTime disbursedOn;
  final DateTime dueAt; // 23:59:59 IST on day 14 (+14 per extension)
  final int daysLeft; // server-computed; negative = days late
  final Lifecycle lifecycle;
  final Hold hold;
  final Repayment repayment;
  final int repaidPaise;
  final int latePaise; // 2%/day on principal to date, capped at 30 days
  final int dpd;
  final int extensionsUsed;
  final int extensionCap;
  final List<Extension> extensions;
  final DateTime? closedAt;
  final bool? closedOnTime;
  final String? disbursalUtr;
  final ExtensionOffer? pendingExtension;

  int get outstandingPaise => quote.repayPaise + latePaise - repaidPaise;
  bool get isOverdue => lifecycle == Lifecycle.overdueD1 || lifecycle == Lifecycle.overdueReview;
  bool get isOpen => lifecycle != Lifecycle.closed;
}

class Txn {
  const Txn({required this.at, required this.title, required this.paise, required this.credit, this.ref, this.status = 'Verified'});
  final DateTime at;
  final String title;
  final int paise;
  final bool credit; // money into the borrower's bank
  final String? ref;
  final String status;
}

class Notice {
  const Notice({required this.at, required this.title, required this.body, this.route, this.read = false});
  final DateTime at;
  final String title;
  final String body;
  final String? route;
  final bool read;
}

class LoanDoc {
  const LoanDoc(this.name, this.meta, {this.id, this.loanId});
  final String name;
  final String meta;
  final int? id;
  final int? loanId;
}

class Complaint {
  const Complaint({required this.id, required this.subject, required this.status, required this.at, this.loanNo});
  final String id;
  final String subject;
  final String status;
  final DateTime at;
  final String? loanNo;
}

class Device {
  const Device({required this.name, required this.where, required this.lastActive, this.current = false, this.id = ''});
  final String id;
  final String name;
  final String where;
  final DateTime lastActive;
  final bool current;
}

/// Bank details to repay to (C6). From config, never typed in the app.
class PayTo {
  const PayTo({required this.upiId, required this.accountName, required this.accountNo, required this.ifsc, required this.bank});
  final String upiId;
  final String accountName;
  final String accountNo;
  final String ifsc;
  final String bank;
}

/// Status timeline on B12 / C3.
class StatusStep {
  const StatusStep(this.title, {this.detail, this.done = false, this.current = false});
  final String title;
  final String? detail;
  final bool done;
  final bool current;
}

/// Server error envelope `error.code`. Messages live in one place: [ApiException.message].
class ApiException implements Exception {
  const ApiException(this.code, [this.requestId, this.serverMessage]);
  final String code;
  final String? requestId;

  /// The server's own wording, for logs only: borrowers see [message].
  final String? serverMessage;

  String get message => switch (code) {
        'OFFLINE' => 'No internet connection.',
        'OTP_INVALID' || 'INVALID_OTP' => 'That code is not right. Check the SMS and try again.',
        'OTP_LOCKED' || 'TOO_MANY_ATTEMPTS' || 'RESEND_LIMIT' => 'Too many tries. Wait a few minutes, then ask for a new code.',
        'LOGIN_INVALID' || 'INVALID_CREDENTIALS' => 'Mobile number or password is not right.',
        'NO_PASSWORD_SET' => 'No password is set for this number. Sign in with a code instead.',
        'SESSION_EXPIRED' || 'REFRESH_INVALID' || 'REFRESH_REUSED' => 'You were logged out. Log in again to continue.',
        'UTR_DUPLICATE' || 'DUPLICATE_UTR' || 'UTR_USED_FOR_OTHER_PURPOSE' => 'This reference was already used for another payment.',
        'STALE_STATE' => 'This changed since you opened it. Pull down to refresh, then try again.',
        'LOAN_OPEN' || 'ANOTHER_LOAN_OPEN' => 'You can do this once your loan is repaid.',
        'LOAN_NOT_ACTIVE' => 'Your loan isn’t active yet. We’re still confirming the transfer with the bank.',
        'EXTENSION_EXPIRED' => 'This extension offer has ended. Call us for a new one.',
        'ACCOUNT_CLOSED' => 'This account was closed at your request.',
        'WEAK_PASSWORD' => 'Choose a stronger password.',
        'AMOUNT_ABOVE_LEVEL' => 'That amount is above your current level.',
        'FEATURE_DISABLED' || 'NOT_IN_APP_YET' => 'This isn’t in the app yet. Call us and we’ll help.',
        'SERVER_ERROR' => requestId == null ? 'Something went wrong on our side. Try again.' : 'Something went wrong on our side. Try again. Ref $requestId',
        _ => 'Something went wrong. Try again.',
      };

  @override
  String toString() => 'ApiException($code)';
}

class Contact {
  const Contact(this.name, this.phone, this.email);
  final String name;
  final String phone;
  final String email;
}

class LegalLink {
  const LegalLink(this.key, this.title, this.url);
  final String key;
  final String title;
  final String url;
}

/// `GET /api/app/config`, read before sign-in: contacts, legal links, flags and the force-update
/// floor (EXT-24). Until it loads (or when it can't), [AppConfig.fallback] uses `core/brand.dart`.
class AppConfig {
  const AppConfig({
    required this.minVersion,
    required this.support,
    required this.grievance,
    this.latestVersion,
    this.maintenance = false,
    this.flags = const {},
    this.legal = const [],
  });
  final String minVersion;
  final String? latestVersion;
  final bool maintenance;
  final Map<String, bool> flags;
  final Contact support;
  final Contact grievance;
  final List<LegalLink> legal;

  bool flag(String key) => flags[key] ?? false;
  LegalLink? link(String key) => legal.where((l) => l.key == key).firstOrNull;
}

/// True when [current] is below [floor] (dotted numbers, `1.2.10` > `1.2.9`; build suffix ignored).
bool versionBelow(String current, String floor) {
  List<int> parts(String v) => [for (final p in v.split('+').first.split('.')) int.tryParse(p) ?? 0];
  final a = parts(current), b = parts(floor);
  for (var i = 0; i < 3; i++) {
    final x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
    if (x != y) return x < y;
  }
  return false;
}
