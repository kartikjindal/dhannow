import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:dhannow/core/api/api.dart';
import 'package:dhannow/core/api/client.dart';
import 'package:dhannow/core/api/http_api.dart';
import 'package:dhannow/core/theme/theme.dart';
import 'package:dhannow/features/money/extension_status_card.dart';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// One canned answer per request; [handle] sees every request the client makes.
class FakeAdapter implements HttpClientAdapter {
  FakeAdapter(this.handle);
  final FutureOr<(int, Object?, Map<String, String>)> Function(RequestOptions o) handle;
  final requests = <RequestOptions>[];

  @override
  Future<ResponseBody> fetch(RequestOptions o, Stream<Uint8List>? body, Future<void>? cancel) async {
    requests.add(o);
    final (status, json, headers) = await handle(o);
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
      }), app, null);
      final o = l.pendingExtension!;
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

    test('quote decoding reads the rates back from the server figures', () {
      final q = quoteOf({'principalPaise': 100000, 'processingFeePaise': 10000, 'gstPaise': 1800, 'interestPaise': 14000, 'netDisbursedPaise': 74200, 'totalRepayablePaise': 100000, 'tenureDays': 14});
      expect((q.feeRateBps, q.gstRateBps, q.dailyRateBps, q.netPaise), (1000, 1800, 100, 74200));
    });
  });
}
