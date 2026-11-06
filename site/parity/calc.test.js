// Calculator parity (PLAN Phase 10a W6): site/calc.js must give the committed fixture's figures for every
// ladder amount. The backend asserts the same fixture against LoanMath. Run: node site/parity/calc.test.js
var assert = require('assert');
var path = require('path');
var fs = require('fs');
var DHANNOW = require('../calc.js');
var fixture = JSON.parse(fs.readFileSync(path.join(__dirname, 'ladder.json'), 'utf8'));

var paise = function (rupees) { return Math.round(rupees * 100); };

assert.deepStrictEqual(DHANNOW.ladder.map(paise), fixture.ladder.map(function (r) { return r.borrowPaise; }), 'ladder amounts');
assert.strictEqual(DHANNOW.tenureDays, fixture.terms.tenureDays, 'tenure');
assert.strictEqual(DHANNOW.lateCapDays, fixture.terms.lateCapDays, 'late cap');
assert.strictEqual(DHANNOW.extensionCap, fixture.terms.extensionCap, 'extension cap');

fixture.ladder.forEach(function (row) {
  var c = DHANNOW.calc(row.borrowPaise / 100);
  var got = {
    level: row.level, borrowPaise: paise(c.principal), feePaise: paise(c.pf), gstPaise: paise(c.gst),
    interestPaise: paise(c.interest), deductionsPaise: paise(c.total), netPaise: paise(c.net), repayPaise: paise(c.repay),
    extensionChargePaise: paise(DHANNOW.extension(c.principal, 0).total),
    latePerDayPaise: paise(DHANNOW.extension(c.principal, 1).late)
  };
  assert.deepStrictEqual(got, row, 'ladder level ' + row.level);
});

fixture.extensionExamples.forEach(function (e) {
  assert.strictEqual(paise(DHANNOW.extension(e.borrowPaise / 100, e.daysLate).total), e.totalPaise,
    'extension ' + e.borrowPaise + ' paise, ' + e.daysLate + ' days late');
});

// The anchors CLAUDE.md quotes.
assert.strictEqual(fixture.ladder[0].netPaise, 74200);
assert.strictEqual(fixture.ladder[6].netPaise, 742000);

console.log('calc parity ok (' + fixture.ladder.length + ' ladder amounts, ' + fixture.extensionExamples.length + ' extension examples)');
