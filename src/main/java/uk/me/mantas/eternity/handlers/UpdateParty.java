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
import uk.me.mantas.eternity.save.PartyManager;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

// Moves characters between the party and the stronghold roster. The request
// carries {oldSave, savedYet, changes: {"<ObjectID>": true|false, ...}} where
// true means "put in party" and false means "store at the stronghold".
public class UpdateParty extends SaveMutationHandler {
	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final JSONObject changes = request.getJSONObject("changes");
		final Map<String, Boolean> desired = new LinkedHashMap<>();

		for (final String guid : changes.keySet()) {
			desired.put(guid, changes.getBoolean(guid));
		}

		return new PartyManager(save).apply(desired) ? null : "Party update failed.";
	}
}
