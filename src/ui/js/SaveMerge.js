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

// Folding a freshly reopened save into the UI's copy without losing the user's
// unsaved edits (invariant 12).
//
// Seven editors send the working save to a manager and get it back reopened:
// inventory, abilities, stronghold, grimoire, party, resurrection and the
// achievements toggle. Each used to merge the reply its own way, and measured on
// the running editor three of those ways lost work -- a party change reverted an
// unsaved attribute, a grimoire Apply an unsaved global, an inventory Apply an
// unsaved portrait. A two-way merge cannot get this right: "keep mine" loses
// what the server just changed, "take theirs" loses what the user just typed.
//
// Three-way can. `base` is a snapshot of what the server last sent; `mine` is
// the UI's copy; `theirs` is the reply. A value that differs between mine and
// base is the user's and is kept. Everything else comes from theirs -- which is
// how a stronghold's new Prestige, or a resurrection's cleared death flags,
// arrive. When both changed the same value the user's wins: what someone typed
// is never silently replaced.
//
// Only the scalar scopes the UI edits in place take part: each character's
// stats, portrait and health flag, the party's money and camping supplies, and
// the globals. The inventory,
// abilities, stronghold and grimoire payloads are the managers' own business,
// staged and applied by them, so theirs is always the truth there. Those same
// scopes are all Save writes, so `writable` is what a Save request carries.
var SaveMerge = (function () {
	// The raw table hands back strings for numbers it never touched.
	var same = (a, b) => a === b
		|| (a !== null && a !== undefined && b !== null && b !== undefined
			&& String(a) === String(b));

	var valuesOf = bag => {
		var out = {};
		Object.keys(bag || {}).forEach(key => {
			var entry = bag[key];
			if (entry && typeof entry === 'object' && 'value' in entry) {
				out[key] = entry.value;
			}
		});

		return out;
	};

	var copy = value => value === undefined ? undefined : JSON.parse(JSON.stringify(value));

	/**
	 * What the server sent, reduced to the values a merge compares against.
	 * Small on purpose: a save's inventory and art run to megabytes and none
	 * of it is edited in place.
	 */
	var snapshot = save => {
		var out = {
			characters: {}, globals: {}, currency: save.currency
			, campingSupplies: save.campingSupplies
		};

		(save.characters || []).forEach(character => {
			out.characters[character.GUID] = {
				stats: valuesOf(character.stats)
				, portraitPaths: valuesOf(character.portraitPaths)
				, portrait: character.portrait
				, health: valuesOf(character.health)
			};
		});

		Object.keys(save.globals || {}).forEach(object => {
			out.globals[object] = {};
			Object.keys(save.globals[object] || {}).forEach(component => {
				out.globals[object][component] = valuesOf(save.globals[object][component]);
			});
		});

		return copy(out);
	};

	/**
	 * Writes the user's edits from `mine` into `theirs`: every slot of
	 * `theirsBag` whose value in `mineBag` differs from `baseValues`.
	 * Returns how many slots were the user's.
	 */
	var keepEdits = (baseValues, mineBag, theirsBag) => {
		var kept = 0;
		Object.keys(theirsBag || {}).forEach(key => {
			var mineEntry = (mineBag || {})[key];
			var theirsEntry = theirsBag[key];

			if (!mineEntry || !theirsEntry || typeof theirsEntry !== 'object'
				|| !(key in (baseValues || {}))) {

				return;
			}

			if (!same(mineEntry.value, baseValues[key])) {
				theirsEntry.value = copy(mineEntry.value);
				kept++;
			}
		});

		return kept;
	};

	/**
	 * `theirs` with the user's unsaved edits from `mine` put back in. `theirs`
	 * is the freshly parsed reply and is modified; `mine` is only read. With
	 * no `base` there is no telling an edit from the file, so theirs is
	 * returned as it is.
	 */
	var merge = (base, mine, theirs) => {
		if (!base || !mine) {
			return theirs;
		}

		var mineByGuid = {};
		(mine.characters || []).forEach(character => {
			mineByGuid[character.GUID] = character;
		});

		(theirs.characters || []).forEach(character => {
			var mineCharacter = mineByGuid[character.GUID];
			var baseCharacter = base.characters[character.GUID];
			if (!mineCharacter || !baseCharacter) {
				// New to the save -- a resurrection or an import -- so there
				// is nothing the user could have edited yet.
				return;
			}

			keepEdits(baseCharacter.stats, mineCharacter.stats, character.stats);

			// The image travels with its paths: a picked face whose paths
			// were kept but whose picture was not would show the old one.
			if (keepEdits(baseCharacter.portraitPaths, mineCharacter.portraitPaths
				, character.portraitPaths) > 0) {

				character.portrait = mineCharacter.portrait;
			}

			keepEdits(baseCharacter.health, mineCharacter.health, character.health);
		});

		if (!same(mine.currency, base.currency)) {
			theirs.currency = mine.currency;
		}

		if (!same(mine.campingSupplies, base.campingSupplies)) {
			theirs.campingSupplies = mine.campingSupplies;
		}

		Object.keys(theirs.globals || {}).forEach(object => {
			Object.keys(theirs.globals[object] || {}).forEach(component => {
				keepEdits(
					(base.globals[object] || {})[component]
					, ((mine.globals || {})[object] || {})[component]
					, theirs.globals[object][component]);
			});
		});

		return theirs;
	};

	// A set of {type, value} slots with every value a string: ChangesSaver
	// reads them with getString, which refuses a number.
	var asStrings = bag => {
		var out = {};
		Object.keys(bag || {}).forEach(key => {
			var slot = copy(bag[key]);
			if (slot && slot.value !== null && slot.value !== undefined) {
				slot.value = String(slot.value);
			}

			out[key] = slot;
		});

		return out;
	};

	/**
	 * What Save sends: the same scopes a merge protects, since those are the
	 * only ones Save writes. Everything else in the UI's copy -- the inventory
	 * and its icons above all -- is a manager's, already on disk, and only
	 * makes the request bigger. `save` is not modified.
	 */
	var writable = save => {
		var globals = {};
		Object.keys(save.globals || {}).forEach(object => {
			globals[object] = {};
			Object.keys(save.globals[object] || {}).forEach(component => {
				globals[object][component] = asStrings(save.globals[object][component]);
			});
		});

		var request = {
			currency: save.currency
			, globals: globals
			, characters: (save.characters || []).map(character => {
				var out = {GUID: character.GUID, stats: asStrings(character.stats)};
				if (character.portraitPaths) {
					out.portraitPaths = asStrings(character.portraitPaths);
				}

				if (character.health) {
					out.health = asStrings(character.health);
				}

				return out;
			})
		};

		// A save whose player has no PlayerInventory has nothing to write here,
		// and the server leaves the count alone when none is sent.
		if (save.campingSupplies !== undefined && save.campingSupplies !== null) {
			request.campingSupplies = save.campingSupplies;
		}

		return request;
	};

	return {snapshot: snapshot, merge: merge, writable: writable};
})();

// Loaded by the editor as a plain script, and by node for its tests.
if (typeof module !== 'undefined' && module.exports) {
	module.exports = SaveMerge;
}
