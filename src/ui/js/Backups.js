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

// File -> Backups: the copies the editor takes before it deletes, renames or
// replaces a save in the saves folder, and a way to put one back. Restoring
// never replaces a save that is there (save/SaveBackups refuses), so the only
// thing this page has to get right is saying what each copy is.
var Backups = function () {
	var self = this;

	var defaultState = {
		loading: false
		, folder: ''
		, keep: 10
		, backups: []
		, restored: {}      // backup id -> true, once put back this session
		, restoring: null   // the id being put back
		, message: ''
		, failed: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.init = () => {
		self.html.menuBackups.click(() => self.open());
		self.html.backupsOpenFolder.click(() => self.ask({action: 'open'}, () => {}));
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		var state = self.state;

		self.html.backupsIntro.text('Before Eternity Keeper deletes, renames or replaces a save, '
			+ 'it keeps a copy here. The ' + state.keep + ' most recent of each kind are kept'
			+ (state.folder ? ', in ' + state.folder + '.' : '.'));

		var list = self.html.backupsList.empty();
		if (state.loading) {
			list.append($('<p class="bk-empty">').text('Reading the backups…'));
		} else if (state.backups.length < 1) {
			list.append($('<p class="bk-empty">').text('No backups yet. One appears here the '
				+ 'first time the editor deletes, renames or replaces a save.'));
		} else {
			state.backups.forEach(backup => list.append(self.row(backup)));
		}

		self.html.backupsStatus.text(state.message)
			.toggleClass('bk-failed', state.failed)
			.toggle(!!state.message);
	};
};

Backups.prototype.open = function () {
	this.transition({loading: true, message: '', failed: false, restored: {}});
	this.html.backupsDialog.modal('show');
	this.refresh();
};

Backups.prototype.refresh = function () {
	var self = this;
	self.ask({action: 'list'}, reply => self.transition({
		loading: false
		, folder: reply.folder
		, keep: reply.keep
		, backups: reply.backups
	}));
};

Backups.prototype.ask = function (request, then) {
	var self = this;
	window.backups({
		request: JSON.stringify(request)
		, onSuccess: response => then(JSON.parse(response))
		, onFailure: (code, message) => self.transition({
			loading: false, restoring: null, message: message, failed: true})
	});
};

Backups.prototype.restore = function (backup) {
	var self = this;
	if (self.state.restoring) {
		return;
	}

	self.transition({restoring: backup.id, message: '', failed: false});
	self.ask({action: 'restore', id: backup.id}, reply => {
		var restored = $.extend({}, self.state.restored);
		restored[backup.id] = true;

		// The save list only shows what it last read, so read it again -- but
		// only while it is on screen: searching switches to the list, and that
		// would pull the user out of a save they have open.
		var onList = !!(Eternity.state && Eternity.state.listView);
		if (onList) {
			Eternity.SaveSearch.search();
		}

		self.transition({
			restoring: null
			, restored: restored
			, failed: false
			, message: 'Put back as ' + reply.restored + '.'
				+ (onList ? '' : ' It will be on the save list the next time you search.')
		});
	});
};

Backups.prototype.row = function (backup) {
	var self = this;
	var name = backup.userSaveName || backup.sceneTitle || backup.file;
	var details = [
		backup.sceneTitle && backup.sceneTitle !== name ? backup.sceneTitle : ''
		, 'copied ' + backup.why
		, self.when(backup.time)
		, (backup.size / 1048576).toFixed(1) + ' MB'
	].filter(part => part);

	var action;
	if (self.state.restored[backup.id]) {
		action = $('<span class="bk-done">').text('Restored');
	} else {
		action = $('<button type="button" class="btn btn-primary btn-sm bk-restore">')
			.text(self.state.restoring === backup.id ? 'Restoring…' : 'Restore')
			.prop('disabled', !!self.state.restoring)
			.attr('title', 'Put this copy back as ' + backup.original)
			.click(() => self.restore(backup));
	}

	return $('<div class="bk-row">')
		.append($('<div class="bk-what">')
			.append($('<div class="bk-name">').text(name))
			.append($('<div class="bk-details">').text(details.join(' · ')))
			.append($('<div class="bk-file">').text(backup.file)))
		.append(action);
};

Backups.prototype.when = function (time) {
	var date = new Date(time);
	return date.toLocaleDateString(undefined, {day: 'numeric', month: 'short', year: 'numeric'})
		+ ', ' + date.toLocaleTimeString(undefined, {hour: '2-digit', minute: '2-digit'});
};

$.extend(Backups.prototype, Renderer.prototype);
