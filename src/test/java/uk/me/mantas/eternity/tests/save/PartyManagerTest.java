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
import uk.me.mantas.eternity.save.PartyManager;
import uk.me.mantas.eternity.serializer.CSharpCollection;
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

public class PartyManagerTest extends TestHarness {
	private static final String PLAYER_GUID = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";
	private static final String STRONGHOLD_LEVEL = "AR_0604_Stronghold_Great_Hall";

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File saveDir = new File(workingDir.get(), "target.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		return saveDir;
	}

	private DeserializedPackets deserialize (final File saveDir)
		throws IOException {

		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();

		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	private ObjectPersistencePacket findByID (
		final List<Property> packets
		, final String objectID) {

		return packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.map(p -> (ObjectPersistencePacket) p.obj)
			.filter(p -> objectID.equals(p.ObjectID)
				&& (p.ObjectName == null || !p.ObjectName.endsWith("_stored")))
			.findFirst().orElse(null);
	}

	private Optional<ObjectPersistencePacket> findRecordFor (
		final List<Property> packets
		, final String characterID) {

		return packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.map(p -> (ObjectPersistencePacket) p.obj)
			.filter(p -> p.ObjectName != null && p.ObjectName.endsWith("_stored"))
			.filter(p -> {
				final Optional<ComponentPersistencePacket> info =
					findComponent(p.ComponentPackets, "StoredCharacterInfo");
				return info.isPresent()
					&& characterID.equals(String.valueOf(info.get().Variables.get("GUID")));
			})
			.findFirst();
	}

	private List<String> storedGuidList (final List<Property> packets) {
		final List<String> result = new ArrayList<>();
		packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.map(p -> (ObjectPersistencePacket) p.obj)
			.filter(p -> p.ObjectName != null && p.ObjectName.startsWith("InGameGlobal("))
			.findFirst()
			.flatMap(p -> findComponent(p.ComponentPackets, "Stronghold"))
			.ifPresent(stronghold -> {
				final Object list = stronghold.Variables.get("SerializedStoredGuids");
				if (list instanceof CSharpCollection) {
					final Iterator it = ((CSharpCollection) list).iterator();
					while (it.hasNext()) {
						result.add(String.valueOf(it.next()));
					}
				}
			});

		return result;
	}

	@Test
	public void dismissMovesCompanionToStronghold ()
		throws URISyntaxException
		, IOException {

		final File saveDir = setupSave();
		final int originalCount = (int) deserialize(saveDir).getCount().obj;

		final PartyManager manager = new PartyManager(saveDir);
		final Map<String, Boolean> desired = new LinkedHashMap<>();
		desired.put(COMPANION_GUID, false);

		assertTrue(manager.apply(desired));

		final DeserializedPackets modified = deserialize(saveDir);
		final List<Property> packets = modified.getPackets();

		// One new packet: the "_stored" roster record.
		assertEquals(originalCount + 1, (int) modified.getCount().obj);
		assertEquals(originalCount + 1, packets.size());

		// The companion physically moved to the Great Hall and traded her
		// PartyMemberAI for a plain AIPackageController.
		final ObjectPersistencePacket calisca = findByID(packets, COMPANION_GUID);
		assertNotNull(calisca);
		assertEquals(STRONGHOLD_LEVEL, calisca.LevelName);
		assertFalse(findComponent(calisca.ComponentPackets, "PartyMemberAI").isPresent());
		assertTrue(findComponent(calisca.ComponentPackets, "AIPackageController").isPresent());

		// Stored characters are packed away: the save was not made in the
		// stronghold level, so the game must not try to spawn her locally.
		assertTrue(calisca.Packed);

		final ComponentPersistencePacket ai =
			findComponent(calisca.ComponentPackets, "AIPackageController").get();
		assertEquals(Boolean.TRUE, ai.Variables.get("IsActive"));
		assertEquals(Boolean.FALSE, ai.Variables.get("Patroller"));

		// The record points back at her and is listed in the stronghold.
		final Optional<ObjectPersistencePacket> record =
			findRecordFor(packets, COMPANION_GUID);
		assertTrue(record.isPresent());

		final ComponentPersistencePacket info =
			findComponent(record.get().ComponentPackets, "StoredCharacterInfo").get();
		assertEquals("Calisca", info.Variables.get("DisplayName"));
		assertEquals("Calisca", String.valueOf(info.Variables.get("NamedCompanion")));

		final List<String> stored = storedGuidList(packets);
		assertEquals(1, stored.size());
		assertEquals(record.get().ObjectID, stored.get(0));

		// The record also carries a matching InstanceID.
		final ComponentPersistencePacket instance =
			findComponent(record.get().ComponentPackets, "InstanceID").get();
		assertEquals(record.get().ObjectID, String.valueOf(instance.Variables.get("Guid")));
	}

	@Test
	public void recruitRestoresDismissedCompanion ()
		throws URISyntaxException
		, IOException {

		final File saveDir = setupSave();
		final int originalCount = (int) deserialize(saveDir).getCount().obj;

		final PartyManager manager = new PartyManager(saveDir);
		final Map<String, Boolean> dismiss = new LinkedHashMap<>();
		dismiss.put(COMPANION_GUID, false);
		assertTrue(manager.apply(dismiss));

		final Map<String, Boolean> recruit = new LinkedHashMap<>();
		recruit.put(COMPANION_GUID, true);
		assertTrue(new PartyManager(saveDir).apply(recruit));

		final DeserializedPackets modified = deserialize(saveDir);
		final List<Property> packets = modified.getPackets();

		// The record is gone again and the counts are back to the original.
		assertEquals(originalCount, (int) modified.getCount().obj);
		assertEquals(originalCount, packets.size());
		assertFalse(findRecordFor(packets, COMPANION_GUID).isPresent());
		assertTrue(storedGuidList(packets).isEmpty());

		// She is back in the party, standing exactly on the player (a
		// guaranteed-valid spawn position), unpacked so the game spawns her.
		final ObjectPersistencePacket calisca = findByID(packets, COMPANION_GUID);
		final ObjectPersistencePacket player = findByID(packets, PLAYER_GUID);
		assertNotNull(calisca);
		assertEquals(player.LevelName, calisca.LevelName);
		assertEquals(player.Location.x, calisca.Location.x, 1e-4);
		assertEquals(player.Location.y, calisca.Location.y, 1e-4);
		assertEquals(player.Location.z, calisca.Location.z, 1e-4);
		assertFalse(calisca.Packed);
		assertFalse(findComponent(calisca.ComponentPackets, "AIPackageController").isPresent());

		final Optional<ComponentPersistencePacket> ai =
			findComponent(calisca.ComponentPackets, "PartyMemberAI");
		assertTrue(ai.isPresent());
		assertEquals(Boolean.TRUE, ai.get().Variables.get("IsActiveInParty"));
		assertEquals(Boolean.TRUE, ai.get().Variables.get("IsInSlot"));

		final int slot = (int) ai.get().Variables.get("AssignedSlot");
		assertTrue(slot > 0 && slot < 6);
	}

	@Test
	public void mainCharacterCannotBeDismissed ()
		throws URISyntaxException
		, IOException {

		final File saveDir = setupSave();
		final PartyManager manager = new PartyManager(saveDir);
		final Map<String, Boolean> desired = new LinkedHashMap<>();
		desired.put(PLAYER_GUID, false);

		assertFalse(manager.apply(desired));
	}

	@Test
	public void aCharacterTheSaveDoesNotHaveIsRefusedWithoutTouchingTheFile ()
		throws URISyntaxException
		, IOException {

		// A companion the game has deleted outright shows up in the character
		// list under a synthetic "dead:<name>" id so it can be resurrected.
		// That id is not an ObjectID and there is nothing to recruit; the
		// party dialog must never send one, and if it does the save has to
		// come through untouched rather than half-applied.
		final File saveDir = setupSave();
		final byte[] before =
			FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save"));

		final Map<String, Boolean> desired = new LinkedHashMap<>();
		desired.put("dead:Eder", true);

		assertFalse(new PartyManager(saveDir).apply(desired));
		assertArrayEquals(
			before, FileUtils.readFileToByteArray(
				new File(saveDir, "MobileObjects.save")));
	}

	@Test
	public void oneUnknownCharacterDoesNotDiscardTheRestOfTheBatch ()
		throws URISyntaxException
		, IOException {

		// The dialog sends every change at once. Refusing the whole batch on
		// one bad id would silently throw away the edits that were fine, so
		// the check happens before anything is written.
		final File saveDir = setupSave();
		final Map<String, Boolean> desired = new LinkedHashMap<>();
		desired.put(COMPANION_GUID, false);   // a real dismissal
		desired.put("dead:Eder", true);       // and one that cannot work

		assertFalse(new PartyManager(saveDir).apply(desired));

		// Calisca must still be in the party: nothing was applied.
		final DeserializedPackets packets = deserialize(saveDir);
		final Optional<Property> calisca = packets.getPackets().stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket)
			.filter(p -> COMPANION_GUID.equalsIgnoreCase(
				((ObjectPersistencePacket) p.obj).ObjectID))
			.findFirst();

		assertTrue(calisca.isPresent());
		assertTrue(new PartyManager(saveDir).isInParty(calisca.get()));
	}

	@Test
	public void noopWhenAlreadyInDesiredState ()
		throws URISyntaxException
		, IOException {

		final File saveDir = setupSave();
		final byte[] before =
			FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save"));

		final PartyManager manager = new PartyManager(saveDir);
		final Map<String, Boolean> desired = new LinkedHashMap<>();
		desired.put(COMPANION_GUID, true); // Calisca is already in the party.

		assertTrue(manager.apply(desired));

		final byte[] after =
			FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save"));
		assertArrayEquals(before, after);
	}
}
