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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.save.CharacterImporter.ImportConflict;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Optional;

public class ImportCharacter extends CefMessageRouterHandlerAdapter {
	private static final Logger logger = Logger.getLogger(ImportCharacter.class);

	@Override
	public boolean onQuery (
		CefBrowser browser
		, long id
		, String request
		, boolean persistent
		, CefQueryCallback callback) {

		// Second phase of an import: the client already knows which CHR file
		// to use (typically after the user confirmed an overwrite), so no
		// file dialog is needed.
		String confirmedChrPath = null;
		boolean overwrite = false;
		try {
			final JSONObject json = new JSONObject(request);
			if (json.has("chrPath")) {
				confirmedChrPath = json.getString("chrPath");
				overwrite = json.optBoolean("overwrite", false);
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return true;
		}

		if (confirmedChrPath != null) {
			final String chrPath = confirmedChrPath;
			final boolean overwriteExisting = overwrite;
			Environment.getInstance().mutationWorker().execute(Answered.to(callback
				, () -> doImport(request, callback, chrPath, overwriteExisting)));
		} else {
			ChrDialog.choose(browser, FileDialogMode.FILE_DIALOG_OPEN, "Choose a character"
				, callback, "NO_SAVE", chrFile -> analyseThenImport(request, callback, chrFile));
		}

		return true;
	}

	@Override
	public void onQueryCanceled (CefBrowser browser, long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	// First phase: if the character already exists in the target save, ask
	// the client to confirm the overwrite instead of importing right away.
	private void analyseThenImport (
		final String request
		, final CefQueryCallback callback
		, final String chrFile) {

		try {
			final CharacterImporter importer = new CharacterImporter(request, chrFile);
			final Optional<ImportConflict> conflict = importer.detectConflict();

			if (conflict.isPresent()) {
				final JSONObject confirm = new JSONObject();
				confirm.put("characterName", conflict.get().characterName);
				confirm.put("isMainCharacter", conflict.get().mainCharacter);
				confirm.put("chrPath", chrFile);

				final JSONObject response = new JSONObject();
				response.put("confirmOverwrite", confirm);
				callback.success(response.toString());
				return;
			}
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		} catch (final FileNotFoundException e) {
			logger.error("File not found: %s%n", e.getMessage());
			callback.failure(-1, "Unable to find your save or CHR file.");
			return;
		} catch (final WriteRefusedException e) {
			logger.error("Import refused: %s%n", e.getMessage());
			callback.failure(-1, e.getMessage());
			return;
		} catch (final IOException e) {
			logger.error("%s%n", e.getMessage());
			callback.failure(-1, "Error reading save or CHR file.");
			return;
		}

		doImport(request, callback, chrFile, false);
	}

	// The import is an edit of the working save like any Apply, run through
	// the same steps (SaveMutation): a step of the save's history, put back
	// when refused or failed, taken back when it cannot be handed back.
	private void doImport (
		final String request
		, final CefQueryCallback callback
		, final String chrFile
		, final boolean overwrite) {

		new SaveMutation(logger, true, (save, json) -> {
			final CharacterImporter importer;
			try {
				importer = new CharacterImporter(request, chrFile);
			} catch (final FileNotFoundException e) {
				logger.error("File not found: %s%n", e.getMessage());
				return "Unable to find your save or CHR file.";
			}

			final boolean imported = overwrite
				? importer.overwriteCharacter()
				: importer.importCharacter();

			return imported ? null : "Character import failed.";
		}).run(request, callback);
	}
}
