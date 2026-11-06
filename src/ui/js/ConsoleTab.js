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

// The Console view: achievements toggle, the New Game Settings panel
// (difficulty + game modes, mirroring the in-game dialog) and a command
// console that emulates the save-representable part of the POE1 console.
// Commands edit the same in-memory saveData the other editors use; nothing
// touches the disk until the save file is written with Save.
var ConsoleTab = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, working: false     // an achievements request is in flight
		, log: []
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	var saveData = () => Eternity.SavedGame.state.saveData || {};

	// ---- saveData accessors -------------------------------------------------

	var globalEntry = (root, component, key) => {
		var globals = saveData().globals;
		return globals && globals[root] && globals[root][component]
			? globals[root][component][key]
			: undefined;
	};

	var gameState = key => globalEntry('Global', 'GameState', key);

	var markDirty = () => Eternity.Modifications.transition({modifications: true});

	// ---- achievements toggle ----------------------------------------------

	var renderAchievements = () => {
		var disabled = !!saveData().achievementsDisabled;
		var button = self.html.achievementsToggle.off().removeAttr('title');

		if (self.state.working) {
			button.html('<i class="fa fa-spinner fa-pulse"></i> <span>Working&hellip;</span>');
		} else if (disabled) {
			button.html('<i class="fa fa-ban"></i> <span>Achievements Disabled</span>');
			button.attr('title', 'Click to enable achievements for this save');
		} else {
			button.html('<i class="fa fa-trophy"></i> <span>Achievements Enabled</span>');
			button.attr('title', 'Click to disable achievements for this save');
		}

		button.toggleClass('pm-btn-danger', disabled);

		if (self.state.enabled && !self.state.working) {
			button.prop('disabled', false).click(self.toggleAchievements.bind(self));
		} else {
			button.prop('disabled', true);
		}
	};

	// ---- console ------------------------------------------------------------

	var print = (text, cls) => {
		self.state.log.push({text: text, cls: cls || ''});
		if (self.state.log.length > 200) {
			self.state.log = self.state.log.slice(-200);
		}
	};

	var renderLog = () => {
		var output = self.html.consoleOutput.empty();
		self.state.log.forEach(line =>
			output.append($('<div>').addClass('console-line ' + line.cls).text(line.text)));
		output.scrollTop(output.prop('scrollHeight'));
	};

	var characters = () =>
		(saveData().characters || []).filter(c => !c.resurrectable && c.stats);

	var findTarget = token => {
		var lower = (token || '').toLowerCase();
		if (lower === 'player') {
			return characters().filter(c => c.isMainCharacter)[0];
		}

		var matches = characters().filter(c => c.name.toLowerCase().startsWith(lower));
		return matches.length === 1 ? matches[0] : undefined;
	};

	var statOf = (character, key) => character.stats[key];

	var setNumericStat = (character, key, value) => {
		var stat = statOf(character, key);
		if (!stat) {
			return false;
		}

		stat.value = value;
		return true;
	};

	// Unique case-insensitive prefix resolution ("mech" -> "Mechanics").
	var resolveName = (token, names) => {
		var lower = (token || '').toLowerCase();
		var matches = names.filter(n => n.toLowerCase().indexOf(lower) === 0);
		return matches.length === 1 ? matches[0] : undefined;
	};

	var attributeNames =
		['Might', 'Constitution', 'Dexterity', 'Perception', 'Intellect', 'Resolve'];
	var skillNames = ['Stealth', 'Athletics', 'Lore', 'Mechanics', 'Survival', 'Crafting'];

	// CharacterStats.GetPointsForSkillLevel caps a skill at rank 20, and
	// rank N costs N(N+1)/2 points -- which is what the save stores.
	var MAX_SKILL_RANK = 20;

	var intArg = token => {
		var n = parseInt(token, 10);
		return isNaN(n) ? undefined : n;
	};

	var commands = {
		help: {
			args: ''
			, help: 'List the available commands.'
			, run: () => {
				print('Available commands (edits apply when you Save):');
				Object.keys(commands).sort().forEach(name =>
					print('  ' + name + ' ' + commands[name].args + ' — ' + commands[name].help));
			}
		}

		, addexperience: {
			args: '<amount>'
			, help: 'Give every party member experience.'
			, run: args => {
				var xp = intArg(args[0]);
				if (xp === undefined) return print('Usage: AddExperience <amount>', 'error');
				characters().forEach(c => {
					var stat = statOf(c, 'Experience');
					if (stat) stat.value = parseInt(stat.value, 10) + xp;
				});
				markDirty();
				print('Party experience increased by ' + xp
					+ '. Level-ups are offered by the game on load.', 'ok');
			}
		}

		, addexperienceplayer: {
			args: '<amount>'
			, help: 'Give only the main character experience.'
			, run: args => {
				var xp = intArg(args[0]);
				if (xp === undefined) return print('Usage: AddExperiencePlayer <amount>', 'error');
				var player = findTarget('player');
				if (!player) return print('No main character found.', 'error');
				var stat = statOf(player, 'Experience');
				stat.value = parseInt(stat.value, 10) + xp;
				markDirty();
				print(player.name + ' gains ' + xp + ' experience.', 'ok');
			}
		}

		, addexperiencetolevel: {
			args: '<level>'
			, help: 'Raise party members below the level to exactly that level\'s XP.'
			, run: args => {
				var level = intArg(args[0]);
				if (level === undefined || level < 1 || level > 16) {
					return print('Usage: AddExperienceToLevel <1-16>', 'error');
				}

				// The same level-up as Heal, level up and resupply.
				var raised = Eternity.PartyCare.levelTo(level);
				print(raised.length + ' party member(s) set to '
					+ Eternity.PartyCare.experienceForLevel(level) + ' XP (level '
					+ level + ').', 'ok');
			}
		}

		, healparty: {
			args: ''
			, help: 'Restore the active party\'s health and stamina to full when the save loads.'
			, run: () => {
				// The game's own full-refill flag, as the dialog sets it: the
				// game works out each maximum itself on load.
				var marked = Eternity.PartyCare.heal();
				print(marked > 0
					? marked + ' party member(s) will be at full health and stamina '
						+ 'when the save loads.'
					: 'Nobody in the active party is left to heal.', 'ok');
			}
		}

		, attributescore: {
			args: '<player|name> <attribute> <score>'
			, help: 'Set a base attribute. Player only — the game rebuilds companion attributes.'
			, run: args => {
				var target = findTarget(args[0]);
				var attribute = resolveName(args[1], attributeNames);
				var score = intArg(args[2]);
				if (!target || !attribute || score === undefined) {
					return print('Usage: AttributeScore <player|name> <attribute> <score>', 'error');
				}
				if (target.isCompanion) {
					return print('The game recalculates companion attributes from their '
						+ 'template on every load; this edit would not stick.', 'error');
				}
				setNumericStat(target, 'Base' + attribute, score);
				markDirty();
				print(target.name + '\'s ' + attribute + ' is now ' + score + '.', 'ok');
			}
		}

		// The in-game command writes <Skill>Bonus, and that is no use in a
		// save: CharacterStats.Restored() re-derives every <Skill>Bonus from
		// the applied status effects on load, so the edit is undone before
		// the player ever sees it. The stored points are what nothing
		// recomputes, so this sets a rank the way the Skills panel does.
		, skill: {
			args: '<player|name> <skill> <rank>'
			, help: 'Set a skill rank. Writes the stored points rather than the '
				+ 'bonus the in-game command uses, which the game recomputes '
				+ 'on load.'
			, run: args => {
				var target = findTarget(args[0]);
				var skill = resolveName(args[1], skillNames);
				var rank = intArg(args[2]);
				if (!target || !skill || rank === undefined
					|| rank < 0 || rank > MAX_SKILL_RANK) {

					return print('Usage: Skill <player|name> <skill> <rank '
						+ '0-' + MAX_SKILL_RANK + '>', 'error');
				}

				var points = rank * (rank + 1) / 2;
				if (!setNumericStat(target, skill + 'Skill', points)) {
					return print(target.name + ' has no ' + skill + ' skill.'
						, 'error');
				}

				markDirty();
				print(target.name + '\'s ' + skill + ' is now rank ' + rank
					+ ' (' + points + ' pts).', 'ok');
			}
		}

		, giveplayermoney: {
			args: '<amount>'
			, help: 'Add copper pands to the party stash.'
			, run: args => {
				var amount = intArg(args[0]);
				if (amount === undefined) return print('Usage: GivePlayerMoney <amount>', 'error');
				saveData().currency = (parseFloat(saveData().currency) || 0) + amount;
				Eternity.CurrencyEditor.render({enabled: true, amount: saveData().currency});
				markDirty();
				print('Party money is now ' + saveData().currency + ' cp.', 'ok');
			}
		}

		, removeplayermoney: {
			args: '<amount>'
			, help: 'Remove copper pands from the party stash.'
			, run: args => {
				var amount = intArg(args[0]);
				if (amount === undefined) return print('Usage: RemovePlayerMoney <amount>', 'error');
				saveData().currency = Math.max(0, (parseFloat(saveData().currency) || 0) - amount);
				Eternity.CurrencyEditor.render({enabled: true, amount: saveData().currency});
				markDirty();
				print('Party money is now ' + saveData().currency + ' cp.', 'ok');
			}
		}

		, setglobalvalue: {
			args: '<name> <value>'
			, help: 'Set a narrative global variable.'
			, run: args => {
				var value = intArg(args[1]);
				var entry = globalEntry('InGameGlobal', 'GlobalVariables', args[0]);
				if (!args[0] || value === undefined) {
					return print('Usage: SetGlobalValue <name> <value>', 'error');
				}
				if (!entry) return print('No global named \'' + args[0] + '\' in this save.', 'error');
				entry.value = value;
				markDirty();
				print(args[0] + ' = ' + value, 'ok');
			}
		}

		, incrementglobalvalue: {
			args: '<name> <delta>'
			, help: 'Add to a narrative global variable.'
			, run: args => {
				var delta = intArg(args[1]);
				var entry = globalEntry('InGameGlobal', 'GlobalVariables', args[0]);
				if (!args[0] || delta === undefined) {
					return print('Usage: IncrementGlobalValue <name> <delta>', 'error');
				}
				if (!entry) return print('No global named \'' + args[0] + '\' in this save.', 'error');
				entry.value = parseInt(entry.value, 10) + delta;
				markDirty();
				print(args[0] + ' = ' + entry.value, 'ok');
			}
		}

		, printglobal: {
			args: '<name>'
			, help: 'Show the value of a narrative global variable.'
			, run: args => {
				var entry = globalEntry('InGameGlobal', 'GlobalVariables', args[0]);
				if (!entry) return print('No global named \'' + args[0] + '\' in this save.', 'error');
				print(args[0] + ' = ' + entry.value);
			}
		}

		, adjustprestige: {
			args: '<amount>'
			, help: 'Adjust stronghold prestige (can be negative).'
			, run: args => adjustStronghold('Prestige', intArg(args[0]), 'AdjustPrestige')
		}

		, adjustsecurity: {
			args: '<amount>'
			, help: 'Adjust stronghold security (can be negative).'
			, run: args => adjustStronghold('Security', intArg(args[0]), 'AdjustSecurity')
		}

		, addturns: {
			args: '<count>'
			, help: 'Add stronghold turns.'
			, run: args => adjustStronghold('AvailableTurns', intArg(args[0]), 'AddTurns')
		}

		, iroll20s: {
			args: ''
			, help: 'Toggle the save\'s CheatsEnabled flag (does NOT touch achievements here).'
			, run: () => {
				var entry = gameState('CheatsEnabled');
				if (!entry) return print('GameState not found in this save.', 'error');
				var on = entry.value === true || entry.value === 'true';
				entry.value = on ? 'false' : 'true';
				markDirty();
				print(on ? 'Cheats Disabled' : 'Cheats Enabled', 'ok');
				print('Unlike the in-game console, this does not change the achievements '
					+ 'lock — use the button above for that.');
			}
		}
	};

	var adjustStronghold = (key, amount, usage) => {
		if (amount === undefined) return print('Usage: ' + usage + ' <amount>', 'error');
		var entry = globalEntry('InGameGlobal', 'Stronghold', key);
		if (!entry) {
			return print('This save has no stronghold data (visit Caed Nua first).', 'error');
		}
		entry.value = (parseFloat(entry.value) || 0) + amount;
		markDirty();
		print('Stronghold ' + key + ' is now ' + entry.value + '.', 'ok');
	};

	// ---- in-game-only command reference ------------------------------------

	// Everything below runs in the GAME's own console (after IRoll20s), not
	// this editor — most need a live GameObject in a loaded scene, so there
	// is no save-file equivalent to emulate. Sourced from the official
	// console command list (pillarsofeternity.fandom.com/wiki/Console),
	// filtered to commands that exist in Pillars of Eternity 1 and trimmed
	// of pure engine/modding internals (camera splines, AI pathing debug,
	// arbitrary code execution, etc.) that no player would reach for.
	var referenceMostUseful = [
		{name: 'God', args: '', desc: 'Makes the whole party invulnerable to damage. '
			+ 'Enter it again to turn off.'}
		, {name: 'Invisible', args: '', desc: 'Makes the party invisible — enemies will '
			+ 'not start combat with you (scripted encounters and dialogue still trigger).'}
		, {name: 'NoFog', args: '', desc: 'Removes fog of war so the whole map is revealed.'}
		, {name: 'Kill', args: '<target>', desc: 'Instantly kills the target. Use a GUID, '
			+ 'an object name, or the keyword "player".'}
		, {name: 'KillAllEnemies', args: '', desc: 'Kills every hostile creature currently '
			+ 'on the map.'}
		, {name: 'GiveItem', args: '<itemName> <count>', desc: 'Adds count copies of an '
			+ 'item to the party stash.'}
		, {name: 'RemoveItem', args: '<itemName>', desc: 'Removes an item from the party '
			+ 'stash.'}
		, {name: 'AddTalent', args: '<target> <talentName>', desc: 'Grants a character a '
			+ 'talent for free, without spending a talent slot.'}
		, {name: 'AddAbility', args: '<target> <abilityName>', desc: 'Grants a character '
			+ 'a spell, ability, or phrase.'}
		, {name: 'TeleportPartyToLocation', args: '<targetGuid>', desc: 'Instantly moves '
			+ 'the whole party to the location of another object or character.'}
		, {name: 'AreaTransition', args: '<mapName> <startPoint>', desc: 'Loads a '
			+ 'different map and drops the party at one of its named start points.'}
		, {name: 'UnlockAllMaps', args: '', desc: 'Reveals every location on the world '
			+ 'map.'}
		, {name: 'RevealAll', args: '', desc: 'Reveals every hidden trap and hidden '
			+ 'stash on the current map.'}
		, {name: 'ManageParty', args: '', desc: 'Opens the in-game party management '
			+ 'screen directly.'}
		, {name: 'UnlockAll', args: '', desc: 'Unlocks every locked container and door '
			+ 'in the current area.'}
		, {name: 'SetClass', args: '<target> <className>', desc: 'Changes a character\'s '
			+ 'class.'}
		, {name: 'DispositionAddPoints', args: '<axis> <strength>', desc: 'Adds Watcher '
			+ 'disposition points along an axis (Benevolent, Aggressive, etc.).'}
		, {name: 'ReputationAddPoints', args: '<faction> <axis> <strength>', desc: 'Adds '
			+ 'reputation with a faction.'}
		, {name: 'OpenCharacterCreation', args: '', desc: 'Reopens character creation for '
			+ 'the Watcher. Caution: worn items and inventory are lost.'}
	];

	var referenceRest = [
		{name: 'AddAbilityWithPopup', args: '<target> <abilityName>', desc: 'Same as '
			+ 'AddAbility, but also shows the player a popup notification.'}
		, {name: 'AddPrisoner', args: '<targetGuid>', desc: 'Sends a character to the '
			+ 'Stronghold dungeon as a prisoner.'}
		, {name: 'AddToParty', args: '<targetGuid>', desc: 'Adds a character or creature '
			+ 'already present in the scene to the active party.'}
		, {name: 'ActivateStronghold', args: '', desc: 'Unlocks access to the Stronghold '
			+ 'screen immediately.'}
		, {name: 'AdvanceQuest', args: '<questId>', desc: 'Advances a quest to its next '
			+ 'recorded stage.'}
		, {name: 'ApplyAffliction', args: '<targetGuid> <affliction>', desc: 'Applies a '
			+ 'specific affliction (e.g. Confused, Frostbite) to a target.'}
		, {name: 'AuditSaveGame', args: '', desc: 'Writes a full dump of the current '
			+ 'save\'s data to a CSV file in your Saved Games folder.'}
		, {name: 'Autosave', args: '', desc: 'Triggers an autosave, same as pressing F5.'}
		, {name: 'BloodyMess', args: '', desc: 'Every killing blow makes the enemy '
			+ 'explode dramatically, with a screen shake.'}
		, {name: 'CharacterUseAbility', args: '<targetGuid> <stringId>', desc: 'Forces a '
			+ 'character to immediately cast a specific ability.'}
		, {name: 'Chipmunk', args: '', desc: 'Raises the pitch of every sound in the game '
			+ 'by 75%. Purely cosmetic (and very silly).'}
		, {name: 'ClearAchievements', args: '', desc: 'Resets progress on every Steam '
			+ 'achievement. Cannot be undone from in-game.'}
		, {name: 'CraftingDebug', args: '', desc: 'Adds a large supply of every crafting '
			+ 'ingredient to the stash.'}
		, {name: 'Damage', args: '<amount>', desc: 'Deals damage to every party member '
			+ 'at once.'}
		, {name: 'DealDamage', args: '<targetGuid> <amount>', desc: 'Deals a specific '
			+ 'amount of damage to a target.'}
		, {name: 'DisableFogOfWar', args: '', desc: 'Same as NoFog: removes fog of war '
			+ 'entirely.'}
		, {name: 'ExportGlobals', args: '', desc: 'Writes every global variable and its '
			+ 'current value to a text file.'}
		, {name: 'FindCharacter', args: '<name>', desc: 'Prints every character in the '
			+ 'scene whose name contains the given text — useful for finding a GUID to '
			+ 'use with other commands.'}
		, {name: 'FindObject', args: '<name>', desc: 'Prints every scene object whose '
			+ 'name contains the given text, with its GUID if it has one.'}
		, {name: 'FreeRecipesToggle', args: '', desc: 'Toggles crafting without needing '
			+ 'to own the ingredients.'}
		, {name: 'GiveItemAndEquip', args: '<targetGuid> <itemName> <primary>'
			, desc: 'Gives a character an item and equips it immediately.'}
		, {name: 'GiveItemToNPC', args: '<targetGuid> <itemName> <count>', desc: 'Gives '
			+ 'an item to an NPC\'s inventory rather than the party stash.'}
		, {name: 'HelmetVisibility', args: '<true|false>', desc: 'Shows or hides helmets '
			+ 'on every party member.'}
		, {name: 'KnockDown', args: '<targetGuid> <duration>', desc: 'Knocks a target '
			+ 'down for duration seconds.'}
		, {name: 'LearnAllAbilities', args: '<targetGuid> <tableId>', desc: 'Grants a '
			+ 'character every ability in a given progression table at once.'}
		, {name: 'Lock', args: '<targetGuid>', desc: 'Locks a specific container.'}
		, {name: 'LoadLevel', args: '<name>', desc: 'Loads a map by name and transitions '
			+ 'to it directly.'}
		, {name: 'MarkConversationNodeAsRead', args: '<conversationId> <nodeId>'
			, desc: 'Marks a dialogue node as already seen, clearing its "new" indicator.'}
		, {name: 'NoDamage', args: '<true|false>', desc: 'Toggles damage for every '
			+ 'character in the scene, party and enemies alike.'}
		, {name: 'OpenInn', args: '<innGuid>', desc: 'Opens a specific inn\'s rest '
			+ 'window.'}
		, {name: 'OpenRecruitment', args: '<storeGuid>', desc: 'Opens the adventurer '
			+ 'recruitment window for a store.'}
		, {name: 'OpenStore', args: '<storeGuid>', desc: 'Opens a specific vendor\'s '
			+ 'store window.'}
		, {name: 'PrintString', args: '<text>', desc: 'Prints the given text to the '
			+ 'combat log.'}
		, {name: 'ProneParty', args: '<duration>', desc: 'Knocks the whole party prone '
			+ 'for duration seconds.'}
		, {name: 'RemoveAffliction', args: '<targetGuid> <affliction>', desc: 'Removes a '
			+ 'specific affliction from a target.'}
		, {name: 'RemoveFromParty', args: '<targetGuid>', desc: 'Sends a companion back '
			+ 'to the bench (Stronghold roster).'}
		, {name: 'RemoveItemIncludingEquipped', args: '<itemName>', desc: 'Removes an '
			+ 'item from the stash AND from any party member who has it equipped.'}
		, {name: 'RemoveItemStack', args: '<itemName> <count>', desc: 'Removes a '
			+ 'specific quantity of an item from the stash.'}
		, {name: 'RemoveTalent', args: '<targetGuid> <talentName>', desc: 'Removes a '
			+ 'talent or ability from a target.'}
		, {name: 'SetBackground', args: '<targetGuid> <background>', desc: 'Changes a '
			+ 'character\'s background.'}
		, {name: 'SetDeity', args: '<targetGuid> <deity>', desc: 'Changes which god a '
			+ 'character follows.'}
		, {name: 'SetHealth', args: '<targetGuid> <value>', desc: 'Sets a target\'s '
			+ 'current health directly.'}
		, {name: 'SetInvunerable', args: '<targetGuid> <true|false>', desc: 'Makes a '
			+ 'single target immune to all incoming damage — the per-target version of '
			+ 'God.'}
		, {name: 'SetIsHostile', args: '<targetGuid> <isHostile>', desc: 'Makes an NPC '
			+ 'hostile or friendly toward the party.'}
		, {name: 'SetMaxFPS', args: '<value>', desc: 'Caps the game\'s frame rate.'}
		, {name: 'SetPaladinOrder', args: '<targetGuid> <order>', desc: 'Changes a '
			+ 'Paladin\'s sworn order.'}
		, {name: 'SetPlayerBackground', args: '<background>', desc: 'Changes the '
			+ 'Watcher\'s background specifically.'}
		, {name: 'SetPreventDeath', args: '<targetGuid> <true|false>', desc: 'Stops a '
			+ 'target from dying, without making them fully invulnerable.'}
		, {name: 'SetStamina', args: '<targetGuid> <value>', desc: 'Sets a target\'s '
			+ 'current stamina directly.'}
		, {name: 'SetTeamRelationship', args: '<teamA> <teamB> <relationship>'
			, desc: 'Sets whether two factions are hostile, neutral, or friendly '
			+ 'toward each other.'}
		, {name: 'ShowPrisoners', args: '', desc: 'Lists every prisoner currently held '
			+ 'at the Stronghold.'}
		, {name: 'ShowScalers', args: '', desc: 'Prints the currently active difficulty '
			+ 'scalers to the combat log.'}
		, {name: 'StartConversation', args: '<targetGuid> <conversationId>'
			, desc: 'Starts a specific conversation with a character.'}
		, {name: 'StartQuest', args: '<questId>', desc: 'Begins a quest immediately.'}
		, {name: 'StrongholdBuild', args: '<type>', desc: 'Constructs a specific '
			+ 'Stronghold building.'}
		, {name: 'StrongholdBuildAll', args: '', desc: 'Instantly constructs every '
			+ 'Stronghold building.'}
		, {name: 'TeleportAvailablePartyToLocation', args: '<targetGuid>'
			, desc: 'Teleports only the party members currently available (not benched) '
			+ 'to a target.'}
		, {name: 'TeleportObjectToLocation', args: '<objectGuid> <targetGuid>'
			, desc: 'Teleports any object to the location of another object.'}
		, {name: 'TeleportPlayerToLocation', args: '<targetGuid>', desc: 'Teleports only '
			+ 'the Watcher — not the whole party — to a target\'s location.'}
		, {name: 'ToggleNeedsGrimoire', args: '', desc: 'Toggles whether a Wizard needs '
			+ 'a grimoire equipped to cast spells from it.'}
		, {name: 'ToggleScaler', args: '<scaler>', desc: 'Toggles a specific difficulty '
			+ 'scaler (creature level, attributes, etc.) on or off.'}
		, {name: 'ToggleSpellLimit', args: '', desc: 'Removes the per-rest limit on how '
			+ 'many spells can be cast.'}
		, {name: 'TransferItem', args: '<fromGuid> <toGuid> <itemName> <count>'
			, desc: 'Moves items between two inventories (e.g. a character and a '
			+ 'container).'}
		, {name: 'TriggerQuestAddendum', args: '<questId> <addendumId>', desc: 'Adds a '
			+ 'specific journal addendum to a quest.'}
		, {name: 'TriggerQuestEndState', args: '<questId> <endStateId>', desc: 'Marks a '
			+ 'quest complete with a specific ending.'}
		, {name: 'TriggerQuestFailState', args: '<questId> <endStateId>', desc: 'Marks a '
			+ 'quest failed with a specific ending.'}
		, {name: 'Unlock', args: '<targetGuid>', desc: 'Unlocks a specific container or '
			+ 'door.'}
		, {name: 'UnlockBestiary', args: '', desc: 'Unlocks every Bestiary entry.'}
		, {name: 'UnlockBiography', args: '', desc: 'Unlocks every Biography entry.'}
		, {name: 'WorldMapSetVisibility', args: '<map> <visibility>', desc: 'Changes '
			+ 'whether a given map is visible/explored on the world map.'}
	];

	var renderReference = () => {
		var table = self.html.referenceTable.find('tbody').empty();
		var addRow = cmd => {
			var syntax = cmd.args ? cmd.name + ' ' + cmd.args : cmd.name;
			table.append(
				$('<tr>')
					.append($('<td>').addClass('reference-command').data('key', cmd.name)
						.text(syntax))
					.append($('<td>').text(cmd.desc)));
		};

		referenceMostUseful.forEach(addRow);
		referenceRest.slice().sort((a, b) => a.name.localeCompare(b.name)).forEach(addRow);
	};

	var filterReference = () => {
		var searchString = self.html.searchReference.val().toLowerCase();
		var rows = self.html.referenceTable.find('tbody tr');
		if (searchString.length < 1) {
			rows.show();
			return;
		}

		rows.hide();
		rows.filter((i, row) =>
			$(row).find('td').text().toLowerCase().includes(searchString)).show();
	};

	var execute = input => {
		var trimmed = input.trim();
		if (trimmed.length < 1) {
			return;
		}

		print('> ' + trimmed, 'echo');
		var parts = trimmed.split(/\s+/);
		var name = parts[0].toLowerCase();
		var command = commands[name];

		if (!command) {
			var known = Object.keys(commands);
			var resolved = resolveName(name, known);
			command = resolved ? commands[resolved] : undefined;
		}

		if (command) {
			// A command that changes the save is one step of the history,
			// named as it was typed.
			Eternity.EditHistory.labelled(trimmed, () => command.run(parts.slice(1)));
		} else {
			print('Unknown command \'' + parts[0] + '\'. Type \'help\' for the list. Commands '
				+ 'that only exist at runtime (God, NoFog, teleports…) cannot be emulated '
				+ 'in a save editor.', 'error');
		}

		renderLog();
	};

	self.init = () => {
		self.html.consoleForm.submit(() => {
			execute(self.html.consoleInput.val());
			self.html.consoleInput.val('');
			return false;
		});

		self.html.searchReference.keyup(filterReference);
		renderReference();
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, {log: self.state.log}, newState);

		renderAchievements();
		renderLog();

		if (self.state.log.length === 0 && self.state.enabled) {
			print('Eternity Keeper console — commands are applied to the save data and '
				+ 'written when you click Save. Type \'help\' to begin.');
			renderLog();
		}
	};
};

ConsoleTab.prototype.toggleAchievements = function () {
	var self = this;
	var enable = !!(Eternity.SavedGame.state.saveData
		&& Eternity.SavedGame.state.saveData.achievementsDisabled);
	self.transition({working: true});

	var finish = () => self.transition({working: false});

	var success = response => {
		response = JSON.parse(response);

		if (response.error) {
			finish();
			Eternity.GenericError.render({msg: response.error});
			return;
		}

		// Through adopt like every other Apply: the three-way merge keeps
		// unsaved edits made elsewhere, the flag and its marker in the globals
		// arrive from the reply, and Undo can put them back. (This used to
		// copy the two values in place, which left the merge's idea of what
		// the file held behind, so an undo would have kept the new flag as if
		// the user had typed it.)
		Eternity.SavedGame.transition({saveData: Eternity.SavedGame.adopt(response
			, enable ? 'Turn achievements back on' : 'Turn achievements off')});

		finish();
		Eternity.Modifications.transition({modifications: true});
	};

	var failure = (errno, response) => {
		finish();
		Eternity.GenericError.render({msg: response});
	};

	window.enableAchievements({
		request: JSON.stringify({
			oldSave: Eternity.SavedGame.state.info.absolutePath
			, savedYet: Eternity.Modifications.state.savedYet
			, enable: enable
		})
		, onSuccess: success
		, onFailure: failure
	});
};

$.extend(ConsoleTab.prototype, Renderer.prototype);
