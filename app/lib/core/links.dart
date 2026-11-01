import 'package:flutter/widgets.dart';
import 'package:url_launcher/url_launcher.dart';

import 'api/api.dart';
import 'brand.dart';
import 'widgets/widgets.dart';

/// The URL of a dhannow.com page (`terms.html`, `privacy.html#retention`): the server's link for
/// that page from `GET /api/app/config` when it lists one, else the site from `brand.dart`.
String siteUrl(String path) {
  final hash = path.indexOf('#');
  final page = hash < 0 ? path : path.substring(0, hash);
  final link = api.config.link(page.replaceAll('.html', ''));
  return link == null ? 'https://${Brand.site}/$path' : '${link.url}${hash < 0 ? '' : path.substring(hash)}';
}

/// Opens [url] in the browser (TODO(phase-11): Android custom tabs). The demo only names it.
Future<void> openUrl(BuildContext context, String url) async {
  if (demoControls) return showDnToast(context, 'Opens ${url.replaceFirst('https://', '')}');
  final ok = await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication).catchError((_) => false);
  if (!ok && context.mounted) showDnToast(context, 'Couldn’t open the page. Visit ${Brand.site}.', error: true);
}

Future<void> openSitePage(BuildContext context, String path) => openUrl(context, siteUrl(path));
