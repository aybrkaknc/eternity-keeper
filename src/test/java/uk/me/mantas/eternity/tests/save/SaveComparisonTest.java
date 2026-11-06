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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.AbilityManager;
import uk.me.mantas.eternity.save.GrimoireManager;
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.SaveComparison;
import uk.me.mantas.eternity.save.StrongholdCatalog;
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.save.VendorManager;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.tests.save.SaveDiffTest.*;

// Two saves compared in the words the editor uses everywhere else: a stat
// under its character, an item by where it went, a global by its name. Each
// test makes one kind of change to a copy of a save -- through the manager
// that makes it in the editor, where there is one -- and reads it back out of
// the comparison.
public class SaveComparisonTest extends TestHarness {
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String PIGLET = "41baf584-9203-44df-a921-62f50000ad74";
	private static final String KNOCKDOWN = "3ffdbced-4efb-4a6a-afcc-686fed65381b";
	private static final String GLOBAL = "181c29fd-57ed-4b97-85dd-50752748b64c";

	@After
	public void restoreCatalog () {
		StrongholdCatalog.useNoCatalog();
	}

	/** Two copies of one save, the second for a test to change. */
	private static File[] pair () throws Exception {
		return pair("MobileObjects.save");
	}

	private static File[] pair (final String world) throws Exception {
		final File resources = new File(SaveComparisonTest.class.getResource("/").toURI());
		final File[] saves = new File[2];
		for (int i = 0; i < 2; i++) {
			final File folder = EKUtils.createTempDir(PREFIX).get();
			saves[i] = new File(folder, "cadena " + i + " Test.savegame");
			assertTrue(saves[i].mkdir());
			FileUtils.copyFile(new File(resources, world), new File(saves[i], "MobileObjects.save"));
			FileUtils.copyFile(
				new File(resources, "SaveGameInfoTest.saveinfo.xml"), new File(saves[i], "saveinfo.xml"));
		}

		return saves;
	}

	/** Changes a save's world state in place, the way an edit lands. */
	private static void edit (final File save, final Consumer<List<Property>> change) throws Exception {
		final File world = new File(save, "MobileObjects.save");
		final Optional<DeserializedPackets> packets = new PacketDeserializer(world).deserialize();
		assertTrue(packets.isPresent());
		change.accept(packets.get().getPackets());
		set(packets.get().getCount(), packets.get().getPackets().size());
		packets.get().replace(world);
	}

	private static void info (final File save, final String field, final String value) throws Exception {
		final File file = new File(save, "saveinfo.xml");
		final String xml = FileUtils.readFileToString(file, "UTF-8");
		final String replaced = xml.replaceFirst(
			"(name=\"" + field + "\"[^>]*value=\")[^\"]*\"", "$1" + value + "\"");
		assertNotEquals(xml, replaced);
		FileUtils.writeStringToFile(file, replaced, "UTF-8");
	}

	private static JSONObject section (final JSONObject comparison, final String id) {
		final JSONArray sections = comparison.getJSONArray("sections");
		for (int i = 0; i < sections.length(); i++) {
			if (id.equals(sections.getJSONObject(i).getString("id"))) {
				return sections.getJSONObject(i);
			}
		}

		throw new AssertionError("no section " + id + " in " + comparison.toString(2));
	}

	private static boolean hasSection (final JSONObject comparison, final String id) {
		final JSONArray sections = comparison.getJSONArray("sections");
		for (int i = 0; i < sections.length(); i++) {
			if (id.equals(sections.getJSONObject(i).getString("id"))) {
				return true;
			}
		}

		return false;
	}

	private static JSONObject group (final JSONObject section, final String title) {
		final JSONArray groups = section.getJSONArray("groups");
		for (int i = 0; i < groups.length(); i++) {
			if (title.equals(groups.getJSONObject(i).optString("title"))) {
				return groups.getJSONObject(i);
			}
		}

		throw new AssertionError("no group " + title + " in " + section.toString(2));
	}

	private static JSONObject row (final JSONObject group, final String label) {
		final JSONArray rows = group.getJSONArray("rows");
		for (int i = 0; i < rows.length(); i++) {
			if (label.equals(rows.getJSONObject(i).getString("label"))) {
				return rows.getJSONObject(i);
			}
		}

		throw new AssertionError("no row " + label + " in " + group.toString(2));
	}

	private static List<String> labels (final JSONObject group) {
		final List<String> labels = new ArrayList<>();
		final JSONArray rows = group.getJSONArray("rows");
		for (int i = 0; i < rows.length(); i++) {
			labels.add(rows.getJSONObject(i).getString("label"));
		}

		return labels;
	}

	private static List<String> ids (final JSONObject comparison) {
		final List<String> ids = new ArrayList<>();
		final JSONArray sections = comparison.getJSONArray("sections");
		for (int i = 0; i < sections.length(); i++) {
			ids.add(sections.getJSONObject(i).getString("id"));
		}

		return ids;
	}

	// Every difference the two files hold is either shown or counted among
	// what is left out, never dropped on the floor.
	private static void accountedFor (final JSONObject comparison) {
		final JSONObject counts = comparison.getJSONObject("counts");
		assertEquals(comparison.toString(2)
			, counts.getInt("differences"), counts.getInt("shown") + counts.getInt("leftOut"));
	}

	@Test
	public void twoCopiesOfOneSaveDifferInNothing () throws Exception {
		final File[] saves = pair();
		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);

		assertEquals(0, comparison.getJSONArray("sections").length());
		assertEquals(0, comparison.getJSONObject("counts").getInt("differences"));
		assertTrue(comparison.getBoolean("samePlaythrough"));
		assertEquals("Encampment", comparison.getJSONObject("before").getString("scene"));
		assertEquals("Start", comparison.getJSONObject("after").getString("userSaveName"));
		assertEquals(0, comparison.getJSONArray("problems").length());
	}

	@Test
	public void aStatIsShownUnderItsCharacterInWords () throws Exception {
		final File[] saves = pair();
		edit(saves[1], packets -> {
			set(variable(packet(packets, ELWYN), "CharacterStats", "BaseMight"), 18);
			set(variable(packet(packets, ELWYN), "CharacterStats", "AthleticsSkill"), 10);
			set(variable(packet(packets, CALISCA), "CharacterStats", "Experience"), 1500);
			set(variable(packet(packets, CALISCA), "Health", "m_needs_current_values"), true);
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject characters = section(comparison, "characters");

		final JSONObject elwyn = group(characters, "Elwyn");
		assertEquals("Main character", elwyn.getString("note"));
		assertEquals(10, row(elwyn, "Might").getInt("before"));
		assertEquals(18, row(elwyn, "Might").getInt("after"));

		// The save holds points; the sheet shows the rank they buy.
		final JSONObject athletics = row(elwyn, "Athletics");
		assertEquals("skill", athletics.getString("format"));
		assertEquals(10, athletics.getInt("after"));

		final JSONObject calisca = group(characters, "Calisca");
		assertEquals("Companion", calisca.getString("note"));
		assertEquals(1500, row(calisca, "Experience").getInt("after"));
		assertTrue(row(calisca, "Refilled on the next load").getBoolean("after"));
		assertFalse(row(calisca, "Refilled on the next load").getBoolean("before"));
		assertEquals("the player comes first", "Elwyn"
			, characters.getJSONArray("groups").getJSONObject(0).getString("title"));
		accountedFor(comparison);
	}

	@Test
	public void moneySuppliesAndDifficultyBelongToTheSave () throws Exception {
		final File[] saves = pair();
		edit(saves[1], packets -> {
			final DictionaryProperty inventory = variables(packet(packets, ELWYN), "PlayerInventory");
			final ComplexProperty currency = (ComplexProperty) inventory.findEntry("currencyTotalValue").get();
			set(currency.findProperty("v").get(), 4321.0f);
			set(inventory.findEntry("campingSupplies").get(), 4);
			set(variable(packet(packets, GLOBAL), "GameState", "Difficulty")
				, uk.me.mantas.eternity.game.GameDifficulty.Hard);
		});
		info(saves[1], "UserSaveName", "Start (edited)");

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject save = section(comparison, "save").getJSONArray("groups").getJSONObject(0);

		final JSONObject money = row(save, "Money");
		assertEquals("money", money.getString("format"));
		assertEquals(4321, money.getInt("after"));
		assertEquals(4, row(save, "Camping supplies").getInt("after"));
		assertEquals("Normal", row(save, "Difficulty").getString("before"));
		assertEquals("Hard", row(save, "Difficulty").getString("after"));
		assertEquals("Start (edited)", row(save, "Name in the load list").getString("after"));
		assertEquals(Collections.singletonList("save"), ids(comparison));
		accountedFor(comparison);
	}

	// The clock is days since the adventure began and the time of day; the
	// day turns at midnight, as the time of day beside it says it does.
	@Test
	public void theGameClockIsADayAndATime () throws Exception {
		final File[] saves = pair();
		final long[] start = new long[1];
		for (int i = 0; i < 2; i++) {
			final int save = i;
			edit(saves[i], packets -> {
				final ComplexProperty world = packet(packets, GLOBAL);
				start[0] = ((Number) ((ComplexProperty) variable(world, "WorldTime", "AdventureStart"))
					.findProperty("TotalSeconds").get().obj).longValue();
				final long fourDaysOn = (start[0] / 86400 + 4) * 86400 + 16 * 3600 + 49 * 60;
				set(((ComplexProperty) variable(world, "WorldTime", "CurrentTime")).findProperty("TotalSeconds").get()
					, (int) (save == 0 ? fourDaysOn : fourDaysOn + 8 * 3600 + 15 * 60));
			});
		}

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject time = row(section(comparison, "save").getJSONArray("groups").getJSONObject(0), "Game time");

		assertEquals("day 5, 16:49", time.getString("before"));
		assertEquals("day 6, 01:04", time.getString("after"));
		assertEquals("+8 h 15 min", time.getString("change"));
		accountedFor(comparison);
	}

	@Test
	public void itemsSayWhereTheyWentAndWhatCameAndWent () throws Exception {
		final File[] saves = pair();
		final InventoryManager.Change toStash = new InventoryManager.Change(
			ELWYN, "PlayerInventory", PIGLET, 1, ELWYN, "StashInventory", -1);
		final InventoryManager.Change sold = new InventoryManager.Change(
			ELWYN, "PlayerInventory", RING, 0);
		final InventoryManager.Change beer = new InventoryManager.Change(
			CALISCA, "Inventory", "11112222-3333-4444-5555-666677778888", 3, CALISCA, "Inventory", -1);
		beer.newItemPrefab = "Food_Beer";
		beer.newItemPath = "assets/data/prefabs/items/consumables/food/food_beer.prefab";
		assertTrue(new InventoryManager(saves[1]).apply(Arrays.asList(toStash, sold, beer)));

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject items = section(comparison, "items");

		final JSONObject moved = row(group(items, "Moved"), "ITEM PET Astral Piglet");
		assertEquals("Elwyn · pack", moved.getString("before"));
		assertEquals("Stash", moved.getString("after"));

		final JSONObject lost = row(group(items, "Lost"), "Ring PREORDER Gauns Pledge");
		assertEquals("Elwyn · pack", lost.getString("before"));
		assertFalse(lost.has("after"));

		final JSONObject gained = row(group(items, "Gained"), "food beer");
		assertEquals("Calisca · pack · ×3", gained.getString("after"));
		assertFalse(gained.has("before"));

		assertEquals("lists, parents and packets are all the items' own"
			, Collections.singletonList("items"), ids(comparison));
		accountedFor(comparison);
	}

	@Test
	public void wornGearIsPlacedByItsSlot () throws Exception {
		final File[] saves = pair();
		assertTrue(new InventoryManager(saves[1]).apply(Collections.singletonList(
			InventoryManager.Change.equip(ELWYN, "PlayerInventory", RING, ELWYN, 4))));

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject moved = row(group(section(comparison, "items"), "Moved")
			, "Ring PREORDER Gauns Pledge");

		assertEquals("Elwyn · pack", moved.getString("before"));
		assertEquals("Elwyn · right-hand ring", moved.getString("after"));
		accountedFor(comparison);
	}

	@Test
	public void abilitiesAndTalentsAreLearnedAndLost () throws Exception {
		final File[] saves = pair();
		assertTrue(new AbilityManager(saves[1]).apply(Arrays.asList(
			AbilityManager.Change.removeAbility(ELWYN, KNOCKDOWN)
			, AbilityManager.Change.addTalent(
				CALISCA, "TLN_Cautious_Attack", Collections.emptyList(), Collections.emptyMap()))));

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject abilities = section(comparison, "abilities");

		// Without a catalog a name is its prefab's, as on the Abilities tab.
		final JSONObject knockDown = row(group(abilities, "Elwyn"), "KnockDown");
		assertEquals("ability", knockDown.getString("before"));
		assertFalse(knockDown.has("after"));

		final JSONObject talent = row(group(abilities, "Calisca"), "TLN Cautious Attack");
		assertEquals("talent", talent.getString("after"));
		assertFalse(talent.has("before"));

		assertEquals(Collections.singletonList("abilities"), ids(comparison));
		accountedFor(comparison);
	}

	// An item's abilities belong to whoever wears it, so handing the item over
	// moves them too: their parent and owner are the abilities' news.
	@Test
	public void anAbilityThatChangesHandsIsLostByOneAndLearnedByTheOther () throws Exception {
		final File[] saves = pair();
		edit(saves[1], packets -> {
			final ComplexProperty knockDown = packet(packets, KNOCKDOWN);
			set(knockDown.findProperty("Parent").get(), "Companion_Calisca(Clone)_1");
			set(variable(knockDown, "GenericAbility", "Owner"), java.util.UUID.fromString(CALISCA));
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject abilities = section(comparison, "abilities");

		assertEquals("ability", row(group(abilities, "Elwyn"), "KnockDown").getString("before"));
		assertEquals("ability", row(group(abilities, "Calisca"), "KnockDown").getString("after"));
		assertEquals(Collections.singletonList("abilities"), ids(comparison));
		accountedFor(comparison);
	}

	@Test
	public void eachGlobalVariableIsShownByName () throws Exception {
		final File[] saves = pair();
		final String[] name = new String[1];
		final Object[] was = new Object[1];
		edit(saves[1], packets -> {
			final DictionaryProperty globals = (DictionaryProperty)
				variable(packet(packets, IN_GAME_GLOBAL), "GlobalVariables", "m_data");
			final Map.Entry<Property, Property> entry = globals.items.get(0);
			name[0] = (String) entry.getKey().obj;
			was[0] = entry.getValue().obj;
			set(entry.getValue(), (Integer) was[0] + 7);
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject globals = section(comparison, "globals").getJSONArray("groups").getJSONObject(0);

		assertEquals(Collections.singletonList(name[0]), labels(globals));
		assertEquals(((Integer) was[0]).intValue(), row(globals, name[0]).getInt("before"));
		assertEquals((Integer) was[0] + 7, row(globals, name[0]).getInt("after"));
		accountedFor(comparison);
	}

	@Test
	public void theStrongholdIsDescribedAsItsScreenWould () throws Exception {
		final File[] saves = pair();
		final File catalog = EKUtils.createTempDir(PREFIX).get();
		FileUtils.write(new File(catalog, "stronghold.json"), "{\"maxHirelings\":8,\"upgrades\":{"
			+ "\"EasternBarbican\":{\"ordinal\":27,\"name\":\"Eastern Barbican\",\"cost\":0,\"days\":0"
			+ ",\"prestige\":1,\"security\":2,\"order\":1,\"global\":\"b_Eastern_Barbican\"}}}", "UTF-8");
		StrongholdCatalog.useCatalogAt(catalog);

		assertTrue(new StrongholdManager(saves[1]).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.addUpgrade("EasternBarbican"))));

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject keep = section(comparison, "stronghold").getJSONArray("groups").getJSONObject(0);

		assertTrue(row(keep, "Owned").getBoolean("after"));
		assertEquals("built", row(keep, "Eastern Barbican").getString("after"));
		assertEquals(1, row(keep, "Prestige").getInt("after"));
		assertEquals(2, row(keep, "Security").getInt("after"));

		// The upgrade's own global is a global like any other.
		assertEquals(1, row(section(comparison, "globals").getJSONArray("groups").getJSONObject(0)
			, "b_Eastern_Barbican").getInt("after"));
		accountedFor(comparison);
	}

	// The fixture's grimoire is spliced onto Elwyn; GrimoireManagerTest says why.
	@Test
	public void aGrimoiresSpellsAreListedByBook () throws Exception {
		final File[] saves = pair("GrimoireManagerTest/MobileObjects.save");
		final String book = "11111111-2222-3333-4444-555555555555";
		assertTrue(new GrimoireManager(saves[0]).apply(Collections.singletonList(
			new GrimoireManager.Change(book, Arrays.asList(
				new GrimoireManager.Spell("Fireball", 3), new GrimoireManager.Spell("Slicken", 1))))));
		assertTrue(new GrimoireManager(saves[1]).apply(Collections.singletonList(
			new GrimoireManager.Change(book, Arrays.asList(
				new GrimoireManager.Spell("Chill_Fog", 1), new GrimoireManager.Spell("Slicken", 1))))));

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONArray groups = section(comparison, "grimoires").getJSONArray("groups");
		assertEquals(1, groups.length());

		final JSONObject grimoire = groups.getJSONObject(0);
		assertTrue(grimoire.toString(), grimoire.getString("note").startsWith("Elwyn"));
		assertEquals(Arrays.asList("Chill Fog", "Fireball"), labels(grimoire));
		assertEquals("in the book", row(grimoire, "Chill Fog").getString("after"));
		assertFalse(row(grimoire, "Chill Fog").has("before"));
		assertEquals("in the book", row(grimoire, "Fireball").getString("before"));
		assertFalse(row(grimoire, "Fireball").has("after"));
		assertEquals(Collections.singletonList("grimoires"), ids(comparison));
		accountedFor(comparison);
	}

	// Where an object stands and the clocks the game keeps ticking change
	// between any two saves of a playthrough; they are counted, not listed.
	@Test
	public void placesAndTimersAreCountedNotListed () throws Exception {
		final File[] saves = pair();
		edit(saves[1], packets -> {
			final ComplexProperty location = (ComplexProperty) packet(packets, CALISCA).findProperty("Location").get();
			set(location.findProperty("x").get(), (Float) location.findProperty("x").get().obj + 3f);
			set(variable(packet(packets, ELWYN), "Health", "m_checkPartyDeathTimer"), 2.5f);
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);

		assertEquals(0, comparison.getJSONArray("sections").length());
		assertEquals(1, comparison.getJSONObject("leftOut").getInt("positions"));
		assertEquals(1, comparison.getJSONObject("leftOut").getInt("bookkeeping"));
		assertEquals(2, comparison.getJSONObject("counts").getInt("leftOut"));
		accountedFor(comparison);
	}

	// What none of the sections explains is still shown, by its object.
	@Test
	public void whatNothingElseExplainsIsShownAsItIs () throws Exception {
		final File[] saves = pair();
		edit(saves[1], packets -> {
			final Property kills = variable(packet(packets, IN_GAME_GLOBAL), "BestiaryManager", "m_TotalKills");
			set(kills, (Integer) kills.obj + 3);
			packets.remove(packet(packets, "24b1643a-f915-4e3e-b937-feb295b5fe76"));
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject other = section(comparison, "other");

		final JSONObject global = group(other, "In Game Global");
		assertEquals(3, row(global, "BestiaryManager · m_TotalKills").getInt("after")
			- row(global, "BestiaryManager · m_TotalKills").getInt("before"));

		final JSONObject gone = group(other, "No longer in the save");
		assertEquals("Encampment", row(gone, "PX2 SI Intro Dream").getString("before"));
		accountedFor(comparison);
	}

	@Test
	public void aStoresStockIsCountedInItsArea () throws Exception {
		final File[] saves = new File[] {VendorStockTest.setupSave(getClass()), VendorStockTest.setupSave(getClass())};
		for (final File save : saves) {
			FileUtils.copyFile(new File(new File(getClass().getResource("/").toURI())
				, "SaveGameInfoTest.saveinfo.xml"), new File(save, "saveinfo.xml"));
		}

		assertTrue(new VendorManager(saves[1]).apply(Collections.singletonList(
			new VendorManager.Removal(VendorStockTest.ARTIFICER_HALL, VendorStockTest.STORE_ARTIFICER
				, Collections.singletonList(new VendorManager.Entry(0, VendorStockTest.TRAP_ARROW))))));

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject areas = section(comparison, "areas");

		final JSONObject changed = group(areas, "Changed");
		assertEquals(Collections.singletonList("Artificer Hall"), labels(changed));

		final JSONObject stores = group(areas, "Stores");
		final JSONObject store = stores.getJSONArray("rows").getJSONObject(0);
		assertEquals(2, store.getInt("before"));
		assertEquals(1, store.getInt("after"));
		assertEquals("items", store.getString("format"));
		accountedFor(comparison);
	}

	// "Companion_Calisca(Clone)_1" is "_2" after the next load, and everything
	// she owns names her by the new number.
	@Test
	public void theGameRenumberingItsClonesIsNoChange () throws Exception {
		final File[] saves = pair();
		final int[] children = new int[1];
		edit(saves[1], packets -> {
			set(packet(packets, CALISCA).findProperty("ObjectName").get(), "Companion_Calisca(Clone)_2");
			for (final Property property : packets) {
				final Property parent = ((ComplexProperty) property).findProperty("Parent").get();
				if ("Companion_Calisca(Clone)_1".equals(parent.obj)) {
					set(parent, "Companion_Calisca(Clone)_2");
					children[0]++;
				}
			}
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);

		assertTrue(children[0] > 3);
		assertEquals("her abilities are still hers", Collections.emptyList(), ids(comparison));
		assertEquals(1 + children[0], comparison.getJSONObject("leftOut").getInt("bookkeeping"));
		accountedFor(comparison);
	}

	// The game makes some items afresh under a new ID -- the stash's stacks,
	// mostly -- for the same thing in the same place.
	@Test
	public void anItemTheGameMadeAfreshIsTheSameItem () throws Exception {
		final File[] saves = pair();
		final String fresh = "aaaabbbb-cccc-dddd-eeee-ffff00001111";
		edit(saves[1], packets -> {
			final ComplexProperty piglet = packet(packets, PIGLET);
			set(piglet.findProperty("ObjectID").get(), fresh);
			set(variable(piglet, "InstanceID", "Guid"), java.util.UUID.fromString(fresh));

			final uk.me.mantas.eternity.serializer.properties.CollectionProperty guids =
				(uk.me.mantas.eternity.serializer.properties.CollectionProperty)
					variable(packet(packets, ELWYN), "PlayerInventory", "SerializedItemList");
			for (final Property guid : guids.items) {
				if (PIGLET.equals(String.valueOf(guid.obj))) {
					set(guid, java.util.UUID.fromString(fresh));
				}
			}
		});

		JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		assertEquals(Collections.emptyList(), ids(comparison));
		accountedFor(comparison);

		// In another number it is the same thing in another quantity.
		assertTrue(new InventoryManager(saves[1]).apply(Collections.singletonList(
			new InventoryManager.Change(ELWYN, "PlayerInventory", fresh, 4))));
		comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject quantity = row(group(section(comparison, "items"), "Quantity"), "ITEM PET Astral Piglet");
		assertEquals("Elwyn · pack · ×1", quantity.getString("before"));
		assertEquals("Elwyn · pack · ×4", quantity.getString("after"));
		accountedFor(comparison);
	}

	// Put in the stash, an item can come out of it under another ID: in two
	// real saves fifty minutes apart, boots from the Watcher's pack did.
	@Test
	public void anItemMadeAfreshSomewhereElseHasMoved () throws Exception {
		final File[] saves = pair();
		assertTrue(new InventoryManager(saves[1]).apply(Collections.singletonList(new InventoryManager.Change(
			ELWYN, "PlayerInventory", PIGLET, 1, ELWYN, "StashInventory", -1))));

		final String fresh = "aaaabbbb-cccc-dddd-eeee-ffff00002222";
		edit(saves[1], packets -> {
			final ComplexProperty piglet = packet(packets, PIGLET);
			set(piglet.findProperty("ObjectID").get(), fresh);
			set(variable(piglet, "InstanceID", "Guid"), java.util.UUID.fromString(fresh));

			final uk.me.mantas.eternity.serializer.properties.CollectionProperty guids =
				(uk.me.mantas.eternity.serializer.properties.CollectionProperty)
					variable(packet(packets, ELWYN), "StashInventory", "SerializedItemList");
			for (final Property guid : guids.items) {
				if (PIGLET.equals(String.valueOf(guid.obj))) {
					set(guid, java.util.UUID.fromString(fresh));
				}
			}
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject items = section(comparison, "items");
		final JSONObject moved = row(group(items, "Moved"), "ITEM PET Astral Piglet");
		assertEquals("Elwyn · pack", moved.getString("before"));
		assertEquals("Stash", moved.getString("after"));
		assertEquals(1, items.getJSONArray("groups").length());
		accountedFor(comparison);
	}

	// A .NET list records the size of the buffer behind it, which grows on
	// its own as items come and go.
	@Test
	public void aListsBufferGrowingIsNoChange () throws Exception {
		final File[] saves = pair();
		edit(saves[1], packets -> {
			final ComplexProperty items = (ComplexProperty) variable(packet(packets, ELWYN), "PlayerInventory", "ItemList");
			set(items.findProperty("Capacity").get(), 8);
		});

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);

		assertEquals(Collections.emptyList(), ids(comparison));
		assertEquals(1, comparison.getJSONObject("leftOut").getInt("bookkeeping"));
		assertEquals("Capacity", comparison.getJSONObject("leftOut").getJSONArray("names").getString(0));
		accountedFor(comparison);
	}

	// An item's enchantments are a list of mod prefabs that the game sorts
	// afresh on load; only one gained or lost is news.
	@Test
	public void enchantmentsAreComparedAsASet () throws Exception {
		final File[] saves = pair();
		final String extra = "Assets/Data/Prefabs/ItemMods/ArmorMods/OfResolve2.prefab";
		edit(saves[0], packets -> mods(packets).items.add(mod(mods(packets), extra)));
		edit(saves[1], packets -> mods(packets).items.add(0, mod(mods(packets), extra)));

		JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		assertEquals(Collections.emptyList(), ids(comparison));
		assertTrue(comparison.getJSONObject("leftOut").getJSONArray("names").toList().contains("Mods"));
		accountedFor(comparison);

		edit(saves[1], packets -> mods(packets).items.remove(0));
		comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONObject lost = row(group(section(comparison, "items"), "Enchantments"), "Ring PREORDER Gauns Pledge");
		assertEquals("Of Resolve2", lost.getString("before"));
		assertFalse(lost.has("after"));
		accountedFor(comparison);
	}

	private static uk.me.mantas.eternity.serializer.properties.CollectionProperty mods (final List<Property> packets) {
		return (uk.me.mantas.eternity.serializer.properties.CollectionProperty)
			variable(packet(packets, RING), "Equippable", "Mods");
	}

	private static Property mod (
		final uk.me.mantas.eternity.serializer.properties.CollectionProperty mods, final String path) {

		final uk.me.mantas.eternity.serializer.properties.SimpleProperty mod =
			new uk.me.mantas.eternity.serializer.properties.SimpleProperty(null, mods.items.get(0).type);
		mod.value = path;
		mod.obj = path;
		return mod;
	}

	// Timestamps keys each quest the party has met by its file, with when
	// each objective was reached; the journal's words are the game's own.
	@Test
	public void aQuestIsNamedAndSaysWhatWasReached () throws Exception {
		final File game = EKUtils.createTempDir(PREFIX).get();
		FileUtils.write(new File(game
			, "PillarsOfEternity_Data/data/localized/en/text/quests/00_dyrford_hendyna.stringtable")
			, "<StringTableFile><Entries>"
				+ "<Entry><ID>0</ID><DefaultText>Nest Egg</DefaultText></Entry>"
				+ "<Entry><ID>3</ID><DefaultText>Bring Hendyna the dragon egg.</DefaultText></Entry>"
				+ "</Entries></StringTableFile>", "UTF-8");

		final File[] saves = pair();
		edit(saves[1], packets -> {
			final DictionaryProperty timestamps = (DictionaryProperty)
				variable(packet(packets, IN_GAME_GLOBAL), "QuestManager", "Timestamps");
			final ComplexProperty quest = (ComplexProperty)
				timestamps.findEntry("data/quests/00_dyrford_hendyna.quest").get();
			@SuppressWarnings("unchecked")
			final List<Object> objectives = ((uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty)
				quest.findProperty("objectiveTimestamps").get()).items;

			// A reached objective holds the game's clock when it was reached.
			final ComplexProperty now = (ComplexProperty) variable(packet(packets, GLOBAL), "WorldTime", "CurrentTime");
			final ComplexProperty reached = new ComplexProperty(null, now.type);
			for (final Property field : now.properties) {
				final uk.me.mantas.eternity.serializer.properties.SimpleProperty copy =
					new uk.me.mantas.eternity.serializer.properties.SimpleProperty(field.name, field.type);
				copy.value = ((uk.me.mantas.eternity.serializer.properties.SimpleProperty) field).value;
				copy.obj = field.obj;
				reached.properties.add(copy);
			}

			objectives.set(3, reached);
		});

		JSONObject row = section(SaveComparison.compare(saves[0], saves[1]), "journal")
			.getJSONArray("groups").getJSONObject(0).getJSONArray("rows").getJSONObject(0);
		assertEquals("without the game's text, the file has to do", "Dyrford hendyna", row.getString("label"));

		uk.me.mantas.eternity.save.GameText.useTextAt(game);
		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		row = row(section(comparison, "journal").getJSONArray("groups").getJSONObject(0), "Nest Egg");
		assertEquals("under way", row.getString("before"));
		assertEquals("under way", row.getString("after"));
		assertEquals("Bring Hendyna the dragon egg.", row.getString("note"));
		accountedFor(comparison);
	}

	@Test
	public void savesFromTwoPlaythroughsSaySo () throws Exception {
		final File[] saves = pair();
		info(saves[1], "SessionID", "11111111-2222-3333-4444-555555555555");

		assertFalse(SaveComparison.compare(saves[0], saves[1]).getBoolean("samePlaythrough"));
	}

	@Test
	public void aDamagedSaveIsComparedAsFarAsItReads () throws Exception {
		final File[] saves = pair();
		final File world = new File(saves[1], "MobileObjects.save");
		final byte[] whole = FileUtils.readFileToByteArray(world);
		FileUtils.writeByteArrayToFile(world, Arrays.copyOf(whole, whole.length - 2000));

		final JSONObject comparison = SaveComparison.compare(saves[0], saves[1]);
		final JSONArray problems = comparison.getJSONArray("problems");

		assertEquals(1, problems.length());
		assertTrue(problems.getString(0), problems.getString(0).contains("could be read"));
		assertTrue(hasSection(comparison, "other") || hasSection(comparison, "characters"));
	}
}
