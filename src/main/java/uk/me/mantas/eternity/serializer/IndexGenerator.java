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


package uk.me.mantas.eternity.serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maintains an insertion-ordered table of unique header items (property names
 * or C# type descriptors) for a serialized packet while providing O(1) index
 * lookup via an internal hash index.
 *
 * @param <T> the header element type
 */
public class IndexGenerator<T> {
	public final List<T> items = new ArrayList<>();
	private final Map<T, Integer> indexByItem = new HashMap<>();

	/**
	 * Returns the zero-based table index for {@code item}, appending it to
	 * {@link #items} on first encounter.
	 *
	 * @param item the header element (may be {@code null})
	 * @return the zero-based index in {@link #items}
	 */
	public int getIndexOfItem (final T item) {
		final Integer existing = indexByItem.get(item);
		if (existing != null) {
			return existing;
		}

		final int index = items.size();
		items.add(item);
		indexByItem.put(item, index);
		return index;
	}
}
