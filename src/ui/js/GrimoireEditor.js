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

// The Grimoire view: a wizard's spellbook laid out the way the game lays it
// out -- eight chapters, four slots to a chapter.
//
// A grimoire is an item, not a character trait. Grimoire.Find() reads the
// component off whatever sits in the wearer's grimoire slot, so the spells
// belong to the book: one in the stash is as editable as one being carried,
// and a book keeps its spells when it changes hands. That is why this view
// lists books rather than people.
//
// The four-to-a-chapter rule is the game's and it is enforced silently there:
// SerializedSpellNames' setter files each spell under its own SpellLevel and
// drops anything past the fourth without a word. The editor shows the slots so
// that limit is visible rather than surprising.
var GrimoireEditor = function () {
	var self = this;

	var MAX_SPELL_LEVEL = 8;
	var MAX_SPELLS_PER_LEVEL = 4;
	var BROWSE_PAGE = 60;

	var defaultState = {
		enabled: false
		, working: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// The working copy: grimoire guid -> array of spell entries. Staged until
	// Apply, like every other editor here.
	var contents = {};
	var selected = '';
	var status = '';

	var browseSpells = [];
	var browseTotal = 0;
	var browseOffset = 0;
	var browseAvailable = true;

	// Sorting is a server-side option because the list is paged: reordering
	// the window the client happens to hold would put level 8 spells above
	// level 1 ones the moment the second page arrived.
	var browseSort = 'level';

	var iconCache = {};
	var iconsRequested = {};

	var saveData = () => Eternity.SavedGame.state.saveData || {};
	var grimoires = () => saveData().grimoires || [];

	var markDirty = () =>
		Eternity.Modifications.transition({modifications: true});

	// ---- who is holding what ------------------------------------------------

	// The save records ownership as the holder's ObjectName on the item's own
	// packet, so turn that back into the name on the character list.
	var holderOf = grimoire => {
		var owners = (saveData().abilities || {}).characters || [];
		var match = owners.filter(owner => owner.objectName === grimoire.holder);
		if (match.length < 1) {
			return null;
		}

		var characters = saveData().characters || [];
		var found = characters.filter(c => c.GUID === match[0].guid);
		return found.length > 0 ? found[0] : null;
	};

	// Whether the holder actually has it equipped, which is the only way the
	// game will cast out of it. Equipment.HasEquipmentSlot gives the grimoire
	// slot to wizards alone, so a non-wizard carrying one cannot use it.
	var equippedBy = grimoire => {
		var inventory = saveData().inventory || {};
		var characters = inventory.characters || [];
		for (var i = 0; i < characters.length; i++) {
			var slots = (characters[i].equipment || {}).slots || [];
			for (var j = 0; j < slots.length; j++) {
				if (slots[j].slot === 'Grimoire' && slots[j].item
					&& slots[j].item.guid === grimoire.guid) {

					return characters[i];
				}
			}
		}

		return null;
	};

	// A container in the inventory payload is {component, maxItems, items},
	// not a bare array -- and the stash is one of those too.
	var itemNameOf = grimoire => {
		var inventory = saveData().inventory || {};
		var found = null;

		var scan = container => {
			var items = $.isArray(container)
				? container : (container || {}).items || [];

			items.forEach(item => {
				if (item && item.guid === grimoire.guid) found = item;
			});
		};

		(inventory.characters || []).forEach(character => {
			scan(character.pack);
			((character.equipment || {}).slots || []).forEach(slot => {
				if (slot.item) scan([slot.item]);
			});
		});

		scan(inventory.stash);
		return found;
	};

	// Where the book actually is. The Parent alone will not do: the party
	// stash hangs off Player_*, so every grimoire ever looted claims the
	// player as its holder. A real mid-game save holds dozens of them.
	var locationOf = grimoire => {
		var inventory = saveData().inventory || {};
		var characters = inventory.characters || [];

		for (var i = 0; i < characters.length; i++) {
			var slots = (characters[i].equipment || {}).slots || [];
			for (var j = 0; j < slots.length; j++) {
				if (slots[j].slot === 'Grimoire' && slots[j].item
					&& slots[j].item.guid === grimoire.guid) {

					return {rank: 0, who: characters[i], text: 'Equipped'};
				}
			}
		}

		for (var k = 0; k < characters.length; k++) {
			var items = (characters[k].pack || {}).items || [];
			for (var m = 0; m < items.length; m++) {
				if (items[m] && items[m].guid === grimoire.guid) {
					return {rank: 1, who: characters[k], text: 'Carried'};
				}
			}
		}

		var stash = (inventory.stash || {}).items || [];
		for (var n = 0; n < stash.length; n++) {
			if (stash[n] && stash[n].guid === grimoire.guid) {
				return {rank: 2, who: null, text: 'Stash'};
			}
		}

		return {rank: 3, who: null, text: 'Elsewhere'};
	};

	var nameOfCharacter = entry => {
		if (!entry || !entry.who) {
			return '';
		}

		var characters = saveData().characters || [];
		var found = characters.filter(c => c.GUID === entry.who.guid);
		return found.length > 0 ? found[0].name : '';
	};

	// Equipped books first, then carried, then the pile in the stash.
	var sortedGrimoires = () => grimoires().slice().sort((a, b) => {
		var left = locationOf(a);
		var right = locationOf(b);
		if (left.rank !== right.rank) {
			return left.rank - right.rank;
		}

		return titleOf(a).localeCompare(titleOf(b));
	});

	var titleOf = grimoire => {
		var item = itemNameOf(grimoire);
		if (item && item.displayName) {
			return item.displayName;
		}

		return String(grimoire.prefab || '')
			.replace(/_/g, ' ')
			.replace(/\s+/g, ' ')
			.trim() || 'Grimoire';
	};

	// ---- the working copy ---------------------------------------------------

	var spellsOf = guid => {
		if (contents[guid] === undefined) {
			var match = grimoires().filter(g => g.guid === guid);
			contents[guid] = match.length > 0
				? match[0].spells.slice() : [];
		}

		return contents[guid];
	};

	var storedSpells = guid => {
		var match = grimoires().filter(g => g.guid === guid);
		return match.length > 0 ? match[0].spells : [];
	};

	var dirty = () => grimoires().some(grimoire => {
		var now = (contents[grimoire.guid] || []).map(s => s.prefab).join('|');
		var was = storedSpells(grimoire.guid).map(s => s.prefab).join('|');
		return contents[grimoire.guid] !== undefined && now !== was;
	});

	/** Whether anything is staged that Apply has not written yet. */
	self.unapplied = () => dirty();

	var chapterOf = (guid, level) =>
		spellsOf(guid).filter(spell => spell.spellLevel === level);

	var hasSpell = (guid, prefab) =>
		spellsOf(guid).some(spell =>
			String(spell.prefab).toLowerCase() === String(prefab).toLowerCase());

	var addSpell = spell => {
		if (!selected || hasSpell(selected, spell.prefab)) {
			return;
		}

		if (chapterOf(selected, spell.spellLevel).length >= MAX_SPELLS_PER_LEVEL) {
			redraw('Level ' + spell.spellLevel + ' is full — a grimoire holds '
				+ MAX_SPELLS_PER_LEVEL + ' spells a level.');

			return;
		}

		spellsOf(selected).push({
			prefab: spell.prefab
			, key: spell.key
			, displayName: spell.displayName
			, description: spell.description
			, spellLevel: spell.spellLevel
			, level: spell.level
		});

		redraw('');
	};

	var removeSpell = prefab => {
		contents[selected] = spellsOf(selected).filter(spell =>
			String(spell.prefab).toLowerCase() !== String(prefab).toLowerCase());

		redraw('');
	};

	// ---- icons --------------------------------------------------------------

	var iconFor = entry => {
		if (entry.icon) return 'data:image/png;base64,' + entry.icon;
		var data = entry.key ? iconCache[entry.key] : '';
		return data ? 'data:image/png;base64,' + data : '';
	};

	var requestIcons = entries => {
		var wanted = [];
		entries.forEach(entry => {
			var key = entry.key;
			if (!key || entry.icon || iconCache[key] || iconsRequested[key]) return;
			iconsRequested[key] = true;
			wanted.push(key);
		});

		if (wanted.length < 1 || !window.browseAbilities) {
			return;
		}

		window.browseAbilities({
			request: JSON.stringify({iconKeys: wanted})
			, onSuccess: response => {
				var result = JSON.parse(response);
				var received = result.icons || {};
				var any = false;
				for (var key in received) {
					if (!received.hasOwnProperty(key)) continue;
					iconCache[key] = received[key];
					any = true;
				}

				if (any) redraw(status);
			}
			, onFailure: () => {
				// Missing art is cosmetic; leave the tiles as they are.
				wanted.forEach(key => delete iconsRequested[key]);
			}
		});
	};

	// ---- drawing ------------------------------------------------------------

	var spellTile = (entry, onRemove) => {
		var tile = $('<div>').addClass('grm-spell');
		var art = iconFor(entry);

		if (art) {
			tile.append($('<img>').addClass('grm-spell-icon').attr('src', art));
		} else {
			tile.append($('<span>')
				.addClass('grm-spell-icon grm-spell-icon-empty')
				.text(String(entry.displayName || '?').charAt(0)));
		}

		// The remove button is floated right and has to come before the name
		// in the DOM: the name is its own block formatting context, so a float
		// that follows it drops onto the next line instead of sitting beside
		// it, and the row doubles in height.
		if (onRemove) {
			tile.append($('<button>')
				.addClass('grm-spell-remove')
				.attr('type', 'button')
				.attr('title', 'Remove from this grimoire')
				.text('×')
				.click(onRemove));
		}

		tile.append($('<span>')
			.addClass('grm-spell-name')
			.text(entry.displayName || entry.prefab)
			.attr('title', entry.description || ''));

		return tile;
	};

	var drawChapters = () => {
		var chapters = self.html.grmChapters.empty();
		var spells = spellsOf(selected);
		requestIcons(spells);

		for (var level = 1; level <= MAX_SPELL_LEVEL; level++) {
			var inChapter = chapterOf(selected, level);
			var chapter = $('<div>').addClass('grm-chapter');

			chapter.append($('<div>')
				.addClass('grm-chapter-head')
				.append($('<span>').addClass('grm-chapter-level')
					.text('Level ' + level))
				.append($('<span>').addClass('grm-chapter-count')
					.text(inChapter.length + ' / ' + MAX_SPELLS_PER_LEVEL)));

			var slots = $('<div>').addClass('grm-slots');
			inChapter.forEach(spell =>
				slots.append(spellTile(spell, removeSpell.bind(null, spell.prefab))));

			for (var i = inChapter.length; i < MAX_SPELLS_PER_LEVEL; i++) {
				slots.append($('<div>').addClass('grm-spell grm-spell-empty'));
			}

			chapter.append(slots);
			chapters.append(chapter);
		}
	};

	var drawList = () => {
		var list = self.html.grmList.empty();
		var books = sortedGrimoires();

		self.html.grmBookCount.text(books.length > 0 ? books.length : '');

		books.forEach(grimoire => {
			var where = locationOf(grimoire);
			var who = nameOfCharacter(where);
			var row = $('<div>')
				.addClass('grm-book')
				.toggleClass('grm-book-selected', grimoire.guid === selected)
				.click(() => {
					selected = grimoire.guid;
					browseOffset = 0;
					redraw('');
					fetchSpells();
				});

			row.append($('<div>').addClass('grm-book-name').text(titleOf(grimoire)));
			row.append($('<div>')
				.addClass('grm-book-where')
				.toggleClass('grm-book-held', where.rank < 2)
				.text(who ? where.text + ' — ' + who : where.text));

			row.append($('<div>')
				.addClass('grm-book-count')
				.text(spellsOf(grimoire.guid).length + ' spells'
					+ (contents[grimoire.guid] !== undefined
						&& spellsOf(grimoire.guid).map(s => s.prefab).join('|')
							!== storedSpells(grimoire.guid).map(s => s.prefab).join('|')
						? ' · edited' : '')));

			list.append(row);
		});
	};

	var drawTitle = () => {
		var match = grimoires().filter(g => g.guid === selected);
		if (match.length < 1) {
			self.html.grmTitle.text('');
			self.html.grmNote.text('');
			return;
		}

		var grimoire = match[0];
		var where = locationOf(grimoire);
		var who = nameOfCharacter(where);
		self.html.grmTitle.text(titleOf(grimoire)
			+ (who ? '  ·  ' + where.text + ' by ' + who : '  ·  ' + where.text));

		var holder = holderOf(grimoire);
		var note = [];
		if (where.rank < 2 && holder && holder.stats && holder.stats.CharacterClass
			&& holder.stats.CharacterClass.value !== 'Wizard') {

			note.push(holder.name + ' is not a wizard, so the game gives them no '
				+ 'grimoire slot — this book can be carried but not read.');
		}

		note.push('A wizard casts only the spell levels their own progression has '
			+ 'reached, so a spell filed above that waits until they get there.');

		self.html.grmNote.text(note.join(' '));
	};

	var drawBrowse = () => {
		var list = self.html.grmBrowse.empty();

		if (!browseAvailable) {
			list.append($('<div>')
				.addClass('grm-browse-empty')
				.text('No spell catalog is installed, so there is nothing to add '
					+ 'from. The spells already in a grimoire still show.'));

			self.html.grmBrowseMore.hide();
			return;
		}

		requestIcons(browseSpells);

		if (browseSpells.length < 1) {
			list.append($('<div>')
				.addClass('grm-browse-empty')
				.text('No wizard spell matches that.'));
		}

		browseSpells.forEach(spell => {
			var already = selected && hasSpell(selected, spell.prefab);
			var full = selected
				&& chapterOf(selected, spell.spellLevel).length >= MAX_SPELLS_PER_LEVEL;

			var row = $('<div>').addClass('grm-browse-row');
			var art = iconFor(spell);

			if (art) {
				row.append($('<img>').addClass('grm-spell-icon').attr('src', art));
			} else {
				row.append($('<span>')
					.addClass('grm-spell-icon grm-spell-icon-empty')
					.text(String(spell.displayName || '?').charAt(0)));
			}

			row.append($('<span>')
				.addClass('grm-browse-name')
				.text(spell.displayName)
				.attr('title', spell.description || ''));

			row.append($('<span>')
				.addClass('grm-browse-level')
				.text('Level ' + spell.spellLevel));

			row.append($('<button>')
				.addClass('grm-btn grm-btn-add')
				.attr('type', 'button')
				.prop('disabled', !selected || already || full)
				.text(already ? 'In book' : (full ? 'Level full' : 'Add'))
				.click(addSpell.bind(null, spell)));

			list.append(row);
		});

		drawSort();

		// browseSpells accumulates every page, so it already counts what the
		// offset skipped -- adding the two double-counts the tail. Same
		// arithmetic slip as the portrait picker had.
		self.html.grmBrowseMore
			.toggle(browseSpells.length < browseTotal)
			.text('Show more (' + (browseTotal - browseSpells.length) + ' left)');
	};

	// By chapter or by name. Both are orders a player asks for: "what can I
	// still fit at level 3" wants the first, "where is Chill Fog" the second.
	var drawSort = () => {
		var bar = self.html.grmSort.empty();
		[['level', 'By level'], ['name', 'A–Z']].forEach(pair => {
			bar.append($('<button type="button">')
				.addClass('pm-btn grm-sort-btn')
				.toggleClass('grm-sort-on', browseSort === pair[0])
				.text(pair[1])
				.click(() => {
					if (browseSort === pair[0]) {
						return;
					}

					browseSort = pair[0];
					browseOffset = 0;
					fetchSpells();
				}));
		});
	};

	var redraw = message => {
		if (message !== undefined) {
			status = message;
		}

		if (grimoires().length < 1) {
			self.html.grmUnavailable.show();
			self.html.grmMain.hide();
			return;
		}

		self.html.grmUnavailable.hide();
		self.html.grmMain.show();

		if (!selected || grimoires().every(g => g.guid !== selected)) {
			selected = grimoires()[0].guid;
		}

		drawList();
		drawTitle();
		drawChapters();
		drawBrowse();

		self.html.grmApply.prop('disabled', !!self.state.working || !dirty());
		// The reply replaces the draft, so anything staged while an Apply is
		// on its way would be lost without a word.
		self.html.grimoireView.toggleClass('view-working', !!self.state.working);
		self.html.grmRevert.prop('disabled', !!self.state.working || !dirty());
		self.html.grmStatus.text(status || '');
		self.html.grmStatus.toggle(!!status);
	};

	// ---- the catalog --------------------------------------------------------

	var fetchSpells = () => {
		if (!window.browseAbilities) {
			return;
		}

		window.browseAbilities({
			request: JSON.stringify({
				search: self.html.grmSearch.val() || ''
				, kind: 'spell'
				// Only a wizard casts out of a grimoire; every other caster
				// knows their spells outright.
				, spellClass: 'Wizard'
				, anyClass: true
				, sort: browseSort
				, offset: browseOffset
				, limit: BROWSE_PAGE
			})
			, onSuccess: response => {
				var result = JSON.parse(response);
				var page = result.abilities || [];
				browseSpells = browseOffset > 0
					? browseSpells.concat(page) : page;

				browseTotal = result.total || 0;
				browseAvailable = !!result.available;
				redraw(status);
			}
			, onFailure: () => {
				browseAvailable = false;
				redraw('The spell catalog could not be read.');
			}
		});
	};

	// ---- applying -----------------------------------------------------------

	self.apply = () => {
		if (!dirty()) {
			redraw('Nothing to apply.');
			return;
		}

		var payload = grimoires()
			.filter(grimoire => contents[grimoire.guid] !== undefined)
			.map(grimoire => ({
				guid: grimoire.guid
				, spells: contents[grimoire.guid].map(spell => spell.prefab)
			}));

		self.state.working = true;
		redraw('Applying…');

		window.updateGrimoires({
			request: JSON.stringify({
				oldSave: Eternity.SavedGame.state.info.absolutePath
				, savedYet: Eternity.Modifications.state.savedYet
				, grimoires: payload
			})
			, onSuccess: response => {
				self.state.working = false;
				contents = {};

				var changed = payload.length;
				Eternity.SavedGame.transition({saveData: Eternity.SavedGame.adopt(JSON.parse(response)
					, 'Grimoire: ' + changed + (changed === 1 ? ' book' : ' books') + ' changed')});
				markDirty();
				redraw('Grimoire updated. Save to write it to a file.');
			}
			, onFailure: (code, message) => {
				self.state.working = false;
				redraw(message || 'The grimoire could not be updated.');
			}
		});
	};

	self.revert = () => {
		contents = {};
		redraw('Reverted.');
	};

	self.reset = () => {
		contents = {};
		selected = '';
		status = '';
		browseSpells = [];
		browseOffset = 0;
		browseTotal = 0;
	};

	self.init = function () {
		// The menu item is bound by SavedGame every time a save opens:
		// Editor.js calls .off() on it whenever the save list is showing, so a
		// handler attached once here would not survive going back to the list.
		self.html.grmApply.click(self.apply.bind(self));
		self.html.grmRevert.click(self.revert.bind(self));

		self.html.grmSearch.keyup(() => {
			browseOffset = 0;
			fetchSpells();
		});

		self.html.grmBrowseMore.click(() => {
			browseOffset += BROWSE_PAGE;
			fetchSpells();
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			self.reset();
			return;
		}

		redraw();
		if (browseSpells.length < 1) {
			fetchSpells();
		}
	};
};

$.extend(GrimoireEditor.prototype, Renderer.prototype);
