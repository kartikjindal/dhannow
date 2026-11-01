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

/// Loan `lifecycle_status` (PLAN Phase 3).
enum Lifecycle { disbursementPending, active, dueToday, overdueD1, overdueReview, restructurePending, restructured, closed }

enum Hold { none, disputed, fraudReview }

/// Loan `repayment_status` (PLAN Phase 3).
enum Repayment { unpaid, partial, pendingVerification, paid }

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
  final int chargePaise;
  final DateTime oldDue;
  final DateTime newDue;
  final ExtensionStatus status;
  final String? utr;
}

enum ExtensionStatus { offered, confirmed, paymentPending, verified, rejected, expired }

/// An extension the officer offered and the Collection Head approved, waiting for OTP (C12).
class ExtensionOffer {
  const ExtensionOffer({
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
    required this.officer,
  });
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
  final String officer;
}

/// Recovery EMI offer (C13). Built, flag `emi-conversion` OFF.
class EmiOffer {
  const EmiOffer({required this.instalments, required this.instalmentPaise, required this.firstDue, required this.totalPaise});
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
  const LoanDoc(this.name, this.meta);
  final String name;
  final String meta;
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
  const Device({required this.name, required this.where, required this.lastActive, this.current = false});
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
  const ApiException(this.code, [this.requestId]);
  final String code;
  final String? requestId;

  String get message => switch (code) {
        'OFFLINE' => 'No internet connection.',
        'OTP_INVALID' => 'That code is not right. Check the SMS and try again.',
        'OTP_LOCKED' => 'Too many tries. Wait a few minutes, then ask for a new code.',
        'LOGIN_INVALID' => 'Mobile number or password is not right.',
        'SESSION_EXPIRED' => 'You were logged out. Log in again to continue.',
        'UTR_DUPLICATE' => 'This reference was already used for another payment.',
        _ => 'Something went wrong. Try again.',
      };

  @override
  String toString() => 'ApiException($code)';
}
