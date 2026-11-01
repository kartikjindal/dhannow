import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/widgets.dart' show AppLifecycleListener;
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../files.dart';
import '../format.dart';
import 'api.dart';
import 'client.dart';

/// The real backend (PLAN Phase 11). Paths and fields follow the controllers in `backend/`; every
/// response is the `ApiResponse` envelope, unwrapped by [ApiClient]. State the screens read
/// synchronously ([stage], [loan], [onboardingDone]) is the last server answer, refetched on
/// [refresh] (app resume, pull-to-refresh) and after every write.
class HttpApi extends Api {
  HttpApi(this.c) {
    c.onSessionExpired = _expired;
  }

  factory HttpApi.create(String baseUrl) => HttpApi(ApiClient(baseUrl: baseUrl));

  final ApiClient c;

  /// The app opens E5 when this fires (wired in `main.dart`).
  void Function()? onSessionExpired;

  /// DigiLocker, e-sign and eNACH pages: a Custom Tab, returning when the borrower is back.
  Future<void> Function(String url) openProvider = openInCustomTab;

  /// Wait between status reads after a provider hand-off (the provider's webhook can lag).
  Duration pollDelay = const Duration(seconds: 2);

  static const _kIntro = 'intro_seen';
  static const _kMobile = 'mobile';

  // Sign-up answers kept on the phone until the consent step sends them (PAN, emails): the keystore,
  // so a restart mid-sign-up does not lose them. Cleared on submit and logout.
  static const _kDraft = 'signup_draft';

  bool _signedIn = false;
  bool _offline = false;
  bool _question = false;
  String? _mobile;
  String? _consentOtp;
  int? _chosen;
  Map<String, dynamic>? _app;
  Map<String, dynamic>? _journey;
  Loan? _loan;
  Map<String, String> _draft = {};

  /// One Idempotency-Key per pending submit, so a retry of the same payment or complaint is the
  /// same request to the server (CC-22). Dropped once it succeeds.
  final _keys = <String, String>{};

  /// Reads the stored flags and `GET /api/app/config` before the first screen. Never throws.
  Future<void> init() async {
    try {
      c.appVersion = (await PackageInfo.fromPlatform()).version;
    } catch (_) {}
    firstLaunch = await c.store.read(_kIntro) == null;
    _mobile = await c.store.read(_kMobile);
    _draft = _decode(await c.store.read(_kDraft));
    _signedIn = await c.hasSession();
    try {
      config = _config(await c.send('GET', '/api/app/config', auth: false) as Map<String, dynamic>);
    } on ApiException {
      // Offline or older server: brand.dart contacts, no force update.
    }
  }

  /// EXT-24: the server's floor is above this build.
  bool get updateRequired => c.appVersion != null && versionBelow(c.appVersion!, config.minVersion);

  @override
  set firstLaunch(bool v) {
    super.firstLaunch = v;
    if (!v) c.store.write(_kIntro, '1');
  }

  @override
  bool get signedIn => _signedIn;
  @override
  bool get offline => _offline;
  @override
  Loan? get loan => _loan;
  @override
  int? get chosenPaise => _chosen;

  int? get _appId => (_app?['id'] as num?)?.toInt();
  String get _status => '${_app?['status']}';
  String get _a => '/api/applications/$_appId';

  @override
  Stage get stage {
    if (!_signedIn) return Stage.signedOut;
    if (_app == null) return Stage.onboarding;
    if (_question) return Stage.question;
    return switch (_status) {
      'DRAFT' || 'CANCELLED' => Stage.onboarding,
      'KYC_REJECTED' || 'REJECTED' => Stage.rejected,
      'SANCTIONED' || 'PRE_APPROVED' => _journey?['stepKey'] == 'offer.amount' && _chosen == null ? Stage.offer : Stage.offerFlow,
      'DISBURSEMENT_PENDING' || 'DISBURSAL_CONFIRMATION_PENDING' || 'ACCOUNTANT_PENDING' || 'DISBURSEMENT_FAILED' || 'DISBURSED' => Stage.disbursal,
      'ACTIVE' || 'OVERDUE' || 'DEFAULTED' => Stage.active,
      'CLOSED' || 'WRITTEN_OFF' => Stage.closed,
      // KYC and credit review, and any status this build does not know: the safe "with the team" state.
      _ => Stage.inReview,
    };
  }

  @override
  Set<String> get onboardingDone {
    if (_app == null) return const {};
    if (_status != 'DRAFT') return Api.signupSteps.toSet();
    final done = (_journey?['completed'] as List?) ?? const [];
    return {for (final s in done) '$s'.toLowerCase().replaceAll('_', '-')};
  }

  // ---- plumbing ----

  /// Every call goes through here: remembers whether the network was there (E3 retry).
  Future<T> _do<T>(Future<T> Function() body) async {
    try {
      final r = await body();
      _offline = false;
      return r;
    } on ApiException catch (e) {
      _offline = e.code == 'OFFLINE';
      rethrow;
    }
  }

  /// OTP calls share the screens' two codes for a wrong code and a lock-out.
  static Future<T> _otp<T>(Future<T> Function() body) async {
    try {
      return await body();
    } on ApiException catch (e) {
      throw switch (e.code) {
        'INVALID_OTP' => ApiException('OTP_INVALID', e.requestId, e.serverMessage),
        'TOO_MANY_ATTEMPTS' || 'RESEND_LIMIT' => ApiException('OTP_LOCKED', e.requestId, e.serverMessage),
        _ => e,
      };
    }
  }

  /// [run] with the Idempotency-Key for [what]: the same key until it succeeds.
  Future<T> _once<T>(String what, Future<T> Function(String key) run) async {
    final key = _keys[what] ??= uuid4();
    final r = await run(key);
    _keys.remove(what);
    return r;
  }

  void _expired() {
    _signedIn = false;
    _app = _journey = _loan = null;
    // E5 asks for a code "we sent to your registered mobile number".
    final m = _mobile;
    if (m != null) c.send('POST', '/api/auth/borrower/otp/request', body: {'mobile': m}, auth: false).catchError((_) => null);
    notifyListeners();
    onSessionExpired?.call();
  }

  Future<void> _keepDraft(Map<String, String> more) async {
    _draft = {..._draft, ...more};
    await c.store.write(_kDraft, jsonEncode(_draft));
  }

  static Map<String, String> _decode(String? json) {
    if (json == null) return {};
    try {
      return (jsonDecode(json) as Map).map((k, v) => MapEntry('$k', '$v'));
    } catch (_) {
      return {};
    }
  }

  Future<Map<String, dynamic>> _session(Object? auth) async {
    final a = auth as Map<String, dynamic>;
    await c.keepSession(a);
    _signedIn = true;
    return a;
  }

  Future<Map<String, Object?>> _device() async => {'deviceId': await c.deviceId(), 'deviceName': c.deviceName, 'appVersion': c.appVersion};

  /// The newest application, its journey, and the open loan.
  Future<void> _load() async {
    final apps = ((await c.get('/api/applications/mine')) as List).cast<Map<String, dynamic>>();
    _app = apps.firstOrNull;
    _journey = _app == null ? null : await c.get('$_a/journey') as Map<String, dynamic>;
    // A bureau question waits where sign-up stopped short of submit (A13 after a restart too).
    _question = _status == 'DRAFT' && _journey?['stepKey'] == 'signup.submitted' && await _challenge() != null;
    final open = apps.where((a) => a['loanId'] != null && !const {'CLOSED', 'WRITTEN_OFF', 'CANCELLED'}.contains(a['status'])).firstOrNull;
    _loan = open == null ? null : await _fetchLoan(open, open: true);
  }

  Future<Loan> _fetchLoan(Map<String, dynamic> app, {required bool open}) async {
    final id = (app['loanId'] as num).toInt();
    final v = await c.get('/api/loan/$id') as Map<String, dynamic>;
    // The ledger split (late charge so far, verified repaid) comes from the outstanding view.
    final o = open ? await c.get('/api/loan/$id/outstanding') as Map<String, dynamic> : null;
    return loanOf(v, app, o);
  }

  // ---- auth ----

  @override
  Future<void> sendOtp(String mobile, {bool consent = false}) => _do(() => _otp(() async {
        if (consent) {
          await c.post('$_a/verify/bureau-consent/otp');
          return;
        }
        _mobile = mobile;
        await c.send('POST', '/api/auth/borrower/otp/request', body: {'mobile': mobile, 'deviceId': await c.deviceId()}, auth: false);
      }));

  @override
  Future<void> verifyOtp(String code, {bool consent = false}) => _do(() => _otp(() async {
        if (consent) {
          _consentOtp = code; // checked by the server with the consent itself (submitApplication)
          return;
        }
        final m = _mobile ?? (throw const ApiException('SESSION_EXPIRED'));
        await _session(await c.send('POST', '/api/auth/borrower/login', body: {'mobile': m, 'otp': code, ...await _device()}, auth: false));
        await c.store.write(_kMobile, m);
        notifyListeners();
      }));

  @override
  Future<void> login(String mobile, String password) => _do(() async {
        await _session(await c.send('POST', '/api/auth/borrower/password-login', body: {'mobile': mobile, 'password': password, ...await _device()}, auth: false));
        _mobile = mobile;
        await c.store.write(_kMobile, mobile);
        notifyListeners();
      });

  @override
  Future<void> setPassword(String password) => _do(() async => c.post('/api/auth/borrower/set-password', {'password': password}));

  /// After the OTP sign-in on S5 the borrower is signed in, so this is the same set-password call.
  @override
  Future<void> resetPassword(String password) => setPassword(password);

  @override
  Future<void> logout() => _do(() async {
        final refresh = await c.refreshToken();
        try {
          await c.send('POST', '/api/auth/borrower/logout', body: {'refreshToken': refresh}, auth: false);
        } on ApiException {
          // Signed out on this phone either way; the token lapses on its own.
        }
        await c.clearSession();
        await c.store.write(_kDraft, null);
        _signedIn = _question = false;
        _app = _journey = _loan = null;
        _draft = {};
        _chosen = null;
        notifyListeners();
      });

  // ---- journey (E6) ----

  @override
  Future<({Stage stage, String route})> journey() => _do(() async {
        if (updateRequired) return (stage: stage, route: '/update');
        _signedIn = await c.hasSession();
        if (!_signedIn) return (stage: Stage.signedOut, route: firstLaunch ? '/intro' : '/welcome');
        await _load();
        final s = stage;
        final key = '${_journey?['stepKey']}';
        return (
          stage: s,
          route: switch (s) {
            Stage.onboarding => '/resume',
            Stage.question => '/credit-question',
            Stage.rejected => '/application-rejected',
            Stage.offerFlow when key.startsWith('offer.') => '/loan/${key.substring(6)}',
            _ => '/dashboard',
          },
        );
      });

  @override
  Future<void> refresh() async {
    if (!_signedIn) return notifyListeners();
    try {
      await _do(_load);
    } on ApiException {
      // Screens keep their last good data; their own loads show the error.
    }
    notifyListeners();
  }

  // ---- onboarding (A1–A13) ----

  Future<int> _draftId() async {
    if (_app == null) await _load();
    if (_status == 'DRAFT') return _appId!;
    final id = (c.auth?['customerId'] as num?)?.toInt() ?? (throw const ApiException('SESSION_EXPIRED'));
    _app = await c.post('/api/applications', {'customerId': id}) as Map<String, dynamic>;
    return _appId!;
  }

  Future<void> _profile(Map<String, Object?> fields) async {
    final body = {for (final e in fields.entries) if (e.value != null && '${e.value}'.isNotEmpty) e.key: e.value};
    if (body.isNotEmpty) await c.put('$_a/profile', body);
  }

  Future<void> _advance(String step) async => _journey = await c.post('$_a/journey/$step') as Map<String, dynamic>;

  /// Rupees typed on a form to paise (a unit change, not a price).
  static int? _rupees(Object? v) {
    final r = int.tryParse('${v ?? ''}'.replaceAll(RegExp(r'[^0-9]'), ''));
    return r == null ? null : r * 100;
  }

  @override
  Future<void> saveStep(String step, Map<String, Object?> data, {Map<String, PickedFile> files = const {}}) => _do(() async {
        final d = {for (final e in data.entries) e.key: '${e.value ?? ''}'};
        switch (step) {
          case 'start':
            await _keepDraft({'pan': d['pan'] ?? '', 'mobile': d['mobile'] ?? '', 'termsVersion': d['termsVersion'] ?? ''});
            if (!_signedIn) return; // saved after the OTP signs them in
            await _draftId();
            await _profile({'pan': d['pan'], 'mobile': d['mobile'], 'termsVersion': d['termsVersion'], 'pepDeclared': true});
          case 'otp':
            await _draftId();
            await _profile({'pan': _draft['pan'], 'mobile': _draft['mobile'] ?? _mobile, 'termsVersion': _draft['termsVersion'], 'pepDeclared': true});
            await _advance('OTP');
          case 'set-password':
            await _advance('SET_PASSWORD');
          case 'employment':
            await _profile({'employmentStatus': d['type'] == 'self-employed' ? 'SELF_EMPLOYED' : 'SALARIED'});
            await _advance('EMPLOYMENT');
          case 'employer' when d['type'] == 'self-employed':
            await _profile({'fullName': d['name'], 'dob': d['dob'], 'annualSalaryPaise': _rupees(d['annualIncome'])});
            _app = await c.post('$_a/self-employed') as Map<String, dynamic>;
          case 'employer':
            await _profile({'employer': d['company'], 'monthlySalaryPaise': _rupees(d['salary'])});
            await _keepDraft({'salaryPaise': '${_rupees(d['salary']) ?? ''}'});
            await _advance('EMPLOYER');
          case 'email':
            // The codes were checked on the screen (sendEmailCode / verifyEmailCode).
            await _keepDraft({'officialEmail': d['work'] ?? ''});
            await _profile({'email': d['personal'], 'officialEmail': d['work']});
            await _advance('EMAIL');
          case 'bank':
            await _profile({'salaryBank': d['bank'], 'salaryAccountNumber': d['account'], 'salaryIfsc': d['ifsc'], 'salaryAccountMobile': d['mobile']});
            if (files.isNotEmpty) await _saveDocs('BANK_STATEMENT', files.values, d['pdfPassword']);
            await _advance('BANK');
          case 'payslips':
            final keys = [for (final f in files.values) await _upload(f, 'SALARY_SLIP')];
            final r = await c.post('$_a/verify/salary', {
              'monthlySalaryPaise': await _salaryPaise(),
              'slipObjectKeys': keys,
              'filePassword': _blankNull(d['pdfPassword']),
            }) as Map<String, dynamic>?;
            if (r?['status'] == 'FAIL') throw ApiException('SALARY_NOT_VERIFIED', null, r?['message'] as String?);
            await _advance('PAYSLIPS');
          case 'aadhaar':
            await _profile({'aadhaar': d['aadhaar']});
            await _saveDocs('AADHAAR_CARD_FRONT', [files['front']!], d['pdfPassword']);
            await _saveDocs('AADHAAR_CARD_BACK', [files['back']!], d['pdfPassword']);
            await _advance('AADHAAR');
          case 'pan-card':
            await _saveDocs('PAN_CARD_FRONT', [files['front']!], d['pdfPassword']);
            await _saveDocs('PAN_CARD_BACK', [files['back']!], d['pdfPassword']);
            await _advance('PAN_CARD');
          case 'address-proof':
            // ponytail: uploaded into the application's folder, as the web did; no endpoint files these
            // doc types yet (backend gap), so staff find them only in storage.
            for (final e in files.entries) {
              await _upload(e.value, switch (e.key) { 'Gas bill' => 'GAS_BILL', 'Rent agreement' => 'RENT_AGREEMENT', _ => 'ELECTRICITY_BILL' });
            }
            final uan = d['uan'];
            if (uan != null && uan.isNotEmpty) {
              await c.put('$_a/profile/self', {'uan': uan});
              await c.post('$_a/verify/employment');
            }
          default:
            // 'apply' (onboarding paused): the server takes the full Aadhaar number, A1b the last four.
            throw const ApiException('NOT_IN_APP_YET');
        }
        notifyListeners();
      });

  @override
  Future<bool> sendEmailCode(String email, {required bool work}) => _do(() => _otp(() async {
        await _draftId();
        await _profile({work ? 'officialEmail' : 'email': email});
        if (work) await _keepDraft({'officialEmail': email});
        final r = await c.post('$_a/verify/${work ? 'official-email' : 'email'}/otp') as Map<String, dynamic>?;
        return r?['sent'] != false;
      }));

  @override
  Future<void> verifyEmailCode(String code, {required bool work}) =>
      _do(() => _otp(() async => c.post('$_a/verify/${work ? 'official-email' : 'email'}/otp/confirm', {'otp': code})));

  Future<int?> _salaryPaise() async {
    final kept = int.tryParse(_draft['salaryPaise'] ?? '');
    if (kept != null) return kept;
    final p = await c.get('$_a/profile') as Map<String, dynamic>;
    return (p['monthlySalaryPaise'] as num?)?.toInt();
  }

  /// The open bureau question on the BUREAU check (`verify/summary`), or null.
  Future<({String question, List<String> options})?> _challenge() async {
    final rows = ((await c.get('$_a/verify/summary')) as List).cast<Map<String, dynamic>>();
    final d = (rows.where((r) => r['checkType'] == 'BUREAU').firstOrNull?['derived'] as Map?) ?? const {};
    if (d['bureauChallenge'] != true || d['bureauChallengeExhausted'] == true) return null;
    return (question: '${d['bureauChallengeQuestion'] ?? ''}', options: [for (final o in (d['bureauChallengeOptions'] as List? ?? const [])) '$o']);
  }

  @override
  Future<({String question, List<String> options})?> creditQuestion() => _do(_challenge);

  @override
  Future<void> submitApplication({DateTime? dob}) => _do(() => _otp(() async {
        if (dob != null) await _profile({'dob': _iso(dob)});
        await c.post('$_a/verify/bureau-consent', {'otp': _consentOtp, 'consentText': bureauConsentText});
        final pan = _draft['pan'];
        if (pan != null && pan.isNotEmpty) {
          final r = await c.post('$_a/verify/pan', {'pan': pan}) as Map<String, dynamic>?;
          if ((r?['derived'] as Map?)?['applicationRejected'] == true) return _reload();
        }
        final work = _draft['officialEmail'];
        if (work != null && work.isNotEmpty) await c.post('$_a/verify/email', {'officialEmail': work});
        final bureau = await c.post('$_a/verify/bureau', {'otp': _consentOtp}) as Map<String, dynamic>?;
        await c.post('$_a/verify/employment');
        await _advance('CONSENT');
        _question = (bureau?['derived'] as Map?)?['bureauChallenge'] != null;
        if (!_question) await _submitKyc();
        await c.store.write(_kDraft, null);
        _draft = {};
        await _reload();
      }));

  Future<void> _submitKyc() async {
    _app = await c.post('$_a/submit-kyc') as Map<String, dynamic>;
    if (_status != 'REJECTED') await _advance('SUBMITTED');
  }

  Future<void> _reload() async {
    await _load();
    notifyListeners();
  }

  @override
  Future<void> answerQuestion(String? answer) => _do(() async {
        if (answer == null) {
          await c.post('$_a/verify/bureau/challenge/skip');
        } else {
          await c.post('$_a/verify/bureau/challenge/answer', {'answer': answer});
          // A refused answer leaves the question open (the server says so in the row, not an error).
          if (await _challenge() != null) throw const ApiException('BUREAU_CHALLENGE_ANSWER_INVALID');
        }
        _question = false;
        await _submitKyc();
        await _reload();
      });

  // ---- profile and level ----

  @override
  Future<Customer> me() => _do(() async {
        if (_app == null) await _load();
        final p = _appId == null ? const <String, dynamic>{} : await c.get('$_a/profile') as Map<String, dynamic>;
        return customerOf(p, c.auth?['customerCode'] as String?);
      });

  @override
  Future<CreditLevel> creditLevel() => _do(() async {
        final v = await c.get('/api/me/credit-level') as Map<String, dynamic>;
        return CreditLevel(
          level: (v['level'] as num).toInt(),
          maxPaise: (v['maxPaise'] as num).toInt(),
          nextLevelPaise: (v['nextLevelPaise'] as num?)?.toInt(),
          ladderPaise: [for (final x in (v['ladder'] as List? ?? const [])) (x as num).toInt()],
        );
      });

  // ---- offer (B1–B12) ----

  /// ponytail: the server prices only the saved amount (`offer/summary`), so B1 offers that one
  /// amount until a per-amount quote endpoint exists (backend gap, Phase 11 status).
  @override
  Future<List<Quote>> quotes() async => [await quote(0)];

  @override
  Future<Quote> quote(int principalPaise) => _do(() async => quoteOf(await c.get('$_a/offer/summary') as Map<String, dynamic>));

  @override
  Future<void> chooseAmount(int paise) => _do(() async {
        // A legacy PRE_APPROVED re-borrow applies straight to disbursal; everyone else walks the offer.
        await (_status == 'PRE_APPROVED' ? c.post('$_a/apply', {'amountPaise': paise}) : c.post('$_a/offer/amount', {'amountPaise': paise}));
        _chosen = paise;
        notifyListeners();
      });

  @override
  Future<void> acceptKfs() => _do(() async {
        await c.post('$_a/offer/kfs'); // issues (or reuses) the KFS for this offer
        await c.post('$_a/offer/kfs/ack');
      });

  @override
  Future<void> sign() => _do(() async {
        await c.post('$_a/offer/sanction-letter'); // the pack being signed
        final r = await c.post('$_a/offer/esign/init', {
          'successRedirectUrl': '$callbackBase/esign?outcome=success',
          'failureRedirectUrl': '$callbackBase/esign?outcome=failure',
        }) as Map<String, dynamic>;
        final url = _derived(r)['url'];
        if (_derived(r)['fallback'] == true || url == null) throw const ApiException('ESIGN_UNAVAILABLE');
        await openProvider('$url');
        await _poll('$_a/offer/esign/status', 'ESIGN_INCOMPLETE');
        await _reload();
      });

  @override
  Future<void> signDrawn(Uint8List png) => _do(() async {
        await c.post('$_a/offer/sanction-letter');
        final r = await c.post('$_a/offer/esign', {'signatureDataUrl': 'data:image/png;base64,${base64Encode(png)}'}) as Map<String, dynamic>;
        if (r['status'] != 'PASS') throw ApiException('ESIGN_INCOMPLETE', null, r['message'] as String?);
        await _reload();
      });

  static Map _derived(Map<String, dynamic>? r) => (r?['derived'] as Map?) ?? const {};

  /// After a hand-off: read [path] until it is PASS (the provider's webhook can lag a few seconds).
  Future<Map<String, dynamic>> _poll(String path, String failCode) async {
    for (var i = 0;; i++) {
      final s = await c.get(path) as Map<String, dynamic>;
      if (s['status'] == 'PASS') return s;
      if (s['status'] == 'FAIL' || i == 2) throw ApiException(failCode, null, s['message'] as String?);
      await Future<void>.delayed(pollDelay);
    }
  }

  @override
  Future<void> verifyDigilocker() => _do(() async {
        final r = await c.post('$_a/verify/digilocker/init', {'redirectUrl': '$callbackBase/digilocker'}) as Map<String, dynamic>;
        if (_derived(r)['sessionsExhausted'] == true) throw const ApiException('DIGILOCKER_EXHAUSTED');
        final url = _derived(r)['url'] ?? (throw const ApiException('DIGILOCKER_FAILED'));
        await openProvider('$url');
        for (var i = 0;; i++) {
          final s = _derived(await c.get('$_a/verify/digilocker/status') as Map<String, dynamic>);
          if (s['failed'] == true) throw const ApiException('DIGILOCKER_FAILED');
          if (s['completed'] == true) break;
          if (i == 2) throw const ApiException('DIGILOCKER_INCOMPLETE');
          await Future<void>.delayed(pollDelay);
        }
        final done = await c.post('$_a/verify/digilocker/complete') as Map<String, dynamic>;
        if (done['status'] == 'FAIL') throw const ApiException('DIGILOCKER_FAILED');
        await _advance('OFFER_DIGILOCKER');
      });

  @override
  Future<void> uploadAadhaar(PickedFile front, PickedFile back) => _do(() async {
        await _saveDocs('AADHAAR_FRONT', [front], null);
        await _saveDocs('AADHAAR_BACK', [back], null);
        await c.post('$_a/verify/aadhaar-manual');
        await _advance('OFFER_DIGILOCKER');
      });

  @override
  Future<void> startMandate() => _do(() async {
        final r = await c.post('$_a/offer/mandate/init') as Map<String, dynamic>;
        final url = _derived(r)['url'];
        if (url != null) await openProvider('$url');
        await _poll('$_a/offer/mandate/status', 'MANDATE_INCOMPLETE');
        await _reload();
      });

  @override
  Future<Stage> reborrow() => _do(() async {
        _app = await c.post('/api/applications/reborrow') as Map<String, dynamic>;
        _chosen = null;
        await _reload();
        return stage;
      });

  static const _relations = {'Parent', 'Spouse', 'Sibling', 'Relative', 'Friend', 'Colleague', 'Manager', 'Neighbour'};

  @override
  Future<void> saveReferences(List<({String name, String mobile, String relation})> refs) => _do(() async {
        await c.post('$_a/offer/references', {
          'references': [
            for (final r in refs) {'fullName': r.name, 'mobile': r.mobile, 'relation': _relations.contains(r.relation) ? r.relation.toUpperCase() : 'RELATIVE'},
          ],
        });
      });

  @override
  Future<String> uploadSelfie(List<PickedFile> frames) => _do(() async {
        final keys = [for (final f in frames) await _upload(f, 'SELFIE')];
        final r = await c.post('$_a/verify/selfie/frames', {'straightKey': keys[0], 'rightKey': keys[1], 'leftKey': keys[2]}) as Map<String, dynamic>;
        await _advance('OFFER_SELFIE');
        return '${r['status']}';
      });

  @override
  Future<void> saveAddress(String address) => _do(() async {
        await _profile({'address': address});
        await c.post('$_a/verify/address', {'manualAddress': address});
        await _advance('OFFER_ADDRESS');
      });

  /// B10: the account on file (salary account, or the last one paid) sent back as is, or the one
  /// typed on B10b; with [proof] the cheque or passbook is filed and checked by hand.
  @override
  Future<void> confirmDisbursalAccount({String? accountNo, String? ifsc, String? holder, PickedFile? proof}) => _do(() async {
        if (proof != null) await _saveDocs('BANK_PROOF', [proof], null);
        final Map<String, dynamic> v = accountNo == null
            ? await c.get('$_a/offer/disbursal-account') as Map<String, dynamic>
            : {'accountNumber': accountNo, 'ifsc': ifsc, 'holderName': holder};
        _app = await c.post('$_a/offer/disbursal-account', {
          'accountNumber': v['accountNumber'],
          'ifsc': v['ifsc'],
          'holderName': v['holderName'],
          'bank': v['bank'],
          'useBankProof': proof != null,
        }) as Map<String, dynamic>;
        await _reload();
      });

  @override
  Future<List<StatusStep>> applicationStatus() => _do(() async {
        await _load();
        final s = stage;
        final at = switch (s) {
          Stage.inReview || Stage.question => 1,
          Stage.offer || Stage.offerFlow => 2,
          Stage.disbursal => 3,
          Stage.active => 4,
          Stage.closed => 5,
          _ => 0,
        };
        final l = _loan;
        return [
          const StatusStep('Applied', done: true),
          StatusStep('Under review', detail: 'Credit team checked your details', done: at >= 2, current: at == 1),
          StatusStep('Approved', done: at >= 2, current: at == 2),
          StatusStep('Money sent', detail: l?.disbursalUtr == null ? null : 'UTR ${l!.disbursalUtr}', done: at >= 3),
          StatusStep('Confirming with the bank', detail: 'Usually within a few hours. Your 14 days start once confirmed', done: at >= 4, current: at == 3),
          StatusStep('Active', detail: 'Repay by day 14, 11:59 pm', done: at >= 4, current: at == 4),
          StatusStep('Repaid and closed', detail: 'Unlocks your next level', done: at >= 5, current: at == 5),
        ];
      });

  // B7 "Use my location": needs geolocator (not added). The typed address works.
  @override
  Future<void> simulate() async => throw const ApiException('NOT_IN_APP_YET');

  // ---- loan, repay, documents ----

  @override
  Future<Loan?> currentLoan() => _do(() async {
        await _load();
        return _loan;
      });

  Future<List<Map<String, dynamic>>> _apps() async => ((await c.get('/api/applications/mine')) as List).cast<Map<String, dynamic>>();

  @override
  Future<List<Loan>> loans() => _do(() async {
        final apps = (await _apps()).where((a) => a['loanId'] != null).toList();
        return [for (final a in apps) await _fetchLoan(a, open: '${a['loanId']}' == _loan?.id)];
      });

  @override
  Future<List<Txn>> transactions() => _do(() async {
        final out = <Txn>[];
        for (final l in await loans()) {
          final pays = ((await c.get('/api/loan/${l.id}/repayments')) as List).cast<Map<String, dynamic>>();
          for (final p in pays) {
            final status = '${p['status']}';
            out.add(Txn(
              at: _date(p['paidOn']) ?? l.disbursedOn,
              title: p['purpose'] == 'EXTENSION' ? 'Extension charge' : 'Repayment',
              paise: (p['amountPaise'] as num).toInt(),
              credit: false,
              ref: p['txnRef'] as String?,
              status: switch (status) { 'VERIFIED' => 'Verified', 'REJECTED' => 'Not matched', 'PENDING_VERIFICATION' => 'Being verified', _ => 'Updating' },
            ));
          }
          if (l.lifecycle != Lifecycle.disbursementPending) {
            out.add(Txn(at: l.disbursedOn, title: 'Loan money received', paise: l.quote.netPaise, credit: true, ref: l.disbursalUtr));
          }
        }
        out.sort((a, b) => b.at.compareTo(a.at));
        return out;
      });

  static const _docNames = {
    'KFS': 'Key Fact Statement',
    'SANCTION': 'Sanction letter',
    'AGREEMENT': 'Loan agreement',
    'SCHEDULE': 'Repayment schedule',
    'CLOSURE_RECEIPT': 'Closure receipt',
    'EXTENSION_ADDENDUM': 'Extension addendum',
    'EMI_ADDENDUM': 'Repayment plan addendum',
  };

  @override
  Future<List<LoanDoc>> documents() => _do(() async {
        final id = _loan?.id ?? (await _apps()).where((a) => a['loanId'] != null).firstOrNull?['loanId'];
        if (id == null) return const <LoanDoc>[];
        final docs = ((await c.get('/api/loans/$id/documents')) as List).cast<Map<String, dynamic>>();
        return [
          for (final d in docs)
            LoanDoc(
              _docNames['${d['type']}'] ?? 'Loan document',
              'PDF · version ${d['version']}${_date(d['acceptedAt']) == null ? '' : ' · accepted ${dayMonth(_date(d['acceptedAt'])!)}'}',
              id: (d['id'] as num).toInt(),
              loanId: int.tryParse('$id'),
            ),
        ];
      });

  @override
  Future<String?> documentUrl(LoanDoc doc) => _do(() async {
        final v = await c.get('/api/loans/${doc.loanId}/documents/${doc.id}/url') as Map<String, dynamic>;
        return v['url'] as String?;
      });

  @override
  Future<PayTo> payTo() => _do(() async {
        final v = await c.get('/api/payment-settings') as Map<String, dynamic>;
        return PayTo(upiId: '${v['upiId'] ?? ''}', accountName: '${v['accountName'] ?? ''}', accountNo: '${v['accountNumber'] ?? ''}', ifsc: '${v['ifsc'] ?? ''}', bank: '${v['bankName'] ?? ''}');
      });

  Future<void> _pay({required String purpose, required int paise, required String utr, required DateTime paidOn, String? extensionId, PickedFile? screenshot}) async {
    final l = _loan ?? (throw const ApiException('LOAN_NOT_ACTIVE'));
    if (screenshot == null) throw const ApiException('PAYMENT_PROOF_REQUIRED');
    // One upload per picked screenshot, so a retry sends the same proof with the same key.
    final proof = _proofKeys[screenshot] ??= await _uploadProof(screenshot);
    final body = {
      'amountPaise': paise,
      'method': 'UPI',
      'txnRef': utr.trim(),
      'paidOn': _iso(paidOn),
      'purpose': purpose,
      'extensionId': ?extensionId,
      'proofUrl': proof,
    };
    await _once('pay|${l.id}|${jsonEncode(body)}', (key) => c.post('/api/loan/${l.id}/repayments', body, {'Idempotency-Key': key}));
    await _reload();
  }

  @override
  Future<void> submitPayment({required String utr, required DateTime paidOn, required int paise, PickedFile? screenshot}) =>
      _do(() => _pay(purpose: 'REPAYMENT', paise: paise, utr: utr, paidOn: paidOn, screenshot: screenshot));

  final _proofKeys = Expando<String>();

  /// Presign (`/api/storage`, REPAYMENT_PROOF) → PUT → the key the payment carries.
  Future<String> _uploadProof(PickedFile f) async {
    final p = await c.post('/api/storage/presign-upload', {'category': 'REPAYMENT_PROOF', 'filename': f.name, 'contentType': f.contentType}) as Map<String, dynamic>;
    await c.putBytes('${p['url']}', _bytes(f), f.contentType);
    return '${p['key']}';
  }

  /// Presign (application folder, [docType]) → PUT → key.
  Future<String> _upload(PickedFile f, String docType) async {
    final problem = uploadProblem(f);
    if (problem != null) throw ApiException('UPLOAD_REFUSED', null, problem);
    final p = await c.post('$_a/verify/presign-upload', {'docType': docType, 'fileName': f.name, 'contentType': f.contentType}) as Map<String, dynamic>;
    await c.putBytes('${p['url']}', _bytes(f), f.contentType);
    return '${p['key']}';
  }

  static Uint8List _bytes(PickedFile f) => f.data ?? (throw const ApiException('UPLOAD_FAILED'));

  /// Uploads [files] and files them under [docType] on the application.
  Future<void> _saveDocs(String docType, Iterable<PickedFile> files, String? password) async {
    final keys = [for (final f in files) await _upload(f, docType)];
    await c.post('$_a/verify/documents', {'docType': docType, 'objectKeys': keys, 'filePassword': _blankNull(password)});
  }

  static String? _blankNull(String? v) => v == null || v.isEmpty ? null : v;

  @override
  Future<List<Notice>> notices() => _do(() async {
        final list = ((await c.get('/api/notifications')) as List).cast<Map<String, dynamic>>();
        return [
          for (final n in list)
            Notice(
              at: _date(n['createdAt']) ?? DateTime.now(),
              title: '${n['title'] ?? ''}',
              body: '${n['body'] ?? ''}',
              read: n['read'] == true,
              // Screens render from server state, never from a notification: open the dashboard.
              route: n['loanId'] == null ? null : '/dashboard',
            ),
        ];
      });

  // ---- extension (C12–C12d), EMI (C13) ----

  @override
  Future<ExtensionOffer?> extensionOffer() => _do(() async {
        await _load();
        final o = _loan?.pendingExtension;
        if (o != null && !_seen.contains(o.id)) {
          _seen.add(o.id);
          c.post('/api/loans/${_loan!.id}/extensions/${o.id}/seen').catchError((_) => null);
        }
        return o;
      });

  final _seen = <String>{};

  String get _ext {
    final o = _loan?.pendingExtension ?? (throw const ApiException('EXTENSION_EXPIRED'));
    return '/api/loans/${_loan!.id}/extensions/${o.id}';
  }

  @override
  Future<void> sendExtensionOtp() => _do(() => _otp(() async => c.post('$_ext/otp')));

  @override
  Future<void> declineExtension(String reason) => _do(() async {
        await c.post('$_ext/decline', {'reason': reason, 'version': _loan!.pendingExtension!.version});
        await _reload();
      });

  @override
  Future<void> confirmExtension(String otp) => _do(() => _otp(() async {
        await c.post('$_ext/accept', {'otp': otp, 'consentVersion': extensionConsentVersion, 'version': _loan!.pendingExtension!.version});
        await _reload();
      }));

  @override
  Future<void> payExtension({required String utr, required DateTime paidOn, PickedFile? screenshot}) => _do(() async {
        final o = _loan?.pendingExtension ?? (throw const ApiException('EXTENSION_EXPIRED'));
        await _pay(purpose: 'EXTENSION', paise: o.totalPaise, utr: utr, paidOn: paidOn, extensionId: o.id, screenshot: screenshot);
      });

  @override
  Future<EmiOffer?> emiOffer() => _do(() async {
        final l = _loan;
        if (l == null) return null;
        try {
          final v = await c.get('/api/loans/${l.id}/emi') as Map<String, dynamic>?;
          final o = v == null ? null : emiOf(v);
          return o?.status == EmiStatus.approved ? o : null;
        } on ApiException catch (e) {
          if (e.code == 'FEATURE_DISABLED' || e.code == 'NOT_FOUND') return null;
          rethrow;
        }
      });

  @override
  Future<void> sendEmiOtp(EmiOffer offer) => _do(() => _otp(() async => c.post('/api/loans/${_loan!.id}/emi/${offer.id}/otp')));

  @override
  Future<void> acceptEmi(EmiOffer offer, String otp) => _do(() => _otp(() async {
        await c.post('/api/loans/${_loan!.id}/emi/${offer.id}/accept', {'otp': otp, 'consentVersion': emiConsentVersion, 'version': offer.version});
        await _reload();
      }));

  // ---- account (D1–D8) ----

  static const _categories = {
    'Payment not reflecting': 'PAYMENT',
    'Charges or amount': 'CHARGES',
    'Collection call': 'COLLECTIONS_CONDUCT',
    'Documents': 'LOAN_DISPUTE',
  };
  static const _categoryLabels = {
    'PAYMENT': 'Payment not reflecting',
    'CHARGES': 'Charges or amount',
    'COLLECTIONS_CONDUCT': 'Collection call',
    'LOAN_DISPUTE': 'Loan dispute',
    'EXTENSION_NOT_REQUESTED': 'Extension I didn’t ask for',
    'DATA_PRIVACY': 'My data',
    'APP_OR_SERVICE': 'App or service',
  };

  static Complaint complaintOf(Map<String, dynamic> v) => Complaint(
        id: '${v['ticketNo'] ?? v['id']}',
        subject: _categoryLabels['${v['category']}'] ?? 'Something else',
        status: switch ('${v['status']}') { 'RESOLVED' => 'Resolved', 'IN_REVIEW' || 'ACKNOWLEDGED' => 'In review', 'ESCALATED' => 'Escalated', _ => 'Open' },
        at: _date(v['createdAt']) ?? DateTime.now(),
        loanNo: v['loanId'] == null ? null : '${v['loanId']}',
      );

  @override
  Future<List<Complaint>> complaints() => _do(() async => [for (final v in ((await c.get('/api/complaints')) as List)) complaintOf(v as Map<String, dynamic>)]);

  @override
  Future<Complaint> raiseComplaint({required String subject, required String body, Loan? loan}) => _do(() async {
        final req = {'category': _categories[subject] ?? 'OTHER', 'body': body, 'loanId': ?int.tryParse(loan?.id ?? '')};
        final v = await _once('complaint|${jsonEncode(req)}', (key) => c.post('/api/complaints', req, {'Idempotency-Key': key}));
        return complaintOf(v as Map<String, dynamic>);
      });

  @override
  Future<List<Device>> devices() => _do(() async => [
        for (final d in ((await c.get('/api/devices/me')) as List).cast<Map<String, dynamic>>())
          Device(
            id: '${d['deviceId']}',
            name: '${d['current'] == true ? 'This phone · ' : ''}${d['name'] ?? 'Android phone'}',
            where: d['appVersion'] == null ? 'Android' : 'Android · app ${d['appVersion']}',
            lastActive: _date(d['lastSeen']) ?? DateTime.now(),
            current: d['current'] == true,
          ),
      ]);

  @override
  Future<void> signOutDevice(Device device) => _do(() async => c.delete('/api/devices/me/${Uri.encodeComponent(device.id)}'));

  @override
  Future<void> requestDeletion() => _do(() async => c.post('/api/me/account-deletion'));

  // ---- decoders (hand-written, PLAN Phase 11: no codegen) ----

  static AppConfig _config(Map<String, dynamic> v) {
    Contact contact(Object? m, Contact fallback) => m is Map ? Contact('${m['name'] ?? fallback.name}', '${m['phone'] ?? fallback.phone}', '${m['email'] ?? fallback.email}') : fallback;
    return AppConfig(
      minVersion: '${v['minVersion'] ?? '0.0.0'}',
      latestVersion: v['latestVersion'] as String?,
      maintenance: v['maintenance'] == true,
      flags: {for (final e in ((v['flags'] as Map?) ?? const {}).entries) '${e.key}': e.value == true},
      support: contact(v['support'], fallbackConfig.support),
      grievance: contact(v['grievance'], fallbackConfig.grievance),
      legal: [for (final l in (v['legal'] as List? ?? const [])) LegalLink('${l['key']}', '${l['title']}', '${l['url']}')],
    );
  }
}

/// Where providers send the borrower back (the server's redirect allowlist, CC-34). The Android
/// app link on this path brings the app forward; [openInCustomTab] then returns.
const callbackBase = 'https://dhannow.com/app/cb';

/// Opens [url] in a Custom Tab (`url_launcher` in-app browser view) and completes when the app is
/// back in front: the callback app link, or the borrower closing the tab. The caller then reads
/// the status from the server; nothing is taken from the callback URL itself.
Future<void> openInCustomTab(String url) async {
  final back = Completer<void>();
  final listener = AppLifecycleListener(onResume: () => back.isCompleted ? null : back.complete());
  try {
    final ok = await launchUrl(Uri.parse(url), mode: LaunchMode.inAppBrowserView).catchError((_) => false);
    if (!ok) throw const ApiException('HANDOFF_FAILED');
    await back.future;
  } finally {
    listener.dispose();
  }
}

/// Sent with the bureau consent; the same words as the A11 checkbox.
const bureauConsentText = 'I authorise DhanNow and its lending partner to fetch my credit report from a credit bureau to assess this application.';
const extensionConsentVersion = 'extension-consent@1';
const emiConsentVersion = 'emi-consent@1';

String _iso(DateTime d) => '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// `2026-11-17` (a LocalDate) or an ISO instant, as a local DateTime.
DateTime? _date(Object? v) => v == null ? null : DateTime.tryParse('$v')?.toLocal();

int _int(Object? v) => (v as num?)?.toInt() ?? 0;

/// `OfferSummaryView`. ponytail: the view carries no rates, so the disclosure bps are read back
/// from the server's own figures (exact for LoanMath's whole-paise rounding on ladder amounts)
/// until `feeRateBps`/`gstRateBps`/`dailyRateBps` are added to it (backend gap).
Quote quoteOf(Map<String, dynamic> v) {
  final p = _int(v['principalPaise']), fee = _int(v['processingFeePaise']), gst = _int(v['gstPaise']), interest = _int(v['interestPaise']);
  final days = _int(v['tenureDays']) == 0 ? 14 : _int(v['tenureDays']);
  return Quote(
    principalPaise: p,
    feePaise: fee,
    gstPaise: gst,
    interestPaise: interest,
    netPaise: _int(v['netDisbursedPaise']),
    repayPaise: _int(v['totalRepayablePaise']),
    tenureDays: days,
    feeRateBps: p == 0 ? 0 : fee * 10000 ~/ p,
    gstRateBps: fee == 0 ? 0 : gst * 10000 ~/ fee,
    dailyRateBps: p == 0 ? 0 : interest * 10000 ~/ (p * days),
  );
}

/// `LoanView` (+ the application row for its number, + `OutstandingView` for an open loan).
Loan loanOf(Map<String, dynamic> v, Map<String, dynamic> app, Map<String, dynamic>? outstanding) {
  final principal = _int(v['principalPaise']), fee = _int(v['processingFeePaise']), gst = _int(v['gstPaise']), net = _int(v['netDisbursedPaise']);
  final repay = v['totalRepayablePaise'] == null ? principal : _int(v['totalRepayablePaise']);
  final disbursed = _date(v['disbursedOn']) ?? DateTime.now();
  final dueDay = _date(v['dueDate']) ?? disbursed.add(const Duration(days: 14));
  final dueAt = DateTime(dueDay.year, dueDay.month, dueDay.day, 23, 59, 59);
  final now = DateTime.now();
  final lifecycle = Lifecycle.of(v['lifecycleStatus']);
  final closed = lifecycle == Lifecycle.closed;
  final pending = v['pendingExtension'] as Map<String, dynamic>?;
  return Loan(
    id: '${v['id']}',
    // ponytail: LoanView has no borrower-facing account number yet (backend gap); the loan id stands in.
    accountNo: '${v['id']}',
    applicationNo: '${app['id']}',
    quote: Quote(
      principalPaise: principal,
      feePaise: fee,
      gstPaise: gst,
      // LoanView sends no interest figure for upfront loans (backend gap); it is what the three
      // server figures leave of the principal.
      interestPaise: principal - fee - gst - net,
      netPaise: net,
      repayPaise: repay,
      feeRateBps: 0,
      gstRateBps: 0,
      dailyRateBps: 0,
    ),
    disbursedOn: disbursed,
    dueAt: dueAt,
    daysLeft: DateTime(dueDay.year, dueDay.month, dueDay.day).difference(DateTime(now.year, now.month, now.day)).inDays,
    lifecycle: lifecycle,
    hold: Hold.of(v['holdStatus']),
    repayment: Repayment.of(v['repaymentStatus']),
    repaidPaise: closed ? repay : _int(outstanding?['verifiedPaise']),
    latePaise: closed ? 0 : _int(outstanding?['penaltyPaise']),
    dpd: lifecycle == Lifecycle.overdueD1 || lifecycle == Lifecycle.overdueReview ? -DateTime(dueDay.year, dueDay.month, dueDay.day).difference(DateTime(now.year, now.month, now.day)).inDays : 0,
    extensionsUsed: _int(v['extensionCount']),
    extensionCap: pending == null ? 3 : _int(pending['cap']),
    extensions: [
      for (final e in (v['extensions'] as List? ?? const []).cast<Map<String, dynamic>>())
        Extension(
          n: _int(e['seq']),
          chargePaise: null,
          oldDue: _dueAt(e['oldDueDate']) ?? dueAt,
          newDue: _dueAt(e['newDueDate']) ?? dueAt,
          status: ExtensionStatus.of(e['status']),
        ),
    ],
    closedAt: _date(v['closedOn']),
    disbursalUtr: v['disbursalTxnRef'] as String?,
    pendingExtension: pending == null ? null : extensionOfferOf(pending, dueAt),
  );
}

DateTime? _dueAt(Object? day) => _date(day)?.let((d) => DateTime(d.year, d.month, d.day, 23, 59, 59));

/// `LoanView.pendingExtension`; [currentDue] is the loan's due date before this extension.
ExtensionOffer extensionOfferOf(Map<String, dynamic> v, DateTime currentDue) {
  final q = (v['quoteForToday'] as Map<String, dynamic>?) ?? const {};
  return ExtensionOffer(
    id: '${v['id']}',
    version: _int(v['version']),
    status: ExtensionStatus.of(v['status']),
    n: _int(v['seq']),
    cap: _int(v['cap']),
    feePaise: _int(q['feePaise']),
    gstPaise: _int(q['gstPaise']),
    interestPaise: _int(q['interestPaise']),
    latePaise: _int(q['lateChargePaise']),
    totalPaise: _int(q['totalPaise']),
    oldDue: currentDue,
    newDue: _dueAt(v['newDueDate']) ?? currentDue.add(const Duration(days: 14)),
    expiresAt: _date(v['expiresAt']) ?? currentDue,
  );
}

/// `BorrowerEmiView`.
EmiOffer emiOf(Map<String, dynamic> v) {
  final first = (v['instalments'] as List? ?? const []).cast<Map<String, dynamic>>().firstOrNull ?? const {};
  return EmiOffer(
    id: '${v['id']}',
    version: _int(v['version']),
    status: EmiStatus.of(v['status']),
    instalments: _int(v['instalmentCount']),
    instalmentPaise: _int(first['totalPaise']),
    firstDue: _date(first['dueDate']) ?? DateTime.now(),
    totalPaise: _int(v['totalPaise']),
  );
}

/// `ProfileView` (masked by the server) and the customer code from sign-in.
Customer customerOf(Map<String, dynamic> p, String? code) {
  final name = '${p['fullName'] ?? ''}'.trim();
  final account = '${p['salaryAccountNumber'] ?? ''}';
  final mobile = '${p['mobile'] ?? ''}';
  return Customer(
    id: code ?? '',
    firstName: name.split(' ').first,
    fullName: name,
    mobileMasked: mobile.length < 4 ? mobile : '+91 ••••• •${mobile.substring(mobile.length - 4)}',
    email: '${p['email'] ?? ''}',
    panMasked: '${p['pan'] ?? ''}',
    aadhaarMasked: '${p['aadhaar'] ?? ''}',
    dob: '${p['dob'] ?? ''}',
    address: '${p['address'] ?? ''}',
    employer: '${p['employer'] ?? ''}',
    monthlyIncomePaise: _int(p['monthlySalaryPaise']),
    bankName: '${p['salaryBank'] ?? ''}',
    bankMasked: account.length < 4 ? account : '••${account.substring(account.length - 4)}',
    ifsc: '${p['salaryIfsc'] ?? ''}',
    referralCode: '',
  );
}

extension _Let<T extends Object> on T {
  R let<R>(R Function(T) f) => f(this);
}
