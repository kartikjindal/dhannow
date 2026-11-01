import 'package:dhannow/core/api/api.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  setUp(() => api
    ..latency = Duration.zero
    ..setScenario(Scenario.offer));

  // Money parity: the demo server must quote the PLAN Phase 1 table (LoanMathDhanNowTest).
  test('quotes match the worked table: net in the bank, repay = principal', () async {
    final q = await api.quotes();
    expect([for (final x in q) x.netPaise ~/ 100], [742, 1484, 2226, 2968, 3710, 5194, 7420]);
    for (final x in q) {
      expect(x.repayPaise, x.principalPaise);
      expect(x.feePaise + x.gstPaise + x.interestPaise + x.netPaise, x.principalPaise);
    }
  });

  test('amount above the credit level is refused', () async {
    expect(() => api.chooseAmount(200000), throwsA(isA<ApiException>()));
    await api.chooseAmount(100000);
    expect(api.stage, Stage.offerFlow);
  });

  test('payment then accountant verification closes the loan and steps up the level', () async {
    api.setScenario(Scenario.active);
    await api.submitPayment(utr: '429012345678', paidOn: DateTime.now(), paise: 100000);
    expect(api.loan!.repayment, Repayment.pendingVerification);
    api.demoVerifyPayment();
    expect(api.loan, isNull);
    expect((await api.creditLevel()).level, 2);
  });
}
