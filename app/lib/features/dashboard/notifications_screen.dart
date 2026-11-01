import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import '../../core/api/api.dart';
import '../../core/format.dart';
import '../../core/widgets/widgets.dart';

/// C11: in-app notifications. Refreshes on open and on pull; a tap opens the related screen.
class NotificationsScreen extends StatefulWidget {
  const NotificationsScreen({super.key});

  @override
  State<NotificationsScreen> createState() => _NotificationsScreenState();
}

class _NotificationsScreenState extends State<NotificationsScreen> {
  late Future<List<Notice>> _notices = api.notices();

  Future<void> _refresh() {
    setState(() {
      _notices = api.notices();
    });
    return _notices.then((_) {}, onError: (_) {});
  }

  @override
  Widget build(BuildContext context) => FutureBuilder(
        future: _notices,
        builder: (context, snap) => DnPage(
          title: 'Notifications',
          back: true,
          onRefresh: _refresh,
          children: [
            if (snap.hasError)
              DnStateView(
                icon: Icons.cloud_off_rounded,
                error: true,
                title: 'Couldn’t load notifications',
                body: snap.error is ApiException ? (snap.error as ApiException).message : null,
                primary: 'Try again',
                onPrimary: _refresh,
                secondary: 'Back to home',
                onSecondary: () => context.go('/dashboard'),
              )
            else if (!snap.hasData)
              for (var i = 0; i < 4; i++) const Padding(padding: EdgeInsets.only(bottom: 12), child: DnSkeleton(height: 64, radius: 16))
            else if (snap.data!.isEmpty)
              const DnStateView(icon: Icons.notifications_none_rounded, title: 'You’re all caught up.')
            else
              DnPanel(
                padding: EdgeInsets.zero,
                child: Column(children: [
                  for (final (i, n) in snap.data!.indexed)
                    DnListRow(
                      first: i == 0,
                      icon: n.read ? Icons.notifications_none_rounded : Icons.notifications_active_rounded,
                      title: n.title,
                      detail: '${n.body}\n${_when(n.at)}',
                      onTap: n.route == null ? null : () => openNoticeRoute(context, n.route!),
                    ),
                ]),
              ),
          ],
        ),
      );
}

/// CC-53: a notice's route resolves through the app router. One that no longer matches a screen
/// lands on the dashboard with a toast instead of a broken page.
void openNoticeRoute(BuildContext context, String route) {
  final router = GoRouter.of(context);
  if (router.configuration.findMatch(Uri.parse(route)).isError) {
    showDnToast(context, 'That item is no longer available.', error: true);
    router.go('/dashboard');
    return;
  }
  router.go(route);
}

String _when(DateTime at) {
  final now = DateTime.now();
  final days = DateTime(now.year, now.month, now.day).difference(DateTime(at.year, at.month, at.day)).inDays;
  return switch (days) { 0 => 'Today', 1 => 'Yesterday', _ => dayMonth(at) };
}
