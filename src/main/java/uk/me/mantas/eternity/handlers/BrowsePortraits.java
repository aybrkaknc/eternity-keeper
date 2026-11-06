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

import org.json.JSONObject;
import uk.me.mantas.eternity.save.PortraitCatalog;
import uk.me.mantas.eternity.save.PortraitCatalog.Portrait;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Serves the portraits on the player's own install to the picker.
 *
 * <p>Paged, and for the same reason {@link BrowseItems} is: the shipped game
 * has 118 pairs and the small images alone are about 1.9 MB, which is well
 * past what one JCEF reply carries comfortably.
 *
 * <p>Only the small image travels — 76×96, the one the party bar uses. The
 * large is 210×330 and seven times the bytes, and the picker is a grid.
 * The chosen portrait's large image is already in the character payload.
 */
public class BrowsePortraits extends CatalogQuery {
	private static final int MAX_LIMIT = 60;

	@Override
	protected JSONObject answer (final JSONObject request) {
		final String search = request.optString("search", "").toLowerCase().trim();
		final String category = request.optString("category", "");
		final PortraitCatalog catalog = PortraitCatalog.getInstance();
		final JSONObject response = new JSONObject();

		// The character view needs one full-size image after a pick, since it
		// draws the large portrait rather than the party-bar thumbnail.
		final String largePath = request.optString("largePath", "");
		if (!largePath.isEmpty()) {
			response.put("largePath", largePath);
			response.put("image", catalog.imageData(largePath));
			response.put("available", catalog.size() > 0);
			return response;
		}

		final List<Portrait> matches = catalog.all().stream()
			.filter(portrait -> category.isEmpty() || category.equals(portrait.category))
			.filter(portrait -> search.isEmpty() || portrait.name.toLowerCase().contains(search))
			.collect(Collectors.toList());

		response.put("portraits", page(matches, offset(request), limit(request, 40, MAX_LIMIT)
			, portrait -> {
				final JSONObject entry = new JSONObject();
				entry.put("key", portrait.key);
				entry.put("name", portrait.name);
				entry.put("category", portrait.category);
				entry.put("large", portrait.large);
				entry.put("small", portrait.small);
				entry.put("image", catalog.imageData(portrait.small));
				return entry;
			}));

		response.put("total", matches.size());
		response.put("categories", array(catalog.categories()));
		response.put("available", catalog.size() > 0);
		return response;
	}
}
