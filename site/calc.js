// DhanNow 14-day loan math (Document (11).docx §4). Ladder amounts only, so every figure is whole rupees.
var DHANNOW = {
  ladder: [1000, 2000, 3000, 4000, 5000, 7000, 10000],
  pfRate: 0.10, gstRate: 0.18, dailyRate: 0.01, tenureDays: 14,
  lateRate: 0.02, lateCapDays: 30, extensionCap: 3,
  calc: function (p) {
    var pf = Math.round(p * this.pfRate);
    var gst = Math.round(pf * this.gstRate);
    var interest = Math.round(p * this.dailyRate * this.tenureDays);
    var total = pf + gst + interest;
    return { principal: p, pf: pf, gst: gst, interest: interest, total: total, net: p - total, repay: p };
  },
  // Collections-led extension: the same fee + GST + 14 days of interest again, plus the late charge to
  // date (2% of the loan a day, at most 30 days). Due date = previous due date + 14 days.
  extension: function (p, daysLate) {
    var c = this.calc(p);
    var late = Math.round(p * this.lateRate) * Math.min(Math.max(0, daysLate || 0), this.lateCapDays);
    return { principal: p, charge: c.total, late: late, total: c.total + late };
  },
  dueDate: function (disbursed) {
    var d = new Date(disbursed.getTime());
    d.setDate(d.getDate() + this.tenureDays);
    return d;
  }
};

if (typeof module !== 'undefined') module.exports = DHANNOW;

if (typeof module !== 'undefined' && require.main === module) {
  var assert = require('assert');
  assert.deepStrictEqual(DHANNOW.calc(1000), { principal: 1000, pf: 100, gst: 18, interest: 140, total: 258, net: 742, repay: 1000 });
  assert.deepStrictEqual(DHANNOW.calc(2000), { principal: 2000, pf: 200, gst: 36, interest: 280, total: 516, net: 1484, repay: 2000 });
  assert.deepStrictEqual(DHANNOW.extension(1000, 3), { principal: 1000, charge: 258, late: 60, total: 318 });
  assert.strictEqual(DHANNOW.calc(10000).net, 7420);
  assert.strictEqual(DHANNOW.dueDate(new Date(2026, 9, 5)).getDate(), 19);
  console.log('calc ok');
}
