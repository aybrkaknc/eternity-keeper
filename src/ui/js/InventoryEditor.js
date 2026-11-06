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

// The Inventory view, laid out like the game's own inventory screen: the
// selected character's portrait sits between their equipment slots, with quick
// items and weapon sets underneath, every party member's own 16-slot pack on
// the right, and the shared stash below.
//
// Items move the way they do in the game — click once to pick up, click again
// to drop — and double-clicking a stackable opens a quantity panel. Packs, the
// stash, the eleven equipment slots, the quick slots and the weapon sets are
// all editable. An item only goes where the game itself would put it — the
// item's own slot flags, its class restriction, whether the wearer has that
// slot at all (grimoire for wizards, head for non-godlike, pet for the
// player), two-handed weapons alone in their set, soulbound items with their
// owner, and no stack bigger than the item's own outside the stash — because a
// sword on someone's feet is exactly the sort of thing that corrupts a save.
// The server checks the same rules again before it writes anything.
var InventoryEditor = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, character: false     // GUID of the party member whose doll is shown
		, working: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// The working copy. containers maps "<characterGuid>|<component>" to
	// {maxItems, items:[...]}; original remembers where every item started so
	// Apply can send the minimal set of changes.
	var containers = {};
	var original = {};
	// Worn gear, kept apart from the lists because it's a fixed 11-slot array:
	// equipment[characterGuid] = [item|null x11]. originalEquipment is the
	// same shape, snapshotted at open time.
	var equipment = {};
	var originalEquipment = {};
	// The four weapon sets, flattened to 8 slots (primary/secondary per set).
	var weapons = {};
	var originalWeapons = {};
	var selected = null;      // {key, slot, itemGuid} of the item "in hand"
	var revealed = null;      // {guid, until}: the item the finder points at
	var stackTarget = null;   // item being edited in the quantity panel
	var stashFilter = 0;      // ItemFilterType currently toggled on, 0 = all
	var browseFilter = 0;
	var browseOffset = 0;
	var browseTotal = 0;
	var browseLimit = 60;
	var browseItems = [];
	var browseTimer = null;
	// Selling is a mode rather than a gesture: picking a dozen things to get
	// rid of is a different act from moving one item, and the two would fight
	// over the same click.
	var sellMode = false;
	var forSale = {};         // itemGuid -> true, across every pack and the stash
	var sold = {units: 0, total: 0};   // what this draft has sold: paid when it is applied
	var dropped = '';         // why a staged draft is gone, said until something is done here

	var saveData = () => Eternity.SavedGame.state.saveData || {};
	var inventory = () => saveData().inventory || {};
	var icons = () => inventory().icons || {};

	var STASH = 'StashInventory';
	var EQUIPMENT = 'Equipment';
	var QUICKBAR = 'QuickbarInventory';
	var WEAPONS = 'WeaponSets';

	// The paper doll as the game's own inventory screen draws it (read off
	// v3.9.5): a column down each side of the character. The doll faces you,
	// so the character's right hand -- and the right-hand ring -- is on your
	// left. The deprecated Cape slot is always empty and the game has no slot
	// for it, so neither does this.
	var DOLL_LEFT = ['Head', 'Chest', 'RightRing', 'Feet', 'Pet'];
	var DOLL_RIGHT = ['Neck', 'Hands', 'LeftRing', 'Waist', 'Grimoire'];

	// What the slot says under its tile. The game calls both rings "Ring".
	var SLOT_LABELS = {
		Head: 'Head', Neck: 'Neck', Chest: 'Armour', Hands: 'Hands'
		, RightRing: 'Ring', LeftRing: 'Ring', Feet: 'Feet'
		, Waist: 'Waist', Grimoire: 'Grimoire', Pet: 'Pet', Cape: 'Cape'
	};

	// And in full, where it has to be told apart: a tooltip, a refusal.
	var SLOT_TITLES = $.extend({}, SLOT_LABELS, {
		RightRing: 'Right-hand ring', LeftRing: 'Left-hand ring'
	});

	// Index into EquipmentSetSerialized, and the Equippable flag an item must
	// carry to be allowed there.
	var SLOT_INDEX = {
		Head: 0, Neck: 1, Chest: 2, Hands: 3, RightRing: 4, LeftRing: 5
		, Cape: 6, Feet: 7, Waist: 8, Grimoire: 9, Pet: 10
	};

	var SLOT_FLAG = {
		Head: 'HeadSlot', Neck: 'NeckSlot', Chest: 'ArmorSlot', Hands: 'HandSlot'
		, RightRing: 'RingRightHandSlot', LeftRing: 'RingLeftHandSlot'
		, Feet: 'FeetSlot', Waist: 'WaistSlot', Grimoire: 'GrimoireSlot'
		, Pet: 'PetSlot', Cape: ''
	};

	// The game's own inventory filter categories (UIInventoryFilter).
	var FILTERS = [
		{value: 1, icon: 'fa-crosshairs', label: 'Weapons'}
		, {value: 2, icon: 'fa-shield', label: 'Armour'}
		, {value: 8, icon: 'fa-user', label: 'Clothing'}
		, {value: 16, icon: 'fa-flask', label: 'Consumables'}
		, {value: 32, icon: 'fa-leaf', label: 'Ingredients'}
		, {value: 64, icon: 'fa-star', label: 'Quest items'}
		, {value: 128, icon: 'fa-ellipsis-h', label: 'Miscellaneous'}
	];

	// Tooltips are plain text, so the only way to break a line is the
	// character itself.
	var TOOLTIP_BREAK = String.fromCharCode(10);

	var containerKey = (characterGuid, component) => characterGuid + '|' + component;

	// ---- selling ------------------------------------------------------------

	// In sell mode a tile says whether it is ticked, and whether it could be.
	var decorateForSale = (tile, item) => {
		if (!sellMode || !item) {
			return;
		}

		if (item.quest || (item.sellValue || 0) < 1) {
			tile.addClass('inv-tile-unsellable');
			return;
		}

		tile.addClass('inv-tile-sellable');
		if (forSale[item.guid]) {
			tile.addClass('inv-tile-for-sale');
		}
	};


	// Every item a player could reasonably part with: their own packs and the
	// stash. Worn gear and quick items are left out — the game will not sell
	// something you are holding either.
	var sellableContainers = () => {
		var keys = [];
		(inventory().characters || []).forEach(character => {
			keys.push(containerKey(character.guid, character.packComponent));
			if (character.isPlayer) {
				keys.push(containerKey(character.guid, STASH));
			}
		});

		return keys.filter(key => containers[key]);
	};

	var eachSellable = visit => {
		sellableContainers().forEach(key => {
			containers[key].items.forEach(item => visit(item, key));
		});
	};

	// A store pays per item, so a stack is worth its count. The price came from
	// the catalog with the save; anything the catalog has no price for is worth
	// nothing and says so rather than quietly counting as zero.
	var sellValueOf = item =>
		(item.sellValue || 0) * Math.max(1, item.stackSize || 1);

	// The cheapest enchantment the game sells costs 1000cp
	// (EconomyManager.ItemModCostMultiplier), so anything worth less than that
	// carries no enchantment at all. That is the line "junk" is drawn on: it is
	// a property of the item rather than a guess, and it keeps the button from
	// ticking something precious.
	//
	// The Unique and soulbound flags are honoured too, but they are not enough
	// on their own — the game leaves plenty of desirable items unflagged
	// (Gaun's Pledge, a 4,050cp ring, is marked as nothing at all).
	var UNENCHANTED_BELOW = 1000;

	// ...and only worn gear counts: UIInventoryFilter.ItemFilterType WEAPONS,
	// ARMOR and CLOTHING. A potion is cheap, unenchanted and not unique, so a
	// rule written on price alone would offer to sell the party's consumables
	// in one click, and MISC is worse still — the game files grimoires (a
	// wizard's entire spellbook), pets, hides and lockpicks there alongside
	// the lore books nobody wants. What genuinely piles up unwanted is the
	// plain weapons and armour every fight leaves behind, so that is the whole
	// of what this offers; anything else is a deliberate click.
	var JUNK_FILTERS = [1, 2, 8];

	// Two kinds of gear are never junk however cheap they are. A grimoire is
	// filed under WEAPONS and costs 100cp, but it carries a wizard's whole
	// spellbook; a pet is 100cp of MISC that cannot be bought back. Both are
	// recognisable by the slot they go in rather than by their category.
	var NEVER_JUNK_SLOTS = ['GrimoireSlot', 'PetSlot'];

	var goesInAProtectedSlot = item =>
		(item.slots || []).some(slot => NEVER_JUNK_SLOTS.indexOf(slot) >= 0);

	var isJunk = item =>
		!item.quest
		&& (item.sellValue || 0) > 0
		&& (item.value || 0) < UNENCHANTED_BELOW
		&& item.quality !== 'unique'
		&& item.quality !== 'soulbound'
		&& JUNK_FILTERS.indexOf(item.filter || 0) >= 0
		&& !goesInAProtectedSlot(item);

	var sellSelection = () => {
		var items = [];
		eachSellable(item => {
			if (forSale[item.guid]) items.push(item);
		});

		return items;
	};

	// A stack of five lockpicks is five things sold, not one, so counts shown
	// to the user are in items rather than in tiles.
	var unitsIn = items =>
		items.reduce((sum, item) => sum + Math.max(1, item.stackSize || 1), 0);

	// Merging split stacks of the same thing, up to the cap the game enforces.
	// Pure tidy-up: nothing leaves the save, the entries just stop being spread
	// over several tiles.
	//
	// Each container is repacked on its own. Items cannot merge across owners —
	// that would be moving them between characters, which is a different act —
	// so a pack and the stash each end up as full as they can be.
	var tidyStacks = () => {
		var merged = 0;

		sellableContainers().forEach(key => {
			var container = containers[key];
			var groups = {};

			container.items.forEach(item => {
				var cap = item.maxStack || 1;
				if (cap < 2) return;
				(groups[item.key] = groups[item.key] || []).push(item);
			});

			Object.keys(groups).forEach(name => {
				var lots = groups[name];
				if (lots.length < 2) return;

				// The stash stacks without limit (BaseInventory.InfiniteStacking),
				// so there one stack is the tidy state whatever the item's own
				// cap. Judged by the cap, 155 lockpicks and two loose ones were
				// "32 stacks' worth" and nothing was merged.
				var cap = parseKey(key).component === STASH
					? Infinity : (lots[0].maxStack || 1);
				var total = lots.reduce((sum, item) => sum + (item.stackSize || 1), 0);
				var wanted = Math.max(1, Math.ceil(total / cap));
				if (wanted >= lots.length) return;

				merged += lots.length - wanted;

				// Fill the first few lots to the brim and drop the rest, keeping
				// the tiles they already occupy so nothing appears to jump.
				var left = total;
				lots.forEach((item, index) => {
					if (index < wanted) {
						item.stackSize = Math.min(cap, left);
						left -= item.stackSize;
						return;
					}

					container.items.splice(container.items.indexOf(item), 1);
				});
			});
		});

		return merged;
	};

	var sell = () => {
		var items = sellSelection();
		if (items.length < 1) {
			redraw('Pick something to sell first.');
			return;
		}

		// Only what has a price is sold; the rest is simply removed. Something
		// added here from the catalog has none -- the save never held it, so
		// there is nothing to be paid for -- which is also why money owed
		// always comes with something for Apply to take out of the save.
		var total = 0;
		var units = 0;
		items.forEach(item => {
			var price = sellValueOf(item);
			if (price > 0) {
				total += price;
				units += Math.max(1, item.stackSize || 1);
			}

			var key = null;
			sellableContainers().forEach(candidate => {
				if (containers[candidate].items.indexOf(item) >= 0) key = candidate;
			});

			if (key) {
				containers[key].items.splice(containers[key].items.indexOf(item), 1);
			}
		});

		// A sale is part of the draft like every other change here: the items
		// leave the save on Apply, and the money goes into the purse when that
		// Apply succeeds (apply, below). Paid at once, as it used to be, the
		// purse -- which Save writes -- held money for items the save still
		// had, and every way of dropping or keeping the draft had to remember
		// to take it back.
		sold.units += units;
		sold.total += total;

		forSale = {};
		sellMode = false;
		redraw(total > 0 ? undefined
			: 'Nothing picked has a price to sell it at, so it was removed for nothing.');
	};

	var saleWords = () =>
		sold.units + (sold.units === 1 ? ' item' : ' items') + ' for ' + sold.total + ' cp';

	var lookupCharacter = guid => {
		var match = (saveData().characters || []).filter(c => c.GUID === guid);
		return match.length > 0 ? match[0] : null;
	};

	var characterName = guid => {
		var character = lookupCharacter(guid);
		return character ? character.name : 'Unknown';
	};

	var characterPortrait = guid => {
		var character = lookupCharacter(guid);
		return character ? character.portrait : '';
	};

	// ---- working copy -------------------------------------------------------

	// Which characters the current working copy was built from. A resurrection
	// or a party change adds someone, and rebuilding only when the copy is
	// empty left them out of the view entirely — the save knew about them, the
	// screen did not.
	var builtFor = '';

	var partyFingerprint = () =>
		(inventory().characters || []).map(c => c.guid).sort().join(',');

	// Builds the draft again from the save, which throws away whatever was
	// staged, a sale included: nothing of a draft has reached the save or the
	// purse until its Apply.
	var buildWorkingCopy = () => {
		sold = {units: 0, total: 0};
		containers = {};
		original = {};
		equipment = {};
		originalEquipment = {};
		selected = null;
		builtFor = partyFingerprint();

		var data = inventory();
		(data.characters || []).forEach(character => {
			var key = containerKey(character.guid, character.packComponent);
			containers[key] = {
				maxItems: character.pack.maxItems
				, items: character.pack.items.map(item => $.extend({}, item))
			};

			containers[key].items.forEach(item => {
				original[item.guid] = {
					character: character.guid
					, component: character.packComponent
					, stackSize: item.stackSize
					, uiSlot: item.uiSlot
				};
			});

			var worn = (character.equipment.slots || []).map(
				slot => slot.item ? $.extend({}, slot.item) : null);

			equipment[character.guid] = worn;
			originalEquipment[character.guid] = worn.map(
				item => item ? item.guid : '');

			worn.forEach((item, index) => {
				if (item) {
					original[item.guid] = {
						character: character.guid
						, component: EQUIPMENT
						, equipmentSlot: index
						, stackSize: 1
						, uiSlot: -1
					};
				}
			});

			// Weapon sets are a flat array of primary/secondary pairs.
			var sets = [];
			(character.equipment.weaponSets || []).forEach(set => {
				sets[set.index * 2] = set.primary ? $.extend({}, set.primary) : null;
				sets[set.index * 2 + 1] = set.secondary ? $.extend({}, set.secondary) : null;
			});

			for (var w = 0; w < 8; w++) {
				if (sets[w] === undefined) {
					sets[w] = null;
				}
			}

			weapons[character.guid] = sets;
			originalWeapons[character.guid] = sets.map(item => item ? item.guid : '');
			sets.forEach((item, index) => {
				if (item) {
					original[item.guid] = {
						character: character.guid
						, component: EQUIPMENT
						, weaponSlot: index
						, stackSize: 1
						, uiSlot: -1
					};
				}
			});

			// The quick bar is a normal inventory, so it can join the
			// container machinery directly.
			var quickKey = containerKey(character.guid, QUICKBAR);
			containers[quickKey] = {
				maxItems: character.quickbar.maxItems || 4
				, items: character.quickbar.items.map(item => $.extend({}, item))
			};

			containers[quickKey].items.forEach(item => {
				original[item.guid] = {
					character: character.guid
					, component: QUICKBAR
					, stackSize: item.stackSize
					, uiSlot: item.uiSlot
				};
			});
		});

		var player = (data.characters || []).filter(c => c.isPlayer)[0];
		if (data.stash && player) {
			var stashKey = containerKey(player.guid, STASH);
			containers[stashKey] = {
				maxItems: data.stash.maxItems
				, items: data.stash.items.map(item => $.extend({}, item))
			};

			containers[stashKey].items.forEach(item => {
				original[item.guid] = {
					character: player.guid
					, component: STASH
					, stackSize: item.stackSize
					, uiSlot: item.uiSlot
				};
			});
		}
	};

	var findItem = guid => {
		for (var key in containers) {
			if (!containers.hasOwnProperty(key)) continue;
			var found = containers[key].items.filter(i => i.guid === guid);
			if (found.length > 0) {
				return {key: key, item: found[0]};
			}
		}

		for (var owner in equipment) {
			if (!equipment.hasOwnProperty(owner)) continue;
			for (var index = 0; index < equipment[owner].length; index++) {
				var worn = equipment[owner][index];
				if (worn && worn.guid === guid) {
					return {
						key: containerKey(owner, EQUIPMENT)
						, item: worn
						, equipmentSlot: index
					};
				}
			}
		}

		for (var wielder in weapons) {
			if (!weapons.hasOwnProperty(wielder)) continue;
			for (var w = 0; w < weapons[wielder].length; w++) {
				var held = weapons[wielder][w];
				if (held && held.guid === guid) {
					return {
						key: containerKey(wielder, WEAPONS)
						, item: held
						, weaponSlot: w
					};
				}
			}
		}

		return null;
	};

	/** Detach an item from wherever it currently sits. */
	var detach = held => {
		if (held.equipmentSlot !== undefined) {
			equipment[parseKey(held.key).character][held.equipmentSlot] = null;
		} else if (held.weaponSlot !== undefined) {
			weapons[parseKey(held.key).character][held.weaponSlot] = null;
		} else {
			containers[held.key].items =
				containers[held.key].items.filter(i => i.guid !== held.item.guid);
		}
	};

	var SET_NAMES = ['I', 'II', 'III', 'IV'];

	// BothPrimaryAndSecondarySlot is what makes a weapon two-handed.
	var isTwoHanded = item =>
		!!item && (item.slots || []).indexOf('BothPrimaryAndSecondarySlot') >= 0;

	// Equippable.WhyCantEquip: nobody but its owner can equip an item that is
	// soulbound. Carrying one is fine, which is why only slots ask.
	var soulboundRejection = (characterGuid, item) => {
		if (!item || !item.boundTo
			|| item.boundTo.toLowerCase() === characterGuid.toLowerCase()) {

			return null;
		}

		var owner = (saveData().characters || []).filter(
			c => (c.GUID || '').toLowerCase() === item.boundTo.toLowerCase())[0];

		return item.displayName + ' is soulbound to '
			+ (owner ? owner.name : 'someone else') + '.';
	};

	/**
	 * Why this item can't go into a weapon set slot beside this partner, or
	 * null when it can. The game's own rules: Equippable.CanUseSlot (the off
	 * hand takes SecondaryWeaponSlot and never a two-hander), the check in
	 * UIInventoryGridItem.ItemTransferValid that a two-hander shares its set
	 * with nothing, CharacterStats.MaxWeaponSets, class and soulbinding.
	 * Without the game data an item has no slot list, and those checks are
	 * left to the game rather than guessed.
	 */
	var weaponRejection = (characterGuid, slotIndex, item, partner) => {
		var character = characterInfo(characterGuid);
		if (!character) {
			return 'That character has no equipment.';
		}

		var set = Math.floor(slotIndex / 2);
		if (set >= weaponSetLimit(characterGuid)) {
			return characterName(characterGuid) + ' has not unlocked weapon set '
				+ SET_NAMES[set] + '.';
		}

		var bound = soulboundRejection(characterGuid, item);
		if (bound) {
			return bound;
		}

		var offHand = slotIndex % 2 === 1;
		if (item.slots) {
			if (offHand && isTwoHanded(item)) {
				return item.displayName + ' takes both hands, so it only goes in the main hand.';
			}

			var flag = offHand ? 'SecondaryWeaponSlot' : 'PrimaryWeaponSlot';
			if (item.slots.indexOf(flag) < 0) {
				return item.displayName + ' cannot go in the '
					+ (offHand ? 'off-hand' : 'main hand') + '.';
			}
		}

		if (item.classes && item.classes.length > 0
			&& item.classes.indexOf(character.characterClass) < 0) {

			return item.displayName + ' can only be used by: ' + item.classes.join(', ') + '.';
		}

		if (partner && (isTwoHanded(item) || isTwoHanded(partner))) {
			return 'Two-handed weapon requires two slots: '
				+ (isTwoHanded(item) ? item : partner).displayName
				+ ' has to be alone in its weapon set.';
		}

		return null;
	};

	var characterInfo = guid =>
		(inventory().characters || []).filter(c => c.guid === guid)[0] || null;

	/**
	 * Party order, matching the list down the left of the editor: the main
	 * character first, then everyone else alphabetically.
	 */
	var sortedCharacters = () => {
		return (inventory().characters || []).slice().sort((a, b) => {
			if (!!a.isPlayer !== !!b.isPlayer) {
				return a.isPlayer ? -1 : 1;
			}

			return characterName(a.guid).localeCompare(characterName(b.guid));
		});
	};

	/**
	 * How many weapon sets this character may actually use. Everyone starts
	 * with two; the rest come from talents, so the game reports
	 * MaxWeaponSets = 2 + BonusWeaponSets and locks the others out.
	 */
	var weaponSetLimit = guid => {
		var character = characterInfo(guid);
		return character && character.maxWeaponSets ? character.maxWeaponSets : 2;
	};

	/**
	 * Whether a character has a given slot at all. Mirrors the game's
	 * Equipment.HasEquipmentSlot: godlike have no head slot (their divine
	 * features occupy it), only wizards get a grimoire, and pets belong to the
	 * player alone. The server works this out from race and class and sends
	 * the list, so the two can't drift apart.
	 */
	// Derived from the live CharacterStats rather than from the list the
	// opener sent: the Identity panel can turn a wizard into a fighter without
	// the save being written, and a grimoire slot that outlives the class
	// would let the user equip a book the game then drops.
	var slotAvailable = (characterGuid, slotName) => {
		var character = characterInfo(characterGuid);
		if (!character) {
			return false;
		}

		var stats = (saveData().characters || []).filter(
			c => c.GUID === characterGuid)[0];

		return unavailableSlotsNow(stats, character.unavailableSlots)
			.indexOf(slotName) < 0;
	};

	/** Why this item can't go in this slot, or null when it can. */
	var rejection = (characterGuid, slotName, item) => {
		var character = characterInfo(characterGuid);
		if (!character) {
			return 'That character has no equipment.';
		}

		if (!slotAvailable(characterGuid, slotName)) {
			if (slotName === 'Head') {
				return character.name + ' is Godlike — nothing can be worn on their head.';
			}

			if (slotName === 'Grimoire') {
				return 'Only wizards carry a grimoire.';
			}

			if (slotName === 'Pet') {
				return 'Only the main character can have a pet.';
			}

			return 'That slot does not exist for this character.';
		}

		var bound = soulboundRejection(characterGuid, item);
		if (bound) {
			return bound;
		}

		var flag = SLOT_FLAG[slotName];
		if (!item.slots || item.slots.indexOf(flag) < 0) {
			return item.displayName + ' cannot be worn in the '
				+ (SLOT_TITLES[slotName] || slotName).toLowerCase() + ' slot.';
		}

		if (item.classes && item.classes.length > 0
			&& item.classes.indexOf(character.characterClass) < 0) {

			return item.displayName + ' can only be used by: ' + item.classes.join(', ') + '.';
		}

		return null;
	};

	var firstFreeSlot = container => {
		var taken = {};
		container.items.forEach(i => taken[i.uiSlot] = true);

		var slot = 0;
		while (taken[slot]) {
			slot++;
		}

		return slot;
	};

	// ---- rendering ----------------------------------------------------------

	var iconFor = item => {
		if (!item) {
			return null;
		}

		// Items from the catalog browser carry their icon inline; items from
		// the save look theirs up in the shared map sent with it.
		var data = item.icon || (item.key ? icons()[item.key] : null);
		return data ? 'data:image/png;base64,' + data : null;
	};

	var tileFor = (item, options) => {
		options = options || {};
		var tile = $('<div>').addClass('inv-tile');

		if (!item) {
			return tile.addClass('inv-tile-empty');
		}

		// What the finder looks for when it has to point at this item, and
		// the mark it leaves, kept through any redraw while it lasts.
		tile.attr('data-guid', item.guid);
		if (revealed && revealed.guid === item.guid && Date.now() < revealed.until) {
			tile.addClass('inv-tile-found');
		}

		var source = iconFor(item);
		if (source) {
			tile.append($('<img>').attr('src', source).addClass('inv-tile-icon'));
		} else {
			// No catalogued icon — fall back to initials so the tile still
			// reads as occupied.
			tile.append($('<span>').addClass('inv-tile-fallback')
				.text((item.displayName || '?').substring(0, 2)));
		}

		if (item.stackSize > 1) {
			tile.append($('<span>').addClass('inv-tile-count').text(item.stackSize));
		}

		if (item.quest) {
			tile.addClass('inv-tile-quest');
		}

		// Soulbound / unique / enchanted gear gets the game's colour coding.
		if (item.quality) {
			tile.addClass('inv-quality-' + item.quality);
		}

		// While selling, the tooltip has to answer the only question that
		// matters -- what is this worth -- rather than explain a gesture that
		// isn't available.
		var hint = options.readOnly
			? ''
			: (sellMode
				? (item.quest
					? TOOLTIP_BREAK + 'Quest item, which the game will not sell'
					: ((item.sellValue || 0) > 0
						? TOOLTIP_BREAK + 'Sells for ' + sellValueOf(item) + ' cp'
						: TOOLTIP_BREAK + 'Worth nothing to a store'))
				: TOOLTIP_BREAK + 'Click to pick up, double-click for quantity');

		tile.attr('title', (item.displayName || item.baseItem)
			+ (item.stackSize > 1 ? ' ×' + item.stackSize : '')
			+ hint);

		return tile;
	};

	var renderDoll = () => {
		var guid = self.state.character;
		var character = (inventory().characters || []).filter(c => c.guid === guid)[0];

		self.html.invCharacterName.text(characterName(guid));
		self.html.invPortrait.empty();
		self.html.invSlotsLeft.empty();
		self.html.invSlotsRight.empty();
		self.html.invQuickSlots.empty();
		self.html.invWeaponSets.empty();

		var portrait = characterPortrait(guid);
		if (portrait) {
			self.html.invPortrait.append(
				$('<img>').attr('src', 'data:image/png;base64,' + portrait));
		}

		if (!character) {
			return;
		}

		var worn = equipment[guid] || [];
		var equipmentKey = containerKey(guid, EQUIPMENT);

		var renderSlots = (host, names) => {
			names.forEach(name => {
				var index = SLOT_INDEX[name];
				var item = worn[index] || null;
				var available = slotAvailable(guid, name);
				var tile = tileFor(item);

				if (!available) {
					tile.addClass('inv-tile-blocked')
						.attr('title', rejection(guid, name, {displayName: '', slots: []}));
				} else {
					if (item && selected && selected.itemGuid === item.guid) {
						tile.addClass('inv-tile-selected');
					}

					bindTile(tile, equipmentKey, index, item);
				}

				if (!item && available) {
					tile.attr('title', SLOT_TITLES[name] || name);
				}

				host.append($('<div>').addClass('inv-equip-slot')
					.attr('data-slot', name)
					.append(tile)
					.append($('<span>').addClass('inv-slot-label')
						.text(SLOT_LABELS[name] || name)));
			});
		};

		renderSlots(self.html.invSlotsLeft, DOLL_LEFT);
		renderSlots(self.html.invSlotsRight, DOLL_RIGHT);

		var quickKey = containerKey(guid, QUICKBAR);
		var quick = containers[quickKey];
		if (quick) {
			renderGrid(self.html.invQuickSlots, quickKey, quick.maxItems);
		}

		var sets = weapons[guid] || [];
		var weaponsKey = containerKey(guid, WEAPONS);

		var usableSets = weaponSetLimit(guid);

		for (var s = 0; s < 4; s++) {
			var locked = s >= usableSets;
			var row = $('<div>').addClass('inv-weapon-set')
				.append($('<span>').addClass('inv-weapon-index')
					.text(['I', 'II', 'III', 'IV'][s]));

			if (s === character.equipment.selectedSet) {
				row.addClass('inv-weapon-set-active');
			}

			if (locked) {
				row.addClass('inv-weapon-set-locked');
			}

			(function (setIndex, isLocked) {
				[0, 1].forEach(hand => {
					var index = setIndex * 2 + hand;
					var item = sets[index] || null;
					var tile = tileFor(item);

					if (isLocked) {
						tile.addClass('inv-tile-blocked').attr('title',
							characterName(guid) + ' has not unlocked weapon set '
							+ ['I', 'II', 'III', 'IV'][setIndex]
							+ ' — it takes a talent to open the third and fourth.');
					} else {
						if (item && selected && selected.itemGuid === item.guid) {
							tile.addClass('inv-tile-selected');
						}

						bindTile(tile, weaponsKey, index, item);
					}

					row.append(tile);
				});
			})(s, locked);

			self.html.invWeaponSets.append(row);
		}
	};

	// A single click redraws the grid, which would tear the tile out of the
	// DOM before its dblclick ever fired. Hold the click back briefly and drop
	// it if a second one arrives, so both gestures work on the same tile.
	var clickTimer = null;

	var bindTile = (tile, key, slot, item) => {
		tile.on('click', () => {
			if (clickTimer) {
				return;
			}

			clickTimer = window.setTimeout(() => {
				clickTimer = null;
				onTileClick(key, slot, item);
			}, 220);
		});

		tile.on('dblclick', () => {
			if (clickTimer) {
				window.clearTimeout(clickTimer);
				clickTimer = null;
			}

			onTileDoubleClick(item);
		});
	};

	var renderGrid = (host, key, slotCount) => {
		var container = containers[key];
		if (!container) {
			return;
		}

		var bySlot = {};
		container.items.forEach(item => bySlot[item.uiSlot] = item);

		for (var slot = 0; slot < slotCount; slot++) {
			var item = bySlot[slot] || null;
			var tile = tileFor(item);

			if (item && selected && selected.itemGuid === item.guid) {
				tile.addClass('inv-tile-selected');
			}

			decorateForSale(tile, item);
			bindTile(tile, key, slot, item);
			host.append(tile);
		}
	};

	var renderPacks = () => {
		self.html.invPacks.empty();

		sortedCharacters().forEach(character => {
			var key = containerKey(character.guid, character.packComponent);
			var container = containers[key];
			if (!container) {
				return;
			}

			var row = $('<div>').addClass('inv-pack-row');
			if (character.guid === self.state.character) {
				row.addClass('inv-pack-row-active');
			}

			var head = $('<div>').addClass('inv-pack-owner')
				.append($('<span>').addClass('inv-pack-owner-name')
					.text(characterName(character.guid)))
				.append($('<span>').addClass('inv-count')
					.text(container.items.length + '/' + container.maxItems))
				.on('click', () => Eternity.SavedGame.switchCharacter(character.guid));

			var portrait = characterPortrait(character.guid);
			if (portrait) {
				head.prepend($('<img>').addClass('inv-pack-portrait')
					.attr('src', 'data:image/png;base64,' + portrait));
			}

			var grid = $('<div>').addClass('inv-grid');
			renderGrid(grid, key, container.maxItems);

			self.html.invPacks.append(row.append(head).append(grid));
		});
	};

	var renderStash = () => {
		var player = (inventory().characters || []).filter(c => c.isPlayer)[0];
		self.html.invStashGrid.empty();

		if (!player) {
			return;
		}

		var key = containerKey(player.guid, STASH);
		var container = containers[key];
		if (!container) {
			return;
		}

		renderFilters();

		var filter = (self.html.invStashSearch.val() || '').toLowerCase();
		var sorted = container.items.slice()
			.sort((a, b) => (a.displayName || '').localeCompare(b.displayName || ''));

		// With a category picked, the game groups that type first; we simply
		// show only that type and fall back to everything when it's cleared.
		if (stashFilter) {
			sorted = sorted.filter(item => (item.filter || 0) === stashFilter);
		}

		var shown = 0;
		sorted.forEach(item => {
			if (filter && (item.displayName || '').toLowerCase().indexOf(filter) < 0) {
				return;
			}

			var tile = tileFor(item);
			if (selected && selected.itemGuid === item.guid) {
				tile.addClass('inv-tile-selected');
			}

			decorateForSale(tile, item);
			bindTile(tile, key, item.uiSlot, item);
			self.html.invStashGrid.append(tile);
			shown++;
		});

		// How many are on screen, when a category or the search hides the rest:
		// "226 items" over a dozen tiles read as a stash that had lost things.
		var total = container.items.length;
		self.html.invStashCount.text(shown < total
			? shown + ' of ' + total + ' items' : total + ' items');

		// The stash is effectively unlimited, so instead of drawing a fixed
		// grid we leave one spare tile to drop things onto.
		if (!filter) {
			var empty = $('<div>').addClass('inv-tile inv-tile-empty');
			empty.on('click', () => onTileClick(key, -1, null));
			self.html.invStashGrid.append(empty);
		}
	};

	// Only on screen while selling, so the panel keeps its usual shape the rest
	// of the time.
	var renderSellBar = () => {
		self.html.invSellMode.toggleClass('inv-mode-on', sellMode);
		self.html.invSellMode.text(sellMode ? 'Done selling' : 'Sell items');

		if (!sellMode) {
			self.html.invSellBar.hide();
			return;
		}

		var items = sellSelection();
		var total = 0;
		items.forEach(item => total += sellValueOf(item));

		var sellable = 0;
		eachSellable(item => { if (isJunk(item)) sellable++; });

		self.html.invSellBar.show();
		self.html.invSellSummary.text(items.length < 1
			? 'Click items to sell them — ' + sellable
				+ ' unenchanted piece(s) of gear look like junk'
			: unitsIn(items) + ' item(s) selected · +' + total
				+ " cp to the party's money");

		self.html.invSellConfirm.prop('disabled', items.length < 1);
	};

	// The game's category filters: nothing is selected to begin with, clicking
	// an icon narrows the stash to that type, and clicking it again clears it.
	var renderFilters = () => {
		self.html.invStashFilters.empty();

		FILTERS.forEach(category => {
			var button = $('<button>')
				.addClass('inv-filter')
				.attr('type', 'button')
				.attr('title', category.label)
				.append($('<i>').addClass('fa ' + category.icon));

			if (stashFilter === category.value) {
				button.addClass('inv-filter-on');
			}

			button.on('click', () => {
				stashFilter = stashFilter === category.value ? 0 : category.value;
				renderStash();
			});

			self.html.invStashFilters.append(button);
		});
	};

	// What you're holding sits right between the equipment panel and the party
	// packs, where the eye already is while moving things around.
	var renderCarry = () => {
		var bar = self.html.invCarry.empty();

		if (!selected) {
			bar.removeClass('inv-carry-on');
			return;
		}

		var held = findItem(selected.itemGuid);
		if (!held) {
			bar.removeClass('inv-carry-on');
			return;
		}

		var source = iconFor(held.item);
		bar.addClass('inv-carry-on')
			.append($('<span>').addClass('inv-carry-label').text('Carrying'));

		if (source) {
			bar.append($('<img>').addClass('inv-carry-icon').attr('src', source));
		}

		bar.append($('<span>').addClass('inv-carry-name').text(held.item.displayName))
			.append($('<span>').addClass('inv-carry-hint')
				.text('click a slot to place it · Esc to put it back'))
			.append($('<button>').addClass('inv-carry-drop').attr('type', 'button')
				.text('Cancel')
				.on('click', () => {
					selected = null;
					redraw();
				}));
	};

	/**
	 * The slots follow the live CharacterStats, which the Identity panel can
	 * change before the save has been written. That is the point -- turning
	 * someone into a wizard should open their grimoire slot straight away --
	 * but the two halves reach the disk by different routes: inventory changes
	 * are written by Apply here, identity changes by Save. Applying first and
	 * never saving would leave a book in a slot the wearer has no access to,
	 * and nothing in the game puts it back: RepairSaveLoadEquipmentErrors
	 * handles the deprecated Cape and locked slots only. So say so.
	 */
	var identityWarning = () => {
		var guid = self.state.character;
		var info = characterInfo(guid);
		var stats = (saveData().characters || []).filter(c => c.GUID === guid)[0];
		if (!info || !stats) {
			return null;
		}

		var was = (info.unavailableSlots || []).slice().sort().join(',');
		var now = unavailableSlotsNow(stats, info.unavailableSlots).sort().join(',');
		if (was === now) {
			return null;
		}

		return 'These slots follow an unsaved change to '
			+ characterName(guid) + '\u2019s class or race. Save the character '
			+ 'edit too \u2014 Apply here writes the items, but only Save writes '
			+ 'who they belong to.';
	};

	// A staged sale is the one staged change with nothing left on screen to
	// show for it, so the status line keeps saying it until it is applied.
	var saleNote = () => sold.units < 1 ? null
		: 'Sold ' + saleWords() + '. Apply changes takes '
			+ (sold.units === 1 ? 'it' : 'them') + ' out of the save and adds the money.';

	var renderStatus = message => {
		var warning = identityWarning();
		var staged = message ? null : saleNote();
		var text = message || staged || warning;

		if (text) {
			self.html.invStatus.text(text)
				.toggleClass('inv-status-on', !!message || !!staged)
				.toggleClass('inv-status-warn', !message && !staged && !!warning)
				.show();

			return;
		}

		self.html.invStatus.text('')
			.removeClass('inv-status-on inv-status-warn').hide();
	};

	var draw = message => {
		renderDoll();
		renderCarry();
		renderPacks();
		renderStash();
		renderSellBar();
		renderBrowseTargets();
		renderStatus(message);
		self.html.invApply.prop('disabled', !!self.state.working);
		// The reply replaces the draft, so anything staged while an Apply is
		// on its way would be lost without a word.
		self.html.inventoryView.toggleClass('view-working', !!self.state.working);
		renderLoadoutButtons();
	};

	// Whatever is done in the tab draws it again. A note that the draft was
	// dropped stays until then: one save-view render follows another, and said
	// once it was gone before it could be read.
	var redraw = message => {
		dropped = '';
		draw(message);
	};

	// ---- the "every item in the game" browser --------------------------------

	var renderBrowseTargets = () => {
		var select = self.html.invBrowseTarget;
		var chosen = select.val();
		select.empty();

		sortedCharacters().forEach(character => {
			select.append($('<option>')
				.val(containerKey(character.guid, character.packComponent))
				.text(characterName(character.guid)));
		});

		var player = (inventory().characters || []).filter(c => c.isPlayer)[0];
		if (player) {
			select.append($('<option>')
				.val(containerKey(player.guid, STASH))
				.text('Stash'));
		}

		if (chosen) {
			select.val(chosen);
		}
	};

	// The browser shows exactly two rows, so a page is however many tiles fit
	// across the grid twice over — which changes with the window size.
	var TILE_SPAN = 46;      // 42px tile + 4px margin
	var BROWSE_ROWS = 2;

	var browsePageSize = () => {
		var width = self.html.invBrowseGrid.width() || 0;
		var columns = Math.max(1, Math.floor(width / TILE_SPAN));
		return columns * BROWSE_ROWS;
	};

	var requestBrowse = () => {
		if (!window.browseItems) {
			return;
		}

		browseLimit = browsePageSize();

		window.browseItems({
			request: JSON.stringify({
				search: self.html.invBrowseSearch.val() || ''
				, filter: browseFilter
				, offset: browseOffset
				, limit: browseLimit
			})
			, onSuccess: response => {
				var page = JSON.parse(response);
				browseItems = page.items || [];
				browseTotal = page.total || 0;
				renderBrowse(page.available);
			}
			, onFailure: () => {
				browseItems = [];
				browseTotal = 0;
				renderBrowse(false);
			}
		});
	};

	var scheduleBrowse = () => {
		window.clearTimeout(browseTimer);
		browseTimer = window.setTimeout(() => {
			browseOffset = 0;
			requestBrowse();
		}, 250);
	};

	var renderBrowse = available => {
		var grid = self.html.invBrowseGrid.empty();

		if (!available) {
			self.html.invBrowseCount.text('no item catalog installed');
			self.html.invBrowsePage.text('');
			return;
		}

		self.html.invBrowseCount.text(browseTotal + ' items');
		self.html.invBrowsePage.text(browseTotal < 1
			? ''
			: (browseOffset + 1) + '–' + Math.min(browseOffset + browseLimit, browseTotal)
				+ ' of ' + browseTotal);

		self.html.invBrowsePrev.prop('disabled', browseOffset <= 0);
		self.html.invBrowseNext.prop('disabled', browseOffset + browseLimit >= browseTotal);

		browseItems.forEach(item => {
			var tile = tileFor(item, {readOnly: true});
			tile.attr('title', item.displayName + '\nClick to add a copy');
			tile.on('click', () => addFromCatalog(item));
			grid.append(tile);
		});

		renderBrowseFilters();
	};

	var renderBrowseFilters = () => {
		var host = self.html.invBrowseFilters.empty();

		FILTERS.forEach(category => {
			var button = $('<button>')
				.addClass('inv-filter')
				.attr('type', 'button')
				.attr('title', category.label)
				.append($('<i>').addClass('fa ' + category.icon));

			if (browseFilter === category.value) {
				button.addClass('inv-filter-on');
			}

			button.on('click', () => {
				browseFilter = browseFilter === category.value ? 0 : category.value;
				browseOffset = 0;
				requestBrowse();
			});

			host.append(button);
		});
	};

	// Adding is staged like everything else; nothing touches the save until
	// Apply. A placeholder GUID is minted here and the server creates the
	// item's real object when the change is applied.
	var addFromCatalog = item => {
		var target = self.html.invBrowseTarget.val();
		if (!target) {
			redraw('Pick who should receive the item first.');
			return;
		}

		var container = containers[target];
		if (!container) {
			redraw('That container is not available.');
			return;
		}

		if (container.items.length >= container.maxItems) {
			redraw(characterName(parseKey(target).character) + "'s pack is full.");
			return;
		}

		var copy = $.extend({}, item);
		copy.guid = newGuid();
		copy.stackSize = 1;
		copy.uiSlot = firstFreeSlot(container);
		copy.isNew = true;

		container.items.push(copy);
		redraw('Added ' + item.displayName + ' — Apply changes to write it to the save.');
	};

	// Plain RFC-4122 v4; the save only needs it to be unique.
	var newGuid = () =>
		'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
			var r = Math.random() * 16 | 0;
			return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
		});

	// ---- interaction --------------------------------------------------------

	var onTileClick = (key, slot, item) => {
		if (sellMode) {
			if (!item) {
				return;
			}

			if (item.quest) {
				redraw(item.displayName + ' is a quest item — the game won’t sell it.');
				return;
			}

			if (forSale[item.guid]) {
				delete forSale[item.guid];
			} else {
				forSale[item.guid] = true;
			}

			redraw();
			return;
		}

		if (!selected) {
			if (item) {
				selected = {key: key, itemGuid: item.guid};
				redraw();
			}

			return;
		}

		// Clicking the held item again puts it back down.
		if (item && item.guid === selected.itemGuid) {
			selected = null;
			redraw();
			return;
		}

		var held = findItem(selected.itemGuid);
		if (!held) {
			selected = null;
			redraw();
			return;
		}

		var target = parseKey(key);
		if (target.component === EQUIPMENT) {
			dropOnEquipment(held, target.character, slot);
			return;
		}

		if (target.component === WEAPONS) {
			dropOnWeaponSet(held, target.character, slot);
			return;
		}

		// Coming off a character rather than out of a pack.
		if (held.equipmentSlot !== undefined || held.weaponSlot !== undefined) {
			var pack = containers[key];
			if (pack.items.length >= pack.maxItems) {
				redraw(fullMessage(key));
				return;
			}

			detach(held);
			held.item.stackSize = 1;
			pack.items.push(held.item);
			held.item.uiSlot = slotOrFirstFree(pack, held.item, slot);

			selected = null;
			redraw();
			return;
		}

		var source = containers[held.key];
		var destination = containers[key];

		if (held.key !== key) {
			if (destination.items.length >= destination.maxItems) {
				redraw(fullMessage(key));
				return;
			}

			// Only the stash stacks without limit (BaseInventory.
			// InfiniteStacking). Anywhere else the game takes MaxStackSize and
			// leaves the rest where it was (UIInventoryGridItem.TryMergeInto),
			// and the part that moves is a new object -- minted on Apply like
			// an item from the catalog.
			var cap = held.item.maxStack || 1;
			if (target.component !== STASH && held.item.stackSize > cap) {
				var part = $.extend({}, held.item
					, {guid: newGuid(), stackSize: cap, isNew: true});

				part.uiSlot = slotOrFirstFree(destination, part, slot);
				destination.items.push(part);
				held.item.stackSize -= cap;

				selected = null;
				redraw(held.item.displayName + ' stacks to ' + cap + ' outside the stash, so '
					+ cap + ' moved and ' + held.item.stackSize + ' stayed where they were.');
				return;
			}

			source.items = source.items.filter(i => i.guid !== held.item.guid);
			destination.items.push(held.item);
		}

		held.item.uiSlot = slotOrFirstFree(destination, held.item, slot);

		selected = null;
		redraw();
	};

	var parseKey = key => {
		var parts = key.split('|');
		return {character: parts[0], component: parts[1]};
	};

	var fullMessage = key => {
		var where = parseKey(key);
		if (where.component === STASH) {
			return 'The stash is full.';
		}

		return characterName(where.character)
			+ (where.component === QUICKBAR ? '’s quick slots are full' : '’s pack is full')
			+ ' — free a slot first.';
	};

	// Land on the clicked tile when it's free, otherwise take the lowest free
	// one, mirroring the game's own placement.
	var slotOrFirstFree = (container, item, slot) => {
		var occupied = container.items
			.filter(i => i.guid !== item.guid && i.uiSlot === slot).length > 0;

		return (slot >= 0 && !occupied) ? slot : firstFreeSlot(container);
	};

	var slotNameOf = index => Object.keys(SLOT_INDEX)
		.filter(name => SLOT_INDEX[name] === index)[0];

	/**
	 * A swap sends whatever was in the target back to where the held item came
	 * from, so that item has to be allowed there too: a soulbound sceptre
	 * knocked out of its owner's hands must not land in someone else's.
	 * Anything going back into a pack is fine -- a swap never changes a
	 * pack's count.
	 */
	var displacedRejection = (held, displaced, target) => {
		if (!displaced) {
			return null;
		}

		var origin = parseKey(held.key).character;

		if (held.weaponSlot !== undefined) {
			// Once the swap is done, the origin's partner is whatever sits
			// there then -- the held item itself, if both are in one set.
			var partnerIndex = held.weaponSlot ^ 1;
			var partner = target.weaponSet && target.character === origin
				&& target.index === partnerIndex
				? held.item
				: weapons[origin][partnerIndex];

			return weaponRejection(origin, held.weaponSlot, displaced, partner);
		}

		if (held.equipmentSlot !== undefined) {
			return rejection(origin, slotNameOf(held.equipmentSlot), displaced);
		}

		return null;
	};

	/** Put the held item on a character, swapping out whatever is there. */
	var dropOnEquipment = (held, characterGuid, slotIndex) => {
		var why = rejection(characterGuid, slotNameOf(slotIndex), held.item)
			|| displacedRejection(held, equipment[characterGuid][slotIndex]
				, {weaponSet: false, character: characterGuid, index: slotIndex});

		if (why) {
			redraw(why);
			return;
		}

		swapInto(equipment[characterGuid], slotIndex, held);
	};

	/** Same idea for the four weapon sets, whose two halves depend on each other. */
	var dropOnWeaponSet = (held, characterGuid, slotIndex) => {
		var sets = weapons[characterGuid];
		var displaced = sets[slotIndex];

		// The partner once the swap is done: moving within one set sends the
		// displaced item into the slot the held one left.
		var partner = sets[slotIndex ^ 1];
		if (partner && partner.guid === held.item.guid) {
			partner = displaced;
		}

		var why = weaponRejection(characterGuid, slotIndex, held.item, partner)
			|| displacedRejection(held, displaced
				, {weaponSet: true, character: characterGuid, index: slotIndex});

		if (why) {
			redraw(why);
			return;
		}

		swapInto(sets, slotIndex, held);
	};

	// Put the held item into slot N of a fixed-size array, sending whatever was
	// there back to wherever the held item came from.
	var swapInto = (slots, slotIndex, held) => {
		var displaced = slots[slotIndex];
		var origin = held.key;
		var originEquipment = held.equipmentSlot;
		var originWeapon = held.weaponSlot;

		detach(held);
		slots[slotIndex] = held.item;
		held.item.stackSize = 1;

		if (displaced) {
			if (originEquipment !== undefined) {
				equipment[parseKey(origin).character][originEquipment] = displaced;
			} else if (originWeapon !== undefined) {
				weapons[parseKey(origin).character][originWeapon] = displaced;
			} else {
				var pack = containers[origin];
				pack.items.push(displaced);
				displaced.uiSlot = firstFreeSlot(pack);
			}
		}

		selected = null;
		redraw();
	};

	// The stash stacks without limit (BaseInventory.InfiniteStacking);
	// everywhere else an item holds its MaxStackSize at most. Clamping a stash
	// stack to the item's cap turned "open the panel, press OK" into deleting
	// all but five of 155 lockpicks.
	var stackCapFor = item => {
		var located = findItem(item.guid);
		return located && parseKey(located.key).component === STASH
			? Infinity : (item.maxStack || 1);
	};

	var onTileDoubleClick = item => {
		if (!item) {
			return;
		}

		// Worn gear is always a single item and deleting it from here would
		// leave the slot pointing at nothing.
		var located = findItem(item.guid);
		if (located && located.equipmentSlot !== undefined) {
			selected = null;
			redraw('Take ' + item.displayName + ' off first to change it.');
			return;
		}

		// Only stackables get a quantity panel; for everything else the count
		// is always one and there is nothing to choose.
		if (!item.maxStack || item.maxStack <= 1) {
			selected = null;
			redraw(item.displayName + ' doesn’t stack.');
			return;
		}

		selected = null;
		stackTarget = item;
		var cap = stackCapFor(item);
		self.html.stackDialogTitle.text(item.displayName);
		self.html.stackAmount.val(item.stackSize);

		if (cap === Infinity) {
			self.html.stackAmount.removeAttr('max');
			self.html.stackHint.text('The stash holds any number — set 0 to remove the item entirely.');
		} else {
			self.html.stackAmount.attr('max', cap);
			self.html.stackHint.text('1 to ' + cap + ' — set 0 to remove the item entirely.');
		}

		self.html.stackIcon.empty();
		var source = iconFor(item);
		if (source) {
			self.html.stackIcon.append($('<img>').attr('src', source));
		}

		self.html.stackDialog.modal('show');
	};

	var applyStack = () => {
		if (!stackTarget) {
			return;
		}

		var amount = parseInt(self.html.stackAmount.val(), 10);
		if (isNaN(amount) || amount < 0) {
			return;
		}

		amount = Math.min(amount, stackCapFor(stackTarget));

		if (amount === 0) {
			var located = findItem(stackTarget.guid);
			if (located) {
				containers[located.key].items =
					containers[located.key].items.filter(i => i.guid !== stackTarget.guid);
			}
		} else {
			stackTarget.stackSize = amount;
		}

		stackTarget = null;
		self.html.stackDialog.modal('hide');
		redraw();
	};

	// ---- persistence --------------------------------------------------------

	self.buildChanges = () => {
		var unequips = [];
		var moves = [];
		var equips = [];
		var stillPresent = {};

		// Everything that came off a character has to be taken off before
		// anything else is put on, or the slot would still look occupied.
		for (var owner in originalEquipment) {
			if (!originalEquipment.hasOwnProperty(owner)) continue;
			originalEquipment[owner].forEach((guid, index) => {
				if (!guid) {
					return;
				}

				var current = equipment[owner][index];
				if (current && current.guid === guid) {
					return;
				}

				var now = findItem(guid);
				stillPresent[guid] = !!now;

				// Where it ended up. An item that moved straight to another
				// slot is parked in its owner's pack on the way through.
				var destCharacter = owner;
				var destComponent = packComponentOf(owner);
				var destSlot = -1;

				if (now && now.equipmentSlot === undefined) {
					var where = parseKey(now.key);
					destCharacter = where.character;
					destComponent = where.component;
					destSlot = now.item.uiSlot;
				}

				unequips.push({
					character: owner
					, component: EQUIPMENT
					, itemGuid: guid
					, stackSize: 1
					, destCharacter: destCharacter
					, destComponent: destComponent
					, destSlot: destSlot
					, fromEquipmentSlot: index
				});
			});
		}

		for (var key in containers) {
			if (!containers.hasOwnProperty(key)) continue;
			var location = parseKey(key);

			containers[key].items.forEach(item => {
				stillPresent[item.guid] = true;

				// Brand new items are created rather than moved.
				if (item.isNew) {
					moves.push({
						character: location.character
						, component: location.component
						, itemGuid: item.guid
						, stackSize: item.stackSize
						, destCharacter: location.character
						, destComponent: location.component
						, destSlot: item.uiSlot
						, newItemPrefab: prefabNameOf(item)
						, newItemPath: item.baseItem
					});

					return;
				}

				var was = original[item.guid];
				if (!was || was.component === EQUIPMENT) {
					// Unequipping already delivered it to this pack.
					return;
				}

				var moved = was.character !== location.character
					|| was.component !== location.component;

				if (!moved && was.stackSize === item.stackSize && was.uiSlot === item.uiSlot) {
					return;
				}

				moves.push({
					character: was.character
					, component: was.component
					, itemGuid: item.guid
					, stackSize: item.stackSize
					, destCharacter: location.character
					, destComponent: location.component
					, destSlot: item.uiSlot
				});
			});
		}

		// Weapon sets follow the same take-off-then-put-on ordering.
		for (var wielder in originalWeapons) {
			if (!originalWeapons.hasOwnProperty(wielder)) continue;
			originalWeapons[wielder].forEach((guid, index) => {
				if (!guid) {
					return;
				}

				var current = weapons[wielder][index];
				if (current && current.guid === guid) {
					return;
				}

				var now = findItem(guid);
				stillPresent[guid] = !!now;

				var destCharacter = wielder;
				var destComponent = packComponentOf(wielder);
				var destSlot = -1;

				if (now && now.equipmentSlot === undefined && now.weaponSlot === undefined) {
					var where = parseKey(now.key);
					destCharacter = where.character;
					destComponent = where.component;
					destSlot = now.item.uiSlot;
				}

				unequips.push({
					character: wielder
					, component: EQUIPMENT
					, itemGuid: guid
					, stackSize: 1
					, destCharacter: destCharacter
					, destComponent: destComponent
					, destSlot: destSlot
					, fromEquipmentSlot: index
					, weaponSet: true
				});
			});
		}

		for (var holder in weapons) {
			if (!weapons.hasOwnProperty(holder)) continue;
			weapons[holder].forEach((item, index) => {
				if (!item) {
					return;
				}

				stillPresent[item.guid] = true;
				if (originalWeapons[holder][index] === item.guid) {
					return;
				}

				var was = original[item.guid];
				equips.push({
					character: was && was.component !== EQUIPMENT ? was.character : holder
					, component: was && was.component !== EQUIPMENT
						? was.component : packComponentOf(holder)
					, itemGuid: item.guid
					, stackSize: 1
					, destCharacter: holder
					, destComponent: EQUIPMENT
					, destSlot: -1
					, toEquipmentSlot: index
					, weaponSet: true
				});
			});
		}

		// Now everything that ended up worn.
		for (var wearer in equipment) {
			if (!equipment.hasOwnProperty(wearer)) continue;
			equipment[wearer].forEach((item, index) => {
				if (!item) {
					return;
				}

				stillPresent[item.guid] = true;
				if (originalEquipment[wearer][index] === item.guid) {
					return;
				}

				var was = original[item.guid];
				equips.push({
					character: was && was.component !== EQUIPMENT ? was.character : wearer
					, component: was && was.component !== EQUIPMENT
						? was.component : packComponentOf(wearer)
					, itemGuid: item.guid
					, stackSize: 1
					, destCharacter: wearer
					, destComponent: EQUIPMENT
					, destSlot: -1
					, toEquipmentSlot: index
				});
			});
		}

		// Anything that started somewhere and is now nowhere was deleted.
		var removals = [];
		for (var guid in original) {
			if (!original.hasOwnProperty(guid) || stillPresent[guid]) continue;
			removals.push({
				character: original[guid].character
				, component: original[guid].component
				, itemGuid: guid
				, stackSize: 0
				, destCharacter: original[guid].character
				, destComponent: original[guid].component
				, destSlot: -1
			});
		}

		return unequips.concat(moves).concat(equips).concat(removals);
	};

	var packComponentOf = characterGuid => {
		var character = characterInfo(characterGuid);
		return character ? character.packComponent : 'Inventory';
	};

	// "assets/.../ring_hag.prefab" -> "ring_hag"; the packet's ObjectName.
	var prefabNameOf = item => {
		var path = item.baseItem || '';
		var file = path.substring(path.lastIndexOf('/') + 1);
		return file.replace(/\.prefab$/i, '') || item.key;
	};

	self.reset = () => {
		containers = {};
		original = {};
		equipment = {};
		originalEquipment = {};
		weapons = {};
		originalWeapons = {};
		selected = null;
		builtFor = '';
		sellMode = false;
		forSale = {};
		sold = {units: 0, total: 0};
		dropped = '';
	};

	/** Whether anything is staged that Apply has not written yet. */
	self.unapplied = () => self.buildChanges().length > 0;

	/** What the draft has sold and is owed for it: {units, total, words}. */
	self.stagedSale = () => ({units: sold.units, total: sold.total, words: saleWords()});

	// An item's name as the save last had it, wherever it was: a removed one
	// is no longer in the working copy.
	var nameInSave = guid => {
		var inventory = saveData().inventory || {};
		var items = ((inventory.stash || {}).items || []).slice();
		(inventory.characters || []).forEach(character => {
			items.push.apply(items, (character.pack || {}).items || []);
			items.push.apply(items, (character.quickbar || {}).items || []);
			((character.equipment || {}).slots || []).forEach(slot => slot.item && items.push(slot.item));
			((character.equipment || {}).weaponSets || []).forEach(set =>
				['primary', 'secondary'].forEach(hand => set[hand] && items.push(set[hand])));
		});

		var found = items.filter(item => item.guid === guid)[0];
		return found ? found.displayName : '';
	};

	/**
	 * How Undo names an Apply: the one item and what became of it, or how
	 * many; a sale as the sale it was.
	 */
	self.describeChanges = changes => {
		if (sold.units < 1) {
			return describeMoves(changes);
		}

		return changes.every(change => change.stackSize <= 0)
			? 'Sell ' + saleWords()
			: describeMoves(changes) + ', selling ' + saleWords();
	};

	var describeMoves = changes => {
		var whose = [];
		changes.forEach(change => whose.push(change.character, change.destCharacter));
		if (changes.length !== 1) {
			return appliedLabel('Inventory', changes.length, whose);
		}

		var change = changes[0];
		var found = findItem(change.itemGuid);
		var name = (found && found.item.displayName) || nameInSave(change.itemGuid) || 'an item';

		if (change.newItemPrefab) {
			return 'Inventory: add ' + name;
		}

		if (change.stackSize <= 0) {
			return 'Inventory: remove ' + name;
		}

		if (change.destCharacter !== change.character || change.destComponent !== change.component) {
			return 'Inventory: ' + name + ' to ' + (change.destComponent === STASH
				? 'the stash' : characterName(change.destCharacter));
		}

		return 'Inventory: ' + name;
	};

	self.setStatus = message => self.html.invStatus.text(message).show();

	// ---- loadouts -----------------------------------------------------------

	// What the character on the doll wears, holds and keeps in quick slots,
	// to a file and back onto anyone (save/Loadout). Both read and change the
	// save as it is, so they wait for the tab's own changes to be applied or
	// reverted: those would be read past, and lost when the reply comes back.
	var loadoutFile = null;
	var loadoutFrom = '';

	var loadoutRequest = extra => JSON.stringify($.extend({
		GUID: self.state.character
		, absolutePath: Eternity.SavedGame.state.info.absolutePath
		// An Apply or a Save moves the save's current state out of the
		// directory the list opened.
		, savedYet: Eternity.Modifications.state.savedYet
	}, extra || {}));

	var renderLoadoutButtons = () => {
		var staged = self.buildChanges().length > 0;
		[self.html.invSaveLoadout, self.html.invPutLoadout].forEach(button => {
			if (button.data('title') === undefined) {
				button.data('title', button.attr('title'));
			}

			button.prop('disabled', !!self.state.working || staged || !self.state.character)
				.attr('title', staged ? 'Apply or revert the changes first' : button.data('title'));
		});
	};

	self.saveLoadout = () => {
		var name = characterName(self.state.character);
		window.exportLoadout({
			request: loadoutRequest({name: name})
			, onSuccess: response => {
				var saved = JSON.parse(response);
				redraw('Saved ' + name + '’s loadout, ' + saved.items
					+ (saved.items === 1 ? ' item' : ' items') + ', to ' + saved.file + '.');
			}
			, onFailure: (code, message) => {
				if (message !== 'CANCELLED') {
					redraw(message);
				}
			}
		});
	};

	self.readLoadout = () => {
		window.readLoadout({
			request: loadoutRequest()
			, onSuccess: response => self.showLoadout(JSON.parse(response))
			, onFailure: (code, message) => {
				if (message !== 'CANCELLED') {
					redraw(message);
				}
			}
		});
	};

	// While it goes on the dialog cannot be closed: the reply is adopted
	// whatever becomes of the dialog, so a Cancel then would not cancel.
	var loadoutBusy = busy => {
		self.state.working = busy;
		self.html.loadoutDialog.find('[data-dismiss="modal"]').prop('disabled', busy);
		self.html.loadoutConfirm.prop('disabled', busy).html(busy
			? '<i class="fa fa-spinner fa-pulse"></i> Working&hellip;'
			: '<i>&#10094;</i> Put it on <i>&#10095;</i>');
	};

	// The plan, item by item, before anything changes.
	self.showLoadout = plan => {
		var target = characterName(self.state.character);
		loadoutFile = plan.file;
		loadoutFrom = plan.from;
		self.html.loadoutSubject.text(plan.from + '’s gear'
			+ (plan.className ? ' (' + plan.className.toLowerCase() + ')' : '') + ', onto ' + target);

		var list = self.html.loadoutList.empty();
		var row = (iconData, name, place, outcome, kind) => {
			var icon = $('<span class="ld-icon">');
			if (iconData) {
				icon.append($('<img alt="">').attr('src', 'data:image/png;base64,' + iconData));
			}

			list.append($('<div class="ld-row">').addClass(kind || '').append(icon
				, $('<span class="ld-what">').append(
					$('<span class="ld-name">').text(name), $('<span class="ld-place">').text(place))
				, $('<span class="ld-outcome">').text(outcome)));
		};

		(plan.items || []).forEach(item => row(item.icon ? (plan.icons || {})[item.icon] : null
			, item.name + (item.stack > 1 ? ' ×' + item.stack : '')
			, item.place
			, !item.fits ? 'Left out: ' + item.reason
				: item.replaces ? 'Replaces ' + item.replaces : 'Into an empty slot'
			, item.fits ? '' : 'ld-skipped'));

		(plan.cleared || []).forEach(item =>
			row(null, item.name, 'comes off its weapon set', 'To the stash', 'ld-cleared'));

		var none = plan.fitting < 1;
		loadoutBusy(false);
		self.html.loadoutStatus.text(none ? 'None of it can go on ' + target + '.' : '')
			.toggleClass('ld-failed', none).toggle(none);
		self.html.loadoutConfirm.prop('disabled', none);
		self.html.loadoutDialog.modal({backdrop: 'static', keyboard: false});
	};

	self.putLoadout = () => {
		var target = self.state.character;
		var name = characterName(target);
		if (!loadoutFile || !target) {
			return;
		}

		loadoutBusy(true);
		self.html.loadoutStatus.text('Putting it on…').removeClass('ld-failed').show();
		window.applyLoadout({
			request: JSON.stringify({
				oldSave: Eternity.SavedGame.state.info.absolutePath
				, savedYet: Eternity.Modifications.state.savedYet
				, GUID: target
				, file: loadoutFile
			})
			, onSuccess: response => {
				loadoutBusy(false);
				self.html.loadoutDialog.modal('hide');
				var updated = Eternity.SavedGame.adopt(JSON.parse(response)
					, 'Put ' + loadoutFrom + '’s loadout on ' + name);
				self.reset();
				self.state.working = false;
				Eternity.Modifications.transition({modifications: true});
				Eternity.SavedGame.render({
					saveData: updated
					, info: Eternity.SavedGame.state.info
					, activeCharacter: Eternity.SavedGame.state.activeCharacter
					, view: Eternity.SavedGame.views.INVENTORY
				});

				self.setStatus('Put the loadout on ' + name + '; whatever it replaced is in the stash. '
					+ 'Save to write it to a file.');
			}
			, onFailure: (code, message) => {
				loadoutBusy(false);
				self.html.loadoutStatus.text(message).addClass('ld-failed').show();
			}
		});
	};

	/**
	 * Every item in the working copy and where it is, in SaveFind's shape. The
	 * working copy rather than the save, so an item moved but not yet applied
	 * is found where it now is; null until there is one to ask.
	 */
	self.everything = () => {
		if (Object.keys(containers).length < 1) {
			return null;
		}

		var found = [];
		Object.keys(containers).forEach(key => {
			var where = parseKey(key);
			var component = where.component === STASH ? 'stash'
				: where.component === QUICKBAR ? 'quick' : 'pack';

			containers[key].items.forEach(item => found.push({
				item: item, character: where.character, place: {component: component}}));
		});

		Object.keys(equipment).forEach(guid => equipment[guid].forEach((item, index) => {
			var slot = Object.keys(SLOT_INDEX).filter(name => SLOT_INDEX[name] === index)[0];
			if (item) {
				found.push({item: item, character: guid, place: {component: 'worn', slot: slot}});
			}
		}));

		Object.keys(weapons).forEach(guid => weapons[guid].forEach((item, index) => {
			if (item) {
				found.push({item: item, character: guid
					, place: {component: 'weapon', set: Math.floor(index / 2) + 1}});
			}
		}));

		return found;
	};

	/**
	 * Points at one item on screen, with its holder already the active
	 * character (the finder sees to that): clears whatever stash category or
	 * search would hide it, then picks its tile out. False when it is nowhere
	 * to be drawn.
	 */
	self.reveal = guid => {
		var tile = () => self.html.inventoryView.find('.inv-tile[data-guid="' + guid + '"]');

		var mark = revealed = {guid: guid, until: Date.now() + 2600};
		self.html.inventoryView.find('.inv-tile-found').removeClass('inv-tile-found');

		if (tile().length < 1) {
			stashFilter = 0;
			self.html.invStashSearch.val('');
		}

		redraw();
		var found = tile().first();
		if (found.length < 1) {
			revealed = null;
			return false;
		}

		var box = found.get(0).getBoundingClientRect();
		if (box.top < 60 || box.bottom > window.innerHeight) {
			window.scrollBy(0, box.top - window.innerHeight / 2);
		}

		// Only this reveal's own mark: a newer one owns the highlight by then.
		setTimeout(() => {
			if (revealed === mark) {
				revealed = null;
				self.html.inventoryView.find('.inv-tile-found').removeClass('inv-tile-found');
			}
		}, 2600);

		return true;
	};

	self.init = () => {
		self.html.invApply.click(() => self.apply());
		self.html.invSaveLoadout.click(() => self.saveLoadout());
		self.html.invPutLoadout.click(() => self.readLoadout());
		self.html.loadoutConfirm.click(() => self.putLoadout());
		self.html.invRevert.click(() => {
			buildWorkingCopy();
			redraw('Reverted to the save’s current contents.');
		});

		self.html.invSellMode.click(() => {
			sellMode = !sellMode;
			// Nothing may be in hand while ticking things off, and a stale
			// selection would be a nasty surprise on the way back in.
			selected = null;
			forSale = {};
			redraw(sellMode
				? 'Click items in the packs or the stash to sell them.'
				: '');
		});

		self.html.invSellJunk.click(() => {
			forSale = {};
			eachSellable(item => { if (isJunk(item)) forSale[item.guid] = true; });
			redraw();
		});

		self.html.invSellAll.click(() => {
			forSale = {};
			eachSellable(item => {
				if (!item.quest && (item.sellValue || 0) > 0) forSale[item.guid] = true;
			});

			redraw();
		});

		self.html.invSellNone.click(() => {
			forSale = {};
			redraw();
		});

		self.html.invSellConfirm.click(sell);

		self.html.invTidy.click(() => {
			var merged = tidyStacks();
			redraw(merged > 0
				? 'Merged ' + merged + ' item(s) into fewer stacks.'
				: 'Nothing to merge — every stack is already whole.');
		});

		self.html.stackAccept.click(applyStack);
		self.html.stackCancel.click(() => stackTarget = null);
		self.html.invStashSearch.keyup(() => renderStash());
		self.html.invBrowseSearch.keyup(scheduleBrowse);
		self.html.invBrowsePrev.click(() => {
			browseOffset = Math.max(0, browseOffset - browseLimit);
			requestBrowse();
		});
		self.html.invBrowseNext.click(() => {
			browseOffset += browseLimit;
			requestBrowse();
		});

		// A resize changes how many tiles fit, so the page has to be refilled
		// to keep it exactly two rows.
		$(window).resize(() => {
			window.clearTimeout(browseTimer);
			browseTimer = window.setTimeout(() => {
				if (self.html.inventoryView.is(':visible')
					&& browsePageSize() !== browseLimit) {

					browseOffset = 0;
					requestBrowse();
				}
			}, 200);
		});

		// Escape drops whatever is in hand, like right-clicking in the game.
		$(document).keyup(event => {
			if (event.which === 27 && selected) {
				selected = null;
				redraw();
			}
		});
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			// Going back to the save list means the next save opened must not
			// inherit this one's staged edits.
			self.reset();
			return;
		}

		// Rebuild when there is nothing to lose, or when the party itself has
		// changed under us. Switching characters must not discard edits that
		// haven't been applied yet, but a party member who has just appeared
		// has to get a pack on screen.
		if (Object.keys(containers).length < 1
			|| partyFingerprint() !== builtFor) {

			// Whatever changes the party waits for a draft to be applied or
			// reverted first (EditHistory.refused), so there should be nothing
			// to lose here; if something gets past that, at least say so.
			var lost = Object.keys(containers).length > 0 && self.unapplied();
			buildWorkingCopy();
			if (lost) {
				dropped = 'The party changed, so the changes staged here were dropped.';
			}
		}

		// Every save-view render transitions us, but drawing a few hundred
		// tiles is only worth doing when the view is actually on screen.
		if (!self.html.inventoryView.is(':visible')) {
			return;
		}

		if (!self.state.character) {
			var player = (inventory().characters || []).filter(c => c.isPlayer)[0];
			if (player) {
				self.state.character = player.guid;
			}
		}

		draw(dropped || undefined);

		// The catalog doesn't depend on the save, so it's only fetched once.
		if (browseTotal < 1 && browseItems.length < 1) {
			requestBrowse();
		}
	};
};

InventoryEditor.prototype.apply = function () {
	var self = this;
	var changes = self.buildChanges();
	var sale = self.stagedSale();

	if (changes.length < 1) {
		self.setStatus('No inventory changes to apply.');
		return;
	}

	// Named now, while the working copy still knows each item by name.
	var label = self.describeChanges(changes);
	self.transition({working: true, character: self.state.character});
	self.setStatus('Applying ' + changes.length + ' change(s)…');

	window.updateInventory({
		request: JSON.stringify({
			oldSave: Eternity.SavedGame.state.info.absolutePath
			, savedYet: Eternity.Modifications.state.savedYet
			, changes: changes
		})
		, onSuccess: response => {
			// A sale is paid when the Apply that takes its items out succeeds.
			// The purse is the page's to hold -- Save writes whatever the page
			// has -- so the money goes in here rather than on the server, into
			// the page's copy before the reply is adopted: the merge keeps
			// what differs from the file as the user's, which this is. The
			// step carries the amount, for Undo to take out again
			// (SaveHistory.moveCredit).
			if (sale.total > 0) {
				var data = Eternity.SavedGame.state.saveData;
				data.currency = (Number(data.currency) || 0) + sale.total;
			}

			var updated = Eternity.SavedGame.adopt(
				JSON.parse(response), label, {credit: sale.total});

			self.reset();
			self.state.working = false;
			Eternity.Modifications.transition({modifications: true});
			Eternity.SavedGame.render({
				saveData: updated
				, info: Eternity.SavedGame.state.info
				, activeCharacter: Eternity.SavedGame.state.activeCharacter
				, view: Eternity.SavedGame.views.INVENTORY
			});

			// Said the way every other tab says it; the redraw above would
			// otherwise leave a successful Apply looking like nothing happened.
			self.setStatus((sale.units > 0 ? 'Inventory updated: sold ' + sale.words + '.'
				: 'Inventory updated.') + ' Save to write it to a file.');
		}
		, onFailure: (code, message) => {
			self.transition({working: false, character: self.state.character});
			self.setStatus('Inventory update failed: ' + message);
		}
	});
};

$.extend(InventoryEditor.prototype, Renderer.prototype);
