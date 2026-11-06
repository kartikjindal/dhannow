import 'dart:async';

import 'package:dhannow/core/api/api.dart';

/// Every widget test runs on the in-memory demo server (the default build talks to the backend).
Future<void> testExecutable(FutureOr<void> Function() testMain) async {
  api = DemoApi();
  await testMain();
}
