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

// node src/test/js/SaveHistoryTest.js
//
// Undo and Redo over everything the user changes in a save.
//
// Two kinds of step share one history. What is typed into the page -- a stat,
// a global, the party's money -- lives in saveData until Save, so the page
// undoes it itself: the history notices each change by comparing the values
// Save writes against the last ones it saw, and puts the old ones back into
// the same {type, value} slots (invariant 12). What an Apply wrote is files,
// which only the server can put back; the page keeps the step's id.

'use strict';

const assert = require('assert');
const SaveHistory = require('../../ui/js/SaveHistory.js');

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
			GlobalVariables: {n_Lafda_State: slot(9)}
			, Stronghold: {Prestige: slot(48), Security: slot(44)}
		}
	}
	, inventory: {stash: {items: [{guid: 'sword'}]}}
});

const MIGHT = 'might input';
const PRESTIGE = 'prestige cell';

// A history over a freshly opened save.
const fresh = () => {
	const save = opened();
	const history = new SaveHistory.History();
	history.reset(save);
	return {save: save, history: history};
};

const labels = steps => steps.map(step => step.label);

const tests = [];
const test = (name, body) => tests.push({name: name, body: body});

// ---- noticing what was typed --------------------------------------------------

test('typing a number is one step, however many keys it took', () => {
	const {save, history} = fresh();
	const might = save.characters[0].stats.BaseMight;

	might.value = '2';
	history.notice(save, {at: 0, element: MIGHT});
	might.value = '25';
	history.notice(save, {at: 300, element: MIGHT});

	assert.strictEqual(history.undo.length, 1);
	assert.deepStrictEqual(labels(history.undo), ['Phantom’s Might, 18 to 25']);
});

test('a pause, or another field, starts a new step', () => {
	const {save, history} = fresh();
	save.characters[0].stats.BaseMight.value = '20';
	history.notice(save, {at: 0, element: MIGHT});
	save.characters[0].stats.BaseMight.value = '21';
	history.notice(save, {at: 9000, element: MIGHT});
	save.characters[0].stats.BaseMight.value = '22';
	history.notice(save, {at: 9100, element: 'the raw table'});

	assert.deepStrictEqual(labels(history.undo), [
		'Phantom’s Might, 18 to 20'
		, 'Phantom’s Might, 20 to 21'
		, 'Phantom’s Might, 21 to 22']);
});

test('typing a value back to what it was leaves no step', () => {
	const {save, history} = fresh();
	const might = save.characters[0].stats.BaseMight;

	might.value = '1';
	history.notice(save, {at: 0, element: MIGHT});
	might.value = '18';
	history.notice(save, {at: 200, element: MIGHT});

	assert.strictEqual(history.undo.length, 0);
});

test('nothing changed is no step', () => {
	const {save, history} = fresh();
	assert.strictEqual(history.notice(save, {at: 0, element: MIGHT}), null);
	assert.strictEqual(history.undo.length, 0);
});

test('only what Save writes is a step: the inventory and the like are the managers\' own', () => {
	const {save, history} = fresh();
	save.inventory.stash.items = [];
	save.achievementsDisabled = true;
	history.notice(save, {at: 0});

	assert.strictEqual(history.undo.length, 0);
});

// ---- undoing and redoing a value ----------------------------------------------

test('undoing puts the old value back into the very slot the page holds', () => {
	const {save, history} = fresh();
	const might = save.characters[0].stats.BaseMight;
	might.value = '25';
	history.notice(save, {at: 0, element: MIGHT});

	const step = history.nextUndo();
	assert.strictEqual(step.kind, 'values');
	SaveHistory.revert(save, step);
	history.settle(step, true, save);

	assert.strictEqual(save.characters[0].stats.BaseMight, might, 'the same wrapper (invariant 12)');
	assert.strictEqual(might.value, 18);
	assert.strictEqual(history.undo.length, 0);
	assert.strictEqual(history.redo.length, 1);
	assert.strictEqual(history.notice(save, {at: 10}), null, 'undoing is not itself a change');
});

test('redoing puts the new value back', () => {
	const {save, history} = fresh();
	save.characters[0].stats.BaseMight.value = '25';
	history.notice(save, {at: 0, element: MIGHT});

	const step = history.nextUndo();
	SaveHistory.revert(save, step);
	history.settle(step, true, save);
	const again = history.nextRedo();
	SaveHistory.reapply(save, again);
	history.settle(again, false, save);

	assert.strictEqual(save.characters[0].stats.BaseMight.value, '25');
	assert.strictEqual(history.undo.length, 1);
	assert.strictEqual(history.redo.length, 0);
});

test('a new change ends what could be redone', () => {
	const {save, history} = fresh();
	save.characters[0].stats.BaseMight.value = '25';
	history.notice(save, {at: 0, element: MIGHT});
	const step = history.nextUndo();
	SaveHistory.revert(save, step);
	history.settle(step, true, save);

	save.globals.InGameGlobal.Stronghold.Prestige.value = '50';
	history.notice(save, {at: 10, element: PRESTIGE});

	assert.strictEqual(history.redo.length, 0);
	assert.strictEqual(history.nextRedo(), null);
});

test('money, camping supplies, a portrait and the heal flag are undone too', () => {
	const {save, history} = fresh();
	const player = save.characters[0];
	save.currency = 5000;
	save.campingSupplies = 4;
	player.portraitPaths.m_textureLargePath.value = 'portraits/eder_lg.png';
	player.portraitPaths.m_textureSmallPath.value = 'portraits/eder_sm.png';
	player.portrait = 'EDER-IMAGE';
	player.health.m_needs_current_values.value = true;
	history.notice(save, {at: 0, label: 'Several things'});

	const step = history.nextUndo();
	SaveHistory.revert(save, step);
	history.settle(step, true, save);

	assert.strictEqual(save.currency, 1000);
	assert.strictEqual(save.campingSupplies, 2);
	assert.strictEqual(player.portraitPaths.m_textureLargePath.value, 'portraits/phantom_lg.png');
	assert.strictEqual(player.portrait, 'PHANTOM-IMAGE', 'the face travels with its paths');
	assert.strictEqual(player.health.m_needs_current_values.value, false);
});

test('a value whose character has gone since is passed over, not thrown on', () => {
	const {save, history} = fresh();
	save.characters[1].stats.BaseMight.value = '16';
	history.notice(save, {at: 0, element: MIGHT});

	save.characters.pop();
	const step = history.nextUndo();
	assert.doesNotThrow(() => SaveHistory.revert(save, step));
});

// ---- Applies -------------------------------------------------------------------

test('an Apply is one step, and what its reply brought is not a step of its own', () => {
	const {save, history} = fresh();
	const reply = opened();
	reply.globals.InGameGlobal.Stronghold.Prestige.value = 52;

	history.applied(7, 'Stronghold: build the Barbican', reply);
	assert.strictEqual(history.notice(reply, {at: 0}), null);
	assert.deepStrictEqual(history.undo.map(step => [step.kind, step.step, step.label])
		, [['applied', 7, 'Stronghold: build the Barbican']]);
});

test('an Apply ends what could be redone', () => {
	const {save, history} = fresh();
	save.currency = 1;
	history.notice(save, {at: 0});
	const step = history.nextUndo();
	SaveHistory.revert(save, step);
	history.settle(step, true, save);

	history.applied(3, 'Inventory: 1 change', save);
	assert.strictEqual(history.redo.length, 0);
});

test('an undo that was still on its way when something was typed cannot be redone after', () => {
	const {save, history} = fresh();
	history.applied(4, 'Inventory: 2 changes', save);

	const step = history.nextUndo();
	const asked = history.ask();
	save.currency = 77;
	history.notice(save, {at: 5});
	history.settle(step, true, save, asked);

	assert.deepStrictEqual(history.undo.map(s => s.kind), ['values']);
	assert.strictEqual(history.redo.length, 0);
});

test('a redo still on its way when something was typed is still undone in its turn', () => {
	const {save, history} = fresh();
	history.applied(4, 'Inventory: 2 changes', save);
	const undone = history.nextUndo();
	history.settle(undone, true, save);

	const step = history.nextRedo();
	const asked = history.ask();
	save.currency = 77;
	history.notice(save, {at: 5});
	history.settle(step, false, save, asked);

	assert.deepStrictEqual(history.undo.map(s => s.kind), ['values', 'applied']);
});

// ---- an Apply that also moved the purse ------------------------------------------
//
// A sale's items leave the save on the Inventory tab's Apply, and its money
// goes into the purse when that Apply succeeds: one step, not two. The purse is
// a value the page holds (Save writes it), so the step carries the amount, and
// undoing it takes that much out again.

test('an Apply that sold something carries the sale\'s money as part of the step', () => {
	const {save, history} = fresh();
	save.currency = 1120;
	history.applied(9, 'Sell 3 items for 120 cp', save, {credit: 120});

	assert.strictEqual(history.notice(save, {at: 0}), null, 'the money is no step of its own');
	assert.strictEqual(history.undo.length, 1);
	assert.strictEqual(history.nextUndo().credit, 120);
});

test('undoing that Apply takes the money out again, and redoing puts it back', () => {
	const {save, history} = fresh();
	save.currency = 1120;
	history.applied(9, 'Sell 3 items for 120 cp', save, {credit: 120});

	const step = history.nextUndo();
	SaveHistory.moveCredit(save, step, true);
	history.settle(step, true, save);
	assert.strictEqual(save.currency, 1000);
	assert.strictEqual(history.notice(save, {at: 1}), null, 'taking it out is not itself a change');

	const again = history.nextRedo();
	SaveHistory.moveCredit(save, again, false);
	history.settle(again, false, save);
	assert.strictEqual(save.currency, 1120);
	assert.strictEqual(history.notice(save, {at: 2}), null);
});

test('the money moves by its amount, so whatever else changed the purse stays changed', () => {
	const {save, history} = fresh();
	save.currency = 1120;
	history.applied(9, 'Sell 3 items for 120 cp', save, {credit: 120});

	// Typed while the undo was on its way to the server.
	const step = history.nextUndo();
	const asked = history.ask();
	save.currency = 1220;
	history.notice(save, {at: 5});

	SaveHistory.moveCredit(save, step, true);
	history.settle(step, true, save, asked);
	assert.strictEqual(save.currency, 1100, 'the hundred typed since is still there');
});

test('an Apply that sold nothing moves no money', () => {
	const {save, history} = fresh();
	history.applied(4, 'Inventory: 2 changes', save);
	const step = history.nextUndo();

	SaveHistory.moveCredit(save, step, true);
	assert.strictEqual(save.currency, 1000);
	assert.strictEqual('credit' in step, false);
});

// ---- saying what a step is -----------------------------------------------------

test('a step says what changed, in the words the editor uses', () => {
	const say = change => {
		const {save, history} = fresh();
		change(save);
		history.notice(save, {at: 0});
		return history.undo[0].label;
	};

	assert.strictEqual(say(s => s.globals.InGameGlobal.Stronghold.Prestige.value = '52')
		, 'Prestige, 48 to 52');
	assert.strictEqual(say(s => s.currency = 2500), 'Party money, 1000 to 2500 cp');
	assert.strictEqual(say(s => s.campingSupplies = 4), 'Camping supplies, 2 to 4');
	assert.strictEqual(say(s => {
		s.characters[0].portraitPaths.m_textureLargePath.value = 'portraits/eder_lg.png';
		s.characters[0].portraitPaths.m_textureSmallPath.value = 'portraits/eder_sm.png';
		s.characters[0].portrait = 'EDER-IMAGE';
	}), 'Phantom’s portrait');
	assert.strictEqual(say(s => s.characters[1].health.m_needs_current_values.value = true)
		, 'Heal Pallegina');
	assert.strictEqual(say(s => {
		s.characters[0].stats.BaseMight.value = '20';
		s.characters[1].stats.BaseMight.value = '20';
		s.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value = '1';
	}), '3 changes to Phantom, Pallegina and the global variables');
});

test('a step uses the sheet\'s words: skills as ranks, identity by name', () => {
	const withSkill = () => {
		const {save, history} = fresh();
		save.characters[0].stats.AthleticsSkill = slot(55);
		save.characters[0].stats.CharacterClass = slot('Paladin', 'CharacterStats$Class');
		history.rebase(save);
		return {save: save, history: history};
	};

	let {save, history} = withSkill();
	save.characters[0].stats.AthleticsSkill.value = 66;
	history.notice(save, {at: 0});
	assert.strictEqual(history.undo[0].label, 'Phantom’s Athletics, rank 10 to 11');

	({save, history} = withSkill());
	save.characters[0].stats.CharacterClass.value = 'Wizard';
	history.notice(save, {at: 0});
	assert.strictEqual(history.undo[0].label, 'Phantom’s class, Paladin to Wizard');
});

test('a long value is cut short in a label', () => {
	const {save, history} = fresh();
	save.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value = 'x'.repeat(200);
	history.notice(save, {at: 0});
	assert.ok(history.undo[0].label.length < 90, history.undo[0].label);
});

test('a label given for a change is kept, and such a step takes no keystrokes after it', () => {
	const {save, history} = fresh();
	save.characters[0].health.m_needs_current_values.value = true;
	save.characters[1].health.m_needs_current_values.value = true;
	history.notice(save, {at: 0, element: MIGHT, label: 'Heal the party'});
	save.characters[0].health.m_needs_current_values.value = false;
	history.notice(save, {at: 10, element: MIGHT});

	assert.deepStrictEqual(labels(history.undo), ['Heal the party', 'Phantom: no heal on load']);
});

test('starting again forgets both lists and takes the save as it is', () => {
	const {save, history} = fresh();
	save.currency = 1;
	history.notice(save, {at: 0});
	history.reset(save);

	assert.strictEqual(history.undo.length + history.redo.length, 0);
	assert.strictEqual(history.notice(save, {at: 1}), null);
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
