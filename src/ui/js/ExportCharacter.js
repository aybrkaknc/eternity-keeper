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

var ExportCharacter = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, exporting: false
	};

	var populateSelector = characters => {
		var selector = self.html.exportCharacterDialog.find('.multi-selector');
		selector.empty();
		selector.append(characters.map(character =>
			$('<li>')
				.data('guid', character.GUID)
				.text(character.name)
				.append(' <i class="fa fa-spinner fa-pulse"></i>')
				.click(self.exportCharacter.bind(self, character.GUID))));
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.openDialog = () => {
		self.html.exportCharacterDialog.find('.alert').hide();
		populateSelector(Eternity.SavedGame.state.saveData.characters);
		self.html.exportCharacterDialog.modal('show');
	};

	self.exportCharacter = (guid, e) => {
		if (self.state.exporting) {
			return;
		}

		var spinner = $(e.currentTarget).find('i');

		var success = () => {
			self.state.exporting = false;
			spinner.hide();
			self.html.exportCharacterDialog.find('.alert-success').show();
		};

		var failure = (errno, response) => {
			self.state.exporting = false;
			spinner.hide();

			// NO_SAVENAME means the user cancelled the file dialog. The other
			// codes keep the dialog's own words; a reason given in words -- a
			// save that could only be read in part -- is shown as it stands.
			if (response !== 'NO_SAVENAME') {
				self.html.exportCharacterDialog.find('.alert-danger')
					.text(/^[A-Z_]+$/.test(response) ? 'Error exporting character.' : response)
					.show();
			}
		};

		self.html.exportCharacterDialog.find('.alert').hide();
		self.state.exporting = true;
		spinner.show();

		window.exportCharacter({
			request: JSON.stringify({
				GUID: guid
				, absolutePath: Eternity.SavedGame.state.info.absolutePath
				// An Apply or a Save moves the character's current state out
				// of the directory the list opened.
				, savedYet: Eternity.Modifications.state.savedYet
			})
			, onSuccess: success
			, onFailure: failure
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		var menuItem = self.html['menu-export-character'];
		menuItem.off();

		if (self.state.enabled) {
			menuItem.parent().removeClass('disabled');
			menuItem.click(self.openDialog);
		} else {
			menuItem.parent().addClass('disabled');
		}
	};
};

$.extend(ExportCharacter.prototype, Renderer.prototype);
