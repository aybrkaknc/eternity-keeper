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
import uk.me.mantas.eternity.save.CompanionRegistry;
import uk.me.mantas.eternity.save.Resurrector;

import java.io.File;
import java.io.IOException;

// Resurrects a companion who died in-game. The request carries
// {oldSave, savedYet, companion} where companion is a CompanionRegistry key
// (the UI takes it from the synthetic "dead:<key>" character GUID). The
// donor save is found automatically among same-playthrough saves.
public class ResurrectCharacter extends SaveMutationHandler {
	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final String companionKey = request.getString("companion");

		switch (new Resurrector(save).resurrect(companionKey)) {
			case OK:
				return null;

			case UNKNOWN_COMPANION:
				return "Unknown companion: " + companionKey;

			case NO_DONOR:
				return String.format(
					"No other save from this playthrough contains %s alive. "
					+ "Resurrection needs a save made while they still lived."
					, CompanionRegistry.byKey(companionKey)
						.map(c -> c.displayName)
						.orElse(companionKey));

			case FAILED:
			default:
				return "Resurrection failed. Details are in eternity.log; Settings shows where it is.";
		}
	}
}
