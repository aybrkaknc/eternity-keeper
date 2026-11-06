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

// "Edit difficulty level" modal (Save data menu): replicates the in-game
// New Game Settings dialog — difficulty plus Expert/Trial of Iron/Turn-Based
// — editing GameState fields directly in the save data.
var DifficultyEditor = function () {
	var self = this;

	var defaultState = {
		enabled: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	var globalEntry = (root, component, key) => {
		var globals = Eternity.SavedGame.state.saveData
			&& Eternity.SavedGame.state.saveData.globals;
		return globals && globals[root] && globals[root][component]
			? globals[root][component][key]
			: undefined;
	};

	var gameState = key => globalEntry('Global', 'GameState', key);

	var markDirty = () => Eternity.Modifications.transition({modifications: true});

	var difficulties = [
		{value: 'StoryTime', label: 'Story Time', icon: 'fa-book'
			, desc: 'Story Time provides a much easier combat experience for players who want '
				+ 'to focus on exploration and narrative.'}
		, {value: 'Easy', label: 'Easy', icon: 'fa-leaf'
			, desc: 'Easy features fewer and weaker groups of enemies, recommended for players '
				+ 'new to this style of game.'}
		, {value: 'Normal', label: 'Normal', icon: 'fa-shield'
			, desc: 'Normal features standard groups of enemies, recommended for most players.'}
		, {value: 'Hard', label: 'Hard', icon: 'fa-fire'
			, desc: 'Hard features more numerous and more powerful groups of enemies, '
				+ 'recommended for veterans of the genre.'}
		, {value: 'PathOfTheDamned', label: 'Path of the Damned', icon: 'fa-bolt'
			, desc: 'Path of the Damned combines the most powerful groups of enemies with '
				+ 'upscaled combat mechanics. Only for the very best players.'}
	];

	var modes = [
		{key: 'ExpertMode', label: 'Expert Mode', icon: 'fa-graduation-cap'
			, desc: 'Expert Mode disables many of the game\'s helper systems and warnings for '
				+ 'a more demanding experience.'}
		, {key: 'TrialOfIron', label: 'Trial of Iron', icon: 'fa-heartbeat'
			, desc: 'Trial of Iron restricts the game to a single save file that is deleted '
				+ 'if the party is ever defeated.'}
		, {key: 'TacticalMode', label: 'Turn-Based Mode', icon: 'fa-clock-o'
			, desc: 'Turn-Based Mode provides an alternate form of play where characters act '
				+ 'on their turn during each round of combat. Recommended for players that '
				+ 'enjoy turn based games. Cannot toggle Turn-Based Mode when in combat.'}
	];

	var describe = text => self.html.settingDesc.text(text);

	var renderSettings = () => {
		var difficultyEntry = gameState('Difficulty');
		var row = self.html.difficultyRow.empty();

		difficulties.forEach(d => {
			var active = difficultyEntry && difficultyEntry.value === d.value;
			var option = $('<div>')
				.addClass('console-option' + (active ? ' active' : ''))
				.append($('<div>').addClass('console-option-icon')
					.append($('<i>').addClass('fa ' + d.icon)))
				.append($('<div>').addClass('console-option-label').text(d.label))
				.on('mouseenter', () => describe(d.desc));

			if (difficultyEntry) {
				option.click(() => {
					difficultyEntry.value = d.value;
					markDirty();
					renderSettings();
					describe(d.desc);
				});
			} else {
				option.addClass('unavailable');
			}

			row.append(option);
		});

		var modeRow = self.html.modesRow.empty();
		modes.forEach(m => {
			var entry = gameState(m.key);
			var on = entry && (m.key === 'TacticalMode'
				? entry.value === 'RoundBased'
				: entry.value === true || entry.value === 'true');

			var option = $('<div>')
				.addClass('console-option console-mode' + (on ? ' active' : ''))
				.append($('<div>').addClass('console-option-icon')
					.append($('<i>').addClass('fa ' + m.icon)))
				.append($('<div>').addClass('console-option-label').text(m.label))
				.on('mouseenter', () => describe(m.desc));

			if (entry) {
				option.click(() => {
					if (m.key === 'TacticalMode') {
						// The game's own names for this enum, which saveinfo.xml
						// carries as text for the load screen.
						entry.value = on ? 'Disabled' : 'RoundBased';
					} else {
						entry.value = on ? 'false' : 'true';
					}

					markDirty();
					renderSettings();
					describe(m.desc);
				});
			} else {
				option.addClass('unavailable');
			}

			modeRow.append(option);
		});
	};

	self.init = () => {
		self.html.menuDifficultyEditor.click(self.open.bind(self));
		self.html.difficultyEditorDone.click(self.close.bind(self));
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.menuDifficultyEditor.off().click(self.open.bind(self));

		if (self.state.enabled) {
			self.html.menuDifficultyEditor.parent().removeClass('disabled');
		} else {
			self.html.menuDifficultyEditor.parent().addClass('disabled');
		}
	};

	self.renderSettings = renderSettings;
};

DifficultyEditor.prototype.open = function () {
	var self = this;
	self.renderSettings();
	self.html.difficultyDialog.modal('show');
	self.transition({});
};

DifficultyEditor.prototype.close = function () {
	var self = this;
	self.html.difficultyDialog.modal('hide');
};

$.extend(DifficultyEditor.prototype, Renderer.prototype);
