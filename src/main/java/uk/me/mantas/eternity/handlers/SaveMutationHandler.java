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
import org.json.JSONObject;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.environment.EditHistory;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.serializer.WriteRefusedException;

import java.io.File;
import java.io.IOException;

/**
 * A handler that edits the working save and hands the reopened save back.
 *
 * <p>Every one of them takes {@code {oldSave, savedYet, ...}} and does the same
 * thing around its edit: runs on the mutation worker (invariant 9 — two edits
 * can never write the same file at once), asks {@code WorkingSave} which copy
 * of the save to edit, refuses one that is not there, records the edit as a
 * step of the save's {@link EditHistory} (what it replaces is kept, so the page
 * can undo it; a refused or failed edit puts back whatever it wrote), and
 * reopens the save so the UI gets exactly what opening it would give, with
 * the step's id as {@code historyStep}. Subclasses supply only
 * {@link #mutate}; the steps around it are {@link SaveMutation}'s.
 *
 * <p>These were seven copies of the same fifty lines, kept consistent only by
 * every copy remembering to do it.
 */
public abstract class SaveMutationHandler extends CefMessageRouterHandlerAdapter {
	private final Logger logger = Logger.getLogger(getClass());

	/**
	 * Makes the edit.
	 *
	 * @param save    the live working copy of the save
	 * @param request the whole request, for whatever this handler reads from it
	 * @return {@code null} on success, or what to tell the user when the edit
	 *         was refused
	 * @throws IOException when the save could not be read or written; the
	 *         message reaches the user. A {@link WriteRefusedException} -- a
	 *         save that could be read only in part, or an edit that would have
	 *         left it contradicting itself -- reaches them as it stands.
	 */
	protected abstract String mutate (File save, JSONObject request) throws IOException;

	/**
	 * Whether the edit is a step of the save's history, to be undone. Only
	 * undoing and redoing are not: they move along the history rather than
	 * adding to it.
	 */
	protected boolean recorded () {
		return true;
	}

	@Override
	public final boolean onQuery (
		final CefBrowser browser
		, final long id
		, final String request
		, final boolean persistent
		, final CefQueryCallback callback) {

		Environment.getInstance().mutationWorker().execute(Answered.to(callback, () -> handle(request, callback)));
		return true;
	}

	@Override
	public void onQueryCanceled (final CefBrowser browser, final long id) {
		logger.error("Query #%d cancelled.%n", id);
	}

	private void handle (final String request, final CefQueryCallback callback) {
		new SaveMutation(logger, recorded(), this::mutate).run(request, callback);
	}
}
