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

import org.cef.callback.CefQueryCallback;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.EditHistory;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.File;
import java.io.IOException;

/**
 * One edit of the working save, from the request to the reopened save handed
 * back: which copy to edit, the step of the save's {@link EditHistory} it
 * becomes, what is put back when it is refused or fails, and the reopen. Runs
 * on the calling thread, which is the mutation worker (invariant 9).
 *
 * <p>{@link SaveMutationHandler} runs its {@code mutate} through this. A
 * handler that cannot be one -- an import first asks for a file and may have
 * to ask whether to overwrite -- runs its edit through this too. The import
 * used to keep its own copy of these steps, and twice that copy lacked an
 * undo the original had (review of 2026-10-27).
 */
final class SaveMutation {
	/** The edit itself: {@code null} when made, or what to tell the user when refused. */
	@FunctionalInterface
	interface Edit {
		String apply (File save, JSONObject request) throws IOException;
	}

	private final Logger logger;
	private final boolean recorded;
	private final Edit edit;

	/**
	 * @param recorded whether the edit is a step of the save's history; only
	 *                 undoing and redoing are not
	 */
	SaveMutation (final Logger logger, final boolean recorded, final Edit edit) {
		this.logger = logger;
		this.recorded = recorded;
		this.edit = edit;
	}

	void run (final String request, final CefQueryCallback callback) {
		final JSONObject json;
		final File opened;
		final boolean savedYet;

		try {
			json = new JSONObject(request);
			opened = new File(json.getString("oldSave"));
			savedYet = json.getBoolean("savedYet");
		} catch (final JSONException e) {
			logger.error("Error parsing JSON request: %s%n", request);
			callback.failure(-1, "Error parsing JSON request.");
			return;
		}

		final EditHistory history = Environment.getInstance().state().workingSave().history();
		File save = opened;
		long step = 0;
		boolean recording = false;
		try {
			// Never the directory the list unpacked: an edit the user goes on
			// to discard must not be there when they open the save again.
			save = Environment.getInstance().state().workingSave().forEditing(opened, savedYet);
			if (!save.exists()) {
				callback.failure(-1, "Unable to find your save file.");
				return;
			}

			// What the edit replaces is kept, so the page can undo it.
			if (recorded) {
				history.begin(save);
				recording = true;
			}

			final String problem = edit.apply(save, json);
			if (problem != null) {
				callback.failure(-1, problem);
				return;
			}

			if (recording) {
				step = history.commit();
				recording = false;
			}
		} catch (final JSONException e) {
			logger.error("Error reading request %s: %s%n", request, e.getMessage());
			callback.failure(-1, "Error parsing JSON request.");
			return;
		} catch (final WriteRefusedException e) {
			// A save read only in part, or an edit that would have left it
			// contradicting itself: nothing was written, so "could not write"
			// would blame the wrong step. The refusal says what happened.
			logger.error("Editing %s refused: %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(-1, e.getMessage());
			return;
		} catch (final IOException e) {
			logger.error("Editing %s failed: %s%n", save.getAbsolutePath(), e.getMessage());
			callback.failure(-1, "Could not write the save: " + e.getMessage());
			return;
		} finally {
			// Refused, or failed part of the way: whatever it wrote goes back.
			if (recording) {
				history.abort();
			}
		}

		final SavedGameOpener opener = new SavedGameOpener(save.getAbsolutePath(), callback);
		if (step > 0) {
			opener.with("historyStep", step);
		}

		try {
			opener.reopen();
		} catch (final SavedGameOpener.Unreadable e) {
			logger.error("Reading %s back after editing it failed: %s%n", save.getAbsolutePath(), e.getMessage());
			takeBack(history, save, step);
			callback.failure(-1, e.takenBack());
		} catch (final RuntimeException | StackOverflowError e) {
			takeBack(history, save, step);
			throw e;
		}
	}

	// Written, and then not readable back. The page is told the edit was not
	// made and goes on showing the save as it was, so the save goes back to
	// that too: its next edit would otherwise be worked out against a save the
	// page has never seen.
	private void takeBack (final EditHistory history, final File save, final long step) {
		if (step > 0) {
			try {
				history.undo(save, step);
			} catch (final IOException undoing) {
				logger.error("The edit could not be taken back either: %s%n", undoing.getMessage());
			}
		}
	}
}
