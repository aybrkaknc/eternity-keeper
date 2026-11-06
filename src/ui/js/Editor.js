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

var Editor = function () {
	var self = this;

	var defaultState = {
		listView: false
		, saveView: false
	};

	var bindDOM = () => {
		// Do one pass over the DOM at startup to bind all the UI elements that we need.
		$('[data-bound]').each((i, element) => {
			var boundTo = $(element).data('bound');
			var id = $(element).attr('id');

			if (boundTo == null || boundTo.length < 1 || id == null || id.length < 1) {
				console.error('Element ', element, ' was incorrectly bound.');
				return;
			}

			if (!self[boundTo]) {
				console.warn(
					'Element ', element, ' was bound to component'
					, boundTo, ' which does not exist');
				return;
			}

			if (!self[boundTo]['html']) {
				console.error('Component ', boundTo, ' does not have the "html" property.');
				return;
			}

			self[boundTo]['html'][id] = $('#' + id);
		});
	};

	var initialise = () => {
		// Call the init method on all of our components to allow them to set up their internal
		// state now that the editor has started up.
		for (var component in self) {
			if (!self.hasOwnProperty(component)	|| typeof self[component].init !== 'function') {
				continue;
			}

			self[component].init.call(self[component]);
		}
	};

	var getDirectoryPaths = () => {
		var updateDirectoryPaths = response => {
			var searchPath = '';
			var gameLocation = '';
			response = JSON.parse(response);

			if (response.savesLocation && response.savesLocation.length > 0) {
				searchPath = response.savesLocation;
			}

			if (response.gameLocation && response.gameLocation.length > 0) {
				gameLocation = response.gameLocation;
			}

			self.SaveSearch.render({searchPath: searchPath});
			self.Settings.render(
				{gameLocation: gameLocation, notes: response.notes || []});
			self.GameData.refresh();
			self.SaveSearch.search();
		};

		window.getDefaultSaveLocation({
			request: 'default'
			, onSuccess: updateDirectoryPaths
			, onFailure: console.error.bind(console, 'Error detecting directory paths.')
		});
	};

	var getStructures = () => {
		var storeStructures = response => {
			self.structures = JSON.parse(response);
		};

		window.getGameStructures({
			request: 'true'
			, onSuccess: storeStructures
			, onFailure: console.error.bind(console, 'Error getting game structures.')
		});
	};

	var disableMenu = menuID => $('#' + menuID).find('li').addClass('disabled').find('a').off();
	var enableMenu = menuID => $('#' + menuID).find('li').removeClass('disabled');

	var applyTheme = theme => {
		var light = theme === 'light';
		$('body').toggleClass('theme-light', light);
		$('#themeToggle span').text(light ? 'Dark mode' : 'Light mode');
		// What a narrow window's icon-only toggle says on hover.
		$('#themeToggle').attr('title', light ? 'Dark mode' : 'Light mode');
		$('#themeToggle i').attr('class', light ? 'fa fa-moon-o' : 'fa fa-sun-o');
	};

	var initialiseTheme = () => {
		var theme = 'dark';
		try {
			theme = localStorage.getItem('ekTheme') || 'dark';
		} catch (e) {
			// localStorage may be unavailable; default to dark.
		}

		applyTheme(theme);
		$('#themeToggle').off('click').click(() => {
			var light = !$('body').hasClass('theme-light');
			applyTheme(light ? 'light' : 'dark');
			try {
				localStorage.setItem('ekTheme', light ? 'light' : 'dark');
			} catch (e) {
				// Not persisted but still applied for this session.
			}
		});
	};

	self.state = $.extend({}, defaultState);
	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.SaveSearch.html.searchContainer.hide();
		self.SaveSearch.html.saveBlocks.hide();
		// Every editor view carries class "view", so hide them by that rather
		// than by a list of ids. The list was hand-maintained and went stale:
		// the grimoire view was missing from it and stayed on screen behind the
		// save-loading panel, on a cold boot and every time you went back to it.
		// SavedGame.render already switches views this way.
		// .save-only is the save screen's own chrome -- the validation strip --
		// which belongs to no single tab but must not outlive the save either.
		$('.view, .save-only').hide();
		self.SavedGame.html.characterList.empty();
		$('#characterQuickFilter').val('');

		// The collapse handle only makes sense next to an actual character list.
		$('body').toggleClass('list-view', !!self.state.listView);

		if (self.state.listView) {
			$('.navbar-nav > li').removeClass('ek-nav-active');
			self.SaveSearch.html.searchContainer.removeClass('ek-view-enter').show().addClass('ek-view-enter');
			self.SaveSearch.html.saveBlocks.show();
			self.CurrencyEditor.transition({enabled: false});
			self.DifficultyEditor.transition({enabled: false});
			self.InventoryEditor.transition({enabled: false});
			self.AbilityEditor.transition({enabled: false});
			self.StrongholdEditor.transition({enabled: false});
			self.GrimoireEditor.transition({enabled: false});
			self.VendorEditor.transition({enabled: false, visible: false});
			self.PortraitPicker.transition({enabled: false});
			self.PanelChanges.transition({enabled: false});
			self.ConsoleTab.transition({enabled: false});
			self.SavedGame.html.menuEditGlobals.off().parent().addClass('disabled');
			self.SavedGame.html.menuOpenConsole.off().parent().addClass('disabled');
			self.InventoryEditor.html.menuInventoryEditor.off().parent().addClass('disabled');
			self.AbilityEditor.html.menuCharacterAbilities.off().parent().addClass('disabled');
			self.StrongholdEditor.html.menuStrongholdEditor.off().parent().addClass('disabled');
			self.GrimoireEditor.html.menuGrimoireEditor.off().parent().addClass('disabled');
			self.VendorEditor.html.menuVendorEditor.off().parent().addClass('disabled');
			disableMenu('menuCharacter');
			disableMenu('menuGlobals');
			self.ImportCharacter.transition({enabled: false});
			self.ExportCharacter.transition({enabled: false});
			self.PartyManagement.transition({enabled: false});
			self.PartyCare.transition({enabled: false});
			self.Finder.transition({enabled: false});
			self.EditHistory.transition({enabled: false});
			self.Modifications.html.saveButton.hide();
		}

		if (self.state.saveView) {
			self.SavedGame.html.menuEditGlobals.parent().removeClass('disabled');
			self.SavedGame.html.menuOpenConsole.parent().removeClass('disabled');
			self.InventoryEditor.html.menuInventoryEditor.parent().removeClass('disabled');
			self.AbilityEditor.html.menuCharacterAbilities.parent().removeClass('disabled');
			self.StrongholdEditor.html.menuStrongholdEditor.parent().removeClass('disabled');
			self.GrimoireEditor.html.menuGrimoireEditor.parent().removeClass('disabled');
			self.VendorEditor.html.menuVendorEditor.parent().removeClass('disabled');
			enableMenu('menuCharacter');
			enableMenu('menuGlobals');
			self.DifficultyEditor.transition({enabled: true});
			self.InventoryEditor.transition({enabled: true});
			self.AbilityEditor.transition({enabled: true});
			self.StrongholdEditor.transition({enabled: true});
			self.GrimoireEditor.transition({enabled: true});
			// Visible only once SavedGame shows its view: transition merges
			// state, and a stale "visible" would read the area files of a
			// save nobody asked to look at.
			self.VendorEditor.transition({enabled: true, visible: false});
			self.PortraitPicker.transition({enabled: true});
			self.ConsoleTab.transition({enabled: true});
			self.ImportCharacter.transition({enabled: true});
			self.ExportCharacter.transition({enabled: true});
			self.PartyManagement.transition({enabled: true});
			self.PartyCare.transition({enabled: true});
			self.Finder.transition({enabled: true});
			self.EditHistory.transition({enabled: true});
			self.SaveSearch.html.saveActions.hide();
			self.Modifications.html.saveButton.show();
			$('#fabRow').addClass('ek-fab-enter');
		}
	};

	// Component instantiation goes here:
	self.GenericError = new GenericError();
	self.Progress = new Progress();
	self.Settings = new Settings();
	self.GameData = new GameData();
	self.Backups = new Backups();
	self.SaveSearch = new SaveSearch();
	self.CompareSaves = new CompareSaves();
	self.SavedGame = new SavedGame();
	self.CurrencyEditor = new CurrencyEditor();
	self.DifficultyEditor = new DifficultyEditor();
	self.InventoryEditor = new InventoryEditor();
	self.AbilityEditor = new AbilityEditor();
	self.StrongholdEditor = new StrongholdEditor();
	self.GrimoireEditor = new GrimoireEditor();
	self.VendorEditor = new VendorEditor();
	self.PortraitPicker = new PortraitPicker();
	self.PanelChanges = new PanelChanges();
	self.ConsoleTab = new ConsoleTab();
	self.Modifications = new Modifications();
	self.EditHistory = new EditHistory();
	self.ImportCharacter = new ImportCharacter();
	self.ExportCharacter = new ExportCharacter();
	self.PartyManagement = new PartyManagement();
	self.PartyCare = new PartyCare();
	self.Finder = new Finder();

	// Client startup tasks go here:
	bindDOM();
	initialise();
	initialiseTheme();
	getDirectoryPaths();
	getStructures();
};

var Eternity = new Editor();
