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

import com.google.common.primitives.UnsignedInteger;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.DataInput;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

import static java.util.AbstractMap.Entry;
import static java.util.AbstractMap.SimpleImmutableEntry;
import static uk.me.mantas.eternity.serializer.SharpSerializer.Elements;
import static uk.me.mantas.eternity.serializer.SharpSerializer.typeMap;

/**
 * Binary reader for a single Polenter SharpSerializer root packet.
 *
 * <p>Each packet begins with two string tables ({@code names} and {@code types}),
 * followed by a recursive tree of {@link Property} nodes. To eliminate reflection
 * and regex overhead across the ~7,000 packets of a world-state file, type-string
 * normalization, field metadata lookups, and C# enum value tables are memoized
 * in static thread-safe caches.
 */
public class Deserializer {
	private static final Logger logger = Logger.getLogger(Deserializer.class);
	private static final Pattern GENERIC_ARITY_PATTERN = Pattern.compile("`\\d");

	private static final Map<String, Class<?>> RESOLVED_TYPE_CACHE = new ConcurrentHashMap<>();
	private static final Map<Class<?>, Map<String, Optional<Field>>> FIELD_CACHE =
		new ConcurrentHashMap<>();
	private static final Map<Class<?>, Map<Integer, Object>> ENUM_LOOKUP_CACHE =
		new ConcurrentHashMap<>();

	private final SharpSerializer parent;
	private final DataInput stream;
	private final List<String> names = new ArrayList<>();
	private final List<TypePair> types = new ArrayList<>();

	private final Map<Integer, ReferenceTargetProperty> propertyCache = new HashMap<>();

	// How many bytes the file has from where this read began: nothing in it
	// is longer than that.
	private final long available;

	public Deserializer (final DataInput stream, final SharpSerializer parent) {
		this(stream, parent, Integer.MAX_VALUE);
	}

	public Deserializer (
		final DataInput stream, final SharpSerializer parent, final long available) {

		this.stream = stream;
		this.parent = parent;
		this.available = available;

		try {
			readHeader(names, Function.identity());
			readHeader(types, this::convertToType);
		} catch (final IOException e) {
			logger.error("An error occurred whilst deserializing: %s%n", e.getMessage());
		}
	}

	private TypePair convertToType (final String s) {
		if (s == null) {
			return null;
		}

		final Class<?> cached = RESOLVED_TYPE_CACHE.get(s);
		if (cached != null) {
			return new TypePair(cached, s);
		}

		String key = GENERIC_ARITY_PATTERN.matcher(s).replaceAll("");
		final int bracketIndex = key.indexOf("[[");
		if (bracketIndex >= 0) {
			key = key.substring(0, bracketIndex);
		}

		final int commaIndex = key.indexOf(',');
		if (commaIndex >= 0) {
			key = key.substring(0, commaIndex);
		}

		final Class<?> value = typeMap.get(key);
		if (value == null) {
			logger.error("Unable to find class corresponding to key '%s'.%n", key);
			return new TypePair(Object.class, s);
		}

		RESOLVED_TYPE_CACHE.put(s, value);
		return new TypePair(value, s);
	}

	private <T> void readHeader (
		final List<T> items, final Function<String, T> readCallback) throws IOException {

		final int count = readNumber();
		for (int i = 0; i < count; i++) {
			final String itemAsText = readString();
			final T item = readCallback.apply(itemAsText);
			items.add(item);
		}
	}

	private String readString () throws IOException {
		if (!stream.readBoolean()) {
			return null;
		}

		return readCSharpString();
	}

	private String readCSharpString () throws IOException {
		final int length = read7BitEncodedInt();
		final byte[] buffer = new byte[sane(length)];
		stream.readFully(buffer);

		return new String(buffer, StandardCharsets.UTF_8);
	}

	// A length is a few bytes like any others, and in a damaged file they say
	// anything: less than nothing, or two gigabytes -- and asking for those
	// is not an exception but an OutOfMemoryError, which ended the thread the
	// page was waiting on. Nothing in a file is longer than the file.
	private int sane (final int length) throws IOException {
		if (length < 0 || length > available) {
			throw new IOException(String.format(
				"a length of %d where at most %d bytes are left", length, available));
		}

		return length;
	}

	private int read7BitEncodedInt () throws IOException, NumberFormatException {
		int count = 0;
		int shift = 0;
		byte b;

		do {
			if (shift == 5 * 7) {
				throw new NumberFormatException("Bad 7 bit encoded int.");
			}

			b = stream.readByte();
			count |= (b & 0x7f) << shift;
			shift += 7;
		} while ((b & 0x80) != 0);

		return count;
	}

	private int readNumber () throws IOException {
		final byte size = stream.readByte();

		switch (size) {
			case 0:
				return 0;

			case 1:
				return stream.readByte() & 0xff;

			case 2:
				return stream.readShort();

			default:
				return stream.readInt();
		}
	}

	public Property deserialize () throws IOException {
		final byte elementID = stream.readByte();
		return deserialize(elementID, null);
	}

	private Property deserialize (final byte elementID, final TypePair expectedType)
		throws IOException {

		final String propertyName = readName();
		return deserialize(elementID, propertyName, expectedType);
	}

	private Property deserialize (
		final byte elementID, final String propertyName, final TypePair expectedType)
		throws IOException {

		TypePair propertyType = readType();
		if (propertyType == null) {
			if (expectedType != null) {
				propertyType = new TypePair(expectedType.type, null);
			} else {
				propertyType = new TypePair(Object.class, null);
			}
		}

		int referenceID = 0;
		if (elementID == Elements.Reference || Elements.isElementWithID(elementID)) {
			referenceID = readNumber();
			if (elementID == Elements.Reference) {
				return createProperty(referenceID, propertyName, propertyType);
			}
		}

		final Property property = createProperty(elementID, propertyName, propertyType);
		if (property == null) {
			return null;
		}

		if (property instanceof NullProperty) {
			return property;
		}

		if (property instanceof SimpleProperty) {
			parseSimpleProperty((SimpleProperty) property);
			return property;
		}

		if (property instanceof ReferenceTargetProperty) {
			if (referenceID > 0) {
				final ReferenceTargetProperty refTarget = (ReferenceTargetProperty) property;
				refTarget.reference = new Reference(referenceID);
				refTarget.reference.isProcessed = true;
				propertyCache.put(referenceID, refTarget);
			}
		}

		if (property instanceof SingleDimensionalArrayProperty) {
			parseSingleDimensionalArrayProperty((SingleDimensionalArrayProperty) property);
			return property;
		}

		if (property instanceof DictionaryProperty) {
			parseDictionaryProperty((DictionaryProperty) property);
			return property;
		}

		if (property instanceof CollectionProperty) {
			parseCollectionProperty((CollectionProperty) property);
			return property;
		}

		if (property instanceof ComplexProperty) {
			parseComplexProperty((ComplexProperty) property);
			return property;
		}

		return property;
	}

	private void parseCollectionProperty (final CollectionProperty property) throws IOException {
		property.elementType = readType();
		readProperties(property.properties, property.type.type);
		readItems(property.items, property.elementType);
	}

	private void parseDictionaryProperty (final DictionaryProperty property) throws IOException {
		property.keyType = readType();
		property.valueType = readType();
		readProperties(property.properties, property.type.type);
		readDictionaryItems(property.items, property.keyType, property.valueType);
	}

	private void readDictionaryItems (
		final List<Entry<Property, Property>> items
		, final TypePair keyType
		, final TypePair valueType) throws IOException {

		final int count = readNumber();
		for (int i = 0; i < count; i++) {
			readDictionaryItem(items, keyType, valueType);
		}
	}

	private void readDictionaryItem (
		final List<Entry<Property, Property>> items
		, final TypePair keyType
		, final TypePair valueType) throws IOException {

		// Key
		byte elementID = stream.readByte();
		final Property keyProperty = deserialize(elementID, keyType);

		// Value
		elementID = stream.readByte();
		final Property valueProperty = deserialize(elementID, valueType);

		final Entry<Property, Property> item =
			new SimpleImmutableEntry<>(keyProperty, valueProperty);
		items.add(item);
	}

	private void parseSingleDimensionalArrayProperty (
		final SingleDimensionalArrayProperty property) throws IOException {

		property.elementType = readType();
		property.lowerBound = readNumber();
		readItems(property.items, property.elementType);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void readItems (final List items, final TypePair elementType) throws IOException {
		final int count = readNumber();
		for (int i = 0; i < count; i++) {
			final byte elementID = stream.readByte();
			final Object subProperty = deserialize(elementID, elementType);
			items.add(subProperty);
		}
	}

	private void parseComplexProperty (final ComplexProperty property) throws IOException {
		readProperties(property.properties, property.type.type);
	}

	/**
	 * Resolves a public field on {@code ownerType} using a per-class concurrent
	 * cache so missing or repeated fields never trigger repeated reflective
	 * scans or exception stack traces in the hot deserialization loop.
	 */
	static Optional<Field> findCachedField (final Class<?> ownerType, final String propertyName) {
		if (ownerType == null || propertyName == null) {
			return Optional.empty();
		}

		final Map<String, Optional<Field>> classFields =
			FIELD_CACHE.computeIfAbsent(ownerType, c -> new ConcurrentHashMap<>());

		return classFields.computeIfAbsent(propertyName, name -> {
			try {
				return Optional.of(ownerType.getField(name));
			} catch (final NoSuchFieldException e) {
				return Optional.empty();
			}
		});
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void readProperties (final List properties, final Class ownerType) throws IOException {
		final int count = readNumber();
		for (int i = 0; i < count; i++) {
			final byte elementID = stream.readByte();
			final String propertyName = readName();
			final Optional<Field> subPropertyInfo = findCachedField(ownerType, propertyName);

			if (!subPropertyInfo.isPresent()) {
				logger.error(
					"No such field '%s' for type '%s'! Skipping.%n"
					, propertyName
					, ownerType != null ? ownerType.getSimpleName() : "null");
			}

			final Class<?> propertyType =
				subPropertyInfo.map(Field::getType).orElse(null);

			final Object subProperty = deserialize(
				elementID, propertyName, new TypePair(propertyType, null));

			properties.add(subProperty);
		}
	}

	private void parseSimpleProperty (final SimpleProperty property) throws IOException {
		property.value = readValue(property.type.type);
	}

	private Object readValue (final Class<?> expectedType) throws IOException {
		if (!stream.readBoolean()) {
			return null;
		}

		return readValueCore(expectedType);
	}

	private Object readValueCore (final Class<?> expectedType) throws IOException {
		try {
			if (expectedType == int.class || expectedType == Integer.class) {
				return stream.readInt();
			}

			if (expectedType == String.class) {
				return readCSharpString();
			}

			if (expectedType == boolean.class || expectedType == Boolean.class) {
				return stream.readBoolean();
			}

			if (expectedType == float.class || expectedType == Float.class) {
				return stream.readFloat();
			}

			if (expectedType == UUID.class) {
				return readGuid();
			}

			if (expectedType == UnsignedInteger.class) {
				return UnsignedInteger.valueOf((long) stream.readInt());
			}

			if (expectedType == Byte[].class || expectedType == byte[].class) {
				final int length = readNumber();
				if (length < 1) {
					return null;
				}

				final byte[] buffer = new byte[sane(length)];
				stream.readFully(buffer);
				return boxBytes(buffer);
			}

			if (expectedType != null && expectedType.isEnum()) {
				return readEnumeration(expectedType);
			}
		} catch (final Exception e) {
			logger.error("Unable to read property value: %s%n", e.getMessage());
		}

		if (expectedType != null) {
			final String typeName = readCSharpString();
			final Class<?> javaType = typeMap.get(typeName);

			if (javaType == null) {
				logger.error(
					"No mapping found for type '%s', expected type was '%s'.%n"
					, typeName, expectedType.getSimpleName());
			}

			return javaType;
		} else {
			logger.error("Expected type was null in readValueCore.%n");
		}

		return null;
	}

	/**
	 * Reads a 16-byte .NET {@code System.Guid} in mixed-endian wire layout and
	 * converts it to a standard Java {@link UUID}.
	 */
	private UUID readGuid () throws IOException {
		final byte[] b = new byte[16];
		stream.readFully(b);

		final long d1 = ((b[0] & 0xffL))
			| ((b[1] & 0xffL) << 8)
			| ((b[2] & 0xffL) << 16)
			| ((b[3] & 0xffL) << 24);
		final long d2 = (b[4] & 0xffL) | ((b[5] & 0xffL) << 8);
		final long d3 = (b[6] & 0xffL) | ((b[7] & 0xffL) << 8);

		final long mostSig = (d1 << 32) | (d2 << 16) | d3;
		final long leastSig = ((b[8] & 0xffL) << 56)
			| ((b[9] & 0xffL) << 48)
			| ((b[10] & 0xffL) << 40)
			| ((b[11] & 0xffL) << 32)
			| ((b[12] & 0xffL) << 24)
			| ((b[13] & 0xffL) << 16)
			| ((b[14] & 0xffL) << 8)
			| (b[15] & 0xffL);

		return new UUID(mostSig, leastSig);
	}

	private Byte[] boxBytes (final byte[] in) {
		final Byte[] out = new Byte[in.length];
		for (int i = 0; i < in.length; i++) {
			out[i] = in[i];
		}

		return out;
	}

	private Object readEnumeration (final Class<?> type) throws IOException {
		final int value = stream.readInt();
		final Map<Integer, Object> lookup = ENUM_LOOKUP_CACHE.computeIfAbsent(type, cls -> {
			final Map<Integer, Object> map = new HashMap<>();
			final Object[] constants = cls.getEnumConstants();
			if (constants == null) {
				return map;
			}

			Field nField = null;
			try {
				nField = cls.getField("n");
			} catch (final NoSuchFieldException ignored) {
			}

			for (int i = 0; i < constants.length; i++) {
				final Object constant = constants[i];
				map.putIfAbsent(i, constant);
				if (nField != null) {
					try {
						map.put(nField.getInt(constant), constant);
					} catch (final IllegalAccessException ignored) {
					}
				}
			}

			return map;
		});

		final Object matched = lookup.get(value);
		if (matched != null) {
			return matched;
		}

		logger.error(
			"Tried to get enum value index #%d that "
				+ "didn't exist for enum %s, preserving the raw value.%n"
			, value, type.getSimpleName());

		// Newer game versions contain enum values this port doesn't know
		// about. Returning the raw value means it serializes back to the
		// same bytes instead of corrupting the save with a null.
		return value;
	}

	private Property createProperty (
		final byte elementID, final String propertyName, final TypePair propertyType) {

		switch (elementID) {
			case Elements.SimpleObject:
				return new SimpleProperty(propertyName, propertyType);

			case Elements.ComplexObject:
			case Elements.ComplexObjectWithID:
				return new ComplexProperty(propertyName, propertyType);

			case Elements.SingleArray:
			case Elements.SingleArrayWithID:
				return new SingleDimensionalArrayProperty(propertyName, propertyType);

			case Elements.Dictionary:
			case Elements.DictionaryWithID:
				return new DictionaryProperty(propertyName, propertyType);

			case Elements.Collection:
				return new CollectionProperty(propertyName, propertyType);

			case Elements.Null:
				return new NullProperty(propertyName);

			default:
				logger.error("Unimplemented element ID #%d.%n", elementID);
		}

		return null;
	}

	private Property createProperty (
		final int referenceID, final String propertyName, final TypePair propertyType) {

		final ReferenceTargetProperty cachedProperty = propertyCache.get(referenceID);
		if (cachedProperty == null) {
			logger.error("No cached property found for reference ID #%d!%n", referenceID);
			return null;
		}

		final ReferenceTargetProperty property = (ReferenceTargetProperty) Property.createInstance(
			cachedProperty.getPropertyArt(), propertyName, propertyType);

		cachedProperty.reference.count++;

		// noinspection ConstantConditions
		property.makeFlatCopyFrom(cachedProperty);
		property.reference = new Reference(referenceID);

		return property;
	}

	private TypePair readType () throws IOException {
		final int index = readNumber();
		return types.get(index);
	}

	private String readName () throws IOException {
		final int index = readNumber();
		return names.get(index);
	}
}
