import 'package:flutter/material.dart';

import 'core/router.dart';
import 'core/theme/theme.dart';

void main() => runApp(const DhanNowApp());

class DhanNowApp extends StatelessWidget {
  const DhanNowApp({super.key});

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
