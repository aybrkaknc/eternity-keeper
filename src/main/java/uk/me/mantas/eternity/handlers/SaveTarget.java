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
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.ChangesSaver;

import java.io.File;
import java.util.Optional;
import java.util.Vector;

/**
 * Tells the UI exactly where a save is about to land, and lets the user pick
 * somewhere else.
 *
 * <p>The file name the game uses has nothing to do with the name typed into the
 * save dialog — that one only goes into {@code saveinfo.xml}. On disk a save is
 * {@code <sessionID> <gameID> <SceneTitle>.savegame}, which is exactly why
 * people struggle to find the file they just wrote. Two actions:
 * {@code {action:"preview", oldSave}} answers with the real file name and
 * folder, and {@code {action:"choose", fileName}} asks the user for a folder
 * and stores it as the new saves location.
 *
 * <p><b>Choosing used to freeze the editor.</b> It opened a Swing
 * {@code JFileChooser} on the event dispatch thread, from inside a CEF query.
 * A modal Swing dialog with no owner disables every frame in the process — the
 * main window reported {@code enabled=False} while it was up — and it could
 * open behind whatever had focus, which left a window that ignored every click
 * with no dialog in sight to explain why. A Swing modal loop running inside a
 * CEF-hosted window is also the kind of thing that turns a hang into a native
 * crash, and that is what the user saw.
 *
 * <p>Now it uses CEF's own native Save dialog, the one Export Character has
 * always used: it is owned by the browser window and runs through CEF rather
 * than through a second event loop. The bundled JCEF has no folder mode, so the
 * dialog opens on the file name the game will actually use, and only the folder
 * it points into is kept — a save renamed here would not appear on the game's
 * load screen.
 */
public class SaveTarget extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(SaveTarget.class);

	@Override
	public boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		final String action;
		final String oldSave;
		final String fileName;

		try {
			final JSONObject json = new JSONObject(request);
			action = json.optString("action", "preview");
			oldSave = json.optString("oldSave", "");
			fileName = json.optString("fileName", "");
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return true;
		}

		if ("choose".equals(action)) {
			browser.runFileDialog(
				FileDialogMode.FILE_DIALOG_SAVE
				, "Choose where to save (the game names the file itself)"
				, dialogStartingPath(currentLocation(), fileName)
				, new Vector<>()
				, 0
				, (selectedFilter, chosen) -> choose(chosen, callback));

			return true;
		}

		Environment.getInstance().workers().execute(Answered.to(callback, () -> preview(oldSave, callback)));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	/**
	 * Where the dialog should open: the game's own file name, inside the
	 * current saves folder when there is one.
	 */
	public static String dialogStartingPath (final String folder, final String fileName) {
		if (folder == null || folder.isEmpty()) {
			return fileName == null ? "" : fileName;
		}

		return new File(folder, fileName == null ? "" : fileName).getAbsolutePath();
	}

	/**
	 * The folder a Save dialog's answer points into, or empty when the user
	 * cancelled or named somewhere that does not exist. A name typed into the
	 * dialog is deliberately dropped: only its folder is kept.
	 */
	public static Optional<File> folderFromDialog (final Vector<String> chosen) {
		if (chosen == null || chosen.isEmpty()) {
			return Optional.empty();
		}

		final String path = chosen.get(0);
		if (path == null || path.trim().isEmpty()) {
			return Optional.empty();
		}

		final File picked = new File(path);
		final File folder = picked.isDirectory() ? picked : picked.getParentFile();

		return folder != null && folder.isDirectory()
			? Optional.of(folder.getAbsoluteFile())
			: Optional.empty();
	}

	private static String currentLocation () {
		return Settings.getInstance().json.optString("savesLocation", "");
	}

	private void preview (final String oldSave, final CefQueryCallback callback) {
		final JSONObject response = new JSONObject();
		response.put("directory", currentLocation());
		response.put("fileName", ChangesSaver.previewSaveFileName(oldSave));
		callback.success(response.toString());
	}

	private void choose (final Vector<String> chosen, final CefQueryCallback callback) {
		final Optional<File> folder = folderFromDialog(chosen);

		if (!folder.isPresent()) {
			callback.failure(-1, "NO_FOLDER");
			return;
		}

		Settings.getInstance().json.put("savesLocation", folder.get().getAbsolutePath());
		Settings.getInstance().save();

		final JSONObject response = new JSONObject();
		response.put("directory", folder.get().getAbsolutePath());
		callback.success(response.toString());
	}
}
