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


package uk.me.mantas.eternity.save;

import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.game.StrongholdUpgrade;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Building and demolishing stronghold upgrades, and the bookkeeping that has to
 * go with them.
 *
 * <p>The list itself is the easy half: {@code m_upgradesBuilt} is a plain
 * {@code List<StrongholdUpgrade.Type>} with no cross-references, no UUIDs and
 * no parallel structure to keep in step — unlike almost everything else in a
 * save.
 *
 * <p>The half that needs care is that Prestige and Security are independent
 * persisted scalars. {@code Stronghold.CompleteBuildingUpgrade()} adds the
 * upgrade's own adjustments once, when it is built, and
 * {@code DestroyUpgrade()} subtracts them again; neither number is ever
 * recomputed from the list. Appending to the list alone would therefore produce
 * a stronghold with upgrades it gets no credit for — a state the game could
 * never have reached. The same goes for
 * {@code UpgradeCompletedGlobalVariableName}, which area content keys off.
 *
 * <p>Hirelings and prisoners go the same way, in one direction only.
 * Dismissing a hireling takes back the Prestige and Security they were paid
 * for, and releasing a prisoner clears the global a conversation set when they
 * were locked up. Taking someone <em>on</em> is not offered: a hireling or a
 * prisoner arrives through a conversation or a visitor that also changes the
 * world, and minting the list entry alone would be a keep the game could not
 * have produced.
 *
 * <p>Everything here is refused outright unless the player actually owns Caed
 * Nua ({@code SerializedIsActivated}), because until then the stronghold does
 * not exist as far as the game is concerned.
 */
public class StrongholdManager {
	private static final Logger logger = Logger.getLogger(StrongholdManager.class);

	private static final String TYPE_UPGRADE =
		"StrongholdUpgrade+Type, Assembly-CSharp";

	private final File saveDirectory;

	public StrongholdManager (final File saveDirectory) {
		this.saveDirectory = saveDirectory;
	}

	/** One edit. Order is preserved: the arithmetic runs as the game's would. */
	public static final class Change {
		public enum Kind {
			ACTIVATE, ADD_UPGRADE, REMOVE_UPGRADE, SET_NUMBER, SET_FLAG
			, DISMISS_HIRELING, RELEASE_PRISONER
		}

		public final Kind kind;
		public final String name;
		public final int number;
		public final boolean flag;

		private Change (
			final Kind kind, final String name, final int number, final boolean flag) {

			this.kind = kind;
			this.name = name;
			this.number = number;
			this.flag = flag;
		}

		public static Change activate (final boolean activated) {
			return new Change(Kind.ACTIVATE, "SerializedIsActivated", 0, activated);
		}

		public static Change addUpgrade (final String key) {
			return new Change(Kind.ADD_UPGRADE, key, 0, false);
		}

		public static Change removeUpgrade (final String key) {
			return new Change(Kind.REMOVE_UPGRADE, key, 0, false);
		}

		public static Change setNumber (final String variable, final int value) {
			return new Change(Kind.SET_NUMBER, variable, value, false);
		}

		public static Change setFlag (final String variable, final boolean value) {
			return new Change(Kind.SET_FLAG, variable, 0, value);
		}

		public static Change dismissHireling (final String hiredGlobal) {
			return new Change(Kind.DISMISS_HIRELING, hiredGlobal, 0, false);
		}

		public static Change releasePrisoner (final String global) {
			return new Change(Kind.RELEASE_PRISONER, global, 0, false);
		}
	}

	/** Whether the player has taken Caed Nua in this save. */
	public boolean isActivated () throws IOException {
		final Optional<DeserializedPackets> deserialized = read();
		if (!deserialized.isPresent()) {
			return false;
		}

		return stronghold(SaveModel.of(deserialized.get()))
			.flatMap(stronghold -> stronghold.get("SerializedIsActivated"))
			.map(Boolean.TRUE::equals)
			.orElse(false);
	}

	/**
	 * A batch goes in whole or not at all: one change the save cannot take
	 * refuses the lot, and nothing is written.
	 *
	 * @return true when the save was changed and rewritten, false when every
	 *         change was already so
	 * @throws WriteRefusedException naming the change that was refused
	 */
	public boolean apply (final List<Change> changes) throws IOException {
		if (changes == null || changes.isEmpty()) {
			return false;
		}

		final Optional<DeserializedPackets> deserialized = read();
		if (!deserialized.isPresent()) {
			logger.error("Unable to deserialize MobileObjects.save.%n");
			return false;
		}

		final SaveModel save = SaveModel.of(deserialized.get());
		final Optional<Component> stronghold = stronghold(save);
		if (!stronghold.isPresent()) {
			logger.error("Save has no Stronghold component.%n");
			return false;
		}

		// Activation has to be settled before anything else: an upgrade on a
		// stronghold nobody owns is meaningless, and the ACTIVATE change in
		// this very batch may be what grants it.
		boolean activated = stronghold.get().get("SerializedIsActivated")
			.map(Boolean.TRUE::equals)
			.orElse(false);

		boolean changed = false;

		for (final Change change : changes) {
			if (change.kind == Change.Kind.ACTIVATE) {
				if (activated != change.flag
					&& setEntry(stronghold.get(), change.name, change.flag)) {

					activated = change.flag;
					changed = true;
				}

				continue;
			}

			if (!activated) {
				throw refused("Caed Nua is not the player's yet in this save, so its upgrades"
					+ ", hirelings and prisoners cannot be changed.");
			}

			switch (change.kind) {
				case ADD_UPGRADE:
					changed |= buildUpgrade(save, stronghold.get(), change.name);
					break;

				case REMOVE_UPGRADE:
					changed |= destroyUpgrade(save, stronghold.get(), change.name);
					break;

				case SET_NUMBER:
					changed |= setEntry(stronghold.get(), change.name, change.number);
					break;

				case SET_FLAG:
					changed |= setEntry(stronghold.get(), change.name, change.flag);
					break;

				case DISMISS_HIRELING:
					changed |= dismissHireling(save, stronghold.get(), change.name);
					break;

				case RELEASE_PRISONER:
					changed |= releasePrisoner(save, stronghold.get(), change.name);
					break;

				default:
					break;
			}
		}

		if (!changed) {
			return false;
		}

		deserialized.get().replace(new File(saveDirectory, "MobileObjects.save"));
		return true;
	}

	// Mirrors Stronghold.CompleteBuildingUpgrade: record it, pay the
	// adjustments, raise the global.
	private boolean buildUpgrade (
		final SaveModel save
		, final Component stronghold
		, final String key) throws WriteRefusedException {

		final Optional<StrongholdCatalog.Upgrade> upgrade =
			StrongholdCatalog.getInstance().lookup(key);

		if (!upgrade.isPresent()) {
			throw refused("There is no upgrade called '" + key + "' that the game can build.");
		}

		final Optional<StrongholdUpgrade.Type> type = upgrade.get().type();
		final Optional<CollectionProperty> list = upgradeList(stronghold);
		if (!type.isPresent() || !list.isPresent()) {
			throw refused("The stronghold in this save has no list of upgrades to record '"
				+ key + "' in.");
		}

		// HasUpgrade() guards the game's own call; without the same guard the
		// adjustments would be paid twice for one upgrade.
		if (contains(list.get(), type.get())) {
			return false;
		}

		list.get().items.add(upgradeEntry(type.get()));

		adjust(stronghold, "Prestige", upgrade.get().prestige);
		adjust(stronghold, "Security", upgrade.get().security);
		setUpgradeGlobal(save, upgrade.get(), 1);
		return true;
	}

	// Mirrors Stronghold.DestroyUpgrade, which is the same steps in reverse.
	private boolean destroyUpgrade (
		final SaveModel save
		, final Component stronghold
		, final String key) throws WriteRefusedException {

		final Optional<StrongholdCatalog.Upgrade> upgrade =
			StrongholdCatalog.getInstance().lookup(key);

		if (!upgrade.isPresent()) {
			throw refused("There is no upgrade called '" + key + "' that the game can build.");
		}

		final Optional<StrongholdUpgrade.Type> type = upgrade.get().type();
		final Optional<CollectionProperty> list = upgradeList(stronghold);
		if (!type.isPresent() || !list.isPresent()) {
			throw refused("The stronghold in this save has no list of upgrades to record '"
				+ key + "' in.");
		}

		boolean removed = false;
		for (int i = list.get().items.size() - 1; i >= 0; i--) {
			if (type.get().equals(list.get().items.get(i).obj)) {
				list.get().items.remove(i);
				removed = true;
			}
		}

		if (!removed) {
			return false;
		}

		adjust(stronghold, "Prestige", -upgrade.get().prestige);
		adjust(stronghold, "Security", -upgrade.get().security);
		setUpgradeGlobal(save, upgrade.get(), 0);
		return true;
	}

	// Mirrors Stronghold.DismissHireling: out of m_hirelingsHired, the hired
	// global back to 0, and -- only for one who is Paid -- their Prestige and
	// Security taken off again. An unpaid hireling's adjustments already came
	// off when the pay cycle could not pay them.
	//
	// A hireling is named by HiredGlobalVariableName, which is one of the two
	// things Restored() matches an entry back to its configured hireling by.
	// The numbers are the entry's own: the game serialized them from that same
	// configured hireling.
	private boolean dismissHireling (
		final SaveModel save
		, final Component stronghold
		, final String hiredGlobal) throws WriteRefusedException {

		final Optional<Property> hireling =
			removeEntry(stronghold, "m_hirelingsHired", "HiredGlobalVariableName", hiredGlobal);

		if (!hireling.isPresent()) {
			throw refused("No hireling '" + hiredGlobal + "' is in this save's stronghold to dismiss.");
		}

		if (Boolean.TRUE.equals(field(hireling.get(), "Paid"))) {
			adjust(stronghold, "Prestige", -number(hireling.get(), "PrestigeAdjustment"));
			adjust(stronghold, "Security", -number(hireling.get(), "SecurityAdjustment"));
		}

		setGlobal(save, hiredGlobal, 0);
		return true;
	}

	// Mirrors Stronghold.RemovePrisoner, which is what the game's own Release
	// button calls: the prisoner's global back to 0 and the entry gone. A
	// PrisonerRequest visitor asking after them is left alone, as the game
	// leaves it; ConfirmPrisoner() simply finds nobody to hand over.
	private boolean releasePrisoner (
		final SaveModel save
		, final Component stronghold
		, final String global) throws WriteRefusedException {

		final Optional<Property> prisoner =
			removeEntry(stronghold, "m_prisoners", "GlobalVariableName", global);

		if (!prisoner.isPresent()) {
			throw refused("No prisoner '" + global + "' is in this save's stronghold to release.");
		}

		setGlobal(save, global, 0);
		return true;
	}

	// Takes the first entry of a list of objects whose field has that value
	// out of the list, as List.Remove does in the game.
	private static Optional<Property> removeEntry (
		final Component stronghold
		, final String list
		, final String key
		, final String value) {

		if (value == null || value.isEmpty()) {
			return Optional.empty();
		}

		final Optional<Property> entry = stronghold.variable(list);
		if (!entry.isPresent() || !(entry.get() instanceof CollectionProperty)) {
			logger.error("Stronghold has no %s list.%n", list);
			return Optional.empty();
		}

		final List<Property> items = ((CollectionProperty) entry.get()).items;
		for (int i = 0; i < items.size(); i++) {
			if (value.equals(field(items.get(i), key))) {
				return Optional.of(items.remove(i));
			}
		}

		return Optional.empty();
	}

	private static Object field (final Property item, final String name) {
		if (!(item instanceof ComplexProperty)) {
			return null;
		}

		return ((ComplexProperty) item).<Property>findProperty(name)
			.map(p -> p.obj)
			.orElse(null);
	}

	private static int number (final Property item, final String name) {
		final Object value = field(item, name);
		return value instanceof Integer ? (Integer) value : 0;
	}

	// A list entry the serializer will write back as the enum's ordinal. Both
	// value and obj have to carry it: the write path reads one and the property
	// tree the other.
	private static SimpleProperty upgradeEntry (final StrongholdUpgrade.Type type) {
		final SimpleProperty entry = new SimpleProperty(
			null, new TypePair(StrongholdUpgrade.Type.class, TYPE_UPGRADE));

		entry.value = type;
		entry.obj = type;
		return entry;
	}

	private static boolean contains (
		final CollectionProperty list, final StrongholdUpgrade.Type type) {

		for (final Property item : list.items) {
			if (type.equals(item.obj)) {
				return true;
			}
		}

		return false;
	}

	private static void adjust (
		final Component stronghold, final String variable, final int delta)
		throws WriteRefusedException {

		if (delta == 0) {
			return;
		}

		final Optional<Object> current = stronghold.get(variable);
		if (!current.isPresent() || !(current.get() instanceof Integer)) {
			logger.error("Stronghold has no '%s' to adjust.%n", variable);
			return;
		}

		stronghold.set(variable, (Integer) current.get() + delta);
	}

	private static WriteRefusedException refused (final String why) {
		return new WriteRefusedException(why + " Nothing was changed.");
	}

	private static boolean setEntry (
		final Component stronghold, final String variable, final Object value)
		throws WriteRefusedException {

		final Optional<Property> entry = stronghold.variable(variable);
		if (!entry.isPresent()) {
			throw refused("The stronghold in this save has no '" + variable + "' to set.");
		}

		if (value.equals(entry.get().obj)) {
			return false;
		}

		stronghold.set(variable, value);
		return true;
	}

	/**
	 * "This global is set to 1 when the upgrade is built and back to 0 when it
	 * is destroyed." Only the Eastern Barbican actually names one, but the
	 * world keys off it, so leaving it stale would show an upgrade the area
	 * does not reflect.
	 */
	private static void setUpgradeGlobal (
		final SaveModel save
		, final StrongholdCatalog.Upgrade upgrade
		, final int value) throws WriteRefusedException {

		setGlobal(save, upgrade.global, value);
	}

	// A global that is not in the save at all reads as 0 in the game, so one
	// missing is left missing rather than added.
	private static void setGlobal (
		final SaveModel save
		, final String name
		, final int value) throws WriteRefusedException {

		if (name == null || name.isEmpty()) {
			return;
		}

		if (!save.globalVariable(name).isPresent()) {
			logger.error("Global '%s' is not in this save; leaving it alone.%n", name);
			return;
		}

		save.setGlobalVariable(name, value);
	}

	private Optional<DeserializedPackets> read () throws IOException {
		return Environment.getInstance().factory().packetDeserializer()
			.forFile(new File(saveDirectory, "MobileObjects.save")).deserialize();
	}

	private static Optional<CollectionProperty> upgradeList (final Component stronghold) {
		final Optional<Property> entry = stronghold.variable("m_upgradesBuilt");
		if (!entry.isPresent() || !(entry.get() instanceof CollectionProperty)) {
			logger.error("Stronghold has no m_upgradesBuilt list.%n");
			return Optional.empty();
		}

		return Optional.of((CollectionProperty) entry.get());
	}

	private static Optional<Component> stronghold (final SaveModel save) {
		return save.inGameGlobal().flatMap(global -> global.component("Stronghold"));
	}
}
