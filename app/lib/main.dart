import 'package:flutter/material.dart';

import 'core/api/api.dart';
import 'core/api/http_api.dart';
import 'core/links.dart';
import 'core/router.dart';
import 'core/theme/theme.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final server = api;
  if (server is HttpApi) {
    await server.init();
    // E5: the session could not be renewed.
    server.onSessionExpired = () => router.go('/session-expired');
  }
  runApp(const DhanNowApp());
}

class DhanNowApp extends StatefulWidget {
  const DhanNowApp({super.key});

  @override
  State<DhanNowApp> createState() => _DhanNowAppState();
}

class _DhanNowAppState extends State<DhanNowApp> {
  // Server state is the only truth: refetch whenever the borrower comes back to the app.
  final _life = AppLifecycleListener(onResume: _resumed);

  /// Refetch, then follow a link that brought the app back (`/app/open?to=…`).
  static Future<void> _resumed() async {
    await api.refresh();
    final link = await takeLinkRoute();
    if (link != null && (api.signedIn || link == '/support')) router.go(link);
  }

  @override
  void dispose() {
    _life.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp.router(
        title: 'DhanNow',
        debugShowCheckedModeBanner: false,
        theme: dnTheme(),
        // Light only in v1 (CC-58).
        themeMode: ThemeMode.light,
        routerConfig: router,
      );
}
