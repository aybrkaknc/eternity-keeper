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

// We host our own implementation of SharpSerializer which is used in
// Pillars of Eternity to serialize game objects into saves.

import com.google.common.io.LittleEndianDataInputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import org.apache.commons.io.input.CountingInputStream;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Map.Entry;

/**
 * Stateful Polenter SharpSerializer reader/writer over a packet stream file
 * (such as {@code MobileObjects.save} or an {@code .lvl} area file).
 *
 * <p>Performance optimizations:
 * <ul>
 *   <li>Loads the target file bytes into memory once on the first
 *       {@link #deserialize()} call rather than opening, seeking, buffering,
 *       and closing a {@link FileInputStream} for each of the ~7,000 packets
 *       in a save file.</li>
 *   <li>Caches reflective {@link Method} ({@code add}/{@code put}) and
 *       {@link Field} lookups across packets.</li>
 * </ul>
 */
public class SharpSerializer {
	private static final Logger logger = Logger.getLogger(SharpSerializer.class);

	public static final Map<String, Class> typeMap = TypeMap.map;
	private static final Map<Class<?>, Optional<Method>> ADD_METHOD_CACHE =
		new ConcurrentHashMap<>();
	private static final Map<Class<?>, Optional<Method>> PUT_METHOD_CACHE =
		new ConcurrentHashMap<>();

	private final Map<Integer, Property> propertyCache = new HashMap<>();

	public static class Elements {
		public static final byte Collection = 1;
		public static final byte ComplexObject = 2;
		public static final byte Dictionary = 3;
		public static final byte MultiArray = 4;
		public static final byte Null = 5;
		public static final byte SimpleObject = 6;
		public static final byte SingleArray = 7;
		public static final byte ComplexObjectWithID = 8;
		public static final byte Reference = 9;
		public static final byte CollectionWithID = 10;
		public static final byte DictionaryWithID = 11;
		public static final byte SingleArrayWithID = 12;
		public static final byte MultiArrayWithID = 13;

		public static boolean isElementWithID (final byte elementID) {
			return elementID == ComplexObjectWithID
				|| elementID == CollectionWithID
				|| elementID == DictionaryWithID
				|| elementID == SingleArrayWithID
				|| elementID == MultiArrayWithID;
		}
	}

	private final File targetFile;
	private long position = 0;
	private byte[] fileBytes = null;

	private SerializerFormat format = SerializerFormat.PRESERVE;

	public SharpSerializer (final String filePath) throws FileNotFoundException {
		targetFile = new File(filePath);
		if (!targetFile.exists()) {
			throw new FileNotFoundException();
		}
	}

	public SharpSerializer toFormat (final SerializerFormat format) {
		this.format = format;
		return this;
	}

	/**
	 * How far into the file the reads have got. A read that fails leaves it
	 * where it was, so the next one starts at the same byte.
	 */
	public long position () {
		return position;
	}

	private byte[] ensureFileBytes () throws IOException {
		if (fileBytes == null) {
			fileBytes = Files.readAllBytes(targetFile.toPath());
		}

		return fileBytes;
	}

	public Optional<Property> deserialize () {
		try {
			final byte[] bytes = ensureFileBytes();
			if (position < 0 || position > bytes.length) {
				return Optional.empty();
			}

			final int offset = (int) position;
			final int remaining = bytes.length - offset;

			try (CountingInputStream counting =
					new CountingInputStream(new ByteArrayInputStream(bytes, offset, remaining));
				LittleEndianDataInputStream stream =
					new LittleEndianDataInputStream(counting)) {

				final Deserializer deserializer =
					new Deserializer(stream, this, remaining);
				final Property property = deserializer.deserialize();
				position += counting.getByteCount();

				return Optional.ofNullable(createObject(property));
			}
		} catch (final IOException e) {
			logger.error(
				"Error opening target file '%s' for deserializing: %s%n"
				, targetFile
				, e.getMessage());
		}

		return Optional.empty();
	}

	public void serialize (final Property property) {
		fileBytes = null;
		try {
			final FileOutputStream baseStream = new FileOutputStream(targetFile, true);

			// Unbuffered, every written byte was a syscall — buffering makes
			// writing real saves ~30x faster with byte-identical output.
			try (LittleEndianDataOutputStream stream =
				new LittleEndianDataOutputStream(new BufferedOutputStream(baseStream))) {

				baseStream.getChannel()
					.position(baseStream.getChannel().size());

				final Serializer serializer = new Serializer(stream).toFormat(format);
				serializer.serialize(property);
			}
		} catch (final IOException e) {
			logger.error(
				"Error opening target file '%s' for serializing: %s%n"
				, targetFile
				, e.getMessage());
		}
	}

	// Writes a whole packet stream in one session. Each property still gets
	// its own Serializer so the output is byte-identical to repeated
	// serialize() calls, but the file is opened only once, which makes
	// writing large saves orders of magnitude faster.
	//
	// It appends (invariant 13): callers writing over an existing save use
	// DeserializedPackets.replace, which gives this an empty sibling to write.
	// A failure is thrown rather than logged -- it used to be swallowed here,
	// and every manager then reported success over a truncated file.
	public void serializeAll (final Property count, final List<Property> properties)
		throws IOException {

		fileBytes = null;
		final FileOutputStream baseStream = new FileOutputStream(targetFile, true);

		try (LittleEndianDataOutputStream stream =
			new LittleEndianDataOutputStream(new BufferedOutputStream(baseStream, 65536))) {

			baseStream.getChannel()
				.position(baseStream.getChannel().size());

			new Serializer(stream).toFormat(format).serialize(count);
			for (final Property property : properties) {
				new Serializer(stream).toFormat(format).serialize(property);
			}
		} catch (final IOException e) {
			logger.error(
				"Error serializing to '%s': %s%n", targetFile, e.getMessage());

			throw e;
		}
	}

	public Optional<Property> followReference (final ReferenceTargetProperty property) {
		if (property.reference == null) {
			return Optional.empty();
		}

		return Optional.ofNullable(propertyCache.get(property.reference.id));
	}

	private Property createObject (final Property property) {
		if (property == null) {
			logger.error("Property is null!%n");
			return null;
		}

		if (property instanceof NullProperty) {
			property.obj = null;
			return property;
		}

		if (property.type == null) {
			logger.error("Tried to create an object from a property with no type!%n");
			return null;
		}

		if (property instanceof SimpleProperty) {
			if (property.type == null) {
				throw new IllegalArgumentException();
			}

			return createObjectFromSimpleProperty((SimpleProperty) property);
		}

		if (!(property instanceof ReferenceTargetProperty)) {
			logger.error("Don't know what to do with this property!%n");
			return null;
		}

		final ReferenceTargetProperty referenceTarget = (ReferenceTargetProperty) property;
		if (referenceTarget.reference != null && !referenceTarget.reference.isProcessed) {
			return propertyCache.get(referenceTarget.reference.id);
		}

		final Property value = createObjectCore(property);
		if (value == null) {
			logger.error("Unimplemented property type!%n");
			return null;
		}

		return value;
	}

	private Property createObjectCore (final Object property) {
		if (property instanceof SingleDimensionalArrayProperty) {
			return createObjectFromSingleDimensionalArrayProperty(
				(SingleDimensionalArrayProperty) property);
		}

		if (property instanceof DictionaryProperty) {
			return createObjectFromDictionaryProperty((DictionaryProperty) property);
		}

		if (property instanceof CollectionProperty) {
			return createObjectFromCollectionProperty((CollectionProperty) property);
		}

		if (property instanceof ComplexProperty) {
			return createObjectFromComplexProperty((ComplexProperty) property);
		}

		return null;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private Property createObjectFromCollectionProperty (final CollectionProperty property) {
		final Class<?> type = property.type.type;
		final Object collection = createInstance(type);

		if (property.reference != null) {
			propertyCache.put(property.reference.id, property);
		}

		fillProperties(collection, property.properties);
		if (collection instanceof Collection) {
			final Collection col = (Collection) collection;
			for (final Property item : property.items) {
				final Property value = createObject(item);
				col.add(value.obj);
			}
		} else if (collection != null) {
			final Optional<Method> addMethod = ADD_METHOD_CACHE.computeIfAbsent(
				collection.getClass(), cls -> {
					try {
						return Optional.of(cls.getMethod("add", Object.class));
					} catch (final NoSuchMethodException e) {
						logger.error(
							"Supposed 'Collection' class '%s' had no add method: %s%n"
							, cls.getSimpleName(), e.getMessage());
						return Optional.empty();
					}
				});

			if (addMethod.isPresent()) {
				try {
					for (final Property item : property.items) {
						final Property value = createObject(item);
						addMethod.get().invoke(collection, value.obj);
					}
				} catch (final InvocationTargetException | IllegalAccessException e) {
					logger.error(
						"Unable to call add method on class '%s': %s%n"
						, collection.getClass().getSimpleName(), e.getMessage());
				}
			}
		}

		property.obj = collection;
		return property;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private Property createObjectFromDictionaryProperty (final DictionaryProperty property) {
		final Object dictionary = createInstance(property.type.type);

		if (property.reference != null) {
			propertyCache.put(property.reference.id, property);
		}

		fillProperties(dictionary, property.properties);
		if (dictionary instanceof Map) {
			final Map map = (Map) dictionary;
			for (final Entry<Property, Property> item : property.items) {
				final Property key = createObject(item.getKey());
				final Property value = createObject(item.getValue());
				map.put(key.obj, value.obj);
			}
		} else if (dictionary != null) {
			final Optional<Method> putMethod = PUT_METHOD_CACHE.computeIfAbsent(
				dictionary.getClass(), cls -> {
					try {
						return Optional.of(cls.getMethod("put", Object.class, Object.class));
					} catch (final NoSuchMethodException e) {
						logger.error(
							"Supposed 'Dictionary' class '%s' had no put method: %s%n"
							, cls.getSimpleName(), e.getMessage());
						return Optional.empty();
					}
				});

			if (putMethod.isPresent()) {
				try {
					for (final Entry<Property, Property> item : property.items) {
						final Property key = createObject(item.getKey());
						final Property value = createObject(item.getValue());
						putMethod.get().invoke(dictionary, key.obj, value.obj);
					}
				} catch (final InvocationTargetException | IllegalAccessException e) {
					logger.error(
						"Unable to call put method on class '%s': %s%n"
						, dictionary.getClass().getSimpleName(), e.getMessage());
				}
			}
		}

		property.obj = dictionary;
		return property;
	}

	@SuppressWarnings("unchecked")
	private Property createObjectFromSingleDimensionalArrayProperty (
		final SingleDimensionalArrayProperty property) {

		final int itemsCount = property.items.size();
		final Object[] array = new Object[itemsCount];

		if (property.reference != null) {
			propertyCache.put(property.reference.id, property);
		}

		for (int index = property.lowerBound; index < property.lowerBound + itemsCount; index++) {
			final Property item = (Property) property.items.get(index);
			final Property value = createObject(item);
			if (value != null) {
				array[index] = value.obj;
			}
		}

		property.obj = Arrays.copyOf(array, array.length, property.type.type);
		return property;
	}

	private Property createObjectFromComplexProperty (final ComplexProperty property) {
		final Object obj = createInstance(property.type.type);
		if (obj == null) {
			return null;
		}

		if (property.reference != null) {
			propertyCache.put(property.reference.id, property);
		}

		fillProperties(obj, property.properties);
		property.obj = obj;
		return property;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void fillProperties (final Object obj, final List properties) {
		if (obj == null) {
			return;
		}

		final Class<?> ownerClass = obj.getClass();
		for (final Property property : (List<Property>) properties) {
			final Optional<Field> field = Deserializer.findCachedField(ownerClass, property.name);
			if (!field.isPresent()) {
				logger.error(
					"Class '%s' has no field '%s'%n"
					, ownerClass.getSimpleName(), property.name);
				continue;
			}

			final Property value = createObject(property);
			if (value == null) {
				continue;
			}

			try {
				field.get().set(obj, value.obj);
			} catch (final IllegalAccessException | IllegalArgumentException e) {
				logger.error(
					"Unable to set field '%s' of class '%s': %s%n"
					, property.name, ownerClass.getSimpleName(), e.getMessage());
			}
		}
	}

	private Object createInstance (final Class<?> type) {
		if (type == null) {
			return null;
		}

		try {
			return type.newInstance();
		} catch (final InstantiationException | IllegalAccessException e) {
			logger.error(
				"Unable to instantiate object of type '%s': %s%n"
				, type.getSimpleName(), e.getMessage());
		}

		return null;
	}

	private Property createObjectFromSimpleProperty (final SimpleProperty property) {
		property.obj = property.value;
		return property;
	}
}
