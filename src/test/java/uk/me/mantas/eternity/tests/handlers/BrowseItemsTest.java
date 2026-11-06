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
import uk.me.mantas.eternity.handlers.BrowseItems;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.Base64;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class BrowseItemsTest extends TestHarness {
	@After
	public void restoreCatalog () {
		ItemCatalog.useNoCatalog();
	}

	// The Vendors tab lists thousands of items without their art and asks for
	// the icons of the store on screen, the way the ability browser does.
	@Test
	public void iconsCanBeAskedForByKey () throws Exception {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		FileUtils.write(new File(directory.get(), "catalog.json"), "{"
			+ "\"trap_item_arrow\":{\"name\":\"Arrow Trap\",\"icon\":\"trap_arrow.png\"}"
			+ ",\"lockpick\":{\"name\":\"Lockpick\",\"icon\":\"missing.png\"}"
			+ ",\"torch\":{\"name\":\"Torch\"}"
			+ "}", "UTF-8");
		FileUtils.writeByteArrayToFile(
			new File(directory.get(), "icons/trap_arrow.png"), new byte[] {1, 2, 3});
		ItemCatalog.useCatalogAt(directory.get());

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new BrowseItems().onQuery(mock(CefBrowser.class), 0, new JSONObject()
			.put("iconKeys", new JSONArray()
				.put("Trap_Item_Arrow").put("lockpick").put("torch").put("no_such_item"))
			.toString(), false, callback);

		final ArgumentCaptor<String> reply = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).success(reply.capture());

		final JSONObject icons = new JSONObject(reply.getValue()).getJSONObject("icons");
		assertEquals("only an icon that exists comes back", 1, icons.length());
		assertEquals(Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
			, icons.getString("trap_item_arrow"));
	}
}
