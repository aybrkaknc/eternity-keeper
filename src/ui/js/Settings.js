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

var Settings = function () {
	var self = this;

	var defaultState = {
		gameLocation: ''
		, notes: []
		, saving: false
		// What getLanguages last said: the languages the install has text
		// for, the one chosen ('' follows the game), the one the game is set
		// to, and whether the game data read can be shown in another at all.
		, languages: null
		// The choice made in the dialog and not saved yet; null until then.
		, language: null
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.init = () => {
		self.html.saveSettings.click(self.save.bind(self));

		// Asked each time the dialog has opened: the answer depends on the
		// game folder, on what the game itself is set to and on the game data
		// that was read, and any of them may have changed since. ('shown', not
		// 'show': render() below calls modal('show') on a dialog that may be
		// open already, which fires 'show' again and would ask for ever.)
		self.html.settingsDialog.on('shown.bs.modal', self.askLanguages.bind(self));
		self.html.gameLanguage.change(() =>
			self.transition({language: self.html.gameLanguage.val()}));
	};

	// The language the game's own names are shown in. Hidden unless the
	// install has more than one to choose from.
	var renderLanguages = () => {
		var info = self.state.languages;
		var block = self.html.settingsLanguage;
		if (!info || !info.languages || info.languages.length < 2) {
			block.hide();
			return;
		}

		var label = code => {
			var found = info.languages.filter(language => language.code === code)[0];
			return found ? found.label : code;
		};

		var picked = self.state.language === null ? info.chosen : self.state.language;
		var select = self.html.gameLanguage.empty();
		select.append($('<option>').val('').text(info.game
			? 'The same as the game (' + label(info.game) + ')'
			: 'The same as the game'));
		info.languages.forEach(language =>
			select.append($('<option>').val(language.code).text(language.label)));
		select.val(picked);

		var shown = picked || info.game || 'en';
		var note = [];
		if (!picked && !info.game) {
			note.push('The game does not say which language it is set to, so names are in English.');
		}

		if (shown !== 'en' && info.catalogued && !info.localizable) {
			note.push('The game data was read before the editor kept what this needs: read it'
				+ ' again, above, to see items and abilities in ' + label(shown) + '.');
		}

		note.push('Applies to saves opened from now on. The editor itself stays in English.');
		self.html.gameLanguageNote.text(note.join(' '));
		block.show();
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.gameLocation.val(self.state.gameLocation);

		// The search can work something out that it cannot act on -- a
		// Microsoft Store copy, say -- and saying so beats an empty box.
		var note = self.html.settingsNote.empty();
		(self.state.notes || []).forEach(text =>
			note.append($('<p>').text(text)));

		note.toggle((self.state.notes || []).length > 0);
		renderLanguages();

		if (self.state.saving) {
			self.html.saveSettings.prop('disabled', true);
			self.html.saveSettings.find('i').show();
			self.html.saveSettings.find('span').text('Saving...');
		} else {
			self.html.saveSettings.prop('disabled', false);
			self.html.saveSettings.find('i').hide();
			self.html.saveSettings.find('span').text('Save');
		}

		if (self.state.gameLocation.length < 1) {
			self.html.settingsDialog.modal('show');
		}
	};
};

Settings.prototype.save = function () {
	var self = this;

	var data = {
		gameLocation: self.html.gameLocation.val()
	};

	// Only a choice that was offered is saved: with no install to read the
	// languages from there is nothing in the list, and an empty list must not
	// wipe a choice made earlier.
	if (self.state.languages && (self.state.languages.languages || []).length > 1) {
		data.language = self.html.gameLanguage.val() || '';
	}

	var success = () => {
		self.transition({saving: false, language: null});
		self.html.settingsDialog.modal('hide');

		// The game data is read from this folder, so what it offers changes with it.
		Eternity.GameData.refresh();
	};

	var failure = () => {
		self.transition({saving: false});
		console.error('Error saving settings.');
	};

	if (self.state.saving) {
		return;
	}

	self.transition({gameLocation: data.gameLocation, saving: true});
	window.saveSettings({
		request: JSON.stringify(data)
		, onSuccess: success
		, onFailure: failure
	});
};

Settings.prototype.askLanguages = function () {
	var self = this;

	// The pages are loaded from disk and may be newer than the editor showing
	// them.
	if (!window.getLanguages) {
		return;
	}

	window.getLanguages({
		request: 'true'
		, onSuccess: reply => self.transition({languages: JSON.parse(reply), language: null})
		, onFailure: () => self.transition({languages: null, language: null})
	});
};

$.extend(Settings.prototype, Renderer.prototype);
