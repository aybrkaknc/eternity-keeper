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
import org.json.JSONObject;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.game.UnityEngine.Vector3;

public class CharacterImporterTest extends TestHarness {
	private static final String PLAYER_GUID = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";

	private File resourceDirectory () throws URISyntaxException {
		return new File(getClass().getResource("/").toURI());
	}

	private File tempDir () {
		final Optional<File> tempDir = EKUtils.createTempDir(PREFIX);
		assertTrue(tempDir.isPresent());
		return tempDir.get();
	}

	private String requestJSON (final File saveDirectory) {
		final JSONObject request = new JSONObject();
		request.put("oldSave", saveDirectory.getAbsolutePath());
		request.put("savedYet", false);
		return request.toString();
	}

	private File exportCompanionChr (final File destinationDir)
		throws IOException
		, URISyntaxException {

		final File chrFile = new File(destinationDir, "companion.chr");
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, COMPANION_GUID
			, chrFile.getAbsolutePath());

		assertTrue(exporter.export());
		return chrFile;
	}

	@Test(expected = FileNotFoundException.class)
	public void constructorThrowsWhenSaveDirectoryMissing ()
		throws IOException
		, URISyntaxException {

		final File chrFile = exportCompanionChr(tempDir());
		new CharacterImporter(
			requestJSON(new File("404"))
			, chrFile.getAbsolutePath());
	}

	@Test(expected = FileNotFoundException.class)
	public void constructorThrowsWhenChrFileMissing ()
		throws IOException
		, URISyntaxException {

		new CharacterImporter(requestJSON(resourceDirectory()), "404.chr");
	}

	@Test
	public void importsCompanionIntoSave ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resourceDirectory(), "MobileObjects.save")
			, saveDir);

		// Record the pre-import state of the target save.
		final DeserializedPackets original =
			deserialize(new File(saveDir, "MobileObjects.save"));
		final int originalCount = (int) original.getCount().obj;
		final ObjectPersistencePacket player =
			findByObjectID(original.getPackets(), PLAYER_GUID);
		final String companionName =
			findByObjectID(original.getPackets(), COMPANION_GUID).ObjectName;

		final File chrFile = exportCompanionChr(workingDir);
		final DeserializedPackets chrContents = deserialize(chrFile);
		final int chrCount = (int) chrContents.getCount().obj;

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertTrue(importer.importCharacter());

		// The import edits a private copy, never the directory the list
		// unpacked -- a user who discards it must not find it on reopening.
		assertNotEquals(saveDir.getAbsoluteFile(), importer.saveFile().getAbsoluteFile());
		assertEquals(originalCount
			, (int) deserialize(new File(saveDir, "MobileObjects.save")).getCount().obj);

		// The modified save must contain every original object plus every
		// object from the CHR file and declare the right count.
		final DeserializedPackets modified =
			deserialize(new File(importer.saveFile(), "MobileObjects.save"));
		final int modifiedCount = (int) modified.getCount().obj;

		assertEquals(originalCount + chrCount, modifiedCount);
		assertEquals(modifiedCount, modified.getPackets().size());

		// The imported companion coexists with the original: same
		// ObjectName, freshly generated GUID.
		final List<ObjectPersistencePacket> companions = modified.getPackets().stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> companionName.equals(packet.ObjectName))
			.collect(Collectors.toList());

		assertEquals(2, companions.size());

		final List<ObjectPersistencePacket> imported = companions.stream()
			.filter(packet -> !COMPANION_GUID.equals(packet.ObjectID))
			.collect(Collectors.toList());

		assertEquals(1, imported.size());
		final ObjectPersistencePacket importedPacket = imported.get(0);

		// The regenerated ObjectID must be a well-formed, distinct UUID.
		assertEquals(
			importedPacket.ObjectID
			, UUID.fromString(importedPacket.ObjectID).toString());

		// The imported character must have been anchored next to the player.
		assertEquals(player.LevelName, importedPacket.LevelName);
		assertEquals(player.Location.x + 1, importedPacket.Location.x, 1e-4);
		assertEquals(player.Location.y + 1, importedPacket.Location.y, 1e-4);
		assertEquals(player.Location.z, importedPacket.Location.z, 1e-4);
	}

	// Keeping both copies of a character: every object the character file
	// brings -- items, abilities -- is already in the save under the same ID.
	// The copy got a fresh ID of its own and they did not, so each existed
	// twice and the game drops both. They get fresh IDs too, and the copy's
	// lists and slots follow them. What names the character itself is left
	// alone: a companion's ID is the fixed GUID the game knows the companion
	// by, and each ability's Owner is that.
	@Test
	public void keepingBothCopiesGivesTheCopyItsOwnIds ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resourceDirectory(), "MobileObjects.save"), saveDir);

		final DeserializedPackets original = deserialize(new File(saveDir, "MobileObjects.save"));
		final java.util.Set<String> originalIds = ids(original);
		final String companionName = findByObjectID(original.getPackets(), COMPANION_GUID).ObjectName;

		final File chrFile = exportCompanionChr(workingDir);
		final java.util.Set<String> brought = ids(deserialize(chrFile));
		assertTrue("the case in question: all of it is already there", originalIds.containsAll(brought));

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());
		assertTrue(importer.importCharacter());

		final DeserializedPackets modified =
			deserialize(new File(importer.saveFile(), "MobileObjects.save"));
		final java.util.Map<String, Long> seen = modified.getPackets().stream()
			.map(property -> ((ObjectPersistencePacket) property.obj).ObjectID.toLowerCase())
			.collect(Collectors.groupingBy(id -> id, Collectors.counting()));

		assertTrue("no ID twice: " + seen, seen.values().stream().allMatch(n -> n == 1));
		assertTrue("the original is untouched", ids(modified).containsAll(originalIds));

		final java.util.Set<String> fresh = new java.util.HashSet<>(ids(modified));
		fresh.removeAll(originalIds);
		assertEquals("every object the copy brought has an ID of its own", brought.size(), fresh.size());

		final List<ObjectPersistencePacket> copy = modified.getPackets().stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> fresh.contains(packet.ObjectID.toLowerCase()))
			.collect(Collectors.toList());

		final String copyId = copy.stream()
			.filter(packet -> companionName.equals(packet.ObjectName))
			.map(packet -> packet.ObjectID.toLowerCase())
			.findFirst()
			.orElseThrow(() -> new AssertionError("no copy of " + companionName));

		final java.util.Set<String> originalsThings = new java.util.HashSet<>(brought);
		originalsThings.remove(COMPANION_GUID);
		int listed = 0;
		for (final ObjectPersistencePacket packet : copy) {
			final List<String> named = namedIds(packet);
			assertTrue(packet.ObjectName + " names one of the original's things: " + named
				, java.util.Collections.disjoint(named, originalsThings));
			if (copyId.equals(packet.ObjectID.toLowerCase())) {
				for (final String id : named) {
					if (fresh.contains(id)) {
						listed++;
					}
				}
			}
		}

		assertTrue("the copy's own lists name its own things", listed > 0);
	}

	// An overwrite takes out only what the replaced character owns. Anything
	// the file brings that the save now keeps somewhere else -- an item handed
	// to someone since the export -- would come back under the ID it still has
	// there. The one the file brings gets an ID of its own; the other stays
	// exactly where it is.
	@Test
	public void anItemHandedOnSinceTheExportComesBackUnderAnIdOfItsOwn ()
		throws IOException
		, URISyntaxException {

		final String ring = "c4b7033d-c452-4946-a9ba-9998cb411201";
		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resourceDirectory(), "MobileObjects.save"), saveDir);

		final File chrFile = new File(workingDir, "player.chr");
		assertTrue(new CharacterExporter(
			resourceDirectory().getAbsolutePath(), PLAYER_GUID, chrFile.getAbsolutePath()).export());

		// Since the export, Elwyn has handed the ring to Calisca.
		assertTrue(new uk.me.mantas.eternity.save.InventoryManager(saveDir).apply(
			java.util.Collections.singletonList(new uk.me.mantas.eternity.save.InventoryManager.Change(
				PLAYER_GUID, "PlayerInventory", ring, 1, COMPANION_GUID, "Inventory", -1))));

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());
		assertTrue(importer.overwriteCharacter());

		final DeserializedPackets modified =
			deserialize(new File(importer.saveFile(), "MobileObjects.save"));
		final java.util.Map<String, Long> seen = modified.getPackets().stream()
			.map(property -> ((ObjectPersistencePacket) property.obj).ObjectID.toLowerCase())
			.collect(Collectors.groupingBy(id -> id, Collectors.counting()));
		assertTrue("no ID twice: " + seen, seen.values().stream().allMatch(n -> n == 1));

		final ObjectPersistencePacket calisca = findByObjectID(modified.getPackets(), COMPANION_GUID);
		assertTrue("Calisca keeps the ring", listed(calisca, "Inventory").contains(ring));
		assertEquals(calisca.ObjectName, findByObjectID(modified.getPackets(), ring).Parent);

		final ObjectPersistencePacket elwyn = findByObjectID(modified.getPackets(), PLAYER_GUID);
		final List<String> elwyns = listed(elwyn, "PlayerInventory");
		assertFalse(elwyns.contains(ring));

		final String ringName = findByObjectID(modified.getPackets(), ring).ObjectName;
		final List<ObjectPersistencePacket> rings = modified.getPackets().stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> ringName.equals(packet.ObjectName))
			.collect(Collectors.toList());
		assertEquals("the file's ring is back, beside Calisca's", 2, rings.size());

		final ObjectPersistencePacket brought = rings.stream()
			.filter(packet -> !ring.equals(packet.ObjectID))
			.findFirst().get();
		assertEquals(elwyn.ObjectName, brought.Parent);
		assertTrue("Elwyn's pack names the ring the file brought", elwyns.contains(brought.ObjectID.toLowerCase()));
	}

	// The same, for something that left the world state altogether: sold, it
	// is an object of the store's area, under the ID the file still has for it.
	@Test
	public void anItemSoldSinceTheExportComesBackUnderAnIdOfItsOwn () throws Exception {
		final String ring = "c4b7033d-c452-4946-a9ba-9998cb411201";
		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resourceDirectory(), "MobileObjects.save"), saveDir);

		final File chrFile = new File(workingDir, "player.chr");
		assertTrue(new CharacterExporter(
			resourceDirectory().getAbsolutePath(), PLAYER_GUID, chrFile.getAbsolutePath()).export());

		// The store's area holds the ring now.
		final DeserializedPackets world = deserialize(new File(saveDir, "MobileObjects.save"));
		final List<Property> sold = world.getPackets().stream()
			.filter(property -> ring.equals(((ObjectPersistencePacket) property.obj).ObjectID))
			.collect(Collectors.toList());
		assertEquals(1, sold.size());
		Property.update(world.getCount(), 1);
		final File area = new File(saveDir, "AR_0003_Dyrford_Store.lvl");
		assertTrue(area.createNewFile());
		new DeserializedPackets(new java.util.ArrayList<>(sold), world.getCount()).reserialize(area);

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());
		assertTrue(importer.overwriteCharacter());

		final DeserializedPackets modified =
			deserialize(new File(importer.saveFile(), "MobileObjects.save"));
		assertFalse("the ring the file brought has an ID of its own", modified.getPackets().stream()
			.anyMatch(property -> ring.equals(((ObjectPersistencePacket) property.obj).ObjectID)));

		final ObjectPersistencePacket elwyn = findByObjectID(modified.getPackets(), PLAYER_GUID);
		final List<String> elwyns = listed(elwyn, "PlayerInventory");
		assertFalse(elwyns.contains(ring));
		assertEquals("and Elwyn's pack still holds as many things as the file had"
			, listed(findByObjectID(deserialize(chrFile).getPackets(), PLAYER_GUID), "PlayerInventory").size()
			, elwyns.size());
	}

	// The GUIDs a character's container lists, in lower case.
	private static List<String> listed (final ObjectPersistencePacket character, final String component) {
		final Optional<uk.me.mantas.eternity.game.ComponentPersistencePacket> found =
			EKUtils.findComponent(character.ComponentPackets, component);
		assertTrue(character.ObjectName + " has no " + component, found.isPresent());

		final List<String> guids = new ArrayList<>();
		for (final java.util.Iterator<?> it = ((uk.me.mantas.eternity.serializer.CSharpCollection)
			found.get().Variables.get("SerializedItemList")).iterator(); it.hasNext();) {

			guids.add(it.next().toString().toLowerCase());
		}

		return guids;
	}

	private static java.util.Set<String> ids (final DeserializedPackets packets) {
		return packets.getPackets().stream()
			.map(property -> ((ObjectPersistencePacket) property.obj).ObjectID.toLowerCase())
			.collect(Collectors.toSet());
	}

	// Every GUID an object's variables name, one level into lists.
	private static List<String> namedIds (final ObjectPersistencePacket packet) {
		final List<String> named = new ArrayList<>();
		for (final uk.me.mantas.eternity.game.ComponentPersistencePacket component : packet.ComponentPackets) {
			if (component == null || component.Variables == null) {
				continue;
			}

			for (final Object value : component.Variables.values()) {
				if (value instanceof uk.me.mantas.eternity.serializer.CSharpCollection) {
					for (final java.util.Iterator<?> it =
						((uk.me.mantas.eternity.serializer.CSharpCollection) value).iterator(); it.hasNext();) {

						addId(named, it.next());
					}
				} else {
					addId(named, value);
				}
			}
		}

		return named;
	}

	private static void addId (final List<String> named, final Object value) {
		if (value instanceof UUID || (value instanceof String
			&& ((String) value).matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))) {

			named.add(value.toString().toLowerCase());
		}
	}

	@Test
	public void detectsMainCharacterConflict ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File chrFile = new File(workingDir, "player.chr");
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, PLAYER_GUID
			, chrFile.getAbsolutePath());
		assertTrue(exporter.export());

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(resourceDirectory()), chrFile.getAbsolutePath());

		final Optional<CharacterImporter.ImportConflict> conflict = importer.detectConflict();
		assertTrue(conflict.isPresent());
		assertTrue(conflict.get().mainCharacter);
		assertEquals("Elwyn", conflict.get().characterName);
	}

	@Test
	public void detectsCompanionConflict ()
		throws IOException
		, URISyntaxException {

		final File chrFile = exportCompanionChr(tempDir());
		final CharacterImporter importer =
			new CharacterImporter(requestJSON(resourceDirectory()), chrFile.getAbsolutePath());

		final Optional<CharacterImporter.ImportConflict> conflict = importer.detectConflict();
		assertTrue(conflict.isPresent());
		assertFalse(conflict.get().mainCharacter);
		assertEquals("Calisca", conflict.get().characterName);
	}

	@Test
	public void noConflictWhenCharacterNotInTargetSave ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File chrFile = exportCompanionChr(workingDir);

		// Build a target save that does not contain the companion.
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		final DeserializedPackets source =
			deserialize(new File(resourceDirectory(), "MobileObjects.save"));

		final String companionName =
			findByObjectID(source.getPackets(), COMPANION_GUID).ObjectName;

		final List<Property> withoutCompanion = source.getPackets().stream()
			.filter(property -> {
				final ObjectPersistencePacket packet =
					(ObjectPersistencePacket) property.obj;
				return !COMPANION_GUID.equals(packet.ObjectID)
					&& !companionName.equals(packet.Parent);
			})
			.collect(Collectors.toList());

		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		assertTrue(mobileObjects.createNewFile());
		Property.update(source.getCount(), withoutCompanion.size());
		source.setPackets(withoutCompanion);
		source.reserialize(mobileObjects);

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertFalse(importer.detectConflict().isPresent());
	}

	@Test
	public void overwriteReplacesExistingCompanion ()
		throws IOException
		, URISyntaxException {

		assertOverwrite(COMPANION_GUID);
	}

	@Test
	public void overwriteReplacesExistingMainCharacter ()
		throws IOException
		, URISyntaxException {

		assertOverwrite(PLAYER_GUID);
	}

	// The game keeps a playthrough's ID on the main character (the Player
	// component's SessionID) and names its autosaves by it. A Watcher brought
	// in from another playthrough carried that one's ID along, and in the game
	// the save's autosaves went into the other playthrough's slots
	// (2026-10-25). The save keeps its own.
	@Test
	public void aMainCharacterFromAnotherPlaythroughJoinsThisOne ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File chrFile = new File(workingDir, "player.chr");
		assertTrue(new CharacterExporter(
			resourceDirectory().getAbsolutePath(), PLAYER_GUID, chrFile.getAbsolutePath()).export());

		// The target is another playthrough: the same save under an ID of its own.
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		final DeserializedPackets world =
			deserialize(new File(resourceDirectory(), "MobileObjects.save"));
		final UUID exported = sessionOf(world);
		final UUID target = UUID.randomUUID();
		assertTrue(Property.update(sessionProperty(world), target));
		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		assertTrue(mobileObjects.createNewFile());
		world.reserialize(mobileObjects);
		assertEquals(target, sessionOf(deserialize(mobileObjects)));

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());
		assertTrue(importer.overwriteCharacter());

		final UUID after = sessionOf(deserialize(new File(importer.saveFile(), "MobileObjects.save")));
		assertNotEquals("not the playthrough the character came from", exported, after);
		assertEquals(target, after);
	}

	// The stash, the quest items and the crafting materials hang off the main
	// character alone, and the money is on their pack, but they are the
	// party's. An overwrite took out everything the old Watcher owned, so a
	// Watcher from another playthrough emptied the save's stash and quest
	// items and brought their own playthrough's, and its money (2026-10-27).
	// The save keeps what is the party's.
	@Test
	public void aWatcherFromAnotherPlaythroughLeavesTheSavesStashQuestItemsAndMoney ()
		throws IOException
		, URISyntaxException {

		final String ring = "c4b7033d-c452-4946-a9ba-9998cb411201";
		final File workingDir = tempDir();

		// The character file comes from another playthrough, where the Watcher
		// has another object name.
		final File exported = new File(workingDir, "exported.chr");
		assertTrue(new CharacterExporter(
			resourceDirectory().getAbsolutePath(), PLAYER_GUID, exported.getAbsolutePath()).export());
		final DeserializedPackets chr = deserialize(exported);
		final String exportedName = findByObjectID(chr.getPackets(), PLAYER_GUID).ObjectName;
		final String newcomerName = "Player_Newcomer";
		for (final Property object : chr.getPackets()) {
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) object.obj;
			if (exportedName.equals(packet.ObjectName)) {
				assertTrue(Property.update(object, "ObjectName", newcomerName));
			}

			if (exportedName.equals(packet.Parent)) {
				assertTrue(Property.update(object, "Parent", newcomerName));
			}
		}

		final File chrFile = new File(workingDir, "newcomer.chr");
		assertTrue(chrFile.createNewFile());
		chr.reserialize(chrFile);

		// Since then, this playthrough has put the ring in its stash and has
		// other money.
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(new File(resourceDirectory(), "MobileObjects.save"), saveDir);
		assertTrue(new uk.me.mantas.eternity.save.InventoryManager(saveDir).apply(
			java.util.Collections.singletonList(new uk.me.mantas.eternity.save.InventoryManager.Change(
				PLAYER_GUID, "PlayerInventory", ring, 1, PLAYER_GUID, "StashInventory", -1))));

		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		final DeserializedPackets world = deserialize(mobileObjects);
		assertTrue(Property.update(currencyProperty(world), "v", 4321f));
		assertTrue(Property.update(campingSuppliesProperty(world), Integer.valueOf(4)));
		world.replace(mobileObjects);

		final DeserializedPackets before = deserialize(mobileObjects);
		final ObjectPersistencePacket watcher = findByObjectID(before.getPackets(), PLAYER_GUID);
		final List<String> stash = listed(watcher, "StashInventory");
		final List<String> quest = listed(watcher, "QuestInventory");
		final List<String> crafting = listed(watcher, "CraftingInventory");
		assertTrue(stash.contains(ring));

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());
		assertTrue(importer.overwriteCharacter());

		final DeserializedPackets after =
			deserialize(new File(importer.saveFile(), "MobileObjects.save"));
		final ObjectPersistencePacket newcomer = findByObjectID(after.getPackets(), PLAYER_GUID);
		assertEquals(newcomerName, newcomer.ObjectName);

		assertEquals("the save's stash", stash, listed(newcomer, "StashInventory"));
		assertEquals("the save's quest items", quest, listed(newcomer, "QuestInventory"));
		assertEquals("the save's crafting materials", crafting, listed(newcomer, "CraftingInventory"));
		for (final List<String> container : java.util.Arrays.asList(stash, quest, crafting)) {
			for (final String id : container) {
				assertEquals(id + " is held by the newcomer"
					, newcomerName, findByObjectID(after.getPackets(), id).Parent);
			}
		}

		assertEquals("the save's money", 4321f, (float) currencyProperty(after).findProperty("v")
			.map(value -> ((Property) value).obj).get(), 0f);
		// The camping supplies sit beside the money: the game showed the
		// prologue's 0 in place of the save's 4 (2026-10-27).
		assertEquals("the save's camping supplies", 4, campingSuppliesProperty(after).obj);

		// The ring the file brings in the newcomer's pack is the file's own copy.
		final List<String> pack = listed(newcomer, "PlayerInventory");
		assertFalse(pack.contains(ring));
		assertEquals(listed(findByObjectID(chr.getPackets(), PLAYER_GUID), "PlayerInventory").size()
			, pack.size());

		final java.util.Map<String, Long> seen = after.getPackets().stream()
			.map(property -> ((ObjectPersistencePacket) property.obj).ObjectID.toLowerCase())
			.collect(Collectors.groupingBy(id -> id, Collectors.counting()));
		assertTrue("no ID twice: " + seen, seen.values().stream().allMatch(n -> n == 1));
		assertEquals(after.getPackets().size(), (int) after.getCount().obj);
	}

	// The main character's money: PlayerInventory.currencyTotalValue.
	private ComplexProperty currencyProperty (final DeserializedPackets world) {
		return playerInventory(world).<ComplexProperty>findEntry("currencyTotalValue").get();
	}

	// PlayerInventory.campingSupplies, an int.
	private Property campingSuppliesProperty (final DeserializedPackets world) {
		return playerInventory(world).<Property>findEntry("campingSupplies").get();
	}

	private uk.me.mantas.eternity.serializer.properties.DictionaryProperty playerInventory (
		final DeserializedPackets world) {

		final Property player = world.getPackets().stream()
			.filter(property -> PLAYER_GUID.equals(((ObjectPersistencePacket) property.obj).ObjectID))
			.findFirst().get();
		final ComplexProperty component = EKUtils.findSubComponent(
			((ComplexProperty) player).<uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty>
				findProperty("ComponentPackets").get(), "PlayerInventory").get();
		return component.<uk.me.mantas.eternity.serializer.properties.DictionaryProperty>
			findProperty("Variables").get();
	}

	private Property sessionProperty (final DeserializedPackets world) {
		final Property player = world.getPackets().stream()
			.filter(property -> PLAYER_GUID.equals(((ObjectPersistencePacket) property.obj).ObjectID))
			.findFirst().get();
		final ComplexProperty component = EKUtils.findSubComponent(
			((ComplexProperty) player).<uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty>
				findProperty("ComponentPackets").get(), "Player").get();
		return component.<uk.me.mantas.eternity.serializer.properties.DictionaryProperty>
			findProperty("Variables").get().<Property>findEntry("SessionID").get();
	}

	private UUID sessionOf (final DeserializedPackets world) {
		return (UUID) sessionProperty(world).obj;
	}

	private void assertOverwrite (final String guid)
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resourceDirectory(), "MobileObjects.save")
			, saveDir);

		final DeserializedPackets original =
			deserialize(new File(saveDir, "MobileObjects.save"));
		final int originalCount = (int) original.getCount().obj;
		final ObjectPersistencePacket existing =
			findByObjectID(original.getPackets(), guid);
		final String objectName = existing.ObjectName;
		final Vector3 originalLocation = existing.Location;
		final String originalLevel = existing.LevelName;

		final File chrFile = new File(workingDir, "subject.chr");
		final CharacterExporter exporter = new CharacterExporter(
			resourceDirectory().getAbsolutePath()
			, guid
			, chrFile.getAbsolutePath());
		assertTrue(exporter.export());

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertTrue(importer.overwriteCharacter());
		assertNotEquals(saveDir.getAbsoluteFile(), importer.saveFile().getAbsoluteFile());

		// Exporting from and overwriting into the same save replaces the
		// character's packets with an identical set so the count is stable.
		final DeserializedPackets modified =
			deserialize(new File(importer.saveFile(), "MobileObjects.save"));

		assertEquals(originalCount, (int) modified.getCount().obj);
		assertEquals(originalCount, modified.getPackets().size());

		final List<ObjectPersistencePacket> matches = modified.getPackets().stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> objectName.equals(packet.ObjectName))
			.collect(Collectors.toList());

		assertEquals(1, matches.size());
		final ObjectPersistencePacket overwritten = matches.get(0);

		// Identity is preserved, no GUID regeneration on overwrite.
		assertEquals(guid, overwritten.ObjectID);

		// The character takes the old character's exact position.
		assertEquals(originalLevel, overwritten.LevelName);
		assertEquals(originalLocation.x, overwritten.Location.x, 1e-4);
		assertEquals(originalLocation.y, overwritten.Location.y, 1e-4);
		assertEquals(originalLocation.z, overwritten.Location.z, 1e-4);
	}

	@Test
	public void importReturnsFalseWhenChrContainsNoCharacter ()
		throws IOException
		, URISyntaxException {

		final File workingDir = tempDir();
		final File saveDir = new File(workingDir, "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFileToDirectory(
			new File(resourceDirectory(), "MobileObjects.save")
			, saveDir);

		// Fabricate a CHR file whose objects are not characters.
		final File chrFile = new File(workingDir, "notACharacter.chr");
		final DeserializedPackets source =
			deserialize(new File(saveDir, "MobileObjects.save"));

		final List<Property> nonCharacters = source.getPackets().stream()
			.filter(property -> {
				final String name =
					((ObjectPersistencePacket) property.obj).ObjectName;
				return name != null
					&& !name.startsWith("Player_")
					&& !name.startsWith("Companion_");
			})
			.limit(1)
			.collect(Collectors.toList());

		assertEquals(1, nonCharacters.size());
		assertTrue(chrFile.createNewFile());
		Property.update(source.getCount(), 1);
		source.setPackets(nonCharacters);
		source.reserialize(chrFile);

		final CharacterImporter importer =
			new CharacterImporter(requestJSON(saveDir), chrFile.getAbsolutePath());

		assertFalse(importer.importCharacter());
	}

	private DeserializedPackets deserialize (final File file)
		throws IOException {

		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(file).deserialize();

		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	private ObjectPersistencePacket findByObjectID (
		final List<Property> packets
		, final String objectID) {

		final Optional<ObjectPersistencePacket> found = packets.stream()
			.map(property -> (ObjectPersistencePacket) property.obj)
			.filter(packet -> objectID.equals(packet.ObjectID))
			.findFirst();

		assertTrue(found.isPresent());
		return found.get();
	}
}
