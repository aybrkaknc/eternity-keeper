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

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.callback.CefRunFileDialogCallback;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.handlers.SaveTarget;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.Optional;
import java.util.Vector;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Choosing where a save goes.
 *
 * <p>This used to open a Swing {@code JFileChooser} on the event dispatch
 * thread from inside a CEF query. A modal Swing dialog with no owner disables
 * every frame in the process while it is up — measured on the running editor,
 * the main window reported {@code enabled=False} — and it can open behind
 * whatever has focus, which leaves a window that ignores every click and shows
 * no dialog to explain why. That is the freeze a user reported on the Save
 * dialog's folder button, and a Swing modal loop running inside a CEF-hosted
 * window is not something to leave in place hoping it only hangs.
 *
 * <p>The replacement is CEF's own native Save dialog — the one Export
 * Character has always used, owned by the browser window rather than by a
 * Swing loop. The bundled JCEF has no folder mode, so the dialog is prefilled
 * with the file name the game will use and only its folder is kept.
 */
public class SaveTargetTest extends TestHarness {
	private static final String GAME_NAME = "0945952c89c640e4a18cdb293e3946b4 6 CaedNua.savegame";

	private Settings settingsAt (final File saves) {
		final Settings settings = mockSettings();
		settings.json = new JSONObject();
		settings.json.put("savesLocation", saves.getAbsolutePath());
		return settings;
	}

	// ---- turning what the dialog returned into a folder ---------------------

	@Test
	public void theFolderIsWhereverTheChosenFileWouldGo () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final Vector<String> chosen = new Vector<>();
		chosen.add(new File(saves, GAME_NAME).getAbsolutePath());

		final Optional<File> folder = SaveTarget.folderFromDialog(chosen);

		assertTrue(folder.isPresent());
		assertEquals(saves.getCanonicalFile(), folder.get().getCanonicalFile());
	}

	/**
	 * The game names the file itself ({@code <session> <game> <SceneTitle>}),
	 * and a save by any other name does not show up on its load screen, so a
	 * name typed into the dialog is deliberately not kept — only where it
	 * pointed.
	 */
	@Test
	public void aRenamedFileStillOnlyChoosesTheFolder () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final Vector<String> chosen = new Vector<>();
		chosen.add(new File(saves, "my better name.savegame").getAbsolutePath());

		assertEquals(saves.getCanonicalFile(),
			SaveTarget.folderFromDialog(chosen).get().getCanonicalFile());
	}

	@Test
	public void choosingAFolderItselfIsAcceptedAsTheFolder () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final Vector<String> chosen = new Vector<>();
		chosen.add(saves.getAbsolutePath());

		assertEquals(saves.getCanonicalFile(),
			SaveTarget.folderFromDialog(chosen).get().getCanonicalFile());
	}

	@Test
	public void cancellingChoosesNothing () {
		assertFalse(SaveTarget.folderFromDialog(new Vector<>()).isPresent());
		assertFalse(SaveTarget.folderFromDialog(null).isPresent());

		final Vector<String> blank = new Vector<>();
		blank.add("");
		assertFalse(SaveTarget.folderFromDialog(blank).isPresent());
	}

	@Test
	public void aFolderThatDoesNotExistIsRefused () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final Vector<String> chosen = new Vector<>();
		chosen.add(new File(new File(saves, "nowhere"), GAME_NAME).getAbsolutePath());

		assertFalse(SaveTarget.folderFromDialog(chosen).isPresent());
	}

	@Test
	public void theDialogOpensOnTheGamesOwnFileName () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();

		assertEquals(new File(saves, GAME_NAME).getAbsolutePath(),
			SaveTarget.dialogStartingPath(saves.getAbsolutePath(), GAME_NAME));

		// No folder yet: the name alone, and the dialog picks its own start.
		assertEquals(GAME_NAME, SaveTarget.dialogStartingPath("", GAME_NAME));
	}

	// ---- the handler -------------------------------------------------------

	@Test
	public void choosingUsesTheBrowsersOwnDialogAndStoresTheFolder () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final File elsewhere = EKUtils.createTempDir(PREFIX).get();
		final Settings settings = settingsAt(saves);

		final CefBrowser browser = mock(CefBrowser.class);
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		final JSONObject request = new JSONObject();
		request.put("action", "choose");
		request.put("fileName", GAME_NAME);

		new SaveTarget().onQuery(browser, 0, request.toString(), false, callback);

		// The native CEF dialog, in save mode, starting on the game's file
		// name in the current saves folder -- and never a Swing dialog.
		final ArgumentCaptor<CefRunFileDialogCallback> dialog =
			ArgumentCaptor.forClass(CefRunFileDialogCallback.class);

		verify(browser, timeout(10000)).runFileDialog(
			eq(FileDialogMode.FILE_DIALOG_SAVE)
			, anyString()
			, eq(new File(saves, GAME_NAME).getAbsolutePath())
			, any()
			, anyInt()
			, dialog.capture());

		final Vector<String> chosen = new Vector<>();
		chosen.add(new File(elsewhere, GAME_NAME).getAbsolutePath());
		dialog.getValue().onFileDialogDismissed(0, chosen);

		verify(callback, timeout(10000)).success(argThat(response ->
			new JSONObject(response).getString("directory")
				.equals(elsewhere.getAbsolutePath())));

		assertEquals(elsewhere.getAbsolutePath(), settings.json.getString("savesLocation"));
		verify(settings).save();
	}

	@Test
	public void cancellingTheDialogLeavesTheFolderAlone () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		final Settings settings = settingsAt(saves);

		final CefBrowser browser = mock(CefBrowser.class);
		final CefQueryCallback callback = mock(CefQueryCallback.class);

		final JSONObject request = new JSONObject();
		request.put("action", "choose");
		request.put("fileName", GAME_NAME);

		new SaveTarget().onQuery(browser, 0, request.toString(), false, callback);

		final ArgumentCaptor<CefRunFileDialogCallback> dialog =
			ArgumentCaptor.forClass(CefRunFileDialogCallback.class);
		verify(browser, timeout(10000)).runFileDialog(
			any(), anyString(), anyString(), any(), anyInt(), dialog.capture());

		dialog.getValue().onFileDialogDismissed(0, new Vector<>());

		verify(callback, timeout(10000)).failure(anyInt(), eq("NO_FOLDER"));
		verify(callback, never()).success(anyString());
		assertEquals(saves.getAbsolutePath(), settings.json.getString("savesLocation"));
		verify(settings, never()).save();
	}

	@Test
	public void previewNamesTheFileAndTheFolder () throws Exception {
		final File saves = EKUtils.createTempDir(PREFIX).get();
		settingsAt(saves);

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		final JSONObject request = new JSONObject();
		request.put("action", "preview");
		request.put("oldSave", "");

		new SaveTarget().onQuery(mock(CefBrowser.class), 0, request.toString(), false, callback);

		verify(callback, timeout(10000)).success(argThat(response -> {
			final JSONObject json = new JSONObject(response);
			return json.getString("directory").equals(saves.getAbsolutePath())
				&& json.has("fileName");
		}));
	}
}
