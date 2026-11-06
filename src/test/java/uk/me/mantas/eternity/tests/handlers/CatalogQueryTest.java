/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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
import uk.me.mantas.eternity.handlers.CatalogQuery;
import uk.me.mantas.eternity.tests.TestHarness;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * What the item, ability and portrait browsers share: answered on the worker
 * pool, a malformed request reported as one, and a page window that cannot be
 * asked for outside the list.
 */
public class CatalogQueryTest extends TestHarness {
	private static final List<String> LETTERS = Arrays.asList("a", "b", "c", "d", "e");

	/** Pages through five letters, or fails however the test asks. */
	private static final class Letters extends CatalogQuery {
		final Function<JSONObject, JSONObject> answering;

		Letters (final Function<JSONObject, JSONObject> answering) {
			this.answering = answering;
		}

		@Override
		protected JSONObject answer (final JSONObject request) {
			return answering.apply(request);
		}
	}

	private static final Letters PAGING = new Letters(request -> {
		final int offset = CatalogQuery.offset(request);
		final JSONObject response = new JSONObject();
		response.put("offset", offset);
		response.put("rows", CatalogQuery.page(LETTERS, offset, CatalogQuery.limit(request, 2, 3)
			, letter -> new JSONObject().put("letter", letter)));
		return response;
	});

	private static JSONObject ask (final CatalogQuery query, final String request) {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		query.onQuery(mock(CefBrowser.class), 0, request, false, callback);

		final String[] answer = new String[1];
		verify(callback, timeout(30000)).success(argThat(response -> {
			answer[0] = response;
			return true;
		}));

		return new JSONObject(answer[0]);
	}

	private static String letters (final JSONObject response) {
		final JSONArray rows = response.getJSONArray("rows");
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < rows.length(); i++) {
			out.append(rows.getJSONObject(i).getString("letter"));
		}

		return out.toString();
	}

	@Test
	public void aPageIsTheWindowTheRequestAskedFor () {
		assertEquals("cd", letters(ask(PAGING, "{\"offset\":2}")));
		assertEquals("bcd", letters(ask(PAGING, "{\"offset\":1,\"limit\":3}")));
		assertEquals("e", letters(ask(PAGING, "{\"offset\":4}")));
	}

	@Test
	public void theWindowCannotBeAskedForOutsideTheList () {
		assertEquals("never more than the most", "abc", letters(ask(PAGING, "{\"limit\":500}")));
		assertEquals("never fewer than one", "a", letters(ask(PAGING, "{\"limit\":0}")));
		assertEquals("never before the start", 0, ask(PAGING, "{\"offset\":-7}").getInt("offset"));
		assertEquals("past the end is simply empty", "", letters(ask(PAGING, "{\"offset\":99}")));
	}

	@Test
	public void aMalformedRequestIsReportedAsOne () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		PAGING.onQuery(mock(CefBrowser.class), 0, "{ not json", false, callback);

		verify(callback, timeout(30000)).failure(anyInt(), eq("Error parsing JSON request."));
		verify(callback, never()).success(anyString());
	}

	/**
	 * Before, an exception past the parsing escaped the worker and the
	 * callback never fired: the browser sat on its loading text for good.
	 */
	@Test
	public void aFailureWhileAnsweringStillAnswers () {
		final Letters broken = new Letters(request -> {
			throw new IllegalStateException("the catalog is unreadable");
		});

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		broken.onQuery(mock(CefBrowser.class), 0, "{}", false, callback);

		verify(callback, timeout(30000)).failure(anyInt()
			, argThat(message -> message.contains("the catalog is unreadable")));
	}
}
