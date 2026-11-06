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


package uk.me.mantas.eternity.tests.handlers;

import org.apache.commons.io.FileUtils;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandler;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.junit.Test;
import uk.me.mantas.eternity.handlers.Answered;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Every question the page asks gets an answer.
 *
 * <p>A handler takes a query on and does its work on a worker thread, and the
 * page waits for the answer with a spinner up or a button disabled. Work that
 * ends in an exception nobody expected -- a save damaged into a shape a
 * manager never met, a bug -- used to end the thread and nothing else: no
 * answer, and a page waiting until the editor was closed. Measured on a save
 * with one byte changed, which opened to a spinner that never stopped.
 */
public class AnsweredTest extends TestHarness {
	@Test
	public void workThatFailsStillAnswers () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		Answered.to(callback, () -> {
			throw new IllegalStateException("a shape nobody expected");
		}).run();

		verify(callback).failure(eq(-1), contains("a shape nobody expected"));
		verify(callback, never()).success(anyString());
	}

	@Test
	public void workThatAnswersIsLeftToIt () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		Answered.to(callback, () -> callback.success("done")).run();

		verify(callback).success("done");
		verify(callback, never()).failure(anyInt(), anyString());
	}

	// Damage can nest objects without end. The stack running out is an Error,
	// which no handler catches, and it ended the thread like any other.
	@Test
	public void aStackThatRanOutStillAnswers () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		Answered.to(callback, AnsweredTest::forEver).run();

		verify(callback).failure(eq(-1), contains("StackOverflowError"));
	}

	private static int forEver () {
		return forEver() + 1;
	}

	@Test
	public void aQueryIsAnsweredOnce () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		final CefQueryCallback once = Answered.once(callback);

		Answered.to(once, () -> {
			once.success("done");
			throw new IllegalStateException("after the answer");
		}).run();

		once.success("again");
		once.failure(-1, "and again");

		verify(callback).success("done");
		verify(callback, never()).failure(anyInt(), anyString());
		verifyNoMoreInteractions(callback);
	}

	// What the page calls is the guarded handler: the query it hands on can
	// only be answered once, and a handler that fails before it has handed its
	// work to anyone answers too.
	@Test
	public void aHandlerThatFailsOutrightStillAnswers () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		final CefMessageRouterHandler failing = new CefMessageRouterHandlerAdapter() {
			@Override
			public boolean onQuery (
				final CefBrowser browser, final long id, final String request
				, final boolean persistent, final CefQueryCallback query) {

				throw new IllegalArgumentException("before any work began");
			}
		};

		assertTrue(Answered.guarded(failing).onQuery(mock(CefBrowser.class), 0, "{}", false, callback));
		verify(callback).failure(eq(-1), contains("before any work began"));
	}

	@Test
	public void aGuardedHandlersQueryIsAnsweredOnce () {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		final CefMessageRouterHandler twice = new CefMessageRouterHandlerAdapter() {
			@Override
			public boolean onQuery (
				final CefBrowser browser, final long id, final String request
				, final boolean persistent, final CefQueryCallback query) {

				query.success("first");
				query.failure(-1, "second");
				return true;
			}
		};

		assertTrue(Answered.guarded(twice).onQuery(mock(CefBrowser.class), 0, "{}", false, callback));
		verify(callback).success("first");
		verifyNoMoreInteractions(callback);
	}

	// Kept honest by reading the handlers themselves: work handed to a worker
	// goes through Answered.to, or the next handler written by copying a
	// neighbour is the next page left waiting.
	@Test
	public void everyHandlerHandsItsWorkOverGuarded () throws Exception {
		final File handlers = new File("src/main/java/uk/me/mantas/eternity/handlers");
		final File[] sources = handlers.listFiles((dir, name) -> name.endsWith(".java"));
		assertNotNull("run from the project's own folder", sources);
		assertTrue(sources.length > 30);

		final List<String> unguarded = new ArrayList<>();
		for (final File source : sources) {
			final String code = FileUtils.readFileToString(source, StandardCharsets.UTF_8);
			int at = -1;
			while ((at = code.indexOf(".execute(", at + 1)) >= 0) {
				final String handed = code.substring(at, Math.min(code.length(), at + 60))
					.replaceAll("\\s+", " ").replace(".execute( ", ".execute(");

				// Closing the window answers before it hands the closing over.
				final boolean answeredAlready = source.getName().equals("CloseWindow.java")
					&& handed.startsWith(".execute(new WindowCloser(");

				if (!answeredAlready && !handed.startsWith(".execute(Answered.to(")) {
					unguarded.add(source.getName() + ": " + handed);
				}
			}
		}

		assertTrue("work handed to a worker with nothing to answer for it: " + unguarded
			, unguarded.isEmpty());
	}
}
