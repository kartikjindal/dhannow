/// Money is integer paise everywhere (CLAUDE.md). The app only formats amounts the server sent;
/// it never computes a fee, interest or total itself (money parity with LoanMath).
String inr(int paise) {
  final neg = paise < 0;
  final abs = paise.abs();
  final rupees = abs ~/ 100;
  final p = abs % 100;
  final s = _group(rupees.toString());
  return '${neg ? '−' : ''}₹$s${p == 0 ? '' : '.${p.toString().padLeft(2, '0')}'}';
}

/// Indian grouping: last three digits, then pairs (1,00,000).
String _group(String d) {
  if (d.length <= 3) return d;
  final head = d.substring(0, d.length - 3);
  final tail = d.substring(d.length - 3);
  final pairs = <String>[];
  for (var i = head.length; i > 0; i -= 2) {
    pairs.insert(0, head.substring(i - 2 < 0 ? 0 : i - 2, i));
  }
  return '${pairs.join(',')},$tail';
}

const _months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/// "19 Oct", or "19 Oct 2026" with [year]. Dates are IST as the server sends them.
String dayMonth(DateTime d, {bool year = false}) => '${d.day} ${_months[d.month - 1]}${year ? ' ${d.year}' : ''}';

/// "11:59 pm".
String clock(DateTime d) {
  final h = d.hour % 12 == 0 ? 12 : d.hour % 12;
  return '$h:${d.minute.toString().padLeft(2, '0')} ${d.hour < 12 ? 'am' : 'pm'}';
}

/// Basis points as a percentage: 1000 → "10%", 125 → "1.25%".
String pct(int bps) {
  final whole = bps ~/ 100, frac = bps % 100;
  return '$whole${frac == 0 ? '' : '.${frac.toString().padLeft(2, '0').replaceAll(RegExp(r'0$'), '')}'}%';
}
