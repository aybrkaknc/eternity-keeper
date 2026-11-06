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

import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.save.PortraitCatalog;
import uk.me.mantas.eternity.save.PortraitCatalog.Portrait;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

// What portraits a player can actually choose from.
//
// A save stores two plain strings on the Portrait component,
// m_textureLargePath and m_textureSmallPath, both relative to
// PillarsOfEternity_Data. Nothing else: decompiled Portrait.Start() loads
// whatever those name and only derives a path from CompanionInstanceID when one
// is empty. GUIUtils.LoadTexture2DFromPathCallback then does
// Path.Combine(Application.dataPath, path) and reads the file straight off
// disk, which is why a portrait the player dropped in themselves works exactly
// like a shipped one -- and why the catalog lists the directory rather than a
// table typed out here.
//
// The two paths are a pair by convention (X_lg.png / X_sm.png), and both have
// to exist: writing a large path whose small counterpart is missing leaves the
// party bar showing a blank white square.
public class PortraitCatalogTest extends TestHarness {
	private File gameDirectory () throws URISyntaxException {
		final File resources = new File(getClass().getResource("/").toURI());
		return new File(resources, "SavedGameOpenerTest");
	}

	private PortraitCatalog catalog () throws URISyntaxException {
		PortraitCatalog.useCatalogAt(gameDirectory());
		return PortraitCatalog.getInstance();
	}

	@After
	public void restoreCatalog () {
		PortraitCatalog.useNoCatalog();
	}

	@Test
	public void itFindsEveryCompletePair () throws URISyntaxException {
		final List<Portrait> all = catalog().all();

		// Two complete pairs in the fixture, plus one large with no small,
		// which is deliberately not offered.
		assertEquals(2, all.size());

		for (final Portrait portrait : all) {
			assertTrue(portrait.large.endsWith("_lg.png"));
			assertTrue(portrait.small.endsWith("_sm.png"));
		}
	}

	@Test
	public void aLargeWithNoSmallIsNotOffered () throws URISyntaxException {
		// The game would load it and hand the party bar a white texture, so
		// offering it would be offering a broken choice.
		assertFalse(catalog().all().stream()
			.anyMatch(portrait -> portrait.name.equals("Kaylon")));
	}

	@Test
	public void pathsAreRelativeToTheDataDirectory () throws URISyntaxException {
		final Optional<Portrait> calisca = catalog().all().stream()
			.filter(portrait -> portrait.name.equals("portrait_calisca"))
			.findFirst();

		assertTrue(calisca.isPresent());
		assertEquals(
			"data/art/gui/portraits/companion/portrait_calisca_lg.png"
			, calisca.get().large);
		assertEquals(
			"data/art/gui/portraits/companion/portrait_calisca_sm.png"
			, calisca.get().small);
	}

	@Test
	public void portraitsAreGroupedTheWayTheDirectoryIs () throws URISyntaxException {
		final PortraitCatalog catalog = catalog();

		assertEquals("companion", catalog.all().stream()
			.filter(p -> p.name.equals("portrait_calisca"))
			.findFirst().get().category);

		assertEquals("player/female", catalog.all().stream()
			.filter(p -> p.name.equals("Shilesque"))
			.findFirst().get().category);

		assertTrue(catalog.categories().contains("companion"));
		assertTrue(catalog.categories().contains("player/female"));
	}

	@Test
	public void aStoredPathResolvesBackToItsPortrait () throws URISyntaxException {
		// The character view has to be able to say which portrait is the one
		// currently in use, and all it has is the path out of the save.
		final Optional<Portrait> found = catalog().lookup(
			"data/art/gui/portraits/companion/portrait_calisca_lg.png");

		assertTrue(found.isPresent());
		assertEquals("portrait_calisca", found.get().name);

		// The save writes forward slashes, but a path that came from Windows
		// would not, and neither would carry a consistent case.
		assertTrue(catalog().lookup(
			"data\\art\\gui\\portraits\\companion\\Portrait_Calisca_LG.png").isPresent());

		assertFalse(catalog().lookup("data/art/gui/portraits/nope_lg.png").isPresent());
		assertFalse(catalog().lookup("").isPresent());
		assertFalse(catalog().lookup(null).isPresent());
	}

	@Test
	public void theImageComesBackAsBase64 () throws URISyntaxException {
		final PortraitCatalog catalog = catalog();
		final String data = catalog.imageData(
			"data/art/gui/portraits/companion/portrait_calisca_sm.png");

		assertFalse(data.isEmpty());

		// Nothing outside the portrait directory is readable through this,
		// whatever the caller asks for.
		assertEquals("", catalog.imageData("../../../../../../etc/passwd"));
		assertEquals("", catalog.imageData("data/art/gui/portraits/missing_sm.png"));
		assertEquals("", catalog.imageData(""));
	}

	@Test
	public void noGameInstallIsNotAnError () {
		PortraitCatalog.useNoCatalog();
		final PortraitCatalog catalog = PortraitCatalog.getInstance();

		assertTrue(catalog.all().isEmpty());
		assertTrue(catalog.categories().isEmpty());
		assertFalse(catalog.lookup("data/art/gui/portraits/x_lg.png").isPresent());
		assertEquals("", catalog.imageData("data/art/gui/portraits/x_sm.png"));
	}
}
