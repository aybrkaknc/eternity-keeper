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

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.UpdateVendors;
import uk.me.mantas.eternity.save.VendorStock;
import uk.me.mantas.eternity.tests.TestHarness;
import uk.me.mantas.eternity.tests.save.VendorStockTest;

import java.io.File;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

// Apply on the Vendors tab. The fixture is VendorStockTest's.
public class UpdateVendorsTest extends TestHarness {
	private static final String FISHERY = "3532ceaa-c805-4e7f-8f05-d299654b7189";
	private static final String RING = "2da7138a-1d14-4200-b8e1-9c634f2c920f";
	private static final String HEODAN = "b1a7e810-0000-0000-0000-000000000000";
	private static final String LOCKPICKS = "c8ee2ef2-219c-49bb-9dd3-9e394ecc9dd9";

	private static JSONObject request (final File save, final JSONObject... removals) {
		final JSONArray list = new JSONArray();
		for (final JSONObject removal : removals) {
			list.put(removal);
		}

		return new JSONObject()
			.put("oldSave", save.getAbsolutePath())
			.put("savedYet", false)
			.put("removals", list);
	}

	// Each item is its place in the store's lists and the GUID it should hold.
	private static JSONObject removal (
		final String file, final String vendor, final int index, final String guid) {

		return new JSONObject().put("file", file).put("vendor", vendor).put("items", new JSONArray()
			.put(new JSONObject().put("index", index).put("guid", guid)));
	}

	// The reply is the reopened save, like every Apply: taking stock out of
	// the world state's store is an edit to the file the rest of the editor
	// reads too.
	@Test
	public void theReplyIsTheReopenedSave () throws Exception {
		mockSettings().json = new JSONObject();
		final File save = VendorStockTest.setupSave(getClass());

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new UpdateVendors().onQuery(mock(CefBrowser.class), 0, request(save
			, removal("PX1_0004_Fishery.lvl", FISHERY, 1, RING)
			, removal("MobileObjects.save", HEODAN, 1, LOCKPICKS)).toString(), false, callback);

		verify(callback, timeout(60000)).success(argThat(reply ->
			new JSONObject(reply).has("characters")));

		final File working =
			Environment.getInstance().state().workingSave().forReading(save, false);
		assertNotEquals(save.getAbsoluteFile(), working.getAbsoluteFile());
		assertEquals(1, VendorStock.read(working, "PX1_0004_Fishery.lvl", FISHERY).get().items.size());
		assertEquals(20, VendorStock.read(working, "MobileObjects.save", HEODAN).get().items.size());
		assertEquals(2, VendorStock.read(save, "PX1_0004_Fishery.lvl", FISHERY).get().items.size());
	}

	@Test
	public void aStaleRequestSaysWhy () throws Exception {
		mockSettings().json = new JSONObject();
		final File save = VendorStockTest.setupSave(getClass());

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new UpdateVendors().onQuery(mock(CefBrowser.class), 0, request(save
			, removal("PX1_0004_Fishery.lvl", FISHERY, 1, LOCKPICKS)).toString(), false, callback);

		verify(callback, timeout(60000)).failure(anyInt(), argThat(message ->
			message.contains("Fishery") && message.contains("out of date")));
		verify(callback, never()).success(anyString());
	}

	@Test
	public void aRequestWithoutRemovalsIsAnError () throws Exception {
		mockSettings().json = new JSONObject();
		final File save = VendorStockTest.setupSave(getClass());

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new UpdateVendors().onQuery(mock(CefBrowser.class), 0
			, new JSONObject().put("oldSave", save.getAbsolutePath()).put("savedYet", false).toString()
			, false, callback);

		verify(callback, timeout(60000)).failure(anyInt(), eq("Error parsing JSON request."));
	}
}
