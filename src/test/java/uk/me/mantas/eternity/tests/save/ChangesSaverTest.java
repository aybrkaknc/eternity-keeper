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
import org.apache.commons.io.FileUtils;
import org.cef.callback.CefQueryCallback;
import org.joox.Match;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.WorkingSave;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.factory.SharpSerializerFactory;
import uk.me.mantas.eternity.game.*;
import uk.me.mantas.eternity.save.ChangesSaver;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SharpSerializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.*;
import uk.me.mantas.eternity.tests.ExposedClass;
import uk.me.mantas.eternity.tests.TestHarness;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.*;

import static org.joox.JOOX.$;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.me.mantas.eternity.EKUtils.findComponent;
import static uk.me.mantas.eternity.EKUtils.unwrapPacket;

public class ChangesSaverTest extends TestHarness {
	@Test
	public void errorParsingRequest () {
		CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		ChangesSaver cls = new ChangesSaver("{}", mockCallback);

		cls.run();
		verify(mockCallback).failure(
			-1
			, "{\"error\":\"Error parsing JSON request.\"}");
	}

	@Test
	public void inconsistentClientServerState ()
		throws NoSuchFieldException
		, IllegalAccessException {

		Environment mockEnvironment = mockEnvironment();
		CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		String request = "{"
			+ "\"savedYet\":true"
			+ ",\"saveName\":\"\""
			+ ",\"absolutePath\":\"/. \""
			+ ",\"saveData\":{"
				+ "\"characters\":[]"
				+ ",\"currency\":0"
			+ "}"
		+ "}";

		ChangesSaver cls = new ChangesSaver(request, mockCallback);
		mockEnvironment.state().workingSave().written(new File("/404"));

		cls.run();
		verify(mockCallback).failure(
			eq(-1)
			, argThat((String error) ->
				error.startsWith("{\"error\":\"IO Error: ")
				&& error.contains("saveinfo.xml")));
	}

	@Test
	@SuppressWarnings("OptionalGetWithoutIsPresent")
	public void changesSaved ()
		throws NoSuchFieldException
		, IllegalAccessException
		, URISyntaxException
		, IOException {

		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		String request = "{"
			+ "\"savedYet\":false"
			+ ",\"saveName\":\"TEST\""
			+ ",\"absolutePath\":\"%s\""
			+ ",\"saveData\":{"
				+ "\"characters\":[{"
					+ "\"GUID\":\"b1a7e809-0000-0000-0000-000000000000\""
					+ ", \"stats\":{\"BaseMight\":{"
						+ "\"type\":\"java.lang.Integer\",\"value\":\"30\"}}}, {"
					+ "\"GUID\":\"09517a0d-4fec-407c-a749-a531f3be64e0\""
					+ ", \"stats\":{\"BaseResolve\":{"
							+ "\"type\":\"java.lang.Integer\",\"value\":\"50\"}}}]"
				+ ", \"currency\":3.14159"
				+ ", \"globals\":{"
					+ "\"Global\":{\"GameState\":{\"Difficulty\":{"
						+ "\"type\":\"uk.me.mantas.eternity.game.GameDifficulty\""
						+ ", \"value\":\"StoryTime\"}"
						+ ", \"TrialOfIron\":{\"type\":\"java.lang.Boolean\",\"value\":\"true\"}}}"
					+ ", \"InGameGlobal\":{\"GlobalVariables\":{"
						+ "\"n_Eder_Cipher\":{\"type\":\"java.lang.Integer\",\"value\":\"50\"}"
						+ ",\"b_gramrfel_prisoner\":{"
							+ "\"type\":\"java.lang.Integer\""
							+ ",\"value\":\"-1\"}}}}}}";

		final String absolutePath =
			new File(
				getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI())
			.getAbsolutePath();

		mockSettings.json = mockJSON;
		request = String.format(request, absolutePath.replace("\\", "\\\\"));
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);

		doThrow(new JSONException("")).when(mockJSON).getString(anyString());

		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());

		final File saveDirectory = new File(workingDirectory, "id 0 Encampment.savegame");
		final ChangesSaver cls = new ChangesSaver(request, mockCallback);

		cls.run();
		verify(mockCallback).success("{\"success\":true}");
		assertEquals(saveDirectory, mockEnvironment.state().workingSave().written());
		assertTrue(new File(savesLocation, saveDirectory.getName()).exists());

		final byte[] saveinfoBytes =
			FileUtils.readFileToByteArray(new File(saveDirectory, "saveinfo.xml"));

		assertEquals(-17, saveinfoBytes[0]);
		final Match xml = $(new String(EKUtils.removeBOM(saveinfoBytes), "UTF-8"));
		assertEquals("TEST", xml.find("Simple[name='UserSaveName']").attr("value"));

		// The load list reads these from saveinfo.xml, so they follow the edit --
		// in the game's own spelling: .NET writes a Boolean as True or False,
		// and the editor's copy arrives as true.
		assertEquals("StoryTime", xml.find("Simple[name='Difficulty']").attr("value"));
		assertEquals("True", xml.find("Simple[name='TrialOfIron']").attr("value"));

		final File mobileObjectsFile = new File(saveDirectory, "MobileObjects.save");
		final SharpSerializer deserializer =
			new SharpSerializer(mobileObjectsFile.getAbsolutePath());
		final Optional<Property> objectCountProp = deserializer.deserialize();
		assertTrue(objectCountProp.isPresent());

		final int objectCount = (int) objectCountProp.get().obj;
		boolean mightUpdated = false;
		boolean resolveUpdated = false;
		boolean currencyUpdated = false;
		boolean difficultyUpdated = false;
		boolean globalVariablesUpdated = false;

		for (int i = 0; i < objectCount; i++) {
			final Optional<Property> property = deserializer.deserialize();
			assertTrue(property.isPresent());
			final ObjectPersistencePacket packet = unwrapPacket(property.get());

			if (!packet.ObjectID.equals("b1a7e809-0000-0000-0000-000000000000")
				&& !packet.ObjectID.equals("09517a0d-4fec-407c-a749-a531f3be64e0")
				&& !packet.ObjectName.equals("Global(Clone)")
				&& !packet.ObjectName.equals("InGameGlobal(Clone)")) {

				continue;
			}

			final Optional<ComponentPersistencePacket> stats =
				findComponent(packet.ComponentPackets, "CharacterStats");

			if (stats.isPresent()) {
				final Map<String, Object> vars = stats.get().Variables;

				if (packet.ObjectID.equals("b1a7e809-0000-0000-0000-000000000000")) {
					mightUpdated = (int) vars.get("BaseMight") == 30;
				}

				if (packet.ObjectID.equals("09517a0d-4fec-407c-a749-a531f3be64e0")) {
					resolveUpdated = (int) vars.get("BaseResolve") == 50;

					final ComponentPersistencePacket inventory =
						findComponent(packet.ComponentPackets, "PlayerInventory").get();

					final CurrencyValue currency =
						(CurrencyValue) inventory.Variables.get("currencyTotalValue");

					currencyUpdated = currency.v == 3.14159f;
				}
			}

			if (packet.ObjectName.equals("Global(Clone)")) {
				final ComponentPersistencePacket gameState =
					findComponent(packet.ComponentPackets, "GameState").get();
				final Map<String, Object> vars = gameState.Variables;
				difficultyUpdated = vars.get("Difficulty").equals(GameDifficulty.StoryTime);
			}

			if (packet.ObjectName.equals("InGameGlobal(Clone)")) {
				final ComponentPersistencePacket globalVariables =
					findComponent(packet.ComponentPackets, "GlobalVariables").get();
				@SuppressWarnings("unchecked")
				final Hashtable<String, Integer> data =
					(Hashtable<String, Integer>) globalVariables.Variables.get("m_data");
				globalVariablesUpdated =
					data.get("n_Eder_Cipher") == 50 && data.get("b_gramrfel_prisoner") == -1;
			}
		}

		assertTrue(
			mightUpdated
			&& resolveUpdated
			&& currencyUpdated
			&& difficultyUpdated
			&& globalVariablesUpdated);
	}

	@Test
	public void aPortraitChangeIsWrittenToThePortraitComponent ()
		throws NoSuchFieldException
		, IllegalAccessException
		, URISyntaxException
		, IOException {

		// A portrait is two plain strings on the Portrait component --
		// m_textureLargePath and m_textureSmallPath, relative to
		// PillarsOfEternity_Data. Nothing derives them on load unless they are
		// empty, so writing them is the whole edit; they ride the same scalar
		// path as the character's stats rather than needing a manager.
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		final String large = "data/art/gui/portraits/player/male/male_elf_01_lg.png";
		final String small = "data/art/gui/portraits/player/male/male_elf_01_sm.png";

		String request = "{"
			+ "\"savedYet\":false"
			+ ",\"saveName\":\"PORTRAIT\""
			+ ",\"absolutePath\":\"%s\""
			+ ",\"saveData\":{"
				+ "\"characters\":[{"
					+ "\"GUID\":\"b1a7e809-0000-0000-0000-000000000000\""
					+ ", \"stats\":{}"
					+ ", \"portraitPaths\":{"
						+ "\"m_textureLargePath\":{"
							+ "\"type\":\"java.lang.String\",\"value\":\"" + large + "\"}"
						+ ",\"m_textureSmallPath\":{"
							+ "\"type\":\"java.lang.String\",\"value\":\"" + small + "\"}}}]"
				+ ", \"currency\":1.0"
				+ ", \"globals\":{\"Global\":{},\"InGameGlobal\":{}}}}";

		final String absolutePath =
			new File(
				getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI())
			.getAbsolutePath();

		mockSettings.json = mockJSON;
		request = String.format(request, absolutePath.replace("\\", "\\\\"));
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());

		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());

		new ChangesSaver(request, mockCallback).run();
		verify(mockCallback).success("{\"success\":true}");

		final File saveDirectory = new File(workingDirectory, "id 0 Encampment.savegame");
		final SharpSerializer deserializer = new SharpSerializer(
			new File(saveDirectory, "MobileObjects.save").getAbsolutePath());

		final Optional<Property> objectCountProp = deserializer.deserialize();
		assertTrue(objectCountProp.isPresent());

		boolean written = false;
		final int objectCount = (int) objectCountProp.get().obj;
		for (int i = 0; i < objectCount; i++) {
			final Optional<Property> property = deserializer.deserialize();
			assertTrue(property.isPresent());

			final ObjectPersistencePacket packet = unwrapPacket(property.get());
			if (!"b1a7e809-0000-0000-0000-000000000000".equals(packet.ObjectID)) {
				continue;
			}

			final Map<String, Object> variables =
				findComponent(packet.ComponentPackets, "Portrait").get().Variables;

			assertEquals(large, variables.get("m_textureLargePath"));
			assertEquals(small, variables.get("m_textureSmallPath"));
			written = true;
		}

		assertTrue("the portrait component was never reached", written);
	}

	/**
	 * Healing and resupplying ride Save as the money does. The heal is the
	 * game's own flag, {@code Health.m_needs_current_values}: the first frame
	 * after a load answers it by filling health and stamina to the maximum the
	 * game works out itself, so the editor never has to. It is set only on the
	 * characters the request names. Camping supplies are one number on the
	 * player's inventory.
	 */
	@Test
	public void aHealAndRefilledCampingSuppliesAreWritten () throws Exception {
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());
		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());

		// The 2015 fixture's world state predates the flag, so this one carries
		// a current game's: the same party, under the same IDs.
		final File opened = new File(EKUtils.createTempDir(PREFIX).get(), "id 0 Encampment.savegame");
		FileUtils.copyDirectory(new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI()), opened);
		FileUtils.copyFile(new File(getClass().getResource("/MobileObjects.save").toURI())
			, new File(opened, "MobileObjects.save"));

		final String calisca = "b1a7e809-0000-0000-0000-000000000000";
		final String request = new JSONObject()
			.put("savedYet", false)
			.put("saveName", "RESTED")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", new org.json.JSONArray().put(new JSONObject()
					.put("GUID", calisca)
					.put("stats", new JSONObject())
					.put("health", new JSONObject().put("m_needs_current_values", new JSONObject()
						.put("type", "java.lang.Boolean").put("value", "true")))))
				.put("currency", 1.0)
				.put("campingSupplies", 4)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject())
					.put("InGameGlobal", new JSONObject())))
			.toString();

		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		new ChangesSaver(request, mockCallback).run();
		verify(mockCallback).success("{\"success\":true}");

		final File written =
			new File(new File(workingDirectory, "id 0 Encampment.savegame"), "MobileObjects.save");
		final Map<String, ObjectPersistencePacket> byId = new HashMap<>();
		for (final Property property : new PacketDeserializer(written).deserialize().get().getPackets()) {
			final ObjectPersistencePacket packet = unwrapPacket(property);
			byId.put(packet.ObjectID, packet);
		}

		final ObjectPersistencePacket elwyn = byId.get("09517a0d-4fec-407c-a749-a531f3be64e0");
		assertEquals(Boolean.TRUE, findComponent(byId.get(calisca).ComponentPackets, "Health")
			.get().Variables.get("m_needs_current_values"));
		assertEquals("only the characters the request names"
			, Boolean.FALSE, findComponent(elwyn.ComponentPackets, "Health")
				.get().Variables.get("m_needs_current_values"));
		assertEquals(4, findComponent(elwyn.ComponentPackets, "PlayerInventory")
			.get().Variables.get("campingSupplies"));
	}

	/**
	 * The game's load list draws a save's party from 0.png, 1.png… inside it,
	 * so a Save that changes a portrait redraws them in slot order: Elenor
	 * (slot 0) with her own, Calisca (slot 1, the character this request edits)
	 * with the portrait just picked for her.
	 */
	@Test
	public void theLoadListThumbnailsShowTheSavedParty () throws Exception {
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());
		when(mockEnvironment.config().pillarsDataDirectory()).thenReturn("PillarsOfEternity_Data");

		final String small = "data/art/gui/portraits/player/male/male_elf_01_sm.png";
		final File install = EKUtils.createTempDir(PREFIX).get();
		final File data = new File(install, "PillarsOfEternity_Data");
		solidPortrait(new File(data, small), Color.GREEN);
		solidPortrait(new File(data, "data/art/gui/portraits/player/female/female_human_03_sm.png"), Color.RED);
		solidPortrait(new File(data, "data/art/gui/portraits/companion/portrait_calisca_sm.png"), Color.BLUE);

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());
		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());
		when(mockJSON.optString(eq("gameLocation"), anyString()))
			.thenReturn(install.getAbsolutePath());

		final String absolutePath = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI())
			.getAbsolutePath();

		final String request = String.format("{"
			+ "\"savedYet\":false, \"saveName\":\"THUMBNAILS\", \"absolutePath\":\"%s\""
			+ ", \"saveData\":{\"characters\":[{"
				+ "\"GUID\":\"b1a7e809-0000-0000-0000-000000000000\", \"stats\":{}"
				+ ", \"portraitPaths\":{\"m_textureSmallPath\":{"
					+ "\"type\":\"java.lang.String\",\"value\":\"" + small + "\"}}}]"
			+ ", \"currency\":1.0, \"globals\":{\"Global\":{},\"InGameGlobal\":{}}}}"
			, absolutePath.replace("\\", "\\\\"));

		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		new ChangesSaver(request, mockCallback).run();
		verify(mockCallback).success("{\"success\":true}");

		final File written = new File(savesLocation, "id 0 Encampment.savegame");
		final File unpacked = EKUtils.createTempDir(PREFIX).get();
		new ZipFile(written).extractAll(unpacked.getAbsolutePath());

		assertEquals(Color.RED, centreOf(new File(unpacked, "0.png")));
		assertEquals("the portrait just picked, not her old one"
			, Color.GREEN, centreOf(new File(unpacked, "1.png")));
		assertFalse(new File(unpacked, "2.png").exists());
	}

	private static void solidPortrait (final File file, final Color colour) throws IOException {
		final BufferedImage image = new BufferedImage(76, 96, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		g.setColor(colour);
		g.fillRect(0, 0, 76, 96);
		g.dispose();
		assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
		ImageIO.write(image, "png", file);
	}

	private static Color centreOf (final File png) throws IOException {
		final BufferedImage image = ImageIO.read(png);
		return new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2), true);
	}

	/**
	 * An Apply in the Inventory or Abilities tab edits a private copy of the
	 * opened save, so the first Save has to start from that copy. Starting from
	 * the directory the list unpacked would write every scalar edit and lose
	 * every Apply.
	 */
	@Test
	public void theFirstSaveStartsFromWhatTheManagersLeft () throws Exception {
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());

		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());

		final File opened = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());

		final File applied = mockEnvironment.state().workingSave().forEditing(opened, false);
		FileUtils.writeStringToFile(new File(applied, "applied.marker"), "an Apply", "UTF-8");

		final String request = new JSONObject()
			.put("savedYet", false)
			.put("saveName", "AFTER APPLY")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", new org.json.JSONArray())
				.put("currency", 1.0)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject())
					.put("InGameGlobal", new JSONObject())))
			.toString();

		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		new ChangesSaver(request, mockCallback).run();
		verify(mockCallback).success("{\"success\":true}");

		final File saveDirectory = new File(workingDirectory, "id 0 Encampment.savegame");
		assertTrue("the Apply reached the written save"
			, new File(saveDirectory, "applied.marker").isFile());
		assertFalse("and the opened save never saw it", new File(opened, "applied.marker").exists());
	}

	/**
	 * Undo reaches back to the last Save. Past it the scalar edits Save wrote
	 * are in the same files an Apply would take back, so a file put back would
	 * take them back too -- and the save that was opened is still in the list.
	 */
	@Test
	public void aSaveStartsTheHistoryAgain () throws Exception {
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(EKUtils.createTempDir(PREFIX).get().getAbsolutePath());

		final File opened = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());
		final WorkingSave working = mockEnvironment.state().workingSave();
		final File applied = working.forEditing(opened, false);
		final File marker = new File(applied, "applied.marker");
		FileUtils.writeStringToFile(marker, "before", "UTF-8");

		working.history().begin(applied);
		working.history().keep(marker);
		FileUtils.writeStringToFile(marker, "an Apply", "UTF-8");
		assertTrue(working.history().commit() > 0);

		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		new ChangesSaver(new JSONObject()
			.put("savedYet", false)
			.put("saveName", "AFTER APPLY")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", new org.json.JSONArray())
				.put("currency", 1.0)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject())
					.put("InGameGlobal", new JSONObject())))
			.toString(), mockCallback).run();

		verify(mockCallback).success("{\"success\":true}");
		assertTrue(working.history().undoable().isEmpty());
	}

	@Test
	public void headlessSaveChangesPersistsWithoutCefCallback () throws Exception {
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());
		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());

		final File opened = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());

		final JSONObject request = new JSONObject()
			.put("savedYet", false)
			.put("saveName", "HEADLESS SAVE")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", new org.json.JSONArray())
				.put("currency", 250.0)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject())
					.put("InGameGlobal", new JSONObject())));

		final File writtenDir = new ChangesSaver(request).saveChanges();
		assertTrue(writtenDir.isDirectory());
		assertTrue(new File(savesLocation, writtenDir.getName()).isFile());
	}

	// The game's load list files a save under saveinfo.xml's PlayerName. A
	// Watcher imported from another playthrough (or renamed) was still filed
	// under the old name until the game next saved for itself (2026-10-25).
	@Test
	public void saveNamesTheMainCharacterInTheLoadList () throws Exception {
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());
		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());

		final File opened = new File(
			getClass().getResource("/ChangesSaverTest/id 0 Encampment.savegame").toURI());
		final String player = new PacketDeserializer(new File(opened, "MobileObjects.save"))
			.deserialize().get().getPackets().stream()
			.map(property -> unwrapPacket(property))
			.filter(packet -> packet.ObjectName != null && packet.ObjectName.startsWith("Player_"))
			.findFirst().get().ObjectID;

		final JSONObject request = new JSONObject()
			.put("savedYet", false)
			.put("saveName", "NAMED")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", new org.json.JSONArray().put(new JSONObject()
					.put("GUID", player)
					.put("stats", new JSONObject()
						.put("OverrideName", new JSONObject().put("value", "Newcomer")))))
				.put("currency", 250.0)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject())
					.put("InGameGlobal", new JSONObject())));

		final File writtenDir = new ChangesSaver(request).saveChanges();
		final Match saveinfo = $(new File(writtenDir, "saveinfo.xml"));
		assertEquals("Newcomer", saveinfo.find("Simple[name='PlayerName']").attr("value"));
		assertEquals("NAMED", saveinfo.find("Simple[name='UserSaveName']").attr("value"));
	}

	private enum Enum {ONE, TWO}

	// The conversions themselves are SaveModelTest's
	// textBecomesTheTypeTheSaveHolds. An enum the game does not have used to
	// be saved as the first one; it stops the Save now, as a bad number does.
	@Test
	public void aValueThatIsNotTheSavesTypeStopsTheSave () throws Exception {
		final DictionaryProperty variables = new DictionaryProperty("Variables", null);
		final SimpleProperty choice = variable(variables, "Choice", Enum.ONE);
		final SimpleProperty flag = variable(variables, "Flag", true);
		final SimpleProperty count = variable(variables, "Count", 1);

		final Map<String, String> good = new LinkedHashMap<>();
		good.put("Choice", "TWO");
		good.put("Flag", "false");
		good.put("Count", "2");
		updateVariables(variables, good);
		assertEquals(Enum.TWO, choice.value);
		assertEquals(false, flag.value);
		assertEquals(2, count.value);

		for (final String[] bad : new String[][] {{"Choice", "THREE"}, {"Flag", "yes"}, {"Count", "2.5"}}) {
			try {
				updateVariables(variables, Collections.singletonMap(bad[0], bad[1]));
				fail(bad[0] + " = " + bad[1] + " was saved");
			} catch (final WriteRefusedException refused) {
				assertTrue(refused.getMessage(), refused.getMessage().startsWith(bad[0] + ": '" + bad[1] + "'"));
			}
		}

		assertEquals(Enum.TWO, choice.value);
		assertEquals(false, flag.value);
		assertEquals(2, count.value);
	}

	private static SimpleProperty variable (
		final DictionaryProperty variables, final String name, final Object value) {

		final SimpleProperty key = new SimpleProperty(null, null);
		key.value = name;
		key.obj = name;
		final SimpleProperty entry = new SimpleProperty(null, null);
		entry.value = value;
		entry.obj = value;
		variables.items.add(new AbstractMap.SimpleEntry<>(key, entry));
		return entry;
	}

	private static void updateVariables (
		final DictionaryProperty variables, final Map<String, String> updates) throws Exception {

		final Method update = ChangesSaver.class.getDeclaredMethod(
			"updateVariables", PacketDeserializer.class, DictionaryProperty.class, Map.class);
		update.setAccessible(true);
		try {
			update.invoke(null, mock(PacketDeserializer.class), variables, updates);
		} catch (final InvocationTargetException e) {
			throw (Exception) e.getCause();
		}
	}

	@Test
	public void updateValueTest () {
		final ExposedClass exposedSaver = expose(ChangesSaver.class);
		final PacketDeserializer mockDeserializer = mock(PacketDeserializer.class);
		final SimpleProperty mockSimpleProperty = mock(SimpleProperty.class);
		final ComplexProperty mockComplexProperty = mock(ComplexProperty.class);
		final ComplexProperty mockReferencedProperty = mock(ComplexProperty.class);
		final SimpleProperty mockDateTimeField = mock(SimpleProperty.class);
		final SimpleProperty mockTimeIntervalField = mock(SimpleProperty.class);
		final Map<Object, Class> argMap = new LinkedHashMap<>();

		when(mockDeserializer.followReference(any(ReferenceTargetProperty.class)))
			.thenReturn(Optional.of(mockReferencedProperty));

		argMap.put(mockDeserializer, PacketDeserializer.class);
		argMap.put(mockSimpleProperty, Property.class);
		argMap.put("42", String.class);
		mockSimpleProperty.obj = 1;
		exposedSaver.call("updateValue", argMap);
		assertEquals(42, mockSimpleProperty.value);

		argMap.clear();
		argMap.put(mockDeserializer, PacketDeserializer.class);
		argMap.put(mockComplexProperty, Property.class);
		argMap.put("100", String.class);
		mockComplexProperty.reference = new Reference(1);
		mockReferencedProperty.obj = new EternityDateTime();
		mockReferencedProperty.properties = new ArrayList<>();
		mockReferencedProperty.properties.add(mockDateTimeField);
		mockDateTimeField.name = "TotalSeconds";
		exposedSaver.call("updateValue", argMap);
		assertEquals(100, mockDateTimeField.value);

		argMap.clear();
		argMap.put(mockDeserializer, PacketDeserializer.class);
		argMap.put(mockComplexProperty, Property.class);
		argMap.put("2000", String.class);
		mockComplexProperty.reference = null;
		mockComplexProperty.obj = new EternityTimeInterval();
		mockComplexProperty.properties = new ArrayList<>();
		mockComplexProperty.properties.add(mockTimeIntervalField);
		mockTimeIntervalField.name = "SerializedSeconds";
		exposedSaver.call("updateValue", argMap);
		assertEquals(2000, mockTimeIntervalField.value);
	}
}
