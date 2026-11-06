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

// One history of everything changed in the open save, for Undo and Redo.
//
// Two kinds of step share it. A *values* step is something typed into the page:
// a stat, a portrait, a global, the party's money -- exactly the scopes Save
// writes (SaveMerge.writable). Those live in saveData until Save, so the page
// undoes them itself. Rather than every editor reporting its edits, the history
// compares what Save would write against what it last saw, whenever anything
// arms the Save button (which every editor already has to do), so nothing new
// has to remember to call it. An *applied* step is an Apply, which wrote files;
// the page keeps the id its reply carried and the server puts the files back
// (EditHistory.java).
//
// Keystrokes into one field, close together, are one step: the sheet writes on
// every key, and nobody wants Undo to take "25" back to "2".
//
// Undoing writes the old values into the same {type, value} slots the page
// holds (invariant 12), never new objects.
var SaveHistory = (function () {
	var SEPARATOR = '\u0001';
	var TOGETHER_MS = 1500;

	// The raw table hands back strings for numbers it never touched.
	var same = (a, b) => {
		if (a === b) {
			return true;
		}

		if (a === null || a === undefined || b === null || b === undefined) {
			return false;
		}

		if (typeof a === 'object' || typeof b === 'object') {
			return JSON.stringify(a) === JSON.stringify(b);
		}

		return String(a) === String(b);
	};

	var copy = value => value !== null && typeof value === 'object'
		? JSON.parse(JSON.stringify(value)) : value;

	var path = parts => parts.join(SEPARATOR);

	/** Every value Save writes, by path. */
	var values = save => {
		var out = {};
		var take = (parts, bag) => Object.keys(bag || {}).forEach(key => {
			var entry = bag[key];
			if (entry && typeof entry === 'object' && 'value' in entry) {
				out[path(parts.concat([key]))] = copy(entry.value);
			}
		});

		(save.characters || []).forEach(character => {
			take(['c', character.GUID, 'stats'], character.stats);
			take(['c', character.GUID, 'portraitPaths'], character.portraitPaths);
			take(['c', character.GUID, 'health'], character.health);

			// The face travels with its paths; a string, so this costs nothing.
			if (character.portrait !== undefined) {
				out[path(['c', character.GUID, 'portrait'])] = character.portrait;
			}
		});

		Object.keys(save.globals || {}).forEach(object => {
			Object.keys(save.globals[object] || {}).forEach(component => {
				take(['g', object, component], save.globals[object][component]);
			});
		});

		if (save.currency !== undefined && save.currency !== null) {
			out.currency = save.currency;
		}

		if (save.campingSupplies !== undefined && save.campingSupplies !== null) {
			out.campingSupplies = save.campingSupplies;
		}

		return out;
	};

	/**
	 * What differs between two readings. A path in only one of them is not a
	 * change anyone typed: characters arrive and leave with a reply, which
	 * the history takes as it is.
	 */
	var differences = (before, after) => {
		var changes = [];
		Object.keys(after).forEach(key => {
			if (key in before && !same(before[key], after[key])) {
				changes.push({path: key, before: before[key], after: after[key]});
			}
		});

		return changes;
	};

	/** Puts {@code value} back into the save's own slot; false when it is gone. */
	var write = (save, key, value) => {
		if (key === 'currency' || key === 'campingSupplies') {
			save[key] = value;
			return true;
		}

		var parts = key.split(SEPARATOR);
		var bag = null;
		if (parts[0] === 'c') {
			var character = (save.characters || []).filter(c => c.GUID === parts[1])[0];
			if (!character) {
				return false;
			}

			if (parts[2] === 'portrait') {
				character.portrait = value;
				return true;
			}

			bag = character[parts[2]];
			parts = [parts[3]];
		} else if (parts[0] === 'g') {
			bag = ((save.globals || {})[parts[1]] || {})[parts[2]];
			parts = [parts[3]];
		}

		var entry = bag ? bag[parts[0]] : null;
		if (!entry || typeof entry !== 'object' || !('value' in entry)) {
			return false;
		}

		entry.value = copy(value);
		return true;
	};

	var revert = (save, step) => step.changes.forEach(c => write(save, c.path, c.before));
	var reapply = (save, step) => step.changes.forEach(c => write(save, c.path, c.after));

	/**
	 * Moves the purse by what an applied step credited: out again when the
	 * step has been undone, back in when it has been redone. By the amount
	 * rather than to a figure, so whatever else changed the purse since stays
	 * changed. A step that credited nothing moves nothing.
	 */
	var moveCredit = (save, step, undone) => {
		if (!step.credit) {
			return;
		}

		save.currency = (Number(save.currency) || 0) + (undone ? -step.credit : step.credit);
	};

	// ---- saying what a step is ---------------------------------------------

	var nameOf = (save, guid) => {
		var character = (save.characters || []).filter(c => c.GUID === guid)[0];
		return character && character.name ? character.name : 'A character';
	};

	var shown = value => {
		var text = value === null || value === undefined ? '' : String(value);
		return text.length > 24 ? text.slice(0, 23) + '…' : text;
	};

	// The sheet's own words for what people edit most there; anything else
	// keeps the name the Raw table shows it by.
	var NAMES = {
		BaseMight: 'Might', BaseConstitution: 'Constitution', BaseDexterity: 'Dexterity'
		, BasePerception: 'Perception', BaseIntellect: 'Intellect', BaseResolve: 'Resolve'
		, CharacterRace: 'race', CharacterSubrace: 'subrace', CharacterClass: 'class'
		, CharacterCulture: 'culture', CharacterBackground: 'background', Gender: 'gender'
		, Deity: 'deity', PaladinOrder: 'order', Experience: 'experience'
		, RemainingSkillPoints: 'unspent skill points'
	};

	// Skills are stored as points and shown as ranks: rank N costs N(N+1)/2.
	var SKILLS = {
		AthleticsSkill: 'Athletics', StealthSkill: 'Stealth', LoreSkill: 'Lore'
		, MechanicsSkill: 'Mechanics', SurvivalSkill: 'Survival', CraftingSkill: 'Crafting'
	};

	var rankOf = points =>
		Math.floor((Math.sqrt(8 * (parseInt(points, 10) || 0) + 1) - 1) / 2);

	var describeOne = (save, change) => {
		var parts = change.path.split(SEPARATOR);
		var from = shown(change.before);
		var to = shown(change.after);

		if (change.path === 'currency') {
			return 'Party money, ' + from + ' to ' + to + ' cp';
		}

		if (change.path === 'campingSupplies') {
			return 'Camping supplies, ' + from + ' to ' + to;
		}

		if (parts[0] === 'g') {
			return parts[3] + ', ' + from + ' to ' + to;
		}

		var name = nameOf(save, parts[1]);
		if (parts[2] === 'portrait' || parts[2] === 'portraitPaths') {
			return name + '’s portrait';
		}

		if (parts[2] === 'health' && parts[3] === 'm_needs_current_values') {
			return same(change.after, true) ? 'Heal ' + name : name + ': no heal on load';
		}

		if (SKILLS[parts[3]]) {
			return name + '’s ' + SKILLS[parts[3]] + ', rank ' + rankOf(change.before)
				+ ' to ' + rankOf(change.after);
		}

		if (parts[3] === 'OverrideName') {
			return 'Rename ' + (from || name) + ' to ' + (to || 'the game’s own name');
		}

		return name + '’s ' + (NAMES[parts[3]] || parts[3]) + ', ' + from + ' to ' + to;
	};

	var list = items => items.length < 2 ? items.join('')
		: items.slice(0, -1).join(', ') + ' and ' + items[items.length - 1];

	/** A step in words: the one change, or how many and to whom. */
	var describe = (save, changes) => {
		// A new face is its two paths and its picture: one change to a person.
		var portraits = {};
		var counted = changes.filter(change => {
			var parts = change.path.split(SEPARATOR);
			if (parts[0] === 'c' && (parts[2] === 'portrait' || parts[2] === 'portraitPaths')) {
				if (portraits[parts[1]]) {
					return false;
				}

				portraits[parts[1]] = true;
			}

			return true;
		});

		if (counted.length === 1) {
			return describeOne(save, counted[0]);
		}

		var whom = [];
		var add = item => {
			if (whom.indexOf(item) < 0) {
				whom.push(item);
			}
		};

		counted.forEach(change => {
			var parts = change.path.split(SEPARATOR);
			if (parts[0] === 'c') {
				add(nameOf(save, parts[1]));
			}
		});

		counted.forEach(change => {
			if (change.path.split(SEPARATOR)[0] === 'g') {
				add('the global variables');
			} else if (change.path === 'currency') {
				add('party money');
			} else if (change.path === 'campingSupplies') {
				add('camping supplies');
			}
		});

		return counted.length + ' changes to ' + list(whom);
	};

	// ---- the history --------------------------------------------------------

	var samePaths = (a, b) => a.length === b.length
		&& a.every(change => b.some(other => other.path === change.path));

	var History = function () {
		this.undo = [];
		this.redo = [];
		this.last = {};
		// Counts new steps, so a request on its way can tell whether anything
		// happened while it was.
		this.stamp = 0;
	};

	/** A save was opened, or written: nothing before it can be undone. */
	History.prototype.reset = function (save) {
		this.undo = [];
		this.redo = [];
		this.last = values(save || {});
	};

	/** Takes the save as it is now without making a step of the difference. */
	History.prototype.rebase = function (save) {
		this.last = values(save);
	};

	/**
	 * Something may have changed. Makes a step of whatever differs from the
	 * last reading, or adds it to the step it continues: the same fields,
	 * from the same element, soon after. {@code options}: {at, element, label}.
	 *
	 * @return the step changed or made, or null when nothing differed
	 */
	History.prototype.notice = function (save, options) {
		options = options || {};
		var now = values(save);
		var changes = differences(this.last, now);
		this.last = now;

		if (changes.length < 1) {
			return null;
		}

		var top = this.undo[this.undo.length - 1];
		if (!options.label && top && top.kind === 'values' && !top.named
			&& top.element && top.element === options.element
			&& (options.at || 0) - top.at <= TOGETHER_MS && samePaths(top.changes, changes)) {

			changes.forEach(change => {
				top.changes.filter(c => c.path === change.path)
					.forEach(c => c.after = change.after);
			});

			top.changes = top.changes.filter(c => !same(c.before, c.after));
			top.at = options.at || 0;

			if (top.changes.length < 1) {
				this.undo.pop();
				return null;
			}

			top.label = describe(save, top.changes);
			return top;
		}

		var step = {
			kind: 'values'
			, changes: changes
			, label: options.label || describe(save, changes)
			, named: !!options.label
			, at: options.at || 0
			, element: options.element || null
		};

		this.push(step);
		return step;
	};

	/**
	 * An Apply's reply has arrived and been adopted: one step, by its id.
	 * {@code extra.credit} is what the step put into the purse as it
	 * succeeded -- a sale's money. The purse is the page's to hold (Save
	 * writes it), so the step carries the amount for {@link moveCredit}.
	 */
	History.prototype.applied = function (step, label, save, extra) {
		this.last = values(save);
		var entry = {kind: 'applied', step: step, label: label};
		if (extra && extra.credit) {
			entry.credit = extra.credit;
		}

		this.push(entry);
	};

	History.prototype.push = function (step) {
		this.undo.push(step);
		this.redo = [];
		this.stamp++;
	};

	History.prototype.nextUndo = function () {
		return this.undo.length > 0 ? this.undo[this.undo.length - 1] : null;
	};

	History.prototype.nextRedo = function () {
		return this.redo.length > 0 ? this.redo[this.redo.length - 1] : null;
	};

	/** What {@link #settle} compares, taken when a request is sent. */
	History.prototype.ask = function () {
		return this.stamp;
	};

	/**
	 * A step has been undone ({@code undone}) or redone, and {@code save} is
	 * the result. An undo that was on its way to the server while something
	 * new was done ({@code asked} is the stamp from when it was sent) cannot
	 * be redone after it: the new step comes after it.
	 */
	History.prototype.settle = function (step, undone, save, asked) {
		var from = undone ? this.undo : this.redo;
		var index = from.indexOf(step);
		if (index >= 0) {
			from.splice(index, 1);
		}

		var since = asked !== undefined && asked !== this.stamp;
		if (undone) {
			if (!since) {
				this.redo.push(step);
			}
		} else {
			this.undo.push(step);
		}

		this.last = values(save);
	};

	return {
		History: History
		, values: values
		, differences: differences
		, write: write
		, revert: revert
		, reapply: reapply
		, moveCredit: moveCredit
		, describe: describe
	};
})();

// Loaded by the editor as a plain script, and by node for its tests.
if (typeof module !== 'undefined' && module.exports) {
	module.exports = SaveHistory;
}
