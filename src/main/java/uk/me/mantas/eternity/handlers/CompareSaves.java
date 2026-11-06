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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SaveComparison;

import java.io.File;
import java.io.IOException;

/**
 * What differs between two saves, for the Compare dialog.
 *
 * <p>The request is {@code {before, after}}, each the folder the save list
 * unpacked a save into ({@code absolutePath} on the list's entries), and the
 * reply is {@link SaveComparison#compare}'s. Both saves are only read, so it
 * runs on the ordinary workers like the catalog browsers, and like them it
 * always answers; two late-game saves take about two seconds.
 *
 * <p>The paths come from the page, so only folders directly inside the save
 * list's own unpacked folder are accepted -- the way {@code PortraitCatalog}
 * checks a portrait is in the portraits folder before reading it.
 */
public class CompareSaves extends CatalogQuery {
	private static final String REFUSED = "Only saves in the list can be compared.";

	@Override
	protected JSONObject answer (final JSONObject request) {
		return SaveComparison.compare(unpacked(request.getString("before")), unpacked(request.getString("after")));
	}

	private static File unpacked (final String path) {
		try {
			final File folder = new File(path).getCanonicalFile();
			final File list = Environment.getInstance().directory().working().getCanonicalFile();
			if (!folder.isDirectory() || !list.equals(folder.getParentFile())) {
				throw new IllegalArgumentException(REFUSED);
			}

			// The list unpacked only what it draws of each save.
			try {
				Environment.getInstance().state().unpacked().complete(folder);
			} catch (final IOException e) {
				throw new IllegalArgumentException(e.getMessage());
			}

			return folder;
		} catch (final IOException e) {
			throw new IllegalArgumentException(REFUSED);
		}
	}
}
