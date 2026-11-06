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

// The Stronghold view: Caed Nua laid out the way the game's own stronghold
// screen lays it out -- the upgrade list down the middle, the prestige and
// security gauges in a rail on the right.
//
// Two things about the save shape drive the whole design:
//
//   * Whether the stronghold exists at all is a single flag,
//     Stronghold.SerializedIsActivated. Until the player takes the keep there
//     is nothing to manage, and the view says so instead of pretending.
//
//   * Prestige and Security are plain persisted numbers. The game adds an
//     upgrade's own adjustments once, when it is built, and takes them off
//     again when it is destroyed; nothing recalculates them from the list. So
//     ticking an upgrade here has to move those numbers too, which is why the
//     rail shows what a pending change will do to them before it is applied.
//
// Hirelings and prisoners can be let go, the way the game's own Dismiss and
// Release buttons do it: a dismissed hireling who was paid takes their
// Prestige and Security with them, and both clear the global that says they
// are there. Nobody can be taken on here -- they arrive through conversations
// and visitors that change the world too.
var StrongholdEditor = function () {
	var self = this;

	var defaultState = {
		enabled: false
		, working: false
	};

	self.state = $.extend({}, defaultState);
	self.html = {};

	// Staged edits, applied together. Upgrades are held as a set of keys so a
	// tick and an untick of the same thing cancel out rather than stacking.
	var pending = {};       // upgrade key -> true to build, false to demolish
	var numbers = {};       // stronghold variable -> new value
	var dismissed = {};     // hireling key -> true
	var released = {};      // prisoner key -> true
	var filter = 'all';
	var status = '';

	var saveData = () => Eternity.SavedGame.state.saveData || {};
	var stronghold = () => saveData().stronghold || {};
	var catalog = () => stronghold().catalog || [];
	var hirelings = () => stronghold().hirelings || [];
	var prisoners = () => stronghold().prisoners || [];

	var markDirty = () =>
		Eternity.Modifications.transition({modifications: true});

	// ---- reading the current state -----------------------------------------

	var builtNow = () => {
		var built = {};
		(stronghold().upgrades || []).forEach(key => built[key] = true);
		return built;
	};

	// What the stronghold would look like once everything staged is applied.
	var builtAfter = () => {
		var built = builtNow();
		Object.keys(pending).forEach(key => {
			if (pending[key]) {
				built[key] = true;
			} else {
				delete built[key];
			}
		});

		return built;
	};

	var numberNow = variable => {
		var current = stronghold();
		var value = current[variable.toLowerCase()];
		return typeof value === 'number' ? value : 0;
	};

	var numberAfter = variable =>
		numbers.hasOwnProperty(variable) ? numbers[variable] : numberNow(variable);

	// Prestige and Security after the staged upgrades are paid for, which is
	// the arithmetic Stronghold.CompleteBuildingUpgrade and DestroyUpgrade do
	// one upgrade at a time.
	var adjustedBy = which => {
		var total = 0;
		var built = builtNow();

		catalog().forEach(upgrade => {
			if (!pending.hasOwnProperty(upgrade.key)) {
				return;
			}

			// Only a change of state moves the numbers: re-ticking something
			// already built is what HasUpgrade() guards against in the game.
			if (pending[upgrade.key] && !built[upgrade.key]) {
				total += upgrade[which] || 0;
			} else if (!pending[upgrade.key] && built[upgrade.key]) {
				total -= upgrade[which] || 0;
			}
		});

		return total;
	};

	// What the staged dismissals take off. Stronghold.DismissHireling only
	// subtracts for a hireling who is Paid: an unpaid one's share already came
	// off when the pay cycle could not pay them.
	var dismissedWorth = which => hirelings()
		.filter(hireling => dismissed[hireling.key] && hireling.paid)
		.reduce((total, hireling) => total + (hireling[which] || 0), 0);

	var projected = variable => {
		var base = numberAfter(variable);
		if (variable === 'Prestige') {
			return base + adjustedBy('prestige') - dismissedWorth('prestige');
		}

		if (variable === 'Security') {
			return base + adjustedBy('security') - dismissedWorth('security');
		}

		return base;
	};

	var dirty = () =>
		Object.keys(pending).length > 0 || Object.keys(numbers).length > 0
		|| Object.keys(dismissed).length > 0 || Object.keys(released).length > 0;

	/** Whether anything is staged that Apply has not written yet. */
	self.unapplied = () => dirty();

	// ---- the upgrade list ---------------------------------------------------

	var FILTERS = [
		{value: 'all', label: 'All'}
		, {value: 'built', label: 'Built'}
		, {value: 'available', label: 'Available'}
		, {value: 'locked', label: 'Locked'}
	];

	var byKey = key => {
		var found = catalog().filter(upgrade => upgrade.key === key);
		return found.length > 0 ? found[0] : null;
	};

	// An upgrade is available when its prerequisite is built (or will be once
	// the staged changes go in), which is the same rule the game's own list
	// uses to grey a row out.
	var isAvailable = (upgrade, built) =>
		!upgrade.prerequisite || !!built[upgrade.prerequisite];

	var categoryOf = (upgrade, built) => {
		if (built[upgrade.key]) {
			return 'built';
		}

		return isAvailable(upgrade, built) ? 'available' : 'locked';
	};

	var renderFilters = () => {
		self.html.shUpgradeFilters.empty();

		FILTERS.forEach(entry => {
			var button = $('<button>')
				.addClass('sh-filter')
				.attr('type', 'button')
				.text(entry.label);

			if (filter === entry.value) {
				button.addClass('sh-filter-on');
			}

			button.on('click', () => {
				filter = entry.value;
				redraw();
			});

			self.html.shUpgradeFilters.append(button);
		});
	};

	var upgradeRow = (upgrade, built) => {
		var category = categoryOf(upgrade, built);
		var staged = pending.hasOwnProperty(upgrade.key);

		var row = $('<div>')
			.addClass('sh-upgrade')
			.addClass('sh-upgrade-' + category);

		if (staged) {
			row.addClass('sh-upgrade-staged');
		}

		var icon = $('<div>').addClass('sh-upgrade-icon');
		if (upgrade.icon) {
			icon.append($('<img>').attr('src', 'data:image/png;base64,' + upgrade.icon));
		}

		row.append(icon);

		var body = $('<div>').addClass('sh-upgrade-body');
		body.append($('<div>').addClass('sh-upgrade-name').text(upgrade.name));

		if (upgrade.description) {
			body.append($('<div>')
				.addClass('sh-upgrade-desc')
				.text(upgrade.description));
		}

		if (upgrade.prerequisite) {
			var required = byKey(upgrade.prerequisite);
			body.append($('<div>')
				.addClass('sh-upgrade-req')
				.toggleClass('sh-upgrade-req-met', !!built[upgrade.prerequisite])
				.text('Requires ' + (required ? required.name : upgrade.prerequisite)));
		}

		row.append(body);

		// Two fixed columns with the same icons the gauges use, so prestige
		// and security line up down the list instead of shuffling about as
		// the numbers change width.
		var stats = $('<div>').addClass('sh-upgrade-stats');
		stats.append($('<div>')
			.addClass('sh-upgrade-adj')
			.append(adjustment('prestige', 'fa-star', upgrade.prestige, 'Prestige'))
			.append(adjustment('security', 'fa-lock', upgrade.security, 'Security')));

		stats.append($('<div>')
			.addClass('sh-upgrade-cost')
			.text(upgrade.cost > 0
				? upgrade.cost.toLocaleString() + 'cp · '
					+ upgrade.days + (upgrade.days === 1 ? ' day' : ' days')
				: 'No cost'));

		if (upgrade.hasBoon) {
			stats.append($('<div>')
				.addClass('sh-upgrade-boon')
				.attr('title', 'Unlocks a resting bonus in Brighthollow')
				.text('Resting bonus'));
		}

		row.append(stats);

		var willBeBuilt = staged ? pending[upgrade.key] : !!built[upgrade.key];
		var button = $('<button>')
			.addClass('pm-btn sh-upgrade-btn')
			.attr('type', 'button')
			.text(willBeBuilt ? 'Built' : 'Not built')
			.toggleClass('sh-upgrade-btn-on', willBeBuilt);

		if (willBeBuilt && !upgrade.destructible) {
			// Destructible false means a stronghold attack cannot knock it
			// down; the editor can still remove it, so say why it is unusual
			// rather than blocking it.
			button.attr('title', 'The game never destroys this one on its own');
		}

		button.on('click', () => toggle(upgrade));
		row.append($('<div>').addClass('sh-upgrade-action').append(button));

		return row;
	};

	// The game prints "+0" rather than a bare zero, and dims it.
	var signed = value => (value >= 0 ? '+' : '') + (value || 0);

	var adjustment = (kind, icon, value, label) =>
		$('<span>')
			.addClass('sh-adj sh-adj-' + kind)
			.toggleClass('sh-adj-none', !value)
			.attr('title', label)
			.append($('<i>').addClass('fa ' + icon))
			.append($('<span>').addClass('sh-adj-value').text(signed(value)));

	var toggle = upgrade => {
		var built = builtNow();
		var isBuilt = !!built[upgrade.key];
		var wanted = pending.hasOwnProperty(upgrade.key)
			? !pending[upgrade.key]
			: !isBuilt;

		if (wanted === isBuilt) {
			// Back to where the save already is, so there is nothing to send.
			delete pending[upgrade.key];
		} else {
			pending[upgrade.key] = wanted;
		}

		// Demolishing something other upgrades were built on top of would leave
		// them orphaned, which the game's own tree never allows.
		if (!wanted) {
			dropDependants(upgrade.key);
		}

		redraw();
	};

	var dropDependants = key => {
		var built = builtAfter();
		catalog().forEach(upgrade => {
			if (upgrade.prerequisite === key && built[upgrade.key]) {
				pending[upgrade.key] = false;
				dropDependants(upgrade.key);
			}
		});
	};

	var renderUpgrades = () => {
		self.html.shUpgrades.empty();

		var built = builtAfter();
		var rows = catalog().filter(upgrade =>
			filter === 'all' || categoryOf(upgrade, built) === filter);

		if (catalog().length < 1) {
			self.html.shUpgrades.append($('<div>')
				.addClass('sh-empty')
				.text('The game data has not been read yet, so upgrades cannot '
					+ 'be listed. Read it from your game install in Settings.'));

			return;
		}

		if (rows.length < 1) {
			self.html.shUpgrades.append($('<div>')
				.addClass('sh-empty')
				.text('Nothing matches that filter.'));

			return;
		}

		rows.forEach(upgrade =>
			self.html.shUpgrades.append(upgradeRow(upgrade, built)));

		// Two columns on a wide window: without this an odd row out would
		// stretch across both on the last line.
		self.html.shUpgrades.append($('<div>').addClass('sh-upgrade-filler'));

		var total = catalog().length;
		var have = catalog().filter(upgrade => built[upgrade.key]).length;
		self.html.shUpgradeCount.text(have + ' of ' + total + ' built');
	};

	// ---- the right-hand rail ------------------------------------------------

	var GAUGES = [
		{variable: 'Prestige', label: 'Prestige', icon: 'fa-star'}
		, {variable: 'Security', label: 'Security', icon: 'fa-lock'}
	];

	var renderGauges = () => {
		self.html.shGauges.empty();

		GAUGES.forEach(gauge => {
			var now = numberNow(gauge.variable);
			var after = projected(gauge.variable);

			var tile = $('<div>').addClass('sh-gauge');
			tile.append($('<i>').addClass('fa ' + gauge.icon));
			tile.append($('<span>').addClass('sh-gauge-value').text(after));
			tile.append($('<span>').addClass('sh-gauge-label').text(gauge.label));

			if (after !== now) {
				tile.addClass('sh-gauge-changed');
				tile.append($('<span>')
					.addClass('sh-gauge-delta')
					.text('was ' + now));
			}

			self.html.shGauges.append(tile);
		});
	};

	// Every scalar worth editing, with the game's own name for it.
	var NUMBERS = [
		{variable: 'Prestige', label: 'Prestige'
			, hint: 'Raises what taxes bring in, and what bandits take.'}
		, {variable: 'Security', label: 'Security'
			, hint: 'Protects the takings, and foils prisoner escapes.'}
		// Measured in the game: three turns set here were played the moment the
		// save loaded -- turns 153-155 in the log, 1,737cp of taxes collected
		// and a companion's adventure resolved -- so they are not a bank.
		, {variable: 'AvailableTurns', label: 'Turns available'
			, hint: 'Turns waiting to be played. The game plays them as soon as the save loads: taxes are collected and events and adventures resolve.'}
		, {variable: 'm_currentTurn', label: 'Current turn'
			, hint: 'Taxes are collected every fifth turn.'}
		, {variable: 'm_Debt', label: 'Debt'
			, hint: 'Deducted from the next tax collection.'}
		, {variable: 'BonusTurnMoney', label: 'Bonus turn money'
			, hint: 'Added to the next collection on top of the roll.'}
	];

	var renderNumbers = () => {
		self.html.shNumbers.empty();

		NUMBERS.forEach(entry => {
			var row = $('<div>').addClass('sh-number');
			row.append($('<label>').text(entry.label).attr('title', entry.hint));

			var input = $('<input>')
				.addClass('form-control sh-number-input')
				.attr('type', 'number')
				.val(numberAfter(entry.variable));

			input.on('change', () => {
				var value = parseInt(input.val(), 10);
				if (isNaN(value)) {
					input.val(numberAfter(entry.variable));
					return;
				}

				if (value === numberNow(entry.variable)) {
					delete numbers[entry.variable];
				} else {
					numbers[entry.variable] = value;
				}

				redraw();
			});

			row.append(input);

			// Prestige and Security are also moved by the staged upgrades, so
			// show the total the save will actually end up with -- underneath
			// the input, not between it and its label, or the row breaks in
			// two.
			var total = projected(entry.variable);
			if (total !== numberAfter(entry.variable)) {
				row.append($('<span>')
					.addClass('sh-number-total')
					.attr('title', 'What this becomes once the staged upgrades '
						+ 'and dismissals are applied')
					.text('→ ' + total));
			}

			self.html.shNumbers.append(row);
		});
	};

	// ---- hirelings and prisoners -------------------------------------------

	// One row per person: the name and the button on one line, what they are
	// worth or who they are underneath. A staged row keeps its place and says
	// so, and the same button takes it back.
	var personRow = (name, staged, verb, undo, onToggle) => {
		var row = $('<div>')
			.addClass('sh-person')
			.toggleClass('sh-person-staged', staged);

		var head = $('<div>').addClass('sh-person-head');
		head.append($('<button>')
			.addClass('pm-btn sh-person-btn')
			.toggleClass('sh-upgrade-btn-on', staged)
			.attr('type', 'button')
			.text(staged ? undo : verb)
			.on('click', onToggle));

		head.append($('<span>').addClass('sh-person-name').text(name).attr('title', name));
		row.append(head);
		return row;
	};

	var toggleIn = (set, key) => {
		if (set[key]) {
			delete set[key];
		} else {
			set[key] = true;
		}

		redraw();
	};

	var renderHirelings = () => {
		self.html.shHirelings.empty();

		var staying = hirelings().filter(hireling => !dismissed[hireling.key]).length;
		self.html.shHirelingCount.text(
			staying + ' of ' + (stronghold().maxHirelings || 8));

		if (hirelings().length < 1) {
			self.html.shHirelings.append($('<div>')
				.addClass('sh-person-empty')
				.text('Nobody is on the payroll.'));

			return;
		}

		hirelings().forEach(hireling => {
			var staged = !!dismissed[hireling.key];
			var row = personRow(hireling.name, staged, 'Dismiss', 'Keep'
				, () => toggleIn(dismissed, hireling.key));

			var worth = $('<div>').addClass('sh-person-detail')
				.append(adjustment('prestige', 'fa-star', hireling.prestige, 'Prestige'))
				.append(adjustment('security', 'fa-lock', hireling.security, 'Security'))
				.append($('<span>').addClass('sh-person-pay')
					.text((hireling.costPerDay || 0) + 'cp a day'));

			if (!hireling.paid) {
				worth.append($('<span>')
					.addClass('sh-person-tag')
					.attr('title', 'The keep could not pay them, so their Prestige '
						+ 'and Security are not counted until it does. '
						+ 'Dismissing them takes nothing more off.')
					.text('unpaid'));
			}

			if (hireling.leaving) {
				worth.append($('<span>')
					.addClass('sh-person-tag')
					.attr('title', 'Leaves at the next pay day.')
					.text('leaving'));
			}

			row.append(worth);
			self.html.shHirelings.append(row);
		});
	};

	var renderPrisoners = () => {
		self.html.shPrisoners.empty();

		if (prisoners().length < 1) {
			self.html.shPrisoners.append($('<div>')
				.addClass('sh-person-empty')
				.text('The dungeon is empty.'));
		}

		prisoners().forEach(prisoner => {
			var row = personRow(prisoner.name, !!released[prisoner.key], 'Release'
				, 'Keep', () => toggleIn(released, prisoner.key));

			if (prisoner.description) {
				row.append($('<div>')
					.addClass('sh-person-detail sh-person-desc')
					.text(prisoner.description));
			}

			self.html.shPrisoners.append(row);
		});

		self.html.shPrisoners.append($('<div>')
			.addClass('sh-resident-note')
			.text('New hirelings and prisoners arrive in the game itself; the '
				+ 'editor can only let them go.'));
	};

	var renderResidents = () => {
		self.html.shResidents.empty();

		var current = stronghold();
		var rows = [
			{label: 'Companions waiting', value: current.companionsStored || 0}
			, {label: 'Erl takes a cut', value: current.erlTax ? 'Yes' : 'No'}
		];

		rows.forEach(entry => {
			self.html.shResidents.append($('<div>')
				.addClass('sh-resident')
				.append($('<span>').addClass('sh-resident-label').text(entry.label))
				.append($('<span>').addClass('sh-resident-value').text(entry.value)));
		});
	};

	var renderHead = () => {
		var current = stronghold();
		var turn = current.m_currentturn || 0;
		var turns = numberAfter('AvailableTurns');

		self.html.shTurn.text('Turn ' + turn + ' · '
			+ turns + (turns === 1 ? ' turn available' : ' turns available'));

		self.html.shApply.prop('disabled', !!self.state.working || !dirty());
		// The reply replaces the draft, so anything staged while an Apply is
		// on its way would be lost without a word.
		self.html.strongholdView.toggleClass('view-working', !!self.state.working);
		self.html.shRevert.prop('disabled', !!self.state.working || !dirty());
	};

	var renderStatus = () => {
		self.html.shStatus.text(status || '');
		self.html.shStatus.toggle(!!status);
	};

	var redraw = message => {
		if (message !== undefined) {
			status = message;
		}

		if (!self.state.enabled) {
			return;
		}

		if (!stronghold().activated) {
			self.html.shUnavailable.show();
			self.html.shMain.hide();
			return;
		}

		self.html.shUnavailable.hide();
		self.html.shMain.show();

		renderHead();
		renderFilters();
		renderUpgrades();
		renderGauges();
		renderNumbers();
		renderHirelings();
		renderPrisoners();
		renderResidents();
		renderStatus();

		if (dirty()) {
			markDirty();
		}
	};

	// ---- applying -----------------------------------------------------------

	// The order matters: the numbers go in first so the upgrades and the
	// dismissals add and subtract on top of them, exactly as the game's own
	// arithmetic would have.
	var changeList = () => {
		var changes = [];

		Object.keys(numbers).forEach(variable => changes.push({
			kind: 'setNumber', name: variable, number: numbers[variable]}));

		var built = builtNow();
		catalog().forEach(upgrade => {
			if (!pending.hasOwnProperty(upgrade.key)) {
				return;
			}

			if (pending[upgrade.key] && !built[upgrade.key]) {
				changes.push({kind: 'addUpgrade', name: upgrade.key});
			} else if (!pending[upgrade.key] && built[upgrade.key]) {
				changes.push({kind: 'removeUpgrade', name: upgrade.key});
			}
		});

		hirelings().filter(hireling => dismissed[hireling.key]).forEach(hireling =>
			changes.push({kind: 'dismissHireling', name: hireling.key}));

		prisoners().filter(prisoner => released[prisoner.key]).forEach(prisoner =>
			changes.push({kind: 'releasePrisoner', name: prisoner.key}));

		return changes;
	};

	self.apply = () => {
		var changes = changeList();
		if (changes.length < 1) {
			redraw('Nothing to apply.');
			return;
		}

		self.state.working = true;
		redraw('Applying…');

		window.updateStronghold({
			request: JSON.stringify({
				oldSave: Eternity.SavedGame.state.info.absolutePath
				, savedYet: Eternity.Modifications.state.savedYet
				, changes: changes
			})
			, onSuccess: response => {
				self.state.working = false;
				pending = {};
				numbers = {};
				dismissed = {};
				released = {};

				// The new Prestige and Security arrive; unsaved edits made
				// elsewhere in the editor are kept.
				Eternity.SavedGame.transition({saveData: Eternity.SavedGame.adopt(JSON.parse(response)
					, appliedLabel('Stronghold', changes.length))});
				markDirty();
				redraw('Stronghold updated. Save to write it to a file.');
			}
			, onFailure: (code, message) => {
				self.state.working = false;
				redraw(message || 'The stronghold could not be updated.');
			}
		});
	};

	self.revert = () => {
		pending = {};
		numbers = {};
		dismissed = {};
		released = {};
		redraw('Reverted.');
	};

	self.reset = () => {
		pending = {};
		numbers = {};
		dismissed = {};
		released = {};
		filter = 'all';
		status = '';
	};

	self.init = function () {
		// The menu item itself is bound by SavedGame every time a save opens:
		// Editor.js calls .off() on it whenever the save list is showing, so a
		// handler attached once here would not survive going back to the list.
		self.html.shApply.click(self.apply.bind(self));
		self.html.shRevert.click(self.revert.bind(self));
	};

	self.render = newState => {
		self.state = $.extend({}, defaultState, newState);

		if (!self.state.enabled) {
			self.reset();
			return;
		}

		redraw();
	};
};

$.extend(StrongholdEditor.prototype, Renderer.prototype);
