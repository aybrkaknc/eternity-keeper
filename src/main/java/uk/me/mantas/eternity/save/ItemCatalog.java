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

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.AppPaths;

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
 * Real item names and inventory icons, keyed by prefab file name.
 *
 * <p>A save only ever stores a prefab path like
 * {@code Assets/Data/Prefabs/Items/Rings/Ring_PREORDER_Gauns_Pledge.prefab}.
 * The display name and icon live inside the game's Unity asset bundles —
 * every item has its own bundle under
 * {@code assetbundles/prefabs/objectbundle/}, named exactly like the lowercased
 * prefab file name, holding an Item component whose {@code DisplayName} points
 * into a {@code *.stringtable} and whose {@code IconTexture} is the 42x42
 * inventory icon. Parsing Unity bundles from Java 8 isn't realistic, so that
 * data is extracted once by a helper script into a plain
 * {@code catalog.json} + {@code icons/} directory, which this class reads.
 *
 * <p>Everything is optional: with no catalog present the editor still works and
 * simply falls back to a prettified file name and no icon.
 */
public class ItemCatalog {
	private static final Logger logger = Logger.getLogger(ItemCatalog.class);
	private static ItemCatalog instance = null;

	private final Map<String, Entry> entries = new HashMap<>();
	private boolean localizable = false;
	private final IconFolder icons;

	/** One catalogued item. All fields except {@link #name} may be absent. */
	public static final class Entry {
		public final String name;
		public final String icon;
		public final int maxStack;
		public final boolean quest;
		public final List<String> slots;
		/** "soulbound", "unique", "fine", or empty — drives the tile tint. */
		public final String quality;
		/** UIInventoryFilter.ItemFilterType, for the stash category filters. */
		public final int filter;
		/** Classes allowed to equip this; empty means anyone can. */
		public final List<String> classes;
		/** Prefab path as a save records it in InventoryItem.BaseItem. */
		public final String path;
		/**
		 * What the item is worth, as {@code Item.GetValue()} computes it: the
		 * base value plus every item mod's cost, which the catalog sums during
		 * extraction. 0 when the game gives no price.
		 */
		public final int value;
		/** Stores buy these back at full price rather than the usual fifth. */
		public final boolean fullValueSell;

		Entry (
			final String name
			, final String icon
			, final int maxStack
			, final boolean quest
			, final List<String> slots
			, final String quality
			, final int filter
			, final List<String> classes
			, final String path
			, final int value
			, final boolean fullValueSell) {

			this.name = name;
			this.icon = icon;
			this.maxStack = maxStack;
			this.quest = quest;
			this.slots = slots;
			this.quality = quality;
			this.filter = filter;
			this.classes = classes;
			this.path = path;
			this.value = value;
			this.fullValueSell = fullValueSell;
		}
	}

	public static synchronized ItemCatalog getInstance () {
		if (instance == null) {
			instance = new ItemCatalog(locateCatalogDirectory());
		}

		return instance;
	}

	/** Testing seam — forces the next getInstance() to re-read from disk. */
	public static synchronized void reset () {
		instance = null;
	}

	/**
	 * Testing seam — pretends no catalog is installed. Without this, whether a
	 * test machine happens to have a game install would change the names and
	 * icons that extraction produces, and fixture comparisons would only pass
	 * on some machines.
	 */
	public static synchronized void useNoCatalog () {
		instance = new ItemCatalog(Optional.empty(), true);
	}

	/**
	 * Testing seam — reads a catalog from a directory of the caller's choosing,
	 * so pricing can be tested against a fixture rather than against whatever
	 * the machine's game install happens to contain.
	 */
	public static synchronized void useCatalogAt (final File directory) {
		instance = new ItemCatalog(Optional.of(directory), true);
	}

	private ItemCatalog (final Optional<File> directory) {
		this(directory, false);
	}

	private ItemCatalog (final Optional<File> directory, final boolean quiet) {
		icons = new IconFolder(directory.map(d -> new File(d, "icons")).orElse(null));

		if (!directory.isPresent()) {
			if (!quiet) {
				logger.info(
					"No item catalog found; falling back to prettified file names.%n");
			}

			return;
		}

		load(new File(directory.get(), "catalog.json"));
	}

	private void load (final File catalogFile) {
		try (final Reader reader = new BufferedReader(
			new InputStreamReader(new FileInputStream(catalogFile), "UTF-8"))) {

			final JSONObject json = new JSONObject(new JSONTokener(reader));
			for (final String key : json.keySet()) {
				final JSONObject item = json.optJSONObject(key);
				if (item == null) {
					continue;
				}

				localizable |= item.has("nameId");
				entries.put(key.toLowerCase(), new Entry(
					GameText.said(item, "name", "")
					, item.optString("icon", "")
					, item.optInt("maxStack", 1)
					, item.optInt("quest", 0) != 0
					, stringList(item.optJSONArray("slots"))
					, item.optString("quality", "")
					, item.optInt("filter", 0)
					, stringList(item.optJSONArray("classes"))
					, item.optString("path", "")
					, item.optInt("value", 0)
					, item.optInt("fullValueSell", 0) != 0));
			}

			logger.info("Loaded %d catalogued items.%n", entries.size());
		} catch (final Exception e) {
			logger.error(
				"Unable to read item catalog '%s': %s%n"
				, catalogFile.getAbsolutePath(), e.getMessage());
		}
	}

	private static List<String> stringList (final JSONArray array) {
		final List<String> values = new ArrayList<>();
		if (array != null) {
			for (int i = 0; i < array.length(); i++) {
				values.add(array.getString(i));
			}
		}

		return Collections.unmodifiableList(values);
	}

	private static Optional<File> locateCatalogDirectory () {
		return locateDataDirectory("catalog.json");
	}

	// The catalog is derived from the user's own game install, so it lives
	// outside the app: in the data folder the extractor writes to, or one of
	// the itemdata folders earlier builds used (AppPaths.gameDataCandidates).
	// An explicit setting beats all of them. Shared with the other catalogs,
	// which sit in the same directory.
	static Optional<File> locateDataDirectory (final String requiredFile) {
		final List<File> candidates = new ArrayList<>();

		// Settings are mocked out in tests, so treat anything here as absent
		// rather than letting it break opening a save.
		try {
			final String configured =
				Settings.getInstance().json.optString("itemDataLocation", "");

			if (configured != null && !configured.isEmpty()) {
				candidates.add(new File(configured));
			}
		} catch (final Exception ignored) {
			// No usable setting; fall through to the conventional locations.
		}

		candidates.addAll(AppPaths.forThisProcess().gameDataCandidates());

		for (final File candidate : candidates) {
			if (candidate != null && new File(candidate, requiredFile).isFile()) {
				return Optional.of(candidate);
			}
		}

		return Optional.empty();
	}

	/**
	 * Turns the prefab path stored in a save into its catalog key:
	 * {@code Assets/.../Ring_PREORDER_Gauns_Pledge.prefab} -&gt;
	 * {@code ring_preorder_gauns_pledge}.
	 */
	public static String keyOf (final String baseItem) {
		if (baseItem == null || baseItem.isEmpty()) {
			return "";
		}

		final String fileName = baseItem.substring(baseItem.lastIndexOf('/') + 1);
		final String withoutExtension = fileName.endsWith(".prefab")
			? fileName.substring(0, fileName.length() - ".prefab".length())
			: fileName;

		return withoutExtension.toLowerCase();
	}

	public Optional<Entry> lookup (final String baseItem) {
		return Optional.ofNullable(entries.get(keyOf(baseItem)));
	}

	/**
	 * Whether the names can be shown in another language at all: data read
	 * before the catalog kept where each name came from can only be English.
	 */
	public boolean localizable () {
		return localizable;
	}

	public int size () {
		return entries.size();
	}

	/**
	 * What a store would pay for {@code count} of this item, following
	 * {@code Item.GetDefaultSellValue}: a fifth of the value, rounded down, or
	 * the whole value for the items flagged {@code FullValueSell} — ingredients
	 * and the like.
	 *
	 * <p>Rounding is per item and then multiplied, which is what selling a
	 * stack one at a time would come to. A quest item is worth nothing here:
	 * the game will not let you sell one either.
	 */
	public int sellValue (final String baseItem, final int count) {
		if (count <= 0) {
			return 0;
		}

		return lookup(baseItem)
			.filter(entry -> !entry.quest)
			.map(entry -> (entry.fullValueSell
				? entry.value : (int) Math.floor(entry.value * 0.2)) * count)
			.orElse(0);
	}

	/**
	 * Catalogued items matching a name fragment and/or an
	 * {@code ItemFilterType}, sorted by display name so paging is stable.
	 * Only items the game really ships are offered ({@link ShippedContent}).
	 */
	public List<Map.Entry<String, Entry>> search (final String needle, final int filter) {
		final List<Map.Entry<String, Entry>> matches = new ArrayList<>();

		for (final Map.Entry<String, Entry> candidate : entries.entrySet()) {
			final Entry entry = candidate.getValue();

			if (filter != 0 && entry.filter != filter) {
				continue;
			}

			if (!ShippedContent.isItemPrefab(entry.path)
				|| ShippedContent.isDevelopmentPrefab(entry.path)) {

				continue;
			}

			if (needle != null && !needle.isEmpty()
				&& !entry.name.toLowerCase().contains(needle)
				&& !candidate.getKey().contains(needle)) {

				continue;
			}

			matches.add(candidate);
		}

		matches.sort((a, b) -> {
			final int byName = a.getValue().name.compareToIgnoreCase(b.getValue().name);
			return byName != 0 ? byName : a.getKey().compareTo(b.getKey());
		});

		return matches;
	}

	/**
	 * Base64 PNG for an icon file name, or an empty string when the catalog or
	 * that particular icon is missing. Icons repeat heavily across a party's
	 * inventories, so results are cached for the life of the process.
	 */
	public String iconData (final String iconFile) {
		return icons.data(iconFile);
	}
}
