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

function flattenObject (obj, levels) {
	var results = [];
	var recursiveFlatten = (key, obj, guessLevel, currentLevel, targetLevel) => {
		for (var p in obj) {
			if (!obj.hasOwnProperty(p)) {
				continue;
			}

			var newKey = (key === null) ? p : key + '.' + p;
			if ((guessLevel && typeof obj[p] !== 'object')
				|| (currentLevel !== undefined && currentLevel === targetLevel)) {

				results.push([newKey, obj[p]]);
				continue;
			}

			recursiveFlatten(newKey, obj[p], guessLevel, currentLevel + 1, targetLevel);
		}
	};

	if (typeof obj !== 'object') {
		console.error('1st argument to flattenObject must be of type object, was:', obj);
		return;
	}

	if (levels !== undefined && typeof levels !== 'number') {
		console.error('2nd argument to flattenObject must be of type number, was:', levels);
		return;
	}

	if (levels < 1) {
		console.error('2nd argument to flattenObject must be at least 1, was:', levels);
		return;
	}

	if (levels === undefined) {
		recursiveFlatten(null, obj, true);
	} else {
		recursiveFlatten(null, obj, false, 1, levels);
	}

	return results;
}

// Who a character is *right now*, rather than who they were when the save was
// opened.
//
// The opener derives a few things from CharacterStats server-side and ships
// them as a snapshot: which equipment slots a character has, what class the
// ability browser should offer from. The Identity panel edits those same stats
// live, so the snapshot goes stale the moment someone changes a class -- the
// Inventory tab would keep offering a grimoire slot to a character who is no
// longer a wizard, and the Abilities tab would keep offering a paladin's
// talents to a cipher. These read the live values instead.

function liveStat (character, key) {
	if (!character || !character.stats || !character.stats[key]) {
		return '';
	}

	var value = character.stats[key].value;
	return value === null || value === undefined ? '' : String(value);
}

/**
 * Equipment.HasEquipmentSlot's rules, for the two it lets identity change.
 *
 * Head exists for anyone who is not Godlike and Grimoire only for a wizard;
 * Pet is only on the Player object, which no edit can move, so that one is
 * taken from the server's list as it stands. Nothing repairs an item left in
 * a slot that stops existing -- RepairSaveLoadEquipmentErrors handles the
 * deprecated Cape and locked slots only -- which is why the character panel
 * warns about stranded gear rather than moving it.
 */
function unavailableSlotsNow (character, serverList) {
	var out = (serverList || []).filter(
		slot => slot !== 'Head' && slot !== 'Grimoire');

	if (liveStat(character, 'CharacterRace') === 'Godlike') {
		out.push('Head');
	}

	if (liveStat(character, 'CharacterClass') !== 'Wizard') {
		out.push('Grimoire');
	}

	return out;
}

/**
 * How Undo names an Apply: "Inventory: 3 changes", "Abilities for Aloth:
 * 1 change". `whose` lists the GUIDs of the characters the changes were
 * for; two or more names are left out rather than run on.
 */
function appliedLabel (what, count, whose) {
	var characters = ((Eternity.SavedGame.state.saveData || {}).characters || []);
	var names = [];
	(whose || []).forEach(guid => {
		var character = characters.filter(c => c.GUID === guid)[0];
		if (character && character.name && names.indexOf(character.name) < 0) {
			names.push(character.name);
		}
	});

	return what + (names.length === 1 ? ' for ' + names[0] : '')
		+ ': ' + count + (count === 1 ? ' change' : ' changes');
}
