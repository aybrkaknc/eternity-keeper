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
import uk.me.mantas.eternity.game.CharacterStats;
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.InventoryManager.Change;
import uk.me.mantas.eternity.save.ItemCatalog;
import uk.me.mantas.eternity.save.Loadout;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static uk.me.mantas.eternity.tests.save.SaveDiffTest.packet;
import static uk.me.mantas.eternity.tests.save.SaveDiffTest.set;
import static uk.me.mantas.eternity.tests.save.SaveDiffTest.variable;
import static uk.me.mantas.eternity.tests.save.SaveDiffTest.variables;

// One character's gear as a file, and putting it on someone else: in the same
// save, or in another playthrough altogether. What goes on is always a copy,
// with IDs of its own; what it replaces goes to the stash; and the game's own
// rules decide what cannot go on at all, slot by slot, rather than refusing
// the lot.
//
// The fixture is InventoryManagerTest's: the prologue party, where Elwyn wears
// mail and holds a sword and a heater shield, and Calisca wears scale and
// holds a battle axe and a torch, with Gyrd Haewanes Stenes -- a two-hander
// soulbound to her -- alone in her second set. Gaun's Pledge and the Astral
// Piglet are in Elwyn's pack.
public class LoadoutTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String MAIL = "5ea3dc07-93bc-46a2-ba3c-d7c14660a127";
	private static final String SCALE = "329dd68e-f108-409c-b58d-51698d0d14d0";
	private static final String SWORD = "5b1a11ca-ddcd-452f-84b3-bc651af00fad";
	private static final String SHIELD = "eff2c4bb-1a24-47d7-b71f-430292d3a6ca";
	private static final String AXE = "a683cc76-919d-446c-bc63-986d99ba9262";
	private static final String TORCH = "8052f588-f385-40c0-8743-33d2e0bdd86e";
	private static final String GYRD = "9a493248-f2d7-42bf-9eb1-9c7475efbd04";
	private static final String RING = "c4b7033d-c452-4946-a9ba-9998cb411201";
	private static final String PIGLET = "41baf584-9203-44df-a921-62f50000ad74";
	private static final String GRIMOIRE = "11111111-2222-3333-4444-555555555555";

	@After
	public void restoreCatalog () {
		ItemCatalog.useNoCatalog();
	}

	private static File setup (final String fixture) throws Exception {
		final File folder = EKUtils.createTempDir(PREFIX).get();
		final File save = new File(folder, "cadena 0 Test.savegame");
		assertTrue(save.mkdir());
		FileUtils.copyFile(new File(new File(LoadoutTest.class.getResource("/").toURI()), fixture)
			, new File(save, "MobileObjects.save"));

		return save;
	}

	private static File inventorySave () throws Exception {
		return setup("InventoryManagerTest/MobileObjects.save");
	}

	private static File file (final String name) {
		return new File(EKUtils.createTempDir(PREFIX).get(), name + ".loadout");
	}

	private static List<Property> packets (final File save) throws IOException {
		final Optional<DeserializedPackets> read =
			new PacketDeserializer(new File(save, "MobileObjects.save")).deserialize();
		assertTrue(read.isPresent());
		return read.get().getPackets();
	}

	/** Changes a save's world state in place, the way an edit lands. */
	private static void edit (final File save, final Consumer<List<Property>> change) throws IOException {
		final File world = new File(save, "MobileObjects.save");
		final DeserializedPackets read = new PacketDeserializer(world).deserialize().get();
		change.accept(read.getPackets());
		set(read.getCount(), read.getPackets().size());
		read.replace(world);
	}

	private static void apply (final File save, final Change... changes) throws IOException {
		final InventoryManager manager = new InventoryManager(save);
		assertTrue(manager.problem().orElse("refused"), manager.apply(Arrays.asList(changes)));
	}

	private static List<String> guids (final Property list) {
		final List<String> guids = new ArrayList<>();
		for (final Property item : ((CollectionProperty) list).items) {
			final String guid = String.valueOf(item.obj).toLowerCase();
			guids.add(guid.startsWith("00000000-0000") ? "" : guid);
		}

		return guids;
	}

	private static List<String> worn (final List<Property> packets, final String who) {
		return guids(variable(packet(packets, who), "Equipment", "EquipmentSetSerialized"));
	}

	private static List<String> weapons (final List<Property> packets, final String who) {
		return guids(variable(packet(packets, who), "Equipment", "WeaponSetsSerialized"));
	}

	/** A container's items by the tile they sit on. */
	private static Map<Integer, String> tiles (final List<Property> packets, final String who, final String component) {
		final List<String> ids = guids(variable(packet(packets, who), component, "SerializedItemList"));
		final List<Property> entries = ((CollectionProperty) variable(packet(packets, who), component, "ItemList")).items;
		final Map<Integer, String> tiles = new TreeMap<>();
		for (int i = 0; i < entries.size(); i++) {
			tiles.put((Integer) ((ComplexProperty) entries.get(i)).findProperty("uiSlot").get().obj, ids.get(i));
		}

		return tiles;
	}

	private static List<String> stash (final List<Property> packets) {
		return guids(variable(packet(packets, ELWYN), "StashInventory", "SerializedItemList"));
	}

	private static String prefab (final List<Property> packets, final String guid) {
		return String.valueOf(packet(packets, guid).findProperty("ObjectName").get().obj).replace("(Clone)", "");
	}

	private static String text (final List<Property> packets, final String guid, final String field) {
		return String.valueOf(packet(packets, guid).findProperty(field).get().obj);
	}

	private static List<String> describe (final Loadout loadout) {
		final List<String> slots = new ArrayList<>();
		loadout.slots.forEach(slot -> slots.add(slot.kind + " " + slot.index + " " + slot.prefab));
		return slots;
	}

	private static Loadout.Fit fit (final Loadout.Plan plan, final String prefab) {
		for (final Loadout.Fit fit : plan.fits) {
			if (fit.slot.prefab.equals(prefab)) {
				return fit;
			}
		}

		throw new AssertionError("no " + prefab + " in the plan");
	}

	private static Loadout saved (final File save, final String who) throws IOException {
		final File file = file("gear");
		assertTrue(Loadout.save(save, who, file) > 0);
		return Loadout.read(file);
	}

	@Test
	public void aLoadoutIsWhatACharacterWearsHoldsAndKeepsToHand () throws Exception {
		final File save = inventorySave();
		apply(save, Change.equip(ELWYN, "PlayerInventory", RING, ELWYN, 4)
			, Change.equip(ELWYN, "PlayerInventory", PIGLET, ELWYN, 10));

		final File file = file("Elwyn");
		assertEquals(5, Loadout.save(save, ELWYN, file));

		final Loadout loadout = Loadout.read(file);
		assertEquals(ELWYN, loadout.characterId);
		assertEquals("Elwyn", loadout.characterName);
		assertEquals("Fighter", loadout.characterClass);
		assertEquals(Arrays.asList(
			"WORN 2 Mail_Armor", "WORN 4 Ring_PREORDER_Gauns_Pledge", "WORN 10 ITEM_PET_Astral_Piglet"
			, "WEAPON 0 Sword", "WEAPON 1 Shield_Medium_Heater"), describe(loadout));
	}

	// Not what they carry, and not what they know: a loadout is their gear.
	@Test
	public void onlyTheGearGoesInTheFile () throws Exception {
		final File file = file("Elwyn");
		assertEquals(3, Loadout.save(inventorySave(), ELWYN, file));

		final List<String> objects = new ArrayList<>();
		for (final Property object : new PacketDeserializer(file).deserialize().get().getPackets()) {
			objects.add(String.valueOf(((ComplexProperty) object).findProperty("ObjectName").get().obj));
		}

		assertEquals(Arrays.asList(
			"Player_Elwyn", "Mail_Armor(Clone)", "Sword(Clone)", "Shield_Medium_Heater(Clone)"), objects);
	}

	@Test
	public void someoneWithNothingOnHasNoLoadout () throws Exception {
		final File save = inventorySave();
		apply(save, Change.unequip(ELWYN, 2, MAIL, ELWYN, "StashInventory", -1)
			, Change.unequipWeapon(ELWYN, 0, SWORD, ELWYN, "StashInventory", -1)
			, Change.unequipWeapon(ELWYN, 1, SHIELD, ELWYN, "StashInventory", -1));

		final File file = file("Elwyn");
		assertEquals(0, Loadout.save(save, ELWYN, file));
		assertFalse("no empty file is left looking like a loadout", file.exists());
	}

	@Test
	public void puttingItOnSomeoneElseGivesThemCopies () throws Exception {
		final File save = inventorySave();
		apply(save, Change.equip(ELWYN, "PlayerInventory", RING, ELWYN, 4));
		final Loadout loadout = saved(save, ELWYN);

		final Loadout.Plan plan = loadout.plan(packets(save), CALISCA);
		assertEquals(4, plan.fits.size());
		assertNull(fit(plan, "Mail_Armor").reason);
		assertEquals(SCALE, fit(plan, "Mail_Armor").replaces);
		assertNull(fit(plan, "Ring_PREORDER_Gauns_Pledge").replaces);
		assertEquals(AXE, fit(plan, "Sword").replaces);
		assertEquals(TORCH, fit(plan, "Shield_Medium_Heater").replaces);
		assertEquals(Collections.emptyList(), plan.cleared);

		assertEquals(Optional.empty(), loadout.putOn(save, CALISCA));
		final List<Property> after = packets(save);

		final List<String> worn = worn(after, CALISCA);
		final List<String> held = weapons(after, CALISCA);
		assertEquals("Mail_Armor", prefab(after, worn.get(2)));
		assertEquals("Ring_PREORDER_Gauns_Pledge", prefab(after, worn.get(4)));
		assertEquals("Sword", prefab(after, held.get(0)));
		assertEquals("Shield_Medium_Heater", prefab(after, held.get(1)));
		assertEquals("her second set is not in the loadout", GYRD, held.get(2));

		for (final String copy : Arrays.asList(worn.get(2), worn.get(4), held.get(0), held.get(1))) {
			assertFalse("a copy has an ID of its own", Arrays.asList(MAIL, RING, SWORD, SHIELD).contains(copy));
			assertEquals(copy, String.valueOf(variable(packet(after, copy), "InstanceID", "Guid").obj));
			assertEquals("Companion_Calisca(Clone)_1", text(after, copy, "Parent"));
		}

		assertEquals("the originals stay where they were"
			, Arrays.asList(MAIL, RING), Arrays.asList(worn(after, ELWYN).get(2), worn(after, ELWYN).get(4)));
		assertEquals(Arrays.asList(SWORD, SHIELD), weapons(after, ELWYN).subList(0, 2));
		assertTrue("what she wore is in the stash", stash(after).containsAll(Arrays.asList(SCALE, AXE, TORCH)));
		assertEquals("the stash is the player's", "Player_Elwyn", text(after, SCALE, "Parent"));
	}

	// Equippable.WhyCantEquip: nobody but its owner can wear an item
	// soulbound to them, so a loadout leaves it behind and says why.
	@Test
	public void soulboundGearStaysWithItsOwner () throws Exception {
		final File save = inventorySave();
		final Loadout loadout = saved(save, CALISCA);
		final Loadout.Plan plan = loadout.plan(packets(save), ELWYN);

		assertEquals("soulbound to Calisca", fit(plan, "Sceptre_Gyrd_Haewanes_Stenes").reason);
		assertNull(fit(plan, "Battle_Axe").reason);

		assertEquals(Optional.empty(), loadout.putOn(save, ELWYN));
		final List<Property> after = packets(save);
		assertEquals("Battle_Axe", prefab(after, weapons(after, ELWYN).get(0)));
		assertEquals("", weapons(after, ELWYN).get(2));
	}

	@Test
	public void itsOwnerTakesSoulboundGearIntoAnotherSave () throws Exception {
		final Loadout loadout = saved(inventorySave(), CALISCA);
		final File other = setup("GrimoireManagerTest/MobileObjects.save");
		assertNull(fit(loadout.plan(packets(other), CALISCA), "Sceptre_Gyrd_Haewanes_Stenes").reason);

		assertEquals(Optional.empty(), loadout.putOn(other, CALISCA));
		final List<Property> after = packets(other);
		final String gyrd = weapons(after, CALISCA).get(2);
		assertEquals("Sceptre_Gyrd_Haewanes_Stenes", prefab(after, gyrd));
		assertEquals(CALISCA, String.valueOf(variable(packet(after, gyrd), "EquipmentSoulbind", "BoundGuid").obj));
	}

	// The game knows the main character and every companion by the same ID in
	// every playthrough, so a refusal names the owner as the save the gear is
	// going into knows them: the one there who can wear it.
	@Test
	public void soulboundGearNamesWhoCanWearItThere () throws Exception {
		final Loadout loadout = saved(inventorySave(), CALISCA);
		final File other = setup("GrimoireManagerTest/MobileObjects.save");
		edit(other, packets -> set(variable(packet(packets, CALISCA), "CharacterStats", "OverrideName"), "Kalista"));

		assertEquals("soulbound to Kalista"
			, fit(loadout.plan(packets(other), ELWYN), "Sceptre_Gyrd_Haewanes_Stenes").reason);
	}

	// A weapon set is a pair, and a two-hander shares its set with nothing:
	// a set the loadout fills is put on whole, both hands.
	@Test
	public void aWeaponSetGoesOnWhole () throws Exception {
		final Loadout loadout = saved(inventorySave(), CALISCA);
		final File other = setup("GrimoireManagerTest/MobileObjects.save");
		apply(other, Change.unequipWeapon(CALISCA, 1, TORCH, CALISCA, "Inventory", -1)
			, Change.equipWeapon(CALISCA, "Inventory", TORCH, CALISCA, 3));

		final Loadout.Plan plan = loadout.plan(packets(other), CALISCA);
		assertEquals(Collections.singletonList(TORCH), plan.cleared);

		assertEquals(Optional.empty(), loadout.putOn(other, CALISCA));
		final List<Property> after = packets(other);
		assertEquals("Sceptre_Gyrd_Haewanes_Stenes", prefab(after, weapons(after, CALISCA).get(2)));
		assertEquals("", weapons(after, CALISCA).get(3));
		assertTrue(stash(after).contains(TORCH));
	}

	@Test
	public void slotsSomeoneDoesNotHaveAreLeftOut () throws Exception {
		final File save = inventorySave();
		apply(save, Change.equip(ELWYN, "PlayerInventory", RING, ELWYN, 0)
			, Change.equip(ELWYN, "PlayerInventory", PIGLET, ELWYN, 10));
		edit(save, packets -> {
			set(variable(packet(packets, ELWYN), "CharacterStats", "BonusWeaponSets"), 1);
			final Property held = variable(packet(packets, ELWYN), "Equipment", "WeaponSetsSerialized");
			final List<Property> hands = ((CollectionProperty) held).items;
			set(hands.get(4), hands.get(0).obj);
			set(hands.get(0), hands.get(2).obj);
			set(variable(packet(packets, CALISCA), "CharacterStats", "CharacterRace"), CharacterStats.Race.Godlike);
		});

		final Loadout.Plan plan = saved(save, ELWYN).plan(packets(save), CALISCA);
		assertEquals("no head slot (godlike)", fit(plan, "Ring_PREORDER_Gauns_Pledge").reason);
		assertEquals("only the main character has a pet slot", fit(plan, "ITEM_PET_Astral_Piglet").reason);
		assertEquals("weapon set III is locked", fit(plan, "Sword").reason);
		assertNull(fit(plan, "Mail_Armor").reason);
	}

	@Test
	public void aGrimoireGoesOnlyToAWizard () throws Exception {
		final File save = setup("GrimoireManagerTest/MobileObjects.save");
		edit(save, packets -> {
			set(((CollectionProperty) variable(packet(packets, ELWYN), "Equipment", "EquipmentSetSerialized"))
				.items.get(9), java.util.UUID.fromString(GRIMOIRE));
			set(variable(packet(packets, ELWYN), "CharacterStats", "CharacterClass"), CharacterStats.Class.Wizard);
		});

		final Loadout loadout = saved(save, ELWYN);
		assertEquals("only wizards have a grimoire slot"
			, fit(loadout.plan(packets(save), CALISCA), "Aloth_Grimoire").reason);

		edit(save, packets -> set(
			variable(packet(packets, CALISCA), "CharacterStats", "CharacterClass"), CharacterStats.Class.Wizard));
		assertEquals(Optional.empty(), loadout.putOn(save, CALISCA));

		final List<Property> after = packets(save);
		final String copy = worn(after, CALISCA).get(9);
		assertNotEquals(GRIMOIRE, copy);
		assertFalse(valuesOf(variable(packet(after, copy), "Grimoire", "SerializedSpellNames")).isEmpty());
		assertEquals("the book keeps its spells"
			, valuesOf(variable(packet(after, GRIMOIRE), "Grimoire", "SerializedSpellNames"))
			, valuesOf(variable(packet(after, copy), "Grimoire", "SerializedSpellNames")));
	}

	private static List<Object> valuesOf (final Property list) {
		final List<Object> values = new ArrayList<>();
		((CollectionProperty) list).items.forEach(item -> values.add(item.obj));
		return values;
	}

	// Equippable.RestrictedToClass, out of the item catalog.
	@Test
	public void gearForAnotherClassIsLeftOut () throws Exception {
		final File catalog = EKUtils.createTempDir(PREFIX).get();
		FileUtils.write(new File(catalog, "catalog.json"), "{\"mail_armor\":{\"name\":\"Mail Armor\""
			+ ",\"slots\":[\"ArmorSlot\"],\"classes\":[\"Paladin\",\"Priest\"]"
			+ ",\"path\":\"Assets/Data/Prefabs/Items/Armor/Mail_Armor.prefab\"}}", "UTF-8");
		ItemCatalog.useCatalogAt(catalog);

		final File save = inventorySave();
		final Loadout.Plan plan = saved(save, ELWYN).plan(packets(save), CALISCA);
		assertEquals("for paladins and priests only", fit(plan, "Mail_Armor").reason);
	}

	@Test
	public void quickItemsKeepTheirTileAndTheirStack () throws Exception {
		final File save = inventorySave();
		apply(save, new Change(ELWYN, "PlayerInventory", PIGLET, 1, ELWYN, "QuickbarInventory", 2));
		final Loadout loadout = saved(save, ELWYN);
		assertTrue(describe(loadout).contains("QUICK 2 ITEM_PET_Astral_Piglet"));

		assertEquals(Optional.empty(), loadout.putOn(save, CALISCA));
		final List<Property> after = packets(save);
		final Map<Integer, String> quick = tiles(after, CALISCA, "QuickbarInventory");
		assertEquals(Collections.singleton(2), quick.keySet());
		assertEquals("ITEM_PET_Astral_Piglet", prefab(after, quick.get(2)));
	}

	// CharacterStats.MaxQuickSlots = 4 + BonusQuickSlots.
	@Test
	public void aQuickSlotSomeoneHasNotGotIsLeftOut () throws Exception {
		final File save = inventorySave();
		edit(save, packets -> {
			set(variable(packet(packets, ELWYN), "CharacterStats", "BonusQuickSlots"), 1);
			set(variable(packet(packets, ELWYN), "QuickbarInventory", "MaxItems"), 5);
		});
		apply(save, new Change(ELWYN, "PlayerInventory", PIGLET, 1, ELWYN, "QuickbarInventory", 4));

		final Loadout.Plan plan = saved(save, ELWYN).plan(packets(save), CALISCA);
		assertEquals("quick slot 5 is locked", fit(plan, "ITEM_PET_Astral_Piglet").reason);
	}

	// An item's enchantments that grant an ability, and a pet's summoned
	// creature, are other objects the item names by ID. A copy names none:
	// ItemModComponent.Initialize makes a new ability when it finds none by
	// that ID, and a summon with nothing summoned summons afresh -- where
	// sharing the original's would tie two items to one object.
	@Test
	public void aCopyGetsEnchantmentsAndAPetOfItsOwn () throws Exception {
		final File save = inventorySave();
		apply(save, Change.equip(ELWYN, "PlayerInventory", RING, ELWYN, 4)
			, Change.equip(ELWYN, "PlayerInventory", PIGLET, ELWYN, 10));
		final Loadout loadout = saved(save, ELWYN);

		final File other = setup("InventoryManagerTest/MobileObjects.save");
		assertEquals(Optional.empty(), loadout.putOn(other, ELWYN));

		final List<Property> after = packets(other);
		final String ring = worn(after, ELWYN).get(4);
		final String pet = worn(after, ELWYN).get(10);
		assertEquals(0, ((CollectionProperty) variable(packet(after, ring), "Equippable", "AbilityModGuids")).items.size());
		assertEquals(0, ((CollectionProperty) variable(packet(after, pet), "Summon", "m_summons")).items.size());
		assertEquals("the original keeps its own", 1
			, ((CollectionProperty) variable(packet(packets(save), RING), "Equippable", "AbilityModGuids")).items.size());
	}

	@Test
	public void aFileThatIsNotALoadoutIsRefused () throws Exception {
		final File save = inventorySave();
		try {
			Loadout.read(new File(save, "MobileObjects.save"));
			fail("a world state is not a loadout");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("not a loadout"));
		}

		final File text = file("notes");
		FileUtils.write(text, "Aloth's gear, from memory", "UTF-8");
		try {
			Loadout.read(text);
			fail("text is not a loadout");
		} catch (final IOException expected) {
			assertTrue(expected.getMessage(), expected.getMessage().contains("not a loadout"));
		}
	}
}
