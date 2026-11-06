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

import org.json.JSONObject;
import uk.me.mantas.eternity.environment.EditHistory;
import uk.me.mantas.eternity.environment.Environment;

import java.io.File;
import java.io.IOException;

/**
 * Undoes or redoes an Apply: {@code {oldSave, savedYet, action, step}}, where
 * {@code action} is "undo" or "redo" and {@code step} the {@code historyStep}
 * the Apply's reply carried. The files the step wrote are swapped with the ones
 * it replaced ({@link EditHistory}), and the reply is the reopened save, as for
 * every Apply. Only the last step can be undone, and only the last one undone
 * redone; anything else means the page and the history no longer agree, and
 * nothing is touched.
 */
public class ChangeHistory extends SaveMutationHandler {
	@Override
	protected boolean recorded () {
		return false;
	}

	@Override
	protected String mutate (final File save, final JSONObject request) throws IOException {
		final EditHistory history = Environment.getInstance().state().workingSave().history();
		final long step = request.getLong("step");

		if ("redo".equals(request.getString("action"))) {
			return history.redo(save, step) ? null
				: "That change is no longer the last one undone, so nothing was redone.";
		}

		return history.undo(save, step) ? null
			: "That change is no longer the last one in the editor's history, so nothing was"
				+ " undone. Reopening the save starts the history again from the file.";
	}
}
