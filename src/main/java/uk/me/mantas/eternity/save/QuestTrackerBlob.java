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

import uk.me.mantas.eternity.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bounded reader/patcher for the .NET BinaryFormatter (NRBF) stream the game
 * nests inside the save as {@code QuestManager.QuestTrackers} — a
 * {@code Dictionary<String, QuestManager+QuestTracker>} in a fixed-size,
 * zero-padded buffer. This is a third serialization format, unrelated to the
 * SharpSerializer format the rest of the editor speaks.
 *
 * The reader indexes the byte offset of every fixed-width primitive it
 * visits, so patches write those bytes in place and every other byte of the
 * stream — layout, ids, padding — is preserved exactly. Empirically (verified
 * against paired pre/post-death saves for all eleven companions), un-failing
 * a quest this way reproduces the pre-death blob byte for byte.
 *
 * Only the record and primitive types observed in real QuestTrackers streams
 * are supported; anything unexpected aborts the parse and the caller leaves
 * the blob untouched.
 */
public class QuestTrackerBlob {
	private static final Logger logger = Logger.getLogger(QuestTrackerBlob.class);

	private static final String TRACKER_CLASS = "QuestManager+QuestTracker";
	private static final String END_STATE = "<EndState>k__BackingField";
	private static final String FAILED = "<Failed>k__BackingField";
	private static final String TRIGGERED_EVENTS = "<TriggeredEvents>k__BackingField";

	// NRBF record type codes.
	private static final int HEADER = 0x00;
	private static final int CLASS_WITH_ID = 0x01;
	private static final int SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES = 0x04;
	private static final int CLASS_WITH_MEMBERS_AND_TYPES = 0x05;
	private static final int BINARY_OBJECT_STRING = 0x06;
	private static final int BINARY_ARRAY = 0x07;
	private static final int MEMBER_PRIMITIVE_TYPED = 0x08;
	private static final int MEMBER_REFERENCE = 0x09;
	private static final int OBJECT_NULL = 0x0A;
	private static final int MESSAGE_END = 0x0B;
	private static final int BINARY_LIBRARY = 0x0C;
	private static final int OBJECT_NULL_MULTIPLE_256 = 0x0D;
	private static final int OBJECT_NULL_MULTIPLE = 0x0E;
	private static final int ARRAY_SINGLE_PRIMITIVE = 0x0F;
	private static final int ARRAY_SINGLE_OBJECT = 0x10;

	// NRBF primitive type codes.
	private static final int PRIM_BOOLEAN = 1;
	private static final int PRIM_BYTE = 2;
	private static final int PRIM_DOUBLE = 6;
	private static final int PRIM_INT16 = 7;
	private static final int PRIM_INT32 = 8;
	private static final int PRIM_INT64 = 9;
	private static final int PRIM_SINGLE = 11;
	private static final int PRIM_UINT32 = 15;

	// NRBF binary type codes (member type descriptors).
	private static final int BT_PRIMITIVE = 0;
	private static final int BT_STRING = 1;
	private static final int BT_OBJECT = 2;
	private static final int BT_SYSTEM_CLASS = 3;
	private static final int BT_CLASS = 4;
	private static final int BT_OBJECT_ARRAY = 5;
	private static final int BT_STRING_ARRAY = 6;
	private static final int BT_PRIMITIVE_ARRAY = 7;

	private final byte[] data;
	private int pos = 0;

	private final Map<Integer, ClassDef> classes = new HashMap<>();
	private final Map<Integer, Object> objects = new HashMap<>();
	private ObjNode rootDictionary = null;

	private static class ClassDef {
		String name;
		String[] members;
		int[] binaryTypes;
		Object[] extraInfo;
	}

	private static class ObjNode {
		String className;
		final Map<String, Object> members = new LinkedHashMap<>();
		final Map<String, Integer> primOffsets = new HashMap<>();
	}

	private static class Ref {
		final int id;
		Ref (final int id) { this.id = id; }
	}

	private static class PrimArrayNode {
		int dataOffset;
		int length;
		int elemType;
	}

	private static class NullRun {
		final int count;
		NullRun (final int count) { this.count = count; }
	}

	private static class EndOfStream {}

	private QuestTrackerBlob (final byte[] data) {
		this.data = data;
	}

	public static Optional<QuestTrackerBlob> parse (final byte[] data) {
		final QuestTrackerBlob blob = new QuestTrackerBlob(data);
		try {
			blob.parseStream();
		} catch (final Exception e) {
			logger.error("Unparseable QuestTrackers blob: %s%n", e.getMessage());
			return Optional.empty();
		}

		if (blob.rootDictionary == null) {
			logger.error("QuestTrackers blob contains no tracker dictionary.%n");
			return Optional.empty();
		}

		return Optional.of(blob);
	}

	/** Live view over one quest's tracker; setters patch the blob in place. */
	public final class Tracker {
		private final int endStateOffset;
		private final int failedOffset;
		private final int eventsDataOffset;   // -1 when the quest has no events
		private final int eventsLength;

		private Tracker (
			final int endStateOffset
			, final int failedOffset
			, final int eventsDataOffset
			, final int eventsLength) {

			this.endStateOffset = endStateOffset;
			this.failedOffset = failedOffset;
			this.eventsDataOffset = eventsDataOffset;
			this.eventsLength = eventsLength;
		}

		public int endState () {
			return readInt32At(endStateOffset);
		}

		public boolean failed () {
			return data[failedOffset] != 0;
		}

		public void setEndState (final int value) {
			writeInt32At(endStateOffset, value);
		}

		public void setFailed (final boolean value) {
			data[failedOffset] = (byte) (value ? 1 : 0);
		}

		public boolean triggeredEvent (final int event) {
			if (eventsDataOffset < 0 || event < 0 || event >= eventsLength) {
				return false;
			}

			final int word = readInt32At(eventsDataOffset + 4 * (event / 32));
			return ((word >> (event % 32)) & 1) != 0;
		}

		public void clearTriggeredEvent (final int event) {
			if (eventsDataOffset < 0 || event < 0 || event >= eventsLength) {
				return;
			}

			final int wordOffset = eventsDataOffset + 4 * (event / 32);
			writeInt32At(wordOffset, readInt32At(wordOffset) & ~(1 << (event % 32)));
		}

		public void setTriggeredEvent (final int event) {
			if (eventsDataOffset < 0 || event < 0 || event >= eventsLength) {
				return;
			}

			final int wordOffset = eventsDataOffset + 4 * (event / 32);
			writeInt32At(wordOffset, readInt32At(wordOffset) | (1 << (event % 32)));
		}

		/** How many of the quest's events have fired: how far it has got. */
		public int eventsFired () {
			int fired = 0;
			for (int event = 0; event < eventsLength; event++) {
				if (triggeredEvent(event)) {
					fired++;
				}
			}

			return fired;
		}
	}

	/** Every quest tracked, by the file the game keys it by, in the blob's order. */
	public List<String> quests () {
		final List<String> quests = new ArrayList<>();
		final Object pairs = resolve(rootDictionary.members.get("KeyValuePairs"));
		if (!(pairs instanceof List)) {
			return quests;
		}

		for (final Object item : (List<?>) pairs) {
			final Object entry = resolve(item);
			if (entry instanceof ObjNode) {
				final Object key = resolve(((ObjNode) entry).members.get("key"));
				if (key instanceof String) {
					quests.add((String) key);
				}
			}
		}

		return quests;
	}

	public Optional<Tracker> tracker (final String questPath) {
		final Object pairs = resolve(rootDictionary.members.get("KeyValuePairs"));
		if (!(pairs instanceof List)) {
			return Optional.empty();
		}

		for (final Object item : (List<?>) pairs) {
			final Object entry = resolve(item);
			if (!(entry instanceof ObjNode)) {
				continue;
			}

			final Object key = resolve(((ObjNode) entry).members.get("key"));
			if (!questPath.equals(key)) {
				continue;
			}

			final Object value = resolve(((ObjNode) entry).members.get("value"));
			if (!(value instanceof ObjNode)
				|| !TRACKER_CLASS.equals(((ObjNode) value).className)) {

				return Optional.empty();
			}

			return trackerView((ObjNode) value);
		}

		return Optional.empty();
	}

	private Optional<Tracker> trackerView (final ObjNode node) {
		final Integer endStateOffset = node.primOffsets.get(END_STATE);
		final Integer failedOffset = node.primOffsets.get(FAILED);
		if (endStateOffset == null || failedOffset == null) {
			return Optional.empty();
		}

		int eventsDataOffset = -1;
		int eventsLength = 0;
		final Object events = resolve(node.members.get(TRIGGERED_EVENTS));
		if (events instanceof ObjNode) {
			final ObjNode bitArray = (ObjNode) events;
			final Object array = resolve(bitArray.members.get("m_array"));
			final Object length = bitArray.members.get("m_length");
			if (array instanceof PrimArrayNode && length instanceof Integer) {
				eventsDataOffset = ((PrimArrayNode) array).dataOffset;
				eventsLength = (Integer) length;
			}
		}

		return Optional.of(new Tracker(endStateOffset, failedOffset, eventsDataOffset, eventsLength));
	}

	private Object resolve (Object value) {
		while (value instanceof Ref) {
			value = objects.get(((Ref) value).id);
		}

		return value;
	}

	private void parseStream () {
		while (pos < data.length) {
			if (readRecord() instanceof EndOfStream) {
				return;
			}
		}

		throw new IllegalStateException("no MessageEnd record");
	}

	private Object readRecord () {
		final int recordType = u8();
		switch (recordType) {
			case HEADER:
				i32(); i32(); i32(); i32();
				return null;

			case BINARY_LIBRARY:
				i32();
				string7Bit();
				return readRecord();

			case CLASS_WITH_ID: {
				final int objectId = i32();
				final ClassDef classDef = classes.get(i32());
				if (classDef == null) {
					throw new IllegalStateException("reference to undefined class");
				}

				return readInstance(objectId, classDef);
			}

			case SYSTEM_CLASS_WITH_MEMBERS_AND_TYPES:
			case CLASS_WITH_MEMBERS_AND_TYPES: {
				final int objectId = i32();
				final ClassDef classDef = new ClassDef();
				classDef.name = string7Bit();
				final int memberCount = i32();
				classDef.members = new String[memberCount];
				for (int i = 0; i < memberCount; i++) {
					classDef.members[i] = string7Bit();
				}

				classDef.binaryTypes = new int[memberCount];
				for (int i = 0; i < memberCount; i++) {
					classDef.binaryTypes[i] = u8();
				}

				classDef.extraInfo = new Object[memberCount];
				for (int i = 0; i < memberCount; i++) {
					classDef.extraInfo[i] = readTypeExtraInfo(classDef.binaryTypes[i]);
				}

				if (recordType == CLASS_WITH_MEMBERS_AND_TYPES) {
					i32(); // library id
				}

				classes.put(objectId, classDef);
				return readInstance(objectId, classDef);
			}

			case BINARY_OBJECT_STRING: {
				final int objectId = i32();
				final String value = string7Bit();
				objects.put(objectId, value);
				return value;
			}

			case MEMBER_PRIMITIVE_TYPED:
				return readPrimitive(u8());

			case MEMBER_REFERENCE:
				return new Ref(i32());

			case OBJECT_NULL:
				return null;

			case OBJECT_NULL_MULTIPLE_256:
				return new NullRun(u8());

			case OBJECT_NULL_MULTIPLE:
				return new NullRun(i32());

			case MESSAGE_END:
				return new EndOfStream();

			case ARRAY_SINGLE_PRIMITIVE: {
				final int objectId = i32();
				final int length = i32();
				final PrimArrayNode array = new PrimArrayNode();
				array.elemType = u8();
				array.length = length;
				array.dataOffset = pos;
				for (int i = 0; i < length; i++) {
					readPrimitive(array.elemType);
				}

				objects.put(objectId, array);
				return array;
			}

			case ARRAY_SINGLE_OBJECT: {
				final int objectId = i32();
				final int length = i32();
				final List<Object> items = readArrayItems(length);
				objects.put(objectId, items);
				return items;
			}

			case BINARY_ARRAY: {
				final int objectId = i32();
				final int arrayType = u8();
				final int rank = i32();
				long total = 1;
				for (int i = 0; i < rank; i++) {
					total *= i32();
				}

				if (arrayType >= 3 && arrayType <= 5) {
					for (int i = 0; i < rank; i++) {
						i32(); // lower bounds
					}
				}

				final int binaryType = u8();
				final Object extraInfo = readTypeExtraInfo(binaryType);

				if (binaryType == BT_PRIMITIVE) {
					final PrimArrayNode array = new PrimArrayNode();
					array.elemType = (Integer) extraInfo;
					array.length = (int) total;
					array.dataOffset = pos;
					for (int i = 0; i < total; i++) {
						readPrimitive(array.elemType);
					}

					objects.put(objectId, array);
					return array;
				}

				final List<Object> items = readArrayItems((int) total);
				objects.put(objectId, items);
				return items;
			}

			default:
				throw new IllegalStateException(
					String.format("unsupported record type 0x%02x at %d", recordType, pos - 1));
		}
	}

	private List<Object> readArrayItems (final int length) {
		final List<Object> items = new ArrayList<>(length);
		while (items.size() < length) {
			final Object value = readRecord();
			if (value instanceof NullRun) {
				for (int i = 0; i < ((NullRun) value).count; i++) {
					items.add(null);
				}
			} else if (value instanceof EndOfStream) {
				throw new IllegalStateException("stream ended inside an array");
			} else {
				items.add(value);
			}
		}

		return items;
	}

	private Object readInstance (final int objectId, final ClassDef classDef) {
		final ObjNode node = new ObjNode();
		node.className = classDef.name;
		objects.put(objectId, node);

		for (int i = 0; i < classDef.members.length; i++) {
			final String member = classDef.members[i];
			if (classDef.binaryTypes[i] == BT_PRIMITIVE) {
				node.primOffsets.put(member, pos);
				node.members.put(member, readPrimitive((Integer) classDef.extraInfo[i]));
			} else {
				final Object value = readRecord();
				if (value instanceof EndOfStream || value instanceof NullRun) {
					throw new IllegalStateException("unexpected record in member position");
				}

				node.members.put(member, value);
			}
		}

		if (node.className.startsWith("System.Collections.Generic.Dictionary")
			&& node.className.contains(TRACKER_CLASS)) {

			rootDictionary = node;
		}

		return node;
	}

	private Object readTypeExtraInfo (final int binaryType) {
		switch (binaryType) {
			case BT_PRIMITIVE:
			case BT_PRIMITIVE_ARRAY:
				return u8();

			case BT_SYSTEM_CLASS:
				return string7Bit();

			case BT_CLASS: {
				final String name = string7Bit();
				i32(); // library id
				return name;
			}

			case BT_STRING:
			case BT_OBJECT:
			case BT_OBJECT_ARRAY:
			case BT_STRING_ARRAY:
				return null;

			default:
				throw new IllegalStateException("unsupported binary type " + binaryType);
		}
	}

	private Object readPrimitive (final int primType) {
		switch (primType) {
			case PRIM_BOOLEAN: return u8() != 0;
			case PRIM_BYTE: return u8();
			case PRIM_INT16: return (short) (u8() | (u8() << 8));
			case PRIM_INT32:
			case PRIM_UINT32: return i32();
			case PRIM_INT64: return i32() | ((long) i32() << 32);
			case PRIM_SINGLE: return Float.intBitsToFloat(i32());
			case PRIM_DOUBLE: return Double.longBitsToDouble(i32() | ((long) i32() << 32));
			default:
				throw new IllegalStateException("unsupported primitive type " + primType);
		}
	}

	private int u8 () {
		if (pos >= data.length) {
			throw new IllegalStateException("unexpected end of blob");
		}

		return data[pos++] & 0xff;
	}

	private int i32 () {
		return u8() | (u8() << 8) | (u8() << 16) | (u8() << 24);
	}

	private String string7Bit () {
		int length = 0;
		int shift = 0;
		int b;
		do {
			b = u8();
			length |= (b & 0x7f) << shift;
			shift += 7;
		} while ((b & 0x80) != 0);

		if (length < 0 || pos + length > data.length) {
			throw new IllegalStateException("invalid string length");
		}

		final String value = new String(data, pos, length, StandardCharsets.UTF_8);
		pos += length;
		return value;
	}

	private int readInt32At (final int offset) {
		return (data[offset] & 0xff)
			| ((data[offset + 1] & 0xff) << 8)
			| ((data[offset + 2] & 0xff) << 16)
			| ((data[offset + 3] & 0xff) << 24);
	}

	private void writeInt32At (final int offset, final int value) {
		data[offset] = (byte) (value & 0xff);
		data[offset + 1] = (byte) ((value >> 8) & 0xff);
		data[offset + 2] = (byte) ((value >> 16) & 0xff);
		data[offset + 3] = (byte) ((value >> 24) & 0xff);
	}
}
