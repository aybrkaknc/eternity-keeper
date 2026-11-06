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

// node src/test/js/SaveFindTest.js
//
// The search box over everything an open save holds: its characters, what
// they carry and know, and the global variables. What it finds, in what
// order, and what each result opens.

'use strict';

const assert = require('assert');
const SaveFind = require('../../ui/js/SaveFind.js');

const slot = (value, type) => ({type: type || 'java.lang.Integer', value: value});
const item = (guid, name, key, stackSize) =>
	({guid: guid, displayName: name, key: key || guid, stackSize: stackSize || 1});

// A small save in the opener's own shape.
const opened = () => ({
	characters: [
		{GUID: 'player', name: 'Phantom', isMainCharacter: true, inParty: true, level: 15
			, className: 'Paladin', stats: {Experience: slot(1)}}
		, {GUID: 'eder', name: 'Edér', isCompanion: true, inParty: true, level: 14
			, className: 'Fighter', stats: {Experience: slot(1)}}
		, {GUID: 'aloth', name: 'Aloth', isCompanion: true, inParty: false, level: 15
			, className: 'Wizard', stats: {Experience: slot(1)}}
		, {GUID: 'dead:durance', name: 'Durance', isCompanion: true, isDead: true, inParty: false
			, stats: {}}
	]
	, inventory: {
		characters: [
			{guid: 'player', isPlayer: true
				, pack: {items: [item('p1', 'Potion of Major Endurance', 'potion_major', 3)]}
				, quickbar: {items: []}
				, equipment: {
					slots: [{slot: 'Head', item: null}, {slot: 'Neck', item: item('n1', 'Ring of Overseeing')}]
					, weaponSets: [{index: 0, primary: null, secondary: null}
						, {index: 1, primary: item('w1', 'Bittercut'), secondary: null}]}}
			, {guid: 'eder', packComponent: 'Inventory'
				, pack: {items: [item('e1', "Gaun's Ring")]}
				, quickbar: {items: [item('q1', 'Bearing Oil')]}
				, equipment: {slots: [], weaponSets: []}}
		]
		, stash: {items: [item('s1', 'Słońce Amulet'), item('s2', 'Lockpick', 'lockpick', 155)]}
	}
	, abilities: {
		characters: [
			{guid: 'aloth', abilities: [{prefab: 'Fireball', name: 'Fireball', kind: 'spell'}]
				, talents: []}
			, {guid: 'eder', abilities: [], talents: [{prefab: 'Weapon_Focus_Soldier'
				, name: 'Weapon Focus: Soldier'}]}
		]
	}
	, globals: {
		Global: {GameState: {Difficulty: slot('Normal', 'uk.me.mantas.eternity.game.GameDifficulty')}}
		, InGameGlobal: {GlobalVariables: {
			b_Eder_Cipher: slot(0), n_Lafda_State: slot(9), nDuranceQuestState: slot(3), '': slot(1)}}
	}
});

const find = (query, items) => SaveFind.search(SaveFind.index(opened(), items), query);
const group = (result, kind) => result.groups.filter(g => g.kind === kind)[0];
const labels = (result, kind) => (group(result, kind) || {rows: []}).rows.map(r => r.entry.label);

const tests = [];
const test = (name, body) => tests.push({name: name, body: body});

// ---- folding -----------------------------------------------------------------

test('a name is found whatever its accents', () => {
	const result = find('eder');
	assert.deepStrictEqual(labels(result, 'character'), ['Edér']);
	assert.deepStrictEqual(group(result, 'character').rows[0].marks, [[0, 4]]);
});

test('letters Unicode does not take apart fold too', () => {
	// ł has no decomposition; ń does. A Polish name has to be findable as typed
	// on any keyboard.
	assert.deepStrictEqual(labels(find('slonce'), 'item'), ['Słońce Amulet']);
	assert.deepStrictEqual(labels(find('SŁOŃCE'), 'item'), ['Słońce Amulet']);
});

test('the marks fall on the original letters', () => {
	const row = group(find('once'), 'item').rows[0];
	assert.strictEqual(row.entry.label.substring(row.marks[0][0], row.marks[0][1]), 'ońce');
});

// ---- ranking -----------------------------------------------------------------

test('the start of a name beats the start of a word beats anywhere', () => {
	assert.deepStrictEqual(labels(find('ring'), 'item'),
		['Ring of Overseeing', "Gaun's Ring", 'Bearing Oil']);
});

test('a word boundary is a space, an underscore or a capital', () => {
	// b_Eder_Cipher starts a word after the underscore; nDuranceQuestState at
	// the capital, which sorts it above a plain substring would.
	assert.strictEqual(group(find('cipher'), 'global').rows[0].score, 2);
	assert.strictEqual(group(find('quest'), 'global').rows[0].score, 2);
});

test('several words find a name holding all of them, in any order', () => {
	assert.deepStrictEqual(labels(find('major potion'), 'item'), ['Potion of Major Endurance']);
	assert.deepStrictEqual(labels(find('major potato'), 'item'), []);
});

test('a group shows its first few and says how many matched', () => {
	const save = opened();
	for (let i = 0; i < 20; i++) {
		save.globals.InGameGlobal.GlobalVariables['b_test_' + i] = slot(i);
	}

	const result = SaveFind.search(SaveFind.index(save), 'b_test');
	assert.strictEqual(group(result, 'global').rows.length, 8);
	assert.strictEqual(group(result, 'global').total, 20);
	assert.strictEqual(result.total, 20);
});

test('groups come in one order: characters, items, abilities, globals', () => {
	const save = opened();
	save.characters[1].name = 'Bear';
	save.abilities.characters[0].abilities.push({prefab: 'Bear_Form', name: 'Bear Form'});
	save.globals.InGameGlobal.GlobalVariables.b_bear_dead = slot(0);

	const kinds = SaveFind.search(SaveFind.index(save), 'bear').groups.map(g => g.kind);
	assert.deepStrictEqual(kinds, ['character', 'item', 'ability', 'global']);
});

test('nothing to look for finds nothing', () => {
	assert.strictEqual(find('').total, 0);
	assert.strictEqual(find('   ').total, 0);
	assert.strictEqual(find('zzzz').groups.length, 0);
});

// ---- what each entry says ------------------------------------------------------

test('every place an item can be is named', () => {
	const details = {};
	SaveFind.index(opened()).filter(e => e.kind === 'item')
		.forEach(e => details[e.label] = e.detail);

	assert.deepStrictEqual(details, {
		'Potion of Major Endurance': 'Phantom · pack · ×3'
		, 'Ring of Overseeing': 'Phantom · neck'
		, 'Bittercut': 'Phantom · weapon set II'
		, "Gaun's Ring": 'Edér · pack'
		, 'Bearing Oil': 'Edér · quick slot'
		, 'Słońce Amulet': 'Stash'
		, 'Lockpick': 'Stash · ×155'
	});
});

test('a character says where they are', () => {
	const details = {};
	SaveFind.index(opened()).filter(e => e.kind === 'character')
		.forEach(e => details[e.label] = e.detail);

	assert.deepStrictEqual(details, {
		Phantom: 'Main character · level 15 Paladin'
		, 'Edér': 'In the party · level 14 Fighter'
		, Aloth: 'Not in the party · level 15 Wizard'
		, Durance: 'Dead, can be resurrected'
	});
});

test('abilities and talents name who has them', () => {
	assert.strictEqual(group(find('fireball'), 'ability').rows[0].entry.detail, 'Aloth · spell');
	assert.strictEqual(group(find('soldier'), 'ability').rows[0].entry.detail, 'Edér · talent');
});

test('a global says its value, and one with no name is not offered', () => {
	const globals = SaveFind.index(opened()).filter(e => e.kind === 'global');
	assert.deepStrictEqual(globals.map(e => e.label).sort(),
		['Difficulty', 'b_Eder_Cipher', 'nDuranceQuestState', 'n_Lafda_State']);
	assert.strictEqual(globals.filter(e => e.label === 'n_Lafda_State')[0].detail,
		'GlobalVariables = 9');
});

// ---- where a result leads ------------------------------------------------------

test('each result carries what it opens', () => {
	const target = query => {
		const result = find(query);
		return result.groups[0].rows[0].entry.target;
	};

	assert.deepStrictEqual(target('aloth'), {character: 'aloth'});
	assert.deepStrictEqual(target("gaun's"), {character: 'eder', item: 'e1'});
	// The stash belongs to the party; the player is who the Inventory tab
	// shows it beside.
	assert.deepStrictEqual(target('lockpick'), {character: 'player', item: 's2'});
	assert.deepStrictEqual(target('fireball'), {character: 'aloth', prefab: 'Fireball'});
	assert.deepStrictEqual(target('lafda'), {global: 'n_Lafda_State'});
});

test('the Inventory tab\'s working copy is searched when it is given', () => {
	// Moved but not applied: the ring is in the stash now, and that is where
	// it has to be found.
	const moved = [{item: item('e1', "Gaun's Ring"), character: 'player', place: {component: 'stash'}}];
	const row = group(find('gaun', moved), 'item').rows[0];
	assert.strictEqual(row.entry.detail, 'Stash');
	assert.deepStrictEqual(labels(find('bittercut', moved), 'item'), []);
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
