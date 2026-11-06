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

// Picking a portrait, which until now meant shuffling files around by hand.
//
// A save stores two plain strings on the Portrait component --
// m_textureLargePath and m_textureSmallPath, both relative to
// PillarsOfEternity_Data. Decompiled Portrait.Start() loads whatever they name
// and only derives a path from CompanionInstanceID when one is empty, so a
// non-empty path is used verbatim. They ride the ordinary scalar path, which is
// why picking one here is written by Save like any attribute edit, with no
// Apply step of its own.
//
// The two are a pair by convention (X_lg.png beside X_sm.png) and both are
// always written together: the party bar reads the small one and would show a
// blank white square if only the large moved.
var PortraitPicker = function () {
	var self = this;

	var PAGE = 40;

	// The directories the game keeps portraits in, given readable names. A
	// category the install has but this does not name still shows, under its
	// own directory name -- players add their own folders.
	var CATEGORY_NAMES = {
		'player/male': 'Male'
		, 'player/female': 'Female'
		, 'companion': 'Companions'
		, 'npcs': 'NPCs'
	};

	var defaultState = {
		enabled: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	var character = '';
	var portraits = [];
	var categories = [];
	var category = '';
	var offset = 0;
	var total = 0;
	var available = true;
	var loading = false;

	var saveData = () => Eternity.SavedGame.state.saveData || {};

	var currentCharacter = () =>
		(saveData().characters || []).filter(c => c.GUID === character)[0];

	var currentLargePath = () => {
		var data = currentCharacter();
		var paths = data ? data.portraitPaths : null;
		return paths && paths.m_textureLargePath
			? String(paths.m_textureLargePath.value) : '';
	};

	var humaniseCategory = key => CATEGORY_NAMES[key] || key;

	// ---- drawing -------------------------------------------------------------

	var drawFilters = () => {
		var bar = self.html.portraitFilters.empty();
		var button = (key, label) => $('<button type="button">')
			.addClass('pm-btn ptr-filter')
			.toggleClass('ptr-filter-on', category === key)
			.text(label)
			.click(() => {
				category = key;
				offset = 0;
				fetch();
			});

		bar.append(button('', 'All'));
		categories.forEach(key => bar.append(button(key, humaniseCategory(key))));
	};

	var drawGrid = () => {
		var grid = self.html.portraitGrid.empty();

		if (!available) {
			grid.append($('<div>')
				.addClass('ptr-empty')
				.text('No portraits found. The editor reads them out of the game '
					+ 'install, so check the folder in Settings.'));

			self.html.portraitMore.hide();
			return;
		}

		if (portraits.length < 1) {
			grid.append($('<div>')
				.addClass('ptr-empty')
				.text(loading ? 'Loading…' : 'No portrait matches that.'));

			self.html.portraitMore.hide();
			return;
		}

		var chosen = currentLargePath().toLowerCase();

		portraits.forEach(portrait => {
			var tile = $('<div>')
				.addClass('ptr-tile')
				.toggleClass('ptr-tile-on',
					String(portrait.large).toLowerCase() === chosen)
				.attr('title', portrait.name)
				.click(self.choose.bind(self, portrait));

			if (portrait.image) {
				tile.append($('<img>')
					.addClass('ptr-image')
					.attr('src', 'data:image/png;base64,' + portrait.image));
			} else {
				tile.append($('<span>').addClass('ptr-image ptr-image-empty'));
			}

			tile.append($('<span>').addClass('ptr-name').text(portrait.name));
			grid.append(tile);
		});

		// `portraits` accumulates every page, so it already counts the rows the
		// offset skipped. Adding the two double-counted everything past the
		// first page: with 118 portraits and a page of 40, the second page put
		// it at 40 + 80 = 120, the button hid itself at 80 of 118, and it
		// quoted "-2 left" on the way out.
		self.html.portraitMore
			.toggle(portraits.length < total)
			.text('Show more (' + (total - portraits.length) + ' left)');
	};

	var redraw = () => {
		drawFilters();
		drawGrid();

		var data = currentCharacter();
		self.html.portraitSubject.text(data ? data.name : '');
	};

	// ---- the catalog ---------------------------------------------------------

	var fetch = () => {
		if (!window.browsePortraits) {
			available = false;
			redraw();
			return;
		}

		loading = true;
		redraw();

		window.browsePortraits({
			request: JSON.stringify({
				search: self.html.portraitSearch.val() || ''
				, category: category
				, offset: offset
				, limit: PAGE
			})
			, onSuccess: response => {
				var result = JSON.parse(response);
				loading = false;
				portraits = offset > 0
					? portraits.concat(result.portraits || []) : (result.portraits || []);

				total = result.total || 0;
				categories = result.categories || [];
				available = !!result.available;
				redraw();
			}
			, onFailure: () => {
				loading = false;
				available = false;
				redraw();
			}
		});
	};

	// ---- choosing ------------------------------------------------------------

	self.choose = portrait => {
		var data = currentCharacter();
		if (!data || !data.portraitPaths) {
			return;
		}

		// Both halves, always. The large one is the character sheet, the small
		// one is the party bar, and moving only one leaves them disagreeing.
		data.portraitPaths.m_textureLargePath.value = portrait.large;
		data.portraitPaths.m_textureSmallPath.value = portrait.small;

		Eternity.Modifications.transition({modifications: true});

		// The character view draws the large image, which is seven times the
		// bytes of the thumbnail in the grid, so it is fetched only once a
		// portrait has actually been picked.
		window.browsePortraits({
			request: JSON.stringify({largePath: portrait.large})
			, onSuccess: response => {
				var result = JSON.parse(response);
				if (result.image) {
					data.portrait = result.image;
				}

				Eternity.SavedGame.transition({});
			}
			, onFailure: () => Eternity.SavedGame.transition({})
		});

		self.close();
	};

	self.open = guid => {
		character = guid;
		offset = 0;
		portraits = [];
		self.html.portraitSearch.val('');
		self.html.portraitDialog.modal('show');
		fetch();
	};

	self.close = () => {
		self.html.portraitDialog.modal('hide');
	};

	self.init = () => {
		self.html.portraitSearch.keyup(() => {
			offset = 0;
			fetch();
		});

		self.html.portraitMore.click(() => {
			offset += PAGE;
			fetch();
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			// A modal is not a .view, so nothing else hides it. Leaving a save
			// with the picker open would otherwise leave it sitting over the
			// save list -- the same leak the view list used to have.
			self.close();
			character = '';
			portraits = [];
			category = '';
			offset = 0;
		}
	};
};

$.extend(PortraitPicker.prototype, Renderer.prototype);
