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

package uk.me.mantas.eternity.save.model;

import com.google.common.primitives.UnsignedInteger;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.EternityDateTime;
import uk.me.mantas.eternity.game.EternityTimeInterval;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.util.Optional;

/**
 * A value made the C# type a save holds where it goes (invariant 2: the wrong
 * type corrupts the save). The page sends text; a writer passes values. What
 * cannot be that type is refused, never written as something near it.
 */
public final class TypedValue {
	private TypedValue () {}

	/**
	 * {@code text} as the type of {@code current}: a number, a boolean, an
	 * unsigned int, the seconds of a date or time interval, the name of an enum
	 * constant, or text.
	 *
	 * @throws WriteRefusedException when the text is not one of that type
	 */
	public static Object from (final Object current, final String text) throws WriteRefusedException {
		if (current == null) {
			throw new WriteRefusedException("There is nothing to tell the type of '" + text + "' by.");
		}

		try {
			if (current instanceof Integer
				|| current instanceof EternityDateTime
				|| current instanceof EternityTimeInterval) {

				return Integer.parseInt(text.trim());
			}

			if (current instanceof Double) {
				return Double.parseDouble(text.trim());
			}

			if (current instanceof Float) {
				return Float.parseFloat(text.trim());
			}

			if (current instanceof UnsignedInteger) {
				return UnsignedInteger.valueOf(text.trim());
			}
		} catch (final IllegalArgumentException notANumber) {
			throw refused(text, current);
		}

		if (current instanceof Boolean) {
			if ("true".equalsIgnoreCase(text.trim()) || "false".equalsIgnoreCase(text.trim())) {
				return Boolean.parseBoolean(text.trim());
			}

			throw refused(text, current);
		}

		if (current.getClass().isEnum()) {
			for (final Object constant : current.getClass().getEnumConstants()) {
				final Optional<String> name = EKUtils.enumConstantName(constant);
				if (name.isPresent() && name.get().equals(text)) {
					return constant;
				}
			}

			throw refused(text, current);
		}

		if (current instanceof String) {
			return text;
		}

		throw new WriteRefusedException(
			"'" + text + "' cannot be written where the save holds a " + typeOf(current) + ".");
	}

	/**
	 * Puts {@code value} into {@code property} as the type it holds: text is
	 * read as that type, anything else must already be it.
	 */
	static void write (final SimpleProperty property, final Object value, final String what)
		throws WriteRefusedException {

		final Object current = property.value;
		final Object typed;
		if (value instanceof String && !(current instanceof String)) {
			try {
				typed = from(current, (String) value);
			} catch (final WriteRefusedException refused) {
				throw new WriteRefusedException(what + ": " + refused.getMessage() + " Nothing was changed.");
			}
		} else if (current != null && value != null && current.getClass() == value.getClass()) {
			typed = value;
		} else {
			throw new WriteRefusedException(what + " holds a " + typeOf(current)
				+ ", not a " + typeOf(value) + ". Nothing was changed.");
		}

		Property.update(property, typed);
	}

	private static WriteRefusedException refused (final String text, final Object current) {
		return new WriteRefusedException("'" + text + "' is not a " + typeOf(current) + ".");
	}

	private static String typeOf (final Object value) {
		if (value == null) {
			return "nothing";
		}

		return value.getClass().getSimpleName();
	}
}
