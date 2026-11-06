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
import org.cef.callback.CefQueryCallback;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.SavedGameOpener;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

// A companion who died in-game has NO mobile object left in the save — only
// their b_X_Dead global set to 1. The opener must surface them anyway so the
// editor can offer resurrection.
public class DeadCompanionsTest extends TestHarness {

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File saveDir = new File(workingDir.get(), "cadena 0 Test.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(new File(resources, "MobileObjects.save"), saveDir);
		return saveDir;
	}

	// Sets a global variable in InGameGlobal/GlobalVariables.m_data through
	// the write model and rewrites the file. Shared with ResurrectorTest.
	public static void setGlobalFlag (final File saveDir, final String flag, final int value)
		throws IOException {

		final File mobileObjectsFile = new File(saveDir, "MobileObjects.save");
		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(mobileObjectsFile).deserialize();
		assertTrue(deserialized.isPresent());

		boolean updated = false;
		for (final Property p : deserialized.get().getPackets()) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (packet.ObjectName == null || !packet.ObjectName.startsWith("InGameGlobal")) {
				continue;
			}

			final Optional<Property> entry = SaveObject.of(p)
				.flatMap(o -> o.component("GlobalVariables"))
				.flatMap(g -> g.variable("m_data"))
				.flatMap(d -> ((DictionaryProperty) d).findEntry(flag));

			assertTrue("flag " + flag + " not present in fixture globals", entry.isPresent());
			assertTrue(Property.update(entry.get(), value));
			updated = true;
		}

		assertTrue(updated);
		assertTrue(mobileObjectsFile.delete());
		assertTrue(mobileObjectsFile.createNewFile());
		deserialized.get().reserialize(mobileObjectsFile);
	}

	private JSONObject open (final File saveDir) {
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());

		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final AtomicReference<String> response = new AtomicReference<>();
		doAnswer(invocation -> {
			response.set(invocation.getArgument(0));
			return null;
		}).when(mockCallback).success(anyString());

		new SavedGameOpener(saveDir.getAbsolutePath(), mockCallback).run();
		assertNotNull(response.get());
		return new JSONObject(response.get());
	}

	private Optional<JSONObject> findCharacter (final JSONObject response, final String guid) {
		final JSONArray characters = response.getJSONArray("characters");
		for (int i = 0; i < characters.length(); i++) {
			final JSONObject character = characters.getJSONObject(i);
			if (guid.equals(character.getString("GUID"))) {
				return Optional.of(character);
			}
		}

		return Optional.empty();
	}

	@Test
	public void listsDeadCompanionWhenFlagSetAndObjectAbsent ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		setGlobalFlag(saveDir, "b_Eder_Dead", 1);

		final JSONObject response = open(saveDir);
		final Optional<JSONObject> eder = findCharacter(response, "dead:Eder");

		assertTrue(eder.isPresent());
		assertEquals("Eder", eder.get().getString("name"));
		assertTrue(eder.get().getBoolean("isDead"));
		assertTrue(eder.get().getBoolean("resurrectable"));
		assertTrue(eder.get().getBoolean("isCompanion"));
		assertFalse(eder.get().getBoolean("isMainCharacter"));

		// The living are untouched.
		final Optional<JSONObject> calisca =
			findCharacter(response, "b1a7e809-0000-0000-0000-000000000000");
		assertTrue(calisca.isPresent());
		assertFalse(calisca.get().getBoolean("isDead"));
	}

	@Test
	public void noDeadEntriesWhenNoFlagsSet () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		final JSONObject response = open(saveDir);

		final JSONArray characters = response.getJSONArray("characters");
		for (int i = 0; i < characters.length(); i++) {
			assertFalse(characters.getJSONObject(i).getString("GUID").startsWith("dead:"));
		}
	}
}
