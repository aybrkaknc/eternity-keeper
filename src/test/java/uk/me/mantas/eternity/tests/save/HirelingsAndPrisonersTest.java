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


package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.game.StrongholdHireling;
import uk.me.mantas.eternity.game.StrongholdPrisonerData;
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.save.StrongholdManager.Change;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.EKUtils.findComponent;

// Dismissing a hireling and releasing a prisoner, as Stronghold.DismissHireling
// and Stronghold.RemovePrisoner do them.
//
// The fixture is the prologue save with a real keep spliced in from a
// mid-game one: 24 upgrades, four hirelings (Crucible Knight +4/+2, Goldpact
// Knight 0/+2, Skirmish Archer +1/+1, Warden of the Wilds 0/+2) and Kestorik
// the vithrack in the dungeon, with every one of their globals at 1. The
// archer is unpaid -- what ProcessPayCycle leaves when the keep cannot pay:
// Paid false and the archer's own +1/+1 already taken back off -- so Prestige
// and Security read 38 and 34.
public class HirelingsAndPrisonersTest extends TestHarness {
	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(
			new File(resources, "StrongholdManagerTest/MobileObjects.save"), saveDir);

		return saveDir;
	}

	private static Optional<ComponentPersistencePacket> component (
		final File saveDir, final String name) throws IOException {

		final Optional<DeserializedPackets> packets =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();

		assertTrue(packets.isPresent());

		for (final Property property : packets.get().getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final Optional<ComponentPersistencePacket> found = findComponent(
				((ObjectPersistencePacket) property.obj).ComponentPackets, name);

			if (found.isPresent()) {
				return found;
			}
		}

		return Optional.empty();
	}

	private static ComponentPersistencePacket stronghold (final File saveDir)
		throws IOException {

		return component(saveDir, "Stronghold")
			.orElseThrow(() -> new AssertionError("no Stronghold in the fixture"));
	}

	@SuppressWarnings("unchecked")
	private static Hashtable<String, Integer> globals (final File saveDir)
		throws IOException {

		return (Hashtable<String, Integer>) component(saveDir, "GlobalVariables")
			.orElseThrow(() -> new AssertionError("no GlobalVariables in the fixture"))
			.Variables.get("m_data");
	}

	private static List<Object> list (
		final ComponentPersistencePacket stronghold, final String variable) {

		final Object collection = stronghold.Variables.get(variable);
		assertTrue(collection instanceof CSharpCollection);

		final List<Object> items = new ArrayList<>();
		for (final Iterator it = ((CSharpCollection) collection).iterator(); it.hasNext();) {
			items.add(it.next());
		}

		return items;
	}

	private static List<String> hired (final ComponentPersistencePacket stronghold) {
		final List<String> names = new ArrayList<>();
		for (final Object item : list(stronghold, "m_hirelingsHired")) {
			names.add(((StrongholdHireling) item).HiredGlobalVariableName);
		}

		return names;
	}

	private static List<String> imprisoned (final ComponentPersistencePacket stronghold) {
		final List<String> names = new ArrayList<>();
		for (final Object item : list(stronghold, "m_prisoners")) {
			names.add(((StrongholdPrisonerData) item).GlobalVariableName);
		}

		return names;
	}

	@Test
	public void theFixtureIsTheKeepItClaimsToBe () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		final ComponentPersistencePacket keep = stronghold(saveDir);

		assertEquals(Arrays.asList(
			"b_crucible_hireling", "b_goldpact_hireling"
			, "b_archer_hireling", "b_warden_wilds_hireling"), hired(keep));

		assertEquals(Collections.singletonList("b_kestorik_prisoner"), imprisoned(keep));
		assertEquals(38, keep.Variables.get("Prestige"));
		assertEquals(34, keep.Variables.get("Security"));
		assertEquals(Integer.valueOf(1), globals(saveDir).get("b_crucible_hireling"));
		assertEquals(Integer.valueOf(1), globals(saveDir).get("b_kestorik_prisoner"));
	}

	@Test
	public void dismissingAHirelingTakesBackWhatTheyAddedAndClearsTheirGlobal ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Collections.singletonList(
			Change.dismissHireling("b_crucible_hireling"))));

		final ComponentPersistencePacket keep = stronghold(saveDir);
		assertEquals(Arrays.asList(
			"b_goldpact_hireling", "b_archer_hireling", "b_warden_wilds_hireling")
			, hired(keep));

		// A paid Crucible Knight was worth +4 Prestige and +2 Security, and
		// DismissHireling takes exactly that back off.
		assertEquals(34, keep.Variables.get("Prestige"));
		assertEquals(32, keep.Variables.get("Security"));

		// "This global is set to 1 when the hireling is hired and 0 when he is
		// fired." Everyone else's stays as it was.
		final Hashtable<String, Integer> globals = globals(saveDir);
		assertEquals(Integer.valueOf(0), globals.get("b_crucible_hireling"));
		assertEquals(Integer.valueOf(1), globals.get("b_goldpact_hireling"));
	}

	@Test
	public void theHirelingsWhoStayKeepEverythingTheGameStoredForThem ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Collections.singletonList(
			Change.dismissHireling("b_goldpact_hireling"))));

		// Restored() matches each entry back to the configured hireling by its
		// global or its name id, and takes Paid and IsLeaving from the save, so
		// none of that may be disturbed for the ones who stay.
		final List<Object> stayed = list(stronghold(saveDir), "m_hirelingsHired");
		assertEquals(3, stayed.size());

		final StrongholdHireling knight = (StrongholdHireling) stayed.get(0);
		assertEquals("b_crucible_hireling", knight.HiredGlobalVariableName);
		assertEquals(329, knight.SerializedNameId);
		assertEquals(20, knight.CostPerDay);
		assertTrue(knight.Paid);

		final StrongholdHireling archer = (StrongholdHireling) stayed.get(1);
		assertEquals("b_archer_hireling", archer.HiredGlobalVariableName);
		assertFalse(archer.Paid);
	}

	@Test
	public void dismissingAnUnpaidHirelingTakesNothingMoreOff ()
		throws URISyntaxException, IOException {

		// The pay cycle already took the archer's +1/+1 back when the keep
		// could not pay, and DismissHireling only subtracts for a hireling
		// that is Paid. Taking it off again would count it twice.
		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Collections.singletonList(
			Change.dismissHireling("b_archer_hireling"))));

		final ComponentPersistencePacket keep = stronghold(saveDir);
		assertFalse(hired(keep).contains("b_archer_hireling"));
		assertEquals(38, keep.Variables.get("Prestige"));
		assertEquals(34, keep.Variables.get("Security"));
		assertEquals(Integer.valueOf(0), globals(saveDir).get("b_archer_hireling"));
	}

	@Test
	public void dismissingSomeoneWhoWasNeverHiredIsRefused ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		final byte[] before =
			FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save"));

		try {
			new StrongholdManager(saveDir).apply(Collections.singletonList(
				Change.dismissHireling("b_brute_hireling")));
			fail("someone who was never hired was dismissed");
		} catch (final WriteRefusedException refused) {
			assertTrue(refused.getMessage(), refused.getMessage().contains("b_brute_hireling"));
		}

		assertArrayEquals(before
			, FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save")));
	}

	@Test
	public void releasingAPrisonerEmptiesTheCellAndClearsTheirGlobal ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Collections.singletonList(
			Change.releasePrisoner("b_kestorik_prisoner"))));

		final ComponentPersistencePacket keep = stronghold(saveDir);
		assertEquals(Collections.emptyList(), imprisoned(keep));
		assertEquals(Integer.valueOf(0), globals(saveDir).get("b_kestorik_prisoner"));

		// A prisoner is worth nothing to Prestige or Security, and the game's
		// own Release button does not touch the hirelings either.
		assertEquals(38, keep.Variables.get("Prestige"));
		assertEquals(34, keep.Variables.get("Security"));
		assertEquals(4, hired(keep).size());
	}

	@Test
	public void releasingSomeoneWhoIsNotImprisonedIsRefused ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		try {
			new StrongholdManager(saveDir).apply(Collections.singletonList(
				Change.releasePrisoner("b_someone_else_prisoner")));
			fail("someone who is not imprisoned was released");
		} catch (final WriteRefusedException refused) {
			assertTrue(refused.getMessage(), refused.getMessage().contains("b_someone_else_prisoner"));
		}

		assertEquals(
			Collections.singletonList("b_kestorik_prisoner")
			, imprisoned(stronghold(saveDir)));
	}

	@Test
	public void everyoneCanGoInOneApply () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			Change.dismissHireling("b_crucible_hireling")
			, Change.dismissHireling("b_goldpact_hireling")
			, Change.dismissHireling("b_archer_hireling")
			, Change.dismissHireling("b_warden_wilds_hireling")
			, Change.releasePrisoner("b_kestorik_prisoner"))));

		final ComponentPersistencePacket keep = stronghold(saveDir);
		assertEquals(0, hired(keep).size());
		assertEquals(0, imprisoned(keep).size());

		// 38 - 4 - 0 - (unpaid) - 0, and 34 - 2 - 2 - (unpaid) - 2.
		assertEquals(34, keep.Variables.get("Prestige"));
		assertEquals(28, keep.Variables.get("Security"));
	}
}
