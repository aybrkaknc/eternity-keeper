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

package uk.me.mantas.eternity.tests.serializer;

import org.junit.Test;
import uk.me.mantas.eternity.serializer.TypePair;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

// A property's type as both sides see it. Some come out of a save with no C#
// name at all, and comparing one of those used to throw.
public class TypePairTest {
	@Test
	public void aTypeWithNoCSharpNameCanStillBeCompared () {
		final TypePair unnamed = new TypePair(Object.class, null);

		assertEquals(unnamed, new TypePair(Object.class, null));
		assertNotEquals(unnamed, new TypePair(Object.class, "System.Object"));
		assertNotEquals(new TypePair(Object.class, "System.Object"), unnamed);
	}

	@Test
	public void equalTypesHashAlike () {
		final Set<TypePair> seen = new HashSet<>();
		seen.add(new TypePair(UUID.class, "System.Guid"));
		seen.add(new TypePair(Object.class, null));

		assertTrue(seen.contains(new TypePair(UUID.class, "System.Guid")));
		assertTrue(seen.contains(new TypePair(Object.class, null)));
		assertFalse(seen.contains(new TypePair(UUID.class, "System.String")));
	}
}
