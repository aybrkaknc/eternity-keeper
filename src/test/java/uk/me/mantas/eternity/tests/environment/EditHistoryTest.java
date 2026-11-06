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


package uk.me.mantas.eternity.tests.environment;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.EditHistory;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.InventoryManager.Change;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Undoing what an Apply wrote.
 *
 * <p>Every edit a manager makes reaches the working save through
 * {@code DeserializedPackets.replace}, which writes a sibling file and moves it
 * over the old one. While a step is open the history keeps the file being
 * replaced, so undoing the step is putting those files back -- and redoing it,
 * putting the step's own versions back again.
 */
public class EditHistoryTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String MAIL = "5ea3dc07-93bc-46a2-ba3c-d7c14660a127";
	private static final String SWORD = "5b1a11ca-ddcd-452f-84b3-bc651af00fad";

	private static File save () throws Exception {
		final File save = new File(EKUtils.createTempDir(PREFIX).get(), "target.savegame");
		assertTrue(save.mkdir());
		FileUtils.copyFileToDirectory(new File(new File(EditHistoryTest.class.getResource("/").toURI())
			, "InventoryManagerTest/MobileObjects.save"), save);

		return save;
	}

	private static byte[] bytes (final File save) throws IOException {
		return Files.readAllBytes(new File(save, "MobileObjects.save").toPath());
	}

	private static String text (final File file) throws IOException {
		return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
	}

	// What every writer does: a sibling first, then a move over the original.
	private static void rewrite (final EditHistory history, final File file, final String contents)
		throws IOException {

		final File sibling = new File(file.getParentFile(), file.getName() + ".writing");
		FileUtils.writeStringToFile(sibling, contents, StandardCharsets.UTF_8);
		history.keep(file);
		Files.move(sibling.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	private static EditHistory history () {
		return Environment.getInstance().state().workingSave().history();
	}

	private static void unequipMail (final File save) throws IOException {
		assertTrue(new InventoryManager(save).apply(Collections.singletonList(
			Change.unequip(ELWYN, 2, MAIL, ELWYN, "StashInventory", -1))));
	}

	@Test
	public void anAppliedEditIsUndoneAndRedone () throws Exception {
		final File save = save();
		final byte[] before = bytes(save);
		final EditHistory history = history();

		history.begin(save);
		unequipMail(save);
		final long step = history.commit();
		final byte[] after = bytes(save);

		assertTrue(step > 0);
		assertFalse("the manager changed the file", Arrays.equals(before, after));

		assertTrue(history.undo(save, step));
		assertArrayEquals("undone, byte for byte", before, bytes(save));

		assertTrue(history.redo(save, step));
		assertArrayEquals("redone, byte for byte", after, bytes(save));
	}

	@Test
	public void onlyTheFilesAStepReplacedAreTouched () throws Exception {
		final File save = save();
		final File area = new File(save, "AR_0001.lvl");
		final File other = new File(save, "AR_0002.lvl");
		FileUtils.writeStringToFile(area, "area", StandardCharsets.UTF_8);
		FileUtils.writeStringToFile(other, "other", StandardCharsets.UTF_8);
		final EditHistory history = history();

		history.begin(save);
		rewrite(history, area, "area, edited");
		final long step = history.commit();
		FileUtils.writeStringToFile(other, "other, changed since", StandardCharsets.UTF_8);

		assertTrue(history.undo(save, step));
		assertEquals("area", text(area));
		assertEquals("a file the step never wrote is left as it is", "other, changed since", text(other));
	}

	@Test
	public void aStepThatWritesSeveralFilesTakesThemAllBack () throws Exception {
		final File save = save();
		final File first = new File(save, "AR_0001.lvl");
		final File second = new File(save, "AR_0002.lvl");
		FileUtils.writeStringToFile(first, "first", StandardCharsets.UTF_8);
		FileUtils.writeStringToFile(second, "second", StandardCharsets.UTF_8);
		final EditHistory history = history();

		history.begin(save);
		rewrite(history, first, "first, edited");
		rewrite(history, second, "second, edited");
		rewrite(history, second, "second, edited twice");
		final long step = history.commit();

		assertTrue(history.undo(save, step));
		assertEquals("first", text(first));
		assertEquals("the version from before the step, not one in the middle of it", "second", text(second));

		assertTrue(history.redo(save, step));
		assertEquals("first, edited", text(first));
		assertEquals("second, edited twice", text(second));
	}

	@Test
	public void aStepThatChangedNothingIsNoStep () throws Exception {
		final File save = save();
		final EditHistory history = history();

		history.begin(save);
		assertEquals(0, history.commit());
		assertTrue(history.undoable().isEmpty());
	}

	@Test
	public void aRefusedEditLeavesTheSaveAndTheHistoryAsTheyWere () throws Exception {
		final File save = save();
		final byte[] before = bytes(save);
		final EditHistory history = history();

		history.begin(save);
		unequipMail(save);
		final long first = history.commit();

		// Half a step: one file written, then the edit fails.
		final byte[] middle = bytes(save);
		history.begin(save);
		assertTrue(new InventoryManager(save).apply(Collections.singletonList(
			Change.unequipWeapon(ELWYN, 0, SWORD, ELWYN, "StashInventory", -1))));
		history.abort();

		assertArrayEquals("what the failed edit wrote is taken back", middle, bytes(save));
		assertEquals(Collections.singletonList(first), history.undoable());
		assertTrue(history.undo(save, first));
		assertArrayEquals(before, bytes(save));
	}

	@Test
	public void onlyTheLastStepCanBeUndoneAndOnlyTheLastUndoneRedone () throws Exception {
		final File save = save();
		final File area = new File(save, "AR_0001.lvl");
		FileUtils.writeStringToFile(area, "0", StandardCharsets.UTF_8);
		final EditHistory history = history();

		history.begin(save);
		rewrite(history, area, "1");
		final long one = history.commit();
		history.begin(save);
		rewrite(history, area, "2");
		final long two = history.commit();

		assertFalse("not the last step", history.undo(save, one));
		assertFalse("nothing undone yet", history.redo(save, two));
		assertEquals("2", text(area));

		assertTrue(history.undo(save, two));
		assertTrue(history.undo(save, one));
		assertEquals("0", text(area));
		assertFalse("not the last one undone", history.redo(save, two));

		assertTrue(history.redo(save, one));
		assertEquals("1", text(area));
	}

	@Test
	public void aNewStepEndsWhatCouldBeRedone () throws Exception {
		final File save = save();
		final File area = new File(save, "AR_0001.lvl");
		FileUtils.writeStringToFile(area, "0", StandardCharsets.UTF_8);
		final EditHistory history = history();

		history.begin(save);
		rewrite(history, area, "1");
		final long one = history.commit();
		assertTrue(history.undo(save, one));

		history.begin(save);
		rewrite(history, area, "something else");
		history.commit();

		assertFalse(history.redo(save, one));
		assertTrue(history.redoable().isEmpty());
		assertEquals("something else", text(area));
	}

	@Test
	public void stepsBelongToTheDirectoryTheyChanged () throws Exception {
		final File save = save();
		final File elsewhere = save();
		final EditHistory history = history();

		history.begin(save);
		unequipMail(save);
		final long step = history.commit();

		assertFalse(history.undo(elsewhere, step));

		// Editing another directory starts over: nothing before it can be
		// put back where it came from any more.
		history.begin(elsewhere);
		unequipMail(elsewhere);
		history.commit();
		assertFalse(history.undo(save, step));
		assertEquals(1, history.undoable().size());
	}

	@Test
	public void clearingForgetsEveryStepAndTheFilesItKept () throws Exception {
		final File save = save();
		final EditHistory history = history();

		history.begin(save);
		unequipMail(save);
		final long step = history.commit();
		final File kept = history.folderOf(step);
		assertTrue(kept.isDirectory());

		history.clear();
		assertFalse(kept.exists());
		assertFalse(history.undo(save, step));
		assertTrue(history.undoable().isEmpty());
	}

	@Test
	public void openingAnotherSaveClearsTheHistory () throws Exception {
		final File save = save();
		final EditHistory history = history();

		history.begin(save);
		unequipMail(save);
		final long step = history.commit();

		Environment.getInstance().state().workingSave().opening();
		assertFalse(history.undo(save, step));
	}

	@Test
	public void filesAreCopiedWhereTheyCannotBeLinked () throws Exception {
		final File save = save();
		final byte[] before = bytes(save);
		final EditHistory history = new EditHistory(false);

		history.begin(save);
		final File world = new File(save, "MobileObjects.save");
		rewrite(history, world, "edited");
		final long step = history.commit();

		assertTrue(history.undo(save, step));
		assertArrayEquals(before, bytes(save));
		assertTrue(history.redo(save, step));
		assertEquals("edited", text(world));
		history.clear();
	}

	@Test
	public void writesOutsideAStepAreNotKept () throws Exception {
		final File save = save();
		final EditHistory history = history();

		unequipMail(save);
		assertTrue(history.undoable().isEmpty());

		history.begin(save);
		final File unrelated = new File(EKUtils.createTempDir(PREFIX).get(), "notes.lvl");
		FileUtils.writeStringToFile(unrelated, "notes", StandardCharsets.UTF_8);
		rewrite(history, unrelated, "notes, edited");
		assertEquals("a file outside the save is none of the step's business", 0, history.commit());
	}
}
