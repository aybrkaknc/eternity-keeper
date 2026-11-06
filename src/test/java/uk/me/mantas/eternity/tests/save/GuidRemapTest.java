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
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.GuidRemap;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Which IDs a save's area files already hold an object under.
 *
 * <p>The world state is not the only file with objects in it. What a companion
 * held in their quick slots when they died is left in the area they died in,
 * as objects of that area, under the IDs it always had; what the party sold is
 * in the store's area. Bringing the same objects back from another save -- a
 * resurrection, an import -- under those IDs puts each in the save twice, and
 * the game says so on loading that area: "Trying to add ... to MobileObjects
 * when packet already exists! Packet is in both Mobile and Persistence object
 * lists!" (read out of Player.log, for three potions Edér came back holding).
 */
public class GuidRemapTest extends TestHarness {
	/** A save folder with one area file, holding the first {@code count} objects of the fixture. */
	static List<String> saveWithArea (final File save, final String area, final int count) throws Exception {
		final File fixture = new File(GuidRemapTest.class.getResource("/MobileObjects.save").toURI());
		final DeserializedPackets read = new PacketDeserializer(fixture).deserialize().get();
		final List<Property> objects = new ArrayList<>(read.getPackets().subList(0, count));
		Property.update(read.getCount(), count);

		final File file = new File(save, area);
		assertTrue(file.createNewFile());
		new DeserializedPackets(objects, read.getCount()).reserialize(file);

		final List<String> ids = new ArrayList<>();
		for (final Property object : objects) {
			ids.add(((ObjectPersistencePacket) object.obj).ObjectID);
		}

		return ids;
	}

	@Test
	public void theIdsAnAreaHoldsAreFound () throws Exception {
		final File save = EKUtils.createTempDir(PREFIX).get();
		final List<String> inArea = saveWithArea(save, "AR_0001_Somewhere.lvl", 3);
		final String elsewhere = "0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f";

		final Set<String> held = GuidRemap.heldByAreas(save
			, Arrays.asList(inArea.get(0), inArea.get(2).toUpperCase(), elsewhere, null));

		assertEquals(new HashSet<>(Arrays.asList(inArea.get(0).toLowerCase(), inArea.get(2).toLowerCase())), held);
	}

	@Test
	public void onlyAreaFilesAreLookedIn () throws Exception {
		final File save = EKUtils.createTempDir(PREFIX).get();
		final List<String> ids = saveWithArea(save, "AR_0001_Somewhere.lvl", 2);

		// The world state is the caller's own to compare with, and anything
		// else in the folder is not a packet file at all.
		assertTrue(new File(save, "AR_0001_Somewhere.lvl").renameTo(new File(save, "MobileObjects.save")));
		FileUtils.writeStringToFile(new File(save, "saveinfo.xml"), ids.get(0), "UTF-8");

		assertEquals(Collections.emptySet(), GuidRemap.heldByAreas(save, ids));
	}

	@Test
	public void aSaveWithNoAreasHoldsNothing () throws Exception {
		final File save = EKUtils.createTempDir(PREFIX).get();
		assertEquals(Collections.emptySet()
			, GuidRemap.heldByAreas(save, Collections.singletonList("0f0f0f0f-0f0f-4f0f-8f0f-0f0f0f0f0f0f")));
		assertEquals(Collections.emptySet(), GuidRemap.heldByAreas(new File(save, "404"), Collections.emptyList()));
	}
}
