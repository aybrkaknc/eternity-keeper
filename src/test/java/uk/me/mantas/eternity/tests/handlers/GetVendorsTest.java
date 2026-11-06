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


package uk.me.mantas.eternity.tests.handlers;

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.handlers.GetVendors;
import uk.me.mantas.eternity.handlers.UpdateVendors;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.save.VendorStock;
import uk.me.mantas.eternity.tests.TestHarness;
import uk.me.mantas.eternity.tests.save.VendorStockTest;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// The Vendors tab's list: every store in the open save with its stock, named
// and priced from the item catalog. The fixture is VendorStockTest's.
public class GetVendorsTest extends TestHarness {
	private static final String ARTIFICER = "36696af5-8025-4532-a5d3-0068fe04e380";
	private static final String FISHERY = "3532ceaa-c805-4e7f-8f05-d299654b7189";
	private static final String HEODAN = "b1a7e810-0000-0000-0000-000000000000";

	private static final String CATALOG = "{"
		+ "\"trap_item_arrow\":{\"name\":\"Arrow Trap\",\"value\":100,\"filter\":16"
		+ ",\"icon\":\"trap_arrow.png\""
		+ ",\"path\":\"Assets/Data/Prefabs/Items/Traps/Trap_Item_Arrow.prefab\"}"
		+ ",\"ring_of_overseeing\":{\"name\":\"Ring of Overseeing\",\"value\":1401"
		+ ",\"quality\":\"unique\",\"filter\":8"
		+ ",\"path\":\"Assets/Data/Prefabs/Items/Rings/Ring_of_Overseeing.prefab\"}"
		+ "}";

	@After
	public void restoreCatalog () {
		ItemCatalog.useNoCatalog();
	}

	private static void useCatalog () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		FileUtils.write(new File(directory.get(), "catalog.json"), CATALOG, "UTF-8");
		FileUtils.writeByteArrayToFile(
			new File(directory.get(), "icons/trap_arrow.png"), new byte[] {1, 2, 3});
		ItemCatalog.useCatalogAt(directory.get());
	}

	static JSONObject ask (final File save) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new GetVendors().onQuery(mock(CefBrowser.class), 0
			, new JSONObject().put("oldSave", save.getAbsolutePath()).put("savedYet", false).toString()
			, false, callback);

		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).success(reply.capture());
		return new JSONObject(reply.getValue());
	}

	private static JSONObject vendor (final JSONObject reply, final String id) {
		final JSONArray vendors = reply.getJSONArray("vendors");
		for (int i = 0; i < vendors.length(); i++) {
			if (vendors.getJSONObject(i).getString("id").equalsIgnoreCase(id)) {
				return vendors.getJSONObject(i);
			}
		}

		throw new AssertionError("no vendor " + id + " in " + vendors);
	}

	@Test
	public void everyVendorComesWithItsStock () throws Exception {
		mockSettings().json = new JSONObject();
		useCatalog();
		final JSONObject reply = ask(VendorStockTest.setupSave(getClass()));

		assertEquals(5, reply.getJSONArray("vendors").length());
		assertEquals(0, reply.getJSONArray("unreadable").length());

		final JSONObject artificer = vendor(reply, ARTIFICER);
		assertEquals("Artificer", artificer.getString("name"));
		assertEquals("Artificer Hall", artificer.getString("area"));
		assertEquals("AR_0611_Artificer_Hall.lvl", artificer.getString("file"));
		assertTrue(artificer.getBoolean("opened"));

		final JSONObject arrow = artificer.getJSONArray("items").getJSONObject(0);
		assertEquals("b33915e6-fee7-4a29-a043-b65d91b1200e", arrow.getString("guid"));
		assertEquals("an entry is its place in the list; a GUID can repeat", 0, arrow.getInt("index"));
		assertEquals("trap_item_arrow", arrow.getString("key"));
		assertEquals("Arrow Trap", arrow.getString("displayName"));
		assertEquals(1, arrow.getInt("stackSize"));
		assertFalse(arrow.getBoolean("original"));
		assertTrue(arrow.getBoolean("hasPacket"));
		assertEquals(16, arrow.getInt("filter"));
		assertEquals(100, arrow.getInt("value"));
		assertEquals("Item.GetBuyValue: ceil(value * sellMultiplier)", 150, arrow.getInt("price"));
		assertFalse("icons are asked for by key, not shipped here", arrow.has("icon"));
	}

	@Test
	public void originalStockIsPricedAndMarked () throws Exception {
		mockSettings().json = new JSONObject();
		useCatalog();
		final JSONObject fishery = vendor(ask(VendorStockTest.setupSave(getClass())), FISHERY);

		assertFalse(fishery.getBoolean("opened"));
		final JSONObject ring = fishery.getJSONArray("items").getJSONObject(1);
		assertEquals("Ring of Overseeing", ring.getString("displayName"));
		assertEquals("unique", ring.getString("quality"));
		assertTrue(ring.getBoolean("original"));
		assertEquals("rounded up, as the store rounds", 2102, ring.getInt("price"));
	}

	// Without a catalog a name still reads as one, and nothing claims a price.
	@Test
	public void anUncataloguedItemIsNamedFromItsPrefab () throws Exception {
		mockSettings().json = new JSONObject();
		final JSONObject heodan = vendor(ask(VendorStockTest.setupSave(getClass())), HEODAN);

		final JSONObject lockpicks = heodan.getJSONArray("items").getJSONObject(1);
		assertEquals("Lockpick", lockpicks.getString("displayName"));
		assertEquals(3, lockpicks.getInt("stackSize"));
		assertFalse(lockpicks.has("price"));
	}

	// An Apply writes the private working copy; the list must come from there
	// too, or it would show the stock that was just taken away.
	@Test
	public void theListReadsTheWorkingCopy () throws Exception {
		mockSettings().json = new JSONObject();
		final File save = VendorStockTest.setupSave(getClass());

		final CefQueryCallback applied = mock(CefQueryCallback.class);
		new UpdateVendors().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("oldSave", save.getAbsolutePath())
			.put("savedYet", false)
			.put("removals", new JSONArray().put(new JSONObject()
				.put("file", "AR_0611_Artificer_Hall.lvl")
				.put("vendor", ARTIFICER)
				.put("items", new JSONArray().put(new JSONObject()
					.put("index", 0).put("guid", "b33915e6-fee7-4a29-a043-b65d91b1200e")))))
			.toString(), false, applied);
		verify(applied, timeout(60000)).success(anyString());

		assertEquals(1, vendor(ask(save), ARTIFICER).getJSONArray("items").length());
		assertEquals("the save the list unpacked is never edited"
			, 2, VendorStock.read(save, "AR_0611_Artificer_Hall.lvl", ARTIFICER).get().items.size());
	}

	@Test
	public void aSaveThatIsNotThereIsAnError () {
		mockSettings().json = new JSONObject();
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new GetVendors().onQuery(mock(CefBrowser.class), 0
			, new JSONObject().put("oldSave", "Z:\\nowhere\\at.all").put("savedYet", false).toString()
			, false, callback);

		verify(callback, timeout(60000)).failure(anyInt(), anyString());
		verify(callback, never()).success(anyString());
	}

	@Test
	public void unreadableAreasAreNamed () throws Exception {
		mockSettings().json = new JSONObject();
		final File save = VendorStockTest.setupSave(getClass());
		final File chapel = new File(save, "AR_0609_Chapel.lvl");
		final byte[] bytes = FileUtils.readFileToByteArray(chapel);
		FileUtils.writeByteArrayToFile(chapel, java.util.Arrays.copyOf(bytes, bytes.length - 64));

		final JSONObject reply = ask(save);
		assertEquals(4, reply.getJSONArray("vendors").length());
		assertEquals("AR_0609_Chapel.lvl", reply.getJSONArray("unreadable").getString(0));
	}
}
