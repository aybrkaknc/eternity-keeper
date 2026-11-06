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
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.handlers.OpenSavedGame;
import uk.me.mantas.eternity.handlers.SaveMutationHandler;
import uk.me.mantas.eternity.serializer.InconsistentWriteException;
import uk.me.mantas.eternity.serializer.ShortReadException;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * What every handler that edits the working save and hands it back has in
 * common.
 *
 * <p>Seven handlers — inventory, abilities, stronghold, grimoires, party,
 * resurrection, achievements — were each the same fifty lines: hop onto the
 * mutation worker, read {@code oldSave} and {@code savedYet}, work out which
 * copy of the save is the live one, check it exists, do the one line that
 * differs, reopen, and catch the same two exceptions. The one thing that
 * differed was the edit; everything else only stayed consistent because every
 * copy remembered to do it, including invariant 9 — every save mutation on
 * the single mutation worker, so two can never write the same file at once.
 */
public class SaveMutationHandlerTest extends TestHarness {
	private File workingSave () throws Exception {
		final File resources = new File(getClass().getResource("/").toURI());
		final File directory = new File(EKUtils.createTempDir(PREFIX).get(), "working.savegame");
		assertTrue(directory.mkdir());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), directory);
		return directory;
	}

	@After
	public void forgetThePreviousSave () {
		Environment.getInstance().state().workingSave().opening();
	}

	/** A handler whose edit is whatever the test says it is. */
	private static final class Probe extends SaveMutationHandler {
		final AtomicReference<File> sawSave = new AtomicReference<>();
		final AtomicReference<JSONObject> sawRequest = new AtomicReference<>();
		final AtomicReference<Thread> ranOn = new AtomicReference<>();
		String problem = null;
		IOException throwing = null;
		RuntimeException failing = null;
		String marker = null;
		boolean unreadable = false;

		@Override
		protected String mutate (final File save, final JSONObject request) throws IOException {
			ranOn.set(Thread.currentThread());
			sawSave.set(save);
			sawRequest.set(request);

			if (throwing != null) {
				throw throwing;
			}

			if (marker != null) {
				FileUtils.writeStringToFile(new File(save, marker), "edit", "UTF-8");
			}

			if (failing != null || unreadable) {
				// Part of the way through, as a bug would be: one file written,
				// the way every writer writes one (invariant 20) -- kept for
				// undo, then a new file moved over it.
				final File world = new File(save, "MobileObjects.save");
				final File writing = new File(save, "MobileObjects.save.writing");
				Environment.getInstance().state().workingSave().history().keep(world);
				FileUtils.writeStringToFile(writing, "half an edit", "UTF-8");
				Files.move(writing.toPath(), world.toPath(), StandardCopyOption.REPLACE_EXISTING);
				if (failing != null) {
					throw failing;
				}
			}

			return problem;
		}
	}

	private static JSONObject request (final File oldSave, final boolean savedYet) {
		final JSONObject request = new JSONObject();
		request.put("oldSave", oldSave.getAbsolutePath());
		request.put("savedYet", savedYet);
		request.put("anything", "else");
		return request;
	}

	private static void send (final SaveMutationHandler handler, final Object request
		, final CefQueryCallback callback) {

		handler.onQuery(mock(CefBrowser.class), 0, request.toString(), false, callback);
	}

	@Test
	public void theEditRunsOnTheMutationWorker () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);
		verify(callback, timeout(60000)).success(anyString());

		final AtomicReference<Thread> worker = new AtomicReference<>();
		Environment.getInstance().mutationWorker()
			.submit(() -> worker.set(Thread.currentThread()))
			.get(10, TimeUnit.SECONDS);

		assertSame("invariant 9: the edit must run on the mutation worker"
			, worker.get(), probe.ranOn.get());
	}

	/**
	 * Not the directory the list unpacked: an Apply the user goes on to discard
	 * must not be there the next time they open the same save.
	 */
	@Test
	public void anUnsavedSessionEditsAPrivateCopyOfTheSaveItOpened () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.marker = "edited.txt";
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);
		verify(callback, timeout(60000)).success(anyString());

		assertNotEquals(save.getAbsoluteFile(), probe.sawSave.get().getAbsoluteFile());
		assertEquals("saving names the new file after it", save.getName(), probe.sawSave.get().getName());
		assertEquals("else", probe.sawRequest.get().getString("anything"));
		assertTrue(new File(probe.sawSave.get(), "edited.txt").isFile());
		assertFalse("the opened save is untouched", new File(save, "edited.txt").exists());
	}

	@Test
	public void openingASaveFromTheListDropsEditsThatWereNeverSaved () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);
		verify(callback, timeout(60000)).success(anyString());
		final File edited = probe.sawSave.get();

		new OpenSavedGame().onQuery(mock(CefBrowser.class), 0, save.getAbsolutePath(), false
			, mock(CefQueryCallback.class));

		Environment.getInstance().mutationWorker().submit(() -> {}).get(60, TimeUnit.SECONDS);
		assertFalse("the private copy is gone", edited.exists());
		assertEquals(save, Environment.getInstance().state().workingSave().forReading(save, false));
	}

	/**
	 * After the first Save the working copy is the one that was written, not
	 * the one the list opened.
	 */
	@Test
	public void aSavedSessionEditsTheCopyItLastWrote () throws Exception {
		final File opened = workingSave();
		final File written = workingSave();
		Environment.getInstance().state().workingSave().written(written);

		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(opened, true), callback);
		verify(callback, timeout(60000)).success(anyString());

		assertEquals(written.getAbsoluteFile(), probe.sawSave.get().getAbsoluteFile());
	}

	@Test
	public void claimingASaveThatWasNeverWrittenFallsBackToTheOpenedOne () throws Exception {
		final File opened = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(opened, true), callback);
		verify(callback, timeout(60000)).success(anyString());

		assertEquals(opened.getName(), probe.sawSave.get().getName());
		assertEquals(Environment.getInstance().state().workingSave().forReading(opened, false)
			, probe.sawSave.get());
	}

	@Test
	public void aSaveThatIsNotThereIsNeverEdited () throws Exception {
		final File missing = new File(EKUtils.createTempDir(PREFIX).get(), "gone.savegame");
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(missing, false), callback);

		verify(callback, timeout(60000)).failure(anyInt(), eq("Unable to find your save file."));
		assertNull(probe.ranOn.get());
		verify(callback, never()).success(anyString());
	}

	@Test
	public void aMalformedRequestIsAnErrorNotAnException () {
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, "{ not json", callback);

		verify(callback, timeout(60000)).failure(anyInt(), eq("Error parsing JSON request."));
		assertNull(probe.ranOn.get());
	}

	@Test
	public void aRefusedEditSaysWhyAndDoesNotReopen () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.problem = "The stronghold is not yours yet.";
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).failure(anyInt(), eq("The stronghold is not yours yet."));
		verify(callback, never()).success(anyString());
	}

	@Test
	public void aFailedWriteIsReportedWithWhatWentWrong () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.throwing = new IOException("MobileObjects.save is locked");
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).failure(anyInt()
			, argThat(message -> message.contains("MobileObjects.save is locked")));
		verify(callback, never()).success(anyString());
	}

	// A manager that meets a save in a shape it never expected fails with
	// something nobody catches by name. The page used to be left waiting --
	// the thread simply ended -- and now it is told, and whatever the edit had
	// written by then is put back.
	@Test
	public void anEditThatFailsUnexpectedlyStillAnswersAndIsTakenBack () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.failing = new IllegalStateException("an inventory with no owner");
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).failure(anyInt()
			, argThat(message -> message.contains("an inventory with no owner")
				&& message.contains("was not done")));
		verify(callback, never()).success(anyString());

		final File edited = probe.sawSave.get();
		assertArrayEquals("what it had written is put back"
			, FileUtils.readFileToByteArray(new File(save, "MobileObjects.save"))
			, FileUtils.readFileToByteArray(new File(edited, "MobileObjects.save")));
		assertTrue("and nothing is left to undo"
			, Environment.getInstance().state().workingSave().history().undoable().isEmpty());
	}

	/**
	 * A save that could only be read in part was never written, so "Could
	 * not write the save" would blame the wrong step. The exception's own
	 * words already say what happened and why, and they are the whole message.
	 */
	@Test
	public void aShortReadIsReportedInItsOwnWords () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.throwing = new ShortReadException("MobileObjects.save", 23, 22);
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		final ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).failure(anyInt(), message.capture());
		assertEquals("Only 22 of the 23 objects in MobileObjects.save could be read, so nothing "
			+ "was written: the rest would have been lost.", message.getValue());
		verify(callback, never()).success(anyString());
	}

	/**
	 * An edit the writer refused, because it would have left the save
	 * contradicting itself, was not written either -- said the same way, in
	 * the refusal's own words.
	 */
	@Test
	public void aContradictionIsReportedInItsOwnWords () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		final InconsistentWriteException refusal = new InconsistentWriteException("MobileObjects.save"
			, java.util.Collections.singletonList("Player_Elwyn's PlayerInventory would hold 1 item but 2 item IDs"));
		probe.throwing = refusal;
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		final ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(callback, timeout(60000)).failure(anyInt(), message.capture());
		assertEquals(refusal.getMessage(), message.getValue());
		assertTrue(message.getValue(), message.getValue().contains("nothing was written"));
		verify(callback, never()).success(anyString());
	}

	// Written, and then nothing of it could be read back. The opener used to
	// answer that as a success carrying {"error": ...}, which the page adopted
	// as if it were the save, and the edit stayed in the working save (found
	// by review, 2026-10-27).
	@Test
	public void anEditThatCannotBeReadBackIsAFailureAndIsTakenBack () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		probe.unreadable = true;
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).failure(anyInt()
			, argThat(message -> message.contains("could not be read back")));
		verify(callback, never()).success(anyString());

		final File edited = probe.sawSave.get();
		assertArrayEquals("what it had written is put back"
			, FileUtils.readFileToByteArray(new File(save, "MobileObjects.save"))
			, FileUtils.readFileToByteArray(new File(edited, "MobileObjects.save")));
		assertTrue("and nothing is left to undo"
			, Environment.getInstance().state().workingSave().history().undoable().isEmpty());
	}

	@Test
	public void aSuccessfulEditHandsBackTheReopenedSave () throws Exception {
		final File save = workingSave();
		final Probe probe = new Probe();
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		send(probe, request(save, false), callback);

		verify(callback, timeout(60000)).success(argThat(response ->
			new JSONObject(response).has("characters")));
	}
}
