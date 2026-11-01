import 'dart:math';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'models.dart';

/// Where the session lives. The refresh token and device id go to [SecureStore] (Android keystore);
/// tests use [MemoryStore]. The access token is never stored: it lives in memory for 15 minutes.
abstract class KeyStore {
  Future<String?> read(String key);
  Future<void> write(String key, String? value);
}

class SecureStore implements KeyStore {
  static const _s = FlutterSecureStorage();
  @override
  Future<String?> read(String key) => _s.read(key: key);
  @override
  Future<void> write(String key, String? value) => value == null ? _s.delete(key: key) : _s.write(key: key, value: value);
}

class MemoryStore implements KeyStore {
  final values = <String, String>{};
  @override
  Future<String?> read(String key) async => values[key];
  @override
  Future<void> write(String key, String? value) async => value == null ? values.remove(key) : values[key] = value;
}

/// A random v4 UUID (device id, Idempotency-Key). No package for 10 lines.
String uuid4() {
  final r = Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  final h = b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();
  return '${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}';
}

/// The one HTTP door. Unwraps the backend `ApiResponse` envelope (`data` / `error.code`) and maps
/// every failure to an [ApiException]: no connection → OFFLINE (E3), 5xx → SERVER_ERROR with the
/// `X-Request-Id` (E4), 401 → one refresh, then SESSION_EXPIRED (E5).
class ApiClient {
  ApiClient({required String baseUrl, KeyStore? store, HttpClientAdapter? adapter, this.appVersion, this.deviceName = 'Android phone'})
      : store = store ?? SecureStore(),
        dio = Dio(BaseOptions(
          baseUrl: baseUrl,
          connectTimeout: const Duration(seconds: 15),
          receiveTimeout: const Duration(seconds: 30),
          // Every status comes back as a response; [_unwrap] decides.
          validateStatus: (_) => true,
          headers: {'X-Client': 'android', 'Accept': 'application/json'},
        )) {
    if (adapter != null) dio.httpClientAdapter = adapter;
  }

  static const _kRefresh = 'refresh_token';
  static const _kDevice = 'device_id';

  final Dio dio;
  final KeyStore store;
  /// Sent on sign-in so Settings > Devices can show it; set from the package at start.
  String? appVersion;
  final String deviceName;

  /// Called once when the session cannot be renewed; the app opens E5.
  void Function()? onSessionExpired;

  /// The last `AuthResponse` (sign-in or refresh): `customerId`, `customerCode` (DN…), `name`.
  Map<String, dynamic>? auth;

  String? _access;
  Future<bool>? _refreshing;
  String? _deviceId;

  bool get hasAccess => _access != null;

  /// Generated once per install and kept (the server binds refresh tokens to it).
  Future<String> deviceId() async => _deviceId ??= await store.read(_kDevice) ?? await _newDevice();

  Future<String> _newDevice() async {
    final id = uuid4();
    await store.write(_kDevice, id);
    return id;
  }

  Future<bool> hasSession() async => _access != null || await store.read(_kRefresh) != null;

  /// Keeps the pair from a login or refresh (`AuthResponse`).
  Future<void> keepSession(Map<String, dynamic> auth) async {
    this.auth = auth;
    _access = auth['token'] as String?;
    final refresh = auth['refreshToken'] as String?;
    if (refresh != null) await store.write(_kRefresh, refresh);
  }

  Future<String?> refreshToken() => store.read(_kRefresh);

  Future<void> clearSession() async {
    _access = null;
    auth = null;
    await store.write(_kRefresh, null);
  }

  Future<dynamic> get(String path, {Map<String, dynamic>? query}) => send('GET', path, query: query);
  Future<dynamic> post(String path, [Object? body, Map<String, String>? headers]) => send('POST', path, body: body, headers: headers);
  Future<dynamic> put(String path, [Object? body]) => send('PUT', path, body: body);
  Future<dynamic> delete(String path) => send('DELETE', path);

  /// One call. [auth] false for the sign-in endpoints. A 401 refreshes once (single-flight: the
  /// backend treats two parallel refreshes as token theft) and retries; a second 401 ends the session.
  Future<dynamic> send(String method, String path, {Object? body, Map<String, dynamic>? query, Map<String, String>? headers, bool auth = true}) async {
    final sentWith = _access;
    var r = await _raw(method, path, body, query, headers, auth);
    if (r.statusCode == 401 && auth) {
      // Another call may already have refreshed while this one was in flight.
      final renewed = (_access != null && _access != sentWith) || await _refreshOnce();
      if (!renewed) throw _expired();
      r = await _raw(method, path, body, query, headers, auth);
      if (r.statusCode == 401) throw _expired();
    }
    return _unwrap(r);
  }

  Future<bool> _refreshOnce() => _refreshing ??= _refresh().whenComplete(() => _refreshing = null);

  Future<bool> _refresh() async {
    final token = await store.read(_kRefresh);
    if (token == null) return false;
    try {
      final data = await send('POST', '/api/auth/borrower/refresh', body: {'refreshToken': token, 'deviceId': await deviceId()}, auth: false);
      await keepSession(data as Map<String, dynamic>);
      return true;
    } on ApiException catch (e) {
      // A network blip is not a sign-out; REFRESH_INVALID / REFRESH_REUSED (CC-39) are.
      if (e.code == 'OFFLINE' || e.code == 'SERVER_ERROR') rethrow;
      await clearSession();
      return false;
    }
  }

  ApiException _expired() {
    _access = null;
    store.write(_kRefresh, null);
    onSessionExpired?.call();
    return const ApiException('SESSION_EXPIRED');
  }

  Future<Response<dynamic>> _raw(String method, String path, Object? body, Map<String, dynamic>? query, Map<String, String>? headers, bool auth) async {
    try {
      return await dio.request<dynamic>(
        path,
        data: body,
        queryParameters: query,
        options: Options(method: method, headers: {
          ...?headers,
          if (auth && _access != null) 'Authorization': 'Bearer $_access',
          // TODO(phase-11): X-Integrity-Token once the play-integrity flag is on (thin platform channel).
        }),
      );
    } on DioException catch (e) {
      // Timeouts, DNS, refused, TLS: the borrower sees "no internet" with a retry (E3).
      throw ApiException('OFFLINE', null, e.message);
    }
  }

  static dynamic _unwrap(Response<dynamic> r) {
    final status = r.statusCode ?? 0;
    final requestId = r.headers.value('x-request-id');
    final body = r.data;
    final error = body is Map ? body['error'] : null;
    if (status >= 500) throw ApiException('SERVER_ERROR', requestId);
    if (error is Map) throw ApiException('${error['code'] ?? 'UNKNOWN'}', requestId, error['message'] as String?);
    if (status == 401 || status == 403) throw ApiException(status == 401 ? 'SESSION_EXPIRED' : 'FORBIDDEN', requestId);
    if (status < 200 || status >= 300) throw ApiException('HTTP_$status', requestId);
    return body is Map ? body['data'] : body;
  }
}
