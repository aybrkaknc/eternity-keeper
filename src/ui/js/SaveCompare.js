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

// Comparing two saves of the list (CompareSaves.js draws the dialog): which
// save to offer beside the one picked, and how each value the server sends is
// written. Kept apart from the page so node can test it.
var SaveCompare = (function () {
	var MINUS = '−';

	// How the save list names a save.
	function name (info) {
		var user = info.userSaveName ? ' (' + info.userSaveName + ')' : '';
		return info.playerName + ' - ' + (info.sceneTitle || info.systemName) + user;
	}

	// The save to compare with the one at `index`, earlier first: the save it
	// was made from or the one before it in its playthrough, else the one
	// after, else a neighbour in the list. The editor's Save keeps the
	// timestamp of the save it was made from, so the two carry the same one.
	// Dates are the list's "yyyy-MM-dd HH:mm:ss", which sort as text.
	function pair (saves, index) {
		var picked = saves[index];
		if (!picked || saves.length < 2) {
			return null;
		}

		var others = saves.map((info, i) => i).filter(i => i !== index);
		var same = others.filter(i => saves[i].guid === picked.guid);

		// Copies of one save share its timestamp, so among those the one whose
		// name this one's extends is where it came from: "Caed Nua (edited)"
		// from "Caed Nua", and an unnamed save from the game starts any name.
		var name = i => saves[i].userSaveName || '';
		var extended = i => (picked.userSaveName || '').indexOf(name(i)) === 0 ? name(i).length : -1;
		var latest = candidates => candidates.sort((a, b) =>
			saves[a].date !== saves[b].date ? (saves[a].date < saves[b].date ? 1 : -1)
				: extended(b) - extended(a) || a - b)[0];

		var earlier = latest(same.filter(i => saves[i].date <= picked.date));
		if (earlier !== undefined) {
			return {before: earlier, after: index};
		}

		var later = same
			.filter(i => saves[i].date > picked.date)
			.sort((a, b) => saves[a].date < saves[b].date ? -1 : saves[a].date > saves[b].date ? 1 : a - b)[0];
		var other = later !== undefined ? later : index + 1 < saves.length ? index + 1 : index - 1;

		return saves[other].date < picked.date
			? {before: other, after: index}
			: {before: index, after: other};
	}

	var number = value => value.toLocaleString('en-US', {maximumFractionDigits: 2});

	// A number, unless rounding would make it look like the one it is set
	// against: then all of it, since the two do differ.
	function plain (value, other) {
		var shown = number(value);
		if (typeof other === 'number' && other !== value && number(other) === shown) {
			return String(value);
		}

		return shown;
	}

	function duration (seconds) {
		seconds = Math.abs(Math.round(seconds));
		var days = Math.floor(seconds / 86400);
		var hours = Math.floor(seconds % 86400 / 3600);
		var minutes = Math.floor(seconds % 3600 / 60);
		if (days > 0) {
			return days + ' d ' + hours + ' h';
		}

		if (hours > 0) {
			return hours + ' h ' + minutes + ' min';
		}

		return seconds < 60 ? seconds + ' s' : minutes + ' min';
	}

	// The save keeps a skill's points; rank n costs n(n+1)/2 of them.
	function rank (points) {
		var n = 0;
		while ((n + 1) * (n + 2) / 2 <= points) {
			n++;
		}

		return n;
	}

	var plural = (n, one, many) => n + ' ' + (n === 1 ? one : many);

	// One value as the dialog writes it.
	function show (value, format, other) {
		if (value === null || value === undefined) {
			return '—';
		}

		if (typeof value === 'boolean') {
			return value ? 'yes' : 'no';
		}

		if (typeof value !== 'number') {
			return String(value);
		}

		switch (format) {
			case 'money':
				return number(Math.round(value)) + ' cp';

			case 'duration':
				return duration(value);

			case 'skill':
				return 'rank ' + rank(value)
					+ (rank(value) * (rank(value) + 1) / 2 === value ? '' : ' (' + value + ' points)');

			case 'items':
				return plural(value, 'item', 'items');

			case 'count':
				return number(value);

			default:
				return plain(value, other);
		}
	}

	// How far a number moved, in the row's own terms; nothing for anything else.
	function change (row) {
		if (typeof row.before !== 'number' || typeof row.after !== 'number' || row.before === row.after) {
			return '';
		}

		var difference = row.after - row.before;
		var sign = difference < 0 ? MINUS : '+';
		var size = Math.abs(difference);

		switch (row.format) {
			case 'money':
				return Math.round(size) === 0 ? '' : sign + number(Math.round(size)) + ' cp';

			case 'duration':
				return sign + duration(size);

			case 'skill':
				var ranks = rank(row.after) - rank(row.before);
				return ranks !== 0
					? (ranks < 0 ? MINUS : '+') + plural(Math.abs(ranks), 'rank', 'ranks')
					: sign + plural(size, 'point', 'points');

			default:
				return number(size) === '0' ? '' : sign + number(size);
		}
	}

	// Every row the comparison shows.
	function rows (comparison) {
		return (comparison.sections || []).reduce((total, section) =>
			total + section.groups.reduce((sum, group) => sum + group.rows.length, 0), 0);
	}

	// What was counted rather than listed, in words.
	function leftOut (comparison) {
		var left = comparison.leftOut || {};
		var parts = [];
		if (left.positions > 0) {
			parts.push('where ' + number(left.positions) + ' '
				+ (left.positions === 1 ? 'object stands' : 'objects stand'));
		}

		if (left.bookkeeping > 0) {
			parts.push(number(left.bookkeeping) + ' '
				+ (left.bookkeeping === 1 ? 'timer or counter' : 'timers and counters')
				+ ' the game keeps updating');
		}

		if (left.maps > 0) {
			parts.push('the map explored in ' + plural(left.maps, 'area', 'areas'));
		}

		if (parts.length === 0) {
			return '';
		}

		var listed = parts.length === 1 ? parts[0]
			: parts.length === 2 ? parts[0] + ' and ' + parts[1]
			: parts.slice(0, -1).join(', ') + ', and ' + parts[parts.length - 1];

		return 'Left out: ' + listed + '.';
	}

	return {name: name, pair: pair, show: show, change: change, rows: rows, leftOut: leftOut, rank: rank};
})();

// Loaded by the editor as a plain script, and by node for its tests.
if (typeof module !== 'undefined' && module.exports) {
	module.exports = SaveCompare;
}
