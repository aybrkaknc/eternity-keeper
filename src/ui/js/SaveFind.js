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

// Searching everything an open save holds -- its characters, what they carry,
// what they know, and the global variables -- for the box at the top of the
// character list (Finder.js). Kept apart from the page so node can test it.
//
// Matching ignores case and accents, so "eder" finds Edér and "slonce" finds
// Słońce, and ranks a name that starts with what was typed above one with a
// word that does, above one that merely contains it.
var SaveFind = (function () {
	// The groups, in the order they are shown, and how many of each are.
	var KINDS = [
		{kind: 'character', title: 'Characters', limit: 6}
		, {kind: 'item', title: 'Items', limit: 8}
		, {kind: 'ability', title: 'Abilities and talents', limit: 6}
		, {kind: 'global', title: 'Global variables', limit: 8}
	];

	// The letters Unicode does not take apart: NFD leaves them whole, so the
	// accent-stripping below would keep them.
	var WHOLE = {
		'ł': 'l', 'Ł': 'l', 'ø': 'o', 'Ø': 'o', 'đ': 'd', 'Đ': 'd'
		, 'æ': 'ae', 'Æ': 'ae', 'œ': 'oe', 'Œ': 'oe', 'ß': 'ss', 'ı': 'i'
	};

	var ACCENTS = /[̀-ͯ]/g;
	var ASCII = /^[\x00-\x7f]*$/;

	/**
	 * Text as it is matched: lower case, accents dropped. For anything that
	 * is not plain ASCII it also says, for each folded character, which
	 * character of the original it came from -- "é" folds to one letter, "æ"
	 * to two -- so a match can be marked in the name as it is shown.
	 */
	var fold = text => {
		text = String(text === undefined || text === null ? '' : text);

		// Most of a save is plain ASCII, the globals above all.
		if (ASCII.test(text)) {
			return {text: text.toLowerCase(), map: null};
		}

		var out = '';
		var map = [];
		for (var i = 0; i < text.length; i++) {
			var ch = text.charAt(i);
			var folded = WHOLE[ch] || ch.normalize('NFD').replace(ACCENTS, '').toLowerCase();
			for (var k = 0; k < folded.length; k++) {
				out += folded.charAt(k);
				map.push(i);
			}
		}

		return {text: out, map: map};
	};

	var ROMAN = ['I', 'II', 'III', 'IV'];

	// What a worn slot is called in a result, in the hand the game means.
	var SLOT_WORDS = {
		Head: 'head', Neck: 'neck', Chest: 'armour', Hands: 'hands'
		, RightRing: 'right-hand ring', LeftRing: 'left-hand ring', Cape: 'cape'
		, Feet: 'feet', Waist: 'waist', Grimoire: 'grimoire', Pet: 'pet'
	};

	/**
	 * Where every item in the opener's inventory payload is, in the shape the
	 * Inventory tab's working copy gives too (InventoryEditor.everything):
	 * {item, character, place: {component, slot | set}}. The stash goes with
	 * the player, beside whom the Inventory tab shows it.
	 */
	var itemsOf = save => {
		var inventory = (save && save.inventory) || {};
		var found = [];
		var player = (inventory.characters || []).filter(c => c.isPlayer)[0];

		(inventory.characters || []).forEach(character => {
			((character.pack || {}).items || []).forEach(item =>
				found.push({item: item, character: character.guid, place: {component: 'pack'}}));

			((character.quickbar || {}).items || []).forEach(item =>
				found.push({item: item, character: character.guid, place: {component: 'quick'}}));

			var equipment = character.equipment || {};
			(equipment.slots || []).forEach(slot => {
				if (slot.item) {
					found.push({item: slot.item, character: character.guid
						, place: {component: 'worn', slot: slot.slot}});
				}
			});

			(equipment.weaponSets || []).forEach(set => {
				['primary', 'secondary'].forEach(hand => {
					if (set[hand]) {
						found.push({item: set[hand], character: character.guid
							, place: {component: 'weapon', set: set.index + 1}});
					}
				});
			});
		});

		if (inventory.stash && player) {
			(inventory.stash.items || []).forEach(item =>
				found.push({item: item, character: player.guid, place: {component: 'stash'}}));
		}

		return found;
	};

	var placeText = (place, who) => {
		switch (place.component) {
			case 'stash': return 'Stash';
			case 'quick': return who + ' · quick slot';
			case 'worn': return who + ' · ' + (SLOT_WORDS[place.slot] || place.slot);
			case 'weapon': return who + ' · weapon set ' + (ROMAN[place.set - 1] || place.set);
			default: return who + ' · pack';
		}
	};

	var capital = text => text.charAt(0).toUpperCase() + text.slice(1);

	var entry = (kind, label, detail, target, extra) => {
		var made = {kind: kind, label: label, detail: detail, target: target, folded: fold(label)};
		Object.keys(extra || {}).forEach(key => made[key] = extra[key]);
		return made;
	};

	/**
	 * Everything there is to find. `items` replaces what the inventory
	 * payload says with the Inventory tab's working copy, where an item moved
	 * but not yet applied already is.
	 */
	var index = (save, items) => {
		save = save || {};
		var entries = [];
		var names = {};
		(save.characters || []).forEach(c => names[c.GUID] = c.name);
		var nameOf = guid => names[guid] || 'Someone';

		(save.characters || []).forEach(c => {
			var where = c.isDead
				? 'dead, can be resurrected'
				: c.isMainCharacter ? 'main character'
				: c.inParty ? 'in the party' : 'not in the party';

			var what = !c.isDead && c.level
				? 'level ' + c.level + (c.className ? ' ' + c.className : '')
				: '';

			entries.push(entry('character', c.name || c.GUID
				, capital([where, what].filter(part => part).join(' · '))
				, {character: c.GUID}));
		});

		(items || itemsOf(save)).forEach(found => {
			var item = found.item || {};
			var detail = placeText(found.place || {}, nameOf(found.character))
				+ (item.stackSize > 1 ? ' · ×' + item.stackSize : '');

			entries.push(entry('item', item.displayName || item.baseItem || item.key || '?', detail
				, {character: found.character, item: item.guid}, {icon: item.key}));
		});

		((save.abilities || {}).characters || []).forEach(c => {
			(c.abilities || []).forEach(ability => entries.push(entry('ability'
				, ability.name || ability.prefab
				, nameOf(c.guid) + ' · ' + (ability.kind || 'ability')
				, {character: c.guid, prefab: ability.prefab})));

			(c.talents || []).forEach(talent => entries.push(entry('ability'
				, talent.name || talent.prefab
				, nameOf(c.guid) + ' · talent'
				, {character: c.guid, prefab: talent.prefab})));
		});

		var globals = save.globals || {};
		Object.keys(globals).forEach(object => {
			Object.keys(globals[object] || {}).forEach(component => {
				var bag = globals[object][component] || {};
				Object.keys(bag).forEach(name => {
					// The globals table calls it "(unnamed)"; there is no way
					// to type it.
					if (!name) {
						return;
					}

					var value = bag[name] && typeof bag[name] === 'object' && 'value' in bag[name]
						? String(bag[name].value) : '';

					entries.push(entry('global', name
						, component + ' = ' + (value.length > 40 ? value.slice(0, 39) + '…' : value)
						, {global: name}));
				});
			});
		});

		return entries;
	};

	var LETTER = /[a-z0-9]/;
	var UPPER = /[A-Z]/;

	// Whether folded position `at` of an entry begins a word: the name's
	// start, after anything not a letter or a digit (a space, an underscore,
	// an apostrophe), or at a capital after a small letter, which is how the
	// globals spell their words (nDuranceQuestState).
	var startsWord = (entry, at) => {
		var text = entry.folded.text;
		if (at === 0 || !LETTER.test(text.charAt(at - 1))) {
			return true;
		}

		var map = entry.folded.map;
		var here = map ? map[at] : at;
		var before = map ? map[at - 1] : at - 1;
		return here !== before
			&& UPPER.test(entry.label.charAt(here))
			&& !UPPER.test(entry.label.charAt(before));
	};

	var firstWordStart = (entry, needle) => {
		var text = entry.folded.text;
		for (var at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
			if (startsWord(entry, at)) {
				return at;
			}
		}

		return -1;
	};

	// 0 the whole name, 1 its start, 2 the start of a word in it, 3 anywhere
	// in it; 4 every word of a several-word query somewhere in it.
	var score = (entry, phrase, words) => {
		var text = entry.folded.text;
		var at = text.indexOf(phrase);

		if (at >= 0) {
			if (text === phrase) {
				return {score: 0, marks: [[0, phrase.length]]};
			}

			if (at === 0) {
				return {score: 1, marks: [[0, phrase.length]]};
			}

			var word = firstWordStart(entry, phrase);
			return word >= 0
				? {score: 2, marks: [[word, word + phrase.length]]}
				: {score: 3, marks: [[at, at + phrase.length]]};
		}

		if (words.length < 2) {
			return null;
		}

		var marks = [];
		for (var i = 0; i < words.length; i++) {
			var start = firstWordStart(entry, words[i]);
			if (start < 0) {
				start = text.indexOf(words[i]);
			}

			if (start < 0) {
				return null;
			}

			marks.push([start, start + words[i].length]);
		}

		return {score: 4, marks: marks.sort((a, b) => a[0] - b[0])};
	};

	// A mark on the folded text, moved onto the name as it is shown.
	var unfold = (entry, mark) => {
		var map = entry.folded.map;
		return map ? [map[mark[0]], map[mark[1] - 1] + 1] : mark;
	};

	/**
	 * What matches `query`, grouped by kind in KINDS order: {groups:[{kind,
	 * title, total, rows:[{entry, score, marks}]}], total}. Each group keeps
	 * its first few rows; `total` says how many matched in all.
	 */
	var search = (entries, query) => {
		var phrase = fold(query).text.replace(/\s+/g, ' ').trim();
		if (!phrase) {
			return {groups: [], total: 0};
		}

		var words = phrase.split(' ');
		var hits = {};

		(entries || []).forEach((candidate, order) => {
			var found = score(candidate, phrase, words);
			if (found) {
				(hits[candidate.kind] = hits[candidate.kind] || []).push({
					entry: candidate
					, order: order
					, score: found.score
					, marks: found.marks.map(mark => unfold(candidate, mark))
				});
			}
		});

		var total = 0;
		var groups = KINDS.filter(kind => hits[kind.kind]).map(kind => {
			var rows = hits[kind.kind].sort((a, b) =>
				a.score - b.score
				|| a.entry.label.length - b.entry.label.length
				|| a.entry.label.localeCompare(b.entry.label)
				|| a.order - b.order);

			total += rows.length;
			return {kind: kind.kind, title: kind.title, total: rows.length
				, rows: rows.slice(0, kind.limit)};
		});

		return {groups: groups, total: total};
	};

	return {fold: fold, index: index, itemsOf: itemsOf, search: search, KINDS: KINDS};
})();

// Loaded by the editor as a plain script, and by node for its tests.
if (typeof module !== 'undefined' && module.exports) {
	module.exports = SaveFind;
}
