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

import com.google.common.primitives.UnsignedInteger;
import org.apache.commons.io.FileUtils;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.CharacterStats;
import uk.me.mantas.eternity.game.EternityDateTime;
import uk.me.mantas.eternity.game.EternityTimeInterval;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.game.AIController;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.ComponentBuilder;
import uk.me.mantas.eternity.save.model.GuidList;
import uk.me.mantas.eternity.save.model.ItemContainer;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.save.model.TypedValue;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.TypePair;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static uk.me.mantas.eternity.game.UnityEngine.Vector3;

public class SaveModelTest extends TestHarness {
	private static final String PLAYER_GUID = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";

	private File fixture () throws Exception {
		final File copy = new File(EKUtils.createTempDir(PREFIX).get(), "MobileObjects.save");
		FileUtils.copyFile(
			new File(new File(getClass().getResource("/").toURI()), "MobileObjects.save"), copy);
		return copy;
	}

	private static DeserializedPackets read (final File file) throws IOException {
		final Optional<DeserializedPackets> packets = new PacketDeserializer(file).deserialize();
		assertTrue(packets.isPresent());
		return packets.get();
	}

	private SaveModel model () throws Exception {
		return SaveModel.of(read(fixture()));
	}

	@Test
	public void findsThePlayerTheGlobalsAndAnObjectByItsId () throws Exception {
		final SaveModel save = model();

		final SaveObject player = save.player().get();
		assertEquals(PLAYER_GUID, player.id());
		assertEquals("Player_Elwyn", player.name());
		assertTrue(player.isPlayer());

		assertTrue(save.inGameGlobal().get().name().startsWith("InGameGlobal("));
		assertEquals("Global(Clone)", save.worldGlobal().get().name());

		// The save writes GUIDs in whatever case the game formatted them in.
		final SaveObject calisca = save.object(COMPANION_GUID.toUpperCase()).get();
		assertTrue(calisca.isCompanion());
		assertFalse(calisca.isPlayer());
		assertFalse(save.object("00000000-0000-0000-0000-000000000000").isPresent());
	}

	@Test
	public void everyObjectOfTheFileIsListedInItsOrder () throws Exception {
		final DeserializedPackets packets = read(fixture());
		final SaveModel save = SaveModel.of(packets);

		int objects = 0;
		for (final Property packet : packets.getPackets()) {
			if (packet.obj instanceof ObjectPersistencePacket) {
				assertSame(packet, save.objects().get(objects++).property());
			}
		}

		assertEquals(objects, save.objects().size());
	}

	@Test
	public void aComponentIsFoundByItsTypeInAnyCase () throws Exception {
		final SaveObject player = model().player().get();

		assertEquals("PlayerInventory", player.component("playerinventory").get().type());
		assertFalse(player.component("NoSuchComponent").isPresent());
	}

	@Test
	public void aVariableIsReadFromTheTree () throws Exception {
		final Component stats = model().player().get().component("CharacterStats").get();

		assertEquals("Elwyn", stats.get("OverrideName").get());
		assertEquals(1, stats.get("Level").get());
		assertEquals(CharacterStats.Class.Fighter, stats.get("CharacterClass").get());
		assertFalse(stats.get("NoSuchVariable").isPresent());

		// What is not a plain value is handed over whole, to be read further.
		final Component globals = model().inGameGlobal().get().component("GlobalVariables").get();
		assertTrue(globals.variable("m_data").get() instanceof DictionaryProperty);
	}

	@Test
	public void aWriteKeepsTheVariablesTypeAndReachesTheFile () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final Component stats = SaveModel.of(packets).player().get().component("CharacterStats").get();

		stats.set("Level", 4);
		stats.set("BaseMight", "18");
		stats.set("CharacterClass", "Wizard");
		stats.set("OverrideName", "Newcomer");
		packets.replace(file);

		final Component after = SaveModel.of(read(file)).player().get().component("CharacterStats").get();
		assertEquals(4, after.get("Level").get());
		assertEquals(18, after.get("BaseMight").get());
		assertEquals(CharacterStats.Class.Wizard, after.get("CharacterClass").get());
		assertEquals("Newcomer", after.get("OverrideName").get());
	}

	@Test
	public void aValueOfAnotherTypeIsRefused () throws Exception {
		final Component stats = model().player().get().component("CharacterStats").get();

		assertRefused(() -> stats.set("Level", 4.5f), "Level");
		assertRefused(() -> stats.set("Level", "four"), "Level");
		assertRefused(() -> stats.set("CharacterClass", 3), "CharacterClass");
		assertEquals(1, stats.get("Level").get());
	}

	// The page names enum constants; one the game does not have was written
	// as the enum's first constant, a value nobody chose.
	@Test
	public void anEnumConstantTheGameDoesNotHaveIsRefused () throws Exception {
		final Component stats = model().player().get().component("CharacterStats").get();

		assertRefused(() -> stats.set("CharacterClass", "Necromancer"), "Necromancer");
		assertEquals(CharacterStats.Class.Fighter, stats.get("CharacterClass").get());
	}

	@Test
	public void aVariableTheComponentDoesNotHoldIsRefusedNotAdded () throws Exception {
		final Component stats = model().player().get().component("CharacterStats").get();

		assertRefused(() -> stats.set("NoSuchVariable", 1), "NoSuchVariable");
		assertFalse(stats.get("NoSuchVariable").isPresent());
	}

	@Test
	public void anObjectsOwnFieldIsWritten () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveObject player = SaveModel.of(packets).player().get();

		player.set("LevelName", "AR_0001_Dyrford_Village");
		assertEquals("AR_0001_Dyrford_Village", player.get("LevelName").get());
		assertRefused(() -> player.set("NoSuchField", "x"), "NoSuchField");
		packets.replace(file);

		assertEquals("AR_0001_Dyrford_Village"
			, SaveModel.of(read(file)).player().get().get("LevelName").get());
	}

	@Test
	public void anObjectIsMovedToAnAreaAndAPlace () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveModel save = SaveModel.of(packets);
		final SaveObject player = save.player().get();
		final SaveObject calisca = save.object(COMPANION_GUID).get();

		final Vector3 beside = player.location();
		beside.x += 1;
		calisca.moveTo("AR_0001_Dyrford_Village", beside);
		packets.replace(file);

		final SaveObject after = SaveModel.of(read(file)).object(COMPANION_GUID).get();
		assertEquals("AR_0001_Dyrford_Village", after.get("LevelName").get());
		assertEquals(beside.x, after.location().x, 0);
		assertEquals(player.location().y, after.location().y, 0);
		assertEquals(player.location().z, after.location().z, 0);
	}

	// An edit changes the tree; the mirror objects the deserializer also built
	// keep what was read. What the model reads is what the edit wrote.
	@Test
	public void whatIsReadIsWhatWasWrittenNotWhatWasFirstRead () throws Exception {
		final SaveModel save = model();
		final SaveObject calisca = save.object(COMPANION_GUID).get();

		calisca.set("Parent", "Player_Newcomer");
		assertEquals("Player_Newcomer", calisca.parent());
		assertNotEquals("Player_Newcomer", ((ObjectPersistencePacket) calisca.property().obj).Parent);
	}

	@Test
	public void aGlobalVariableIsReadAndWrittenAsTheTypeItHolds () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveModel save = SaveModel.of(packets);

		final Object before = save.globalVariable("nDuranceQuestState").get();
		assertTrue(before instanceof Integer);
		assertFalse(save.globalVariable("no_such_global").isPresent());

		save.setGlobalVariable("nDuranceQuestState", 8);
		assertRefused(() -> save.setGlobalVariable("nDuranceQuestState", true), "nDuranceQuestState");
		assertRefused(() -> save.setGlobalVariable("no_such_global", 1), "no_such_global");
		packets.replace(file);

		assertEquals(8, SaveModel.of(read(file)).globalVariable("nDuranceQuestState").get());
	}

	// Real saves hold packets with no ObjectName, and the odd entry that is
	// not a game object at all. An object is known by the tree alone: one the
	// editor built has no mirror to go by.
	@Test
	public void objectsWithNoNameAndEntriesThatAreNoObjectsAreTolerated () throws Exception {
		final ComplexProperty garbage = new ComplexProperty("Root", null);
		garbage.obj = new ObjectPersistencePacket();
		final ComplexProperty noComponents =
			new ComplexProperty("Root", new TypePair(ObjectPersistencePacket.class, null));
		noComponents.properties.add(field("ObjectID", "no-components"));
		final List<Property> packets = Arrays.asList(
			packet(null, "some-guid"), garbage, noComponents
			, packet("Companion_Test(Clone)", "companion-guid"), packet("Player_Test(Clone)", "player-guid"));

		final SaveModel save = SaveModel.of(packets);
		assertEquals(3, save.objects().size());
		assertFalse(save.objects().get(0).isPlayer());
		assertFalse(save.objects().get(0).isCompanion());
		assertEquals("player-guid", save.player().get().id());
		assertFalse(SaveObject.of(garbage).isPresent());
		assertFalse(SaveObject.of(noComponents).isPresent());
		assertEquals("companion-guid", SaveObject.of(packets.get(3)).get().id());
		assertFalse(save.inGameGlobal().isPresent());
	}

	private static ComplexProperty packet (final String name, final String id) {
		final ComplexProperty packet =
			new ComplexProperty("Root", new TypePair(ObjectPersistencePacket.class, null));
		packet.properties.add(field("ObjectName", name));
		packet.properties.add(field("ObjectID", id));
		packet.properties.add(new SingleDimensionalArrayProperty("ComponentPackets", null));
		return packet;
	}

	// The model is the file's own list: what it adds and removes is what the
	// file holds, and what is put in the list some other way is seen.
	@Test
	public void objectsAreAddedToAndRemovedFromTheFilesOwnList () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveModel save = SaveModel.of(packets);
		final int before = packets.getPackets().size();

		final SaveObject calisca = save.object(COMPANION_GUID).get();
		save.remove(calisca);
		assertEquals(before - 1, packets.getPackets().size());
		assertFalse(save.object(COMPANION_GUID).isPresent());
		assertRefused(() -> save.remove(calisca), "Companion_Calisca");

		save.add(calisca);
		assertSame(calisca.property(), packets.getPackets().get(before - 1));
		assertTrue(save.object(COMPANION_GUID).isPresent());
		assertRefused(() -> save.add(calisca), COMPANION_GUID);

		final ComplexProperty newcomer = packet("Newcomer", "newcomer-guid");
		packets.getPackets().add(newcomer);
		assertSame(newcomer, save.object("NEWCOMER-GUID").get().property());
		save.remove(save.object("newcomer-guid").get());

		save.remove(save.player().get());
		packets.replace(file);
		final DeserializedPackets after = read(file);
		assertEquals(before - 1, after.getPackets().size());
		assertFalse(SaveModel.of(after).player().isPresent());
	}

	private static SimpleProperty field (final String name, final Object value) {
		final SimpleProperty field = new SimpleProperty(name, null);
		field.value = value;
		field.obj = value;
		return field;
	}

	@Test
	public void aComponentIsReplacedWhereItStands () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveModel save = SaveModel.of(packets);
		final SaveObject player = save.player().get();
		final SaveObject calisca = save.object(COMPANION_GUID).get();
		final List<String> types = types(player);

		final Component theirs = calisca.component("CharacterStats").get();
		player.replace(player.component("CharacterStats").get(), theirs);

		assertEquals(types, types(player));
		assertSame(theirs.property(), player.component("CharacterStats").get().property());

		try {
			player.replace(calisca.component("Health").get(), theirs);
			fail("replaced a component the player does not have");
		} catch (final WriteRefusedException refused) {
			assertTrue(refused.getMessage(), refused.getMessage().contains("Health"));
		}
	}

	@Test
	public void componentsAreRemovedAndAddedAtTheEnd () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveModel save = SaveModel.of(packets);
		final SaveObject player = save.player().get();
		final SaveObject calisca = save.object(COMPANION_GUID).get();
		final List<String> types = types(player);

		final Component ai = player.component("PartyMemberAI").get();
		player.remove(ai);
		assertFalse(player.component("PartyMemberAI").isPresent());
		assertEquals(types.size() - 1, player.components().size());

		player.add(ai.property());
		final List<String> after = types(player);
		assertEquals("PartyMemberAI", after.get(after.size() - 1));
		assertEquals(types.size(), after.size());

		assertRefused(() -> player.remove(calisca.component("Health").get()), "Health");

		final ComplexProperty broken = packet("Bare", "1");
		broken.properties.set(2, field("ComponentPackets", null));
		final SaveObject bare = SaveObject.of(broken).get();
		assertRefused(() -> bare.add(ai.property()), "Bare");
	}

	private static final String RING_GUID = "c4b7033d-c452-4946-a9ba-9998cb411201";
	private static final String PET_GUID = "41baf584-9203-44df-a921-62f50000ad74";

	private static int capacity (final GuidList list) {
		return capacity(list.property());
	}

	private static int capacity (final CollectionProperty list) {
		for (final Property field : list.properties) {
			if ("Capacity".equals(field.name)) {
				return (Integer) ((SimpleProperty) field).value;
			}
		}

		throw new AssertionError("no Capacity");
	}

	@Test
	public void aGuidListMatchesInAnyCaseAndWritesRealGuids () throws Exception {
		final SaveModel save = model();
		final GuidList slots = save.player().get().component("Equipment").get()
			.guidList("EquipmentSetSerialized").get();
		final int size = slots.size();

		slots.set(0, RING_GUID.toUpperCase());
		assertEquals(0, slots.indexOf(RING_GUID));
		assertTrue(slots.contains(RING_GUID.toUpperCase()));
		assertTrue(((SimpleProperty) slots.property().items.get(0)).value instanceof UUID);

		slots.set(0, GuidList.EMPTY);
		assertEquals("", slots.get(0));
		assertEquals(-1, slots.indexOf(RING_GUID));

		assertRefused(() -> slots.set(size, RING_GUID), "slot " + size);
		assertRefused(() -> slots.set(0, "not a guid"), "not a guid");
		assertRefused(() -> slots.add("not a guid"), "not a guid");
		assertEquals(size, slots.size());
	}

	// The game never writes a list's Capacity below its count; an add that
	// passes it raises it, and one that does not leaves it alone.
	@Test
	public void anAddRaisesCapacityOnlyPastIt () throws Exception {
		final SaveModel save = model();
		final GuidList guids = save.player().get().component("PlayerInventory").get()
			.guidList("SerializedItemList").get();
		final int before = capacity(guids);
		assertTrue(before >= guids.size());

		while (guids.size() < before) {
			guids.add(UUID.randomUUID().toString());
		}

		assertEquals(before, capacity(guids));
		guids.add(UUID.randomUUID().toString());
		assertEquals(before + 1, capacity(guids));

		final String last = guids.get(guids.size() - 1);
		assertTrue(guids.remove(last.toUpperCase()));
		assertFalse(guids.contains(last));
		assertEquals(before + 1, capacity(guids));
	}

	@Test
	public void aContainerKeepsItsTwoListsInStep () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveModel save = SaveModel.of(packets);
		final ItemContainer pack = save.player().get().component("PlayerInventory").get().itemContainer().get();
		final ItemContainer hers = save.object(COMPANION_GUID).get().component("Inventory").get().itemContainer().get();
		assertEquals(2, pack.size());
		assertEquals(0, hers.size());
		assertEquals(Optional.of(16), hers.maxItems());

		final int pet = pack.indexOf(PET_GUID.toUpperCase());
		assertTrue(pet >= 0);
		final ItemContainer.Entry moved = pack.moveTo(hers, pet, save);
		assertEquals(1, pack.size());
		assertTrue(pack.paired());
		assertEquals(0, hers.indexOf(PET_GUID));
		assertSame(moved.property(), hers.entry(0).property());
		assertEquals(save.object(COMPANION_GUID).get().name(), save.object(PET_GUID).get().parent());

		moved.setStackSize(3);
		moved.setSlot(hers.freeSlot(-1, 0));
		assertEquals(3, hers.entry(0).stackSize());
		assertEquals(Optional.of(0), hers.entry(0).slot());
		assertRefused(() -> hers.remove(1), "no item 1");

		final ItemContainer.Entry made = ItemContainer.newEntry(save, "Assets/Data/Prefabs/Items/Foo.prefab", 5, 2);
		hers.add(made, RING_GUID);
		assertEquals(Optional.of("Assets/Data/Prefabs/Items/Foo.prefab"), hers.entry(1).baseItem());
		assertEquals(2, hers.entry(1).stackSize());
		hers.renumberSlots();
		assertEquals(Optional.of(1), hers.entry(1).slot());
		hers.remove(1);
		assertTrue(hers.paired());

		pack.remove(pack.indexOf(RING_GUID));
		save.removeUnlisted(Arrays.asList(RING_GUID));
		packets.replace(file);
		final SaveModel after = SaveModel.of(read(file));
		assertEquals(0, after.player().get().component("PlayerInventory").get().itemContainer().get().size());
		assertEquals(3, after.object(COMPANION_GUID).get().component("Inventory").get()
			.itemContainer().get().entry(0).stackSize());
	}

	// An item's object goes only when nothing in the file names it any more.
	@Test
	public void anItemsObjectGoesOnlyWhenNoListNamesIt () throws Exception {
		final SaveModel save = model();
		final ItemContainer pack = save.player().get().component("PlayerInventory").get().itemContainer().get();
		assertTrue(save.listedGuids().contains(RING_GUID));

		assertEquals(0, save.removeUnlisted(Arrays.asList(RING_GUID, PET_GUID)));
		pack.remove(pack.indexOf(RING_GUID.toUpperCase()));
		assertEquals(1, save.removeUnlisted(Arrays.asList(RING_GUID.toUpperCase(), PET_GUID)));
		assertFalse(save.object(RING_GUID).isPresent());
		assertTrue(save.object(PET_GUID).isPresent());
	}

	@Test
	public void aMintedObjectIsANewCopyInTheFile () throws Exception {
		final File file = fixture();
		final DeserializedPackets packets = read(file);
		final SaveModel save = SaveModel.of(packets);
		final SaveObject ring = save.object(RING_GUID).get();
		final UUID guid = UUID.randomUUID();
		final int before = packets.getPackets().size();

		final SaveObject minted = save.mint(ring
			, new SaveModel.Identity("Ring_New(Clone)", guid.toString(), guid, "Assets/Ring_New.prefab"
				, "Player_Elwyn", null)
			, (type, component) -> "InstanceID".equals(type) || "Persistence".equals(type)
			, (type, copy) -> {});

		assertEquals(before + 1, packets.getPackets().size());
		assertSame(minted.property(), save.object(guid.toString()).get().property());
		assertEquals("Player_Elwyn", minted.parent());
		assertEquals(ring.levelName(), minted.levelName());
		assertEquals(guid, minted.component("InstanceID").get().get("Guid").get());
		assertNotEquals(guid, ring.component("InstanceID").get().get("Guid").get());
		assertEquals(2, minted.components().size());
		assertTrue(minted.property().obj instanceof ObjectPersistencePacket);

		assertRefused(() -> save.mint(ring
			, new SaveModel.Identity("Again", guid.toString(), guid, "", "", null)
			, (type, component) -> true, (type, copy) -> {}), guid.toString());
		assertRefused(() -> save.mint(ring
			, new SaveModel.Identity("None", "x", UUID.randomUUID(), "", "", null)
			, (type, component) -> false, (type, copy) -> {}), "no component");

		packets.replace(file);
		assertTrue(SaveModel.of(read(file)).object(guid.toString()).isPresent());
	}

	@Test
	public void aBuiltComponentReadsAsTheGameWritesIt () throws Exception {
		final SaveModel save = model();
		final SaveObject player = save.player().get();
		final UUID summoner = UUID.randomUUID();
		player.add(ComponentBuilder.of("Built")
			.integer("Number", 3)
			.bool("Flag", true)
			.text("Name", "x")
			.guid("Who", summoner)
			.guid("Nobody", null)
			.constant("Kind", AIController.AISummonType.Summoned, "AIController+AISummonType, Assembly-CSharp")
			.emptyGuidList("Attached")
			.build());

		final Component built = player.component("Built").get();
		assertEquals(3, built.get("Number").get());
		assertEquals(true, built.get("Flag").get());
		assertEquals(summoner, built.get("Who").get());
		assertEquals(AIController.AISummonType.Summoned, built.get("Kind").get());
		assertEquals(0, built.guidList("Attached").get().size());
		assertEquals(0, capacity(built.guidList("Attached").get()));
		built.set("Number", 4);
		assertEquals(4, built.get("Number").get());
	}

	private static List<String> types (final SaveObject object) {
		final List<String> types = new ArrayList<>();
		for (final Component component : object.components()) {
			types.add(component.type());
		}

		return types;
	}

	@Test
	public void textBecomesTheTypeTheSaveHolds () throws Exception {
		assertEquals(2, TypedValue.from(1, "2"));
		assertEquals(3.14159, (double) TypedValue.from(1d, "3.14159"), 1e-6);
		assertEquals(2.5f, (float) TypedValue.from(1f, "2.5"), 1e-6);
		assertEquals(false, TypedValue.from(true, "false"));
		assertEquals(2147483648L
			, ((UnsignedInteger) TypedValue.from(UnsignedInteger.valueOf(1), "2147483648")).longValue());
		assertEquals(42, TypedValue.from(new EternityDateTime(), "42"));
		assertEquals(42, TypedValue.from(new EternityTimeInterval(), "42"));
		assertEquals(CharacterStats.Class.Wizard, TypedValue.from(CharacterStats.Class.Fighter, "Wizard"));
		assertEquals("text", TypedValue.from("old", "text"));

		try {
			TypedValue.from(true, "yes");
			fail("'yes' was taken for a boolean");
		} catch (final WriteRefusedException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("yes"));
		}
	}

	@FunctionalInterface
	private interface Write {
		void run () throws WriteRefusedException;
	}

	private static void assertRefused (final Write write, final String named) {
		try {
			write.run();
			fail("written: " + named);
		} catch (final WriteRefusedException refused) {
			assertTrue(refused.getMessage(), refused.getMessage().contains(named));
		}
	}
}
