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
import uk.me.mantas.eternity.save.VendorStock;
import uk.me.mantas.eternity.save.VendorStock.Item;
import uk.me.mantas.eternity.save.VendorStock.Vendor;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

// What every vendor in a save holds.
//
// A store's stock is not in the world state but in the area file of the level
// the store stands in, so the fixture is a save directory rather than one
// file: the world state of a prologue save, where Heodan's shop is the one
// store the world state carries, plus four real area files from a mid-game
// save -- the Artificer's Hall (an empty store on the NPC and the shop proper,
// two traps the player sold there), the Fishery on the White March (two
// uniques in a store the player never opened), Caed Nua's chapel (a priest
// who is a character with his own inventory as well as a store) and a bandit
// hideout with chests but no vendor at all.
public class VendorStockTest extends TestHarness {
	static final String ARTIFICER_HALL = "AR_0611_Artificer_Hall.lvl";
	static final String FISHERY = "PX1_0004_Fishery.lvl";
	static final String CHAPEL = "AR_0609_Chapel.lvl";
	static final String HIDEOUT = "AR_0810_Gilded_Vale_Hideout.lvl";
	static final String WORLD = "MobileObjects.save";

	static final String STORE_ARTIFICER = "36696af5-8025-4532-a5d3-0068fe04e380";
	static final String NPC_ARTIFICER = "cffb808b-dfe4-4e1b-be68-b609d490b705";
	static final String FISHERY_STORE = "3532ceaa-c805-4e7f-8f05-d299654b7189";
	static final String CHAPEL_PRIEST = "c95e51f4-95bf-47f1-b132-47cb07efe10f";
	static final String HEODAN = "b1a7e810-0000-0000-0000-000000000000";

	static final String TRAP_ARROW = "b33915e6-fee7-4a29-a043-b65d91b1200e";
	static final String TRAP_FLAMES = "e6dde585-121d-471e-9eea-417abd439287";
	static final String IVORY_WURM = "7cdf29f4-4454-417a-b79c-b3f31c53a47b";
	static final String RING_OF_OVERSEEING = "2da7138a-1d14-4200-b8e1-9c634f2c920f";
	static final String HEODAN_SHIELD = "b24045c4-cf83-4a97-a33b-97bc942c9780";
	static final String HEODAN_LOCKPICKS = "c8ee2ef2-219c-49bb-9dd3-9e394ecc9dd9";

	public static File setupSave (final Class<?> test) throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());

		final File saveDir = new File(workingDir.get(), "vendors.savegame");
		assertTrue(saveDir.mkdir());

		final File resources = new File(test.getResource("/").toURI());
		FileUtils.copyDirectory(new File(resources, "VendorsTest"), saveDir);
		return saveDir;
	}

	private static Vendor vendor (final List<Vendor> vendors, final String id) {
		return vendors.stream()
			.filter(v -> v.id.equalsIgnoreCase(id))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no vendor " + id + " in " + vendors));
	}

	private static Item item (final Vendor vendor, final String guid) {
		return vendor.items.stream()
			.filter(i -> i.guid.equalsIgnoreCase(guid))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no item " + guid + " at " + vendor.objectName));
	}

	@Test
	public void everyStoreInTheSaveIsFound () throws Exception {
		final VendorStock.Result result = VendorStock.read(setupSave(getClass()));

		assertEquals(
			"the five Store components: Heodan's in the world state, and four in the area files"
			, 5, result.vendors.size());

		assertTrue(result.unreadable.isEmpty());
		for (final String id : new String[] {
			HEODAN, NPC_ARTIFICER, STORE_ARTIFICER, FISHERY_STORE, CHAPEL_PRIEST}) {

			vendor(result.vendors, id);
		}
	}

	@Test
	public void aVendorSaysWhereItStands () throws Exception {
		final List<Vendor> vendors = VendorStock.read(setupSave(getClass())).vendors;

		final Vendor artificer = vendor(vendors, STORE_ARTIFICER);
		assertEquals(ARTIFICER_HALL, artificer.file);
		assertEquals("Store_Artificer", artificer.objectName);
		assertEquals("AR_0611_Artificer_Hall", artificer.level);
		assertEquals("Artificer", artificer.name);
		assertEquals("Artificer Hall", artificer.area);
		assertTrue("the player has traded here", artificer.opened);
		assertEquals(1.5f, artificer.sellMultiplier, 0.0001f);
		assertEquals(0.2f, artificer.buyMultiplier, 0.0001f);

		final Vendor heodan = vendor(vendors, HEODAN);
		assertEquals(WORLD, heodan.file);
		assertEquals("Heodan", heodan.name);
		assertEquals("Encampment", heodan.area);
		assertFalse("m_firstTime is still set", heodan.opened);
		assertEquals(0.75f, heodan.buyMultiplier, 0.0001f);
	}

	@Test
	public void theStockComesInTheStoresOwnOrder () throws Exception {
		final Vendor artificer =
			vendor(VendorStock.read(setupSave(getClass())).vendors, STORE_ARTIFICER);

		assertEquals(2, artificer.items.size());

		final Item arrow = artificer.items.get(0);
		assertEquals(0, arrow.index);
		assertEquals(TRAP_ARROW, arrow.guid);
		assertEquals("Trap_Item_Arrow", arrow.prefab);
		assertEquals(1, arrow.stack);
		assertFalse("sold there by the player", arrow.original);
		assertTrue(arrow.hasPacket);

		final Item flames = artificer.items.get(1);
		assertEquals(1, flames.index);
		assertEquals(TRAP_FLAMES, flames.guid);
		assertEquals("Trap_Item_Fan_of_Flames", flames.prefab);
		assertEquals(2, flames.stack);
	}

	@Test
	public void originalStockIsMarked () throws Exception {
		final Vendor fishery =
			vendor(VendorStock.read(setupSave(getClass())).vendors, FISHERY_STORE);

		assertFalse(fishery.opened);
		assertTrue(item(fishery, IVORY_WURM).original);
		assertTrue(item(fishery, RING_OF_OVERSEEING).original);
		assertEquals("Ring_of_Overseeing", item(fishery, RING_OF_OVERSEEING).prefab);
	}

	// Heodan's stock was never restored by the game, so its entries carry no
	// prefab path at all. The item's own packet still names it.
	@Test
	public void anItemWithNoPathIsNamedByItsPacket () throws Exception {
		final Vendor heodan = vendor(VendorStock.read(setupSave(getClass())).vendors, HEODAN);

		assertEquals(21, heodan.items.size());
		assertTrue(heodan.items.stream().allMatch(i -> i.original));
		assertEquals("Shield_Medium_Heater", item(heodan, HEODAN_SHIELD).prefab);
		assertEquals("Lockpick", item(heodan, HEODAN_LOCKPICKS).prefab);
		assertEquals(3, item(heodan, HEODAN_LOCKPICKS).stack);
	}

	// The priest is a character too, with an Inventory of his own. Only what
	// he sells is stock.
	@Test
	public void aCharactersOwnInventoryIsNotStock () throws Exception {
		final Vendor priest = vendor(VendorStock.read(setupSave(getClass())).vendors, CHAPEL_PRIEST);

		assertEquals(CHAPEL, priest.file);
		assertEquals("Chapel Priest", priest.name);
		assertEquals(
			"Scroll_of_Valor_L4, Scroll_of_Paralysis_L3, Scroll_of_Protection_L2"
			, priest.items.stream().map(i -> i.prefab).collect(Collectors.joining(", ")));
	}

	@Test
	public void anEmptyStoreIsStillAStore () throws Exception {
		final Vendor npc = vendor(VendorStock.read(setupSave(getClass())).vendors, NPC_ARTIFICER);
		assertTrue(npc.items.isEmpty());
		assertEquals(ARTIFICER_HALL, npc.file);
	}

	// Most area files hold no store. They are recognised without being read,
	// which is also why a damaged one of them cannot stop the list.
	@Test
	public void anAreaWithNoStoreIsNotRead () throws Exception {
		final File save = setupSave(getClass());
		assertFalse(VendorStock.mayHoldStore(new File(save, HIDEOUT)));
		assertTrue(VendorStock.mayHoldStore(new File(save, ARTIFICER_HALL)));
		assertTrue(VendorStock.mayHoldStore(new File(save, WORLD)));

		FileUtils.writeStringToFile(
			new File(save, "AR_9999_Broken.lvl"), "not a level", StandardCharsets.UTF_8);

		final VendorStock.Result result = VendorStock.read(save);
		assertEquals(5, result.vendors.size());
		assertTrue(result.unreadable.isEmpty());
	}

	// One that does name a store and cannot be read is reported, not hidden:
	// the list would otherwise look complete. The file is cut off just after
	// the store's TypeString, so it still says it holds one.
	@Test
	public void anUnreadableAreaWithAStoreIsReported () throws Exception {
		final File save = setupSave(getClass());
		final File chapel = new File(save, CHAPEL);
		final byte[] bytes = FileUtils.readFileToByteArray(chapel);
		final int store = indexOf(bytes, new byte[] {5, 'S', 't', 'o', 'r', 'e'});
		assertTrue(store > 0);
		FileUtils.writeByteArrayToFile(chapel, java.util.Arrays.copyOf(bytes, store + 16));
		assertTrue(VendorStock.mayHoldStore(chapel));

		final VendorStock.Result result = VendorStock.read(save);
		assertEquals(4, result.vendors.size());
		assertEquals(java.util.Collections.singletonList(CHAPEL), result.unreadable);
	}

	private static int indexOf (final byte[] haystack, final byte[] needle) {
		outer:
		for (int i = 0; i + needle.length <= haystack.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) {
					continue outer;
				}
			}

			return i;
		}

		return -1;
	}

	@Test
	public void oneVendorCanBeReadOnItsOwn () throws Exception {
		final Optional<Vendor> artificer =
			VendorStock.read(setupSave(getClass()), ARTIFICER_HALL, STORE_ARTIFICER);

		assertTrue(artificer.isPresent());
		assertEquals(2, artificer.get().items.size());

		assertFalse(VendorStock.read(setupSave(getClass()), ARTIFICER_HALL, FISHERY_STORE).isPresent());
	}

	@Test
	public void namesReadLikeNames () {
		assertEquals("Winfrith", VendorStock.vendorName("Store_Winfrith"));
		assertEquals("Black Hound (inn)", VendorStock.vendorName("Store_Inn_Black_Hound"));
		assertEquals("General Goods Merchant", VendorStock.vendorName("NPC_General_Goods_Merchant"));
		assertEquals("Yduran", VendorStock.vendorName("PX1_Store_Yduran"));
		assertEquals("Ponamu", VendorStock.vendorName("PX4_NPC_Ponamu"));
		assertEquals("Heodan", VendorStock.vendorName("companion_Heodan(Clone)_2"));
		assertEquals("Child Iwen", VendorStock.vendorName("CRE_Child_Iwen"));
		assertEquals("Steel Flagon (inn)", VendorStock.vendorName("PX1_Store_Inn_Steel_Flagon"));

		assertEquals("Dyrford Store", VendorStock.areaName("AR_0003_Dyrford_Store"));
		assertEquals("Steel Flagon", VendorStock.areaName("PX1_0002_Steel_Flagon"));
		assertEquals("Stronghold Great Hall", VendorStock.areaName("AR_0604_Stronghold_Great_Hall"));
		assertEquals("", VendorStock.areaName(null));
	}

	// The same arithmetic as Store.Restored: an old save may carry the
	// multipliers ten times over, and the game divides them back on load.
	@Test
	public void oversizedMultipliersAreReadAsTheGameReadsThem () {
		assertEquals(1.5f, VendorStock.sellMultiplier(15f), 0.0001f);
		assertEquals(1.5f, VendorStock.sellMultiplier(1.5f), 0.0001f);
		assertEquals(0.2f, VendorStock.buyMultiplier(2f), 0.0001f);
		assertEquals(0.75f, VendorStock.buyMultiplier(0.75f), 0.0001f);
	}
}
