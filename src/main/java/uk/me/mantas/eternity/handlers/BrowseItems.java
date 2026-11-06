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

package uk.me.mantas.eternity.handlers;

import org.json.JSONArray;
import org.json.JSONObject;
import uk.me.mantas.eternity.save.ItemCatalog;

import java.util.List;
import java.util.Map;

/**
 * Serves the item catalog to the "add any item" browser.
 *
 * <p>Sending all ~2200 items with their icons in one go would be several
 * megabytes of base64 through a JCEF query, so this pages: the UI asks for a
 * slice matching the current search and category and gets back only those,
 * icons included.
 */
public class BrowseItems extends CatalogQuery {
	private static final int MAX_LIMIT = 200;

	@Override
	protected JSONObject answer (final JSONObject request) {
		final ItemCatalog catalog = ItemCatalog.getInstance();

		// Icon-only mode, for lists that arrive without their art: the
		// Vendors tab holds thousands of items and asks for the icons of the
		// store on screen.
		final JSONArray iconKeys = request.optJSONArray("iconKeys");
		if (iconKeys != null) {
			return icons(catalog, iconKeys);
		}

		final String search = request.optString("search", "").toLowerCase().trim();
		final int filter = request.optInt("filter", 0);
		final int offset = offset(request);

		final List<Map.Entry<String, ItemCatalog.Entry>> matches =
			catalog.search(search, filter);

		final JSONObject response = new JSONObject();
		response.put("total", matches.size());
		response.put("offset", offset);
		response.put("items", page(matches, offset, limit(request, 60, MAX_LIMIT), match -> {
			final ItemCatalog.Entry entry = match.getValue();
			final JSONObject item = new JSONObject();
			item.put("key", match.getKey());
			item.put("displayName", entry.name);
			item.put("baseItem", entry.path);
			item.put("maxStack", entry.maxStack);
			item.put("quality", entry.quality);
			item.put("filter", entry.filter);
			item.put("quest", entry.quest);
			item.put("icon", catalog.iconData(entry.icon));
			item.put("slots", array(entry.slots));
			item.put("classes", array(entry.classes));
			return item;
		}));

		response.put("available", catalog.size() > 0);
		return response;
	}

	private static JSONObject icons (final ItemCatalog catalog, final JSONArray keys) {
		final JSONObject icons = new JSONObject();
		for (int i = 0; i < keys.length(); i++) {
			final String key = keys.optString(i, "").toLowerCase();
			catalog.lookup(key)
				.filter(entry -> !entry.icon.isEmpty())
				.map(entry -> catalog.iconData(entry.icon))
				.filter(data -> !data.isEmpty())
				.ifPresent(data -> icons.put(key, data));
		}

		return new JSONObject()
			.put("icons", icons)
			.put("available", catalog.size() > 0);
	}
}
