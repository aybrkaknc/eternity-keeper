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

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.json.JSONArray;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SaveArchiveService;
import uk.me.mantas.eternity.save.SaveGameExtractor;
import uk.me.mantas.eternity.save.SaveGameInfo;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class ListSavedGames extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ListSavedGames.class);

	private static final DateTimeFormatter dateFormatter =
		DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withZoneUTC();

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Settings.getInstance().json.put("savesLocation", request);
		Settings.getInstance().save();
		final Environment environment = Environment.getInstance();

		// Spawn a separate thread to handle all the file stuff so we don't
		// lock up the UI.
		final SaveInfoLister lister = new SaveInfoLister(request, callback);
		environment.state().currentSaveLister(lister);
		environment.workers().execute(Answered.to(callback, lister));

		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d was cancelled.%n", id);
	}

	public static class SaveInfoLister implements Runnable {
		private final String savesLocation;
		private final CefQueryCallback callback;
		public SaveGameExtractor extractor = null;
		private SaveArchiveService service;

		public SaveInfoLister (final String savesLocation, final CefQueryCallback callback) {
			this.savesLocation = savesLocation;
			this.callback = callback;
		}

		@Override
		public void run () {
			// Whatever happens, the page gets an answer: it waits for one with
			// its Search button disabled, and one that never came left it
			// saying "Searching..." until the editor was closed.
			try {
				final Environment environment = Environment.getInstance();
				environment.directory().emptyWorking();
				service = new SaveArchiveService(
					environment.directory().working(), environment.state().unpacked());
				final SaveArchiveService.SaveListResult result = service.list(
					new File(savesLocation), progress -> extractor = progress);
				extractor = service.extractor();
				unpackAllSaves(result);
			} catch (final IOException e) {
				logger.error(e, "Unable to prepare save listing for '%s'.%n", savesLocation);
				failed(callback, new RuntimeException(e));
			} catch (final RuntimeException e) {
				logger.error(e, "Unable to search '%s'.%n", savesLocation);
				failed(callback, e);
			}
		}

		private void unpackAllSaves (final SaveArchiveService.SaveListResult result) {
			final JSONArray unreadable = unreadableToJSON(result.unreadable());
			if (result.saves().isEmpty()) {
				notFound(callback, unreadable);
				return;
			}

			callback.success(new JSONObject()
				.put("saves", saveInfoToJSON(result.saves().toArray(new SaveGameInfo[0])))
				.put("unreadable", unreadable)
				.toString());
		}

		// Kept as a narrow adapter seam for existing handler tests; the production
		// path consumes the typed service result above.
		private void unpackAllSaves (final SaveGameExtractor extractor) {
			final Optional<SaveGameInfo[]> info = extractor.unpackAllSaves();
			final JSONArray unreadable = unreadableToJSON(extractor.unreadable());
			if (!info.isPresent() || info.get().length == 0) {
				notFound(callback, unreadable);
				return;
			}
			callback.success(new JSONObject()
				.put("saves", saveInfoToJSON(info.get()))
				.put("unreadable", unreadable)
				.toString());
		}
	}

	// The files in the folder that are not in the list, each with the reason:
	// a save that is simply missing leaves its owner looking for it.
	private static JSONArray unreadableToJSON (final List<SaveGameExtractor.Unreadable> unreadable) {
		final JSONArray json = new JSONArray();
		for (final SaveGameExtractor.Unreadable file : unreadable) {
			json.put(new JSONObject().put("name", file.name).put("reason", file.reason));
		}

		return json;
	}

	/**
	 * Converts a single {@link SaveGameInfo} domain object into the JSON tile
	 * representation consumed by the frontend.
	 */
	public static JSONObject saveInfoToJSONObject (final SaveGameInfo saveInfo) {
		return new JSONObject()
			.put("guid", saveInfo.guid)
			.put("systemName", saveInfo.systemName)
			.put("absolutePath", saveInfo.absolutePath)
			.put("playerName", saveInfo.playerName)
			.put("sceneTitle", saveInfo.sceneTitle)
			.put("chapter", saveInfo.chapter)
			.put("trialOfIron", saveInfo.trialOfIron)
			.put("date", dateFormatter.print(saveInfo.timestamp))
			.put("timestampMillis", saveInfo.timestamp == null
				? JSONObject.NULL : saveInfo.timestamp.getMillis())
			.put("userSaveName", saveInfo.userSaveName)
			.put("difficulty", saveInfo.difficulty)
			.put("screenshot", saveInfo.screenshot)
			.put("portraits", saveInfo.portraits);
	}

	private static JSONArray saveInfoToJSON (final SaveGameInfo[] info) {
		final JSONObject[] infoJSONObjects = Arrays.stream(info)
			.map(ListSavedGames::saveInfoToJSONObject)
			.toArray(JSONObject[]::new);

		return new JSONArray(infoJSONObjects);
	}

	private static void notFound (final CefQueryCallback callback, final JSONArray unreadable) {
		callback.success(new JSONObject()
			.put("error", "NO_RESULTS")
			.put("unreadable", unreadable)
			.toString());
	}

	private static void failed (final CefQueryCallback callback, final RuntimeException reason) {
		callback.success(new JSONObject()
			.put("error", "LIST_ERR")
			.put("msg", "The saves folder could not be searched: " + reason
				+ ". eternity.log has the details.")
			.toString());
	}
}
