import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/api/client.dart';
import 'package:dhannow/core/api/http_api.dart';
import 'package:dhannow/core/files.dart';
import 'package:dhannow/core/links.dart' show routeForLink;
import 'package:dhannow/features/offer/sign.dart' show signaturePng;
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/features/money/extension_status_card.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// One canned answer per request; [handle] sees every request the client makes.
class FakeAdapter implements HttpClientAdapter {
  FakeAdapter(this.handle);
  final FutureOr<(int, Object?, Map<String, String>)?> Function(RequestOptions o) handle;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<Uint8List>? body, Future<void>? cancel) async {
    requests.add(o);
    final (status, json, headers) = (await handle(o))!;
    return ResponseBody.fromString(jsonEncode(json), status, headers: {
      Headers.contentTypeHeader: ['application/json'],
      for (final e in headers.entries) e.key: [e.value],
    });
  }

  @override
  void close({bool force = false}) {}
}

Map<String, Object?> ok(Object? data) => {'success': true, 'data': data};
Map<String, Object?> fail(String code) => {'success': false, 'error': {'code': code, 'message': 'server words'}};

ApiClient client(FakeAdapter a, [MemoryStore? store]) => ApiClient(baseUrl: 'https://api.test', store: store ?? MemoryStore(), adapter: a);

void main() {
  group('envelope and errors', () {
    test('data is unwrapped; error.code becomes the ApiException code with the request id', () async {
      final a = FakeAdapter((o) => o.path.endsWith('/good') ? (200, ok({'x': 1}), <String, String>{}) : (422, fail('DUPLICATE_UTR'), {'X-Request-Id': 'r-42'}));
      final c = client(a);
      expect(await c.get('/good'), {'x': 1});
      await expectLater(c.get('/bad'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'DUPLICATE_UTR').having((e) => e.requestId, 'id', 'r-42')));
      expect(a.requests.first.headers['X-Client'], 'android');
    });

    test('5xx is SERVER_ERROR with the request id (E4)', () async {
      final c = client(FakeAdapter((_) => (503, {'oops': true}, {'X-Request-Id': 'r-9'})));
      await expectLater(c.get('/x'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'SERVER_ERROR').having((e) => e.requestId, 'id', 'r-9')));
    });

    test('no connection is OFFLINE (E3)', () async {
      final c = client(FakeAdapter((_) => throw const SocketException('down')));
      await expectLater(c.get('/x'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'OFFLINE')));
    });
  });

  group('session', () {
    test('parallel 401s refresh once (single-flight) and both calls retry with the new token', () async {
      final store = MemoryStore()..values['refresh_token'] = 'r1';
      var refreshes = 0;
      final refreshDone = Completer<void>();
      final a = FakeAdapter((o) async {
        if (o.path.endsWith('/refresh')) {
          refreshes++;
          expect((o.data as Map)['refreshToken'], 'r1');
          await refreshDone.future; // both 401s are in before the refresh answers
          return (200, ok({'token': 'a2', 'refreshToken': 'r2'}), <String, String>{});
        }
        final auth = o.headers['Authorization'];
        return auth == 'Bearer a2' ? (200, ok('fine'), <String, String>{}) : (401, null, <String, String>{});
      });
      final c = client(a, store);
      final calls = [c.get('/one'), c.get('/two')];
      await Future<void>.delayed(const Duration(milliseconds: 20));
      refreshDone.complete();
      expect(await Future.wait(calls), ['fine', 'fine']);
      expect(refreshes, 1);
      expect(store.values['refresh_token'], 'r2');
    });

    test('a refused refresh ends the session (E5) and forgets the token', () async {
      final store = MemoryStore()..values['refresh_token'] = 'r1';
      var expired = 0;
      final c = client(FakeAdapter((o) => o.path.endsWith('/refresh') ? (422, fail('REFRESH_REUSED'), <String, String>{}) : (401, null, <String, String>{})), store)
        ..onSessionExpired = () => expired++;
      await expectLater(c.get('/x'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'SESSION_EXPIRED')));
      expect(expired, 1);
      expect(store.values.containsKey('refresh_token'), isFalse);
    });

    test('a network blip during refresh is OFFLINE, not a sign-out', () async {
      final store = MemoryStore()..values['refresh_token'] = 'r1';
      final c = client(FakeAdapter((o) => o.path.endsWith('/refresh') ? throw const SocketException('down') : (401, null, <String, String>{})), store);
      await expectLater(c.get('/x'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'OFFLINE')));
      expect(store.values['refresh_token'], 'r1');
    });

    test('the device id is made once and kept', () async {
      final store = MemoryStore();
      final id = await client(FakeAdapter((_) => (200, ok(null), <String, String>{})), store).deviceId();
      expect(RegExp(r'^[A-Za-z0-9._:-]{8,128}$').hasMatch(id), isTrue); // DeviceService.DEVICE_ID
      expect(await client(FakeAdapter((_) => (200, ok(null), <String, String>{})), store).deviceId(), id);
    });
  });

  group('HttpApi', () {
    test('a retried complaint reuses its Idempotency-Key; the next complaint gets a new one', () async {
      var n = 0;
      final a = FakeAdapter((o) {
        n++;
        if (n == 1) throw const SocketException('down');
        return (200, ok({'id': n, 'ticketNo': 'DN-GRV-0000$n', 'category': 'PAYMENT', 'status': 'OPEN', 'createdAt': '2026-11-02T10:00:00Z'}), <String, String>{});
      });
      final api = HttpApi(client(a));
      await expectLater(api.raiseComplaint(subject: 'Payment not reflecting', body: 'Paid on 1 Nov, not showing'), throwsA(isA<ApiException>()));
      final c = await api.raiseComplaint(subject: 'Payment not reflecting', body: 'Paid on 1 Nov, not showing');
      await api.raiseComplaint(subject: 'Payment not reflecting', body: 'Paid on 1 Nov, not showing');
      final keys = [for (final r in a.requests) r.headers['Idempotency-Key']];
      expect(keys[0], isNotNull);
      expect(keys[1], keys[0]);
      expect(keys[2], isNot(keys[0]));
      expect((a.requests[1].data as Map)['category'], 'PAYMENT');
      expect(c.id, 'DN-GRV-00002');
      expect(c.subject, 'Payment not reflecting');
    });

    test('journey: stepKey decides the route; signed out opens the intro', () async {
      final store = MemoryStore();
      final a = FakeAdapter((o) => switch (o.path) {
            '/api/applications/mine' => (200, ok([{'id': 7, 'status': 'SANCTIONED'}]), <String, String>{}),
            '/api/applications/7/journey' => (200, ok({'stepKey': 'offer.kfs', 'completed': []}), <String, String>{}),
            _ => (404, fail('NOT_FOUND'), <String, String>{}),
          });
      final api = HttpApi(client(a, store));
      expect((await api.journey()).route, '/intro');
      store.values['refresh_token'] = 'r';
      final j = await api.journey();
      expect(j.stage, Stage.offerFlow);
      expect(j.route, '/loan/kfs');
    });
  });

  group('uploads and hand-offs (Phase 11b)', () {
    final jpg = PickedFile('shot.jpg', bytes: 3, data: Uint8List.fromList([1, 2, 3]));

    /// An HttpApi signed in on application 7 with an open loan 11; [extra] answers anything else.
    Future<(HttpApi, FakeAdapter)> signedIn(FutureOr<(int, Object?, Map<String, String>)?> Function(RequestOptions o)? extra) async {
      final store = MemoryStore()..values['refresh_token'] = 'r';
      final a = FakeAdapter((o) {
        switch (o.path) {
          case '/api/applications/mine':
            return (200, ok([{'id': 7, 'status': 'ACTIVE', 'loanId': 11}]), <String, String>{});
          case '/api/applications/7/journey':
            return (200, ok({'stepKey': 'status', 'completed': []}), <String, String>{});
          case '/api/loan/11':
            return (200, ok({'id': 11, 'principalPaise': 100000, 'processingFeePaise': 10000, 'gstPaise': 1800, 'netDisbursedPaise': 74200, 'totalRepayablePaise': 100000, 'disbursedOn': '2026-11-01', 'dueDate': '2026-11-15', 'lifecycleStatus': 'ACTIVE'}), <String, String>{});
          case '/api/loan/11/outstanding':
            return (200, ok({'verifiedPaise': 0, 'penaltyPaise': 0}), <String, String>{});
        }
        if (o.uri.host == 'bucket.test') return (200, null, <String, String>{});
        return extra?.call(o) ?? (404, fail('NOT_FOUND'), <String, String>{});
      });
      final api = HttpApi(client(a, store))..pollDelay = Duration.zero;
      await api.journey();
      return (api, a);
    }

    test('a payment uploads its screenshot (presign → PUT) once, and a retry reuses proof and key', () async {
      var pays = 0;
      final (api, a) = await signedIn((o) {
        // /api/storage answers without the envelope.
        if (o.path == '/api/storage/presign-upload') return (200, {'key': 'loan/repayment-proof/k1.jpg', 'url': 'https://bucket.test/put?sig=1', 'method': 'PUT', 'expiresInSeconds': 300}, <String, String>{});
        if (o.path == '/api/loan/11/repayments') {
          pays++;
          if (pays == 1) throw const SocketException('down');
          return (200, ok({'id': 5, 'status': 'PENDING_VERIFICATION'}), <String, String>{});
        }
        return null;
      });
      await expectLater(api.submitPayment(utr: '429011238876', paidOn: DateTime(2026, 11, 2), paise: 100000, screenshot: jpg), throwsA(isA<ApiException>()));
      await api.submitPayment(utr: '429011238876', paidOn: DateTime(2026, 11, 2), paise: 100000, screenshot: jpg);

      final presigns = a.requests.where((r) => r.path == '/api/storage/presign-upload').toList();
      expect(presigns, hasLength(1), reason: 'the retry reuses the uploaded proof');
      expect(presigns.single.data, {'category': 'REPAYMENT_PROOF', 'filename': 'shot.jpg', 'contentType': 'image/jpeg'});
      final put = a.requests.singleWhere((r) => r.uri.host == 'bucket.test');
      expect(put.method, 'PUT');
      expect(put.headers[Headers.contentTypeHeader], 'image/jpeg');
      expect(put.headers['Authorization'], isNull, reason: 'never send the token to storage');
      final payments = a.requests.where((r) => r.path == '/api/loan/11/repayments').toList();
      expect((payments.last.data as Map)['proofUrl'], 'loan/repayment-proof/k1.jpg');
      expect(payments.first.headers['Idempotency-Key'], payments.last.headers['Idempotency-Key']);
    });

    test('a payment without a screenshot, or a file over 5 MB, is refused before any call', () async {
      final (api, a) = await signedIn(null);
      final before = a.requests.length;
      await expectLater(api.submitPayment(utr: '429011238876', paidOn: DateTime(2026, 11, 2), paise: 100000), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'PAYMENT_PROOF_REQUIRED')));
      final big = PickedFile('slip.pdf', bytes: 6 * 1024 * 1024, data: Uint8List(1));
      await expectLater(api.saveStep('pan-card', const {}, files: {'front': big, 'back': big}), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'UPLOAD_REFUSED')));
      expect(a.requests.length, before);
    });

    test('guided selfie: three SELFIE uploads, then verify/selfie/frames with the three keys (REVIEW)', () async {
      var n = 0;
      final (api, a) = await signedIn((o) => switch (o.path) {
            '/api/applications/7/verify/presign-upload' => (200, ok({'key': 'applications/7/selfie/${++n}.jpg', 'url': 'https://bucket.test/$n'}), <String, String>{}),
            '/api/applications/7/verify/selfie/frames' => (200, ok({'checkType': 'SELFIE', 'status': 'REVIEW'}), <String, String>{}),
            '/api/applications/7/journey/OFFER_SELFIE' => (200, ok({'stepKey': 'offer.address'}), <String, String>{}),
            _ => null,
          });
      expect(await api.uploadSelfie([jpg, jpg, jpg]), 'REVIEW');
      final frames = a.requests.singleWhere((r) => r.path.endsWith('/selfie/frames'));
      expect(frames.data, {'straightKey': 'applications/7/selfie/1.jpg', 'rightKey': 'applications/7/selfie/2.jpg', 'leftKey': 'applications/7/selfie/3.jpg'});
      expect(a.requests.where((r) => r.path.endsWith('/presign-upload')).map((r) => (r.data as Map)['docType']), everyElement('SELFIE'));
    });

    test('DigiLocker: the provider page opens with the allowlisted return, then status is read back', () async {
      var polls = 0;
      final (api, a) = await signedIn((o) => switch (o.path) {
            '/api/applications/7/verify/digilocker/init' => (200, ok({'status': 'PENDING', 'derived': {'url': 'https://digilocker.test/s/1'}}), <String, String>{}),
            '/api/applications/7/verify/digilocker/status' => (200, ok({'derived': {'completed': ++polls > 1, 'failed': false}}), <String, String>{}),
            '/api/applications/7/verify/digilocker/complete' => (200, ok({'status': 'PASS'}), <String, String>{}),
            '/api/applications/7/journey/OFFER_DIGILOCKER' => (200, ok({'stepKey': 'offer.references'}), <String, String>{}),
            _ => null,
          });
      final opened = <String>[];
      api.openProvider = (url) async => opened.add(url);
      await api.verifyDigilocker();
      expect(opened, ['https://digilocker.test/s/1']);
      expect((a.requests.firstWhere((r) => r.path.endsWith('/digilocker/init')).data as Map)['redirectUrl'], startsWith('https://dhannow.com/app/cb/'));
      expect(polls, 2, reason: 'a lagging status is read again');
      expect(a.requests.any((r) => r.path.endsWith('/journey/OFFER_DIGILOCKER')), isTrue);
    });

    test('A13: the question comes from the bureau check; a refused answer keeps it open', () async {
      final summary = [
        {'checkType': 'BUREAU', 'status': 'REVIEW', 'derived': {'bureauChallenge': true, 'bureauChallengeQuestion': 'Which lender?', 'bureauChallengeOptions': ['A', 'B']}},
      ];
      final (api, _) = await signedIn((o) => switch (o.path) {
            '/api/applications/7/verify/summary' => (200, ok(summary), <String, String>{}),
            '/api/applications/7/verify/bureau/challenge/answer' => (200, ok({'status': 'REVIEW'}), <String, String>{}),
            _ => null,
          });
      final q = await api.creditQuestion();
      expect(q!.question, 'Which lender?');
      expect(q.options, ['A', 'B']);
      await expectLater(api.answerQuestion('A'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'BUREAU_CHALLENGE_ANSWER_INVALID')));
    });

    testWidgets('the drawn signature becomes a PNG', (t) async {
      final png = await t.runAsync(() => signaturePng([
            [const Offset(10, 10), const Offset(60, 40)],
            [const Offset(80, 20)],
          ]));
      expect(png!.sublist(0, 4), [0x89, 0x50, 0x4E, 0x47]);
    });
  });

  group('backend gaps closed (Phase 11c)', () {
    /// An HttpApi signed in on application 7 in [status]; [extra] answers anything else.
    Future<(HttpApi, FakeAdapter)> on(String status, FutureOr<(int, Object?, Map<String, String>)?> Function(RequestOptions o)? extra, {Map<String, Object?> journey = const {}}) async {
      final store = MemoryStore()..values['refresh_token'] = 'r';
      final a = FakeAdapter((o) {
        if (o.path == '/api/applications/mine') return (200, ok([{'id': 7, 'status': status}]), <String, String>{});
        if (o.path == '/api/applications/7/journey') return (200, ok({'stepKey': 'status', 'completed': [], ...journey}), <String, String>{});
        if (o.uri.host == 'bucket.test') return (200, null, <String, String>{});
        return extra?.call(o) ?? (200, ok(null), <String, String>{});
      });
      final api = HttpApi(client(a, store));
      await api.journey();
      return (api, a);
    }

    test('B1: one quote per ladder amount, with the server’s rates', () async {
      Map<String, Object?> q(int p) => {'principalPaise': p, 'processingFeePaise': p ~/ 10, 'gstPaise': p * 18 ~/ 1000, 'interestPaise': p * 14 ~/ 100, 'netDisbursedPaise': p * 742 ~/ 1000, 'totalRepayablePaise': p, 'tenureDays': 14, 'feeRateBps': 1000, 'gstRateBps': 1800, 'dailyRateBps': 100};
      final (api, _) = await on('SANCTIONED', (o) => o.path == '/api/applications/7/offer/quotes' ? (200, ok([q(100000), q(200000)]), <String, String>{}) : null);
      final list = await api.quotes();
      expect([for (final x in list) x.netPaise], [74200, 148400]);
      expect((await api.quote(200000)).dailyRateBps, 100);
    });

    test('S5: the code goes to forgot-password/otp; the reset sends code + password and signs in', () async {
      final store = MemoryStore();
      final a = FakeAdapter((o) => switch (o.path) {
            '/api/auth/borrower/forgot-password/reset' => (200, ok({'token': 'a1', 'refreshToken': 'r1', 'customerId': 3, 'customerCode': 'DN0000003'}), <String, String>{}),
            _ => (200, ok({'sent': true}), <String, String>{}),
          });
      final api = HttpApi(client(a, store));
      await api.sendOtp('9876543210', purpose: OtpFor.reset);
      await api.verifyOtp('123456', purpose: OtpFor.reset);
      expect(a.requests.map((r) => r.path), ['/api/auth/borrower/forgot-password/otp'], reason: 'the code is checked with the reset');
      await api.resetPassword('abc12345!');
      final reset = a.requests.last.data as Map;
      expect((reset['mobile'], reset['otp'], reset['password']), ('9876543210', '123456', 'abc12345!'));
      expect(reset['deviceId'], isNotNull);
      expect(api.signedIn, isTrue);
      expect(store.values['refresh_token'], 'r1');
    });

    test('S5: a wrong code at reset reads as the OTP error', () async {
      final api = HttpApi(client(FakeAdapter((o) => o.path.endsWith('/reset') ? (422, fail('INVALID_OTP'), <String, String>{}) : (200, ok({'sent': true}), <String, String>{}))));
      await api.sendOtp('9876543210', purpose: OtpFor.reset);
      await api.verifyOtp('000000', purpose: OtpFor.reset);
      await expectLater(api.resetPassword('abc12345!'), throwsA(isA<ApiException>().having((e) => e.code, 'code', 'OTP_INVALID')));
    });

    test('A12 address proof is filed; A1b sends only the Aadhaar last 4', () async {
      var n = 0;
      final (api, a) = await on('KYC_PENDING', (o) => o.path.endsWith('/presign-upload') ? (200, ok({'key': 'applications/7/x/${++n}.pdf', 'url': 'https://bucket.test/$n'}), <String, String>{}) : null);
      final pdf = PickedFile('Gas_bill.pdf', bytes: 3, data: Uint8List.fromList([1, 2, 3]));
      await api.saveStep('address-proof', const {}, files: {'Gas bill': pdf});
      expect(a.requests.singleWhere((r) => r.path.endsWith('/verify/documents')).data, {'docType': 'GAS_BILL', 'objectKeys': ['applications/7/x/1.pdf'], 'filePassword': null});
      await api.saveStep('apply', {'name': 'Aarav Sharma', 'mobile': '9876543210', 'email': 'a@b.in', 'pan': 'ABCPS1234K', 'aadhaarLast4': '4821'});
      final w = a.requests.singleWhere((r) => r.path == '/api/onboarding-waitlist').data as Map;
      expect(w['aadhaarLast4'], '4821');
      expect(w.containsKey('aadhaar'), isFalse);
    });

    test('KFS acceptance records the accepted page versions from app config', () async {
      final (api, a) = await on('SANCTIONED', null);
      api.config = const AppConfig(minVersion: '0.0.0', support: Contact('s', '1', 'e'), grievance: Contact('g', '2', 'f'), legal: [
        LegalLink('terms', 'Terms', 'https://dhannow.com/terms.html', '3'),
        LegalLink('consent', 'Consent', 'https://dhannow.com/consent.html', '1'),
        LegalLink('kfs-policy', 'KFS policy', 'https://dhannow.com/kfs-policy.html', '2'),
      ]);
      await api.acceptKfs();
      expect(a.requests.singleWhere((r) => r.path.endsWith('/verify/agreement')).data, {
        'versions': [
          {'key': 'terms', 'version': '3'},
          {'key': 'consent', 'version': '1'},
          {'key': 'kfs-policy', 'version': '2'},
        ],
      });
    });

    test('an open bureau question opens A13 on resume; a legacy re-borrow becomes an offer', () async {
      final (q, _) = await on('DRAFT', null, journey: {'stepKey': 'signup.submitted', 'bureauChallengePending': true});
      expect((await q.journey()).route, '/credit-question');

      var status = 'CLOSED';
      final store = MemoryStore()..values['refresh_token'] = 'r';
      final a = FakeAdapter((o) => switch (o.path) {
            '/api/applications/mine' => (200, ok([{'id': 8, 'status': status}]), <String, String>{}),
            '/api/applications/reborrow' => (200, ok({'id': 8, 'status': status = 'PRE_APPROVED'}), <String, String>{}),
            '/api/me/credit-level' => (200, ok({'level': 2, 'maxPaise': 200000, 'ladder': []}), <String, String>{}),
            '/api/applications/8/apply' => (200, ok({'id': 8, 'status': status = 'SANCTIONED'}), <String, String>{}),
            '/api/applications/8/journey' => (200, ok({'stepKey': 'offer.amount'}), <String, String>{}),
            _ => (200, ok(null), <String, String>{}),
          });
      final api = HttpApi(client(a, store));
      await api.journey();
      expect(await api.reborrow(), Stage.offer);
      expect((a.requests.singleWhere((r) => r.path.endsWith('/apply')).data as Map)['amountPaise'], 200000);
    });

    test('SMS/email links: repay, loans, support; anything else is the journey', () {
      expect(routeForLink('https://dhannow.com/app/open?to=repay'), '/repay');
      expect(routeForLink('https://dhannow.com/app/open?to=loans'), '/loans');
      expect(routeForLink('https://dhannow.com/app/open?to=support'), '/support');
      expect(routeForLink('https://dhannow.com/app/open?to=admin'), isNull);
      expect(routeForLink('https://dhannow.com/app/open'), isNull);
      expect(routeForLink('https://dhannow.com/app/cb/esign?outcome=success'), isNull);
      expect(routeForLink(null), isNull);
    });

    test('upload refusals from the server have their own messages', () {
      for (final code in ['UNSUPPORTED_FILE_TYPE', 'FILE_TOO_LARGE', 'UPLOAD_NOT_FOUND', 'UPLOAD_UNAVAILABLE']) {
        expect(ApiException(code).message, isNot(const ApiException('SOMETHING_ELSE').message), reason: code);
      }
    });
  });

  group('decoding', () {
    final app = {'id': 7, 'loanId': 11};
    Map<String, Object?> view(Object? lifecycle, [Object? ext]) => {
          'id': 11,
          'principalPaise': 100000,
          'processingFeePaise': 10000,
          'gstPaise': 1800,
          'netDisbursedPaise': 74200,
          'totalRepayablePaise': 100000,
          'disbursedOn': '2026-11-01',
          'dueDate': '2026-11-15',
          'lifecycleStatus': lifecycle,
          'repaymentStatus': 'SOMETHING_NEW',
          'upfrontInterestPaise': 14000,
          'loanAccountNumber': 'DNL0000011',
          'daysLeft': 9,
          'extensionCount': 0,
          'pendingExtension': ext,
        };

    test('loan money is the server’s; unknown enums decode to unknown', () {
      final l = loanOf(view('ARCHIVED_LATER'), app, {'verifiedPaise': 0, 'penaltyPaise': 0});
      expect(l.lifecycle, Lifecycle.unknown);
      expect(l.repayment, Repayment.unknown);
      expect(l.quote.netPaise, 74200);
      expect(l.quote.interestPaise, 14000);
      expect(l.outstandingPaise, 100000);
      expect(l.dueAt, DateTime(2026, 11, 15, 23, 59, 59));
      expect((l.accountNo, l.daysLeft), ('DNL0000011', 9));
    });

    test('the open extension keeps its id and version and maps the server states', () {
      final l = loanOf(view('OVERDUE_D1', {
        'id': 'e-1',
        'status': 'APPROVED',
        'version': 3,
        'seq': 1,
        'cap': 3,
        'quoteForToday': {'feePaise': 10000, 'gstPaise': 1800, 'interestPaise': 14000, 'lateChargePaise': 6000, 'totalPaise': 31800},
        'newDueDate': '2026-11-29',
        'expiresAt': '2026-11-03T18:29:59Z',
        'offeredBy': 'Priya',
      }), app, null);
      final o = l.pendingExtension!;
      expect(o.officer, 'Priya');
      expect((o.id, o.version, o.status, o.totalPaise), ('e-1', 3, ExtensionStatus.offered, 31800));
      expect(ExtensionStatus.of('ACCEPTED'), ExtensionStatus.confirmed);
      expect(ExtensionStatus.of('ACTIVE'), ExtensionStatus.verified);
      expect(ExtensionStatus.of('BRAND_NEW'), ExtensionStatus.unknown);
      expect(EmiStatus.of('BRAND_NEW'), EmiStatus.unknown);
    });

    testWidgets('an extension state this build doesn’t know renders a safe banner', (t) async {
      final l = loanOf(view('ACTIVE', {'id': 'e-1', 'status': 'BRAND_NEW', 'version': 1, 'seq': 1, 'cap': 3}), app, null);
      await t.pumpWidget(MaterialApp(theme: dnTheme(), home: Scaffold(body: ExtensionStatusCard(loan: l))));
      expect(find.text('Your extension is being updated'), findsOneWidget);
      expect(t.takeException(), isNull);
    });

    test('version floor (EXT-24)', () {
      expect(versionBelow('1.2.9', '1.2.10'), isTrue);
      expect(versionBelow('1.3.0+7', '1.2.10'), isFalse);
      expect(versionBelow('1.0.0', '1.0.0'), isFalse);
    });

    test('quote decoding takes the rates from the server', () {
      final q = quoteOf({'principalPaise': 100000, 'processingFeePaise': 10000, 'gstPaise': 1800, 'interestPaise': 14000, 'netDisbursedPaise': 74200, 'totalRepayablePaise': 100000, 'tenureDays': 14, 'feeRateBps': 1000, 'gstRateBps': 1800, 'dailyRateBps': 100});
      expect((q.feeRateBps, q.gstRateBps, q.dailyRateBps, q.netPaise), (1000, 1800, 100, 74200));
    });
  });
}
