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

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.UnsignedInteger;
import org.apache.commons.io.FileUtils;
import org.cef.callback.CefQueryCallback;
import org.jooq.lambda.tuple.Tuple2;
import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.game.*;
import uk.me.mantas.eternity.save.GameText;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.save.StrongholdCatalog;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.ExposedClass;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SavedGameOpenerTest extends TestHarness {
	private static final String DESERIALIZATION_ERR = "{\"error\":\"DESERIALIZATION_ERR\"}";

	@Test
	public void mobileObjectsFileNotExists () {
		CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		SavedGameOpener cls = new SavedGameOpener("404", mockCallback);
		cls.run();

		verify(mockCallback).success("{\"error\":\"NOT_EXISTS\"}");
	}

	@Test
	public void deserializationError () throws IOException {
		CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		Optional<File> saveDir = EKUtils.createTempDir(PREFIX);
		assertTrue(saveDir.isPresent());

		Files.createFile(
			new File(saveDir.get(), "MobileObjects.save").toPath());

		SavedGameOpener cls =
			new SavedGameOpener(saveDir.get().getAbsolutePath(), mockCallback);

		cls.run();
		verify(mockCallback).success("{\"error\":\"DESERIALIZATION_ERR\"}");
	}

	@Test
	public void saveGameOpened () throws URISyntaxException, IOException {
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final SavedGameOpener cls =
			new SavedGameOpener(resources.getAbsolutePath(), mockCallback);

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);

		// (don't remove settings mock; SavedGameOpener needs this singleton to load correct portraits)
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		cls.run(); // we expect this to invoke success() on the mockCallback with JSON as below

		final File extractedFile =
			new File(getClass().getResource("/SavedGameOpenerExtracted.json").toURI());
		final String extractedJSONString = FileUtils.readFileToString(extractedFile);

		verify(mockCallback).success(
			argThat(new EquivalentJSON(extractedJSONString)));
	}

	@Test
	public void inventoryItemsCarryWhatAStoreWouldPayForThem ()
		throws URISyntaxException, IOException {

		// The fixture's Player_Elwyn is carrying Gaun's Pledge; price it and the
		// opener should pass a store's offer through to the UI, which is what
		// lets a selection be totalled without asking the server again.
		final Optional<File> catalogDir = EKUtils.createTempDir(PREFIX);
		assertTrue(catalogDir.isPresent());
		FileUtils.write(new File(catalogDir.get(), "catalog.json")
			, "{\"ring_preorder_gauns_pledge\":{\"name\":\"Gaun's Pledge\""
			+ ",\"value\":1000,\"filter\":128"
			+ ",\"path\":\"Assets/Data/Prefabs/Items/Rings/"
			+ "Ring_PREORDER_Gauns_Pledge.prefab\"}}"
			, "UTF-8");

		ItemCatalog.useCatalogAt(catalogDir.get());
		try {
			final File resources = new File(getClass().getResource("/").toURI());
			final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
			final Settings mockSettings = mockSettings();
			final JSONObject mockJSON = mock(JSONObject.class);
			mockSettings.json = mockJSON;
			when(mockJSON.getString("gameLocation")).thenReturn(
				new File(resources, "SavedGameOpenerTest").getAbsolutePath());

			new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

			final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
			verify(mockCallback).success(response.capture());

			final JSONObject json = new JSONObject(response.getValue());
			final JSONArray characters =
				json.getJSONObject("inventory").getJSONArray("characters");

			int priced = 0;
			for (int i = 0; i < characters.length(); i++) {
				final JSONArray items = characters.getJSONObject(i)
					.getJSONObject("pack").getJSONArray("items");

				for (int j = 0; j < items.length(); j++) {
					final JSONObject item = items.getJSONObject(j);
					if ("Gaun's Pledge".equals(item.optString("displayName"))) {
						// floor(1000 * 0.2)
						assertEquals(200, item.optInt("sellValue"));
						priced++;
					}
				}
			}

			assertEquals(1, priced);
		} finally {
			ItemCatalog.useNoCatalog();
		}
	}

	@Test
	public void theOpenerSaysWhichEquipmentSlotsACharacterDoesNotHave ()
		throws URISyntaxException, IOException {

		// Equipment.HasEquipmentSlot gates the grimoire on being a wizard, the
		// head slot on not being godlike, and the pet slot on being the
		// player. The identity editor refuses a class or race change that
		// would stand gear in a slot that stops existing, so this rule has to
		// stay exactly as the game states it.
		//
		// The fixture is a human Fighter player plus a human Fighter
		// companion: neither is a wizard, neither is godlike, one is the
		// player.
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONArray characters = new JSONObject(response.getValue())
			.getJSONObject("inventory").getJSONArray("characters");

		int checked = 0;
		for (int i = 0; i < characters.length(); i++) {
			final JSONObject character = characters.getJSONObject(i);
			final List<String> missing = new ArrayList<>();
			final JSONArray slots = character.getJSONArray("unavailableSlots");
			for (int j = 0; j < slots.length(); j++) {
				missing.add(slots.getString(j));
			}

			assertEquals("Fighter", character.getString("characterClass"));
			assertEquals("Human", character.getString("characterRace"));

			// A fighter has no grimoire, a human keeps their head slot, and
			// Cape is dead weight the game never populates.
			assertTrue(missing.contains("Grimoire"));
			assertFalse(missing.contains("Head"));
			assertTrue(missing.contains("Cape"));

			// Only the player has a pet slot.
			assertEquals(!character.getBoolean("isPlayer"), missing.contains("Pet"));
			checked++;
		}

		assertEquals(2, checked);
	}

	@Test
	public void theOpenerShipsTheIdentityFieldsTheEditorNeeds ()
		throws URISyntaxException, IOException {

		// Race, subrace, culture, class and background are all [Persistent]
		// enums on CharacterStats, so they ride the ordinary scalar path and
		// simply have to reach the UI with their enum type intact.
		//
		// RacialBodyType rides along too, and must be READ ONLY: Awake()
		// recomputes it from CharacterRace for anyone who is not godlike, and
		// for a godlike it holds the body underneath the godlike features.
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONArray characters =
			new JSONObject(response.getValue()).getJSONArray("characters");

		JSONObject player = null;
		for (int i = 0; i < characters.length(); i++) {
			if (characters.getJSONObject(i).optBoolean("isMainCharacter")) {
				player = characters.getJSONObject(i);
			}
		}

		assertNotNull(player);
		final JSONObject stats = player.getJSONObject("stats");

		for (final String field : new String[] {
			"CharacterRace", "CharacterSubrace", "CharacterCulture"
			, "CharacterClass", "CharacterBackground", "RacialBodyType"}) {

			assertTrue(field + " should reach the UI", stats.has(field));
			assertTrue(field + " should carry its enum type"
				, stats.getJSONObject(field).getString("type")
					.contains("CharacterStats$"));
		}

		assertEquals("Human", stats.getJSONObject("CharacterRace").getString("value"));
		assertEquals("Fighter", stats.getJSONObject("CharacterClass").getString("value"));
		assertEquals(
			"Meadow_Human", stats.getJSONObject("CharacterSubrace").getString("value"));
	}

	@Test
	public void theOpenerSaysWhetherTheSaveIsSelfConsistent ()
		throws URISyntaxException, IOException {

		// Every manager re-opens the save through here after applying, so this
		// is where a structural mistake the editor made surfaces -- immediately,
		// rather than as items silently missing the next time the game loads.
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONObject validation =
			new JSONObject(response.getValue()).getJSONObject("validation");

		// The fixture is a real, untouched save, so it has nothing to say.
		assertEquals(0, validation.getJSONArray("problems").length());
	}

	@Test
	public void theOpenerShipsThePortraitPathsAsWellAsThePicture ()
		throws URISyntaxException, IOException {

		// The picture alone is not enough to build a picker: it cannot say
		// which portrait is the one in use. The save's own two strings can, and
		// they are what an edit has to write back.
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONArray characters =
			new JSONObject(response.getValue()).getJSONArray("characters");

		boolean checked = false;
		for (int i = 0; i < characters.length(); i++) {
			final JSONObject character = characters.getJSONObject(i);
			if (!character.has("portraitPaths")) {
				continue;
			}

			final JSONObject paths = character.getJSONObject("portraitPaths");
			assertTrue(paths.has("m_textureLargePath"));
			assertTrue(paths.has("m_textureSmallPath"));

			// Shipped in the same {type, value} shape as stats, because that
			// is the shape ChangesSaver already knows how to write back.
			assertEquals("java.lang.String"
				, paths.getJSONObject("m_textureLargePath").getString("type"));

			checked = true;
		}

		assertTrue("no character carried portraitPaths", checked);
	}

	/**
	 * Healing is the game's own flag: {@code Health.m_needs_current_values},
	 * which the first frame after a load answers by filling health and stamina
	 * to the maximum the game works out itself. Camping supplies are one number
	 * on the player's inventory. Both travel in the shapes Save writes back.
	 */
	// Save writes every stat back as it is sent, so the stats must be what the
	// save holds. A companion the game has not named has an empty
	// OverrideName, and the game then shows the name in the player's own
	// language; the opener used to fill in the English one it lists her by,
	// and every Save wrote it into the save -- Edér came back "Eder". Found by
	// comparing a saved save with the one it was made from.
	@Test
	public void aCompanionTheGameHasNotNamedIsListedByNameWithoutOneBeingWritten ()
		throws URISyntaxException, IOException {

		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());
		final JSONArray characters = new JSONObject(response.getValue()).getJSONArray("characters");

		JSONObject calisca = null;
		for (int i = 0; i < characters.length(); i++) {
			if (characters.getJSONObject(i).getBoolean("isCompanion")) {
				calisca = characters.getJSONObject(i);
			}
		}

		assertNotNull(calisca);
		assertEquals("listed by who she is", "Calisca", calisca.getString("name"));
		assertEquals("but her stats are the save's", ""
			, calisca.getJSONObject("stats").getJSONObject("OverrideName").getString("value"));
	}

	@Test
	public void theOpenerShipsWhatHealingAndResupplyingNeed ()
		throws URISyntaxException, IOException {

		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());
		final JSONObject save = new JSONObject(response.getValue());

		assertEquals(0, save.getInt("campingSupplies"));

		final JSONArray characters = save.getJSONArray("characters");
		assertEquals(2, characters.length());
		for (int i = 0; i < characters.length(); i++) {
			final JSONObject health = characters.getJSONObject(i).getJSONObject("health");

			// Only the flag: the rest of Health is the game's to keep, and Save
			// writes back whatever it is sent.
			assertEquals(Collections.singleton("m_needs_current_values"), health.keySet());
			final JSONObject flag = health.getJSONObject("m_needs_current_values");
			assertEquals("java.lang.Boolean", flag.getString("type"));
			assertFalse(flag.getBoolean("value"));
		}
	}

	@Test
	public void theOpenerListsEveryGrimoireAndWhatItHolds ()
		throws URISyntaxException, IOException {

		// A grimoire is an item, so its spells hang off the item's own packet
		// rather than off any character -- one in the stash is as real as one
		// being carried. What it holds is SerializedSpellNames, a flat list of
		// spell prefab names; SerializedSpells is object references and comes
		// back all-null, so it is not what the editor reads.
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(
			new File(resources, "GrimoireManagerTest").getAbsolutePath()
			, mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONArray grimoires =
			new JSONObject(response.getValue()).getJSONArray("grimoires");

		assertEquals(1, grimoires.length());

		final JSONObject grimoire = grimoires.getJSONObject(0);
		assertEquals("11111111-2222-3333-4444-555555555555", grimoire.getString("guid"));
		assertEquals("Aloth_Grimoire", grimoire.getString("prefab"));
		assertEquals("Player_Elwyn", grimoire.getString("holder"));

		final JSONArray spells = grimoire.getJSONArray("spells");
		assertEquals(30, spells.length());
		assertEquals("Chill_Fog", spells.getJSONObject(0).getString("prefab"));

		// The chapter a spell files under is its own SpellLevel, which is on
		// the ability catalog rather than in the save.
		assertTrue(spells.getJSONObject(0).has("spellLevel"));
	}

	@Test
	public void aSaveWithNoGrimoireSaysSo () throws URISyntaxException, IOException {
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		assertEquals(0
			, new JSONObject(response.getValue()).getJSONArray("grimoires").length());
	}

	@Test
	public void theOpenerSaysWhetherTheStrongholdIsEvenAvailable ()
		throws URISyntaxException, IOException {

		// Caed Nua only exists once the player has taken it. The fixture's
		// SerializedIsActivated is false, and the editor has to be able to say
		// so rather than offering to edit a stronghold that is not there.
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources, "SavedGameOpenerTest").getAbsolutePath());

		new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONObject stronghold =
			new JSONObject(response.getValue()).getJSONObject("stronghold");

		assertFalse(stronghold.getBoolean("activated"));
		assertEquals(0, stronghold.getJSONArray("upgrades").length());
		assertEquals(0, stronghold.getInt("prestige"));
		assertEquals(0, stronghold.getInt("security"));
	}

	@Test
	public void theOpenerNamesEveryUpgradeTheStrongholdHasBuilt ()
		throws URISyntaxException, IOException {

		// A save records m_upgradesBuilt by enum ordinal and nothing else, so
		// the display name, price and adjustments have to come from the
		// catalog before the UI can draw the game's own upgrade list.
		final Optional<File> catalogDir = EKUtils.createTempDir(PREFIX);
		assertTrue(catalogDir.isPresent());
		FileUtils.write(new File(catalogDir.get(), "stronghold.json")
			, "{\"maxHirelings\":8,\"upgrades\":{"
			+ "\"EasternBarbican\":{\"ordinal\":27,\"name\":\"Eastern Barbican\""
			+ ",\"cost\":0,\"days\":0,\"prestige\":1,\"security\":2,\"order\":1}"
			+ ",\"MainKeep\":{\"ordinal\":4,\"name\":\"Main Keep\",\"cost\":1400"
			+ ",\"days\":3,\"prestige\":3,\"security\":4,\"order\":4"
			+ ",\"prerequisite\":\"EasternBarbican\"}}}"
			, "UTF-8");

		StrongholdCatalog.useCatalogAt(catalogDir.get());
		try {
			final File resources = new File(getClass().getResource("/").toURI());
			final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
			final Settings mockSettings = mockSettings();
			final JSONObject mockJSON = mock(JSONObject.class);
			mockSettings.json = mockJSON;
			when(mockJSON.getString("gameLocation")).thenReturn(
				new File(resources, "SavedGameOpenerTest").getAbsolutePath());

			new SavedGameOpener(resources.getAbsolutePath(), mockCallback).run();

			final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
			verify(mockCallback).success(response.capture());

			final JSONObject stronghold =
				new JSONObject(response.getValue()).getJSONObject("stronghold");

			// Nothing is built in the fixture, but the catalog still has to
			// reach the UI so it knows what could be.
			final JSONArray catalog = stronghold.getJSONArray("catalog");
			assertEquals(2, catalog.length());
			assertEquals("EasternBarbican", catalog.getJSONObject(0).getString("key"));
			assertEquals("Main Keep", catalog.getJSONObject(1).getString("name"));
			assertEquals(1400, catalog.getJSONObject(1).getInt("cost"));
			assertEquals(
				"EasternBarbican", catalog.getJSONObject(1).getString("prerequisite"));

			assertEquals(8, stronghold.getInt("maxHirelings"));
		} finally {
			StrongholdCatalog.useNoCatalog();
		}
	}

	// The keep spliced into StrongholdManagerTest's fixture: four hirelings,
	// the archer unpaid, and Kestorik in the dungeon.
	private JSONObject openKeep () throws URISyntaxException {
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		mockSettings.json = new JSONObject();

		new SavedGameOpener(
			new File(resources, "StrongholdManagerTest").getAbsolutePath()
			, mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());
		return new JSONObject(response.getValue()).getJSONObject("stronghold");
	}

	@Test
	public void theOpenerListsTheHirelingsAndPrisonersByTheGamesOwnNames ()
		throws URISyntaxException, IOException {

		// A save stores a hireling's name as an id into the characters table
		// (SerializedNameId, the prefab's DisplayName.StringID) and a
		// prisoner's as a DatabaseString, so the words come off the install.
		final Optional<File> game = EKUtils.createTempDir(PREFIX);
		assertTrue(game.isPresent());
		final File tables = new File(game.get()
			, "PillarsOfEternity_Data/data/localized/en/text/game");

		FileUtils.write(new File(tables, "characters.stringtable")
			, "<StringTableFile><Entries>"
			+ "<Entry><ID>318</ID><DefaultText>Skirmish Archer</DefaultText></Entry>"
			+ "<Entry><ID>321</ID><DefaultText>Goldpact Knight</DefaultText></Entry>"
			+ "<Entry><ID>322</ID><DefaultText>Warden of the Wilds</DefaultText></Entry>"
			+ "<Entry><ID>329</ID><DefaultText>Crucible Knight</DefaultText></Entry>"
			+ "<Entry><ID>1171</ID><DefaultText>Kestorik</DefaultText></Entry>"
			+ "</Entries></StringTableFile>", "UTF-8");

		FileUtils.write(new File(tables, "stronghold.stringtable")
			, "<StringTableFile><Entries>"
			+ "<Entry><ID>79</ID><DefaultText>A vithrack scout.</DefaultText></Entry>"
			+ "</Entries></StringTableFile>", "UTF-8");

		GameText.useTextAt(game.get());
		try {
			final JSONObject stronghold = openKeep();

			final JSONArray hirelings = stronghold.getJSONArray("hirelings");
			assertEquals(4, hirelings.length());

			final JSONObject knight = hirelings.getJSONObject(0);
			assertEquals("b_crucible_hireling", knight.getString("key"));
			assertEquals("Crucible Knight", knight.getString("name"));
			assertEquals(20, knight.getInt("costPerDay"));
			assertEquals(4, knight.getInt("prestige"));
			assertEquals(2, knight.getInt("security"));
			assertTrue(knight.getBoolean("paid"));
			assertFalse(knight.getBoolean("leaving"));

			final JSONObject archer = hirelings.getJSONObject(2);
			assertEquals("Skirmish Archer", archer.getString("name"));
			assertFalse(archer.getBoolean("paid"));

			assertEquals("Warden of the Wilds"
				, hirelings.getJSONObject(3).getString("name"));

			final JSONArray prisoners = stronghold.getJSONArray("prisoners");
			assertEquals(1, prisoners.length());
			assertEquals("b_kestorik_prisoner", prisoners.getJSONObject(0).getString("key"));
			assertEquals("Kestorik", prisoners.getJSONObject(0).getString("name"));
			assertEquals("A vithrack scout."
				, prisoners.getJSONObject(0).getString("description"));

			assertTrue(stronghold.getBoolean("activated"));
			assertEquals(38, stronghold.getInt("prestige"));
		} finally {
			GameText.useNoText();
		}
	}

	@Test
	public void theOpenerSaysWhoAnItemIsSoulboundTo () throws URISyntaxException {
		// Equippable.WhyCantEquip refuses an item soulbound to someone else, and
		// the binding is on the item's own packet (EquipmentSoulbind.BoundGuid),
		// so the editor has to be told before it can refuse the same way. The
		// fixture's Calisca holds a sceptre bound to her in weapon set II.
		final File resources = new File(getClass().getResource("/").toURI());
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Settings mockSettings = mockSettings();
		mockSettings.json = new JSONObject();

		new SavedGameOpener(
			new File(resources, "InventoryManagerTest").getAbsolutePath()
			, mockCallback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(mockCallback).success(response.capture());

		final JSONArray characters = new JSONObject(response.getValue())
			.getJSONObject("inventory").getJSONArray("characters");

		JSONObject calisca = null;
		for (int i = 0; i < characters.length(); i++) {
			if (characters.getJSONObject(i).getString("guid")
				.equalsIgnoreCase("b1a7e809-0000-0000-0000-000000000000")) {

				calisca = characters.getJSONObject(i);
			}
		}

		assertNotNull(calisca);
		final JSONArray sets = calisca.getJSONObject("equipment").getJSONArray("weaponSets");
		assertEquals("b1a7e809-0000-0000-0000-000000000000"
			, sets.getJSONObject(1).getJSONObject("primary").getString("boundTo").toLowerCase());

		// Her battle axe is bound to nobody.
		assertFalse(sets.getJSONObject(0).getJSONObject("primary").has("boundTo"));
	}

	@Test
	public void withoutTheGameAHirelingIsNamedAfterTheirGlobal ()
		throws URISyntaxException {

		// No install, no string tables: the global is the one name the save
		// itself carries, and "Crucible" beats an empty row.
		final JSONObject stronghold = openKeep();

		final JSONArray hirelings = stronghold.getJSONArray("hirelings");
		assertEquals("Crucible", hirelings.getJSONObject(0).getString("name"));
		assertEquals("Warden Wilds", hirelings.getJSONObject(3).getString("name"));

		final JSONObject kestorik = stronghold.getJSONArray("prisoners").getJSONObject(0);
		assertEquals("Kestorik", kestorik.getString("name"));
		assertEquals("", kestorik.getString("description"));
	}

	private class EquivalentJSON implements ArgumentMatcher<String> {
		JSONObject expected = null;
		JSONObject actual = null;

		public EquivalentJSON(String expected) {
			this.expected = new JSONObject(expected);
		}

		public boolean matches(String actual) {
			this.actual = new JSONObject(actual);
			// ignores whitespace & ordering of object keys/children
			// (order of array entries is still significant)

			return expected.similar(this.actual);
		}

		public String toString() {
			// this removes whitespace
			// BUT may still have insignificant object key ordering differences	from actual
			// TODO: to aid troubleshooting tests, output expected using the ordering from actual (where available)
			return expected.toString();
		}
	}

	private PacketDeserializer mockDeserializer (final Environment mockEnvironment)
		throws FileNotFoundException {

		final PacketDeserializerFactory mockFactory = mock(PacketDeserializerFactory.class);
		final PacketDeserializer mockDeserializer = mock(PacketDeserializer.class);

		when(mockEnvironment.factory().packetDeserializer()).thenReturn(mockFactory);
		when(mockFactory.forFile(any(File.class))).thenReturn(mockDeserializer);

		return mockDeserializer;
	}

	/** A save folder holding a MobileObjects.save, whatever is in it. */
	private File saveFolder () throws IOException {
		final File folder = EKUtils.createTempDir(PREFIX).get();
		FileUtils.writeStringToFile(new File(folder, "MobileObjects.save"), "unread", "UTF-8");
		return folder;
	}

	// Opening answers an unreadable save with the error, and with nothing
	// after it: it used to go on and send an empty save as well.
	@Test
	public void deserializeTestDeserializationError () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final PacketDeserializer mockDeserializer = mockDeserializer(mockEnvironment);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		when(mockDeserializer.deserializeEvenIfShort()).thenReturn(Optional.empty());

		new SavedGameOpener(saveFolder().getAbsolutePath(), mockCallback).run();

		verify(mockCallback).success(DESERIALIZATION_ERR);
		verifyNoMoreInteractions(mockCallback);
	}

	@Test
	public void deserializeTestThrowsException () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final PacketDeserializerFactory mockFactory = mock(PacketDeserializerFactory.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		// Reading tolerates a short file, so the one thing left to throw is
		// finding the file at all.
		when(mockEnvironment.factory().packetDeserializer()).thenReturn(mockFactory);
		when(mockFactory.forFile(any(File.class))).thenThrow(new FileNotFoundException());

		new SavedGameOpener(saveFolder().getAbsolutePath(), mockCallback).run();

		verify(mockCallback).success(DESERIALIZATION_ERR);
		verifyNoMoreInteractions(mockCallback);
	}

	// A writer reopening the save it has just edited is not answered for: it
	// is told, so it can take the edit back before it answers.
	@Test
	public void reopeningAnUnreadableSaveAnswersNothing () throws IOException {
		final Environment mockEnvironment = mockEnvironment();
		final PacketDeserializer mockDeserializer = mockDeserializer(mockEnvironment);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);

		when(mockDeserializer.deserializeEvenIfShort()).thenReturn(Optional.empty());

		try {
			new SavedGameOpener(saveFolder().getAbsolutePath(), mockCallback).reopen();
			fail("an unreadable save was reopened");
		} catch (final SavedGameOpener.Unreadable expected) {
			assertTrue(expected.takenBack(), expected.takenBack().contains("taken back"));
		}

		verifyZeroInteractions(mockCallback);
	}

	@Test
	public void extractGlobalsTest () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Property startsWithGlobal = mock(Property.class);
		final Property startsWithInGameGlobal = mock(Property.class);
		final Property notFound = mock(Property.class);

		final List<Property> gameObjects =
			ImmutableList.of(startsWithGlobal, startsWithInGameGlobal, notFound);

		final ObjectPersistencePacket startsWithGlobalPacket = new ObjectPersistencePacket();
		startsWithGlobalPacket.ObjectName = "Global(Clone)";
		startsWithGlobal.obj = startsWithGlobalPacket;

		final ObjectPersistencePacket startsWithInGameGlobalPacket = new ObjectPersistencePacket();
		startsWithInGameGlobalPacket.ObjectName = "InGameGlobal(Clone)";
		startsWithInGameGlobal.obj = startsWithInGameGlobalPacket;

		final ObjectPersistencePacket notFoundPacket = new ObjectPersistencePacket();
		notFoundPacket.ObjectName = "SecondWind(Clone)";
		notFound.obj = notFoundPacket;

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);

		final Map<Object, Class> argMap = new HashMap<>();
		argMap.put(gameObjects, List.class);

		final Map<String, Property> globals = exposedOpener.call("extractGlobals", argMap);
		assertEquals(2, globals.size());
		assertSame(startsWithGlobal, globals.get("Global"));
		assertSame(startsWithInGameGlobal, globals.get("InGameGlobal"));
	}

	@Test
	public void globalsToJSONTest () {
		final Environment mockEnvironment = mockEnvironment();
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Property global = mock(Property.class);
		final ComponentPersistencePacket found = new ComponentPersistencePacket();
		final ComponentPersistencePacket notFound = new ComponentPersistencePacket();

		when(mockEnvironment.config().usefulGlobals()).thenReturn(ImmutableList.of("Found"));
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		packet.ComponentPackets = new ComponentPersistencePacket[] {found, notFound};
		global.obj = packet;

		found.TypeString = "Found";
		notFound.TypeString = "NotFound";

		found.Variables = new HashMap<>();
		found.Variables.put("SupportedType", 1);
		found.Variables.put("UnsupportedType", new CurrencyValue());

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);
		assertEquals(
			"{\"Found\":{\"SupportedType\":{\"type\":\"java.lang.Integer\",\"value\":1}}}"
			, exposedOpener.call("globalsToJSON", global).toString());
	}

	@Test
	public void extractCharactersTest () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Property noObjectID = mock(Property.class);
		final Property startsWithCompanion = mock(Property.class);
		final Property startsWithLowercaseCompanion = mock(Property.class);
		final Property startsWithPlayer = mock(Property.class);

		final List<Property> gameObjects = new ArrayList<Property>(){{
			add(noObjectID);
			add(startsWithCompanion);
			add(startsWithLowercaseCompanion);
			add(startsWithPlayer);
		}};

		final ObjectPersistencePacket noObjectIDPacket = new ObjectPersistencePacket();
		noObjectIDPacket.ObjectName = "";
		noObjectIDPacket.ObjectID = null;
		noObjectID.obj = noObjectIDPacket;

		final ObjectPersistencePacket startsWithCompanionPacket = new ObjectPersistencePacket();
		startsWithCompanionPacket.ObjectName = "Companion_A";
		startsWithCompanionPacket.ObjectID = "Key_A";
		startsWithCompanion.obj = startsWithCompanionPacket;

		final ObjectPersistencePacket startsWithLowercaseCompanionPacket =
			new ObjectPersistencePacket();
		startsWithLowercaseCompanionPacket.ObjectName = "companion_C";
		startsWithLowercaseCompanionPacket.ObjectID = "Key_C";
		startsWithLowercaseCompanion.obj = startsWithLowercaseCompanionPacket;

		final ObjectPersistencePacket startsWithPlayerPacket = new ObjectPersistencePacket();
		startsWithPlayerPacket.ObjectName = "Player_B";
		startsWithPlayerPacket.ObjectID = "Key_B";
		startsWithPlayer.obj = startsWithPlayerPacket;

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);

		final Map<Object, Class> argMap = new HashMap<Object, Class>(){{
			put(gameObjects, List.class);
		}};

		final Map<String, Property> characters = exposedOpener.call("extractCharacters", argMap);
		assertEquals(3, characters.size());
		assertSame(startsWithCompanion, characters.get("Key_A"));
		assertSame(startsWithLowercaseCompanion, characters.get("Key_C"));
		assertSame(startsWithPlayer, characters.get("Key_B"));
	}

	@Test
	public void extractPortraitTestSelectsFirst () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final ComponentPersistencePacket firstComponent = new ComponentPersistencePacket();
		final ComponentPersistencePacket secondComponent = new ComponentPersistencePacket();

		firstComponent.TypeString = "Portrait";
		secondComponent.TypeString = "Portrait";
		firstComponent.Variables = new HashMap<>();
		secondComponent.Variables = new HashMap<String, Object>() {{
			put("m_textureLargePath", "png");
		}};

		packet.ComponentPackets = new ComponentPersistencePacket[]{
			firstComponent
			, secondComponent
		};

		mockSettings();

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);
		final String result = exposedOpener.call("extractPortrait", packet, false);

		assertEquals("", result);
	}

	@Test
	public void extractPortraitTestNoGameLocation () {
		final Settings mockSettings = mockSettings();
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final ComponentPersistencePacket component = new ComponentPersistencePacket();

		component.TypeString = "Portrait";
		component.Variables = new HashMap<String, Object>(){{
			put("m_textureLargePath", "png");
		}};

		mockSettings.json = new JSONObject();
		packet.ComponentPackets = new ComponentPersistencePacket[]{ component };

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);
		final String result = exposedOpener.call("extractPortrait", packet, false);

		assertEquals("", result);
	}

	@Test
	public void extractPortraitTestPortraitNotFound () throws URISyntaxException {
		final Settings mockSettings = mockSettings();
		final Logger mockLogger = interceptLogging(SavedGameOpener.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final ComponentPersistencePacket component = new ComponentPersistencePacket();
		final File testResources = new File(getClass().getResource("/").toURI());

		component.TypeString = "Portrait";
		component.Variables = new HashMap<String, Object>(){{
			put("m_textureLargePath", "../404");
		}};

		mockSettings.json = new JSONObject().put("gameLocation", testResources.getAbsolutePath());
		packet.ComponentPackets = new ComponentPersistencePacket[]{ component };

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);
		final String result = exposedOpener.call("extractPortrait", packet, false);

		final ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
		verify(mockLogger).error(anyString(), argument.capture());
		assertEquals("", result);
		assertEquals(new File(testResources, "404").getAbsolutePath(), argument.getValue());
	}

	@Test
	public void extractPortraitTestUnmappedCompanion () throws URISyntaxException {
		final Settings mockSettings = mockSettings();
		final Logger mockLogger = interceptLogging(SavedGameOpener.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final ComponentPersistencePacket component = new ComponentPersistencePacket();
		final File testResources = new File(getClass().getResource("/").toURI());
		final String companionPortraitPath =
			Environment.getInstance().config().companionPortraitPath();
		final String substitutedPath = String.format(companionPortraitPath, "unmapped");
		final File portraitFile =
			Paths.get(testResources.toURI())
				.resolve(Environment.getInstance().config().pillarsDataDirectory())
				.resolve(substitutedPath)
				.toFile();

		mockSettings.json = new JSONObject().put("gameLocation", testResources.getAbsolutePath());
		packet.ObjectName = "Companion_Unmapped";
		component.TypeString = "Portrait";
		component.Variables = new HashMap<>();
		packet.ComponentPackets = new ComponentPersistencePacket[]{ component };

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);
		final String result = exposedOpener.call("extractPortrait", packet, true);

		final ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
		verify(mockLogger).error(anyString(), argument.capture());
		assertEquals("", result);
		assertEquals(portraitFile.getAbsolutePath(), argument.getValue());
	}

	@Test
	public void extractPortraitTestMappedCompanion () throws URISyntaxException {
		final Settings mockSettings = mockSettings();
		final Logger mockLogger = interceptLogging(SavedGameOpener.class);
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final ComponentPersistencePacket component = new ComponentPersistencePacket();
		final File testResources = new File(getClass().getResource("/").toURI());
		final String companionPortraitPath =
			Environment.getInstance().config().companionPortraitPath();
		final String substitutedPath = String.format(companionPortraitPath, "grieving_mother");
		final File portraitFile =
			Paths.get(testResources.toURI())
				.resolve(Environment.getInstance().config().pillarsDataDirectory())
				.resolve(substitutedPath)
				.toFile();

		mockSettings.json = new JSONObject().put("gameLocation", testResources.getAbsolutePath());
		packet.ObjectName = "Companion_Grieving_Mother";
		component.TypeString = "Portrait";
		component.Variables = new HashMap<>();
		packet.ComponentPackets = new ComponentPersistencePacket[]{ component };

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);
		final String result = exposedOpener.call("extractPortrait", packet, true);

		final ArgumentCaptor<String> argument = ArgumentCaptor.forClass(String.class);
		verify(mockLogger).error(anyString(), argument.capture());
		assertEquals("", result);
		assertEquals(portraitFile.getAbsolutePath(), argument.getValue());
	}

	@Test
	public void detectDeadTest () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final ComponentPersistencePacket component = new ComponentPersistencePacket();

		component.TypeString = "Health";
		component.Variables = new HashMap<>();
		packet.ComponentPackets = new ComponentPersistencePacket[]{ component };

		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);
		boolean result = exposedOpener.call("detectDead", packet);
		assertFalse(result);

		component.Variables = new HashMap<String, Object>(){{
			put("CurrentHealth", 1f);
		}};

		result = exposedOpener.call("detectDead", packet);
		assertFalse(result);

		component.Variables = new HashMap<String, Object>(){{
			put("CurrentHealth", 0f);
		}};

		result = exposedOpener.call("detectDead", packet);
		assertTrue(result);
	}

	@Test
	public void extractNameTest () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);

		packet.ObjectName = "NoUnderscore";
		String result = exposedOpener.call("extractName", packet);
		assertEquals("", result);

		packet.ObjectName = "Player_HasUnderscore";
		result = exposedOpener.call("extractName", packet);
		assertEquals("HasUnderscore", result);

		packet.ObjectName = "Player_HasBracket(Clone)_1";
		result = exposedOpener.call("extractName", packet);
		assertEquals("HasBracket", result);
	}

	@Test
	public void extractCharacterStatsTest () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final ComponentPersistencePacket notStatsComponent = new ComponentPersistencePacket();
		final ComponentPersistencePacket statsComponent = new ComponentPersistencePacket();
		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);

		notStatsComponent.TypeString = "NotCharacterStats";
		statsComponent.TypeString = "CharacterStats";
		statsComponent.Variables = new HashMap<String, Object>(){{
			put("Integer", 1);
			put("Float", 1f);
			put("String", "1");
			put("UnsignedInteger", UnsignedInteger.valueOf(1L));
			put("NotSupported", new byte[0]);
		}};

		packet.ComponentPackets = new ComponentPersistencePacket[]{ notStatsComponent };
		Optional<Map<String, Object>> result = exposedOpener.call("extractCharacterStats", packet);
		assertFalse(result.isPresent());
		packet.ComponentPackets = new ComponentPersistencePacket[]{
			notStatsComponent
			, null
			, statsComponent
		};

		result = exposedOpener.call("extractCharacterStats", packet);
		assertTrue(result.isPresent());
		assertEquals(
			"{\"type\":\"java.lang.Integer\",\"value\":1}"
			, result.get().get("Integer").toString());
		assertEquals(
			"{\"type\":\"java.lang.String\",\"value\":\"1\"}"
			, result.get().get("String").toString());
		assertEquals(
			"{\"type\":\"java.lang.Float\",\"value\":1}"
			, result.get().get("Float").toString());
		assertEquals(
			"{\"type\":\"com.google.common.primitives.UnsignedInteger\",\"value\":1}"
			, result.get().get("UnsignedInteger").toString());
		assertNull(result.get().get("NotSupported"));
	}

	@Test
	public void detectCompanionTest () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final ObjectPersistencePacket packet = new ObjectPersistencePacket();
		final SavedGameOpener savedGameOpener = new SavedGameOpener("404", mockCallback);
		final ExposedClass exposedOpener = expose(savedGameOpener);

		packet.ObjectName = "Companion_Calisca";
		boolean result = exposedOpener.call("detectCompanion", packet);
		assertTrue(result);

		packet.ObjectName = "companion_Heodan";
		result = exposedOpener.call("detectCompanion", packet);
		assertTrue(result);

		packet.ObjectName = "Companion_Generic_(Clone)_1";
		result = exposedOpener.call("detectCompanion", packet);
		assertFalse(result);

		packet.ObjectName = "Player_Fyorl";
		result = exposedOpener.call("detectCompanion", packet);
		assertFalse(result);
	}

	private Tuple2<Logger, ExposedClass> setupExtractCurrency () {
		final CefQueryCallback mockCallback = mock(CefQueryCallback.class);
		final Logger mockLogger = interceptLogging(SavedGameOpener.class);
		final SavedGameOpener opener = new SavedGameOpener("", mockCallback);
		final ExposedClass exposedOpener = expose(opener);
		return new Tuple2<>(mockLogger, exposedOpener);
	}

	@Test
	public void extractCurrencyTestNoPlayerPacket () {
		final Tuple2<Logger, ExposedClass> setup = setupExtractCurrency();
		final Map<Object, Class> argMap = new HashMap<Object, Class>() {{
			put(new ArrayList<Property>(), List.class);
		}};

		assertEquals(0f, setup.v2().call("extractCurrency", argMap), 0f);
		verify(setup.v1()).error("Unable to find player mobile object.%n");
	}

	@Test
	public void extractCurrencyTestNoPlayerInventoryComponent () {
		final Tuple2<Logger, ExposedClass> setup = setupExtractCurrency();
		final Property property = mock(Property.class);
		final ObjectPersistencePacket playerPacket = mock(ObjectPersistencePacket.class);
		final List<Property> gameObjects = new ArrayList<Property>() {{add(property);}};
		final Map<Object, Class> argMap = new HashMap<Object, Class>() {{
			put(gameObjects, List.class);
		}};

		property.obj = playerPacket;
		playerPacket.ObjectName = "Player_Elenor";
		playerPacket.ComponentPackets = new ComponentPersistencePacket[0];

		assertEquals(0f, setup.v2().call("extractCurrency", argMap), 0f);
		verify(setup.v1()).error("Unable to find PlayerInventory component.");
	}

	@Test
	public void extractCurrencyTestNoCurrencyTotalValue () {
		final Tuple2<Logger, ExposedClass> setup = setupExtractCurrency();
		final Property property = mock(Property.class);
		final ObjectPersistencePacket playerPacket = mock(ObjectPersistencePacket.class);
		final ComponentPersistencePacket inventoryComponent =
			mock(ComponentPersistencePacket.class);

		final List<Property> gameObjects = new ArrayList<Property>() {{add(property);}};
		final Map<Object, Class> argMap = new HashMap<Object, Class>() {{
			put(gameObjects, List.class);
		}};

		property.obj = playerPacket;
		playerPacket.ObjectName = "Player_Elenor";
		playerPacket.ComponentPackets = new ComponentPersistencePacket[] {inventoryComponent};
		inventoryComponent.TypeString = "PlayerInventory";
		inventoryComponent.Variables = new HashMap<>();

		assertEquals(0f, setup.v2().call("extractCurrency", argMap), 0f);
		verify(setup.v1()).error("Unable to find currencyTotalValue in PlayerInventory component.");
	}

	@Test
	public void extractCurrencyTest () {
		final Tuple2<Logger, ExposedClass> setup = setupExtractCurrency();
		final Property property = mock(Property.class);
		final ObjectPersistencePacket playerPacket = mock(ObjectPersistencePacket.class);
		final CurrencyValue currencyValue = new CurrencyValue();
		final ComponentPersistencePacket inventoryComponent =
			mock(ComponentPersistencePacket.class);

		final List<Property> gameObjects = new ArrayList<Property>() {{add(property);}};
		final Map<Object, Class> argMap = new HashMap<Object, Class>() {{
			put(gameObjects, List.class);
		}};

		currencyValue.v = 3.14159f;
		property.obj = playerPacket;
		playerPacket.ObjectName = "Player_Elenor";
		playerPacket.ComponentPackets = new ComponentPersistencePacket[] {inventoryComponent};
		inventoryComponent.TypeString = "PlayerInventory";
		inventoryComponent.Variables = new HashMap<String, Object>() {{
			put("currencyTotalValue", currencyValue);
		}};

		assertEquals(3.14159f, setup.v2().call("extractCurrency", argMap), 1e-6f);
	}

	private enum Enum {ONE, TWO}

	@Test
	public void recordTypeTest () {
		final ExposedClass exposedOpener = expose(SavedGameOpener.class);
		final EternityDateTime dateTime = new EternityDateTime();
		final EternityTimeInterval timeInterval = new EternityTimeInterval();
		final Map<Object, Class> argMap = new HashMap<>();

		dateTime.TotalSeconds = 42;
		timeInterval.SerializedSeconds = 42;

		argMap.put(1, Object.class);
		final JSONObject intTest = exposedOpener.call("recordType", argMap);
		assertEquals("{\"type\":\"java.lang.Integer\",\"value\":1}", intTest.toString());

		argMap.clear();
		argMap.put(1d, Object.class);
		final JSONObject dblTest = exposedOpener.call("recordType", argMap);
		assertEquals("{\"type\":\"java.lang.Double\",\"value\":1}", dblTest.toString());

		argMap.clear();
		argMap.put(true, Object.class);
		final JSONObject boolTest = exposedOpener.call("recordType", argMap);
		assertEquals("{\"type\":\"java.lang.Boolean\",\"value\":true}", boolTest.toString());

		argMap.clear();
		argMap.put("str", Object.class);
		final JSONObject strTest = exposedOpener.call("recordType", argMap);
		assertEquals("{\"type\":\"java.lang.String\",\"value\":\"str\"}", strTest.toString());

		argMap.clear();
		argMap.put(UnsignedInteger.valueOf(Integer.MAX_VALUE + 1L), Object.class);
		final JSONObject uintTest = exposedOpener.call("recordType", argMap);
		assertEquals(
			"{\"type\":\"com.google.common.primitives.UnsignedInteger\",\"value\":2147483648}"
			, uintTest.toString());

		argMap.clear();
		argMap.put(Enum.ONE, Object.class);
		final JSONObject enumTest = exposedOpener.call("recordType", argMap);
		assertEquals(
			"{\"type\":\"uk.me.mantas.eternity.tests.save.SavedGameOpenerTest$Enum\""
			+ ",\"value\":\"ONE\"}"
			, enumTest.toString());

		argMap.clear();
		argMap.put(dateTime, Object.class);
		final JSONObject dateTimeTest = exposedOpener.call("recordType", argMap);
		assertEquals(
			"{\"type\":\"uk.me.mantas.eternity.game.EternityDateTime\",\"value\":42}"
			, dateTimeTest.toString());

		argMap.clear();
		argMap.put(timeInterval, Object.class);
		final JSONObject timeIntervalTest = exposedOpener.call("recordType", argMap);
		assertEquals(
			"{\"type\":\"uk.me.mantas.eternity.game.EternityTimeInterval\",\"value\":42}"
			, timeIntervalTest.toString());
	}

	@Test
	public void globalsToJSONGlobalVariablesTest () {
		final ExposedClass exposedOpener = expose(SavedGameOpener.class);
		final Property globalProperty = mock(Property.class);
		final ObjectPersistencePacket global = new ObjectPersistencePacket();
		final ComponentPersistencePacket packet = new ComponentPersistencePacket();
		final Hashtable<String, Integer> table = new Hashtable<>();

		globalProperty.obj = global;
		global.ComponentPackets = new ComponentPersistencePacket[]{packet};
		packet.TypeString = "GlobalVariables";
		packet.Variables = new HashMap<>();
		packet.Variables.put("m_data", table);
		table.put("A", 1);
		table.put("B", 2);

		final JSONObject result = exposedOpener.call("globalsToJSON", globalProperty);
		assertEquals(
			"{\"GlobalVariables\":{"
				+ "\"A\":{\"type\":\"java.lang.Integer\",\"value\":1}"
				+ ",\"B\":{\"type\":\"java.lang.Integer\",\"value\":2}}}"
			, result.toString());
	}
}
