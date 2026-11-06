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


package uk.me.mantas.eternity.tests;

import org.junit.BeforeClass;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.handlers.GetGameStructures;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;

import java.awt.Rectangle;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static uk.me.mantas.eternity.EKUtils.*;

public class EKUtilsTest {
	@BeforeClass
	public static void setDefaultLocale () {
		Locale.setDefault(Locale.UK);
	}

	@Test
	public void extractCharacterNameTest () {
		assertEquals("Elwyn", extractCharacterName("Player_Elwyn(Clone)_0"));
		assertEquals("Calisca", extractCharacterName("Companion_Calisca(Clone)_1"));
		assertEquals("New_Game", extractCharacterName("Player_New_Game(Clone)_0"));
		assertEquals("", extractCharacterName("NoPrefix"));
		assertEquals("", extractCharacterName(null));
	}

	@Test
	public void removeBOMTest () {
		byte[] data = new byte[]{-17, -69, -65, 100, 97, 116, 97};
		byte[] actual = removeBOM(data);
		byte[] expected = new byte[]{100, 97, 116, 97};
		assertArrayEquals(expected, actual);
	}

	@Test
	public void addBOMTest () {
		byte[] actual = addBOM(new byte[]{});
		byte[] expected = new byte[]{-17, -69, -65};
		assertArrayEquals(expected, actual);
	}

	@Test
	public void removeExtensionTest () {
		assertNull(removeExtension(null));
		assertEquals("", removeExtension(""));
		assertEquals("noextension", removeExtension("noextension"));
		assertEquals("noextension", removeExtension("noextension.extension"));
	}

	@Test
	public void getExtensionTest () {
		assertFalse(getExtension(null).isPresent());
		assertFalse(getExtension("").isPresent());
		assertFalse(getExtension("noextension").isPresent());
		assertEquals("ext", getExtension("file.ext").get());
		assertEquals("ext", getExtension("file.part.ext").get());
	}

	@Test
	public void findPropertyTest () {
		final Property needle = mock(Property.class);
		final Property needle2 = mock(Property.class);
		final ObjectPersistencePacket packet = mock(ObjectPersistencePacket.class);
		final List<Property> haystack = new ArrayList<Property>() {{
			add(needle);
			add(needle2);
		}};

		packet.ObjectName = "FindMe";
		needle.obj = packet;
		needle2.obj = packet;

		assertFalse(findProperty(haystack, "404").isPresent());
		assertSame(needle, findProperty(haystack, "FINDME").get());
	}

	@Test
	public void findComponentTest () {
		final ComponentPersistencePacket needle = mock(ComponentPersistencePacket.class);
		final ComponentPersistencePacket needle2 = mock(ComponentPersistencePacket.class);
		final ComponentPersistencePacket[] haystack = new ComponentPersistencePacket[] {
			null
			, needle
			, needle2
		};

		needle.TypeString = "FindMe";
		needle2.TypeString = "FindMe";

		assertFalse(findComponent(haystack, "404").isPresent());
		assertSame(needle, findComponent(haystack, "FINDME").get());
	}

	// Two side-by-side 2560x1440 monitors, both top-aligned at y=0 — the same
	// layout as the machine that first hit the off-screen-window bug.
	private static final Rectangle[] TWO_MONITORS = new Rectangle[] {
		new Rectangle(0, 0, 2560, 1440)
		, new Rectangle(2560, 0, 2560, 1440)
	};

	@Test
	public void isReachableAcceptsOnScreenBounds () {
		// A normal window on the primary monitor.
		assertTrue(EKUtils.isReachable(new Rectangle(120, 80, 1600, 1048), TWO_MONITORS));
		// A window on the secondary monitor.
		assertTrue(EKUtils.isReachable(new Rectangle(2700, 100, 1600, 1000), TWO_MONITORS));
		// A maximised window with the usual few pixels of overscan.
		assertTrue(EKUtils.isReachable(new Rectangle(-8, -8, 2576, 1456), TWO_MONITORS));
	}

	@Test
	public void isReachableRejectsOffScreenBounds () {
		// The actual bug: title bar ~1088px above both monitors.
		assertFalse(EKUtils.isReachable(new Rectangle(-8, -1088, 1936, 1048), TWO_MONITORS));
		// Entirely to the right of every monitor.
		assertFalse(EKUtils.isReachable(new Rectangle(6000, 100, 800, 600), TWO_MONITORS));
		// Title bar below the bottom edge, so it can't be grabbed.
		assertFalse(EKUtils.isReachable(new Rectangle(100, 1430, 800, 600), TWO_MONITORS));
		// No monitors at all (e.g. headless) is never reachable.
		assertFalse(EKUtils.isReachable(new Rectangle(120, 80, 800, 600), new Rectangle[0]));
	}

	@Test
	public void reachableBoundsFallsBackWhenOffScreen () {
		final Rectangle fallback = new Rectangle(427, 240, 1706, 960);
		final Rectangle offScreen = new Rectangle(-8, -1088, 1936, 1048);
		final Rectangle onScreen = new Rectangle(120, 80, 1600, 1048);

		assertSame(onScreen, EKUtils.reachableBounds(onScreen, fallback, TWO_MONITORS));
		assertSame(fallback, EKUtils.reachableBounds(offScreen, fallback, TWO_MONITORS));
	}

	private enum Enum {A, B}
	private static class NotAnEnum {}

	@Test
	public void enumConstantNameTest () {
		final Optional<String> testA = EKUtils.enumConstantName(Enum.A);
		final Optional<String> testB = EKUtils.enumConstantName(Enum.B);
		final Optional<String> testFail = enumConstantName(new NotAnEnum());

		assertTrue(testA.isPresent());
		assertEquals("A", testA.get());
		assertTrue(testB.isPresent());
		assertEquals("B", testB.get());
		assertFalse(testFail.isPresent());
	}
}
