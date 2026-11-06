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

package uk.me.mantas.eternity.handlers;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;

import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * A read-only query over one of the catalogs, answered on the worker pool.
 *
 * <p>The item, ability and portrait browsers were each the same thirty lines
 * around their own search: hop onto the workers, parse the request, clamp the
 * page window, report a malformed request. Subclasses supply only
 * {@link #answer}, and an exception while answering is reported rather than
 * escaping the worker and leaving the browser waiting for a reply that never
 * comes.
 */
public abstract class CatalogQuery extends CefMessageRouterHandlerAdapter {
	private final Logger logger = Logger.getLogger(getClass());

	/** The reply to a well-formed request. */
	protected abstract JSONObject answer (JSONObject request);

	@Override
	public final boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().workers().execute(Answered.to(callback, () -> respond(request, callback)));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void respond (final String request, final CefQueryCallback callback) {
		final JSONObject json;
		try {
			json = new JSONObject(request);
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		final String response;
		try {
			response = answer(json).toString();
		} catch (final RuntimeException e) {
			logger.error(e, "Answering %s failed: %s%n", request, e.getMessage());
			callback.failure(-1, "Could not answer: " + e.getMessage());
			return;
		}

		callback.success(response);
	}

	/** The first row a paged request wants; never before the start. */
	public static int offset (final JSONObject request) {
		return Math.max(0, request.optInt("offset", 0));
	}

	/** How many rows it wants: {@code preferred} unless it says, one to {@code most}. */
	public static int limit (final JSONObject request, final int preferred, final int most) {
		return Math.min(most, Math.max(1, request.optInt("limit", preferred)));
	}

	/** Up to {@code limit} of {@code matches} from {@code offset}, each drawn by {@code row}. */
	public static <T> JSONArray page (
		final List<T> matches, final int offset, final int limit
		, final Function<T, JSONObject> row) {

		final JSONArray rows = new JSONArray();
		for (int i = offset; i < matches.size() && rows.length() < limit; i++) {
			rows.put(row.apply(matches.get(i)));
		}

		return rows;
	}

	/**
	 * A JSON array of any collection. Written when org.json (20141113) took only
	 * a {@code Collection<Object>}; the current one takes {@code Collection<?>},
	 * so this is now a convenience rather than a workaround.
	 */
	public static JSONArray array (final Collection<?> values) {
		final JSONArray array = new JSONArray();
		values.forEach(array::put);
		return array;
	}
}
