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
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.game.StrongholdUpgrade;
import uk.me.mantas.eternity.save.StrongholdCatalog;
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.EKUtils.findComponent;

// Building and demolishing stronghold upgrades in a save.
//
// The awkward part is that Prestige and Security are plain persisted scalars.
// Stronghold.CompleteBuildingUpgrade() adds the upgrade's adjustments once, at
// the moment of building, and DestroyUpgrade() takes them back off; nothing
// recalculates either number from m_upgradesBuilt on load. So appending to the
// list is only half the edit -- the other half is doing the arithmetic the game
// would have done, or the save ends up in a state it could never have reached.
public class StrongholdManagerTest extends TestHarness {
	private static final String CATALOG = "{"
		+ "\"maxHirelings\":8"
		+ ",\"upgrades\":{"
		+ "\"EasternBarbican\":{\"ordinal\":27,\"name\":\"Eastern Barbican\""
		+ ",\"cost\":0,\"days\":0,\"prestige\":1,\"security\":2,\"order\":1"
		+ ",\"global\":\"b_Eastern_Barbican\"}"
		+ ",\"MainKeep\":{\"ordinal\":4,\"name\":\"Main Keep\",\"cost\":1400"
		+ ",\"days\":3,\"prestige\":3,\"security\":4,\"order\":4"
		+ ",\"prerequisite\":\"EasternBarbican\"}"
		+ ",\"Bailey\":{\"ordinal\":3,\"name\":\"Bailey\",\"cost\":800,\"days\":2"
		+ ",\"prestige\":2,\"security\":0,\"order\":5,\"prerequisite\":\"MainKeep\"}"
		+ ",\"Dungeons\":{\"ordinal\":6,\"name\":\"Dungeons\",\"cost\":1200"
		+ ",\"days\":2,\"prestige\":0,\"security\":0,\"order\":11"
		+ ",\"prerequisite\":\"MainKeep\"}"
		+ "}}";

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);

		final Optional<File> catalogDir = EKUtils.createTempDir(PREFIX);
		assertTrue(catalogDir.isPresent());
		FileUtils.write(
			new File(catalogDir.get(), "stronghold.json"), CATALOG, "UTF-8");
		StrongholdCatalog.useCatalogAt(catalogDir.get());

		return saveDir;
	}

	@After
	public void restoreCatalog () {
		StrongholdCatalog.useNoCatalog();
	}

	private ComponentPersistencePacket stronghold (final File saveDir)
		throws IOException {

		final Optional<DeserializedPackets> packets =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();

		assertTrue(packets.isPresent());

		for (final Property property : packets.get().getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			final Optional<ComponentPersistencePacket> component =
				findComponent(packet.ComponentPackets, "Stronghold");

			if (component.isPresent()) {
				return component.get();
			}
		}

		fail("no Stronghold component in the fixture");
		return null;
	}

	@SuppressWarnings("unchecked")
	private Hashtable<String, Integer> globals (final File saveDir)
		throws IOException {

		final Optional<DeserializedPackets> packets =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();

		for (final Property property : packets.get().getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final Optional<ComponentPersistencePacket> component = findComponent(
				((ObjectPersistencePacket) property.obj).ComponentPackets
				, "GlobalVariables");

			if (component.isPresent()) {
				return (Hashtable<String, Integer>)
					component.get().Variables.get("m_data");
			}
		}

		fail("no GlobalVariables component in the fixture");
		return null;
	}

	private static List<String> built (final ComponentPersistencePacket stronghold) {
		final List<String> names = new ArrayList<>();
		final Object list = stronghold.Variables.get("m_upgradesBuilt");
		assertTrue(list instanceof CSharpCollection);

		for (final Iterator it = ((CSharpCollection) list).iterator(); it.hasNext();) {
			names.add(String.valueOf(it.next()));
		}

		return names;
	}

	@Test
	public void aStrongholdNobodyOwnsYetCannotBeEdited ()
		throws URISyntaxException, IOException {

		// The fixture's SerializedIsActivated is false: the player has not
		// taken Caed Nua. Nothing may be written until they have.
		final File saveDir = setupSave();
		final StrongholdManager manager = new StrongholdManager(saveDir);

		assertFalse(manager.isActivated());
		try {
			manager.apply(Arrays.asList(StrongholdManager.Change.addUpgrade("MainKeep")));
			fail("a stronghold nobody owns was edited");
		} catch (final WriteRefusedException refused) {
			assertTrue(refused.getMessage(), refused.getMessage().contains("not the player's yet"));
		}

		assertEquals(0, built(stronghold(saveDir)).size());
	}

	@Test
	public void buildingAnUpgradeRecordsItAndPaysTheAdjustments ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		final StrongholdManager manager = new StrongholdManager(saveDir);

		assertTrue(manager.apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.addUpgrade("EasternBarbican")
			, StrongholdManager.Change.addUpgrade("MainKeep"))));

		final ComponentPersistencePacket after = stronghold(saveDir);
		assertEquals(
			Arrays.asList("EasternBarbican", "MainKeep"), built(after));

		// 1 + 3 prestige, 2 + 4 security, exactly as the game would have added
		// them one upgrade at a time.
		assertEquals(4, after.Variables.get("Prestige"));
		assertEquals(6, after.Variables.get("Security"));
		assertEquals(true, after.Variables.get("SerializedIsActivated"));
	}

	@Test
	public void demolishingAnUpgradeTakesTheAdjustmentsBackOff ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.addUpgrade("EasternBarbican")
			, StrongholdManager.Change.addUpgrade("MainKeep"))));

		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.removeUpgrade("MainKeep"))));

		final ComponentPersistencePacket after = stronghold(saveDir);
		assertEquals(Arrays.asList("EasternBarbican"), built(after));
		assertEquals(1, after.Variables.get("Prestige"));
		assertEquals(2, after.Variables.get("Security"));
	}

	@Test
	public void buildingSomethingAlreadyBuiltChangesNothing ()
		throws URISyntaxException, IOException {

		// HasUpgrade() guards CompleteBuildingUpgrade in the game; without the
		// same guard the adjustments would be paid twice.
		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.addUpgrade("EasternBarbican"))));

		assertFalse(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.addUpgrade("EasternBarbican"))));

		final ComponentPersistencePacket after = stronghold(saveDir);
		assertEquals(1, built(after).size());
		assertEquals(1, after.Variables.get("Prestige"));
	}

	@Test
	public void demolishingSomethingThatWasNeverBuiltChangesNothing ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true))));

		assertFalse(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.removeUpgrade("Bailey"))));

		assertEquals(0, stronghold(saveDir).Variables.get("Prestige"));
	}

	@Test
	public void buildingKeepsTheUpgradesGlobalVariableInStep ()
		throws URISyntaxException, IOException {

		// UpgradeCompletedGlobalVariableName is "set to 1 when the upgrade is
		// built and back to 0 when it is destroyed"; area content keys off it,
		// so leaving it behind would show a built upgrade the world does not
		// reflect.
		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.addUpgrade("EasternBarbican"))));

		assertEquals(
			Integer.valueOf(1), globals(saveDir).get("b_Eastern_Barbican"));

		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.removeUpgrade("EasternBarbican"))));

		assertEquals(
			Integer.valueOf(0), globals(saveDir).get("b_Eastern_Barbican"));
	}

	@Test
	public void anUpgradeTheGameNeverConfiguredIsRefused ()
		throws URISyntaxException, IOException {

		// BeastVault is in the enum but has no data behind it, so the game
		// could never have built one.
		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true))));

		try {
			new StrongholdManager(saveDir).apply(Arrays.asList(
				StrongholdManager.Change.addUpgrade("BeastVault")));
			fail("an upgrade the game never configured was built");
		} catch (final WriteRefusedException refused) {
			assertTrue(refused.getMessage(), refused.getMessage().contains("BeastVault"));
		}

		assertEquals(0, built(stronghold(saveDir)).size());
	}

	// One refused change in a batch was logged and skipped, the rest were
	// written, and the page was told the whole Apply had gone in (found by
	// review, 2026-10-27). A batch goes in whole or not at all.
	@Test
	public void aBatchWithARefusedChangeWritesNothingAndSaysWhich ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true))));
		final byte[] before =
			FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save"));

		try {
			new StrongholdManager(saveDir).apply(Arrays.asList(
				StrongholdManager.Change.addUpgrade("EasternBarbican")
				, StrongholdManager.Change.addUpgrade("BeastVault")));
			fail("a batch with a refused change was applied");
		} catch (final WriteRefusedException refused) {
			assertTrue(refused.getMessage(), refused.getMessage().contains("BeastVault"));
			assertTrue(refused.getMessage(), refused.getMessage().contains("Nothing was changed"));
		}

		assertArrayEquals(before
			, FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save")));
	}

	@Test
	public void theUpgradeListStaysDeserializableAsItsOwnEnum ()
		throws URISyntaxException, IOException {

		// The list is a List<StrongholdUpgrade.Type> and the save stores each
		// entry by ordinal, so what goes in has to be the enum constant rather
		// than its name.
		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.addUpgrade("Dungeons"))));

		final Object list = stronghold(saveDir).Variables.get("m_upgradesBuilt");
		final Object first = ((CSharpCollection) list).iterator().next();

		assertTrue(first instanceof StrongholdUpgrade.Type);
		assertEquals(StrongholdUpgrade.Type.Dungeons, first);
	}

	@Test
	public void theScalarsThePlayerCaresAboutAreWritable ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.setNumber("Prestige", 40)
			, StrongholdManager.Change.setNumber("Security", 35)
			, StrongholdManager.Change.setNumber("AvailableTurns", 9)
			, StrongholdManager.Change.setNumber("m_Debt", 0))));

		final ComponentPersistencePacket after = stronghold(saveDir);
		assertEquals(40, after.Variables.get("Prestige"));
		assertEquals(35, after.Variables.get("Security"));
		assertEquals(9, after.Variables.get("AvailableTurns"));
	}

	@Test
	public void anUpgradeBuiltAfterTheNumbersWereSetStillAddsToThem ()
		throws URISyntaxException, IOException {

		// Order matters within one apply: the game's own arithmetic runs on
		// whatever the numbers are at the time the upgrade goes up.
		final File saveDir = setupSave();
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.setNumber("Prestige", 10)
			, StrongholdManager.Change.addUpgrade("EasternBarbican"))));

		assertEquals(11, stronghold(saveDir).Variables.get("Prestige"));
	}
}
