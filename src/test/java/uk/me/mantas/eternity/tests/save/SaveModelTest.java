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
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.save.model.TypedValue;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
	// not a game object at all.
	@Test
	public void objectsWithNoNameAndEntriesThatAreNoObjectsAreTolerated () throws Exception {
		final ComplexProperty garbage = new ComplexProperty("Root", null);
		garbage.obj = "not a packet";
		final List<Property> packets = Arrays.asList(
			packet(null, "some-guid"), garbage
			, packet("Companion_Test(Clone)", "companion-guid"), packet("Player_Test(Clone)", "player-guid"));

		final SaveModel save = SaveModel.of(packets);
		assertEquals(3, save.objects().size());
		assertFalse(save.objects().get(0).isPlayer());
		assertFalse(save.objects().get(0).isCompanion());
		assertEquals("player-guid", save.player().get().id());
		assertFalse(SaveObject.of(garbage).isPresent());
		assertEquals("companion-guid", SaveObject.of(packets.get(2)).get().id());
		assertFalse(save.inGameGlobal().isPresent());
	}

	private static Property packet (final String name, final String id) {
		final ComplexProperty packet = new ComplexProperty("Root", null);
		packet.obj = new ObjectPersistencePacket();
		packet.properties.add(field("ObjectName", name));
		packet.properties.add(field("ObjectID", id));
		return packet;
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
