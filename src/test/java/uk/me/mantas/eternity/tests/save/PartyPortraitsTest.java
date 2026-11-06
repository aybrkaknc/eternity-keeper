/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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
import org.junit.Before;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.save.PartyPortraits;
import uk.me.mantas.eternity.save.PartyPortraits.Member;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The game's load list shows a save's party as 0.png…5.png inside the save:
 * 32×41 thumbnails taken when the game last saved, in party-slot order. After
 * a party or portrait edit they showed the old faces, so Save redraws them
 * from each member's own small portrait.
 */
public class PartyPortraitsTest extends TestHarness {
	private static final String ELWYN = "data/art/gui/portraits/player/female/Shilesque_sm.png";
	private static final String CALISCA = "data/art/gui/portraits/companion/portrait_calisca_sm.png";

	private List<Property> packets;
	private File saveDirectory;
	private File gameData;

	@Before
	public void load () throws Exception {
		packets = new PacketDeserializer(
			new File(getClass().getResource("/MobileObjects.save").toURI()))
			.deserialize().get().getPackets();
		saveDirectory = EKUtils.createTempDir(PREFIX).get();
		gameData = EKUtils.createTempDir(PREFIX).get();
	}

	private void portrait (final String path, final Color colour) throws IOException {
		final BufferedImage image = new BufferedImage(76, 96, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		g.setColor(colour);
		g.fillRect(0, 0, 76, 96);
		g.dispose();

		final File file = new File(gameData, path);
		assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
		ImageIO.write(image, "png", file);
	}

	private void thumbnail (final int slot, final String contents) throws IOException {
		FileUtils.writeStringToFile(new File(saveDirectory, slot + ".png"), contents, "UTF-8");
	}

	private static Color centre (final File png) throws IOException {
		final BufferedImage image = ImageIO.read(png);
		return new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 2), true);
	}

	private ComplexProperty packet (final String objectName) {
		return (ComplexProperty) EKUtils.findProperty(packets, objectName).get();
	}

	private static DictionaryProperty variables (final ComplexProperty packet, final String component) {
		final SingleDimensionalArrayProperty components = packet.findProperty("ComponentPackets")
			.map(p -> (SingleDimensionalArrayProperty) p).get();

		for (final Object item : components.items) {
			final ComplexProperty candidate = (ComplexProperty) item;
			final Object type = candidate.findProperty("TypeString").get().obj;
			if (component.equals(type)) {
				return candidate.<DictionaryProperty>findProperty("Variables").get();
			}
		}

		throw new AssertionError(component + " not found");
	}

	private void set (final String objectName, final String component, final String key, final Object value) {
		Property.update(variables(packet(objectName), component).findEntry(key).get(), value);
	}

	@Test
	public void thePartyIsReadInSlotOrderWithEachSmallPortrait () {
		final List<Member> members = PartyPortraits.members(packets);

		assertEquals(2, members.size());
		assertEquals("Player_Elwyn", members.get(0).objectName);
		assertEquals(0, members.get(0).slot);
		assertEquals(ELWYN, members.get(0).smallPortrait);

		// Her save leaves the path empty, and the game derives it from who she is.
		assertEquals("Companion_Calisca(Clone)_1", members.get(1).objectName);
		assertEquals(1, members.get(1).slot);
		assertEquals(CALISCA, members.get(1).smallPortrait);
	}

	@Test
	public void theSlotDecidesTheOrderNotWhereTheObjectSitsInTheSave () {
		set("Player_Elwyn", "PartyMemberAI", "AssignedSlot", 3);

		final List<Member> members = PartyPortraits.members(packets);
		assertEquals("Companion_Calisca(Clone)_1", members.get(0).objectName);
		assertEquals("Player_Elwyn", members.get(1).objectName);
	}

	/** A pet has slot 6 and up, and a companion on the bench is not active. */
	@Test
	public void petsAndInactiveMembersAreNotDrawn () {
		set("Companion_Calisca(Clone)_1", "PartyMemberAI", "AssignedSlot", 7);
		assertEquals(1, PartyPortraits.members(packets).size());

		set("Companion_Calisca(Clone)_1", "PartyMemberAI", "AssignedSlot", 1);
		set("Companion_Calisca(Clone)_1", "PartyMemberAI", "IsActiveInParty", false);
		assertEquals(1, PartyPortraits.members(packets).size());
	}

	@Test
	public void eachMemberGetsAThumbnailAndStaleOnesGo () throws IOException {
		portrait(ELWYN, Color.RED);
		portrait(CALISCA, Color.BLUE);
		for (int slot = 0; slot < 5; slot++) {
			thumbnail(slot, "the old face " + slot);
		}

		assertTrue(PartyPortraits.refresh(saveDirectory, packets, gameData));

		final BufferedImage first = ImageIO.read(new File(saveDirectory, "0.png"));
		assertEquals(PartyPortraits.WIDTH, first.getWidth());
		assertEquals(PartyPortraits.HEIGHT, first.getHeight());
		assertEquals(Color.RED, centre(new File(saveDirectory, "0.png")));
		assertEquals(Color.BLUE, centre(new File(saveDirectory, "1.png")));
		for (int slot = 2; slot < 5; slot++) {
			assertFalse("slot " + slot + " is nobody now", new File(saveDirectory, slot + ".png").exists());
		}
	}

	/** Save writes a portrait the user just picked into the tree; that is the one drawn. */
	@Test
	public void aPortraitChangedInThisSaveIsTheOneDrawn () throws IOException {
		portrait(ELWYN, Color.RED);
		portrait(CALISCA, Color.BLUE);
		set("Player_Elwyn", "Portrait", "m_textureSmallPath", CALISCA);

		assertTrue(PartyPortraits.refresh(saveDirectory, packets, gameData));
		assertEquals(Color.BLUE, centre(new File(saveDirectory, "0.png")));
	}

	/** Half a party of new faces beside old ones would be worse than the old ones. */
	@Test
	public void aPortraitThatCannotBeFoundLeavesTheThumbnailsAlone () throws IOException {
		portrait(ELWYN, Color.RED);
		thumbnail(0, "the old face 0");
		thumbnail(1, "the old face 1");

		assertFalse(PartyPortraits.refresh(saveDirectory, packets, gameData));
		assertEquals("the old face 0"
			, FileUtils.readFileToString(new File(saveDirectory, "0.png"), "UTF-8"));
		assertEquals("the old face 1"
			, FileUtils.readFileToString(new File(saveDirectory, "1.png"), "UTF-8"));
	}

	@Test
	public void withoutTheGameNothingChanges () throws IOException {
		thumbnail(0, "the old face 0");
		assertFalse(PartyPortraits.refresh(saveDirectory, packets, null));
		assertEquals("the old face 0"
			, FileUtils.readFileToString(new File(saveDirectory, "0.png"), "UTF-8"));
	}
}
