// node src/test/js/SaveCompareTest.js
//
// Comparing two saves of the list: which save the dialog offers to compare
// with, and how it writes each value the server sends.

'use strict';

const assert = require('assert');
const SaveCompare = require('../../ui/js/SaveCompare.js');

const save = (guid, date, userSaveName) =>
	({guid: guid, date: date, userSaveName: userSaveName || '', playerName: 'Phantom', sceneTitle: 'Caed Nua'});

const tests = [];
const test = (name, body) => tests.push({name: name, body: body});

test('an edited save is compared with the save it was made from', () => {
	// The editor's Save keeps the save's own timestamp, so the original and
	// the edited copy carry the same one.
	const saves = [
		save('a', '2026-07-20 16:49:35')
		, save('a', '2026-07-20 20:14:01')
		, save('a', '2026-07-20 20:14:01', 'Caed Nua (edited)')
		, save('a', '2026-07-20 21:03:31')
	];

	assert.deepStrictEqual(SaveCompare.pair(saves, 2), {before: 1, after: 2});
});

test('among copies of one save, the one whose name this one extends is where it came from', () => {
	// Edited twice: the game's save, an edit of it, and an edit of the edit,
	// all with the game's timestamp. The name typed on Save is the only trace.
	const saves = [
		save('a', '2026-07-20 20:14:01', 'Caed Nua (edited) (edited)')
		, save('a', '2026-07-20 20:14:01', 'Caed Nua (edited)')
		, save('a', '2026-07-20 20:14:01', 'ConsoleTest')
		, save('a', '2026-07-20 20:14:01')
	];

	assert.deepStrictEqual(SaveCompare.pair(saves, 0), {before: 1, after: 0});
	assert.deepStrictEqual(SaveCompare.pair(saves, 1), {before: 3, after: 1}, 'an unnamed save starts every name');
	assert.deepStrictEqual(SaveCompare.pair(saves, 2), {before: 3, after: 2});
	assert.deepStrictEqual(SaveCompare.pair(saves, 3), {before: 0, after: 3}, 'the first of the copies, if nothing else');
});

test('a save is compared with the one before it in its playthrough', () => {
	const saves = [
		save('a', '2026-07-20 16:49:35')
		, save('b', '2026-07-21 10:00:00')
		, save('a', '2026-07-20 21:03:31')
		, save('a', '2026-07-20 20:14:01')
	];

	assert.deepStrictEqual(SaveCompare.pair(saves, 2), {before: 3, after: 2});
});

test("the playthrough's first save is compared with the one after it", () => {
	const saves = [
		save('a', '2026-07-20 21:03:31')
		, save('a', '2026-07-20 16:49:35')
		, save('a', '2026-07-20 20:14:01')
	];

	assert.deepStrictEqual(SaveCompare.pair(saves, 1), {before: 1, after: 2});
});

test('a save alone in its playthrough is compared with its neighbour, earlier first', () => {
	const saves = [
		save('a', '2026-07-20 21:03:31')
		, save('b', '2026-07-01 10:00:00')
		, save('c', '2026-08-01 10:00:00')
	];

	assert.deepStrictEqual(SaveCompare.pair(saves, 1), {before: 1, after: 2});
	assert.deepStrictEqual(SaveCompare.pair(saves, 2), {before: 1, after: 2});
	assert.strictEqual(SaveCompare.pair([saves[0]], 0), null, 'one save has nothing to compare with');
});

test('numbers are written as the rest of the editor writes them', () => {
	assert.strictEqual(SaveCompare.show(291248, null), '291,248');
	assert.strictEqual(SaveCompare.show(1258.1097, null), '1,258.11');
	assert.strictEqual(SaveCompare.show(true, null), 'yes');
	assert.strictEqual(SaveCompare.show(false, null), 'no');
	assert.strictEqual(SaveCompare.show(null, null), '—');
	assert.strictEqual(SaveCompare.show('Path of the Damned', null), 'Path of the Damned');
});

test('a difference too small to round is still shown as one', () => {
	assert.strictEqual(SaveCompare.show(0, null, 2.9802322e-8), '0');
	assert.strictEqual(SaveCompare.show(2.9802322e-8, null, 0), '2.9802322e-8');
	assert.strictEqual(SaveCompare.show(0.19999999, null, 0.2), '0.19999999');
	assert.strictEqual(SaveCompare.show(0.2, null, 0.19999999), '0.2');
});

test('money, time and skills are written in their own terms', () => {
	assert.strictEqual(SaveCompare.show(291248.0, 'money'), '291,248 cp');
	assert.strictEqual(SaveCompare.show(4789240, 'duration'), '55 d 10 h');
	assert.strictEqual(SaveCompare.show(29700, 'duration'), '8 h 15 min');
	assert.strictEqual(SaveCompare.show(420, 'duration'), '7 min');
	assert.strictEqual(SaveCompare.show(10, 'skill'), 'rank 4');
	assert.strictEqual(SaveCompare.show(12, 'skill'), 'rank 4 (12 points)');
	assert.strictEqual(SaveCompare.show(0, 'skill'), 'rank 0');
	assert.strictEqual(SaveCompare.show(712, 'items'), '712 items');
	assert.strictEqual(SaveCompare.show(1, 'items'), '1 item');
	assert.strictEqual(SaveCompare.show(51, 'count'), '51');
});

test('a number says how far it moved', () => {
	assert.strictEqual(SaveCompare.change({before: 1500, after: 2611}), '+1,111');
	assert.strictEqual(SaveCompare.change({before: 291512, after: 291248, format: 'money'}), '−264 cp');
	assert.strictEqual(SaveCompare.change({before: 3600, after: 33300, format: 'duration'}), '+8 h 15 min');
	assert.strictEqual(SaveCompare.change({before: 6, after: 10, format: 'skill'}), '+1 rank');
	assert.strictEqual(SaveCompare.change({before: 'Normal', after: 'Hard'}), '');
	assert.strictEqual(SaveCompare.change({after: 3}), '');
	assert.strictEqual(SaveCompare.change({before: 0, after: 2.9802322e-8}), '');
});

test('the summary counts what is shown and what is left out', () => {
	const comparison = {
		sections: [
			{id: 'save', groups: [{rows: [{}, {}]}]}
			, {id: 'characters', groups: [{rows: [{}]}, {rows: [{}, {}]}]}
		]
		, leftOut: {positions: 1081, bookkeeping: 238, maps: 2}
		, counts: {differences: 3789}
	};

	assert.strictEqual(SaveCompare.rows(comparison), 5);
	assert.strictEqual(SaveCompare.leftOut(comparison)
		, 'Left out: where 1,081 objects stand, 238 timers and counters the game keeps updating, '
		+ 'and the map explored in 2 areas.');
	assert.strictEqual(SaveCompare.leftOut({leftOut: {positions: 0, bookkeeping: 0, maps: 0}}), '');
	assert.strictEqual(SaveCompare.leftOut({leftOut: {positions: 1, bookkeeping: 1, maps: 0}})
		, 'Left out: where 1 object stands and 1 timer or counter the game keeps updating.');
});

test("a save's name is the list's", () => {
	assert.strictEqual(SaveCompare.name(save('a', '2026-07-20 20:14:01', 'Caed Nua (edited)'))
		, 'Phantom - Caed Nua (Caed Nua (edited))');
	assert.strictEqual(SaveCompare.name(save('a', '2026-07-20 20:14:01')), 'Phantom - Caed Nua');
});

let failed = 0;
tests.forEach(t => {
	try {
		t.body();
		console.log('PASS  ' + t.name);
	} catch (e) {
		failed++;
		console.log('FAIL  ' + t.name + '\n      ' + e.message.split('\n')[0]);
	}
});

console.log('\n' + tests.length + ' tests, ' + failed + ' failed');
process.exit(failed ? 1 : 0);
