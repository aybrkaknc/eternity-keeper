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

// Reading the game's own data -- item names and icons, abilities, stronghold
// upgrades, deities -- out of the player's install. The editor works without
// it, showing prefab file names instead, so this is offered rather than
// forced: a banner on the save list until it has been done, and a row in
// Settings to do it again after the game updates.
//
// The same state draws both places. The run itself happens in Java (the
// gameData handler starts the extractor and keeps its progress), so the page
// only polls while one is going.
var GameData = function () {
	var self = this;

	var POLL_MS = 1000;

	var defaultState = {
		loaded: false
		, available: false
		, game: ''
		, items: 0
		, running: false
		, percent: 0
		, text: ''
		, error: null
		, finished: false
		, dismissed: false
		, data: null
		, logFile: ''
	};

	self.state = $.extend({}, defaultState);
	self.html = {};
	self.polling = null;

	self.init = () => {
		self.html.gameDataStart.click(() => self.start());
		self.html.settingsGameDataStart.click(() => self.start());
		self.html.gameDataDismiss.click(() => self.transition({dismissed: true}));

		// No status yet: Editor asks once the install search has answered,
		// since what this offers depends on the folder it finds.
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		var state = self.state;

		self.renderBanner(state);
		self.renderSettings(state);

		if (state.running && self.polling === null) {
			self.polling = setTimeout(() => {
				self.polling = null;
				self.refresh();
			}, POLL_MS);
		}
	};
};

GameData.prototype.ask = function (action) {
	var self = this;

	window.gameData({
		request: JSON.stringify({action: action})
		, onSuccess: response => {
			var status = JSON.parse(response);
			self.transition($.extend({}, status, {
				loaded: true
				, error: status.error || null
				, data: status.data || null
				, dismissed: self.state.dismissed
			}));
		}
		, onFailure: (code, message) => console.error('Game data:', code, message)
	});
};

GameData.prototype.refresh = function () {
	this.ask('status');
};

GameData.prototype.start = function () {
	var self = this;

	if (self.state.running) {
		self.ask('cancel');
		return;
	}

	// Nothing to read from yet: the install folder is set in Settings, which
	// is its own component's dialog.
	if (!self.state.game) {
		$('#settingsDialog').modal('show');
		return;
	}

	self.transition({running: true, percent: 0, text: 'Starting', error: null
		, finished: false, dismissed: false});
	self.ask('start');
};

// Only on the save list, and only when there is something to say: nothing
// read yet, a run going, a run that failed, or one that just finished.
GameData.prototype.renderBanner = function (state) {
	var self = this;
	var banner = self.html.gameDataBanner;
	var message = self.html.gameDataMessage.empty();
	var button = self.html.gameDataStart;

	var nothingYet = state.loaded && state.available && state.items < 1;
	var show = !state.dismissed
		&& (state.running || state.error || state.finished || nothingYet);

	banner.toggle(!!show);
	banner.toggleClass('gd-failed', !!state.error && !state.running);
	banner.toggleClass('gd-done', state.finished && !state.running);
	self.html.gameDataDismiss.toggle(!state.running && (state.finished || !!state.error));

	if (!show) {
		return;
	}

	if (state.running) {
		message.append($('<strong>').text('Reading game data. '))
			.append(document.createTextNode(state.text || ''));
	} else if (state.error) {
		message.append($('<strong>').text('Reading game data failed. '))
			.append(document.createTextNode(state.error));
	} else if (state.finished) {
		message.append($('<strong>').text('Game data read. '))
			.append(document.createTextNode(
				state.items.toLocaleString() + ' items with their names and icons. '
				+ 'Saves you open now show them.'));
	} else if (!state.game) {
		message.append($('<strong>').text('Item names and icons are not loaded. '))
			.append(document.createTextNode(
				'Eternity Keeper reads them from your Pillars of Eternity install. '
				+ 'Set its folder in Settings first.'));
	} else {
		message.append($('<strong>').text('Item names and icons are not loaded yet. '))
			.append(document.createTextNode(
				'Eternity Keeper reads them, with abilities, stronghold upgrades and '
				+ 'deities, from your game install in ' + state.game + '. It takes a few '
				+ 'minutes and only needs doing once. Until then, items show their '
				+ 'file names.'));
	}

	self.renderProgress(self.html.gameDataProgress, state);
	button.toggle(!state.finished || state.running);
	button.text(self.buttonLabel(state));
};

GameData.prototype.renderSettings = function (state) {
	var self = this;
	var line = self.html.settingsGameData.empty();
	var button = self.html.settingsGameDataStart;

	if (!state.loaded) {
		return;
	}

	if (!state.available) {
		line.text('This copy of Eternity Keeper does not include the game-data reader, '
			+ 'so items show their file names.');
	} else if (state.running) {
		line.text(state.text || 'Starting');
	} else if (state.error) {
		line.text(state.error);
	} else if (state.data) {
		line.text(state.items.toLocaleString() + ' items, read from '
			+ state.data.game + ' on ' + self.when(state.data.written) + '.');
	} else if (state.items > 0) {
		line.text(state.items.toLocaleString() + ' items, from an earlier copy of the '
			+ 'editor. Read them again after the game updates.');
	} else {
		line.text('Not read yet, so items show their file names.');
	}

	line.toggleClass('text-danger', !!state.error && !state.running);

	self.html.settingsFiles.text(state.logFile
		? 'If something goes wrong, the log to report is ' + state.logFile + '.'
		: '');
	self.renderProgress(self.html.settingsGameDataProgress, state);
	button.toggle(state.available);
	button.text(self.buttonLabel(state));
};

GameData.prototype.renderProgress = function (bar, state) {
	bar.toggle(state.running);
	bar.find('.gd-bar').css('width', state.percent + '%');
	bar.attr('title', state.percent + '%');
};

GameData.prototype.buttonLabel = function (state) {
	if (state.running) {
		return 'Stop';
	}

	if (!state.game && state.items < 1) {
		return 'Open Settings';
	}

	if (state.error) {
		return 'Try again';
	}

	return state.items > 0 ? 'Read again' : 'Read game data';
};

// "2026-09-19T10:00:00" -> the date alone, in the page's locale.
GameData.prototype.when = function (written) {
	var date = new Date(written);
	return isNaN(date.getTime()) ? written : date.toLocaleDateString();
};

$.extend(GameData.prototype, Renderer.prototype);
