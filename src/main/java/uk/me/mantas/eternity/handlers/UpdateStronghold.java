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
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.save.StrongholdManager.Change;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Edits Caed Nua. The request carries {oldSave, savedYet, changes: [{kind,
// name, number, flag}]} where kind is one of activate / addUpgrade /
// removeUpgrade / setNumber / setFlag / dismissHireling / releasePrisoner. A
// hireling is named by their HiredGlobalVariableName and a prisoner by their
// GlobalVariableName, the keys the opener hands out.
//
// Order within the list is preserved and meaningful: building an upgrade adds
// its own Prestige and Security on top of whatever the numbers are at that
// moment, exactly as Stronghold.CompleteBuildingUpgrade would have.
public class UpdateStronghold extends SaveMutationHandler {

	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final JSONArray changesJson = request.getJSONArray("changes");
		final List<Change> changes = new ArrayList<>();

		for (int i = 0; i < changesJson.length(); i++) {
			final JSONObject change = changesJson.getJSONObject(i);
			final String kind = change.getString("kind");
			final String name = change.optString("name", "");

			switch (kind) {
				case "activate":
					changes.add(Change.activate(change.optBoolean("flag", true)));
					break;

				case "addUpgrade":
					changes.add(Change.addUpgrade(name));
					break;

				case "removeUpgrade":
					changes.add(Change.removeUpgrade(name));
					break;

				case "setNumber":
					changes.add(Change.setNumber(name, change.optInt("number", 0)));
					break;

				case "setFlag":
					changes.add(Change.setFlag(name, change.optBoolean("flag", false)));
					break;

				case "dismissHireling":
					changes.add(Change.dismissHireling(name));
					break;

				case "releasePrisoner":
					changes.add(Change.releasePrisoner(name));
					break;

				default:
					// Skipped, the rest would go in and the page be told it all had.
					return "There is no stronghold change called '" + kind + "'. Nothing was changed.";
			}
		}

		return new StrongholdManager(save).apply(changes)
			? null : "Stronghold update failed. Details are in eternity.log; Settings shows where it is.";
	}
}
