/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.junit.Test;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.ChrDialog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.util.Vector;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Choosing a character file, which importing and exporting both start with.
 * Each used to spell it out as two inner classes of its own.
 */
public class ChrDialogTest extends TestHarness {
	/** A browser whose dialog comes back with {@code chosen} straight away. */
	@SuppressWarnings("unchecked")
	private static CefBrowser answering (final String... chosen) {
		final CefBrowser browser = mock(CefBrowser.class);
		doAnswer(invocation -> {
			final Vector<String> files = new Vector<>();
			for (final String file : chosen) {
				files.add(file);
			}

			((CefRunFileDialogCallback) invocation.getArgument(5)).onFileDialogDismissed(0, files);
			return null;
		}).when(browser).runFileDialog(any(), anyString(), anyString(), any(Vector.class), anyInt(), any());

		return browser;
	}

	private static void settle () throws Exception {
		Environment.getInstance().workers().submit(() -> {}).get(30, TimeUnit.SECONDS);
		Environment.getInstance().mutationWorker().submit(() -> {}).get(30, TimeUnit.SECONDS);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void itAsksForACharacterFileInTheModeGiven () throws Exception {
		final CefBrowser browser = answering();
		ChrDialog.choose(browser, FileDialogMode.FILE_DIALOG_SAVE, "Save Character"
			, mock(CefQueryCallback.class), "NO_SAVENAME", chosen -> {});

		verify(browser, timeout(30000)).runFileDialog(eq(FileDialogMode.FILE_DIALOG_SAVE)
			, eq("Save Character"), anyString()
			, argThat((Vector<String> filters) -> filters.size() == 1 && ".chr".equals(filters.get(0)))
			, anyInt(), any());
	}

	@Test
	public void cancellingSaysSoAndDoesNothingElse () throws Exception {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		final AtomicReference<String> ran = new AtomicReference<>();

		ChrDialog.choose(answering(), FileDialogMode.FILE_DIALOG_OPEN, "Choose a character"
			, callback, "NO_SAVE", ran::set);

		verify(callback, timeout(30000)).failure(anyInt(), eq("NO_SAVE"));
		settle();
		assertNull(ran.get());
	}

	@Test
	public void anEmptyNameIsACancelToo () throws Exception {
		final CefQueryCallback callback = mock(CefQueryCallback.class);
		ChrDialog.choose(answering(""), FileDialogMode.FILE_DIALOG_SAVE, "Save Character"
			, callback, "NO_SAVENAME", chosen -> fail("nothing was chosen"));

		verify(callback, timeout(30000)).failure(anyInt(), eq("NO_SAVENAME"));
	}

	/** Invariant 9: a character file is read or written against the save on its queue. */
	@Test
	public void theChoiceIsActedOnOnTheMutationWorker () throws Exception {
		final AtomicReference<String> chosen = new AtomicReference<>();
		final AtomicReference<Thread> ranOn = new AtomicReference<>();
		final CountDownLatch acted = new CountDownLatch(1);

		ChrDialog.choose(answering("C:/party/Eder.chr"), FileDialogMode.FILE_DIALOG_OPEN
			, "Choose a character", mock(CefQueryCallback.class), "NO_SAVE", path -> {
				chosen.set(path);
				ranOn.set(Thread.currentThread());
				acted.countDown();
			});

		// Waited for by name. Letting both queues run dry is not the same
		// thing: the workers are a pool, so an empty task can finish on one
		// thread before the dialog has answered on another, and this test then
		// looked before anything had been chosen.
		assertTrue("the choice was acted on", acted.await(30, TimeUnit.SECONDS));
		final AtomicReference<Thread> worker = new AtomicReference<>();
		Environment.getInstance().mutationWorker()
			.submit(() -> worker.set(Thread.currentThread())).get(30, TimeUnit.SECONDS);

		assertEquals("C:/party/Eder.chr", chosen.get());
		assertSame(worker.get(), ranOn.get());
	}

	@Test
	public void anExportedFileAlwaysEndsInChr () {
		assertEquals("Eder.chr", ChrDialog.withChrExtension("Eder"));
		assertEquals("Eder.chr", ChrDialog.withChrExtension("Eder.chr"));
		assertEquals("Eder.v2.chr", ChrDialog.withChrExtension("Eder.v2"));
		assertEquals("Eder..chr", ChrDialog.withChrExtension("Eder."));
	}
}
