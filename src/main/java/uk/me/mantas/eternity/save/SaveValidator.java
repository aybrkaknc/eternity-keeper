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


package uk.me.mantas.eternity.save;

import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.CSharpCollection;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.properties.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The invariants this project learned the hard way, checked against a save.
 *
 * <p>Every one of these has cost real debugging time. The item-minting bug
 * inserted a template's own {@code Property} objects into a new packet, which
 * aliased the two: writing the new GUID rewrote the <em>template's</em> GUID as
 * well, and the game then dropped both items on load with no error at all. It
 * surfaced only in-game, and it is a two-line check here.
 *
 * <p>Read-only and cheap — a 4,894-packet save is one pass. What it looks for:
 *
 * <ul>
 *   <li>Two objects sharing an ObjectID.</li>
 *   <li>An object whose {@code InstanceID.Guid} is not its own ObjectID.</li>
 *   <li>{@code ItemList} and {@code SerializedItemList} of different lengths —
 *       they are parallel, same index (invariant 6).</li>
 *   <li>A carried or worn item GUID with no standalone packet of its own.</li>
 *   <li>A leading object count that disagrees with the contents (invariant 5),
 *       which makes every later read stop early.</li>
 *   <li>A file that gave back fewer objects than its count promised — cut
 *       short, or damaged part of the way through. The editor shows what it
 *       could read and writes none of it back
 *       ({@link uk.me.mantas.eternity.serializer.ShortReadException}).</li>
 * </ul>
 *
 * <p>The first five were measured against four real saves before being written —
 * a 4,894-packet mid-game save, an early-prologue one, and both unit fixtures —
 * and report nothing on any of them; the sixth cannot fire on a file that reads
 * whole. Another candidate did not survive that measurement: <em>"Parent names
 * an object that exists"</em> fires 29 times on a perfectly healthy save,
 * because a dead companion's belongings outlive the companion the game
 * deleted. It is deliberately not checked.
 */
public class SaveValidator {
	/** Something about the save that should not be true. */
	public static final class Problem {
		public enum Kind {
			DUPLICATE_OBJECT_ID
			, INSTANCE_ID_MISMATCH
			, ITEM_LIST_LENGTH
			, UNRESOLVED_ITEM
			, UNRESOLVED_EQUIPMENT
			, OBJECT_COUNT
			, SHORT_READ
		}

		public final Kind kind;
		/** The object it is about, or empty for a whole-file problem. */
		public final String objectName;
		public final String objectID;
		/** Readable enough to put in front of a user. */
		public final String detail;

		Problem (
			final Kind kind
			, final String objectName
			, final String objectID
			, final String detail) {

			this.kind = kind;
			this.objectName = objectName == null ? "" : objectName;
			this.objectID = objectID == null ? "" : objectID;
			this.detail = detail;
		}

		@Override
		public String toString () {
			return objectName.isEmpty()
				? String.format("%s: %s", kind, detail)
				: String.format("%s (%s): %s", kind, objectName, detail);
		}
	}

	private SaveValidator () {}

	/** A file that gave back fewer objects than its count promised, or nothing. */
	public static Optional<Problem> shortRead (final DeserializedPackets packets) {
		return packets == null
			? Optional.empty()
			: packets.shortRead().map(read -> new Problem(Problem.Kind.SHORT_READ, "", ""
				, String.format(
					"only %d of the %d objects in %s could be read, so the editor will "
						+ "not write this save: the rest would be lost"
					, read.read, read.declared, read.file)));
	}

	public static List<Problem> validate (final DeserializedPackets packets) {
		if (packets == null) {
			return Collections.emptyList();
		}

		// A short read is said as what it is. Its count may be quite right,
		// and comparing it with the objects read would say the same thing
		// again as a count that is wrong.
		final List<Problem> problems = new ArrayList<>();
		shortRead(packets).ifPresent(problems::add);

		final Object count = packets.getCount() == null ? null : packets.getCount().obj;
		problems.addAll(validate(packets.getPackets()
			, problems.isEmpty() && count instanceof Number ? ((Number) count).intValue() : -1));

		return problems;
	}

	/**
	 * @param declaredCount the leading object count, or a negative number to
	 *                      skip that check.
	 */
	public static List<Problem> validate (
		final List<Property> packets, final int declaredCount) {

		final List<Problem> problems = new ArrayList<>();
		if (packets == null || packets.isEmpty()) {
			return problems;
		}

		final List<ObjectPersistencePacket> objects = new ArrayList<>();
		final Map<String, Integer> counts = new LinkedHashMap<>();

		for (final Property property : packets) {
			if (property == null || !(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet =
				(ObjectPersistencePacket) property.obj;

			objects.add(packet);
			if (packet.ObjectID != null && !packet.ObjectID.isEmpty()) {
				counts.merge(key(packet.ObjectID), 1, Integer::sum);
			}
		}

		if (declaredCount >= 0 && declaredCount != objects.size()) {
			problems.add(new Problem(Problem.Kind.OBJECT_COUNT, "", ""
				, String.format(
					"the file says it holds %d objects but carries %d; "
					+ "every read past the count is lost"
					, declaredCount, objects.size())));
		}

		for (final Map.Entry<String, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > 1) {
				problems.add(new Problem(Problem.Kind.DUPLICATE_OBJECT_ID
					, "", entry.getKey()
					, String.format(
						"%d objects share this ID; the game drops all of them"
						, entry.getValue())));
			}
		}

		for (final ObjectPersistencePacket packet : objects) {
			checkInstanceID(packet, problems);
			checkContainers(packet, counts, problems);
		}

		return problems;
	}

	/**
	 * An object's own identity has to agree with itself. Objects with no
	 * InstanceID at all are normal — ten of the 4,894 packets in a real save
	 * have none, the globals among them.
	 */
	private static void checkInstanceID (
		final ObjectPersistencePacket packet, final List<Problem> problems) {

		if (packet.ComponentPackets == null) {
			return;
		}

		for (final ComponentPersistencePacket component : packet.ComponentPackets) {
			if (component == null || component.Variables == null
				|| !"InstanceID".equalsIgnoreCase(component.TypeString)) {

				continue;
			}

			final Object guid = component.Variables.get("Guid");
			if (guid == null) {
				return;
			}

			if (!key(String.valueOf(guid)).equals(key(String.valueOf(packet.ObjectID)))) {
				problems.add(new Problem(Problem.Kind.INSTANCE_ID_MISMATCH
					, packet.ObjectName, packet.ObjectID
					, String.format(
						"its InstanceID is %s; the game loads objects by that, "
						+ "so this one goes missing", guid)));
			}

			return;
		}
	}

	/** Inventories and equipment, both of which point at packets by GUID. */
	private static void checkContainers (
		final ObjectPersistencePacket packet
		, final Map<String, Integer> known
		, final List<Problem> problems) {

		if (packet.ComponentPackets == null) {
			return;
		}

		for (final ComponentPersistencePacket component : packet.ComponentPackets) {
			if (component == null || component.Variables == null) {
				continue;
			}

			final Object items = component.Variables.get("ItemList");
			final Object serialized = component.Variables.get("SerializedItemList");

			if (items instanceof CSharpCollection && serialized instanceof CSharpCollection) {
				final int entries = size((CSharpCollection) items);
				final int guids = size((CSharpCollection) serialized);

				if (entries != guids) {
					problems.add(new Problem(Problem.Kind.ITEM_LIST_LENGTH
						, packet.ObjectName, packet.ObjectID
						, String.format(
							"%s holds %d items but %d item IDs; the two lists are "
							+ "parallel and have to match"
							, component.TypeString, entries, guids)));
				}
			}

			if (serialized instanceof CSharpCollection) {
				checkReferences((CSharpCollection) serialized, packet, component
					, known, problems, Problem.Kind.UNRESOLVED_ITEM, "carries");
			}

			final Object equipment = component.Variables.get("EquipmentSetSerialized");
			if (equipment instanceof CSharpCollection) {
				checkReferences((CSharpCollection) equipment, packet, component
					, known, problems, Problem.Kind.UNRESOLVED_EQUIPMENT, "is wearing");
			}
		}
	}

	private static void checkReferences (
		final CSharpCollection guids
		, final ObjectPersistencePacket packet
		, final ComponentPersistencePacket component
		, final Map<String, Integer> known
		, final List<Problem> problems
		, final Problem.Kind kind
		, final String verb) {

		for (final Iterator iterator = guids.iterator(); iterator.hasNext();) {
			final Object guid = iterator.next();
			if (guid == null || isEmpty(guid)) {
				continue;
			}

			if (known.containsKey(key(String.valueOf(guid)))) {
				continue;
			}

			problems.add(new Problem(kind, packet.ObjectName, packet.ObjectID
				, String.format(
					"%s %s item %s, which has no object of its own in the save"
					, component.TypeString, verb, guid)));
		}
	}

	/**
	 * An unused slot. The eleven equipment slots are mostly the all-zero UUID,
	 * and the deprecated Cape slot always is.
	 */
	private static boolean isEmpty (final Object guid) {
		if (guid instanceof UUID) {
			return ((UUID) guid).getMostSignificantBits() == 0
				&& ((UUID) guid).getLeastSignificantBits() == 0;
		}

		final String text = String.valueOf(guid);
		return text.isEmpty() || "00000000-0000-0000-0000-000000000000".equals(text);
	}

	private static int size (final CSharpCollection collection) {
		int size = 0;
		for (final Iterator iterator = collection.iterator(); iterator.hasNext();) {
			iterator.next();
			size++;
		}

		return size;
	}

	private static String key (final String guid) {
		return guid == null ? "" : guid.toLowerCase();
	}
}
