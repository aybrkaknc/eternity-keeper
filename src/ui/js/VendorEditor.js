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

// The Vendors view: what every store in the save holds, and a way to take
// some of it out.
//
// A store's stock is not in the world state. It lives in the area file of the
// level the store stands in, which nothing else in the editor reads, so the
// list is fetched on its own (getVendors) the first time this tab is shown,
// and again after every Apply here.
//
// Nothing is picked until the user picks it. Stock marked original is what
// the vendor started with -- the uniques nobody has bought yet among it -- and
// once taken out it never comes back. Everything else is what the player sold
// there, or restock the store destroys and re-rolls itself every twelve game
// hours anyway, which is what "select what you sold" means.
var VendorEditor = function () {
	var self = this;

	var ICON_BATCH = 200;

	// The game's own inventory filter categories (UIInventoryFilter), as the
	// stash uses them.
	var FILTERS = [
		{value: 1, icon: 'fa-crosshairs', label: 'Weapons'}
		, {value: 2, icon: 'fa-shield', label: 'Armour'}
		, {value: 8, icon: 'fa-user', label: 'Clothing'}
		, {value: 16, icon: 'fa-flask', label: 'Consumables'}
		, {value: 32, icon: 'fa-leaf', label: 'Ingredients'}
		, {value: 64, icon: 'fa-star', label: 'Quest items'}
		, {value: 128, icon: 'fa-ellipsis-h', label: 'Miscellaneous'}
	];

	var TOOLTIP_BREAK = String.fromCharCode(10);

	var defaultState = {
		enabled: false
		, visible: false
		, working: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	var vendors = null;
	var unreadable = [];
	var loading = false;
	var loadError = '';
	var selected = '';
	// Vendor key -> {entry's place: true}. By place, not GUID: a GUID with no
	// packet can repeat within one store, each time on a different item.
	var marked = {};
	var filter = 0;
	var showUnvisited = false;
	var status = '';
	var detail = null;
	var iconCache = {};
	var iconsRequested = {};

	var keyOf = vendor => vendor.file + '|' + vendor.id;
	var markDirty = () => Eternity.Modifications.transition({modifications: true});
	var number = n => (n || 0).toLocaleString('en-US');

	var marksAt = vendor => marked[keyOf(vendor)] || {};
	var markedCount = vendor => Object.keys(marksAt(vendor)).length;
	var totalMarked = () => (vendors || []).reduce((sum, v) => sum + markedCount(v), 0);
	var dirty = () => totalMarked() > 0;

	/** Whether anything is picked that Apply has not taken out yet. */
	self.unapplied = () => dirty();

	var byKey = key => (vendors || []).filter(v => keyOf(v) === key)[0] || null;
	var current = () => byKey(selected);

	// The ones the player has traded with come first: that is where sold
	// items pile up. A store never opened holds only what it started with.
	var listed = () => (vendors || [])
		.filter(v => v.opened || showUnvisited || markedCount(v) > 0)
		.sort((a, b) => (a.opened === b.opened ? 0 : a.opened ? -1 : 1)
			|| a.area.localeCompare(b.area) || a.name.localeCompare(b.name));

	var shown = vendor => (vendor ? vendor.items : [])
		.filter(item => !filter || item.filter === filter);

	var price = (item, count) => item.price ? item.price * count : 0;

	// ---- the list -------------------------------------------------------------

	var drawList = () => {
		var list = listed();
		self.html.vndList.empty();
		self.html.vndCount.text(vendors
			? list.length + ' of ' + vendors.length : '');

		list.forEach(vendor => {
			var notOriginal = vendor.items.filter(item => !item.original).length;
			var picked = markedCount(vendor);

			var name = $('<div>').addClass('vnd-vendor-name').text(vendor.name);
			if (picked > 0) {
				name.append($('<span>').addClass('vnd-vendor-picked')
					.attr('title', picked + ' picked for removal')
					.text('−' + number(picked)));
			}

			var count = vendor.items.length < 1
				? 'Nothing in stock'
				: number(vendor.items.length) + (vendor.items.length === 1 ? ' item' : ' items')
					+ (notOriginal > 0 ? ' · ' + number(notOriginal) + ' not original' : '');

			var row = $('<div>').addClass('vnd-vendor')
				.toggleClass('vnd-vendor-selected', keyOf(vendor) === selected)
				.toggleClass('vnd-vendor-unvisited', !vendor.opened)
				.append(name)
				.append($('<div>').addClass('vnd-vendor-where').text(vendor.area))
				.append($('<div>').addClass('vnd-vendor-count').text(count));

			row.on('click', () => {
				selected = keyOf(vendor);
				detail = null;
				redraw(status);
			});

			self.html.vndList.append(row);
		});
	};

	// ---- the stock --------------------------------------------------------------

	var iconFor = item => {
		var data = item.key ? iconCache[item.key] : null;
		return data ? 'data:image/png;base64,' + data : null;
	};

	var describe = (vendor, item) => {
		var lines = [item.displayName + (item.stackSize > 1 ? ' ×' + item.stackSize : '')];
		if (item.price) {
			lines.push(number(item.price) + ' cp each at this vendor.');
		}

		lines.push(item.original
			? 'Original stock: part of what this vendor started with. Removed, it does not come back.'
			: 'Not original stock: sold here, or restock this vendor re-rolls every 12 game hours.');

		if (marksAt(vendor)[item.index]) {
			lines.push('Picked for removal. Click again to keep it.');
		}

		return lines;
	};

	var tileFor = (vendor, item) => {
		var tile = $('<div>').addClass('inv-tile vnd-tile');
		var source = iconFor(item);
		if (source) {
			tile.append($('<img>').attr('src', source).addClass('inv-tile-icon'));
		} else {
			tile.append($('<span>').addClass('inv-tile-fallback')
				.text((item.displayName || '?').substring(0, 2)));
		}

		if (item.stackSize > 1) {
			tile.append($('<span>').addClass('inv-tile-count').text(item.stackSize));
		}

		if (item.quality) {
			tile.addClass('inv-quality-' + item.quality);
		}

		tile.toggleClass('vnd-tile-original', !!item.original)
			.toggleClass('vnd-tile-marked', !!marksAt(vendor)[item.index])
			.attr('title', describe(vendor, item).join(TOOLTIP_BREAK))
			.attr('data-index', item.index)
			.attr('data-guid', item.guid);

		tile.on('mouseenter', () => {
			detail = item.index;
			drawDetail();
		});

		// Only this tile changes, so only this tile is redrawn: the
		// stronghold's merchant alone can hold eight hundred.
		tile.on('click', () => {
			if (self.state.working) {
				return;
			}

			var marks = marked[keyOf(vendor)] = marksAt(vendor);
			if (marks[item.index]) {
				delete marks[item.index];
			} else {
				marks[item.index] = true;
			}

			tile.toggleClass('vnd-tile-marked', !!marks[item.index])
				.attr('title', describe(vendor, item).join(TOOLTIP_BREAK));

			detail = item.index;
			status = '';
			refresh();
		});

		return tile;
	};

	var drawFilters = vendor => {
		self.html.vndFilters.empty();
		FILTERS.forEach(category => {
			var count = vendor
				? vendor.items.filter(item => item.filter === category.value).length : 0;

			var button = $('<button>')
				.addClass('inv-filter')
				.attr('type', 'button')
				.attr('title', category.label + (vendor ? ' (' + count + ')' : ''))
				.prop('disabled', count < 1 && filter !== category.value)
				.toggleClass('inv-filter-on', filter === category.value)
				.append($('<i>').addClass('fa ' + category.icon));

			button.on('click', () => {
				filter = filter === category.value ? 0 : category.value;
				redraw(status);
			});

			self.html.vndFilters.append(button);
		});
	};

	var drawStock = () => {
		var vendor = current();
		self.html.vndGrid.empty();
		drawFilters(vendor);

		if (!vendor) {
			self.html.vndTitle.text('');
			self.html.vndMeta.text('');
			return;
		}

		self.html.vndTitle.text(vendor.name);
		self.html.vndMeta.text([
			vendor.area
			, vendor.opened ? 'traded with' : 'never opened'
			, 'charges ×' + (Math.round(vendor.sellMultiplier * 100) / 100)
		].filter(part => !!part).join(' · '));

		var items = shown(vendor);
		if (items.length < 1) {
			self.html.vndGrid.append($('<div>').addClass('vnd-grid-empty').text(
				vendor.items.length < 1 ? 'This vendor has nothing in stock.'
					: 'Nothing of this kind here.'));
		}

		items.forEach(item => self.html.vndGrid.append(tileFor(vendor, item)));
		requestIcons(vendor);
	};

	var drawDetail = () => {
		var vendor = current();
		var item = vendor && detail !== null ? vendor.items[detail] : null;

		self.html.vndDetail.empty();
		if (!item) {
			self.html.vndDetail.append($('<span>').addClass('vnd-detail-hint')
				.text('Point at an item to see what it is; click it to pick it for removal.'));
			return;
		}

		var lines = describe(vendor, item);
		self.html.vndDetail
			.append($('<span>').addClass('vnd-detail-name').text(lines[0]))
			.append($('<span>').addClass('vnd-detail-rest').text(lines.slice(1).join(' ')));
	};

	var summary = () => {
		var count = totalMarked();
		if (count < 1) {
			return '';
		}

		var at = (vendors || []).filter(v => markedCount(v) > 0);
		var worth = at.reduce((sum, vendor) => sum + vendor.items
			.filter(item => marksAt(vendor)[item.index])
			.reduce((s, item) => s + price(item, item.stackSize), 0), 0);

		return number(count) + (count === 1 ? ' item' : ' items') + ' picked at '
			+ at.length + (at.length === 1 ? ' vendor' : ' vendors')
			+ (worth > 0 ? ', ' + number(worth) + ' cp at their prices' : '')
			+ '. Apply takes them out of stock.';
	};

	var redraw = message => {
		if (message !== undefined) {
			status = message;
		}

		if (controls()) {
			drawList();
			drawStock();
			drawDetail();
			drawStatus();
		}
	};

	// Everything but the grid, after a change the grid already shows.
	var refresh = () => {
		if (controls()) {
			drawList();
			drawDetail();
			drawStatus();
		}
	};

	var drawStatus = () => {
		var line = [status, summary()].filter(part => !!part).join(' ');
		self.html.vndStatus.text(line).toggle(!!line);
	};

	// The message, and which buttons can be pressed. Whether there is a list
	// to draw at all.
	var controls = () => {
		var ready = !!vendors && !loading;
		self.html.vndColumns.toggle(ready && vendors.length > 0);

		var note = loading
			? 'Reading the vendors in this save’s area files…'
			: loadError
				? loadError
				: vendors && vendors.length < 1
					? 'No vendors in this save yet. A store’s stock is kept in the '
						+ 'area it stands in, from the first time the party visits.'
					: unreadable.length > 0
						? 'Could not read ' + unreadable.join(', ')
							+ ', so any vendors there are missing from this list.'
						: '';
		self.html.vndMessage.text(note).toggle(!!note);

		self.html.vndShowUnvisited.prop('checked', showUnvisited);
		self.html.vndApply.prop('disabled', !!self.state.working || !dirty());
		// The reply replaces the draft, so anything staged while an Apply is
		// on its way would be lost without a word.
		self.html.vendorsView.toggleClass('view-working', !!self.state.working);
		self.html.vndRevert.prop('disabled', !!self.state.working || !dirty());
		self.html.vndSelectEverywhere.prop('disabled', !ready || !!self.state.working);

		var vendor = current();
		var visibleItems = shown(vendor);
		self.html.vndSelectSold.prop('disabled', !!self.state.working
			|| !visibleItems.some(item => !item.original && !marksAt(vendor)[item.index]));
		self.html.vndSelectNone.prop('disabled', !!self.state.working
			|| !visibleItems.some(item => marksAt(vendor)[item.index]));

		return ready;
	};

	// ---- icons --------------------------------------------------------------------

	// The list arrives without its art: a mid-game save holds thousands of
	// items. The icons of the vendor on screen are asked for by key.
	var requestIcons = vendor => {
		if (!window.browseItems) {
			return;
		}

		var wanted = [];
		vendor.items.forEach(item => {
			if (item.key && !iconsRequested[item.key]) {
				iconsRequested[item.key] = true;
				wanted.push(item.key);
			}
		});

		for (var start = 0; start < wanted.length; start += ICON_BATCH) {
			window.browseItems({
				request: JSON.stringify({iconKeys: wanted.slice(start, start + ICON_BATCH)})
				, onSuccess: response => {
					$.extend(iconCache, JSON.parse(response).icons || {});
					if (self.state.visible) {
						redrawStockOnly();
					}
				}
				, onFailure: () => {}
			});
		}
	};

	var redrawStockOnly = () => {
		drawStock();
		drawDetail();
	};

	// ---- reading and applying --------------------------------------------------------

	var load = message => {
		if (!window.getVendors || !Eternity.SavedGame.state.info) {
			return;
		}

		loading = true;
		loadError = '';
		redraw(message);

		window.getVendors({
			request: JSON.stringify({
				oldSave: Eternity.SavedGame.state.info.absolutePath
				, savedYet: Eternity.Modifications.state.savedYet
			})
			, onSuccess: response => {
				var result = JSON.parse(response);
				loading = false;
				vendors = result.vendors || [];
				unreadable = result.unreadable || [];
				detail = null;

				if (!current()) {
					var first = listed()[0] || vendors[0];
					selected = first ? keyOf(first) : '';
				}

				redraw();
			}
			, onFailure: (code, error) => {
				loading = false;
				vendors = null;
				loadError = 'The vendors could not be read: ' + (error || 'unknown error') + '.';
				redraw();
			}
		});
	};

	self.selectNotOriginal = () => {
		var vendor = current();
		if (!vendor) {
			return;
		}

		var marks = marked[keyOf(vendor)] = marksAt(vendor);
		shown(vendor).filter(item => !item.original).forEach(item => marks[item.index] = true);
		redraw('');
	};

	self.selectNone = () => {
		var vendor = current();
		if (!vendor) {
			return;
		}

		var marks = marksAt(vendor);
		shown(vendor).forEach(item => delete marks[item.index]);
		redraw('');
	};

	// Every vendor the player has traded with; one never opened holds only
	// its original stock anyway.
	self.selectEverywhere = () => {
		(vendors || []).filter(vendor => vendor.opened).forEach(vendor => {
			var marks = marked[keyOf(vendor)] = marksAt(vendor);
			vendor.items.filter(item => !item.original).forEach(item => marks[item.index] = true);
		});

		redraw('Review any vendor on the left before applying.');
	};

	self.apply = () => {
		if (!dirty()) {
			redraw('Nothing to apply.');
			return;
		}

		var removals = (vendors || [])
			.filter(vendor => markedCount(vendor) > 0)
			.map(vendor => ({
				file: vendor.file
				, vendor: vendor.id
				, items: vendor.items
					.filter(item => marksAt(vendor)[item.index])
					.map(item => ({index: item.index, guid: item.guid}))
			}));

		var count = totalMarked();
		self.state.working = true;
		redraw('Taking ' + number(count) + (count === 1 ? ' item' : ' items') + ' out of stock…');

		window.updateVendors({
			request: JSON.stringify({
				oldSave: Eternity.SavedGame.state.info.absolutePath
				, savedYet: Eternity.Modifications.state.savedYet
				, removals: removals
			})
			, onSuccess: response => {
				self.state.working = false;
				marked = {};

				Eternity.SavedGame.transition({saveData: Eternity.SavedGame.adopt(JSON.parse(response)
					, 'Vendors: took ' + number(count) + (count === 1 ? ' item' : ' items') + ' out of '
						+ removals.length + (removals.length === 1 ? ' vendor’s' : ' vendors’') + ' stock')});
				markDirty();
				load('Took ' + number(count) + (count === 1 ? ' item' : ' items') + ' out of '
					+ removals.length + (removals.length === 1 ? ' vendor’s' : ' vendors’')
					+ ' stock. Save to write it to a file.');
			}
			, onFailure: (code, message) => {
				self.state.working = false;
				redraw(message || 'The vendors could not be updated.');
			}
		});
	};

	self.revert = () => {
		marked = {};
		redraw('Reverted.');
	};

	self.reset = () => {
		vendors = null;
		unreadable = [];
		loading = false;
		loadError = '';
		selected = '';
		marked = {};
		filter = 0;
		status = '';
		detail = null;

		// Nothing of the last save may show while the next one's list loads.
		self.html.vndList.empty();
		self.html.vndGrid.empty();
		self.html.vndTitle.text('');
		self.html.vndMeta.text('');
		self.html.vndStatus.text('');
	};

	self.init = function () {
		// The menu item is bound by SavedGame every time a save opens:
		// Editor.js calls .off() on it whenever the save list is showing, so a
		// handler attached once here would not survive going back to the list.
		self.html.vndApply.click(self.apply.bind(self));
		self.html.vndRevert.click(self.revert.bind(self));
		self.html.vndSelectSold.click(self.selectNotOriginal.bind(self));
		self.html.vndSelectNone.click(self.selectNone.bind(self));
		self.html.vndSelectEverywhere.click(self.selectEverywhere.bind(self));
		self.html.vndShowUnvisited.change(() => {
			showUnvisited = self.html.vndShowUnvisited.prop('checked');
			redraw(status);
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			self.reset();
			return;
		}

		// Reading every area file costs about a second, so it waits until
		// the tab is actually opened.
		if (!self.state.visible) {
			return;
		}

		if (vendors === null && !loading) {
			load();
			return;
		}

		redraw();
	};
};

$.extend(VendorEditor.prototype, Renderer.prototype);
