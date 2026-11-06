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
import uk.me.mantas.eternity.game.StrongholdUpgrade;
import uk.me.mantas.eternity.save.StrongholdCatalog;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

// What the game says an upgrade costs and is worth.
//
// The numbers are the game's own, out of the Stronghold behaviour on the
// InGameGlobal prefab, because Stronghold.CompleteBuildingUpgrade() applies
// PrestigeAdjustment and SecurityAdjustment exactly once -- at the moment of
// building -- and nothing recomputes them on load. An editor that does not know
// them cannot add an upgrade without leaving the save inconsistent.
public class StrongholdCatalogTest extends TestHarness {
	private static final String CATALOG = "{"
		+ "\"maxHirelings\":8"
		+ ",\"upgrades\":{"
		// The one upgrade the player starts with: free, instant, and the only
		// one that drives a global variable.
		+ "\"EasternBarbican\":{\"ordinal\":27,\"name\":\"Eastern Barbican\""
		+ ",\"description\":\"Gates the Woodend Plains.\",\"cost\":0,\"days\":0"
		+ ",\"prestige\":1,\"security\":2,\"order\":1,\"destructible\":0"
		+ ",\"global\":\"b_Eastern_Barbican\",\"icon\":\"eastern_barbican.png\"}"
		+ ",\"MainKeep\":{\"ordinal\":4,\"name\":\"Main Keep\""
		+ ",\"description\":\"Repairs the Great Hall.\",\"cost\":1400,\"days\":3"
		+ ",\"prestige\":3,\"security\":4,\"order\":4,\"destructible\":1"
		+ ",\"prerequisite\":\"EasternBarbican\"}"
		+ ",\"Bailey\":{\"ordinal\":3,\"name\":\"Bailey\""
		+ ",\"description\":\"Keeps the stronghold self-sufficient.\""
		+ ",\"cost\":800,\"days\":2,\"prestige\":2,\"security\":0,\"order\":5"
		+ ",\"destructible\":1,\"prerequisite\":\"MainKeep\"}"
		+ ",\"Forum\":{\"ordinal\":12,\"name\":\"Forum\",\"description\":\"A forum.\""
		+ ",\"cost\":1200,\"days\":2,\"prestige\":4,\"security\":0,\"order\":17"
		+ ",\"destructible\":1,\"prerequisite\":\"Bailey\",\"hasBoon\":1}"
		+ "}}";

	private StrongholdCatalog catalog () throws IOException {
		final Optional<File> directory = EKUtils.createTempDir(PREFIX);
		assertTrue(directory.isPresent());
		FileUtils.write(
			new File(directory.get(), "stronghold.json"), CATALOG, "UTF-8");

		StrongholdCatalog.useCatalogAt(directory.get());
		return StrongholdCatalog.getInstance();
	}

	@After
	public void restoreCatalog () {
		StrongholdCatalog.useNoCatalog();
	}

	@Test
	public void readsWhatAnUpgradeCostsAndIsWorth () throws IOException {
		final StrongholdCatalog catalog = catalog();
		final StrongholdCatalog.Upgrade keep = catalog.lookup("MainKeep").get();

		assertEquals("Main Keep", keep.name);
		assertEquals(1400, keep.cost);
		assertEquals(3, keep.days);
		assertEquals(3, keep.prestige);
		assertEquals(4, keep.security);
	}

	@Test
	public void findsAnUpgradeByTheOrdinalTheSaveStores () throws IOException {
		// A save records StrongholdUpgrade.Type by ordinal, and our mirror enum
		// spells one of them differently from the game (ArtificersHall vs the
		// game's own AritficersHall typo), so the ordinal is the identity.
		final StrongholdCatalog catalog = catalog();
		assertEquals("MainKeep", catalog.lookup(StrongholdUpgrade.Type.MainKeep).get().key);
		assertEquals(
			StrongholdUpgrade.Type.EasternBarbican.ordinal()
			, catalog.lookup("EasternBarbican").get().ordinal);
	}

	@Test
	public void knowsWhichUpgradeHasToComeFirst () throws IOException {
		final StrongholdCatalog catalog = catalog();
		assertEquals("EasternBarbican", catalog.lookup("MainKeep").get().prerequisite);
		assertFalse(catalog.lookup("EasternBarbican").get().hasPrerequisite());
	}

	@Test
	public void listsUpgradesInTheOrderTheGameShowsThem () throws IOException {
		final List<StrongholdCatalog.Upgrade> all = catalog().all();

		assertEquals(4, all.size());
		assertEquals("EasternBarbican", all.get(0).key);
		assertEquals("MainKeep", all.get(1).key);
		assertEquals("Bailey", all.get(2).key);
		assertEquals("Forum", all.get(3).key);
	}

	@Test
	public void onlyOneUpgradeDrivesAGlobalVariable () throws IOException {
		// Building sets UpgradeCompletedGlobalVariableName to 1 and destroying
		// puts it back to 0. Only the Eastern Barbican actually names one, but
		// the editor has to honour it wherever it appears.
		final StrongholdCatalog catalog = catalog();
		assertEquals(
			"b_Eastern_Barbican", catalog.lookup("EasternBarbican").get().global);

		assertTrue(catalog.lookup("MainKeep").get().global.isEmpty());
	}

	@Test
	public void anUnbuildableUpgradeIsNotInTheCatalogAtAll () throws IOException {
		// StrongholdUpgrade.Type has 28 values but the game only configures 25;
		// BeastVault, RoadRepairs and AdditionalStorage are cut content and
		// must never be offered.
		final StrongholdCatalog catalog = catalog();
		assertFalse(catalog.lookup(StrongholdUpgrade.Type.BeastVault).isPresent());
		assertFalse(catalog.lookup("RoadRepairs").isPresent());
	}

	@Test
	public void aMissingCatalogKnowsNothing () {
		StrongholdCatalog.useNoCatalog();
		final StrongholdCatalog catalog = StrongholdCatalog.getInstance();

		assertTrue(catalog.all().isEmpty());
		assertFalse(catalog.lookup("MainKeep").isPresent());
	}
}
