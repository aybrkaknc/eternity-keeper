/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
 *
 *  Eternity Keeper is free software: you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  Eternity Keeper is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

// node src/test/js/SaveMergeTest.js
//
// What an Apply in one panel does to unsaved edits made in another.
//
// Seven editors hand the working save to the server and get a freshly reopened
// copy back, and each merged that reply into the UI's copy its own way. Measured
// on the running editor, three of them lost the user's work: moving a companion
// out of the party put the player's unsaved Might back from 25 to 18, a grimoire
// Apply put an unsaved global back from 4242 to 9, and an inventory Apply threw
// away an unsaved portrait. Invariant 12 says none of that may happen.
//
// The merge that gets it right is three-way. `base` is what the server last
// sent; `mine` is the UI's copy with the user's unsaved edits in it; `theirs` is
// the reply to this Apply. A value that differs between mine and base is the
// user's, and is kept; everything else comes from theirs, which is how a
// stronghold's new Prestige or a resurrection's cleared death flags arrive.

'use strict';

const assert = require('assert');
const SaveMerge = require('../../ui/js/SaveMerge.js');

const slot = (value, type) => ({type: type || 'java.lang.Integer', value: value});
const clone = value => JSON.parse(JSON.stringify(value));

// A small save in the opener's own shape.
const opened = () => ({
	currency: 1000
	, campingSupplies: 2
	, achievementsDisabled: false
	, characters: [
		{
			GUID: 'player', name: 'Phantom', isMainCharacter: true
			, stats: {BaseMight: slot(18), BaseResolve: slot(15)}
			, portraitPaths: {
				m_textureLargePath: slot('portraits/phantom_lg.png', 'java.lang.String')
				, m_textureSmallPath: slot('portraits/phantom_sm.png', 'java.lang.String')
			}
			, portrait: 'PHANTOM-IMAGE'
			, health: {m_needs_current_values: slot(false, 'java.lang.Boolean')}
		}
		, {
			GUID: 'pallegina', name: 'Pallegina', inParty: true
			, stats: {BaseMight: slot(14)}
			, portraitPaths: {}
			, health: {m_needs_current_values: slot(false, 'java.lang.Boolean')}
		}
	]
	, globals: {
		InGameGlobal: {
			GlobalVariables: {n_Lafda_State: slot(9), b_Eastern_Barbican: slot(0)}
			, Stronghold: {Prestige: slot(48), Security: slot(44)}
		}
	}
	, inventory: {stash: {items: [{guid: 'sword'}]}}
});

const tests = [];
const test = (name, body) => tests.push({name: name, body: body});

// ---- the three measured failures --------------------------------------------

test('a party change keeps an unsaved attribute edit', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.characters[0].stats.BaseMight.value = 25;

	const theirs = opened();
	theirs.characters[1].inParty = false;

	const merged = SaveMerge.merge(base, mine, theirs);

	assert.strictEqual(merged.characters[0].stats.BaseMight.value, 25);
	assert.strictEqual(merged.characters[1].inParty, false, 'the party change itself arrives');
});

test('a grimoire change keeps an unsaved global edit', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value = 4242;

	const merged = SaveMerge.merge(base, mine, opened());

	assert.strictEqual(merged.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value, 4242);
});

test('an inventory change keeps an unsaved portrait, image and all', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.characters[0].portraitPaths.m_textureLargePath.value = 'portraits/eder_lg.png';
	mine.characters[0].portraitPaths.m_textureSmallPath.value = 'portraits/eder_sm.png';
	mine.characters[0].portrait = 'EDER-IMAGE';

	const theirs = opened();
	theirs.inventory.stash.items = [];

	const merged = SaveMerge.merge(base, mine, theirs);
	const player = merged.characters[0];

	assert.strictEqual(player.portraitPaths.m_textureLargePath.value, 'portraits/eder_lg.png');
	assert.strictEqual(player.portraitPaths.m_textureSmallPath.value, 'portraits/eder_sm.png');
	assert.strictEqual(player.portrait, 'EDER-IMAGE', 'the sheet must show the picked face');
	assert.deepStrictEqual(merged.inventory.stash.items, [], 'the inventory change arrives');
});

// ---- what the server changed arrives -----------------------------------------

test('a value the user did not touch takes the server\'s new one', () => {
	const base = SaveMerge.snapshot(opened());
	const theirs = opened();
	theirs.globals.InGameGlobal.Stronghold.Prestige.value = 18;
	theirs.globals.InGameGlobal.GlobalVariables.b_Eastern_Barbican.value = 1;

	const merged = SaveMerge.merge(base, opened(), theirs);

	assert.strictEqual(merged.globals.InGameGlobal.Stronghold.Prestige.value, 18);
	assert.strictEqual(merged.globals.InGameGlobal.GlobalVariables.b_Eastern_Barbican.value, 1);
});

test('the server\'s change and the user\'s edit to different globals both survive', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value = 4242;

	const theirs = opened();
	theirs.globals.InGameGlobal.Stronghold.Prestige.value = 18;

	const merged = SaveMerge.merge(base, mine, theirs);

	assert.strictEqual(merged.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value, 4242);
	assert.strictEqual(merged.globals.InGameGlobal.Stronghold.Prestige.value, 18);
});

test('a character the server added appears, and one it removed is gone', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.characters.push({GUID: 'dead:eder', name: 'Eder', isDead: true, stats: {}});

	const theirs = opened();
	theirs.characters.push({GUID: 'eder', name: 'Eder', stats: {BaseMight: slot(16)}});

	const merged = SaveMerge.merge(base, mine, theirs);
	const guids = merged.characters.map(c => c.GUID);

	assert.deepStrictEqual(guids, ['player', 'pallegina', 'eder']);
	assert.strictEqual(merged.characters[2].stats.BaseMight.value, 16);
});

test('an unsaved currency edit is kept and a server one arrives', () => {
	const base = SaveMerge.snapshot(opened());

	const edited = opened();
	edited.currency = 5000;
	assert.strictEqual(SaveMerge.merge(base, edited, opened()).currency, 5000);

	const theirs = opened();
	theirs.currency = 1270;
	assert.strictEqual(SaveMerge.merge(base, opened(), theirs).currency, 1270);
});

// The party dialog heals by setting the game's own flag for a full refill on
// load, and refills camping supplies: both wait for Save like the currency, so
// an Apply elsewhere must not undo them.
test('an unsaved heal survives an Apply elsewhere', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.characters[1].health.m_needs_current_values.value = true;

	const merged = SaveMerge.merge(base, mine, opened());
	assert.strictEqual(merged.characters[1].health.m_needs_current_values.value, true);
	assert.strictEqual(merged.characters[0].health.m_needs_current_values.value, false);
});

test('unsaved camping supplies are kept and a server change arrives', () => {
	const base = SaveMerge.snapshot(opened());

	const edited = opened();
	edited.campingSupplies = 4;
	assert.strictEqual(SaveMerge.merge(base, edited, opened()).campingSupplies, 4);

	const theirs = opened();
	theirs.campingSupplies = 1;
	assert.strictEqual(SaveMerge.merge(base, opened(), theirs).campingSupplies, 1);
});

test('when both changed the same value, what the user typed wins', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.globals.InGameGlobal.Stronghold.Prestige.value = 99;

	const theirs = opened();
	theirs.globals.InGameGlobal.Stronghold.Prestige.value = 18;

	assert.strictEqual(
		SaveMerge.merge(base, mine, theirs).globals.InGameGlobal.Stronghold.Prestige.value, 99);
});

// ---- the details that make it safe -------------------------------------------

test('the raw table\'s strings for untouched numbers are not edits', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.characters[0].stats.BaseResolve.value = '15';

	const theirs = opened();
	theirs.characters[0].stats.BaseResolve.value = 16;

	assert.strictEqual(SaveMerge.merge(base, mine, theirs).characters[0].stats.BaseResolve.value, 16);
});

test('the snapshot holds only values, not the inventory or the art', () => {
	const snapshot = SaveMerge.snapshot(opened());

	assert.strictEqual(snapshot.inventory, undefined);
	assert.strictEqual(snapshot.characters.player.portrait, 'PHANTOM-IMAGE',
		'the portrait image is kept so a picked face can be told apart');
	assert.strictEqual(snapshot.characters.player.stats.BaseMight, 18);
	assert.strictEqual(snapshot.globals.InGameGlobal.Stronghold.Prestige, 48);
	assert.strictEqual(snapshot.currency, 1000);
});

test('the snapshot is a copy, so later edits do not move it', () => {
	const save = opened();
	const snapshot = SaveMerge.snapshot(save);
	save.characters[0].stats.BaseMight.value = 30;

	assert.strictEqual(snapshot.characters.player.stats.BaseMight, 18);
});

test('with nothing to compare against, the server\'s copy is taken as it is', () => {
	const mine = opened();
	mine.characters[0].stats.BaseMight.value = 25;

	const merged = SaveMerge.merge(null, mine, opened());
	assert.strictEqual(merged.characters[0].stats.BaseMight.value, 18);
});

test('merging does not modify the UI\'s own copy', () => {
	const base = SaveMerge.snapshot(opened());
	const mine = opened();
	mine.characters[0].stats.BaseMight.value = 25;
	const untouched = clone(mine);

	SaveMerge.merge(base, mine, opened());
	assert.deepStrictEqual(mine, untouched);
});

// ---- what Save sends ---------------------------------------------------------
//
// Save writes exactly the scopes a merge protects, so the same module says what
// goes over the bridge. It used to be a deep copy of the whole save -- inventory
// icons and all, megabytes a reply once proved the bridge will not carry -- and
// the copy was not a copy for the characters: every stat in the UI's own save
// was turned into a string on the way out.

test('Save sends only what it writes', () => {
	const request = SaveMerge.writable(opened());

	assert.deepStrictEqual(Object.keys(request).sort(),
		['campingSupplies', 'characters', 'currency', 'globals']);
	assert.deepStrictEqual(Object.keys(request.characters[0]).sort(),
		['GUID', 'health', 'portraitPaths', 'stats']);
	assert.strictEqual(request.characters[0].GUID, 'player');
	assert.strictEqual(request.currency, 1000);
	assert.strictEqual(request.campingSupplies, 2);
});

test('a save that records no camping supplies sends none', () => {
	const save = opened();
	delete save.campingSupplies;

	assert.ok(!('campingSupplies' in SaveMerge.writable(save)));
});

test('every value Save sends is a string, since the server reads them as strings', () => {
	const request = SaveMerge.writable(opened());

	assert.strictEqual(request.characters[0].stats.BaseMight.value, '18');
	assert.strictEqual(request.characters[0].stats.BaseMight.type, 'java.lang.Integer');
	assert.strictEqual(request.characters[0].portraitPaths.m_textureSmallPath.value,
		'portraits/phantom_sm.png');
	assert.strictEqual(request.globals.InGameGlobal.Stronghold.Prestige.value, '48');
	assert.strictEqual(request.characters[1].health.m_needs_current_values.value, 'false');
});

test('sending a Save does not change the UI\'s own copy', () => {
	const save = opened();
	const untouched = clone(save);

	SaveMerge.writable(save);
	assert.deepStrictEqual(save, untouched);
	assert.strictEqual(save.characters[0].stats.BaseMight.value, 18);
});

test('a character with nothing to write sends no stats, and no portrait or health it lacks', () => {
	const save = opened();
	save.characters.push({GUID: 'dead:eder', name: 'Eder', isDead: true});

	const request = SaveMerge.writable(save);
	// The server skips the portrait component when the key is absent, and
	// would log a missing component for every character without one otherwise.
	assert.deepStrictEqual(request.characters[2], {GUID: 'dead:eder', stats: {}});
});

// ---- run ---------------------------------------------------------------------

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
