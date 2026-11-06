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
import uk.me.mantas.eternity.save.VendorManager;
import uk.me.mantas.eternity.save.VendorManager.Entry;
import uk.me.mantas.eternity.save.VendorManager.Removal;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Takes items out of vendors' stock. The request carries {oldSave, savedYet,
// removals: [{file, vendor, items: [{index, guid}, ...]}]}: which area file,
// which store in it, and which of its entries -- by place, with the GUID that
// place should hold as the check that the list has not changed since the page
// read it. The reply is the reopened save, as for every Apply; the Vendors
// tab asks for its list again afterwards.
public class UpdateVendors extends SaveMutationHandler {
	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final JSONArray removalsJson = request.getJSONArray("removals");
		final List<Removal> removals = new ArrayList<>();

		for (int i = 0; i < removalsJson.length(); i++) {
			final JSONObject removal = removalsJson.getJSONObject(i);
			final JSONArray itemsJson = removal.getJSONArray("items");
			final List<Entry> items = new ArrayList<>();

			for (int j = 0; j < itemsJson.length(); j++) {
				final JSONObject item = itemsJson.getJSONObject(j);
				items.add(new Entry(item.getInt("index"), item.getString("guid")));
			}

			removals.add(new Removal(removal.getString("file"), removal.getString("vendor"), items));
		}

		final VendorManager manager = new VendorManager(save);
		return manager.apply(removals)
			? null
			: manager.problem().orElse(
				"Vendor update failed. Details are in eternity.log; Settings shows where it is.");
	}
}
