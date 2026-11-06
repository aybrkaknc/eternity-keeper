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
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SaveConverter;
import uk.me.mantas.eternity.save.SaveConverter.Format;

import java.io.File;
import java.io.IOException;

/**
 * Reports which Unity assembly naming a save uses, and on request writes a
 * converted copy beside it.
 *
 * <p>Request: {@code {savePath: <.savegame>, convert: <boolean>}}. With
 * {@code convert} false nothing is written — the reply just says what the save
 * is and where a converted copy would go, which is what the dialog needs to
 * explain itself before the user commits to anything.
 */
public class ConvertSave extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ConvertSave.class);
	private static final String CONVERTED_FOLDER = "converted";

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(Answered.to(callback, () -> convert(request, callback)));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void convert (final String request, final CefQueryCallback callback) {
		final File save;
		final boolean write;

		try {
			final JSONObject json = new JSONObject(request);
			save = new File(json.getString("savePath"));
			write = json.optBoolean("convert", false);
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		if (!save.isFile()) {
			logger.error("No save at %s.%n", save.getAbsolutePath());
			callback.failure(-1, "Unable to find the save file.");
			return;
		}

		final File destinationFolder = new File(save.getParentFile(), CONVERTED_FOLDER);
		final File destination = new File(destinationFolder, save.getName());

		try {
			final Format format = SaveConverter.detectSave(save);
			final JSONObject response = new JSONObject();

			response.put("format", format.name());
			response.put("convertible", format == Format.MODERN);
			response.put("destination", destination.getAbsolutePath());
			response.put("existing", destination.isFile());
			response.put("converted", false);

			if (!write) {
				callback.success(response.toString());
				return;
			}

			if (format != Format.MODERN) {
				callback.failure(-1, "This save is already in the older format.");
				return;
			}

			final long start = System.nanoTime();
			final SaveConverter.Result result =
				SaveConverter.convertArchive(save, destinationFolder, Format.LEGACY);

			response.put("converted", true);
			response.put("destination", result.output.getAbsolutePath());
			response.put("files", result.converted);
			response.put("copied", result.copied);
			response.put("fellBack", result.fellBack);
			response.put("replacements", result.replacements);
			response.put("millis", (System.nanoTime() - start) / 1000000L);

			logger.info(
				"Converted %s: %d type names across %d files in %d ms.%n"
				, save.getName(), result.replacements, result.converted
				, (System.nanoTime() - start) / 1000000L);

			callback.success(response.toString());
		} catch (final IOException e) {
			logger.error("Error converting %s: %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(-1, e.getMessage());
		}
	}
}
