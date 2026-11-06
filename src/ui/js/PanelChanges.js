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

// Revert and Apply for the panels that edit saveData directly.
//
// The Inventory, Abilities, Stronghold and Grimoire tabs each stage a change
// set and send it to a manager on Apply, so they have always had the pair. The
// character sheet, the raw and globals tables and the console do not: they
// write into saveData as you type, and until now there was no way back from a
// number you had already changed except closing the save and losing
// everything else with it.
//
// So this keeps a *confirmed baseline* of the two things those panels edit --
// each character's own stats and portrait paths, and the save's globals -- and
// offers the same two words everywhere:
//
//   Revert          put this panel's scope back to the baseline
//   Apply changes   make what is on screen the new baseline
//
// Apply is a commit point, not a write: nothing reaches the disk until Save,
// and the bar says so. Deliberately NOT done here: disabling the Save button
// when everything is reverted. Inventory, party and item edits live outside
// these two scopes, so "clean" here does not mean "nothing to save", and
// guessing wrong in that direction would hide a real pending change.
var PanelChanges = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, character: ''
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// guid -> {stats: {...}, portraitPaths: {...}}, and the globals tree.
	var characters = {};
	var globals = null;

	var clone = value => value == null ? value : JSON.parse(JSON.stringify(value));
	var saveData = () => Eternity.SavedGame.state.saveData || {};

	var characterOf = guid =>
		(saveData().characters || []).filter(c => c.GUID === guid)[0] || null;

	// A baseline copies only the values, not the {type, value} wrappers'
	// identity: reverting writes back into the live wrappers so nothing that
	// holds a reference to them goes stale (invariant 12 -- never replace
	// saveData wholesale).
	var valuesOf = bag => {
		var out = {};
		Object.keys(bag || {}).forEach(key => {
			if (bag[key] && typeof bag[key] === 'object' && 'value' in bag[key]) {
				out[key] = clone(bag[key].value);
			}
		});

		return out;
	};

	var restoreValues = (bag, baseline) => {
		var changed = 0;
		Object.keys(baseline || {}).forEach(key => {
			var slot = bag ? bag[key] : null;
			if (slot && typeof slot === 'object' && 'value' in slot
				&& !same(slot.value, baseline[key])) {

				slot.value = clone(baseline[key]);
				changed++;
			}
		});

		return changed;
	};

	// The sheet draws the picture, not the paths: putting the paths back
	// without it left the new face on screen beside the old paths.
	var restorePortrait = (data, baseline) => {
		var changed = restoreValues(data.portraitPaths, baseline.portraitPaths);
		if (changed > 0 && baseline.portrait !== undefined) {
			data.portrait = baseline.portrait;
		}

		return changed;
	};

	var countDifferences = (bag, baseline) => {
		var count = 0;
		Object.keys(baseline || {}).forEach(key => {
			var slot = bag ? bag[key] : null;
			if (slot && typeof slot === 'object' && 'value' in slot
				&& !same(slot.value, baseline[key])) {

				count++;
			}
		});

		return count;
	};

	// The raw table hands back strings for numbers it never touched, so a
	// loose comparison is what "unchanged" actually means here.
	var same = (a, b) => {
		if (a === b) {
			return true;
		}

		if (a == null || b == null) {
			return false;
		}

		return String(a) === String(b);
	};

	// ---- the globals tree ----------------------------------------------------
	//
	// globals is {objectName: {componentName: {variable: {type, value}}}},
	// so it needs one more level of walking than a character does.

	var walkGlobals = (live, baseline, restore) => {
		var count = 0;
		Object.keys(baseline || {}).forEach(object => {
			Object.keys(baseline[object] || {}).forEach(component => {
				var liveBag = ((live || {})[object] || {})[component];
				var baseBag = baseline[object][component];
				count += restore
					? restoreValues(liveBag, baseBag)
					: countDifferences(liveBag, baseBag);
			});
		});

		return count;
	};

	var globalsBaseline = live => {
		var out = {};
		Object.keys(live || {}).forEach(object => {
			out[object] = {};
			Object.keys(live[object] || {}).forEach(component => {
				out[object][component] = valuesOf(live[object][component]);
			});
		});

		return out;
	};

	// ---- baselines -----------------------------------------------------------

	/** Called when a save opens: everything on screen is what the save holds. */
	self.capture = () => {
		characters = {};
		(saveData().characters || []).forEach(character => {
			characters[character.GUID] = {
				stats: valuesOf(character.stats)
				, portraitPaths: valuesOf(character.portraitPaths)
				, portrait: character.portrait
			};
		});

		globals = globalsBaseline(saveData().globals);
		self.refresh();
	};

	/**
	 * Called after a party change or a resurrection, which mint characters
	 * this has never seen. An unknown character is baselined where it stands
	 * rather than reported as a pile of unconfirmed edits.
	 */
	self.captureNewCharacters = () => {
		(saveData().characters || []).forEach(character => {
			if (!characters[character.GUID]) {
				characters[character.GUID] = {
					stats: valuesOf(character.stats)
					, portraitPaths: valuesOf(character.portraitPaths)
					, portrait: character.portrait
				};
			}
		});

		self.refresh();
	};

	// ---- counting ------------------------------------------------------------

	self.characterCount = guid => {
		var data = characterOf(guid);
		var baseline = characters[guid];
		if (!data || !baseline) {
			return 0;
		}

		return countDifferences(data.stats, baseline.stats)
			+ countDifferences(data.portraitPaths, baseline.portraitPaths);
	};

	self.globalsCount = () => walkGlobals(saveData().globals, globals, false);

	/** What the console can reach: every character plus the globals. */
	self.everythingCount = () => {
		var count = self.globalsCount();
		Object.keys(characters).forEach(guid => {
			count += self.characterCount(guid);
		});

		return count;
	};

	// ---- reverting and confirming --------------------------------------------

	self.revertCharacter = guid => {
		var data = characterOf(guid);
		var baseline = characters[guid];
		if (!data || !baseline) {
			return 0;
		}

		// A revert is a step of the history like any change, so Undo can
		// bring back what it threw away.
		var changed = 0;
		Eternity.EditHistory.labelled('Revert ' + data.name + '’s changes', () => {
			changed = restoreValues(data.stats, baseline.stats)
				+ restorePortrait(data, baseline);
		});

		// The sheet reads its numbers straight out of saveData, so redrawing
		// it is what makes the revert visible.
		Eternity.SavedGame.transition({});
		self.refresh();
		return changed;
	};

	self.applyCharacter = guid => {
		var data = characterOf(guid);
		if (!data) {
			return 0;
		}

		var confirmed = self.characterCount(guid);
		characters[guid] = {
			stats: valuesOf(data.stats)
			, portraitPaths: valuesOf(data.portraitPaths)
			, portrait: data.portrait
		};

		self.refresh();
		return confirmed;
	};

	self.revertGlobals = () => {
		var changed = 0;
		Eternity.EditHistory.labelled('Revert the global variables', () => {
			changed = walkGlobals(saveData().globals, globals, true);
		});

		Eternity.SavedGame.transition({});
		self.refresh();
		return changed;
	};

	self.applyGlobals = () => {
		var confirmed = self.globalsCount();
		globals = globalsBaseline(saveData().globals);
		self.refresh();
		return confirmed;
	};

	self.revertEverything = () => {
		var changed = 0;
		Eternity.EditHistory.labelled('Revert every unconfirmed change', () => {
			changed = walkGlobals(saveData().globals, globals, true);
			Object.keys(characters).forEach(guid => {
				var data = characterOf(guid);
				if (data) {
					changed += restoreValues(data.stats, characters[guid].stats);
					changed += restorePortrait(data, characters[guid]);
				}
			});
		});

		Eternity.SavedGame.transition({});
		self.refresh();
		return changed;
	};

	self.applyEverything = () => {
		var confirmed = self.everythingCount();
		self.capture();
		return confirmed;
	};

	// ---- the bars ------------------------------------------------------------

	var describe = count =>
		count < 1
			? 'No unconfirmed changes.'
			: count + (count === 1 ? ' unconfirmed change.' : ' unconfirmed changes.');

	var paint = (note, revert, apply, count) => {
		if (!note || !note.length) {
			return;
		}

		note.text(describe(count)).toggleClass('panel-bar-note-on', count > 0);
		revert.prop('disabled', count < 1);
		apply.prop('disabled', count < 1);
	};

	/** Re-reads every bar's count. Cheap: it walks values, not the DOM. */
	self.refresh = () => {
		if (!self.html.charPanelNote) {
			return;
		}

		var guid = Eternity.SavedGame.state.activeCharacter;
		var characterCount = self.characterCount(guid);

		paint(self.html.charPanelNote, self.html.charPanelRevert
			, self.html.charPanelApply, characterCount);
		paint(self.html.rawPanelNote, self.html.rawPanelRevert
			, self.html.rawPanelApply, characterCount);
		paint(self.html.globalsPanelNote, self.html.globalsPanelRevert
			, self.html.globalsPanelApply, self.globalsCount());
		paint(self.html.consolePanelNote, self.html.consolePanelRevert
			, self.html.consolePanelApply, self.everythingCount());

		var data = characterOf(guid);
		var name = data ? data.name : '';
		self.html.charPanelTitle.text(name);
		self.html.rawPanelTitle.text(name ? 'Raw variables — ' + name : 'Raw variables');
	};

	self.init = () => {
		var character = () => Eternity.SavedGame.state.activeCharacter;

		self.html.charPanelRevert.click(() => self.revertCharacter(character()));
		self.html.charPanelApply.click(() => self.applyCharacter(character()));
		self.html.rawPanelRevert.click(() => self.revertCharacter(character()));
		self.html.rawPanelApply.click(() => self.applyCharacter(character()));
		self.html.globalsPanelRevert.click(self.revertGlobals);
		self.html.globalsPanelApply.click(self.applyGlobals);
		self.html.consolePanelRevert.click(self.revertEverything);
		self.html.consolePanelApply.click(self.applyEverything);
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			characters = {};
			globals = null;
			self.refresh();
			return;
		}

		// Taken once per opened save. A manager's Apply hands back a freshly
		// built saveData, and re-capturing on every render would quietly
		// confirm edits the user had not confirmed -- so the baseline is kept
		// and only topped up with characters it has never seen.
		if (globals === null) {
			self.capture();
		} else {
			self.captureNewCharacters();
		}
	};
};

$.extend(PanelChanges.prototype, Renderer.prototype);
