/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2015 the authors.
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

// Heal, level up and resupply (Character menu): the party's upkeep between
// fights, done to the save the way the game itself does it. Every change goes
// into saveData and rides Save, like the currency.
//
// Healing sets Health.m_needs_current_values, the game's own flag for a full
// refill: the first frame after the save loads fills health and stamina to the
// maximum the game works out from class, level, Constitution and whatever is
// active, so the editor never has to. That is what its HealParty command ends
// with, for the party it acts on -- the characters in the active party.
//
// Levelling is the console's AddExperienceToLevel, for everyone in the save:
// a companion waiting at Caed Nua keeps experience on their own record, which
// is where the game adds theirs too. Unlike the game's command it never lowers
// anyone's experience; the game compares levels, so a character with a level-up
// still to take would lose it.
//
// Camping supplies are one number on the player's inventory, capped by the
// difficulty (CampingSupplies.StackMaximum).
var PartyCare = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, status: ''
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// What the player's inventory can hold on each difficulty.
	var suppliesCap = {
		StoryTime: 99, Easy: 6, Normal: 4, Hard: 2, PathOfTheDamned: 2
	};

	var difficultyNames = {
		StoryTime: 'Story Time', Easy: 'Easy', Normal: 'Normal', Hard: 'Hard'
		, PathOfTheDamned: 'Path of the Damned'
	};

	// Past 12 the cap is the expansions': each part of The White March opens two.
	var levelCap = 16;
	var baseGameCap = 12;

	var saveData = () => Eternity.SavedGame.state.saveData || {};

	var everyone = () => (saveData().characters || [])
		.filter(c => !c.resurrectable && !c.isDead && c.stats && c.stats.Experience);

	var healable = () => everyone()
		.filter(c => c.inParty && c.health && c.health.m_needs_current_values);

	var flagged = c => {
		var value = c.health.m_needs_current_values.value;
		return value === true || value === 'true';
	};

	var experienceOf = c => parseInt(c.stats.Experience.value, 10) || 0;

	var markDirty = () => Eternity.Modifications.transition({modifications: true});

	var difficulty = () => {
		var globals = saveData().globals;
		var entry = globals && globals.Global && globals.Global.GameState
			&& globals.Global.GameState.Difficulty;

		return entry ? entry.value : undefined;
	};

	var count = n => n.toLocaleString('en-US');

	var plural = (n, one, many) => n + ' ' + (n === 1 ? one : many);

	/** Experience for a level, as the game counts it: 500 × L × (L − 1). */
	self.experienceForLevel = level => 500 * level * (level - 1);

	/** Marks the active party for a full refill on load; how many it marked. */
	self.heal = () => {
		var marked = 0;
		healable().forEach(c => {
			if (!flagged(c)) {
				c.health.m_needs_current_values.value = true;
				marked++;
			}
		});

		if (marked > 0) {
			markDirty();
		}

		return marked;
	};

	/**
	 * Raises everyone below a level to that level's experience and returns
	 * who it raised. Nobody's experience goes down.
	 */
	self.levelTo = level => {
		var threshold = self.experienceForLevel(level);
		var raised = everyone().filter(c => experienceOf(c) < threshold);
		raised.forEach(c => c.stats.Experience.value = threshold);

		if (raised.length > 0) {
			markDirty();
		}

		return raised;
	};

	/** The most camping supplies the save's difficulty lets the party carry. */
	self.suppliesCap = () => {
		var d = difficulty();
		return d in suppliesCap ? suppliesCap[d] : 1;
	};

	self.refill = () => {
		var data = saveData();
		if (data.campingSupplies === undefined || data.campingSupplies === null
			|| data.campingSupplies >= self.suppliesCap()) {

			return false;
		}

		data.campingSupplies = self.suppliesCap();
		markDirty();
		return true;
	};

	var renderHeal = () => {
		var party = healable();
		var waiting = party.filter(c => !flagged(c));
		var text;

		if (party.length === 0) {
			text = everyone().some(c => c.inParty)
				? 'This save was written by an early version of the game and has no '
					+ 'health flag to set.'
				: 'No one in this save is in the active party.';
		} else if (waiting.length === 0) {
			text = 'The ' + plural(party.length, 'party member', 'party members')
				+ ' will be at full health and stamina when the save loads.';
		} else {
			text = 'Brings the ' + plural(party.length, 'party member', 'party members')
				+ ' back to full health and stamina when the save loads, as the game\'s '
				+ 'own HealParty does: the game works out every maximum itself.';
		}

		self.html.partyCareHealText.text(text);
		self.html.partyCareHeal
			.text(party.length > 0 && waiting.length === 0 ? 'Healed' : 'Heal the party')
			.prop('disabled', waiting.length === 0);
	};

	var renderLevel = () => {
		var level = parseInt(self.html.partyCareLevel.val(), 10);
		var threshold = self.experienceForLevel(level);
		var all = everyone();
		var below = all.filter(c => experienceOf(c) < threshold);
		var text;

		if (all.length === 0) {
			text = 'No one in this save has experience to raise.';
		} else if (below.length === 0) {
			text = 'Everyone already has the ' + count(threshold)
				+ ' experience level ' + level + ' takes.';
		} else {
			text = 'Raises ' + below.length + ' of ' + all.length + ' characters, in the '
				+ 'party or at Caed Nua, to ' + count(threshold) + ' experience. The game '
				+ 'offers the level-ups when the save loads.';
		}

		if (below.length > 0 && level > baseGameCap) {
			text += ' Past level 12 the game needs The White March: each part opens two '
				+ 'levels, and without them it keeps experience at its cap.';
		}

		self.html.partyCareLevelText.text(text);
		self.html.partyCareLevelUp.prop('disabled', below.length === 0);
	};

	var renderSupplies = () => {
		var have = saveData().campingSupplies;
		var cap = self.suppliesCap();
		var known = difficulty() in suppliesCap;

		if (have === undefined || have === null) {
			self.html.partyCareSuppliesText.text('This save does not record camping supplies.');
			self.html.partyCareRefill.text('Refill').prop('disabled', true);
			return;
		}

		self.html.partyCareSuppliesText.text(have + ' of ' + cap + ', the most a party can '
			+ 'carry' + (known ? ' on ' + difficultyNames[difficulty()] : '') + '.');
		self.html.partyCareRefill
			.text(have >= cap ? 'Full' : 'Refill')
			.prop('disabled', have >= cap);
	};

	// The level to offer first: the highest anyone has reached, which is the
	// one the others are most likely to be brought up to.
	var fillLevels = () => {
		var top = everyone().reduce((most, c) => Math.max(most, parseInt(c.level, 10) || 1), 2);
		var select = self.html.partyCareLevel.empty();

		for (var level = 2; level <= levelCap; level++) {
			select.append($('<option>').val(level).text('Level ' + level));
		}

		select.val(Math.min(top, levelCap));
	};

	self.renderRows = () => {
		renderHeal();
		renderLevel();
		renderSupplies();
		self.html.partyCareStatus.text(self.state.status);
	};

	self.init = () => {
		self.html.partyCareHeal.click(() => {
			var marked = 0;
			Eternity.EditHistory.labelled('Heal the party', () => {
				marked = self.heal();
			});
			self.state.status = marked > 0
				? 'Marked ' + plural(marked, 'party member', 'party members')
					+ ' for full health and stamina. Save to write it to a file.'
				: '';
			self.renderRows();
		});

		self.html.partyCareLevel.change(() => {
			self.state.status = '';
			self.renderRows();
		});

		self.html.partyCareLevelUp.click(() => {
			var level = parseInt(self.html.partyCareLevel.val(), 10);
			var raised = [];
			Eternity.EditHistory.labelled('Level up to ' + level, () => {
				raised = self.levelTo(level);
			});
			self.state.status = raised.length > 0
				? 'Raised ' + raised.map(c => c.name).join(', ') + ' to level ' + level
					+ '’s experience. Save to write it to a file.'
				: '';
			self.renderRows();
		});

		self.html.partyCareRefill.click(() => {
			var refilled = false;
			Eternity.EditHistory.labelled('Refill camping supplies', () => {
				refilled = self.refill();
			});

			self.state.status = refilled
				? 'Camping supplies refilled. Save to write it to a file.'
				: '';
			self.renderRows();
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.menuPartyCare.off().click(self.open.bind(self));
		self.html.menuPartyCare.parent().toggleClass('disabled', !self.state.enabled);

		// Nothing to show for a save that has closed.
		if (!self.state.enabled) {
			self.html.partyCareDialog.modal('hide');
		}
	};

	self.fillLevels = fillLevels;
};

PartyCare.prototype.open = function () {
	var self = this;
	if (!self.state.enabled) {
		return;
	}

	self.state.status = '';
	self.fillLevels();
	self.renderRows();
	self.html.partyCareDialog.modal('show');
};

$.extend(PartyCare.prototype, Renderer.prototype);
