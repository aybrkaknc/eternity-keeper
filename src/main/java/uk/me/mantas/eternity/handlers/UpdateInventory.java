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
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.InventoryManager.Change;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Moves, removes and creates items. The request carries {oldSave, savedYet,
// changes: [{character, component, itemGuid, stackSize, destCharacter,
// destComponent, destSlot}]} where (character, component) locates the
// container the item is in now — every party member has their own pack, so the
// owner matters — stackSize <= 0 means remove, the dest* fields default to
// staying put, and destSlot < 0 means "first free tile".
public class UpdateInventory extends SaveMutationHandler {
	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final JSONArray changesJson = request.getJSONArray("changes");
		final List<Change> changes = new ArrayList<>();

		for (int i = 0; i < changesJson.length(); i++) {
			final JSONObject change = changesJson.getJSONObject(i);
			final Change parsed = new Change(
				change.getString("character")
				, change.getString("component")
				, change.getString("itemGuid")
				, change.getInt("stackSize")
				, change.optString("destCharacter", change.getString("character"))
				, change.optString("destComponent", change.getString("component"))
				, change.optInt("destSlot", -1)
				, change.optInt("fromEquipmentSlot", -1)
				, change.optInt("toEquipmentSlot", -1));

			parsed.weaponSet = change.optBoolean("weaponSet", false);

			// Present only when the item is being created from the catalog
			// rather than moved around.
			final String prefab = change.optString("newItemPrefab", "");
			if (!prefab.isEmpty()) {
				parsed.newItemPrefab = prefab;
				parsed.newItemPath = change.optString("newItemPath", "");
			}

			changes.add(parsed);
		}

		// A rule the game would have enforced says so in its own terms; only a
		// save that could not be read or edited falls back to the log.
		final InventoryManager manager = new InventoryManager(save);
		return manager.apply(changes)
			? null
			: manager.problem().orElse(
				"Inventory update failed. Details are in eternity.log; Settings shows where it is.");
	}
}
