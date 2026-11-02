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

import com.google.common.primitives.UnsignedInteger;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.ZipParameters;
import org.apache.commons.io.FileUtils;
import org.cef.callback.CefQueryCallback;
import org.json.JSONException;
import org.json.JSONObject;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Logger;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.WorkingSave;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.game.EternityDateTime;
import uk.me.mantas.eternity.game.EternityTimeInterval;
import uk.me.mantas.eternity.handlers.SaveChanges;
import uk.me.mantas.eternity.handlers.SaveWriteRequestAdapter;
import uk.me.mantas.eternity.save.model.Component;
import uk.me.mantas.eternity.save.model.SaveModel;
import uk.me.mantas.eternity.save.model.SaveObject;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.WriteRefusedException;
import uk.me.mantas.eternity.serializer.properties.*;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Persists scalar and structural modifications from the working save directory
 * into a packaged {@code .savegame} archive.
 *
 * <p>Decoupled so callers can invoke {@link #saveChanges()} headlessly without a
 * JCEF callback, while {@link #run()} adapts the result for the browser bridge.
 */
public class ChangesSaver implements Runnable {
	private static final Logger logger = Logger.getLogger(ChangesSaver.class);
	private final CefQueryCallback callback;
	private final SaveWriteCommand command;
	private final PacketDeserializerFactory packetDeserializer;

	/**
	 * Creates a headless {@code ChangesSaver} for direct domain or test invocation.
	 *
	 * @param request the save request payload
	 */
	public ChangesSaver (final JSONObject request) {
		this.callback = null;
		this.command = new SaveWriteRequestAdapter().parse(Objects.requireNonNull(request, "request"));
		this.packetDeserializer = Environment.getInstance().factory().packetDeserializer();
	}

	/**
	 * Compatibility constructor for callers which still have the raw bridge request.
	 */
	public ChangesSaver (final String request, final CefQueryCallback callback) {
		this.callback = callback;
		SaveWriteCommand parsed = null;
		try {
			parsed = new SaveWriteRequestAdapter().parse(request);
		} catch (final JSONException ignored) {
		}
		this.command = parsed;
		this.packetDeserializer = Environment.getInstance().factory().packetDeserializer();
	}

	/** Creates a writer from an already validated typed command. */
	public ChangesSaver (final SaveWriteCommand command, final CefQueryCallback callback) {
		this.callback = callback;
		this.command = Objects.requireNonNull(command, "command");
		this.packetDeserializer = Environment.getInstance().factory().packetDeserializer();
	}

	// The main character's name as the save now holds it: the Player_
	// object's CharacterStats.OverrideName, read from the property tree, which
	// is what an edit changes (the packets' own objects keep the old value).
	private static Optional<String> mainCharacterName (final List<Property> packets) {
		return SaveModel.of(packets).player()
			.flatMap(player -> player.component("CharacterStats"))
			.flatMap(stats -> stats.get("OverrideName"))
			.filter(value -> value instanceof String && !((String) value).trim().isEmpty())
			.map(String.class::cast);
	}

	// The handful of settings saveinfo.xml keeps its own copy of, so the load
	// screen agrees with what the save actually contains. GameState is the
	// authority; this just mirrors the values the edit put there.
	private static final String[] SUMMARY_FIELDS = {
		"Difficulty", "TrialOfIron", "TacticalMode"
	};

	private static Map<String, String> summaryFields (final SaveWriteCommand command) {
		final Map<String, String> fields = new LinkedHashMap<>();
		for (final Map<String, Map<String, String>> components : command.getGlobals().values()) {
			final Map<String, String> gameState = components.get("GameState");
			if (gameState == null) {
				continue;
			}

			for (final String field : SUMMARY_FIELDS) {
				final String written = gameState.get(field);
				if (written != null && !written.isEmpty()) {
					fields.put(field, written);
				}
			}
		}

		return fields;
	}

	/**
	 * Executes the save workflow headlessly without requiring a JCEF callback.
	 *
	 * @return the directory that was packaged into the new {@code .savegame}
	 * @throws JSONException            if the request payload is missing required fields
	 * @throws IOException              if cloning, serializing, backing up, or zipping fails
	 * @throws DeserializationException if {@code MobileObjects.save} cannot be read
	 */
	public File saveChanges () throws IOException, DeserializationException {
		return execute().getWrittenDirectory();
	}

	/** Executes the save workflow and returns typed paths for the adapter. */
	public SaveWriteResult execute () throws IOException, DeserializationException {
		final Environment environment = Environment.getInstance();
		boolean savedYet = command.isSavedYet();

		final WorkingSave working = environment.state().workingSave();
		File saveDirectory = working.written();
		if (savedYet && saveDirectory == null) {
			logger.error(
				"Client claimed we had already saved but "
					+ "server had no record having saved previously.%n");

			savedYet = false;
		}

		// The first Save starts from whatever the managers' Applies left,
		// which is not necessarily the directory the list opened.
		if (!savedYet) {
			saveDirectory = cloneExtractedSave(
				working.forReading(new File(command.getAbsolutePath()), false).getAbsolutePath());
			working.written(saveDirectory);
		}

		final List<Property> written = updateMobileObjects(saveDirectory, command);

		// The load list files a save under its main character's name, read
		// from the summary: after an import of another playthrough's Watcher,
		// or a rename, the summary still had the old one.
		final Map<String, String> summary = summaryFields(command);
		mainCharacterName(written).ifPresent(name -> summary.put("PlayerName", name));
		SaveGameInfo.updateSaveInfo(saveDirectory, command.getSaveName(), summary);
		redrawPartyThumbnails(saveDirectory, written);
		packageSaveGame(saveDirectory);

		// Undo reaches back to here: what this wrote is in the files an
		// Apply's undo would put back, and would go with them.
		working.history().clear();
		final String savesLocation = Settings.getInstance().json.optString("savesLocation", "");
		return new SaveWriteResult(saveDirectory,
			new File(new File(savesLocation), saveDirectory.getName()));
	}

	@Override
	public void run () {
		try {
			if (command == null) {
				if (callback != null) {
					callback.failure(-1, SaveChanges.jsonError());
				}
				return;
			}
			execute();
			if (callback != null) {
				callback.success("{\"success\":true}");
			}
		} catch (final JSONException e) {
			if (callback != null) {
				callback.failure(-1, SaveChanges.jsonError());
			}
		} catch (final WriteRefusedException e) {
			// Said as it stands: "IO Error" and a class name would bury what the
			// user needs to know -- the save could not all be read, or the
			// changes would have left it contradicting itself, and so nothing
			// was written.
			logger.error("Save refused: %s%n", e.getMessage());
			if (callback != null) {
				callback.failure(-1, SaveChanges.genericError(e.getMessage()));
			}
		} catch (final IOException e) {
			logger.error(e, "IO/Zip Error during save: %s%n", e.toString());
			if (callback != null) {
				callback.failure(-1, SaveChanges.genericError("IO Error: " + e.toString()));
			}
		} catch (final DeserializationException e) {
			logger.error("Unable to deserialize new save.%n");
			if (callback != null) {
				callback.failure(-1, SaveChanges.deserializationError());
			}
		} catch (final Exception e) {
			logger.error(e, "Unexpected error during save: %s%n", e.toString());
			if (callback != null) {
				callback.failure(-1, SaveChanges.genericError("Unexpected Error: " + e.toString()));
			}
		}
	}

	static void packageSaveGame (final File saveDirectory) throws ZipException, IOException {
		final String savesLocation = Settings.getInstance().json.optString("savesLocation", "");
		if (savesLocation.isEmpty()) {
			logger.error("Saves location is not set in settings!%n");
			throw new IOException("Pillars of Eternity saves location is not set in settings.");
		}

		final File pillarsSavesDirectory = new File(savesLocation);
		logger.info("Target saves directory: %s (exists: %b, dir: %b)%n",
			pillarsSavesDirectory.getAbsolutePath(), pillarsSavesDirectory.exists(),
			pillarsSavesDirectory.isDirectory());

		if (!pillarsSavesDirectory.exists()) {
			throw new IOException(
				"Pillars of Eternity saves directory does not exist: "
					+ pillarsSavesDirectory.getAbsolutePath());
		}

		final File saveFile = new File(pillarsSavesDirectory, saveDirectory.getName());
		logger.info("Packaging save game to: %s%n", saveFile.getAbsolutePath());

		if (saveFile.exists()) {
			// It is the player's file -- an earlier edit, or a save that shares
			// the name -- so it is copied into the backups before it goes. One
			// that cannot be copied stops the Save rather than being lost.
			SaveBackups.forThisProcess().backup(saveFile, SaveBackups.Reason.OVERWRITE);
			logger.info("Old save file exists, attempting to delete: %s%n", saveFile.getAbsolutePath());
			if (!FileUtils.deleteQuietly(saveFile)) {
				logger.warn("Unable to delete old save game '%s', attempting overwrite.%n",
					saveFile.getAbsolutePath());
			}
		}

		final File[] saveContents = saveDirectory.listFiles();
		if (saveContents == null || saveContents.length == 0) {
			logger.error("Save directory is empty or inaccessible: %s%n", saveDirectory.getAbsolutePath());
			throw new IOException("No files found to package in " + saveDirectory.getAbsolutePath());
		}

		logger.info("Zipping %d files into %s%n", saveContents.length, saveFile.getAbsolutePath());

		final ZipFile saveArchive = new ZipFile(saveFile);
		saveArchive.addFiles(
			new ArrayList<>(Arrays.asList(saveContents)), new ZipParameters());
		logger.info("Successfully packaged save game: %s%n", saveFile.getAbsolutePath());
	}

	// The game's load list draws a save's party from 0.png, 1.png… inside it:
	// its own snapshot from when it last saved, so after a party or portrait
	// edit it showed the old faces. They are redrawn from what is being
	// written. A thumbnail is never worth failing a Save over.
	private static void redrawPartyThumbnails (
		final File saveDirectory, final List<Property> packets) {

		try {
			final String game = Settings.getInstance().json.optString("gameLocation", "");
			final File gameData = game == null || game.isEmpty()
				? null
				: new File(game, Environment.getInstance().config().pillarsDataDirectory());

			PartyPortraits.refresh(saveDirectory, packets, gameData);
		} catch (final IOException | RuntimeException e) {
			logger.error(e, "Could not redraw the party thumbnails: %s%n", e.getMessage());
		}
	}

	private List<Property> updateMobileObjects (
		final File saveDirectory, final SaveWriteCommand command)
		throws IOException, DeserializationException {

		return updateMobileObjects(saveDirectory, command, this.packetDeserializer);
	}

	/** @return the packets as written, for anything else Save derives from them */
	private static List<Property> updateMobileObjects (
		final File saveDirectory
		, final SaveWriteCommand command
		, final PacketDeserializerFactory packetDeserializer)
		throws IOException, DeserializationException {

		final File mobileObjectsFile = new File(saveDirectory, "MobileObjects.save");
		if (!mobileObjectsFile.exists()) {
			logger.warn("MobileObjects.save not found in %s, skipping mobile objects update.%n",
				saveDirectory.getAbsolutePath());
			return Collections.emptyList();
		}

		logger.info("Updating mobile objects in: %s%n", mobileObjectsFile.getAbsolutePath());
		final PacketDeserializer deserializer = packetDeserializer.forFile(mobileObjectsFile);
		final Optional<DeserializedPackets> deserialized = deserializer.deserialize();

		if (!deserialized.isPresent()) {
			throw new DeserializationException();
		}

		// Keyed by GUID, so each of the thousands of packets in the world state
		// finds its character's edits at once rather than by a search.
		final Map<String, SaveWriteCommand.CharacterUpdate> charactersByGuid = command.getCharacters();

		final List<Property> packets = deserialized.get().getPackets();
		final SaveModel save = SaveModel.of(packets);

		final Optional<SaveObject> player = save.player();
		if (player.isPresent()) {
			updateCurrency(player.get(), (float) command.getCurrency());

			// Absent when the save records none, and from any request made
			// before the party dialog could change it.
			if (command.getCampingSupplies() != null) {
				updateCampingSupplies(player.get(), command.getCampingSupplies());
			}
		}

		save.worldGlobal().ifPresent(global ->
			updateGlobal(deserializer, global, command.getGlobals().get("Global")));
		save.inGameGlobal().ifPresent(global ->
			updateGlobal(deserializer, global, command.getGlobals().get("InGameGlobal")));

		for (final SaveObject object : save.objects()) {
			final SaveWriteCommand.CharacterUpdate character =
				object.id() != null ? charactersByGuid.get(object.id()) : null;

			if (character != null) {
				updateComponent(deserializer, object, "CharacterStats", character.getStats());

				// A portrait is two plain strings on a different component, so
				// it rides the same scalar path rather than needing its own
				// write tier. Absent when the character has no Portrait at all.
				final Map<String, String> portraitPaths = character.getPortraitPaths();
				if (!portraitPaths.isEmpty()) {
					updateComponent(deserializer, object, "Portrait", portraitPaths);
				}

				// A heal is the game's own flag for a full refill on the first
				// frame after the load, on the Health component.
				final Map<String, String> health = character.getHealth();
				if (!health.isEmpty()) {
					updateComponent(deserializer, object, "Health", health);
				}
			}
		}

		deserialized.get().replace(mobileObjectsFile);
		return packets;
	}

	private static void updateCurrency (final SaveObject player, final float currency) {
		final Optional<Property> currencyValue = player.component("PlayerInventory")
			.flatMap(inventory -> inventory.variable("currencyTotalValue"));

		if (!currencyValue.isPresent()) {
			logger.error("Unable to navigate property structure when updating currency!%n");
			return;
		}

		Property.update(currencyValue.get(), "v", currency);
	}

	private static void updateCampingSupplies (final SaveObject player, final int count) {
		final Optional<Property> supplies = player.component("PlayerInventory")
			.flatMap(inventory -> inventory.variable("campingSupplies"));

		if (!supplies.isPresent()) {
			logger.error("Unable to find campingSupplies when updating the player's inventory!%n");
			return;
		}

		// An int in the save: anything else would be written as the wrong type.
		Property.update(supplies.get(), Integer.valueOf(Math.max(0, count)));
	}

	private static void updateGlobal (
		final PacketDeserializer deserializer, final SaveObject global,
		final Map<String, Map<String, String>> updates) {
		if (updates == null) {
			return;
		}

		for (final Map.Entry<String, Map<String, String>> entry : updates.entrySet()) {
			final String updateKey = entry.getKey();
			final Optional<Component> component = global.component(updateKey);

			if (!component.isPresent()) {
				logger.error(
					"Client tried to update global ComponentPacket '%s' "
						+ "which did not exist in the saved data.%n",
					updateKey);
				continue;
			}

			final Optional<DictionaryProperty> variables =
				component.get().property().findProperty("Variables");

			if (!variables.isPresent()) {
				logger.error(
					"Invalid ComponentPersistencePacket detected in ComponentPackets "
						+ "list when navigating globals.%n");
				continue;
			}

			if ("GlobalVariables".equals(component.get().type())) {
				updateHashtable(deserializer, variables.get(), entry.getValue());
			} else {
				updateVariables(deserializer, variables.get(), entry.getValue());
			}
		}
	}

	/** Writes a set of scalars onto one named component of a game object. */
	private static void updateComponent (
		final PacketDeserializer deserializer
		, final SaveObject object
		, final String component
		, final Map<String, String> updates) {

		final Optional<DictionaryProperty> variables = object.component(component)
			.flatMap(found -> found.property().findProperty("Variables"));

		if (!variables.isPresent()) {
			logger.error(
				"Unable to navigate property structure when updating %s!%n", component);

			return;
		}

		updateVariables(deserializer, variables.get(), updates);
	}

	private static void updateVariables (
		final PacketDeserializer deserializer
		, final DictionaryProperty variables
		, final Map<String, String> updates) {

		for (final Map.Entry<String, String> update : updates.entrySet()) {
			final String updateKey = update.getKey();
			final String newValue = update.getValue();
			final Optional<Property> savedValue = variables.findEntry(updateKey);

			if (!savedValue.isPresent()) {
				logger.error(
					"Wanted to update ComponentPacket variable %s but "
						+ "could not find it in saved data.%n",
					updateKey);

				continue;
			}

			try {
				updateValue(deserializer, savedValue.get(), newValue);
			} catch (final NumberFormatException e) {
				logger.error(
					"Unable to save value '%s' in ComponentPacket variable '%s'.%n",
					newValue, updateKey);
			}
		}
	}

	private static void updateHashtable (
		final PacketDeserializer deserializer
		, final DictionaryProperty variables
		, final Map<String, String> updates) {

		final Optional<DictionaryProperty> data = variables.findEntry("m_data");
		if (!data.isPresent()) {
			logger.error(
				"Tried to update a Hashtable property that "
					+ "did not contain an 'm_data' Hashtable.%n");
			return;
		}

		updateVariables(deserializer, data.get(), updates);
	}

	private static void updateValue (
		final PacketDeserializer deserializer, final Property property, final String val) {

		if (property instanceof SimpleProperty) {
			final Object typedValue = castValue(property.obj, val);
			Property.update(property, typedValue);
		} else if (property instanceof ComplexProperty) {
			final ComplexProperty complexProperty = (ComplexProperty) property;
			if (complexProperty.reference == null) {
				final Object typedValue = castValue(property.obj, val);
				updateComplexProperty((ComplexProperty) property, typedValue);
			} else {
				final Optional<Property> referencedProperty =
					deserializer.followReference(complexProperty);
				if (referencedProperty.isPresent()) {
					final Object typedValue = castValue(referencedProperty.get().obj, val);
					updateComplexProperty((ComplexProperty) referencedProperty.get(), typedValue);
				} else {
					logger.error("ComplexProperty was a reference that could not be followed.%n");
				}
			}
		} else {
			logger.error(
				"Unable to update a property that is not SimpleProperty or ComplexProperty.%n");
		}
	}

	private static void updateComplexProperty (final ComplexProperty property, final Object field) {
		if (property.obj instanceof EternityDateTime) {
			Property.update(property, "TotalSeconds", field);
		} else if (property.obj instanceof EternityTimeInterval) {
			Property.update(property, "SerializedSeconds", field);
		} else {
			logger.error(
				"Tried to update unsupported object of type '%s'.",
				property.obj.getClass().getSimpleName());
		}
	}

	private static Object castValue (final Object primitive, final String val) {
		if (primitive instanceof Integer) {
			return Integer.parseInt(val);
		}

		if (primitive instanceof Double) {
			return Double.parseDouble(val);
		}

		if (primitive instanceof Float) {
			return Float.parseFloat(val);
		}

		if (primitive instanceof Boolean) {
			return Boolean.parseBoolean(val);
		}

		if (primitive instanceof UnsignedInteger) {
			return UnsignedInteger.valueOf(val);
		}

		if (primitive instanceof EternityDateTime || primitive instanceof EternityTimeInterval) {
			return Integer.parseInt(val);
		}

		if (primitive.getClass().isEnum()) {
			for (final Object constant : primitive.getClass().getEnumConstants()) {
				final Optional<String> constantName = EKUtils.enumConstantName(constant);
				if (constantName.isPresent() && constantName.get().equals(val)) {
					return constant;
				}
			}

			logger.error(
				"Client returned non-existent enum value '%s' for class %s.%n", val,
				primitive.getClass().getName());
			return primitive.getClass().getEnumConstants()[0];
		}

		return val;
	}

	private static File cloneExtractedSave (final String oldSaveAbsolutePath) throws IOException {
		final File oldSave = new File(oldSaveAbsolutePath);
		if (!oldSave.exists() || !oldSave.isDirectory()) {
			logger.error("Source save directory not found or invalid: %s%n", oldSaveAbsolutePath);
			throw new IOException("Source save directory not found: " + oldSaveAbsolutePath);
		}

		final File newDirectory = createNewSaveDirectory(oldSaveAbsolutePath);

		logger.info("Cloning save from '%s' to '%s'%n",
			oldSave.getAbsolutePath(), newDirectory.getAbsolutePath());

		final File[] sourceFiles = oldSave.listFiles();
		if (sourceFiles != null) {
			logger.info("Source directory contains %d files.%n", sourceFiles.length);
		} else {
			logger.warn("Source directory listFiles() returned null!%n");
		}

		try {
			FileUtils.copyDirectory(oldSave, newDirectory);
		} catch (final IOException e) {
			// Don't leave a partial clone behind; it would occupy a sequence
			// number in the working directory and confuse the next attempt.
			FileUtils.deleteQuietly(newDirectory);
			throw e;
		}

		final File[] targetFiles = newDirectory.listFiles();
		if (targetFiles != null) {
			logger.info("Cloning complete. Target directory contains %d files.%n", targetFiles.length);
		} else {
			logger.error("Target directory is empty after copy!%n");
		}

		return newDirectory;
	}

	/**
	 * The file name a save is about to be written under, worked out the same
	 * way {@link #createNewSaveDirectory} does but without creating anything.
	 * The name typed into the save dialog isn't part of it — that only becomes
	 * the display name inside saveinfo.xml — so this is the only way the UI can
	 * tell someone which file to go looking for.
	 */
	public static String previewSaveFileName (final String oldSaveAbsolutePath) {
		if (oldSaveAbsolutePath == null || oldSaveAbsolutePath.isEmpty()) {
			return "";
		}

		final File oldSave = new File(oldSaveAbsolutePath);
		final String oldSaveName = oldSave.getName();
		if (!oldSaveName.contains(" ")) {
			return oldSaveName;
		}

		final String sessionID = oldSaveName.split(" ")[0].replace("-", "");
		final String sceneTitle = oldSaveName.substring(oldSaveName.lastIndexOf(" ") + 1);
		final int gameID = getAvailableGameID(
			Environment.getInstance().directory().working(), sessionID);

		return String.format("%s %d %s", sessionID, gameID, sceneTitle);
	}

	static File createNewSaveDirectory (final String oldSaveAbsolutePath) throws IOException {
		final Environment environment = Environment.getInstance();
		final File workingDirectory = environment.directory().working();
		final File oldSave = new File(oldSaveAbsolutePath);
		final String sessionID = oldSave.getName().split(" ")[0].replace("-", "");

		final int gameID = getAvailableGameID(workingDirectory, sessionID);
		return createNewSaveDirectory(
			workingDirectory, oldSave.getName(), sessionID, gameID);
	}

	private static File createNewSaveDirectory (
		final File workingDirectory
		, final String oldSaveName
		, final String sessionID
		, final int gameID) throws IOException {

		final String sceneTitle = oldSaveName.substring(oldSaveName.lastIndexOf(" ") + 1);
		final File newSaveDirectory = new File(
			workingDirectory, String.format("%s %d %s", sessionID, gameID, sceneTitle));

		if (!newSaveDirectory.exists() && !newSaveDirectory.mkdirs()) {
			throw new IOException("Unable to create directory: " + newSaveDirectory.getAbsolutePath());
		}

		return newSaveDirectory;
	}

	private static int getAvailableGameID (final File workingDirectory, final String sessionID) {
		// Unique among the saves the list unpacked, and in the saves folder
		// itself. The list is as old as the last search and leaves out what it
		// could not read, so a number can look free while a file is sitting
		// under it -- a backup put back since, a save cut short -- and the
		// first Save of an edit used to replace that file.
		final Set<String> existingIDs = new HashSet<>(gameIDs(workingDirectory.listFiles(), sessionID));
		final String savesLocation = Settings.getInstance().json.optString("savesLocation", "");
		if (!savesLocation.isEmpty()) {
			existingIDs.addAll(gameIDs(new File(savesLocation).listFiles(file ->
				file.isFile() && file.getName().toLowerCase().endsWith(".savegame")), sessionID));
		}

		int candidateID = 0;
		while (existingIDs.contains(String.format("%d", candidateID))) {
			candidateID++;
		}

		return candidateID;
	}

	// The second word of every name whose first is the session's.
	private static Set<String> gameIDs (final File[] saves, final String sessionID) {
		if (saves == null) {
			return Collections.emptySet();
		}

		return Arrays.stream(saves)
			.map(save -> save.getName().split(" "))
			.filter(words -> words.length > 1 && words[0].replace("-", "").equals(sessionID))
			.map(words -> words[1])
			.collect(Collectors.toSet());
	}

	public static class DeserializationException extends Exception {
		DeserializationException () {
			super();
		}
	}
}
