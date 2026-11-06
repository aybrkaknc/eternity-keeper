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

var SavedGame = function () {
	var self = this;
	var disabledForCompanions = [
		'BaseMight', 'BaseConstitution', 'BaseDexterity', 'BaseIntellect', 'BasePerception'
		, 'BaseResolve'];

	// The stats players most commonly want to edit, pinned to the top of the
	// Raw view in this order. Everything else follows alphabetically.
	var importantStats = [
		'Experience', 'Level', 'RemainingSkillPoints'
		, 'AthleticsSkill', 'LoreSkill', 'MechanicsSkill', 'StealthSkill'
		, 'SurvivalSkill', 'CraftingSkill'
		, 'MaxHealth', 'MaxStamina'
		, 'BaseMight', 'BaseConstitution', 'BaseDexterity', 'BasePerception'
		, 'BaseIntellect', 'BaseResolve'
		, 'BaseDeflection', 'BaseFortitude', 'BaseReflexes', 'BaseWill'];

	self.views = Object.freeze({
		ATTR: 0, RAW: 1, GLOBALS: 2, CONSOLE: 3, INVENTORY: 4, ABILITIES: 5
		, STRONGHOLD: 6, GRIMOIRE: 7, VENDORS: 8});

	var defaultState = {
		saveData: {}
		, info: {}
		, activeCharacter: false
		, view: self.views.ATTR
	};

	// The name a character is listed by: what is typed into the name box as
	// soon as it is typed, and otherwise the name the save was opened with
	// (a companion's own name box is empty: the game supplies her name).
	var listedName = character => {
		var typed = character.stats && character.stats.OverrideName
			? $.trim(String(character.stats.OverrideName.value || '')) : '';
		return typed || character.name;
	};

	self.refreshName = character => {
		self.html.characterList.find('li')
			.filter((i, li) => $(li).data('guid') === character.GUID)
			.find('.character-name').text(listedName(character));
	};

	var populateCharacterList = (container, characters) => {
		// The main character always sits at the top marked with a star; the
		// rest of the party is sorted alphabetically.
		var sorted = characters.slice().sort((a, b) => {
			if (!!a.isMainCharacter !== !!b.isMainCharacter) {
				return a.isMainCharacter ? -1 : 1;
			}

			return a.name.localeCompare(b.name);
		});

		container.append(
			sorted.map(character => {
				var row = $('<li>')
					.data('guid', character.GUID)
					.append($('<i>').addClass(
						character.isMainCharacter
							? 'fa fa-star main-character-star'
							: 'fa fa-heartbeat'))
					.append(document.createTextNode(' '))
					.append($('<span>').addClass('character-name').text(listedName(character)))
					.addClass(character.isDead ? 'dead' : '')
					.click(self.switchCharacter.bind(self, character.GUID));

				// Every living party member carries their own pack, so give
				// each one a direct way into their inventory rather than
				// making people select the character and then find the tab.
				if (!character.isDead) {
					row.append($('<i>')
						.addClass('fa fa-briefcase character-inventory')
						.attr('title', 'Open ' + character.name + "'s inventory")
						.click(event => {
							event.stopPropagation();
							self.switchCharacter(character.GUID);
							self.switchView(self.views.INVENTORY);
						}));
				}

				return row;
			}));
	};

	var sortData = unsorted => {
		var sortable = [];
		for (var k in unsorted) {
			if (!unsorted.hasOwnProperty(k)) {
				continue;
			}

			sortable.push([k, unsorted[k]]);
		}

		sortable.sort((a, b) => a[0].toLowerCase() > b[0].toLowerCase() ? 1 : -1);
		return sortable;
	};

	var createBooleanEditor = initialValue => {
		return createEnumEditor(['true', 'false'], initialValue);
	};

	var createEnumEditor = (enumValues, initialValue) => {
		return $('<select></select>')
			.addClass('form-control')
			.append(enumValues.map(v =>
				$('<option></option>').prop('selected', v == initialValue).text(v).val(v)));
	};

	var createRawEditor = (key, fullkey, value, locked) => {
		var row = $('<tr></tr>');
		var keyCol = $('<td></td>');
		var valCol = $('<td></td>');
		var editor;

		// A real save does hold one global whose name is the empty string, and
		// an unlabelled editable row reads as a rendering fault rather than as
		// data. Say what it is instead of hiding a variable that exists.
		if (String(key).length < 1) {
			keyCol.text('(unnamed)').addClass('raw-unnamed').attr(
				'title', 'This variable has no name in the save.');
		} else {
			keyCol.text(key);
		}

		valCol.data('key', key);
		valCol.data('fullkey', fullkey);

		// The game rebuilds companion base attributes from their character
		// template on every load, so editing them here would silently do
		// nothing in-game — show them read-only instead.
		if (locked) {
			valCol.text(value.value)
				.addClass('raw-locked')
				.attr('title', 'The game recalculates companion attributes from their '
					+ 'template on load; this value cannot be edited.');
			row.append(keyCol, valCol);
			return row;
		}

		if (value.type === 'java.lang.Boolean') {
			editor = createBooleanEditor(value.value);
			valCol.append(editor);
			editor.change(self.update.bind(self));
		} else if (Eternity.structures[value.type] !== undefined) {
			editor = createEnumEditor(Eternity.structures[value.type], value.value);
			valCol.append(editor);
			editor.change(self.update.bind(self));
		} else {
			valCol.prop('contenteditable', true);
			valCol.text(value.value);
			valCol.keyup(self.update.bind(self));
		}

		row.append(keyCol, valCol);
		return row;
	};

	var populateGlobals = globals => {
		var globalsTable = self.html.globalsTable.find('tbody');
		globalsTable.empty();

		var flat = flattenObject(globals, 2);
		var tuples = [];
		flat.forEach(tuple => {
			for (var key in tuple[1]) {
				if (!tuple[1].hasOwnProperty(key)) {
					continue;
				}

				tuples.push([tuple[0], key, tuple[1][key]]);
			}
		});

		tuples.sort((a, b) => a[1].toLowerCase() > b[1].toLowerCase() ? 1 : -1);
		tuples.forEach(tuple => {
			var row = createRawEditor(tuple[1], tuple[0] + '.' + tuple[1], tuple[2]);
			globalsTable.append(row);
		});
	};

	// The save stores cumulative skill POINTS, but the character sheet shows a
	// RANK: reaching rank N costs 1+2+...+N points. Editing points directly is
	// why small changes look like they did nothing, so the editor works in
	// ranks and writes the exact point total the game would.
	// (CharacterStats.GetPointsForSkillLevel / CalculateSkillLevelViaPoints.)
	var SKILLS = [
		{stat: 'AthleticsSkill', label: 'Athletics'}
		, {stat: 'StealthSkill', label: 'Stealth'}
		, {stat: 'LoreSkill', label: 'Lore'}
		, {stat: 'MechanicsSkill', label: 'Mechanics'}
		, {stat: 'SurvivalSkill', label: 'Survival'}
		, {stat: 'CraftingSkill', label: 'Crafting'}
	];

	var MAX_SKILL_RANK = 20;

	var pointsForRank = rank => rank * (rank + 1) / 2;

	var rankForPoints = points => {
		var rank = 0;
		var total = 0;
		while (total + (rank + 1) <= points && rank < 100) {
			rank++;
			total += rank;
		}

		return rank;
	};

	// ---- identity -----------------------------------------------------------
	//
	// Race, subrace, culture, class, background and gender are [Persistent]
	// enums on CharacterStats, so they are written on Save by the ordinary
	// scalar path -- there is nothing new to send. What needs care is the
	// consequences, all of them from the decompiled game:
	//
	//   * RacialBodyType is NOT editable. Awake() sets it to CharacterRace for
	//     anyone who is not godlike, and for a godlike it holds the body
	//     underneath -- a Moon Godlike player really does carry an Aumaua
	//     body. Writing it would either be pointless or break that.
	//   * Equipment.HasEquipmentSlot means a grimoire slot exists only for a
	//     wizard and a head slot only for a non-godlike. Nothing in the game
	//     repairs an item left in a slot that stops existing, so the editor
	//     says so rather than stranding it silently.
	//   * Restored() rebuilds m_abilities from whatever ability objects exist;
	//     it never checks them against the class. Changing class therefore
	//     leaves the old class's abilities behind, which is worth saying.

	// Only the races and classes a player character can actually be. The enums
	// carry creature types too (Beast, Spirit, Troll, Ogre...) which have no
	// portraits, no subraces and no progression table.
	var PLAYABLE_RACES = ['Human', 'Elf', 'Dwarf', 'Godlike', 'Orlan', 'Aumaua'];
	var PLAYABLE_CLASSES = [
		'Fighter', 'Rogue', 'Priest', 'Wizard', 'Barbarian', 'Ranger'
		, 'Druid', 'Paladin', 'Monk', 'Cipher', 'Chanter'];

	// Which subraces belong to which race. The godlike set is exactly what
	// CharacterStats.SubraceIsGodlike() tests for.
	var SUBRACES_BY_RACE = {
		Human: ['Meadow_Human', 'Ocean_Human', 'Savannah_Human']
		, Elf: ['Wood_Elf', 'Snow_Elf']
		, Dwarf: ['Mountain_Dwarf', 'Boreal_Dwarf']
		, Godlike: ['Death_Godlike', 'Fire_Godlike', 'Nature_Godlike'
			, 'Moon_Godlike', 'Avian_Godlike']
		, Orlan: ['Hearth_Orlan', 'Wild_Orlan']
		, Aumaua: ['Coastal_Aumaua', 'Island_Aumaua']
	};

	var IDENTITY = [
		{stat: 'CharacterRace', label: 'Race', only: PLAYABLE_RACES}
		, {stat: 'CharacterSubrace', label: 'Subrace'}
		, {stat: 'CharacterClass', label: 'Class', only: PLAYABLE_CLASSES}
		, {stat: 'CharacterCulture', label: 'Culture'}
		, {stat: 'CharacterBackground', label: 'Background'}
		, {stat: 'Gender', label: 'Gender'}
		// Only these two classes have anything to choose.
		, {stat: 'Deity', label: 'Deity', whenClass: 'Priest'}
		, {stat: 'PaladinOrder', label: 'Order', whenClass: 'Paladin'}
	];

	// Sentinels, and the values the game itself marks as unusable.
	var isRealOption = value =>
		value !== 'Count' && value !== 'Undefined' && value.indexOf('DO_NOT_USE') < 0;

	var statValue = (data, stat) =>
		data.stats[stat] === undefined ? '' : String(data.stats[stat].value);

	// Enum constants are either underscored (Moon_Godlike) or run together
	// (IxamitlPlains, GoldpactKnights); the game shows them spaced.
	var humanise = value => String(value)
		.replace(/_/g, ' ')
		.replace(/([a-z])([A-Z])/g, '$1 $2')
		.replace(/\s+/g, ' ')
		.trim();

	// What this character has equipped in a named slot, or null. The inventory
	// payload has already resolved slot names for us.
	var equippedIn = (data, slot) => {
		var inventory = (self.state.saveData || {}).inventory || {};
		var found = (inventory.characters || []).filter(c => c.guid === data.GUID);
		if (found.length < 1) {
			return null;
		}

		// equipment.slots is an ordered array of {slot, flag, index, item},
		// laid out by EquipmentSet.SerializedEquipment rather than keyed by
		// name.
		var slots = (found[0].equipment || {}).slots || [];
		var match = slots.filter(entry => entry.slot === slot);
		var item = match.length > 0 ? match[0].item : null;
		return item && item.displayName ? item.displayName : null;
	};

	var optionsFor = (entry, data) => {
		var type = data.stats[entry.stat] ? data.stats[entry.stat].type : '';
		var all = Eternity.structures[type] || [];

		if (entry.stat === 'CharacterSubrace') {
			var race = statValue(data, 'CharacterRace');
			return SUBRACES_BY_RACE[race] || all.filter(isRealOption);
		}

		if (entry.only) {
			return entry.only.filter(value => all.indexOf(value) > -1);
		}

		return all.filter(isRealOption);
	};

	var identityWarnings = data => {
		var warnings = [];

		if (statValue(data, 'CharacterClass') !== 'Wizard') {
			var grimoire = equippedIn(data, 'Grimoire');
			if (grimoire) {
				warnings.push('A non-wizard has no grimoire slot, so ' + grimoire
					+ ' can no longer be reached. Unequip it in the Inventory tab.');
			}
		}

		if (statValue(data, 'CharacterRace') === 'Godlike') {
			var head = equippedIn(data, 'Head');
			if (head) {
				warnings.push('Godlike have no head slot, so ' + head
					+ ' can no longer be reached. Unequip it in the Inventory tab.');
			}
		}

		return warnings;
	};

	// ---- what each choice is worth ------------------------------------------
	//
	// None of this is baked into the save at character creation, which is why
	// the panel can show it and why editing these dropdowns really does move
	// the character sheet. CharacterStats.GetAttributeScore() adds
	// RaceAbilityAdjustment and CultureAbilityAdjustment to the stored BaseX
	// on every read, and CalculateSkillInternal() adds ClassSkillAdjustment
	// and BackgroundSkillAdjustment to the skill rank the same way.
	//
	// The tables themselves are the same for every save, so they are fetched
	// once from GetIdentityEffects and kept. The arithmetic stays here rather
	// than server-side because the numbers have to move as the dropdowns move,
	// without a round trip.

	var identityEffects = null;
	var identityEffectsRequested = false;
	var identityCharacter = null;

	var loadIdentityEffects = () => {
		if (identityEffectsRequested || !window.getIdentityEffects) {
			return;
		}

		identityEffectsRequested = true;
		window.getIdentityEffects({
			request: '{}'
			, onSuccess: response => {
				identityEffects = JSON.parse(response);
				if (identityCharacter) {
					populateIdentity(identityCharacter);
				}
			}
			, onFailure: () => {
				// Without the tables the panel is still a working editor; it
				// just cannot say what the choices are worth.
				identityEffects = null;
			}
		});
	};

	// The order the game's own sheet uses, which is neither enum's declared
	// order. The bonus fields are the persisted XBonus the game adds on top.
	var SHEET_ATTRIBUTES = [
		{name: 'Might', stat: 'BaseMight', bonus: 'MightBonus'}
		, {name: 'Constitution', stat: 'BaseConstitution', bonus: 'ConstitutionBonus'}
		, {name: 'Dexterity', stat: 'BaseDexterity', bonus: 'DexterityBonus'}
		, {name: 'Perception', stat: 'BasePerception', bonus: 'PerceptionBonus'}
		, {name: 'Intellect', stat: 'BaseIntellect', bonus: 'IntellectBonus'}
		, {name: 'Resolve', stat: 'BaseResolve', bonus: 'ResolveBonus'}
	];

	var MINUS = '−';

	var signed = value => (value > 0 ? '+' : MINUS) + Math.abs(value);

	var numberFrom = (data, stat) => {
		var held = data.stats[stat];
		var value = held === undefined ? 0 : parseInt(held.value, 10);
		return isNaN(value) ? 0 : value;
	};

	// One row of an adjustment table, in the order the sheet lists it.
	var adjustmentText = (table, order) => {
		if (!table) {
			return '';
		}

		return order
			.filter(name => table[name])
			.map(name => signed(table[name]) + ' ' + name)
			.join(', ');
	};

	var attributeOrder = () => SHEET_ATTRIBUTES.map(attribute => attribute.name);
	var skillOrder = () => SKILLS.map(skill => skill.label);

	var adjustmentsOf = (group, value, field) => {
		var block = (identityEffects || {})[group] || {};
		return (block[value] || {})[field] || null;
	};

	// Whether this character actually owns an ability, by catalog key. Adding
	// a subrace does not mint its racial ability: CharacterStats.Restored()
	// rebuilds m_abilities from whatever ability objects exist, so the object
	// has to be there.
	var ownsAbility = (data, key) => {
		var owners = ((self.state.saveData || {}).abilities || {}).characters || [];
		var mine = owners.filter(owner => owner.guid === data.GUID);
		if (mine.length < 1) {
			return true;
		}

		return (mine[0].abilities || []).some(ability => ability.key === key);
	};

	var abilityEffect = (data, value) => {
		var ability = ((identityEffects || {}).subrace || {})[value];
		if (!ability) {
			return null;
		}

		var line = $('<div>').addClass('identity-effect identity-ability');
		if (ability.icon) {
			line.append($('<img>')
				.addClass('identity-ability-icon')
				.attr('src', 'data:image/png;base64,' + ability.icon));
		}

		line.append($('<span>')
			.addClass('identity-ability-name')
			.text(ability.name));

		if (ability.description) {
			line.append($('<span>')
				.addClass('identity-ability-desc')
				.text(ability.description));
		}

		if (!ownsAbility(data, ability.key)) {
			line.append($('<span>')
				.addClass('identity-ability-missing')
				.text('This character does not have it — the game only grants a '
					+ 'racial ability at creation. Add it in the Abilities tab.'));
		}

		return line;
	};

	var devotionEffect = (group, value) => {
		var devotion = ((identityEffects || {})[group] || {})[value];
		if (!devotion) {
			return null;
		}

		var line = $('<div>').addClass('identity-effect');
		if (devotion.positive.length < 1 && devotion.negative.length < 1) {
			return line.text('No dispositions of its own.');
		}

		var ladder = ((identityEffects || {}).dispositionBonus || {}).positive || [];
		var most = ladder.length > 0
			? Math.round(ladder[ladder.length - 1] * 100) : 0;

		line.append($('<span>').addClass('identity-favour')
			.text('Favours ' + devotion.positive.join(', ')));
		line.append($('<span>').addClass('identity-disfavour')
			.text('Disfavours ' + devotion.negative.join(', ')));

		if (most > 0) {
			line.append($('<span>').addClass('identity-effect-note')
				.text('Each matching disposition rank shifts ability power by '
					+ 'up to ' + most + '%, and only for the player character.'));
		}

		return line;
	};

	// What goes under one dropdown.
	var effectFor = (entry, data) => {
		if (!identityEffects) {
			return null;
		}

		var value = statValue(data, entry.stat);

		if (entry.stat === 'CharacterRace' || entry.stat === 'CharacterCulture') {
			var attributes = adjustmentsOf(
				entry.stat === 'CharacterRace' ? 'race' : 'culture'
				, value, 'attributes');

			if (!attributes) {
				return null;
			}

			var text = adjustmentText(attributes, attributeOrder());
			return $('<div>').addClass('identity-effect')
				.text(text === '' ? 'No attribute adjustment.' : text);
		}

		if (entry.stat === 'CharacterClass'
			|| entry.stat === 'CharacterBackground') {

			var skills = adjustmentsOf(
				entry.stat === 'CharacterClass' ? 'characterClass' : 'background'
				, value, 'skills');

			if (!skills) {
				return null;
			}

			var skillText = adjustmentText(skills, skillOrder());
			return $('<div>').addClass('identity-effect')
				.text(skillText === '' ? 'No skill adjustment.' : skillText);
		}

		if (entry.stat === 'CharacterSubrace') {
			return abilityEffect(data, value);
		}

		if (entry.stat === 'Deity') {
			return devotionEffect('deity', value);
		}

		if (entry.stat === 'PaladinOrder') {
			return devotionEffect('order', value);
		}

		if (entry.stat === 'Gender') {
			// StringTableManager.GetCharacterName(DisplayName, Gender) is the
			// only thing that reads it.
			return $('<div>').addClass('identity-effect')
				.text('Names and pronouns in text. Nothing on the sheet.');
		}

		return null;
	};

	// ---- the totals the game will show ---------------------------------------

	var chip = (name, total, parts) => $('<div>')
		.addClass('identity-chip')
		.append($('<span>').addClass('identity-chip-name').text(name))
		.append($('<span>').addClass('identity-chip-total').text(total))
		.append($('<span>').addClass('identity-chip-parts').text(parts.join(' ')));

	var populateSheet = data => {
		var sheet = self.html.identitySheet.empty();
		if (!identityEffects) {
			return;
		}

		var fromRace = adjustmentsOf(
			'race', statValue(data, 'CharacterRace'), 'attributes') || {};
		var fromCulture = adjustmentsOf(
			'culture', statValue(data, 'CharacterCulture'), 'attributes') || {};
		var fromClass = adjustmentsOf(
			'characterClass', statValue(data, 'CharacterClass'), 'skills') || {};
		var fromBackground = adjustmentsOf(
			'background', statValue(data, 'CharacterBackground'), 'skills') || {};

		var attributeRow = $('<div>').addClass('identity-sheet-row');
		var haveAttributes = false;

		SHEET_ATTRIBUTES.forEach(attribute => {
			if (data.stats[attribute.stat] === undefined) {
				return;
			}

			haveAttributes = true;
			var base = numberFrom(data, attribute.stat);
			var bonus = numberFrom(data, attribute.bonus);
			var race = fromRace[attribute.name] || 0;
			var culture = fromCulture[attribute.name] || 0;

			// GetAttributeScore() floors the result at 1.
			var total = Math.max(1, base + bonus + race + culture);
			var parts = [base + ' base'];
			if (race) parts.push(signed(race) + ' race');
			if (culture) parts.push(signed(culture) + ' culture');
			if (bonus) parts.push(signed(bonus) + ' bonus');

			attributeRow.append(chip(attribute.name, total, parts));
		});

		var skillRow = $('<div>').addClass('identity-sheet-row');
		var haveSkills = false;

		SKILLS.forEach(skill => {
			if (data.stats[skill.stat] === undefined) {
				return;
			}

			haveSkills = true;
			var rank = rankForPoints(numberFrom(data, skill.stat));
			var bonus = numberFrom(data, skill.label + 'Bonus');
			var fromTheClass = fromClass[skill.label] || 0;
			var fromTheBackground = fromBackground[skill.label] || 0;

			var total = rank + bonus + fromTheClass + fromTheBackground;
			var parts = ['rank ' + rank];
			if (fromTheClass) parts.push(signed(fromTheClass) + ' class');
			if (fromTheBackground) {
				parts.push(signed(fromTheBackground) + ' background');
			}

			if (bonus) parts.push(signed(bonus) + ' bonus');

			skillRow.append(chip(skill.label, total, parts));
		});

		if (!haveAttributes && !haveSkills) {
			return;
		}

		sheet.append($('<div>')
			.addClass('identity-sheet-head')
			.text('On the character sheet'));

		if (haveAttributes) sheet.append(attributeRow);
		if (haveSkills) sheet.append(skillRow);
	};

	// The totals follow what is typed into the attribute and skill boxes, not
	// only the dropdowns: they used to keep the old numbers until the sheet was
	// next drawn, under a box that already showed the new one.
	self.refreshSheet = () => {
		if (identityCharacter) {
			populateSheet(identityCharacter);
		}
	};

	var populateIdentity = data => {
		var grid = self.html.identityGrid.empty();
		var note = self.html.identityNote.empty();
		identityCharacter = data;

		// A companion the game deleted carries no stats to edit.
		if (data.resurrectable || !data.stats
			|| data.stats.CharacterRace === undefined) {

			self.html.identitySheet.empty();
			self.html.identityPanel.hide();
			return;
		}

		self.html.identityPanel.show();
		loadIdentityEffects();
		var characterClass = statValue(data, 'CharacterClass');

		IDENTITY.forEach(entry => {
			if (entry.whenClass && entry.whenClass !== characterClass) {
				return;
			}

			var current = data.stats[entry.stat];
			if (current === undefined) {
				return;
			}

			var options = optionsFor(entry, data);
			var select = $('<select>')
				.addClass('form-control identity-select')
				// Which CharacterStats field this dropdown writes. The panel
				// closes over it, so nothing needs the attribute to work --
				// but a field nothing can name is a field nothing else can
				// check, and these are the values the rest of the editor
				// derives equipment slots and progression tables from.
				.attr('data-stat', entry.stat)
				.append(options.map(value => $('<option>')
					.prop('selected', value === String(current.value))
					.text(humanise(value))
					.val(value)));

			// A value the game holds that the list does not offer (an NPC race
			// on a summoned creature, say) still has to be visible rather than
			// silently reassigned to the first option.
			if (options.indexOf(String(current.value)) < 0) {
				select.prepend($('<option>')
					.prop('selected', true)
					.text(humanise(current.value) + ' (unusual)')
					.val(String(current.value)));
			}

			select.on('change', () => {
				current.value = select.val();

				// A subrace has to belong to its race, or the save ends up
				// with a Wood Elf dwarf.
				if (entry.stat === 'CharacterRace') {
					var valid = SUBRACES_BY_RACE[select.val()] || [];
					var subrace = data.stats.CharacterSubrace;
					if (subrace && valid.length > 0
						&& valid.indexOf(String(subrace.value)) < 0) {

						subrace.value = valid[0];
					}
				}

				Eternity.Modifications.transition({modifications: true});
				populateIdentity(data);
			});

			var field = $('<div>')
				.addClass('identity-field')
				.append($('<label>').text(entry.label))
				.append(select);

			// What this choice is worth, right under the dropdown that sets it.
			var effect = effectFor(entry, data);
			if (effect) {
				field.append(effect);
			}

			grid.append(field);
		});

		populateSheet(data);

		identityWarnings(data).forEach(text =>
			note.append($('<div>').addClass('identity-warning').text(text)));

		note.append($('<div>')
			.addClass('identity-hint')
			.text('Changing class leaves the old class’s abilities in place — '
				+ 'the game rebuilds them from what the save holds rather than '
				+ 'from the class. Use the Abilities tab to sort those out.'));
	};

	// What the save says about itself. The strip is invisible unless a check
	// actually fires, which on an untouched save it never does -- all five
	// were measured against four real saves before being written. Every
	// manager re-opens through SavedGameOpener after applying, so a mistake
	// the editor made shows up here at once instead of in-game.
	var VALIDATION_LABELS = {
		DUPLICATE_OBJECT_ID: 'Two objects share an ID'
		, INSTANCE_ID_MISMATCH: 'An object disagrees with its own ID'
		, ITEM_LIST_LENGTH: 'An inventory\'s two lists are different lengths'
		, UNRESOLVED_ITEM: 'A carried item has no object of its own'
		, UNRESOLVED_EQUIPMENT: 'A worn item has no object of its own'
		, OBJECT_COUNT: 'The file\'s object count is wrong'
		, SHORT_READ: 'Part of the file could not be read'
	};

	var populateValidation = validation => {
		var problems = (validation || {}).problems || [];
		var list = self.html.saveWarningList.empty();

		if (problems.length < 1) {
			self.html.saveWarning.hide();
			return;
		}

		// A file that could only be read in part is the one fact worth leading
		// with: nothing else on screen can be trusted to be all there.
		self.html.saveWarning.show();
		self.html.saveWarningHead.text(problems.some(problem => problem.kind === 'SHORT_READ')
			? 'Part of this save could not be read'
			: problems.length === 1
				? 'This save contradicts itself in one place'
				: 'This save contradicts itself in ' + problems.length + ' places');

		// A long list helps nobody; the kinds are what matter, and the first
		// few examples of each. The rest are in eternity.log.
		var byKind = {};
		problems.forEach(problem => {
			byKind[problem.kind] = byKind[problem.kind] || [];
			byKind[problem.kind].push(problem);
		});

		Object.keys(byKind).forEach(kind => {
			var group = byKind[kind];
			var row = $('<div>').addClass('save-warning-row');

			row.append($('<span>')
				.addClass('save-warning-kind')
				.text((VALIDATION_LABELS[kind] || kind)
					+ (group.length > 1 ? ' (×' + group.length + ')' : '')));

			row.append($('<span>')
				.addClass('save-warning-detail')
				.text(group[0].objectName
					? group[0].objectName + ' — ' + group[0].detail
					: group[0].detail));

			list.append(row);
		});

		list.append($('<div>')
			.addClass('save-warning-note')
			.text('The game drops what it cannot resolve, usually without an error. '
				+ 'If this appeared after an edit, undo it by reopening the save '
				+ 'rather than writing this one out. Full detail is in eternity.log (Settings shows where it is).'));
	};

	var populateSkills = (data) => {
		var grid = self.html.skillsGrid.empty();
		var available = SKILLS.filter(skill => data.stats[skill.stat] !== undefined);

		if (available.length < 1) {
			self.html.skillsNote.text('This character has no skill data.');
			self.html.remainingSkillPoints.val('').prop('disabled', true);
			return;
		}

		var remaining = data.stats.RemainingSkillPoints;
		self.html.remainingSkillPoints
			.prop('disabled', remaining === undefined)
			.val(remaining === undefined ? '' : remaining.value)
			.off()
			.on('change keyup', function () {
				var value = parseInt($(this).val(), 10);
				if (!isNaN(value) && value >= 0 && remaining !== undefined) {
					remaining.value = value;
					Eternity.Modifications.transition({modifications: true});
				}
			});

		available.forEach(skill => {
			var entry = data.stats[skill.stat];
			var points = parseInt(entry.value, 10) || 0;
			var rank = rankForPoints(points);

			var readout = $('<span>').addClass('skill-points');
			var input = $('<input>')
				.attr({type: 'number', min: 0, max: MAX_SKILL_RANK})
				.addClass('form-control skill-rank')
				.val(rank);

			var describe = (currentRank, currentPoints) => {
				var spare = currentPoints - pointsForRank(currentRank);
				readout.text(currentPoints + ' pts'
					+ (spare > 0 ? ' (' + spare + ' spare)' : ''));
			};

			describe(rank, points);

			input.on('change keyup', function () {
				var wanted = parseInt($(this).val(), 10);
				if (isNaN(wanted) || wanted < 0 || wanted > MAX_SKILL_RANK) {
					return;
				}

				// Writing the exact cost keeps the sheet and the save agreeing;
				// any leftover points from the old value are dropped, which is
				// what the game itself would store for that rank.
				entry.value = pointsForRank(wanted);
				describe(wanted, entry.value);
				populateSheet(data);
				Eternity.Modifications.transition({modifications: true});
			});

			grid.append($('<div>').addClass('skill-row')
				.append($('<label>').addClass('skill-name').text(skill.label))
				.append(input)
				.append(readout));
		});

		// Unlike the six base attributes, skills are not re-copied from the
		// prefab on load, so these stick for companions too.
		self.html.skillsNote.text(
			'Rank ' + 0 + '–' + MAX_SKILL_RANK + '. Rank N costs '
			+ 'N(N+1)/2 points; the game shows the rank, the save stores points.');
	};

	var populateCharacter = (container, data) => {
		var portrait = container.find('.portrait')
			.empty()
			.css('background-image', 'url(data:image/png;base64,' + data.portrait + ')')
			.css('background-repeat', 'no-repeat')
			.html((data.isDead) ? '<div>DEAD</div>' : '');

		if (data.resurrectable) {
			portrait.append(
				$('<button type="button">')
					.addClass('pm-btn pm-btn-dialog resurrect-btn')
					.html('<i>&#10094;</i> Resurrect <i>&#10095;</i>')
					.click(self.resurrect.bind(self, data.GUID)));
		} else if (data.portraitPaths) {
			// A character the game deleted has no Portrait component to edit,
			// so the button only appears where there is something to write.
			portrait.append(
				$('<button type="button">')
					.addClass('pm-btn pm-btn-dialog portrait-btn')
					.html('<i>&#10094;</i> Change portrait <i>&#10095;</i>')
					.click(() => Eternity.PortraitPicker.open(data.GUID)));
		}

		// Synthetic dead-companion entries carry no stats; clear the inputs
		// so the previous character's values don't linger, and lock them.
		container.find('.stats input')
			.val('')
			.prop('disabled', !!data.resurrectable);

		var sortedData = sortData(data.stats);
		var rawTable = self.html.rawTable.find('tbody');
		rawTable.empty();

		var sectionHeader = text =>
			$('<tr>').addClass('raw-section-header')
				.append($('<td>').attr('colspan', 2).text(text));

		var lockedStat = stat =>
			data.isCompanion && disabledForCompanions.indexOf(stat) > -1;

		// Pinned section first, in curated order...
		var pinned = importantStats.filter(stat => data.stats[stat] !== undefined);
		if (pinned.length > 0) {
			rawTable.append(sectionHeader('Most useful'));
			pinned.forEach(stat =>
				rawTable.append(createRawEditor(stat, stat, data.stats[stat], lockedStat(stat))));
			rawTable.append(sectionHeader('All stats (A–Z)'));
		}

		sortedData.forEach(tuple => {
			var stat = tuple[0];
			var value = tuple[1];

			container
				.find('.stats')
				.find('input[data-fullkey="' + stat + '"]')
				.val(value.value.toString())
				.prop('disabled', lockedStat(stat));

			if (pinned.indexOf(stat) < 0) {
				rawTable.append(createRawEditor(stat, stat, value, lockedStat(stat)));
			}
		});

		container
			.find('.stats')
			.find('input')
			.change(self.update.bind(self))
			.keyup(self.update.bind(self));

		populateSkills(data);
		populateIdentity(data);
	};

	var filterTable = (search, table) => {
		var searchString = search.val().toLowerCase();
		if (searchString.length < 1) {
			table.find('tbody tr').show();
			return;
		}

		var matches =
			table.find('td:last-child')
				.filter((i, el) => {
					// Section-header rows carry no key.
					var key = $(el).data('key');
					return key && key.toLowerCase().includes(searchString);
				});

		table.find('tbody tr').hide();
		matches.each((i, el) => $(el).parent().show());
	};

	self.init = () => {
		// Collapsing the character list gives the inventory grids room on
		// smaller screens; the choice sticks between sessions.
		var applySidebar = collapsed => {
			$('body').toggleClass('sidebar-collapsed', collapsed);
			self.html.sidebarToggle
				.attr('title', collapsed ? 'Show the character list' : 'Hide the character list')
				.find('i')
				.attr('class', collapsed ? 'fa fa-chevron-right' : 'fa fa-chevron-left');
		};

		var collapsed = false;
		try {
			collapsed = localStorage.getItem('ekSidebar') === 'collapsed';
		} catch (e) {
			// localStorage may be unavailable; default to expanded.
		}

		applySidebar(collapsed);
		self.html.sidebarToggle.click(() => {
			collapsed = !collapsed;
			applySidebar(collapsed);

			try {
				localStorage.setItem('ekSidebar', collapsed ? 'collapsed' : 'open');
			} catch (e) {
				// Not persisting the choice is harmless.
			}
		});

		self.html.searchRaw.keyup(filterTable.bind(self, self.html.searchRaw, self.html.rawTable));
		self.html.searchGlobals.keyup(
			filterTable.bind(self, self.html.searchGlobals, self.html.globalsTable));
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);
		self.html.characterList.empty();
		Eternity.render({saveView: true});

		self.html.menuCharacterAttributes.off();
		self.html.menuCharacterAttributes.click(self.switchView.bind(self, self.views.ATTR));
		self.html.menuCharacterRaw.off();
		self.html.menuCharacterRaw.click(self.switchView.bind(self, self.views.RAW));
		self.html.menuEditGlobals.off();
		self.html.menuEditGlobals.click(self.switchView.bind(self, self.views.GLOBALS));
		self.html.menuOpenConsole.off();
		self.html.menuOpenConsole.click(self.switchView.bind(self, self.views.CONSOLE));
		Eternity.InventoryEditor.html.menuInventoryEditor.off();
		Eternity.InventoryEditor.html.menuInventoryEditor.click(
			self.switchView.bind(self, self.views.INVENTORY));
		Eternity.AbilityEditor.html.menuCharacterAbilities.off();
		Eternity.AbilityEditor.html.menuCharacterAbilities.click(
			self.switchView.bind(self, self.views.ABILITIES));
		Eternity.StrongholdEditor.html.menuStrongholdEditor.off();
		Eternity.StrongholdEditor.html.menuStrongholdEditor.click(
			self.switchView.bind(self, self.views.STRONGHOLD));
		Eternity.GrimoireEditor.html.menuGrimoireEditor.off();
		Eternity.GrimoireEditor.html.menuGrimoireEditor.click(
			self.switchView.bind(self, self.views.GRIMOIRE));
		Eternity.VendorEditor.html.menuVendorEditor.off();
		Eternity.VendorEditor.html.menuVendorEditor.click(
			self.switchView.bind(self, self.views.VENDORS));

		Eternity.CurrencyEditor.render({enabled: true, amount: self.state.saveData.currency});
		Eternity.Modifications.html.newSaveName.val(
			Eternity.Modifications.suggestSaveName(self.state.info));
		populateCharacterList(self.html.characterList, self.state.saveData.characters);
		populateGlobals(self.state.saveData.globals);
		populateValidation(self.state.saveData.validation);

		// Revert needs something to go back to, so the values on screen when a
		// save opens become the confirmed baseline. Later renders top it up
		// with characters that did not exist before -- a resurrection or an
		// import mints one, and an unknown character should read as clean
		// rather than as a pile of unconfirmed edits.
		Eternity.PanelChanges.transition({enabled: true});

		if (self.state.activeCharacter) {
			self.html.characterList.find('li')
				.removeClass('active')
				.filter((i, li) => $(li).data('guid') === self.state.activeCharacter)
				.addClass('active');
			var character =
				self.state.saveData.characters.filter(c => c.GUID == self.state.activeCharacter);
			if (character.length > 0) {
				populateCharacter(self.html.character, character[0]);
			}
		} else {
			// A freshly opened save always starts on the main character.
			var characters = self.state.saveData.characters;
			var main = characters.filter(c => c.isMainCharacter)[0] || characters[0];
			self.switchCharacter(main.GUID);
		}

		var showAnimatedView = (viewEl, navId) => {
			$('.navbar-nav > li').removeClass('ek-nav-active');
			if (navId) {
				$('#' + navId).addClass('ek-nav-active');
			}

			var animClass = self.charSwitching ? 'ek-char-switch' : 'ek-view-enter';
			viewEl.removeClass('ek-view-enter ek-char-switch').show().addClass(animClass);
		};

		$('.view').hide();
		switch(self.state.view) {
			case self.views.RAW:
				showAnimatedView(self.html.rawTable, 'menuCharacter');
				filterTable(self.html.searchRaw, self.html.rawTable);
				break;

			case self.views.GLOBALS:
				showAnimatedView(self.html.globalsTable, 'menuGlobals');
				filterTable(self.html.searchGlobals, self.html.globalsTable);
				break;

			case self.views.CONSOLE:
				showAnimatedView(Eternity.ConsoleTab.html.consoleView, 'menuConsole');
				Eternity.ConsoleTab.transition({enabled: true});
				break;

			case self.views.INVENTORY:
				showAnimatedView(Eternity.InventoryEditor.html.inventoryView, 'menuInventory');
				Eternity.InventoryEditor.transition({
					enabled: true, character: self.state.activeCharacter});
				break;

			case self.views.ABILITIES:
				showAnimatedView(Eternity.AbilityEditor.html.abilitiesView, 'menuCharacter');
				Eternity.AbilityEditor.transition({
					enabled: true, character: self.state.activeCharacter});
				break;

			// The stronghold belongs to the party rather than to whoever
			// happens to be selected, so no character rides along with it.
			case self.views.STRONGHOLD:
				showAnimatedView(Eternity.StrongholdEditor.html.strongholdView, 'menuStronghold');
				Eternity.StrongholdEditor.transition({enabled: true});
				break;

			// A grimoire belongs to the book, not to a character: the spells
			// live on the item's own packet, so this view lists books.
			case self.views.GRIMOIRE:
				showAnimatedView(Eternity.GrimoireEditor.html.grimoireView, 'menuGrimoire');
				Eternity.GrimoireEditor.transition({enabled: true});
				break;

			// Stores belong to the world, not to the party. Their stock is
			// read from the area files only once this tab is opened.
			case self.views.VENDORS:
				showAnimatedView(Eternity.VendorEditor.html.vendorsView, 'menuVendors');
				Eternity.VendorEditor.transition({enabled: true, visible: true});
				break;

			default:
				showAnimatedView(self.html.character, 'menuCharacter');
		}
	};
};

// What the file on disk holds, reduced to the values SaveMerge compares
// against. Taken when a save opens and again whenever Save writes the UI's copy
// out, since at both moments the two agree.
SavedGame.prototype.open = function (saveData, info) {
	var self = this;
	self.serverCopy = SaveMerge.snapshot(saveData);
	// A new history for a new save: nothing before it can be undone.
	Eternity.EditHistory.opened(saveData);
	self.render({saveData: saveData, info: info});
};

SavedGame.prototype.written = function () {
	var self = this;
	self.serverCopy = SaveMerge.snapshot(self.state.saveData);
	// Undo reaches back to here: past it, what Save wrote would go with it.
	Eternity.EditHistory.opened(self.state.saveData);
};

/**
 * A save a manager just rewrote and reopened, with the user's unsaved edits
 * put back in (invariant 12). Every Apply goes through here rather than
 * merging the reply its own way; `theirs` is the parsed reply and is modified.
 * An Apply's reply names its step of the history, and `label` says what it
 * was, for Undo; an undo's reply names none. `extra` is what the caller did to
 * the page's own values as the Apply succeeded, to be undone with the step
 * (EditHistory.applied).
 */
SavedGame.prototype.adopt = function (theirs, label, extra) {
	var self = this;
	var onDisk = SaveMerge.snapshot(theirs);
	var merged = SaveMerge.merge(self.serverCopy, self.state.saveData, theirs);
	self.serverCopy = onDisk;

	if (theirs.historyStep) {
		Eternity.EditHistory.applied(theirs.historyStep, label, merged, extra);
	}

	return merged;
};

SavedGame.prototype.switchCharacter = function (guid) {
	var self = this;
	self.charSwitching = true;
	try {
		self.transition({activeCharacter: guid});
	} finally {
		self.charSwitching = false;
	}
};

SavedGame.prototype.resurrect = function (guid) {
	var self = this;

	// A resurrection reopens the save with someone new in it, and a draft
	// staged in a tab against the old one would be dropped.
	if (Eternity.EditHistory.refused()) {
		return;
	}

	var name = ((self.state.saveData.characters || []).filter(c => c.GUID === guid)[0] || {}).name
		|| 'a companion';
	var button = self.html.character.find('.resurrect-btn');
	button.prop('disabled', true)
		.html('<i class="fa fa-spinner fa-pulse"></i> Resurrecting&hellip;');

	var success = response => {
		response = JSON.parse(response);

		if (response.error) {
			button.prop('disabled', false).html('<i>&#10094;</i> Resurrect <i>&#10095;</i>');
			Eternity.GenericError.render({msg: response.error});
			return;
		}

		// A new character exists now and resurrection cleared death flags in
		// the globals; both arrive, and unsaved edits elsewhere are kept.
		// The synthetic "dead:" entry is gone from the re-opened save;
		// falling back to the default selection re-picks the main character.
		self.render({
			saveData: self.adopt(response, 'Resurrect ' + name)
			, info: self.state.info
			, view: self.state.view
		});

		Eternity.Modifications.transition({modifications: true});
	};

	var failure = (errno, response) => {
		button.prop('disabled', false).html('<i>&#10094;</i> Resurrect <i>&#10095;</i>');
		Eternity.GenericError.render({msg: response});
	};

	window.resurrectCharacter({
		request: JSON.stringify({
			oldSave: self.state.info.absolutePath
			, savedYet: Eternity.Modifications.state.savedYet
			// Synthetic dead-companion GUIDs look like "dead:<registry key>".
			, companion: guid.replace(/^dead:/, '')
		})
		, onSuccess: success
		, onFailure: failure
	});
};

SavedGame.prototype.switchView = function (view) {
	var self = this;
	self.transition({view: view});
};

SavedGame.prototype.update = function (e) {
	var self = this;
	var element = $(e.currentTarget);
	var isCol = element.prop('nodeName') === 'TD';
	var isDropdown = element.prop('nodeName') === 'SELECT';
	var value = isCol ? element.text() : element.val();
	var key = isDropdown ? element.parent().data('fullkey') : element.data('fullkey');

	if (key === null || key.length < 1) {
		return;
	}

	if (key.indexOf('.') < 0) {
		var character =
			self.state.saveData.characters.filter(c => c.GUID === self.state.activeCharacter)[0];
		character.stats[key].value = value;
		self.refreshSheet();
		if (key === 'OverrideName') {
			self.refreshName(character);
		}
	} else {
		var ex = key.split('.');
		self.state.saveData.globals[ex[0]][ex[1]][ex[2]].value = value;
	}

	Eternity.Modifications.transition({modifications: true});
};

$.extend(SavedGame.prototype, Renderer.prototype);
