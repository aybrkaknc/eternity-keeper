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
import uk.me.mantas.eternity.save.AchievementsEnabler;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;
import uk.me.mantas.eternity.save.model.SaveObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.EKUtils.findComponent;

public class AchievementsEnablerTest extends TestHarness {

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File saveDir = new File(workingDir.get(), "cadena 0 Test.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		return saveDir;
	}

	// Marks the save as cheated the way the in-game console does.
	private void enableCheats (final File saveDir) throws IOException {
		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(mobileObjects).deserialize();
		assertTrue(deserialized.isPresent());

		assertTrue(setBoolean(
			deserialized.get().getPackets(), "GameState", "CheatsEnabled", true));
		assertTrue(setBoolean(
			deserialized.get().getPackets(), "AchievementTracker", "m_disableAchievements", true));

		assertTrue(mobileObjects.delete());
		assertTrue(mobileObjects.createNewFile());
		deserialized.get().reserialize(mobileObjects);
	}

	private boolean setBoolean (
		final List<Property> packets
		, final String component
		, final String variable
		, final boolean value) {

		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket) || !(p instanceof ComplexProperty)) {
				continue;
			}

			final Optional<Property> flag = SaveObject.of(p)
				.flatMap(o -> o.component(component))
				.flatMap(c -> c.variable(variable));

			if (flag.isPresent()) {
				return Property.update(flag.get(), value);
			}
		}

		return false;
	}

	private boolean readBoolean (
		final File saveDir, final String component, final String variable) throws IOException {

		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();
		assertTrue(deserialized.isPresent());

		for (final Property p : deserialized.get().getPackets()) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (packet.ComponentPackets == null) continue;

			final Optional<ComponentPersistencePacket> c =
				findComponent(packet.ComponentPackets, component);

			if (c.isPresent() && c.get().Variables != null
				&& c.get().Variables.get(variable) instanceof Boolean) {

				return (Boolean) c.get().Variables.get(variable);
			}
		}

		fail("no " + component + "." + variable + " found");
		return false;
	}

	// Enabling achievements must clear ONLY the achievement gate. The game
	// checks m_disableAchievements alone when awarding achievements, while
	// CheatsEnabled keeps the player's active cheat effects (god mode etc.)
	// working — clearing it would switch those off behind their back.
	@Test
	public void enablingLeavesCheatsEnabledAlone () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		enableCheats(saveDir);

		// Sanity: the save is now marked as cheated.
		assertTrue(readBoolean(saveDir, "GameState", "CheatsEnabled"));
		assertTrue(readBoolean(saveDir, "AchievementTracker", "m_disableAchievements"));

		assertTrue(new AchievementsEnabler(saveDir).enable());

		assertTrue(readBoolean(saveDir, "GameState", "CheatsEnabled"));
		assertFalse(readBoolean(saveDir, "AchievementTracker", "m_disableAchievements"));
	}

	@Test
	public void succeedsOnAlreadyCleanSave () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		assertTrue(new AchievementsEnabler(saveDir).enable());
		assertFalse(readBoolean(saveDir, "AchievementTracker", "m_disableAchievements"));
		assertFalse(readBoolean(saveDir, "GameState", "CheatsEnabled"));
	}

	// The Console tab exposes this as a toggle, so the reverse direction must
	// work too: disabling achievements again — still without touching
	// CheatsEnabled in either direction.
	@Test
	public void togglesBothDirections () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		assertTrue(new AchievementsEnabler(saveDir).set(false));
		assertFalse(readBoolean(saveDir, "GameState", "CheatsEnabled"));
		assertTrue(readBoolean(saveDir, "AchievementTracker", "m_disableAchievements"));

		assertTrue(new AchievementsEnabler(saveDir).set(true));
		assertFalse(readBoolean(saveDir, "GameState", "CheatsEnabled"));
		assertFalse(readBoolean(saveDir, "AchievementTracker", "m_disableAchievements"));
	}
}
