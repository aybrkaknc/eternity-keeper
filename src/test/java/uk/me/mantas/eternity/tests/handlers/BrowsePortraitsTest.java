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
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.handlers.BrowsePortraits;
import uk.me.mantas.eternity.save.PortraitCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The paging contract the picker is built on. It asks for a window of the
 * catalog at a time and appends what comes back, so the pages have to tile the
 * whole list: every portrait exactly once, and a {@code total} that does not
 * move between requests.
 *
 * <p>Worth pinning even though the handler was right: the client's own
 * arithmetic for "how many are left" was not, and it showed 80 of 118
 * portraits with the button quoting "-2 left". A reader who finds that bug's
 * fix should be able to see what the server half promises.
 */
public class BrowsePortraitsTest extends TestHarness {
	private void useFixtureCatalog () throws Exception {
		final File resources = new File(getClass().getResource("/").toURI());
		PortraitCatalog.useCatalogAt(new File(resources, "SavedGameOpenerTest"));
	}

	@After
	public void restoreCatalog () {
		PortraitCatalog.useNoCatalog();
	}

	private JSONObject page (final int offset, final int limit) {
		final JSONObject request = new JSONObject();
		request.put("offset", offset);
		request.put("limit", limit);

		final AtomicReference<String> reply = new AtomicReference<>();
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		doAnswer(invocation -> {
			reply.set(invocation.getArgument(0));
			return null;
		}).when(callback).success(anyString());

		new BrowsePortraits().onQuery(
			mock(CefBrowser.class), 0, request.toString(), false, callback);

		verify(callback, timeout(60000)).success(anyString());
		verify(callback, never()).failure(anyInt(), anyString());

		return new JSONObject(reply.get());
	}

	@Test
	public void pagesTileTheWholeCatalogExactlyOnce () throws Exception {
		useFixtureCatalog();
		final int total = PortraitCatalog.getInstance().size();
		assertTrue("the fixture needs at least two portraits", total >= 2);

		final Set<String> seen = new HashSet<>();
		final List<Integer> sizes = new ArrayList<>();

		// One at a time is the harshest tiling, and the one the picker does
		// with a page of 40 against 118.
		for (int offset = 0; offset < total; offset++) {
			final JSONObject response = page(offset, 1);
			assertEquals("total must not move between pages",
				total, response.getInt("total"));

			final JSONArray portraits = response.getJSONArray("portraits");
			sizes.add(portraits.length());
			assertEquals("one portrait was asked for", 1, portraits.length());

			final JSONObject portrait = portraits.getJSONObject(0);
			assertTrue("a portrait must not appear on two pages",
				seen.add(portrait.getString("key")));
			assertTrue(portrait.getString("large").endsWith("_lg.png"));
			assertTrue(portrait.getString("small").endsWith("_sm.png"));
		}

		assertEquals("every portrait is reachable by paging", total, seen.size());
		assertEquals(total, sizes.size());
	}

	@Test
	public void runningOffTheEndIsEmptyRatherThanAnError () throws Exception {
		useFixtureCatalog();
		final int total = PortraitCatalog.getInstance().size();

		final JSONObject response = page(total + 10, 40);
		assertEquals(0, response.getJSONArray("portraits").length());
		assertEquals(total, response.getInt("total"));
		assertTrue(response.getBoolean("available"));
	}

	@Test
	public void everyCategoryIsOfferedAndItsPortraitsAddUp () throws Exception {
		useFixtureCatalog();

		final JSONObject all = page(0, 200);
		final JSONArray categories = all.getJSONArray("categories");
		assertTrue("the fixture has portraits in folders", categories.length() > 0);

		int counted = 0;
		for (int i = 0; i < categories.length(); i++) {
			final JSONObject request = new JSONObject();
			request.put("category", categories.getString(i));
			request.put("offset", 0);
			request.put("limit", 200);

			final AtomicReference<String> reply = new AtomicReference<>();
			final CefQueryCallback callback = mock(CefQueryCallback.class);
			doAnswer(invocation -> {
				reply.set(invocation.getArgument(0));
				return null;
			}).when(callback).success(anyString());

			new BrowsePortraits().onQuery(
				mock(CefBrowser.class), 0, request.toString(), false, callback);
			verify(callback, timeout(60000)).success(anyString());

			counted += new JSONObject(reply.get()).getJSONArray("portraits").length();
		}

		// Every portrait in the fixture sits in a folder, so the filters have
		// to account for all of them — a portrait no filter reaches is one the
		// player cannot find.
		assertEquals(all.getInt("total"), counted);
	}

	@Test
	public void saysSoWhenThereIsNoInstallToReadFrom () {
		PortraitCatalog.useNoCatalog();

		final JSONObject response = page(0, 40);
		assertFalse(response.getBoolean("available"));
		assertEquals(0, response.getInt("total"));
	}
}
