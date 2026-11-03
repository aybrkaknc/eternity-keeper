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
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.ShortReadException;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CharacterExporter {
	private static final Logger logger = Logger.getLogger(CharacterExporter.class);

	private final File saveDirectory;
	private final File chrFile;
	private final String guid;

	private final PacketDeserializerFactory packetDeserializer;

	public CharacterExporter (
		final String savePath
		, final String guid
		, final String chrFileName)
		throws IOException {

		final File saveDirectory = new File(savePath);
		if (!saveDirectory.exists()) {
			throw new FileNotFoundException(savePath);
		}

		final File chrFile = new File(chrFileName);
		//noinspection ResultOfMethodCallIgnored
		chrFile.delete();

		if (!chrFile.createNewFile()) {
			throw new FileNotFoundException("Cannot create " + chrFileName);
		}

		this.saveDirectory = saveDirectory;
		this.chrFile = chrFile;
		this.guid = guid;

		final Environment environment = Environment.getInstance();
		packetDeserializer = environment.factory().packetDeserializer();
	}

	/**
	 * Writes the character and everything it owns into the file this was made
	 * for. An export that is refused or fails leaves no file behind: the
	 * constructor made an empty one to write into, and an empty or partial
	 * {@code .chr} beside the user's others would look like a character.
	 *
	 * @throws ShortReadException when the save could be read only in part --
	 *         whatever the character owns after the damage would be missing
	 */
	public boolean export () throws IOException {
		boolean exported = false;
		try {
			exported = write();
			return exported;
		} finally {
			if (!exported && chrFile.exists() && !chrFile.delete()) {
				logger.error("Unable to remove the unfinished %s.%n", chrFile.getAbsolutePath());
			}
		}
	}

	private boolean write () throws IOException {
		final File mobileObjectsFile = new File(saveDirectory, "MobileObjects.save");
		if (!mobileObjectsFile.exists()) {
			throw new FileNotFoundException(mobileObjectsFile.getAbsolutePath());
		}

		final PacketDeserializer deserializer = packetDeserializer.forFile(mobileObjectsFile);
		final Optional<DeserializedPackets> deserialized = deserializer.deserialize();
		if (!deserialized.isPresent()) {
			return false;
		}

		final SimpleProperty count = deserialized.get().getCount();
		final List<Property> extractedObjects = extractCharactersObjects(SaveModel.of(deserialized.get()));
		if (extractedObjects.size() < 1) {
			return false;
		}

		// The save's own leading count, which the write sets to what the file
		// holds; the save is never written from this reading.
		new DeserializedPackets(extractedObjects, count).reserialize(chrFile);

		return true;
	}

	private List<Property> extractCharactersObjects (final SaveModel save) {
		final List<Property> objs = new ArrayList<>();

		// Real saves contain objects with null ObjectIDs, Parents and
		// ObjectNames so we match null-safely with the GUID/name on the left.
		final String objectName = save.objects().stream()
			.filter(object -> guid.equals(object.id()))
			.findFirst()
			.map(SaveObject::name)
			.orElse(null);

		for (final SaveObject object : save.objects()) {
			if (guid.equals(object.id())
				|| (objectName != null && objectName.equals(object.parent()))) {

				objs.add(object.property());
			}
		}

		return objs;
	}
}
