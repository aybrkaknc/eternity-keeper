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

// Find anything: the box at the top of the character list. One search over
// the open save -- its characters, every item wherever it is, abilities and
// talents, and the global variables -- whose results open the right tab on the
// right thing. The matching is SaveFind's; this is the box and the list.
//
// It sits in the sidebar rather than the navbar because the sidebar is the
// same 250px at every window size: the navbar's menus reach x=995, so at
// 1,100px there is no room beside them at all. The results panel lives outside
// the sidebar, which clips anything wider than itself.
var Finder = function () {
	var self = this;

	var defaultState = {
		enabled: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	var entries = null;   // the index, built by the first search after a close
	var rows = [];        // the rows on screen, in keyboard order
	var active = -1;

	var saveData = () => Eternity.SavedGame.state.saveData || {};
	var icons = () => (saveData().inventory || {}).icons || {};

	// From the save and the Inventory tab's working copy, which knows where
	// an item moved but not yet applied is now.
	var build = () => {
		entries = SaveFind.index(saveData(), Eternity.InventoryEditor.everything());
	};

	// Closing drops the index too. Nothing can be edited without leaving the
	// box, so the next search builds afresh -- and that does not hang on a
	// focus event, which a window in the background does not always get.
	var close = () => {
		self.html.finderResults.hide().empty();
		rows = [];
		active = -1;
		entries = null;
	};

	// The name with what matched in bold, built from text nodes: a name is
	// whatever the save says it is.
	var marked = (text, marks) => {
		var label = $('<span>').addClass('finder-label');
		var at = 0;

		marks.forEach(mark => {
			var start = Math.max(at, mark[0]);
			if (start > at) {
				label.append(document.createTextNode(text.slice(at, start)));
			}

			if (mark[1] > start) {
				label.append($('<b>').text(text.slice(start, mark[1])));
			}

			at = Math.max(at, mark[1]);
		});

		if (at < text.length) {
			label.append(document.createTextNode(text.slice(at)));
		}

		return label;
	};

	var highlight = index => {
		active = index;
		var panel = self.html.finderResults;
		var lines = panel.find('.finder-row').removeClass('active');
		if (index < 0) {
			return;
		}

		var line = lines.eq(index).addClass('active').get(0);
		if (!line) {
			return;
		}

		// Keep the highlighted row inside the panel's own scroll.
		var top = line.offsetTop;
		var bottom = top + line.offsetHeight;
		var view = panel.get(0);
		if (top < view.scrollTop) {
			view.scrollTop = top;
		} else if (bottom > view.scrollTop + view.clientHeight) {
			view.scrollTop = bottom - view.clientHeight;
		}
	};

	// Under the box, as wide as reads well, and never past the window.
	var place = () => {
		var box = self.html.finderInput.get(0).getBoundingClientRect();
		var top = Math.round(box.bottom + 4);
		var left = Math.round(box.left);

		self.html.finderResults.css({
			top: top
			, left: left
			, width: Math.min(460, window.innerWidth - left - 16)
			, maxHeight: Math.max(160, window.innerHeight - top - 16)
		});
	};

	var show = () => {
		var query = self.html.finderInput.val();
		var panel = self.html.finderResults.empty();
		rows = [];
		active = -1;

		if (!query.trim()) {
			close();
			return;
		}

		if (!entries) {
			build();
		}

		var result = SaveFind.search(entries, query);
		if (result.total < 1) {
			panel.append($('<div>').addClass('finder-empty')
				.text('Nothing in this save matches “' + query.trim() + '”.'));
			panel.append($('<div>').addClass('finder-note')
				.text('Vendors’ stock is not searched here: the Vendors tab lists it.'));
		}

		result.groups.forEach(group => {
			panel.append($('<div>').addClass('finder-title')
				.append($('<span>').text(group.title))
				.append($('<span>').addClass('finder-count').text(group.rows.length < group.total
					? group.rows.length + ' of ' + group.total
					: String(group.total))));

			group.rows.forEach(row => {
				var index = rows.length;
				rows.push(row);

				var line = $('<div>').addClass('finder-row finder-' + row.entry.kind)
					.attr('data-index', index);

				if (row.entry.kind === 'item') {
					var icon = icons()[row.entry.icon];
					line.append(icon
						? $('<img>').addClass('finder-icon')
							.attr('src', 'data:image/png;base64,' + icon)
						: $('<span>').addClass('finder-icon finder-icon-empty'));
				}

				line.append($('<span>').addClass('finder-text')
					.append(marked(row.entry.label, row.marks))
					.append($('<span>').addClass('finder-detail').text(row.entry.detail)));

				// mousedown rather than click: the box keeps its focus, so its
				// blur does not close the list out from under the click.
				line.on('mousedown', event => {
					event.preventDefault();
					self.open(index);
				});

				line.on('mousemove', () => {
					if (active !== index) {
						highlight(index);
					}
				});

				panel.append(line);
			});
		});

		place();
		panel.show();
		highlight(rows.length > 0 ? 0 : -1);
	};

	/** Opens result `index`: the tab it belongs to, on the character it names. */
	self.open = index => {
		var row = rows[index];
		if (!row) {
			return;
		}

		var target = row.entry.target;
		var saved = Eternity.SavedGame;
		var views = saved.views;

		self.html.finderInput.val('').blur();
		close();

		switch (row.entry.kind) {
			case 'character':
				saved.transition({activeCharacter: target.character, view: views.ATTR});
				break;

			case 'item':
				saved.transition({activeCharacter: target.character, view: views.INVENTORY});
				Eternity.InventoryEditor.reveal(target.item);
				break;

			case 'ability':
				saved.transition({activeCharacter: target.character, view: views.ABILITIES});
				Eternity.AbilityEditor.reveal(target.prefab);
				break;

			case 'global':
				// The table filters on its own box as the view opens.
				saved.html.searchGlobals.val(target.global);
				saved.transition({view: views.GLOBALS});
				break;
		}
	};

	self.init = () => {
		var input = self.html.finderInput;

		input.on('focus', () => {
			if (input.val().trim()) {
				show();
			}
		});

		input.on('input', show);
		input.on('blur', close);

		input.on('keydown', event => {
			switch (event.which) {
				case 40:
					highlight(Math.min(active + 1, rows.length - 1));
					break;

				case 38:
					highlight(Math.max(active - 1, 0));
					break;

				case 13:
					if (active >= 0) {
						self.open(active);
					}
					break;

				// Escape clears what was typed, then leaves the box. It goes
				// no further: the Inventory tab drops the item in hand on
				// Escape, and this one was not meant for it.
				case 27:
					if (input.val()) {
						input.val('');
						close();
					} else {
						input.blur();
					}

					event.stopPropagation();
					break;

				default:
					return;
			}

			event.preventDefault();
		});

		// Ctrl+F from anywhere in an open save. The list may be folded away;
		// asking to find something is reason enough to bring it back.
		$(document).on('keydown', event => {
			if (!self.state.enabled || !event.ctrlKey || event.which !== 70) {
				return;
			}

			event.preventDefault();
			if ($('body').hasClass('sidebar-collapsed')) {
				Eternity.SavedGame.html.sidebarToggle.click();
			}

			input.focus().select();
		});

		$(window).on('resize', () => {
			if (self.html.finderResults.is(':visible')) {
				place();
			}
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.finder.toggle(!!self.state.enabled);

		if (!self.state.enabled) {
			self.html.finderInput.val('');
			entries = null;
			close();
		}
	};
};

$.extend(Finder.prototype, Renderer.prototype);
