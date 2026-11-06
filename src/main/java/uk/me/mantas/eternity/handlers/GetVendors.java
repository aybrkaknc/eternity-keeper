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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.save.VendorStock;

import java.io.File;
import java.util.Optional;

/**
 * Every vendor in the open save, with its stock, for the Vendors tab.
 *
 * <p>The request is {@code {oldSave, savedYet}} and the list is read from the
 * working copy, so it shows what an Apply left rather than what the save list
 * unpacked. A mid-game save holds about 3,000 items across 70-odd stores, some
 * 600 KB here; the icons are left out and asked for by key
 * ({@code browseItems} with {@code iconKeys}) for the store on screen, since
 * all of them at once would be megabytes through one reply.
 *
 * <p>Read-only, so it runs on the ordinary workers like the catalog
 * browsers, and like them it always answers.
 */
public class GetVendors extends CatalogQuery {
	@Override
	protected JSONObject answer (final JSONObject request) {
		final File save = Environment.getInstance().state().workingSave().forReading(
			new File(request.getString("oldSave")), request.optBoolean("savedYet", false));

		if (!save.isDirectory()) {
			throw new IllegalStateException("Unable to find your save file.");
		}

		final VendorStock.Result result = VendorStock.read(save);
		final ItemCatalog catalog = ItemCatalog.getInstance();

		final JSONArray vendors = new JSONArray();
		for (final VendorStock.Vendor vendor : result.vendors) {
			final JSONArray items = new JSONArray();
			for (final VendorStock.Item item : vendor.items) {
				items.put(item(catalog, vendor, item));
			}

			vendors.put(new JSONObject()
				.put("file", vendor.file)
				.put("id", vendor.id)
				.put("objectName", vendor.objectName)
				.put("name", vendor.name)
				.put("area", vendor.area)
				.put("opened", vendor.opened)
				.put("sellMultiplier", vendor.sellMultiplier)
				.put("buyMultiplier", vendor.buyMultiplier)
				.put("items", items));
		}

		return new JSONObject()
			.put("vendors", vendors)
			.put("unreadable", array(result.unreadable))
			.put("catalog", catalog.size() > 0);
	}

	private static JSONObject item (
		final ItemCatalog catalog, final VendorStock.Vendor vendor, final VendorStock.Item item) {

		final String prefab = item.prefab == null ? "" : item.prefab;
		final JSONObject json = new JSONObject()
			.put("index", item.index)
			.put("guid", item.guid)
			.put("key", ItemCatalog.keyOf(prefab))
			.put("stackSize", item.stack)
			.put("original", item.original)
			.put("hasPacket", item.hasPacket);

		final Optional<ItemCatalog.Entry> entry = catalog.lookup(prefab);
		if (!entry.isPresent()) {
			return json.put("displayName", SavedGameOpener.prettifyItemName(prefab));
		}

		json.put("displayName", entry.get().name.isEmpty()
			? SavedGameOpener.prettifyItemName(prefab) : entry.get().name);
		json.put("quality", entry.get().quality);
		json.put("filter", entry.get().filter);
		json.put("quest", entry.get().quest);

		// What the store asks for one: Item.GetBuyValue rounds the float
		// product up.
		if (entry.get().value > 0) {
			json.put("value", entry.get().value);
			json.put("price", (int) Math.ceil((float) entry.get().value * vendor.sellMultiplier));
		}

		return json;
	}
}
