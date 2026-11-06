// node src/test/js/SaveListModelTest.js
// Save panel ordering uses the timestamp value, not its locale-formatted label.
'use strict';

const assert = require('assert');
const SaveListModel = require('../../ui/js/SaveListModel.js');
const saves = [
	{playerName: 'Watcher', sceneTitle: 'Late', date: '01/02/2026', timestampMillis: 1767312000000}
	, {playerName: 'Watcher', sceneTitle: 'Early', date: '12/31/2025', timestampMillis: 1767139200000}
	, {playerName: 'Watcher', sceneTitle: 'Middle', date: '01/01/2026', timestampMillis: 1767225600000}
];
const allMatch = () => true;

assert.deepStrictEqual(
	SaveListModel.filteredAndSorted(saves, '', 'date-asc', allMatch).map(entry => entry.info.sceneTitle)
	, ['Early', 'Middle', 'Late']);
assert.deepStrictEqual(
	SaveListModel.filteredAndSorted(saves, '', 'date-desc', allMatch).map(entry => entry.info.sceneTitle)
	, ['Late', 'Middle', 'Early']);

console.log('SaveListModelTest passed');
