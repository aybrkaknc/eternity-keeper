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

// Two saves of the list side by side: Compare on the save list opens it with
// the save picked and the one it was made from (or the one before it in its
// playthrough), and either can be changed. The server works out what differs
// (handlers/CompareSaves, save/SaveComparison); this only draws it, with the
// formatting in SaveCompare.js.
var CompareSaves = function () {
	var self = this;

	var defaultState = {
		saves: []
		, before: -1
		, after: -1
		, loading: false
		, comparison: null
		, error: null
		, otherOpen: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.init = () => {
		self.html.saveActionCompare.click(() => self.open());
		self.html.compareBefore.change(() => self.compare(+self.html.compareBefore.val(), self.state.after));
		self.html.compareAfter.change(() => self.compare(self.state.before, +self.html.compareAfter.val()));
		self.html.compareSwap.click(() => self.compare(self.state.after, self.state.before));
		self.html.compareResults.on('click', '.cmp-section-toggle', () =>
			self.transition({otherOpen: !self.state.otherOpen}));
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		var state = self.state;

		self.html.compareBefore.val(state.before);
		self.html.compareAfter.val(state.after);
		self.html.compareSwap.prop('disabled', state.before < 0 || state.after < 0);

		var results = self.html.compareResults.empty();
		if (state.loading) {
			results.append($('<p class="cmp-reading">').append(
				$('<i class="fa fa-spinner fa-pulse">'), ' Reading both saves…'));
			return;
		}

		if (state.error) {
			results.append($('<p class="cmp-problem">').text(state.error));
			return;
		}

		if (state.comparison) {
			self.draw(results, state.comparison);
		}
	};
};

CompareSaves.prototype.open = function () {
	var search = Eternity.SaveSearch;
	var saves = search.state.saves;
	var pair = SaveCompare.pair(saves, search.state.selected);
	if (!pair) {
		return;
	}

	this.transition({saves: saves, comparison: null, error: null, otherOpen: false});
	var fill = select => {
		select.empty();
		saves.forEach((info, i) => select.append(
			$('<option>').val(i).text(SaveCompare.name(info) + ' · ' + info.date)));
	};

	fill(this.html.compareBefore);
	fill(this.html.compareAfter);
	this.html.compareSavesDialog.modal('show');
	this.compare(pair.before, pair.after);
};

CompareSaves.prototype.compare = function (before, after) {
	var self = this;
	var saves = self.state.saves;
	if (!saves[before] || !saves[after]) {
		return;
	}

	// Only the reply to the latest question counts: the pickers can change
	// while two saves are still being read.
	var ticket = ++self.asked;
	if (before === after) {
		self.transition({before: before, after: after, loading: false, comparison: null
			, error: 'Pick two different saves to compare.'});
		return;
	}

	self.transition({before: before, after: after, loading: true, comparison: null, error: null});
	window.compareSaves({
		request: JSON.stringify({before: saves[before].absolutePath, after: saves[after].absolutePath})
		, onSuccess: response => {
			if (ticket === self.asked) {
				self.transition({loading: false, comparison: JSON.parse(response)});
			}
		}
		, onFailure: (code, message) => {
			if (ticket === self.asked) {
				self.transition({loading: false, error: message || 'The two saves could not be compared.'});
			}
		}
	});
};

CompareSaves.prototype.asked = 0;

CompareSaves.prototype.draw = function (results, comparison) {
	var self = this;
	var shown = SaveCompare.rows(comparison);

	var summary = $('<div class="cmp-summary">').appendTo(results);
	summary.append($('<p class="cmp-headline">').text(shown === 0
		? 'Nothing differs between these two saves.'
		: shown + (shown === 1 ? ' difference, in ' : ' differences, in ')
			+ comparison.sections.length + (comparison.sections.length === 1 ? ' part' : ' parts')
			+ ' of the save.'));

	if (!comparison.samePlaythrough) {
		summary.append($('<p class="cmp-warning">').text('These saves are from different '
			+ 'playthroughs, so nearly everything in them differs.'));
	}

	(comparison.problems || []).forEach(problem =>
		summary.append($('<p class="cmp-problem">').text(problem)));

	var leftOut = SaveCompare.leftOut(comparison);
	if (leftOut) {
		summary.append($('<p class="cmp-leftout">').text(leftOut));
	}

	comparison.sections.forEach(section => {
		var count = section.groups.reduce((sum, group) => sum + group.rows.length, 0);
		var block = $('<div class="cmp-section">').attr('data-section', section.id).appendTo(results);
		var heading = $('<div class="cmp-section-title">').appendTo(block)
			.append($('<span>').text(section.title), ' ', $('<span class="cmp-count">').text(count));

		// Everything else is the raw remainder: there on request.
		var folded = section.id === 'other' && !self.state.otherOpen;
		if (section.id === 'other') {
			heading.addClass('cmp-section-toggle')
				.attr('title', folded ? 'Show' : 'Hide')
				.prepend($('<i class="fa">').addClass(folded ? 'fa-caret-right' : 'fa-caret-down'), ' ');
		}

		if (folded) {
			return;
		}

		section.groups.forEach(group => block.append(self.group(group, comparison.icons || {})));
	});
};

CompareSaves.prototype.group = function (group, icons) {
	var block = $('<div class="cmp-group">');
	if (group.title || group.note) {
		var title = $('<div class="cmp-group-title">').appendTo(block).text(group.title || '');
		if (group.note) {
			title.append(' ', $('<span class="cmp-group-note">').text(group.note));
		}
	}

	if (group.rows.length === 0) {
		return block;
	}

	var body = $('<tbody>');
	group.rows.forEach(row => {
		var label = $('<td class="cmp-label">');
		if (row.icon && icons[row.icon]) {
			label.append($('<img class="cmp-icon" alt="">').attr('src', 'data:image/png;base64,' + icons[row.icon]));
		}

		label.append(document.createTextNode(row.label));
		var tr = $('<tr>').append(label).appendTo(body);

		var bare = row.before === undefined && row.after === undefined;
		if (bare) {
			tr.append($('<td class="cmp-note" colspan="4">').text(row.note || 'changed'));
			return;
		}

		var change = row.change || SaveCompare.change(row);
		tr.append(
			$('<td class="cmp-before">').text(SaveCompare.show(row.before, row.format, row.after))
				.toggleClass('cmp-none', row.before === undefined)
			, $('<td class="cmp-arrow">').text('→')
			, $('<td class="cmp-after">').text(SaveCompare.show(row.after, row.format, row.before))
				.toggleClass('cmp-none', row.after === undefined)
			, $('<td class="cmp-change">').text(change)
				.toggleClass('cmp-down', change.charAt(0) === '−'));

		if (row.note) {
			$('<tr class="cmp-row-note">').append($('<td>'), $('<td colspan="4">').text(row.note)).appendTo(body);
		}
	});

	// Columns by colgroup: a fixed table takes its widths from the first row,
	// and that can be a note across four of them.
	var columns = $('<colgroup>').append(
		$('<col class="cmp-c-label">'), $('<col class="cmp-c-value">'), $('<col class="cmp-c-arrow">')
		, $('<col class="cmp-c-value">'), $('<col class="cmp-c-change">'));

	block.append($('<table class="cmp-table">').append(columns, body));
	return block;
};

$.extend(CompareSaves.prototype, Renderer.prototype);
