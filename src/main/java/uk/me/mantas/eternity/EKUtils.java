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

package uk.me.mantas.eternity;

import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SerializerFormat;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.function.Function;

public class EKUtils {
	private static final Logger logger = Logger.getLogger(EKUtils.class);

	// A window needs at least this much of itself on a monitor, and its title
	// bar within this much of the monitor's top edge, to be seen and dragged.
	private static final int MIN_VISIBLE = 100;
	private static final int TITLE_BAR_GRACE = 50;

	public static Rectangle getDefaultWindowBounds () {
		final double multiplier = 2d / 3d;
		final Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();

		final double defaultW = screenSize.width * multiplier;
		final double defaultH = screenSize.height * multiplier;
		final Rectangle fallback = new Rectangle(
			(int) (screenSize.width / 2 - defaultW / 2)
			, (int) (screenSize.height / 2 - defaultH / 2)
			, (int) defaultW
			, (int) defaultH);

		double w = fallback.width;
		double h = fallback.height;
		double x = fallback.x;
		double y = fallback.y;

		final JSONObject settings = Settings.getInstance().json;
		try { w = settings.getDouble("width"); } catch (final JSONException ignore) {}
		try { h = settings.getDouble("height"); } catch (final JSONException ignore) {}
		try { x = settings.getDouble("x"); } catch (final JSONException ignore) {}
		try { y = settings.getDouble("y"); } catch (final JSONException ignore) {}

		final Rectangle saved = new Rectangle((int) x, (int) y, (int) w, (int) h);
		return reachableBounds(saved, fallback, screenBounds());
	}

	// Keeps the saved bounds if a usable portion sits on some monitor;
	// otherwise recentres. Screens are injected so this is unit-testable
	// without a display. A window saved on a machine with a monitor stacked
	// above the primary (negative y) would otherwise open off-screen — visible
	// on the taskbar but impossible to see or click.
	public static Rectangle reachableBounds (
		final Rectangle saved, final Rectangle fallback, final Rectangle[] screens) {

		return isReachable(saved, screens) ? saved : fallback;
	}

	public static boolean isReachable (final Rectangle bounds, final Rectangle[] screens) {
		for (final Rectangle screen : screens) {
			final int overlapX =
				Math.min(bounds.x + bounds.width, screen.x + screen.width)
				- Math.max(bounds.x, screen.x);

			final boolean titleBarReachable =
				bounds.y >= screen.y - TITLE_BAR_GRACE
				&& bounds.y <= screen.y + screen.height - MIN_VISIBLE;

			if (overlapX >= MIN_VISIBLE && titleBarReachable) {
				return true;
			}
		}

		return false;
	}

	private static Rectangle[] screenBounds () {
		try {
			final GraphicsDevice[] devices =
				GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices();
			final Rectangle[] bounds = new Rectangle[devices.length];
			for (int i = 0; i < devices.length; i++) {
				bounds[i] = devices[i].getDefaultConfiguration().getBounds();
			}
			return bounds;
		} catch (final HeadlessException e) {
			return new Rectangle[0];
		}
	}

	public static Optional<File> createTempDir (String prefix) {
		try {
			return Optional.of(Files.createTempDirectory(prefix).toFile());
		} catch (IOException e) {
			logger.error("Error creating temp directory: %s%n", e.getMessage());
		} catch (IllegalArgumentException e) {
			logger.error("Unable to create temp directory with unique name: %s%n", e.getMessage());
		}

		return Optional.empty();
	}

	public static byte[] removeBOM (byte[] bytes) {
		if (bytes.length < 3) {
			return bytes;
		}

		if (bytes[0] == -17 && bytes[1] == -69 && bytes[2] == -65) {
			byte[] newBytes = new byte[bytes.length - 3];
			System.arraycopy(bytes, 3, newBytes, 0, bytes.length - 3);
			return newBytes;
		}

		return bytes;
	}

	public static byte[] addBOM (byte[] bytes) {
		byte[] newBytes = new byte[bytes.length + 3];
		newBytes[0] = -17; newBytes[1] = -69; newBytes[2] = -65;
		System.arraycopy(bytes, 0, newBytes, 3, bytes.length);

		return newBytes;
	}

	public static String removeExtension (String s) {
		if (s == null || s.length() < 1 || !s.contains(".")) {
			return s;
		}

		return s.substring(0, s.lastIndexOf("."));
	}

	public static Optional<String> getExtension (String s) {
		if (s == null || s.length() < 1 || !s.contains(".")) {
			return Optional.empty();
		}

		return Optional.of(s.substring(s.lastIndexOf(".") + 1, s.length()));
	}

	public static <T> BinaryOperator<T> throwingMerger() {
		return (u, v) -> { throw new IllegalStateException(String.format("Duplicate key %s", u)); };
	}

	// The finders below read the deserializer's mirror of an object (its
	// ObjectPersistencePacket), which is what the file held when it was read:
	// they are for readers, the opener and the comparisons. Anything that
	// writes a save reads and writes the tree, through the save model
	// (invariant 26).

	public static Optional<Property> findProperty (
		final List<Property> haystack
		, final String needle) {

		return findProperty(haystack, objectName -> objectName.trim().equalsIgnoreCase(needle));
	}

	public static Optional<Property> findProperty (
		final List<Property> haystack
		, final Function<String, Boolean> predicate) {

		Optional<Property> found = Optional.empty();
		for (final Property property : haystack) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = unwrapPacket(property);
			if (packet.ObjectName == null) {
				continue;
			}

			if (predicate.apply(packet.ObjectName)) {
				found = Optional.of(property);
				break;
			}
		}

		return found;
	}

	public static ObjectPersistencePacket unwrapPacket (final Property property) {
		return (ObjectPersistencePacket) property.obj;
	}

	// Extracts a user-facing character name from a mobile object name,
	// e.g. "Companion_Calisca(Clone)_1" -> "Calisca".
	public static String extractCharacterName (final String objectName) {
		if (objectName == null || !objectName.contains("_")) {
			return "";
		}

		String name = objectName.substring(objectName.indexOf("_") + 1);
		if (name.contains("(")) {
			name = name.split("\\(")[0];
		}

		return name;
	}

	public static Optional<ComponentPersistencePacket> findComponent (
		final ComponentPersistencePacket[] haystack
		, final String needle) {

		return Arrays.stream(haystack)
			.filter(component -> component != null)
			.filter(component -> component.TypeString.equalsIgnoreCase(needle))
			.findFirst();
	}

	/**
	 * An independent copy of a component packet	/**
	 * An independent copy of a component packet: its TypeString and a fresh
	 * Variables dictionary of new SimpleProperty instances, every type carried
	 * across exactly.
	 *
	 * <p>Never insert a template's own properties into a new packet instead.
	 * Minting an item once did, so writing the new GUID into the copied
	 * InstanceID also rewrote the template's, and the game dropped both items
	 * on load without a word. The components this is used on — InstanceID,
	 * Persistence, an ability's own — only ever hold simple values, so it does
	 * not need to recurse further; anything with a nested shape is carried
	 * through untouched rather than dropped.
	 */
	public static ComplexProperty copyComponent (final ComplexProperty source) {
		final ComplexProperty copy = new ComplexProperty(source.name, source.type);

		for (final Property field : source.properties) {
			if (!(field instanceof DictionaryProperty)) {
				copy.properties.add(copySimple(field));
				continue;
			}

			final DictionaryProperty variables = (DictionaryProperty) field;
			final DictionaryProperty copiedVariables =
				new DictionaryProperty(variables.name, variables.type);

			copiedVariables.keyType = variables.keyType;
			copiedVariables.valueType = variables.valueType;

			for (final Map.Entry<Property, Property> entry : variables.items) {
				copiedVariables.items.add(new AbstractMap.SimpleEntry<>(
					copySimple(entry.getKey()), copySimple(entry.getValue())));
			}

			copy.properties.add(copiedVariables);
		}

		return copy;
	}

	private static Property copySimple (final Property source) {
		if (!(source instanceof SimpleProperty)) {
			return source;
		}

		final SimpleProperty copy = new SimpleProperty(source.name, source.type);
		copy.value = ((SimpleProperty) source).value;
		copy.obj = source.obj;
		return copy;
	}

	public static Optional<String> enumConstantName (final Object constant) {
		final Class<?> cls = constant.getClass();

		try {
			final Method nameMethod = cls.getMethod("name");
			final Object result = nameMethod.invoke(constant);

			if (result instanceof String) {
				return Optional.of((String) result);
			}
		} catch (final NoSuchMethodException
			| IllegalAccessException
			| InvocationTargetException ignore) {}

		logger.error("Unable to extract enum constant name for %s.", cls.getName());
		return Optional.empty();
	}

	/**
	 * Reads a serialized file and writes it into {@code output}, which must
	 * already exist and is appended to. SaveConverter falls back on this for a
	 * file it cannot rewrite byte-for-byte.
	 */
	public static void reserializeFile (
		final File input, final File output, final SerializerFormat outputFormat)
		throws IOException {

		final DeserializedPackets deserialized = new PacketDeserializer(input).deserialize()
			.orElseThrow(() -> new IOException("Could not read " + input.getAbsolutePath()));

		deserialized.reserialize(output, outputFormat);
	}
}
