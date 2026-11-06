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

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import org.apache.commons.io.FileUtils;
import org.json.JSONObject;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.QuestRestorer;
import uk.me.mantas.eternity.save.QuestTrackerBlob;
import uk.me.mantas.eternity.save.Resurrector;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.EKUtils.findComponent;

// The game deletes a dead companion's mobile object outright, so
// resurrection means transplanting their object graph from a donor save of
// the same playthrough — regenerating any UUIDs the dead save already holds
// (death-dropped loot keeps its ids) and picking a free party slot. This is
// the exact recipe that produced an in-game-verified resurrection.
public class ResurrectorTest extends TestHarness {
	private static final String PREFIX_CALISCA = "Companion_Calisca";

	private File setupSave (final String name) throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File saveDir = new File(workingDir.get(), name);
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		return saveDir;
	}

	private DeserializedPackets deserialize (final File mobileObjects) throws IOException {
		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(mobileObjects).deserialize();
		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	// Removes a companion's graph the way in-game death does. Optionally
	// leaves one owned object behind — still parented to the deleted
	// companion, exactly like the orphaned remnants real deaths produce.
	// Returns the ObjectID of the object left behind, if any.
	private String removeCompanion (
		final File saveDir, final String prefix, final boolean leaveOneOwnedObject)
		throws IOException {

		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		final DeserializedPackets deserialized = deserialize(mobileObjects);
		final List<Property> packets = deserialized.getPackets();

		String rootName = null;
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket o = (ObjectPersistencePacket) p.obj;
			if (o.ObjectName != null
				&& o.ObjectName.toLowerCase().startsWith(prefix.toLowerCase())
				&& !o.ObjectName.endsWith("_stored")) { rootName = o.ObjectName; break; }
		}
		assertNotNull(rootName);

		final List<Property> retained = new ArrayList<>();
		String leftBehindID = null;
		for (final Property p : packets) {
			final boolean isRoot = p.obj instanceof ObjectPersistencePacket
				&& rootName.equals(((ObjectPersistencePacket) p.obj).ObjectName);
			final boolean isOwned = p.obj instanceof ObjectPersistencePacket
				&& rootName.equals(((ObjectPersistencePacket) p.obj).Parent);

			if (isRoot) continue;
			if (isOwned && leaveOneOwnedObject && leftBehindID == null
				&& ((ObjectPersistencePacket) p.obj).ObjectID != null) {
				leftBehindID = ((ObjectPersistencePacket) p.obj).ObjectID;
				retained.add(p);
				continue;
			}
			if (isOwned) continue;
			retained.add(p);
		}

		if (leaveOneOwnedObject) {
			assertNotNull("fixture companion has no owned objects to leave behind", leftBehindID);
		}

		final int removed = packets.size() - retained.size();
		assertTrue(removed >= 1);
		deserialized.setPackets(retained);
		Property.update(deserialized.getCount(), (int) deserialized.getCount().obj - removed);

		assertTrue(mobileObjects.delete());
		assertTrue(mobileObjects.createNewFile());
		deserialized.reserialize(mobileObjects);
		return leftBehindID;
	}

	// Re-parents one object through the write model and rewrites the file.
	private void reparent (final File saveDir, final String objectID, final String newParent)
		throws IOException {

		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		final DeserializedPackets deserialized = deserialize(mobileObjects);

		boolean updated = false;
		for (final Property p : deserialized.getPackets()) {
			if (p.obj instanceof ObjectPersistencePacket
				&& objectID.equals(((ObjectPersistencePacket) p.obj).ObjectID)) {

				assertTrue(Property.update(p, "Parent", newParent));
				updated = true;
				break;
			}
		}

		assertTrue(updated);
		assertTrue(mobileObjects.delete());
		assertTrue(mobileObjects.createNewFile());
		deserialized.reserialize(mobileObjects);
	}

	private Optional<ObjectPersistencePacket> findByPrefix (
		final List<Property> packets, final String prefix) {

		return packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.map(p -> (ObjectPersistencePacket) p.obj)
			.filter(o -> o.ObjectName != null
				&& o.ObjectName.toLowerCase().startsWith(prefix.toLowerCase())
				&& !o.ObjectName.endsWith("_stored"))
			.findFirst();
	}

	private int countOwned (final List<Property> packets, final String rootName) {
		int owned = 0;
		for (final Property p : packets) {
			if (p.obj instanceof ObjectPersistencePacket
				&& rootName.equals(((ObjectPersistencePacket) p.obj).Parent)) {
				owned++;
			}
		}
		return owned;
	}

	@Test
	public void transplantsCompanionFromDonor () throws URISyntaxException, IOException {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		// Simulate real death: graph removed, one item left behind still
		// parented to the deleted companion (the orphaned-remnant pattern
		// real deaths produce), death flag raised. (The fixture pre-seeds all
		// companion flags, so we use a real flag key parametrically.)
		removeCompanion(deadDir, PREFIX_CALISCA, true);
		DeadCompanionsTest.setGlobalFlag(deadDir, "b_Eder_Dead", 1);

		final File donorDir = setupSave("cadena 5 Donor.savegame");
		final File donorMobile = new File(donorDir, "MobileObjects.save");
		final DeserializedPackets donorBefore = deserialize(donorMobile);
		final String donorRootName = findByPrefix(donorBefore.getPackets(), PREFIX_CALISCA)
			.get().ObjectName;
		final int donorOwned = countOwned(donorBefore.getPackets(), donorRootName);

		final boolean result = new Resurrector(deadDir)
			.transplant(donorMobile, PREFIX_CALISCA, "b_Eder_Dead", Collections.emptyList(), null, null);
		assertTrue(result);

		final DeserializedPackets after =
			deserialize(new File(deadDir, "MobileObjects.save"));
		final List<Property> packets = after.getPackets();

		// The companion is back...
		final Optional<ObjectPersistencePacket> calisca = findByPrefix(packets, PREFIX_CALISCA);
		assertTrue(calisca.isPresent());

		// ...owning exactly what the donor copy owned: the stale remnant must
		// be purged, not kept alongside the donor's copy. Re-attached
		// duplicates make the game's load-time reconciliation drop items and
		// item-granted abilities (the "half his skills are gone" bug).
		assertEquals(donorOwned, countOwned(packets, calisca.get().ObjectName));

		// ...the object count is consistent...
		assertEquals(packets.size(), (int) after.getCount().obj);

		// ...no ObjectID exists twice (the planted loot collision was
		// resolved by regenerating the incoming copy's UUID)...
		final Set<String> seen = new HashSet<>();
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final String id = ((ObjectPersistencePacket) p.obj).ObjectID;
			if (id == null) continue;
			assertTrue("duplicate ObjectID " + id, seen.add(id));
		}

		// ...the death flag is cleared...
		assertEquals(0, globalFlag(packets, "b_Eder_Dead"));

		// ...the companion took a free party slot and stands next to the
		// player on the player's level.
		final ObjectPersistencePacket player = findByPrefix(packets, "Player_").get();
		final Map<String, Object> ai = componentVars(calisca.get(), "PartyMemberAI");
		final Map<String, Object> playerAI = componentVars(player, "PartyMemberAI");
		assertEquals(Boolean.TRUE, ai.get("IsActiveInParty"));
		assertNotEquals(playerAI.get("AssignedSlot"), ai.get("AssignedSlot"));
		final int slot = (Integer) ai.get("AssignedSlot");
		assertTrue("slot " + slot + " out of party range", slot >= 1 && slot <= 5);
		assertEquals(player.LevelName, calisca.get().LevelName);
	}

	// Real dead saves also hold junk parented to OLDER incarnations of the
	// companion (e.g. Companion_Eder(Clone)_2 while the current one is _1).
	// The purge must match by companion prefix, not exact root name.
	@Test
	public void purgesRemnantsOfOlderInstances () throws URISyntaxException, IOException {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		final String remnantID = removeCompanion(deadDir, PREFIX_CALISCA, true);
		reparent(deadDir, remnantID, "Companion_Calisca(Clone)_2");
		DeadCompanionsTest.setGlobalFlag(deadDir, "b_Eder_Dead", 1);

		final File donorDir = setupSave("cadena 5 Donor.savegame");
		assertTrue(new Resurrector(deadDir).transplant(
			new File(donorDir, "MobileObjects.save"), PREFIX_CALISCA, "b_Eder_Dead"
			, Collections.emptyList(), null, null));

		final List<Property> packets =
			deserialize(new File(deadDir, "MobileObjects.save")).getPackets();

		// The old-instance orphan is gone; the donor's copy of the same
		// object (same UUID) exists exactly once, owned by the companion.
		int occurrences = 0;
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket o = (ObjectPersistencePacket) p.obj;
			if (remnantID.equals(o.ObjectID)) {
				occurrences++;
				assertTrue(o.Parent != null
					&& o.Parent.startsWith(PREFIX_CALISCA)
					&& !o.Parent.endsWith("_2"));
			}
		}
		assertEquals(1, occurrences);
	}

	// An object that migrated to ANOTHER owner (e.g. looted into the
	// player's inventory) is not a remnant — it must survive, and the
	// donor's incoming copy gets a fresh UUID instead.
	@Test
	public void remapsCollisionsOwnedByOthers () throws URISyntaxException, IOException {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		final String migratedID = removeCompanion(deadDir, PREFIX_CALISCA, true);

		final List<Property> before =
			deserialize(new File(deadDir, "MobileObjects.save")).getPackets();
		final String playerName = findByPrefix(before, "Player_").get().ObjectName;
		reparent(deadDir, migratedID, playerName);
		DeadCompanionsTest.setGlobalFlag(deadDir, "b_Eder_Dead", 1);

		final File donorDir = setupSave("cadena 5 Donor.savegame");
		assertTrue(new Resurrector(deadDir).transplant(
			new File(donorDir, "MobileObjects.save"), PREFIX_CALISCA, "b_Eder_Dead"
			, Collections.emptyList(), null, null));

		final List<Property> packets =
			deserialize(new File(deadDir, "MobileObjects.save")).getPackets();

		// No duplicate ids anywhere...
		final Set<String> seen = new HashSet<>();
		String migratedParent = null;
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket o = (ObjectPersistencePacket) p.obj;
			if (o.ObjectID == null) continue;
			assertTrue("duplicate ObjectID " + o.ObjectID, seen.add(o.ObjectID));
			if (migratedID.equals(o.ObjectID)) {
				migratedParent = o.Parent;
			}
		}

		// ...the migrated object survived with the player...
		assertEquals(playerName, migratedParent);

		// ...and the companion still owns a full donor graph (their copy of
		// the migrated object arrived under a regenerated id).
		final ObjectPersistencePacket calisca = findByPrefix(packets, PREFIX_CALISCA).get();
		final DeserializedPackets donor =
			deserialize(new File(donorDir, "MobileObjects.save"));
		final String donorRootName =
			findByPrefix(donor.getPackets(), PREFIX_CALISCA).get().ObjectName;
		assertEquals(
			countOwned(donor.getPackets(), donorRootName)
			, countOwned(packets, calisca.ObjectName));
	}

	// Not everything a dead companion carried stays in the world state: what
	// was in their quick slots is left in the area they died in, as objects of
	// that area, under the ids it always had. The donor brings the same
	// objects, and under the same ids each was in the save twice -- the game
	// said so on loading the area (Player.log, 2026-10-05, three potions).
	@Test
	public void whatTheGameLeftInAnAreaComesBackUnderAnIdOfItsOwn () throws Exception {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		removeCompanion(deadDir, PREFIX_CALISCA, false);
		DeadCompanionsTest.setGlobalFlag(deadDir, "b_Eder_Dead", 1);

		// The area she died in still holds two of the things she carried.
		final File donorDir = setupSave("cadena 5 Donor.savegame");
		final DeserializedPackets donor = deserialize(new File(donorDir, "MobileObjects.save"));
		final String donorRootName = findByPrefix(donor.getPackets(), PREFIX_CALISCA).get().ObjectName;
		final int carried = countOwned(donor.getPackets(), donorRootName);
		final List<Property> left = new ArrayList<>();
		for (final Property p : donor.getPackets()) {
			if (left.size() < 2 && donorRootName.equals(((ObjectPersistencePacket) p.obj).Parent)
				&& ((ObjectPersistencePacket) p.obj).ObjectID != null) {

				left.add(p);
			}
		}

		assertEquals(2, left.size());
		final Set<String> leftIDs = new HashSet<>();
		left.forEach(p -> leftIDs.add(((ObjectPersistencePacket) p.obj).ObjectID.toLowerCase()));
		Property.update(donor.getCount(), left.size());
		final File area = new File(deadDir, "AR_0701_Encampment.lvl");
		assertTrue(area.createNewFile());
		new DeserializedPackets(left, donor.getCount()).reserialize(area);
		final byte[] areaBefore = FileUtils.readFileToByteArray(area);

		assertTrue(new Resurrector(deadDir).transplant(
			new File(donorDir, "MobileObjects.save"), PREFIX_CALISCA, "b_Eder_Dead"
			, Collections.emptyList(), null, null));

		final List<Property> packets = deserialize(new File(deadDir, "MobileObjects.save")).getPackets();
		for (final Property p : packets) {
			final String id = ((ObjectPersistencePacket) p.obj).ObjectID;
			assertFalse("the world state holds " + id + ", which the area holds too"
				, id != null && leftIDs.contains(id.toLowerCase()));
		}

		// She has everything the donor had all the same, two of them renamed...
		final ObjectPersistencePacket calisca = findByPrefix(packets, PREFIX_CALISCA).get();
		assertEquals(carried, countOwned(packets, calisca.ObjectName));

		// ...and the area is as the game left it.
		assertArrayEquals(areaBefore, FileUtils.readFileToByteArray(area));
	}

	// A max-level donor save usually has most companions dismissed to the
	// stronghold roster — AIPackageController instead of PartyMemberAI. The
	// transplant must perform the recruit-time component swap.
	@Test
	public void transplantsFromRosterDonor () throws URISyntaxException, IOException {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		removeCompanion(deadDir, PREFIX_CALISCA, false);
		DeadCompanionsTest.setGlobalFlag(deadDir, "b_Eder_Dead", 1);

		final File donorDir = setupSave("cadena 5 Donor.savegame");
		final Map<String, Boolean> dismiss = new HashMap<>();
		dismiss.put("b1a7e809-0000-0000-0000-000000000000", false);
		assertTrue(new uk.me.mantas.eternity.save.PartyManager(donorDir).apply(dismiss));

		assertTrue(new Resurrector(deadDir).transplant(
			new File(donorDir, "MobileObjects.save"), PREFIX_CALISCA, "b_Eder_Dead"
			, Collections.emptyList(), null, null));

		final List<Property> packets =
			deserialize(new File(deadDir, "MobileObjects.save")).getPackets();
		final ObjectPersistencePacket calisca = findByPrefix(packets, PREFIX_CALISCA).get();

		// She's a real party member again: swapped component, valid slot,
		// unpacked so the game instantiates her next to the player.
		final Map<String, Object> ai = componentVars(calisca, "PartyMemberAI");
		assertEquals(Boolean.TRUE, ai.get("IsActiveInParty"));
		final int slot = (Integer) ai.get("AssignedSlot");
		assertTrue("slot " + slot + " out of party range", slot >= 1 && slot <= 5);
		assertFalse(findComponent(calisca.ComponentPackets, "AIPackageController").isPresent());
		assertFalse(calisca.Packed);
	}

	@Test
	public void refusesDonorWithoutCompanion () throws URISyntaxException, IOException {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		removeCompanion(deadDir, PREFIX_CALISCA, false);
		DeadCompanionsTest.setGlobalFlag(deadDir, "b_Eder_Dead", 1);

		// The dead save itself is a valid file that lacks the companion.
		final File badDonor = new File(deadDir, "MobileObjects.save");
		assertFalse(new Resurrector(deadDir)
			.transplant(badDonor, PREFIX_CALISCA, "b_Eder_Dead", Collections.emptyList(), null, null));
	}

	@Test
	public void refusesWhenCompanionStillPresent () throws URISyntaxException, IOException {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		final File donorDir = setupSave("cadena 5 Donor.savegame");

		// Nothing was removed — the companion is alive in the "dead" save.
		assertFalse(new Resurrector(deadDir).transplant(
			new File(donorDir, "MobileObjects.save"), PREFIX_CALISCA, "b_Eder_Dead"
			, Collections.emptyList(), null, null));
	}

	@Test
	public void findsNewestSameSessionDonor () throws URISyntaxException, IOException {
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		removeCompanion(deadDir, PREFIX_CALISCA, false);

		// A saves folder with: an old same-session save WITH the companion, a
		// newer same-session save WITHOUT them, and a foreign-session save
		// WITH them. Only the first qualifies.
		final Optional<File> savesDir = EKUtils.createTempDir(PREFIX);
		assertTrue(savesDir.isPresent());

		final File donorSource = setupSave("cadena 5 HasCalisca.savegame");
		final File noCaliscaSource = setupSave("cadena 9 NoCalisca.savegame");
		removeCompanion(noCaliscaSource, PREFIX_CALISCA, false);
		final File foreignSource = setupSave("other 3 HasCalisca.savegame");

		final File hasCalisca = zipSave(donorSource, savesDir.get(), "cadena 5 HasCalisca.savegame");
		final File noCalisca = zipSave(noCaliscaSource, savesDir.get(), "cadena 9 NoCalisca.savegame");
		final File foreign = zipSave(foreignSource, savesDir.get(), "other 3 HasCalisca.savegame");

		// Make the unsuitable candidates look newer so the scan must reject
		// them on the merits, not on age.
		assertTrue(hasCalisca.setLastModified(System.currentTimeMillis() - 60_000));
		assertTrue(noCalisca.setLastModified(System.currentTimeMillis()));
		assertTrue(foreign.setLastModified(System.currentTimeMillis()));

		final Settings settings = mockSettings();
		settings.json = new JSONObject();
		settings.json.put("savesLocation", savesDir.get().getAbsolutePath());

		final int workspacesBefore = donorWorkspaces();
		final Optional<File> donor = new Resurrector(deadDir).findDonor(PREFIX_CALISCA);
		assertTrue(donor.isPresent());

		// The returned MobileObjects.save must actually contain the companion.
		assertTrue(findByPrefix(deserialize(donor.get()).getPackets(), PREFIX_CALISCA)
			.isPresent());

		// Each candidate is unpacked somewhere to be looked at. The newer save
		// without her was rejected, and its copy must not be left in temp --
		// only the donor's own, which the caller is about to use.
		assertEquals(workspacesBefore + 1, donorWorkspaces());
		assertTrue(donor.get().getParentFile().getName().startsWith("EK-donor"));
	}

	private static int donorWorkspaces () {
		final File[] found = new File(System.getProperty("java.io.tmpdir"))
			.listFiles((dir, name) -> name.startsWith("EK-donor"));

		return found == null ? 0 : found.length;
	}

	// A full transplant also un-fails the companion's quest when a quest path
	// is supplied — the way real resurrections run via the registry.
	@Test
	public void transplantRestoresFailedQuest () throws URISyntaxException, IOException {
		final String ederQuest = "data/quests/companions/companion_qst_eder.quest";
		final File deadDir = setupSave("cadena 0 Dead.savegame");
		removeCompanion(deadDir, PREFIX_CALISCA, false);
		DeadCompanionsTest.setGlobalFlag(deadDir, "b_Eder_Dead", 1);
		failQuest(deadDir, ederQuest, 1);

		final File donorDir = setupSave("cadena 5 Donor.savegame");
		assertTrue(new Resurrector(deadDir).transplant(
			new File(donorDir, "MobileObjects.save"), PREFIX_CALISCA, "b_Eder_Dead"
			, Collections.emptyList(), null, ederQuest));

		final List<Property> packets =
			deserialize(new File(deadDir, "MobileObjects.save")).getPackets();
		final Property blobProperty = QuestRestorer.findQuestTrackers(packets).get();
		final Byte[] boxed =
			(Byte[]) ((uk.me.mantas.eternity.serializer.properties.SimpleProperty) blobProperty)
				.value;
		final byte[] raw = new byte[boxed.length];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = boxed[i];
		}

		final QuestTrackerBlob.Tracker tracker =
			QuestTrackerBlob.parse(raw).get().tracker(ederQuest).get();
		assertEquals(-1, tracker.endState());
		assertFalse(tracker.failed());
	}

	// Marks a quest failed in a save file's QuestTrackers blob, the way
	// companion death does.
	private void failQuest (final File saveDir, final String questPath, final int endState)
		throws IOException {

		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		final DeserializedPackets deserialized = deserialize(mobileObjects);
		final Property blobProperty =
			QuestRestorer.findQuestTrackers(deserialized.getPackets()).get();
		final Byte[] boxed =
			(Byte[]) ((uk.me.mantas.eternity.serializer.properties.SimpleProperty) blobProperty)
				.value;
		final byte[] raw = new byte[boxed.length];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = boxed[i];
		}

		final QuestTrackerBlob.Tracker tracker =
			QuestTrackerBlob.parse(raw).get().tracker(questPath).get();
		tracker.setEndState(endState);
		tracker.setFailed(true);

		final Byte[] patched = new Byte[raw.length];
		for (int i = 0; i < raw.length; i++) {
			patched[i] = raw[i];
		}

		assertTrue(Property.update(blobProperty, patched));
		assertTrue(mobileObjects.delete());
		assertTrue(mobileObjects.createNewFile());
		deserialized.reserialize(mobileObjects);
	}

	private File zipSave (final File extractedDir, final File targetDir, final String name)
		throws IOException {

		final File zip = new File(targetDir, name);
		new ZipFile(zip).addFiles(
			new ArrayList<>(Arrays.asList(extractedDir.listFiles())), new ZipParameters());
		return zip;
	}

	private int globalFlag (final List<Property> packets, final String flag) {
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket o = (ObjectPersistencePacket) p.obj;
			if (o.ObjectName == null || !o.ObjectName.startsWith("InGameGlobal")) continue;
			final Optional<ComponentPersistencePacket> gv =
				findComponent(o.ComponentPackets, "GlobalVariables");
			if (!gv.isPresent()) continue;
			final Object mData = gv.get().Variables.get("m_data");
			if (mData instanceof Hashtable) {
				return ((Number) ((Hashtable<?, ?>) mData).get(flag)).intValue();
			}
		}

		fail("no GlobalVariables found");
		return -1;
	}

	private Map<String, Object> componentVars (
		final ObjectPersistencePacket packet, final String component) {

		final Optional<ComponentPersistencePacket> c =
			findComponent(packet.ComponentPackets, component);
		assertTrue(component + " missing", c.isPresent());
		return c.get().Variables;
	}
}
