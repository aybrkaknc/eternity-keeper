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
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.save.Loadout;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.Property;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Opens a {@code .loadout} file the user chooses and says what putting it on a
 * character would do, item by item, before anything is changed.
 *
 * <p>The request is {@code {absolutePath, savedYet, GUID}}, the character to
 * put it on; the reply is {@link #describe}'s. Nothing is written: putting it
 * on is {@code ApplyLoadout}, with the file this names.
 */
public class ReadLoadout extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ReadLoadout.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		final JSONObject json;
		try {
			json = new JSONObject(request);
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return true;
		}

		ChrDialog.choose(browser, FileDialogMode.FILE_DIALOG_OPEN, "Choose a loadout", Loadout.EXTENSION
			, "", callback, ExportLoadout.CANCELLED, path -> answer(json, path, callback));

		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	/** What the chosen file would do; public so it can be asked without a dialog. */
	public static void answer (final JSONObject request, final String path, final CefQueryCallback callback) {
		// ApplyLoadout takes nothing else, so this says so before the plan
		// rather than after it is confirmed. (A .chr would read: it is a
		// character and their things too.)
		if (!path.toLowerCase(Locale.ROOT).endsWith("." + Loadout.EXTENSION)) {
			callback.failure(-1, new File(path).getName() + " is not a loadout: only a ."
				+ Loadout.EXTENSION + " file can be put on.");
			return;
		}

		try {
			final File save = Environment.getInstance().state().workingSave().forReading(
				new File(request.getString("absolutePath")), request.optBoolean("savedYet", false));
			final Loadout loadout = Loadout.read(new File(path));
			final List<Property> packets = new PacketDeserializer(new File(save, "MobileObjects.save"))
				.deserialize()
				.orElseThrow(() -> new IOException("The save's world state could not be read."))
				.getPackets();

			final String target = request.getString("GUID");
			callback.success(describe(path, loadout, loadout.plan(packets, target), packets).toString());
		} catch (final JSONException e) {
			logger.error("Error reading request %s: %s%n", request, e.getMessage());
			callback.failure(-1, "Error parsing JSON request.");
		} catch (final FileNotFoundException e) {
			callback.failure(-1, "That file is not there any more.");
		} catch (final WriteRefusedException | IllegalArgumentException e) {
			callback.failure(-1, e.getMessage());
		} catch (final IOException e) {
			logger.error("Reading the loadout %s failed: %s%n", path, e.getMessage());
			callback.failure(-1, e.getMessage());
		}
	}

	/**
	 * A plan in words: {@code {file, from, className, items: [{place, name,
	 * icon, stack, fits, reason, replaces}], cleared: [{name}], fitting,
	 * icons}}, where {@code replaces} and {@code cleared} name what goes to
	 * the stash: what an item takes the place of, and what comes off a weapon
	 * set the loadout puts on whole.
	 */
	public static JSONObject describe (
		final String file, final Loadout loadout, final Loadout.Plan plan, final List<Property> packets) {

		final JSONObject icons = new JSONObject();
		final JSONArray items = new JSONArray();
		for (final Loadout.Fit fit : plan.fits) {
			final String prefab = fit.slot.prefab;
			final JSONObject item = new JSONObject()
				.put("place", fit.slot.place())
				.put("name", nameOf(prefab))
				.put("stack", fit.slot.stack)
				.put("fits", fit.reason == null);

			iconOf(prefab, icons).ifPresent(key -> item.put("icon", key));
			if (fit.reason != null) {
				item.put("reason", fit.reason);
			}

			if (fit.replaces != null) {
				item.put("replaces", nameOf(prefabIn(packets, fit.replaces)));
			}

			items.put(item);
		}

		final JSONArray cleared = new JSONArray();
		for (final String guid : plan.cleared) {
			cleared.put(new JSONObject().put("name", nameOf(prefabIn(packets, guid))));
		}

		return new JSONObject()
			.put("file", file)
			.put("from", loadout.characterName)
			.put("className", loadout.characterClass)
			.put("items", items)
			.put("cleared", cleared)
			.put("fitting", plan.fitting())
			.put("icons", icons);
	}

	private static String nameOf (final String prefab) {
		return ItemCatalog.getInstance().lookup(prefab)
			.map(entry -> entry.name)
			.filter(name -> !name.isEmpty())
			.orElse(SavedGameOpener.prettifyItemName(prefab));
	}

	private static String prefabIn (final List<Property> packets, final String guid) {
		return SaveModel.of(packets).object(guid)
			.map(SaveObject::name)
			.map(name -> name.replace("(Clone)", "").trim())
			.orElse("");
	}

	private static Optional<String> iconOf (final String prefab, final JSONObject icons) {
		final String key = ItemCatalog.keyOf(prefab);
		if (!icons.has(key)) {
			ItemCatalog.getInstance().lookup(prefab)
				.filter(entry -> !entry.icon.isEmpty())
				.map(entry -> ItemCatalog.getInstance().iconData(entry.icon))
				.filter(data -> !data.isEmpty())
				.ifPresent(data -> icons.put(key, data));
		}

		return icons.has(key) ? Optional.of(key) : Optional.empty();
	}
}
