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

var PartyManagement = function () {
	var self = this;
	var MAX_PARTY = 6;

	var defaultState = {
		enabled: false
		, saving: false
	};

	// guid -> true (party) / false (roster), staged until Accept.
	var staged = {};

	self.state = $.extend({}, defaultState);
	self.html = {};

	var characters = () => Eternity.SavedGame.state.saveData.characters;

	var partySize = () =>
		characters().filter(c => staged[c.GUID]).length;

	// A companion the game deleted on death is synthesised by the opener under
	// a "dead:<name>" id purely so it can be resurrected. There is no object to
	// move in or out of the party, and sending that id would have PartyManager
	// refuse the whole batch -- including everything else the player changed in
	// the same dialog. Resurrecting is the only thing that can be done with
	// one, and that lives in the character view.
	var isGone = character => !!character.resurrectable;

	var statusOf = character => {
		if (isGone(character)) {
			return 'Dead — resurrect from the character view';
		}

		if (character.isDead) {
			return staged[character.GUID]
				? 'In party, but dead'
				: 'Dead, waiting at the stronghold';
		}

		return staged[character.GUID] ? 'In party' : 'Available for hire';
	};

	var showInfo = character => {
		var info = self.html.partyManagementDialog.find('.pm-info');
		info.find('.pm-info-name').text(character ? character.name : '');
		info.find('.pm-info-class').text(
			!character ? '' : isGone(character) ? '' : character.className);
		info.find('.pm-info-level').text(
			!character || isGone(character) ? '' : 'Level ' + character.level);
		info.find('.pm-info-status').text(character ? statusOf(character) : '');
		info.toggleClass('pm-info-filled', !!character);
	};

	var tile = character => {
		var inParty = staged[character.GUID];
		var gone = isGone(character);
		var element = $('<div>')
			.addClass('pm-tile')
			.toggleClass('pm-locked', !!character.isMainCharacter || gone)
			.toggleClass('pm-dead', !!character.isDead)
			.data('guid', character.GUID);

		element.append($('<img>').attr(
			'src', 'data:image/png;base64,' + character.portrait));

		if (gone) {
			// Inert: labelled so the player can see where the companion went,
			// rather than silently missing from the roster.
			element.append($('<div>')
				.addClass('pm-tile-overlay pm-tile-overlay-dead')
				.text('Dead'));
		} else if (!character.isMainCharacter) {
			element.append($('<div>')
				.addClass('pm-tile-overlay')
				.text(inParty ? 'Dismiss' : 'Recruit'));
			element.click(self.toggle.bind(self, character.GUID));
		}

		element.mouseenter(showInfo.bind(self, character));
		element.mouseleave(showInfo.bind(self, null));
		return element;
	};

	var rebuild = () => {
		var dialog = self.html.partyManagementDialog;
		var partyGrid = dialog.find('.pm-party-grid').empty();
		var rosterGrid = dialog.find('.pm-roster-grid').empty();

		var sorted = characters().slice().sort((a, b) => {
			if (!!a.isMainCharacter !== !!b.isMainCharacter) {
				return a.isMainCharacter ? -1 : 1;
			}

			if (staged[a.GUID] && staged[b.GUID] && a.slot !== b.slot) {
				return a.slot - b.slot;
			}

			return a.name.localeCompare(b.name);
		});

		sorted.forEach(character => {
			(staged[character.GUID] ? partyGrid : rosterGrid).append(tile(character));
		});

		dialog.find('.pm-count').text(partySize() + '/' + MAX_PARTY);
		showInfo(null);
	};

	// While a party update is running the dialog cannot be dismissed and the
	// rest of the UI must not start another operation on the same save.
	var setBusy = busy => {
		self.state.saving = busy;
		var dialog = self.html.partyManagementDialog;
		dialog.find('#partyManagementCancel, .pm-close').prop('disabled', busy);
		dialog.find('#partyManagementAccept')
			.prop('disabled', busy)
			.html(busy
				? '<i class="fa fa-spinner fa-pulse"></i> Working&hellip;'
				: '<i>&#10094;</i> Accept <i>&#10095;</i>');
		Eternity.Modifications.transition({saving: busy});
	};

	self.open = () => {
		// A party change reopens the save, and a draft staged in a tab against
		// the old one would be dropped: the Inventory tab's is rebuilt whenever
		// the party changes.
		if (Eternity.EditHistory.refused()) {
			return;
		}

		staged = {};
		characters().forEach(c => staged[c.GUID] = !!c.inParty);
		rebuild();
		setBusy(false);
		self.html.partyManagementDialog.modal({backdrop: 'static', keyboard: false});
	};

	self.toggle = guid => {
		if (self.state.saving) {
			return;
		}

		if (!staged[guid] && partySize() >= MAX_PARTY) {
			var count = self.html.partyManagementDialog.find('.pm-count');
			count.addClass('pm-count-full');
			setTimeout(() => count.removeClass('pm-count-full'), 700);
			return;
		}

		staged[guid] = !staged[guid];
		rebuild();
	};

	self.accept = () => {
		if (self.state.saving) {
			return;
		}

		var changes = {};
		characters().forEach(c => {
			// Belt and braces: a synthetic "dead:<name>" id is not an ObjectID,
			// and one of them in the batch makes PartyManager refuse the lot.
			if (isGone(c)) {
				return;
			}

			if (!!c.inParty !== staged[c.GUID]) {
				changes[c.GUID] = staged[c.GUID];
			}
		});

		if (Object.keys(changes).length < 1) {
			self.html.partyManagementDialog.modal('hide');
			return;
		}

		var success = response => {
			setBusy(false);
			self.html.partyManagementDialog.modal('hide');
			response = JSON.parse(response);

			if (response.error) {
				Eternity.GenericError.render({msg: response.error});
				return;
			}

			// "Party: Edér in, Aloth out", for Undo.
			var moved = characters().filter(c => changes.hasOwnProperty(c.GUID))
				.map(c => c.name + (changes[c.GUID] ? ' in' : ' out'));

			Eternity.SavedGame.render({
				saveData: Eternity.SavedGame.adopt(response, 'Party: ' + moved.join(', '))
				, info: Eternity.SavedGame.state.info
			});

			Eternity.Modifications.transition({modifications: true});
		};

		var failure = (errno, response) => {
			setBusy(false);
			self.html.partyManagementDialog.modal('hide');
			Eternity.GenericError.render({msg: response});
		};

		setBusy(true);
		window.updateParty({
			request: JSON.stringify({
				oldSave: Eternity.SavedGame.state.info.absolutePath
				, savedYet: Eternity.Modifications.state.savedYet
				, changes: changes
			})
			, onSuccess: success
			, onFailure: failure
		});
	};

	self.init = () => {
		self.html.partyManagementAccept.off('click').click(self.accept.bind(self));
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		var menuItem = self.html['menu-party-management'];
		menuItem.off();

		if (self.state.enabled) {
			menuItem.parent().removeClass('disabled');
			menuItem.click(self.open);
		} else {
			menuItem.parent().addClass('disabled');
		}
	};
};

$.extend(PartyManagement.prototype, Renderer.prototype);
