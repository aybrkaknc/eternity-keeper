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

import org.json.JSONObject;
import org.json.JSONTokener;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.game.StrongholdUpgrade;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the game says each stronghold upgrade costs and is worth.
 *
 * <p>A save records only which {@code StrongholdUpgrade.Type} values are in
 * {@code m_upgradesBuilt}. Everything else — the display name, the price, how
 * long it takes, what it does to Prestige and Security, which upgrade has to
 * come first — lives on the {@code Stronghold} behaviour attached to the
 * {@code InGameGlobal} prefab, inside a Unity asset bundle.
 *
 * <p>That data is not decoration. {@code Stronghold.CompleteBuildingUpgrade()}
 * applies {@code PrestigeAdjustment} and {@code SecurityAdjustment} exactly
 * once, at the moment of building, and {@code DestroyUpgrade()} takes them back
 * off again; nothing recomputes either number from the list on load. So the
 * editor cannot add an upgrade correctly without knowing what the game would
 * have added alongside it.
 *
 * <p>Parsing Unity bundles from Java 8 isn't realistic, so the table is
 * extracted by {@code tools/gamedata/stronghold.py} into a plain
 * {@code stronghold.json} next to the item catalog, which this class reads.
 * With no catalog present the stronghold editor simply reports that it has
 * nothing to offer, the same way the item catalog degrades.
 */
public class StrongholdCatalog {
	private static final Logger logger = Logger.getLogger(StrongholdCatalog.class);
	private static StrongholdCatalog instance = null;

	/** {@code Stronghold.MaxHirelings} when the catalog cannot say. */
	private static final int DEFAULT_MAX_HIRELINGS = 8;

	private final Map<String, Upgrade> upgrades = new HashMap<>();
	private final Map<Integer, Upgrade> byOrdinal = new HashMap<>();
	private final List<Upgrade> ordered = new ArrayList<>();
	private final IconFolder icons;
	private int maxHirelings = DEFAULT_MAX_HIRELINGS;

	/** One buildable upgrade, exactly as the game has it configured. */
	public static final class Upgrade {
		/** The {@code StrongholdUpgrade.Type} constant's name. */
		public final String key;
		/**
		 * The enum ordinal, which is what a save actually stores. It is the
		 * identity rather than the name: our mirror enum spells one constant
		 * differently from the game (ArtificersHall against the game's own
		 * AritficersHall typo) and the two still have to line up.
		 */
		public final int ordinal;
		public final String name;
		public final String description;
		public final int cost;
		public final int days;
		public final int prestige;
		public final int security;
		/** Where the game's own upgrade list puts it. */
		public final int order;
		/** The upgrade that has to be built first, or empty. */
		public final String prerequisite;
		/** Set to 1 on building and 0 on destroying, or empty for most. */
		public final String global;
		public final String icon;
		/** Whether a stronghold attack can knock it down again. */
		public final boolean destructible;
		/** Whether it unlocks a resting bonus in Brighthollow. */
		public final boolean hasBoon;

		Upgrade (
			final String key
			, final int ordinal
			, final String name
			, final String description
			, final int cost
			, final int days
			, final int prestige
			, final int security
			, final int order
			, final String prerequisite
			, final String global
			, final String icon
			, final boolean destructible
			, final boolean hasBoon) {

			this.key = key;
			this.ordinal = ordinal;
			this.name = name;
			this.description = description;
			this.cost = cost;
			this.days = days;
			this.prestige = prestige;
			this.security = security;
			this.order = order;
			this.prerequisite = prerequisite;
			this.global = global;
			this.icon = icon;
			this.destructible = destructible;
			this.hasBoon = hasBoon;
		}

		public boolean hasPrerequisite () {
			return prerequisite != null && !prerequisite.isEmpty();
		}

		/** The enum constant a save stores, resolved from the ordinal. */
		public Optional<StrongholdUpgrade.Type> type () {
			final StrongholdUpgrade.Type[] values = StrongholdUpgrade.Type.values();
			return ordinal >= 0 && ordinal < values.length
				? Optional.of(values[ordinal])
				: Optional.empty();
		}
	}

	public static synchronized StrongholdCatalog getInstance () {
		if (instance == null) {
			instance = new StrongholdCatalog(
				ItemCatalog.locateDataDirectory("stronghold.json"), false);
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to re-read from disk. */
	public static synchronized void reset () {
		instance = null;
	}

	/**
	 * Testing seam — pretends no catalog is installed, so a test machine that
	 * happens to have a game install produces the same result as one that
	 * doesn't.
	 */
	public static synchronized void useNoCatalog () {
		instance = new StrongholdCatalog(Optional.empty(), true);
	}

	/** Testing seam — reads a catalog from a directory of the caller's choosing. */
	public static synchronized void useCatalogAt (final File directory) {
		instance = new StrongholdCatalog(Optional.of(directory), true);
	}

	private StrongholdCatalog (final Optional<File> directory, final boolean quiet) {
		icons = new IconFolder(
			directory.map(d -> new File(d, "stronghold-icons")).orElse(null));

		if (!directory.isPresent()) {
			if (!quiet) {
				logger.info("No stronghold catalog found; upgrades unavailable.%n");
			}

			return;
		}

		load(new File(directory.get(), "stronghold.json"));
	}

	private void load (final File catalogFile) {
		try (final Reader reader = new BufferedReader(
			new InputStreamReader(new FileInputStream(catalogFile), "UTF-8"))) {

			final JSONObject json = new JSONObject(new JSONTokener(reader));
			maxHirelings = json.optInt("maxHirelings", DEFAULT_MAX_HIRELINGS);

			final JSONObject entries = json.optJSONObject("upgrades");
			if (entries == null) {
				return;
			}

			for (final String key : entries.keySet()) {
				final JSONObject entry = entries.optJSONObject(key);
				if (entry == null) {
					continue;
				}

				final Upgrade upgrade = new Upgrade(
					key
					, entry.optInt("ordinal", -1)
					, GameText.said(entry, "name", key)
					, GameText.said(entry, "description", "")
					, entry.optInt("cost", 0)
					, entry.optInt("days", 0)
					, entry.optInt("prestige", 0)
					, entry.optInt("security", 0)
					, entry.optInt("order", 0)
					, entry.optString("prerequisite", "")
					, entry.optString("global", "")
					, entry.optString("icon", "")
					, entry.optInt("destructible", 0) != 0
					, entry.optInt("hasBoon", 0) != 0);

				upgrades.put(key, upgrade);
				if (upgrade.ordinal >= 0) {
					byOrdinal.put(upgrade.ordinal, upgrade);
				}

				ordered.add(upgrade);
			}

			ordered.sort((a, b) -> {
				final int byOrder = Integer.compare(a.order, b.order);
				return byOrder != 0 ? byOrder : a.key.compareTo(b.key);
			});

			logger.info("Loaded %d stronghold upgrades.%n", upgrades.size());
		} catch (final Exception e) {
			logger.error(
				"Unable to read stronghold catalog '%s': %s%n"
				, catalogFile.getAbsolutePath(), e.getMessage());
		}
	}

	public Optional<Upgrade> lookup (final String key) {
		return Optional.ofNullable(key == null ? null : upgrades.get(key));
	}

	/**
	 * The upgrade a save's enum constant refers to. Matching is by ordinal
	 * rather than by name, because that is what the save records and because
	 * the game and our mirror enum disagree about one spelling.
	 */
	public Optional<Upgrade> lookup (final StrongholdUpgrade.Type type) {
		return Optional.ofNullable(
			type == null ? null : byOrdinal.get(type.ordinal()));
	}

	/** Every buildable upgrade, in the order the game's own list shows them. */
	public List<Upgrade> all () {
		return Collections.unmodifiableList(ordered);
	}

	public int maxHirelings () {
		return maxHirelings;
	}

	/**
	 * Base64 PNG for an upgrade icon, or an empty string when the catalog or
	 * that particular icon is missing.
	 */
	public String iconData (final String iconFile) {
		return icons.data(iconFile);
	}
}
