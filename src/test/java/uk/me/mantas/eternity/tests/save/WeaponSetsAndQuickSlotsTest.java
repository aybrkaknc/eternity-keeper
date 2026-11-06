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
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.InventoryItem;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.InventoryManager.Change;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.serializer.CSharpCollection;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

// Weapon sets and quick slots, held to the game's own rules.
//
// The fixture is the prologue save with one real addition: Gyrd Haewanes
// Stenes, a two-handed sceptre spliced in from a mid-game save, sits in the
// main hand of Calisca's second weapon set, soulbound to Calisca. So she holds
// a battle axe and a torch in set I and the sceptre alone in set II, and Elwyn
// holds a sword and a heater shield in his set I. Both have two usable sets
// (no BonusWeaponSets) and four quick slots.
//
// The rules, all decompiled:
//   Equippable.CanUseSlot -- the main hand wants PrimaryWeaponSlot; the off
//     hand wants SecondaryWeaponSlot and NOT BothPrimaryAndSecondarySlot, which
//     is what makes a weapon two-handed.
//   UIInventoryGridItem.ItemTransferValid -- a two-hander shares its set with
//     nothing ("Two-handed weapon requires two slots.").
//   Equippable.WhyCantEquip -- an item soulbound to someone else cannot be
//     equipped (SoulboundToOther).
//   CharacterStats.MaxWeaponSets = 2 + BonusWeaponSets.
//   BaseInventory.CanPutItem -- no container but the stash takes a stack over
//     the item's MaxStackSize; a quick bar takes any item at all.
public class WeaponSetsAndQuickSlotsTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String AXE = "a683cc76-919d-446c-bc63-986d99ba9262";
	private static final String TORCH = "8052f588-f385-40c0-8743-33d2e0bdd86e";
	private static final String SCEPTRE = "9a493248-f2d7-42bf-9eb1-9c7475efbd04";
	private static final String SWORD = "5b1a11ca-ddcd-452f-84b3-bc651af00fad";
	private static final String RING = "c4b7033d-c452-4946-a9ba-9998cb411201";
	private static final String PET = "41baf584-9203-44df-a921-62f50000ad74";
	private static final String EMPTY = "";

	// Only what the tests need, in catalog.json's own shape.
	private static final String CATALOG = "{"
		+ "\"battle_axe\":{\"name\":\"Battle Axe\",\"slots\":[\"PrimaryWeaponSlot\",\"SecondaryWeaponSlot\"]}"
		+ ",\"torch01\":{\"name\":\"Torch\",\"slots\":[\"PrimaryWeaponSlot\",\"SecondaryWeaponSlot\"]}"
		+ ",\"sceptre_gyrd_haewanes_stenes\":{\"name\":\"Gyrd Háewanes Sténes\",\"quality\":\"soulbound\""
		+ ",\"slots\":[\"PrimaryWeaponSlot\",\"SecondaryWeaponSlot\",\"BothPrimaryAndSecondarySlot\"]}"
		+ ",\"sword\":{\"name\":\"Sword\",\"slots\":[\"PrimaryWeaponSlot\",\"SecondaryWeaponSlot\"]}"
		+ ",\"shield_medium_heater\":{\"name\":\"Medium Shield\",\"slots\":[\"SecondaryWeaponSlot\"]}"
		+ ",\"food_beer\":{\"name\":\"Beer\",\"maxStack\":5,\"filter\":16}"
		+ "}";

	private File setupSave () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File saveDir = new File(workingDir.get(), "cadena 0 Test.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(getClass().getResource("/").toURI());
		FileUtils.copyFileToDirectory(
			new File(resources, "InventoryManagerTest/MobileObjects.save"), saveDir);

		final Optional<File> catalogDir = EKUtils.createTempDir(PREFIX);
		assertTrue(catalogDir.isPresent());
		FileUtils.write(new File(catalogDir.get(), "catalog.json"), CATALOG, "UTF-8");
		ItemCatalog.useCatalogAt(catalogDir.get());

		return saveDir;
	}

	@After
	public void restoreCatalog () {
		ItemCatalog.useNoCatalog();
	}

	private static DeserializedPackets deserialize (final File saveDir) throws IOException {
		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(new File(saveDir, "MobileObjects.save")).deserialize();

		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	private static ObjectPersistencePacket packetOf (
		final List<Property> packets, final String objectID) {

		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (objectID.equalsIgnoreCase(packet.ObjectID)) {
				return packet;
			}
		}

		fail("no packet with ObjectID " + objectID);
		return null;
	}

	private static List<String> guids (final Object collection) {
		assertTrue(collection instanceof CSharpCollection);
		final List<String> guids = new ArrayList<>();
		for (final Iterator it = ((CSharpCollection) collection).iterator(); it.hasNext();) {
			final Object guid = it.next();
			final String text = guid == null ? "" : guid.toString();
			guids.add("00000000-0000-0000-0000-000000000000".equals(text) ? "" : text);
		}

		return guids;
	}

	private static ComponentPersistencePacket component (
		final File saveDir, final String character, final String name) throws IOException {

		final Optional<ComponentPersistencePacket> found = EKUtils.findComponent(
			packetOf(deserialize(saveDir).getPackets(), character).ComponentPackets, name);

		assertTrue(name + " missing", found.isPresent());
		return found.get();
	}

	/** Eight GUIDs, main and off hand of each of the four sets; "" is empty. */
	private static List<String> weaponSets (final File saveDir, final String character)
		throws IOException {

		return guids(component(saveDir, character, "Equipment")
			.Variables.get("WeaponSetsSerialized"));
	}

	private static List<String> carried (
		final File saveDir, final String character, final String container)
		throws IOException {

		return guids(component(saveDir, character, container)
			.Variables.get("SerializedItemList"));
	}

	private static List<InventoryItem> entries (
		final File saveDir, final String character, final String container)
		throws IOException {

		final Object list = component(saveDir, character, container).Variables.get("ItemList");
		assertTrue(list instanceof CSharpCollection);

		final List<InventoryItem> items = new ArrayList<>();
		for (final Iterator it = ((CSharpCollection) list).iterator(); it.hasNext();) {
			items.add((InventoryItem) it.next());
		}

		return items;
	}

	private static byte[] bytes (final File saveDir) throws IOException {
		return FileUtils.readFileToByteArray(new File(saveDir, "MobileObjects.save"));
	}

	/**
	 * Caps a container, the way the fixture's 16-slot packs become full ones
	 * without carrying sixteen items.
	 */
	private static void setMaxItems (
		final File saveDir, final String character, final String container, final int maxItems)
		throws IOException {

		final DeserializedPackets deserialized = deserialize(saveDir);
		for (final Property p : deserialized.getPackets()) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			if (!character.equalsIgnoreCase(((ObjectPersistencePacket) p.obj).ObjectID)) continue;

			final Optional<Property> entry = SaveObject.of(p)
				.flatMap(o -> o.component(container))
				.flatMap(inv -> inv.variable("MaxItems"));

			assertTrue(entry.isPresent());
			assertTrue(Property.update(entry.get(), maxItems));
		}

		deserialized.replace(new File(saveDir, "MobileObjects.save"));
	}

	/**
	 * What the editor sends to move a weapon from one set slot to another: it
	 * parks the weapon in its owner's pack on the way. The player's pack is a
	 * PlayerInventory, everyone else's a plain Inventory.
	 */
	private static List<Change> moveWeapon (
		final String from, final int fromSlot, final String item
		, final String to, final int toSlot) {

		final String pack = ELWYN.equals(from) ? "PlayerInventory" : "Inventory";
		return Arrays.asList(
			Change.unequipWeapon(from, fromSlot, item, from, pack, -1)
			, Change.equipWeapon(from, pack, item, to, toSlot));
	}

	private static String problemOf (final InventoryManager manager) {
		return manager.problem().orElse("(no problem reported)");
	}

	@Test
	public void theFixtureHoldsWhatItSays () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		assertEquals(Arrays.asList(AXE, TORCH, SCEPTRE, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY)
			, weaponSets(saveDir, CALISCA));
		assertEquals(SWORD, weaponSets(saveDir, ELWYN).get(0));
	}

	// ---- two hands --------------------------------------------------------

	@Test
	public void aTwoHandedWeaponCannotGoInTheOffHand () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		final byte[] before = bytes(saveDir);
		final InventoryManager manager = new InventoryManager(saveDir);

		assertFalse(manager.apply(moveWeapon(CALISCA, 2, SCEPTRE, CALISCA, 3)));
		assertTrue(problemOf(manager), problemOf(manager).contains("Gyrd Háewanes Sténes"));
		assertTrue(problemOf(manager), problemOf(manager).contains("main hand"));

		// Refused means untouched, not half done.
		assertArrayEquals(before, bytes(saveDir));
	}

	@Test
	public void aTwoHandedWeaponNeedsItsSetToItself () throws URISyntaxException, IOException {
		// Dropping the sceptre on the axe swaps the two: the axe goes back to
		// set II, the sceptre lands beside the torch -- which the game refuses.
		final File saveDir = setupSave();
		final byte[] before = bytes(saveDir);
		final InventoryManager manager = new InventoryManager(saveDir);

		assertFalse(manager.apply(Arrays.asList(
			Change.unequipWeapon(CALISCA, 0, AXE, CALISCA, "Inventory", -1)
			, Change.unequipWeapon(CALISCA, 2, SCEPTRE, CALISCA, "Inventory", -1)
			, Change.equipWeapon(CALISCA, "Inventory", SCEPTRE, CALISCA, 0)
			, Change.equipWeapon(CALISCA, "Inventory", AXE, CALISCA, 2))));

		assertTrue(problemOf(manager), problemOf(manager).contains("two slots"));
		assertArrayEquals(before, bytes(saveDir));
	}

	@Test
	public void nothingJoinsATwoHanderInItsSet () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		final byte[] before = bytes(saveDir);
		final InventoryManager manager = new InventoryManager(saveDir);

		assertFalse(manager.apply(moveWeapon(CALISCA, 1, TORCH, CALISCA, 3)));
		assertTrue(problemOf(manager), problemOf(manager).contains("two slots"));
		assertArrayEquals(before, bytes(saveDir));
	}

	@Test
	public void aTwoHanderOnItsOwnIsFine () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			Change.unequipWeapon(CALISCA, 0, AXE, CALISCA, "Inventory", -1)
			, Change.unequipWeapon(CALISCA, 1, TORCH, CALISCA, "Inventory", -1)
			, Change.unequipWeapon(CALISCA, 2, SCEPTRE, CALISCA, "Inventory", -1)
			, Change.equipWeapon(CALISCA, "Inventory", SCEPTRE, CALISCA, 0))));

		assertEquals(Arrays.asList(SCEPTRE, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY)
			, weaponSets(saveDir, CALISCA));
		assertTrue(carried(saveDir, CALISCA, "Inventory").containsAll(Arrays.asList(AXE, TORCH)));
	}

	@Test
	public void aShieldOnlyGoesInTheOffHand () throws URISyntaxException, IOException {
		final File saveDir = setupSave();
		final byte[] before = bytes(saveDir);
		final InventoryManager manager = new InventoryManager(saveDir);

		final List<String> sets = weaponSets(saveDir, ELWYN);
		final String shield = sets.get(1);

		// Set II's main hand, which a shield cannot take.
		assertFalse(manager.apply(moveWeapon(ELWYN, 1, shield, ELWYN, 2)));
		assertTrue(problemOf(manager), problemOf(manager).contains("main hand"));
		assertArrayEquals(before, bytes(saveDir));
	}

	@Test
	public void aWeaponSetNobodyHasUnlockedStaysEmpty () throws URISyntaxException, IOException {
		// MaxWeaponSets is 2 + BonusWeaponSets, and neither of them has the talent.
		final File saveDir = setupSave();
		final byte[] before = bytes(saveDir);
		final InventoryManager manager = new InventoryManager(saveDir);

		assertFalse(manager.apply(moveWeapon(CALISCA, 0, AXE, CALISCA, 4)));
		assertTrue(problemOf(manager), problemOf(manager).contains("weapon set III"));
		assertArrayEquals(before, bytes(saveDir));
	}

	// ---- soulbinding ------------------------------------------------------

	@Test
	public void aSoulboundWeaponCannotBeWieldedBySomeoneElse ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		final byte[] before = bytes(saveDir);
		final InventoryManager manager = new InventoryManager(saveDir);

		assertFalse(manager.apply(moveWeapon(CALISCA, 2, SCEPTRE, ELWYN, 2)));
		assertTrue(problemOf(manager), problemOf(manager).contains("soulbound to Calisca"));
		assertArrayEquals(before, bytes(saveDir));
	}

	@Test
	public void aSoulboundWeaponCanStillBeCarriedBySomeoneElse ()
		throws URISyntaxException, IOException {

		// Only equipping checks the binding; the game lets anyone carry it.
		final File saveDir = setupSave();

		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			Change.unequipWeapon(CALISCA, 2, SCEPTRE, ELWYN, "PlayerInventory", -1))));

		assertTrue(carried(saveDir, ELWYN, "PlayerInventory").contains(SCEPTRE));
		assertEquals(EMPTY, weaponSets(saveDir, CALISCA).get(2));
	}

	@Test
	public void aSoulboundWeaponMovesFreelyBetweenItsOwnersSets ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();

		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			Change.unequipWeapon(CALISCA, 0, AXE, CALISCA, "Inventory", -1)
			, Change.unequipWeapon(CALISCA, 1, TORCH, CALISCA, "Inventory", -1)
			, Change.unequipWeapon(CALISCA, 2, SCEPTRE, CALISCA, "Inventory", -1)
			, Change.equipWeapon(CALISCA, "Inventory", SCEPTRE, CALISCA, 0))));

		assertEquals(SCEPTRE, weaponSets(saveDir, CALISCA).get(0));
	}

	// ---- capacity is judged on where things end up ------------------------

	@Test
	public void swappingTwoWeaponsNeedsNoRoomInThePack ()
		throws URISyntaxException, IOException {

		// The editor parks each weapon in its owner's pack on the way from
		// one slot to the other. A full pack must not stop a swap that leaves
		// the pack exactly as full as it was.
		final File saveDir = setupSave();
		setMaxItems(saveDir, CALISCA, "Inventory", 0);

		final InventoryManager manager = new InventoryManager(saveDir);
		assertTrue(problemOf(manager), manager.apply(Arrays.asList(
			Change.unequipWeapon(CALISCA, 0, AXE, CALISCA, "Inventory", -1)
			, Change.unequipWeapon(CALISCA, 1, TORCH, CALISCA, "Inventory", -1)
			, Change.equipWeapon(CALISCA, "Inventory", AXE, CALISCA, 1)
			, Change.equipWeapon(CALISCA, "Inventory", TORCH, CALISCA, 0))));

		assertEquals(Arrays.asList(TORCH, AXE, SCEPTRE, EMPTY, EMPTY, EMPTY, EMPTY, EMPTY)
			, weaponSets(saveDir, CALISCA));
		assertEquals(0, carried(saveDir, CALISCA, "Inventory").size());
	}

	@Test
	public void twoFullPacksCanTradeItems () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		// Elwyn carries the ring and the pet; give Calisca the pet, then make
		// both packs exactly full.
		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(new Change(
			ELWYN, "PlayerInventory", PET, 1, CALISCA, "Inventory", -1))));
		setMaxItems(saveDir, ELWYN, "PlayerInventory", 1);
		setMaxItems(saveDir, CALISCA, "Inventory", 1);

		final InventoryManager manager = new InventoryManager(saveDir);
		assertTrue(problemOf(manager), manager.apply(Arrays.asList(
			new Change(ELWYN, "PlayerInventory", RING, 1, CALISCA, "Inventory", -1)
			, new Change(CALISCA, "Inventory", PET, 1, ELWYN, "PlayerInventory", -1))));

		assertEquals(Arrays.asList(PET), carried(saveDir, ELWYN, "PlayerInventory"));
		assertEquals(Arrays.asList(RING), carried(saveDir, CALISCA, "Inventory"));
	}

	@Test
	public void aPackStillCannotEndUpFullerThanItCanHold ()
		throws URISyntaxException, IOException {

		final File saveDir = setupSave();
		setMaxItems(saveDir, CALISCA, "Inventory", 1);
		final byte[] before = bytes(saveDir);

		final InventoryManager manager = new InventoryManager(saveDir);
		assertFalse(manager.apply(Arrays.asList(
			new Change(ELWYN, "PlayerInventory", RING, 1, CALISCA, "Inventory", -1)
			, new Change(ELWYN, "PlayerInventory", PET, 1, CALISCA, "Inventory", -1))));

		assertTrue(problemOf(manager), problemOf(manager).contains("room for 1"));
		assertArrayEquals(before, bytes(saveDir));
	}

	// ---- quick slots ------------------------------------------------------

	@Test
	public void anyItemCanGoInAQuickSlot () throws URISyntaxException, IOException {
		// The game puts no type filter on a quick slot: the HUD even offers
		// non-consumables from it (UIAbilityBarButtonSet, last quick-item row).
		final File saveDir = setupSave();

		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			new Change(ELWYN, "PlayerInventory", RING, 1, ELWYN, "QuickbarInventory", 3))));

		assertEquals(Arrays.asList(RING), carried(saveDir, ELWYN, "QuickbarInventory"));
		assertEquals(3, entries(saveDir, ELWYN, "QuickbarInventory").get(0).uiSlot);
		assertFalse(carried(saveDir, ELWYN, "PlayerInventory").contains(RING));
	}

	@Test
	public void aQuickItemCanGoToSomeoneElsesQuickSlots () throws URISyntaxException, IOException {
		final File saveDir = setupSave();

		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			new Change(ELWYN, "PlayerInventory", PET, 1, ELWYN, "QuickbarInventory", 0))));
		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			new Change(ELWYN, "QuickbarInventory", PET, 1, CALISCA, "QuickbarInventory", 2))));

		assertEquals(Arrays.asList(PET), carried(saveDir, CALISCA, "QuickbarInventory"));
		assertEquals(2, entries(saveDir, CALISCA, "QuickbarInventory").get(0).uiSlot);

		// Whoever carries it owns its packet.
		final DeserializedPackets after = deserialize(saveDir);
		assertEquals(packetOf(after.getPackets(), CALISCA).ObjectName
			, packetOf(after.getPackets(), PET).Parent);
	}

	private static Change mint (
		final String guid, final String prefab, final String container, final int slot) {

		final Change change = new Change(ELWYN, container, guid, 1, ELWYN, container, slot);
		change.newItemPrefab = prefab;
		change.newItemPath = "assets/data/prefabs/items/consumables/food/" + prefab.toLowerCase() + ".prefab";
		return change;
	}

	@Test
	public void anItemLandsOnTheSlotItAskedForOnceTheOccupantHasLeft ()
		throws URISyntaxException, IOException {

		// A full quick bar: beer, ale, the pet and the ring on slots 0-3.
		final File saveDir = setupSave();
		final String wine = "aaaa0000-0000-0000-0000-000000000003";
		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			mint("aaaa0000-0000-0000-0000-000000000001", "Food_Beer", "QuickbarInventory", 0)
			, mint("aaaa0000-0000-0000-0000-000000000002", "Food_Ale", "QuickbarInventory", 1)
			, new Change(ELWYN, "PlayerInventory", PET, 1, ELWYN, "QuickbarInventory", 2)
			, new Change(ELWYN, "PlayerInventory", RING, 1, ELWYN, "QuickbarInventory", 3)
			, mint(wine, "Food_Wine", "PlayerInventory", 5))));

		// Trade the wine for the ring: the wine asks for slot 3 while the ring
		// is still on it. It must end up there, not on a fifth quick slot the
		// bar does not have.
		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			new Change(ELWYN, "PlayerInventory", wine, 1, ELWYN, "QuickbarInventory", 3)
			, new Change(ELWYN, "QuickbarInventory", RING, 1, ELWYN, "PlayerInventory", 5))));

		final List<String> quick = carried(saveDir, ELWYN, "QuickbarInventory");
		final List<InventoryItem> slots = entries(saveDir, ELWYN, "QuickbarInventory");
		assertEquals(3, slots.get(quick.indexOf(wine)).uiSlot);

		final List<String> pack = carried(saveDir, ELWYN, "PlayerInventory");
		assertEquals(5, entries(saveDir, ELWYN, "PlayerInventory").get(pack.indexOf(RING)).uiSlot);
	}

	@Test
	public void aStackBiggerThanTheItemsCapOnlyFitsInTheStash ()
		throws URISyntaxException, IOException {

		// The stash stacks without limit; everything else holds MaxStackSize.
		final File saveDir = setupSave();
		final String beer = "11112222-3333-4444-5555-666677778888";

		final Change mint = new Change(ELWYN, "StashInventory", beer, 12, ELWYN, "StashInventory", -1);
		mint.newItemPrefab = "Food_Beer";
		mint.newItemPath = "assets/data/prefabs/items/consumables/food/food_beer.prefab";
		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(mint)));

		final byte[] before = bytes(saveDir);
		final InventoryManager manager = new InventoryManager(saveDir);
		assertFalse(manager.apply(Arrays.asList(
			new Change(ELWYN, "StashInventory", beer, 12, ELWYN, "QuickbarInventory", -1))));

		assertTrue(problemOf(manager), problemOf(manager).contains("stacks to 5"));
		assertArrayEquals(before, bytes(saveDir));

		// What the game does instead is split it (UIInventoryGridItem.
		// TryMergeInto): a new stack of five in the quick slot, seven left
		// behind. A new stack is a new object, so it is minted.
		final String five = "99998888-7777-6666-5555-444433332222";
		final Change split = new Change(ELWYN, "QuickbarInventory", five, 5
			, ELWYN, "QuickbarInventory", 1);
		split.newItemPrefab = "Food_Beer";
		split.newItemPath = "assets/data/prefabs/items/consumables/food/food_beer.prefab";

		assertTrue(new InventoryManager(saveDir).apply(Arrays.asList(
			new Change(ELWYN, "StashInventory", beer, 7), split)));

		assertEquals(5, entries(saveDir, ELWYN, "QuickbarInventory").get(0).stackSize);
		assertEquals(7, entries(saveDir, ELWYN, "StashInventory").get(0).stackSize);
	}
}
