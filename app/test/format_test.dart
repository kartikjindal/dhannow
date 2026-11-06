import 'package:dhannow/core/format.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('inr formats integer paise with Indian grouping', () {
    expect(inr(0), '₹0');
    expect(inr(74200), '₹742');
    expect(inr(148400), '₹1,484');
    expect(inr(742000), '₹7,420');
    expect(inr(10000000), '₹1,00,000');
    expect(inr(1234567890), '₹1,23,45,678.90');
    expect(inr(1805), '₹18.05');
    expect(inr(-31800), '−₹318');
  });

  test('pct formats basis points', () {
    expect(pct(1000), '10%');
    expect(pct(1800), '18%');
    expect(pct(100), '1%');
    expect(pct(150), '1.5%');
    expect(pct(125), '1.25%');
  });
}
