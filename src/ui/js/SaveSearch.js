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

var SaveSearch = function () {
	var self = this;

	var defaultState = {
		searchPath: ''
		, saves: []
		, searching: false
		, opening: false
		, selected: -1
		, busy: false
		, unreadable: []
		, filterQuery: ''
		, sortBy: 'default'
		, viewMode: 'grid'
	};

	// How many of the files left out are named on the page; eternity.log has
	// every one.
	var UNREADABLE_SHOWN = 5;

	// The files in the folder that are not in the list, each with the reason.
	// A save that is simply missing leaves its owner looking for it. Text
	// only: a file's name is whatever somebody called the file.
	var renderUnreadable = (container, files) => {
		container.empty().toggle(files.length > 0);
		if (files.length < 1) {
			return;
		}

		$('<div class="us-head">').text(files.length === 1
			? 'One file in this folder is not listed, because it could not be read as a save:'
			: files.length + ' files in this folder are not listed, because they could not'
				+ ' be read as saves:').appendTo(container);

		files.slice(0, UNREADABLE_SHOWN).forEach(file => {
			$('<div class="us-file">')
				.append($('<b>').text(file.name))
				.append(document.createTextNode(' \u2014 ' + file.reason + '.'))
				.appendTo(container);
		});

		if (files.length > UNREADABLE_SHOWN) {
			$('<div class="us-more">').text('And ' + (files.length - UNREADABLE_SHOWN)
				+ ' more: eternity.log names every one.').appendTo(container);
		}
	};

	// Prefer the scene title from saveinfo.xml: the game strips non-ASCII
	// characters (Polish diacritics included) when building filenames, so the
	// filename-derived system name is mangled for localized area names.
	var saveName = info => {
		var userSaveName = info.userSaveName ? ' (' + info.userSaveName + ')' : '';
		return info.playerName + ' - ' + (info.sceneTitle || info.systemName) + userSaveName;
	};

	var matchesFilter = (info, query) => {
		if (!query) {
			return true;
		}

		var needle = query.toLowerCase();
		var haystack = [
			saveName(info)
			, info.playerName || ''
			, info.sceneTitle || ''
			, info.systemName || ''
			, info.userSaveName || ''
			, info.date || ''
			, info.difficulty || ''
			, info.chapter !== undefined ? 'chapter ' + info.chapter : ''
		].join(' ').toLowerCase();

		return haystack.indexOf(needle) !== -1;
	};

	var populateSaveBlocks = (container, template, data, opening, selected, filterQuery, sortBy) => {
		var entries = SaveListModel.filteredAndSorted(
			data, filterQuery, sortBy, matchesFilter);

		if (data.length > 0 && entries.length === 0) {
			container.append(
				$('<div class="ek-save-empty-filter">')
					.text('No saved games match "' + filterQuery + '".'));
			return entries.length;
		}

		entries.forEach((entry, seq) => {
			var info = entry.info;
			var i = entry.index;
			var locationAndSaveName = info.sceneTitle || info.systemName || '';
			if (info.userSaveName) {
				locationAndSaveName += ' (' + info.userSaveName + ')';
			}
			var tile = CloneFactory.clone(template);
			var portraits = (info.portraits || []).map(
					portrait => '<img src="data:image/png;base64,' + portrait + '">');

			var delayMs = Math.min(seq * 25, 250) + 'ms';
			tile.addClass('ek-tile-enter')
				.css({'-webkit-animation-delay': delayMs, 'animation-delay': delayMs});

			if (opening === i) {
				tile.addClass('ek-save-opening');
				tile.find('i').hide();
				tile.append(
					'<span class="ek-opening-badge">'
					+ '<i class="fa fa-spinner fa-pulse"></i> Opening save&hellip;'
					+ '</span>');
			}

			tile.attr('data-save-index', i).toggleClass('selected', selected === i);
			tile.find('.screenshot img').attr('src', 'data:image/png;base64,' + info.screenshot);
			tile.find('.name').text(saveName(info));
			tile.find('.date').text(info.date);
			tile.find('.portraits').html(portraits.join(' '));
			tile.find('.list-character').text(info.playerName || '');
			tile.find('.list-location').text(locationAndSaveName);
			tile.find('.list-date').text(info.date || '');
			tile.find('.list-difficulty').text(info.difficulty || '');
			tile.find('.list-chapter').text(info.chapter === undefined ? '' : info.chapter);
			tile.click(self.select.bind(self, i));
			tile.dblclick(() => self.loadSelected(info, i));
			container.append(tile);
		});

		return entries.length;
	};

	// Builds the filter, sort and view controls inside #searchContainer once
	// at startup.
	var ensureModernControls = () => {
		if (!self.html.searchContainer || self.html.saveFilterBar) {
			return;
		}

		var filterBar = $(
			'<div class="ek-save-filter-bar" id="saveFilterBar">'
			+ '  <div class="ek-save-filter-input-wrap">'
			+ '    <i class="fa fa-search"></i>'
			+ '    <input type="text" class="form-control" id="saveListFilter"'
			+ '           placeholder="Filter saves by character, area, save name or date..." autocomplete="off" spellcheck="false">'
			+ '  </div>'
			+ '  <div class="ek-save-sort-wrap">'
			+ '    <label for="saveListSort">Sort</label>'
			+ '    <select class="form-control" id="saveListSort">'
			+ '      <option value="date-desc">Newest first</option>'
			+ '      <option value="date-asc">Oldest first</option>'
			+ '      <option value="name-asc">Character &amp; area (A&ndash;Z)</option>'
			+ '      <option value="default">Folder order</option>'
			+ '    </select>'
			+ '  </div>'
			+ '  <div class="ek-save-view-toggle" role="group" aria-label="Save list view">'
			+ '    <button type="button" class="btn btn-default btn-sm" id="saveGridView" title="Grid view" aria-label="Grid view"><i class="fa fa-th-large"></i></button>'
			+ '    <button type="button" class="btn btn-default btn-sm" id="saveListView" title="List view" aria-label="List view"><i class="fa fa-list"></i></button>'
			+ '  </div>'
			+ '  <span class="ek-save-count" id="saveListCount"></span>'
			+ '</div>');

		self.html.searchContainer.append(filterBar);
		self.html.saveFilterBar = filterBar;
		self.html.saveListFilter = filterBar.find('#saveListFilter');
		self.html.saveListSort = filterBar.find('#saveListSort');
		self.html.saveListCount = filterBar.find('#saveListCount');
		self.html.saveGridView = filterBar.find('#saveGridView');
		self.html.saveListView = filterBar.find('#saveListView');

		self.html.saveListFilter.on('input keyup', () => {
			self.state.filterQuery = self.html.saveListFilter.val();
			self.state.selected = -1;
			self.html.saveActions.hide();
			self.renderSaveTilesOnly();
		});

		self.html.saveListSort.on('change', () => {
			self.state.sortBy = self.html.saveListSort.val();
			self.renderSaveTilesOnly();
		});

		self.html.saveGridView.on('click', () => self.setViewMode('grid'));
		self.html.saveListView.on('click', () => self.setViewMode('list'));

		// A file dropped on the window would make the browser leave the editor
		// for it. Opening saves by dropping them is not supported yet: Chrome 45
		// tells the page a dropped file's name but not its folder.
		$(document).on('dragover drop', e => e.preventDefault());
	};

	// The original .savegame archive corresponding to an extracted save.
	self.saveFilePath = info => {
		var base = info.absolutePath.split(/[\\\/]/).pop();
		var root = self.state.searchPath.replace(/[\\\/]+$/, '');
		return root + '\\' + base;
	};

	self.selectedInfo = () =>
		self.state.selected >= 0 ? self.state.saves[self.state.selected] : null;

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.init = () => {
		ensureModernControls();
		try {
			self.state.viewMode = localStorage.getItem('ekSaveViewMode') === 'list'
				? 'list' : 'grid';
		} catch (e) {
			self.state.viewMode = 'grid';
		}
		self.html.searchForSavedGames.click(self.search.bind(self));
		self.html.saveActionLoad.off('click').click(() => {
			var info = self.selectedInfo();
			if (info) {
				self.loadSelected(info, self.state.selected);
			}
		});
		self.html.saveActionRename.off('click').click(self.renamePrompt.bind(self));
		self.html.saveActionConvert.off('click').click(self.convertPrompt.bind(self));
		self.html.saveActionDelete.off('click').click(self.deletePrompt.bind(self));
		self.html.convertSaveConfirm.off('click').click(self.convertConfirmed.bind(self));
		self.html.renameSaveConfirm.off('click').click(self.renameConfirmed.bind(self));
		self.html.deleteSaveConfirm.off('click').click(self.deleteConfirmed.bind(self));
		self.html.renameSaveDialog.on(
			'shown.bs.modal', () => self.html.renameSaveInput[0].select());
	};

	self.renderSaveTilesOnly = () => {
		self.html.saveBlocks.empty();
		self.html.saveBlocks.toggleClass('list-view', self.state.viewMode === 'list');
		if (self.state.viewMode === 'list' && self.state.saves.length > 0) {
			self.html.saveBlocks.append(
				'<div class="save-list-header" aria-hidden="true">'
				+ '<span>Character</span><span>Location / save name</span>'
				+ '<span>Modified</span><span>Difficulty</span><span>Chapter</span>'
				+ '</div>');
		}
		var shownCount = populateSaveBlocks(
			self.html.saveBlocks
			, self.html.saveBlockClone
			, self.state.saves
			, self.state.opening
			, self.state.selected
			, self.state.filterQuery
			, self.state.sortBy);

		if (self.html.saveFilterBar) {
			self.html.saveFilterBar.toggle(self.state.saves.length > 0);
			var total = self.state.saves.length;
			self.html.saveListCount.text(
				shownCount === total
					? total + (total === 1 ? ' save' : ' saves')
					: shownCount + ' of ' + total + ' saves');
		}
	};

	self.render = newState => {
		var previous = self.state;
		self.state = $.extend({}, defaultState, newState);
		self.html.savedGameLocation.val(self.state.searchPath);
		self.html.saveBlocks.show();
		if (!Eternity.state.listView) {
			Eternity.render({listView: true});
		}

		if (self.html.saveListFilter && self.html.saveListFilter.val() !== self.state.filterQuery) {
			self.html.saveListFilter.val(self.state.filterQuery);
		}

		if (self.html.saveListSort && self.html.saveListSort.val() !== self.state.sortBy) {
			self.html.saveListSort.val(self.state.sortBy);
		}

		if (previous.saves !== self.state.saves
			|| previous.filterQuery !== self.state.filterQuery
			|| previous.sortBy !== self.state.sortBy
			|| previous.viewMode !== self.state.viewMode) {
			self.renderSaveTilesOnly();
		} else {
			if (previous.selected !== self.state.selected) {
				self.html.saveBlocks.find('.save-info').removeClass('selected');
				self.html.saveBlocks.find('[data-save-index="' + self.state.selected + '"]')
					.addClass('selected');
			}

			if (previous.opening !== self.state.opening) {
				self.html.saveBlocks.find('.save-info').removeClass('ek-save-opening');
				self.html.saveBlocks.find('.ek-opening-badge').remove();
				if (self.state.opening !== false) {
					var openingTile = self.html.saveBlocks
						.find('[data-save-index="' + self.state.opening + '"]');
					openingTile.addClass('ek-save-opening')
						.append('<span class="ek-opening-badge"><i class="fa fa-spinner fa-pulse"></i> Opening save&hellip;</span>');
				}
			}
		}

		if (self.html.saveGridView) {
			self.html.saveGridView.toggleClass('active', self.state.viewMode === 'grid');
			self.html.saveListView.toggleClass('active', self.state.viewMode === 'list');
		}

		var actionable = self.state.selected >= 0;
		self.html.saveActions.toggle(actionable).toggleClass('ek-fab-enter', actionable);
		self.html.saveActions.find('button').prop(
			'disabled', self.state.busy || self.state.opening !== false);

		// A save on its own has nothing to be compared with.
		if (self.state.saves.length < 2) {
			$('#saveActionCompare').prop('disabled', true);
		}

		renderUnreadable(self.html.unreadableSaves, self.state.unreadable || []);

		if (self.state.searching) {
			self.html.searchForSavedGames.prop('disabled', true);
			self.html.searchForSavedGames.find('i').css('display', 'inline-block');
			self.html.searchForSavedGames.find('span').text('Searching...');
		} else {
			self.html.searchForSavedGames.prop('disabled', false);
			self.html.searchForSavedGames.find('i').hide();
			self.html.searchForSavedGames.find('span').text('Search');
		}
	};
};

SaveSearch.prototype.setViewMode = function (mode) {
	var self = this;
	if (mode !== 'grid' && mode !== 'list') {
		return;
	}
	try {
		localStorage.setItem('ekSaveViewMode', mode);
	} catch (e) {
		// The view still changes for this session when storage is unavailable.
	}
	self.transition({viewMode: mode});
};

SaveSearch.prototype.loadSelected = function (info, index) {
	var self = this;
	if (!info) {
		return;
	}
	self.state.selected = index;
	self.html.saveBlocks.find('.save-info').removeClass('selected');
	self.html.saveBlocks.find('[data-save-index="' + index + '"]').addClass('selected');
	self.open(info, index);
};

SaveSearch.prototype.select = function (i) {
	var self = this;

	if (self.state.busy) {
		return;
	}

	self.transition({selected: self.state.selected === i ? -1 : i});
};

SaveSearch.prototype.search = function () {
	var self = this;
	var interval = null;
	var searchPath = this.html.savedGameLocation.val();

	var success = response => {
		clearInterval(interval);
		Eternity.Progress.render({});
		self.transition({searching: false});

		var reply = JSON.parse(response);
		var unreadable = reply.unreadable || [];
		if (reply.error) {
			// Nothing of the last search is left to open: every search starts
			// by emptying the folder its saves were unpacked into, so the tiles
			// that used to stay on screen here opened nothing.
			self.transition({saves: [], unreadable: unreadable, selected: -1});
			Eternity.GenericError.render({msg: reply.msg || 'No saves found.'});
			return;
		}

		self.transition({saves: reply.saves, unreadable: unreadable, selected: -1});
	};

	var failure = () => {
		clearInterval(interval);
		Eternity.Progress.render({});
		self.transition({searching: false});
		Eternity.GenericError.render({msg: 'The saves folder could not be searched.'});
	};

	var pollForUpdate = () => {
		window.checkExtractionProgress({
			request: "true"
			, onSuccess: response =>
				Eternity.Progress.render({percentage: JSON.parse(response).update})
			, onFailure: console.error.bind(console, 'Error checking for extraction progress.')
		});
	};

	if (searchPath.length < 1 || self.state.searching) {
		return;
	}

	Eternity.GenericError.render({});
	self.transition({searchPath: searchPath, searching: true, selected: -1});
	interval = setInterval(pollForUpdate, 1000);
	window.listSavedGames({
		request: searchPath
		, onSuccess: success
		, onFailure: failure
	});
};

SaveSearch.prototype.open = function (info, i) {
	var self = this;

	var success = response => {
		self.transition({opening: false});
		response = JSON.parse(response);

		if (response.error) {
			var msg;
			if (response.error === 'DESERIALIZATION_ERR') {
				msg = 'Error deserializing save file, please report it with your eternity.log (Settings shows where it is).';
			} else if (response.error === 'NOT_EXISTS') {
				msg = 'Save file "' + info.absolutePath + '" does not exist.';
			} else if (response.msg) {
				msg = response.msg;
			} else {
				msg = 'Unknown error when opening save file, please report it with your eternity.log (Settings shows where it is).';
			}

			Eternity.GenericError.render({msg: msg});
		} else if (response.characters.length < 1) {
			Eternity.GenericError.render({msg: 'No characters found in save game.'});
		} else {
			Eternity.SavedGame.open(response, info);
		}
	};

	var failure = (code, message) => {
		self.transition({opening: false});
		Eternity.GenericError.render({msg: 'This save could not be opened'
			+ (message ? ': ' + message : '.') + ' (' + info.absolutePath + ')'});
	};

	if (self.state.opening !== false || self.state.busy) {
		return;
	}

	Eternity.GenericError.render({});
	self.transition({opening: i, selected: i});
	window.openSavedGame({
		request: info.absolutePath
		, onSuccess: success
		, onFailure: failure
	});
};

SaveSearch.prototype.renamePrompt = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	self.html.renameSaveInput.val(info.userSaveName || info.sceneTitle || info.systemName);
	self.html.renameSaveDialog.find('.pm-dialog-subject').text(saveNameOf(info));
	self.html.renameSaveDialog.modal({backdrop: 'static', keyboard: false});
};

SaveSearch.prototype.renameConfirmed = function () {
	var self = this;
	var info = self.selectedInfo();
	var newName = self.html.renameSaveInput.val().trim();

	if (!info || newName.length < 1 || self.state.busy) {
		return;
	}

	var setBusy = busy => {
		self.state.busy = busy;
		self.html.renameSaveConfirm
			.prop('disabled', busy)
			.html(busy
				? '<i class="fa fa-spinner fa-pulse"></i> Working&hellip;'
				: '<i>&#10094;</i> Rename <i>&#10095;</i>');
		self.html.renameSaveDialog.find('[data-dismiss]').prop('disabled', busy);
	};

	var success = response => {
		setBusy(false);
		self.html.renameSaveDialog.modal('hide');
		info.userSaveName = JSON.parse(response).userSaveName;
		// A new list, so render() draws the tiles again: it redraws them only
		// when the list itself changes, not when one entry is edited in place.
		self.transition({saves: self.state.saves.slice()});
	};

	var failure = (errno, response) => {
		setBusy(false);
		self.html.renameSaveDialog.modal('hide');
		Eternity.GenericError.render({msg: response});
	};

	setBusy(true);
	window.renameSavedGame({
		request: JSON.stringify({
			savePath: self.saveFilePath(info)
			, extractedPath: info.absolutePath
			, newName: newName
		})
		, onSuccess: success
		, onFailure: failure
	});
};

SaveSearch.prototype.deletePrompt = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	self.html.deleteSaveDialog.find('.pm-dialog-subject').text(saveNameOf(info));
	self.html.deleteSaveDialog.modal({backdrop: 'static', keyboard: false});
};

SaveSearch.prototype.deleteConfirmed = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	var setBusy = busy => {
		self.state.busy = busy;
		self.html.deleteSaveConfirm
			.prop('disabled', busy)
			.html(busy
				? '<i class="fa fa-spinner fa-pulse"></i> Working&hellip;'
				: '<i>&#10094;</i> Delete <i>&#10095;</i>');
		self.html.deleteSaveDialog.find('[data-dismiss]').prop('disabled', busy);
	};

	var success = () => {
		setBusy(false);
		self.html.deleteSaveDialog.modal('hide');

		var saves = self.state.saves.slice();
		saves.splice(self.state.selected, 1);
		self.transition({saves: saves, selected: -1});
	};

	var failure = (errno, response) => {
		setBusy(false);
		self.html.deleteSaveDialog.modal('hide');
		var msg = response;
		try {
			msg = JSON.parse(response).error || response;
		} catch (e) {
			// Not JSON; show as-is.
		}
		Eternity.GenericError.render({msg: msg});
	};

	setBusy(true);
	window.deleteSavedGame({
		request: self.saveFilePath(info)
		, onSuccess: success
		, onFailure: failure
	});
};

// A save's type headers name the Unity assembly its types came from, and the
// game changed that name when it moved to Unity 2017. Everything the editor
// itself does works with either, so this only matters when a save has to be
// read by a build older than that.
SaveSearch.prototype.convertPrompt = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	self.html.convertSaveDialog.find('.pm-dialog-subject').text(saveNameOf(info));
	self.html.convertSaveConfirm.prop('disabled', true);
	self.html.convertSaveBody.empty().append(
		$('<p class="cnv-reading">').append(
			$('<i class="fa fa-spinner fa-pulse">'), ' Reading the save…'));
	self.html.convertSaveDialog.modal({backdrop: 'static', keyboard: false});

	window.convertSave({
		request: JSON.stringify({savePath: self.saveFilePath(info), convert: false})
		, onSuccess: response => self.describeFormat(JSON.parse(response))
		, onFailure: (errno, response) =>
			self.html.convertSaveBody.empty().append(
				$('<p class="cnv-problem">').text(response || 'Unable to read the save.'))
	});
};

SaveSearch.prototype.describeFormat = function (report) {
	var self = this;
	var body = self.html.convertSaveBody.empty();

	var line = (className, text) => body.append($('<p class="' + className + '">').text(text));

	if (report.format === 'LEGACY') {
		line('cnv-format', 'Already in the older format.');
		line('', 'This save names its Unity types the single-assembly way '
			+ '(UnityEngine), which every build of the game reads. There is '
			+ 'nothing to convert.');
		return;
	}

	if (report.format !== 'MODERN') {
		line('cnv-format', 'Nothing to go on.');
		line('', 'This save names no Unity types at all, so there is no format '
			+ 'to convert between.');
		return;
	}

	line('cnv-format', 'The newer format (UnityEngine.CoreModule).');
	line('', 'The game has written its type names this way since its Unity 2017 '
		+ 'update — on Steam, GOG, the Windows Store and Xbox alike. '
		+ 'Converting rewrites them to the single-assembly form that builds from '
		+ 'before that update expect.');
	line('cnv-note', 'You only need this for an old, unpatched Steam or GOG copy '
		+ 'of the game. A current one loads this save exactly as it is.');

	if (report.existing) {
		line('cnv-problem', 'There is already a converted copy at:');
		body.append($('<p class="cnv-path">').text(report.destination));
		line('', 'Move or delete it first if you want to convert this save again.');
		return;
	}

	line('', 'A converted copy will be written to:');
	body.append($('<p class="cnv-path">').text(report.destination));
	line('cnv-note', 'Your original save is not touched.');
	self.html.convertSaveConfirm.prop('disabled', false);
};

SaveSearch.prototype.convertConfirmed = function () {
	var self = this;
	var info = self.selectedInfo();

	if (!info || self.state.busy) {
		return;
	}

	var setBusy = busy => {
		self.state.busy = busy;
		self.html.convertSaveConfirm
			.prop('disabled', busy)
			.html(busy
				? '<i class="fa fa-spinner fa-pulse"></i> Converting&hellip;'
				: '<i>&#10094;</i> Convert <i>&#10095;</i>');
		self.html.convertSaveDialog.find('[data-dismiss]').prop('disabled', busy);
	};

	var success = response => {
		var result = JSON.parse(response);
		setBusy(false);
		self.html.convertSaveConfirm.prop('disabled', true);

		var body = self.html.convertSaveBody.empty();
		body.append($('<p class="cnv-format">').text('Converted.'));
		body.append($('<p>').text(
			result.replacements.toLocaleString() + ' type names rewritten across '
			+ result.files + ' files, in ' + (result.millis / 1000).toFixed(1) + ' seconds.'));
		body.append($('<p>').text('Written to:'));
		body.append($('<p class="cnv-path">').text(result.destination));
		body.append($('<p class="cnv-note">').text(
			'Your original save is untouched. Copy the converted file into the '
			+ 'other machine’s saves folder to load it there.'));
	};

	var failure = (errno, response) => {
		setBusy(false);
		self.html.convertSaveBody.empty().append(
			$('<p class="cnv-problem">').text(response || 'The conversion failed.'));
	};

	setBusy(true);
	window.convertSave({
		request: JSON.stringify({savePath: self.saveFilePath(info), convert: true})
		, onSuccess: success
		, onFailure: failure
	});
};

// Shared helper for dialog subjects.
function saveNameOf (info) {
	var userSaveName = info.userSaveName ? ' (' + info.userSaveName + ')' : '';
	return info.playerName + ' - ' + (info.sceneTitle || info.systemName) + userSaveName;
}

$.extend(SaveSearch.prototype, Renderer.prototype);
