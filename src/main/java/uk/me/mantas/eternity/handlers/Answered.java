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
import org.cef.handler.CefMessageRouterHandler;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import uk.me.mantas.eternity.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every question the page asks gets an answer.
 *
 * <p>A handler does its work on a worker thread while the page waits with a
 * spinner up or a button disabled. Work that ended in an exception nobody
 * expected -- a save damaged into a shape a manager never met, a bug -- used
 * to end the thread and nothing else, and the page waited until the editor was
 * closed. Each handler caught what it knew could go wrong, which is exactly
 * not this.
 *
 * <p>So work is handed to a worker as {@link #to}, which answers for it when
 * it ends without having answered, and the page calls every handler through
 * {@link #guarded}, whose query can be answered only once.
 * {@code AnsweredTest} reads the handlers' sources to see that each does.
 */
public final class Answered {
	private static final Logger logger = Logger.getLogger(Answered.class);

	private Answered () {}

	/**
	 * {@code work} as a task for a worker: should it end in something nobody
	 * caught, the page is told so instead of being left waiting.
	 */
	public static Runnable to (final CefQueryCallback callback, final Runnable work) {
		return () -> {
			// A stack that ran out counts: damage can nest objects without end.
			try {
				work.run();
			} catch (final RuntimeException | StackOverflowError e) {
				logger.error(e, "A query's work ended in %s%n", e);
				callback.failure(-1, unexpected(e));
			}
		};
	}

	/** What the page is told about a failure nothing was written to explain. */
	public static String unexpected (final Throwable e) {
		return "The editor ran into something it did not expect (" + e
			+ "), so this was not done. eternity.log has the details.";
	}

	/** {@code callback}, answered by whichever answer comes first and by no other. */
	public static CefQueryCallback once (final CefQueryCallback callback) {
		final AtomicBoolean answered = new AtomicBoolean(false);

		return new CefQueryCallback() {
			@Override
			public void success (final String response) {
				if (answered.compareAndSet(false, true)) {
					callback.success(response);
				}
			}

			@Override
			public void failure (final int code, final String message) {
				if (answered.compareAndSet(false, true)) {
					callback.failure(code, message);
				}
			}
		};
	}

	/**
	 * {@code handler} as the page calls it: its query is answered once, and
	 * a handler that fails before it has handed its work to anyone answers.
	 */
	public static CefMessageRouterHandler guarded (final CefMessageRouterHandler handler) {
		return new CefMessageRouterHandlerAdapter() {
			@Override
			public boolean onQuery (
				final CefBrowser browser
				, final long id
				, final String request
				, final boolean persistent
				, final CefQueryCallback callback) {

				final CefQueryCallback once = once(callback);
				try {
					return handler.onQuery(browser, id, request, persistent, once);
				} catch (final RuntimeException | StackOverflowError e) {
					logger.error(e, "%s ended in %s%n", handler.getClass().getSimpleName(), e);
					once.failure(-1, unexpected(e));
					return true;
				}
			}

			@Override
			public void onQueryCanceled (final CefBrowser browser, final long id) {
				handler.onQueryCanceled(browser, id);
			}
		};
	}
}
