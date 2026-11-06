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
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.save.AbilityCatalog;
import uk.me.mantas.eternity.save.GrimoireManager;
import uk.me.mantas.eternity.save.GrimoireManager.Change;
import uk.me.mantas.eternity.save.GrimoireManager.Spell;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Rewrites what grimoires hold. The request carries {oldSave, savedYet,
// grimoires: [{guid, spells: [prefab, ...]}]} -- the whole intended contents of
// each grimoire, not a diff. A flat list of prefab names has no cross-
// references to keep in sync, so replacing it wholesale is both simpler and
// safer than working out what moved.
//
// A spell's level is resolved here rather than trusted from the client: it
// decides which chapter the spell lands in, and the game silently drops a fifth
// spell at any one level, so getting it from the catalog keeps the editor and
// the save agreeing about what will survive the next load.
public class UpdateGrimoires extends SaveMutationHandler {
	private static final Logger logger = Logger.getLogger(UpdateGrimoires.class);

	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final JSONArray grimoiresJson = request.getJSONArray("grimoires");
		final AbilityCatalog catalog = AbilityCatalog.getInstance();
		final List<Change> changes = new ArrayList<>();

		for (int i = 0; i < grimoiresJson.length(); i++) {
			final JSONObject grimoire = grimoiresJson.getJSONObject(i);
			final JSONArray spellsJson = grimoire.optJSONArray("spells");
			final List<Spell> spells = new ArrayList<>();

			for (int j = 0; spellsJson != null && j < spellsJson.length(); j++) {
				final String prefab = spellsJson.optString(j, "");
				if (prefab.isEmpty()) {
					continue;
				}

				final Optional<AbilityCatalog.Entry> entry = catalog.lookup(prefab);
				if (!entry.isPresent()) {
					logger.error("No catalogued spell named '%s'.%n", prefab);
					continue;
				}

				spells.add(new Spell(prefab, entry.get().spellLevel));
			}

			changes.add(new Change(grimoire.getString("guid"), spells));
		}

		return new GrimoireManager(save).apply(changes)
			? null : "Grimoire update failed. Details are in eternity.log; Settings shows where it is.";
	}
}
