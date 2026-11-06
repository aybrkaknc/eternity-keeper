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


package uk.me.mantas.eternity.serializer.write;

import com.google.common.primitives.UnsignedInteger;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.serializer.CSharpType;

import java.io.DataOutput;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Low-level binary writer for the Polenter SharpSerializer format used by
 * Pillars of Eternity. Encodes variable-width integers, 7-bit length-prefixed
 * UTF-8 strings, mixed-endian .NET {@code System.Guid} values, and C# enum
 * values with cached reflection metadata.
 */
public class BinaryWriter {
	private static final Logger logger = Logger.getLogger(BinaryWriter.class);
	private static final Map<Class<?>, Optional<Field>> ENUM_VALUE_FIELD_CACHE =
		new ConcurrentHashMap<>();

	public DataOutput out;

	public BinaryWriter (final DataOutput out) {
		this.out = out;
	}

	public void writeValue (final Object value) throws IOException {
		if (value == null) {
			out.writeBoolean(false);
		} else {
			out.writeBoolean(true);
			writeValueCore(value);
		}
	}

	private void writeValueCore (final Object value) throws IOException {
		if (value == null) {
			logger.error("Cannot write null value!%n");
			return;
		}

		if (value instanceof byte[] || value instanceof Byte[]) {
			writeNumber(Array.getLength(value));
			out.write(unboxBytes(value));
			return;
		}

		if (value instanceof String) {
			writeString((String) value);
			return;
		}

		if (value instanceof Integer) {
			out.writeInt((int) value);
			return;
		}

		if (value instanceof Boolean) {
			out.writeBoolean((boolean) value);
			return;
		}

		if (value instanceof Float) {
			out.writeFloat((float) value);
			return;
		}

		if (value instanceof UUID) {
			writeGuid((UUID) value);
			return;
		}

		if (value instanceof UnsignedInteger) {
			out.writeInt(((UnsignedInteger) value).intValue());
			return;
		}

		if (value instanceof Byte) {
			out.writeByte((byte) value);
			return;
		}

		if (value instanceof Character) {
			out.writeChar((char) value);
			return;
		}

		if (value instanceof Double) {
			out.writeDouble((double) value);
			return;
		}

		if (value instanceof Short) {
			out.writeShort((short) value);
			return;
		}

		if (value instanceof Long) {
			out.writeLong((long) value);
			return;
		}

		if (value.getClass().isEnum()) {
			writeEnumValue(value);
			return;
		}

		if (value instanceof CSharpType) {
			writeString(((CSharpType) value).type);
			return;
		}

		if (value instanceof Class) {
			logger.error("Trying to write Class type unimplemented!%n");
			return;
		}

		logger.error(
			"Don't know how to write object of type '%s'!%n"
			, value.getClass().getSimpleName());
	}

	/**
	 * Writes a {@link UUID} in .NET {@code System.Guid.ToByteArray()} layout
	 * (little-endian {@code Data1}, {@code Data2}, {@code Data3}; big-endian
	 * {@code Data4}) without intermediate array or buffer allocations.
	 */
	private void writeGuid (final UUID uuid) throws IOException {
		final long msb = uuid.getMostSignificantBits();
		final long lsb = uuid.getLeastSignificantBits();
		final byte[] guid = new byte[16];

		// Data1 (32-bit little-endian)
		guid[0] = (byte) (msb >>> 32);
		guid[1] = (byte) (msb >>> 40);
		guid[2] = (byte) (msb >>> 48);
		guid[3] = (byte) (msb >>> 56);

		// Data2 (16-bit little-endian)
		guid[4] = (byte) (msb >>> 16);
		guid[5] = (byte) (msb >>> 24);

		// Data3 (16-bit little-endian)
		guid[6] = (byte) msb;
		guid[7] = (byte) (msb >>> 8);

		// Data4 (64-bit big-endian)
		guid[8] = (byte) (lsb >>> 56);
		guid[9] = (byte) (lsb >>> 48);
		guid[10] = (byte) (lsb >>> 40);
		guid[11] = (byte) (lsb >>> 32);
		guid[12] = (byte) (lsb >>> 24);
		guid[13] = (byte) (lsb >>> 16);
		guid[14] = (byte) (lsb >>> 8);
		guid[15] = (byte) lsb;

		out.write(guid);
	}

	private void writeEnumValue (final Object value) throws IOException {
		final Class<?> cls = value.getClass();
		final Optional<Field> nField = ENUM_VALUE_FIELD_CACHE.computeIfAbsent(cls, c -> {
			try {
				return Optional.of(c.getField("n"));
			} catch (final NoSuchFieldException e) {
				return Optional.empty();
			}
		});

		if (nField.isPresent()) {
			try {
				out.writeInt(nField.get().getInt(value));
				return;
			} catch (final IllegalAccessException ignored) {
			}
		}

		final Object[] constants = cls.getEnumConstants();
		for (int i = 0; i < constants.length; i++) {
			if (value == constants[i]) {
				out.writeInt(i);
				return;
			}
		}

		logger.error("Supposed enum value couldn't be matched with any enum constants!%n");
	}

	public void writeStringGuarded (final String value) throws IOException {
		if (value == null || value.isEmpty()) {
			out.writeBoolean(false);
		} else {
			out.writeBoolean(true);
			writeString(value);
		}
	}

	private void writeString (final String value) throws IOException {
		final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		write7BitEncodedInt(bytes.length);
		out.write(bytes);
	}

	private void write7BitEncodedInt (int v) throws IOException {
		while (v >= 0x80) {
			out.writeByte((byte) (v | 0x80));
			v >>= 7;
		}

		out.writeByte((byte) v);
	}

	public void writeNumber (final int n) throws IOException {
		final byte size = getIntSizeInBytes(n);
		out.writeByte(size);

		if (size > 0) {
			switch (size) {
				case 1:
					out.writeByte((byte) n);
					break;

				case 2:
					out.writeShort((short) n);
					break;

				default:
					out.writeInt(n);
			}
		}
	}

	private byte getIntSizeInBytes (final int n) {
		if (n == 0) {
			return 0;
		}

		if (n > Short.MAX_VALUE || n < Short.MIN_VALUE) {
			return 4;
		}

		if (n > 255 || n < 0) {
			return 2;
		}

		return 1;
	}

	private byte[] unboxBytes (final Object value) {
		if (value instanceof byte[]) {
			return (byte[]) value;
		}

		final Byte[] in = (Byte[]) value;
		final byte[] out = new byte[in.length];
		for (int i = 0; i < in.length; i++) {
			out[i] = in[i];
		}

		return out;
	}

	public void writeNumbers (final int[] n) throws IOException {
		writeNumber(n.length);
		for (final int i : n) {
			writeNumber(i);
		}
	}
}
