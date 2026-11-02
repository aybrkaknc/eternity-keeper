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

package uk.me.mantas.eternity.tests.save;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.factory.SharpSerializerFactory;
import uk.me.mantas.eternity.save.ChangesSaver;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.save.PartyManager;
import uk.me.mantas.eternity.save.QuestRestorer;
import uk.me.mantas.eternity.save.QuestTrackerBlob;
import uk.me.mantas.eternity.save.StrongholdCatalog;
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.save.StrongholdManager.Change;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.CollectionProperty;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.MultiDimensionalArrayProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.ReferenceTargetProperty;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the writers moved onto the save model write, recorded before the move:
 * a move meant to change nothing is held to having changed nothing.
 *
 * <p>Each scenario runs a writer on a fixture and reduces what it wrote to a
 * fingerprint: every property of every object, in order, with its C# type,
 * value and reference. IDs the writer made up itself (random GUIDs) are
 * numbered by where they first appear, so the fingerprint is the same from run
 * to run; every other ID stays as it is.
 *
 * <p>{@code -Dek.recordWrites=true} records the fingerprints afresh into
 * {@code src/test/resources/UnchangedWritesTest/recorded.properties}. Record
 * only on a commit whose writes are known good, and say so in its message.
 */
public class UnchangedWritesTest extends TestHarness {
	private static final String RECORDED = "UnchangedWritesTest/recorded.properties";
	private static final String PLAYER_GUID = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String COMPANION_GUID = "b1a7e809-0000-0000-0000-000000000000";
	private static final String DURANCE_QUEST =
		"data/quests/companions/companion_qst_durance.quest";

	private static final String CATALOG = "{"
		+ "\"maxHirelings\":8"
		+ ",\"upgrades\":{"
		+ "\"EasternBarbican\":{\"ordinal\":27,\"name\":\"Eastern Barbican\""
		+ ",\"cost\":0,\"days\":0,\"prestige\":1,\"security\":2,\"order\":1"
		+ ",\"global\":\"b_Eastern_Barbican\"}"
		+ ",\"MainKeep\":{\"ordinal\":4,\"name\":\"Main Keep\",\"cost\":1400"
		+ ",\"days\":3,\"prestige\":3,\"security\":4,\"order\":4"
		+ ",\"prerequisite\":\"EasternBarbican\"}"
		+ "}}";

	private static final Pattern GUID = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	@After
	public void restoreCatalog () {
		StrongholdCatalog.useNoCatalog();
	}

	private File resources () throws Exception {
		return new File(getClass().getResource("/").toURI());
	}

	private File saveFrom (final String fixture) throws Exception {
		final File saveDir = new File(EKUtils.createTempDir(PREFIX).get(), "target.savegame");
		assertTrue(saveDir.mkdir());
		FileUtils.copyFile(new File(resources(), fixture), new File(saveDir, "MobileObjects.save"));
		return saveDir;
	}

	@Test
	public void strongholdBuildAndNumbers () throws Exception {
		final File saveDir = saveFrom("MobileObjects.save");
		final File catalog = EKUtils.createTempDir(PREFIX).get();
		FileUtils.write(new File(catalog, "stronghold.json"), CATALOG, "UTF-8");
		StrongholdCatalog.useCatalogAt(catalog);

		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			Change.activate(true)
			, Change.addUpgrade("EasternBarbican")
			, Change.addUpgrade("MainKeep")
			, Change.setNumber("Prestige", 40)
			, Change.setNumber("AvailableTurns", 9))));
		check("strongholdBuildAndNumbers", saveDir, inputs(saveDir));

		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(Change.removeUpgrade("MainKeep"))));
		check("strongholdDemolish", saveDir, inputs(saveDir));
	}

	@Test
	public void strongholdHirelingsAndPrisoner () throws Exception {
		final File saveDir = saveFrom("StrongholdManagerTest/MobileObjects.save");
		assertTrue(new StrongholdManager(saveDir).apply(Arrays.asList(
			Change.dismissHireling("b_crucible_hireling")
			, Change.dismissHireling("b_archer_hireling")
			, Change.releasePrisoner("b_kestorik_prisoner"))));
		check("strongholdHirelingsAndPrisoner", saveDir, inputs(saveDir));
	}

	@Test
	public void importOverwritesCompanion () throws Exception {
		overwrite("importOverwritesCompanion", COMPANION_GUID);
	}

	@Test
	public void importOverwritesMainCharacter () throws Exception {
		overwrite("importOverwritesMainCharacter", PLAYER_GUID);
	}

	private void overwrite (final String scenario, final String guid) throws Exception {
		final File saveDir = saveFrom("MobileObjects.save");
		final Set<String> known = inputs(saveDir);
		final File chr = new File(saveDir.getParentFile(), "subject.chr");
		assertTrue(new CharacterExporter(
			resources().getAbsolutePath(), guid, chr.getAbsolutePath()).export());
		known.addAll(ids(read(chr)));

		final JSONObject request = new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath())
			.put("savedYet", false);
		final CharacterImporter importer = new CharacterImporter(request.toString(), chr.getAbsolutePath());
		assertTrue(importer.overwriteCharacter());
		check(scenario, importer.saveFile(), known);
	}

	@Test
	public void partyDismissesAndRecruits () throws Exception {
		final File saveDir = saveFrom("MobileObjects.save");
		final Set<String> known = inputs(saveDir);

		final Map<String, Boolean> dismiss = new LinkedHashMap<>();
		dismiss.put(COMPANION_GUID, false);
		assertTrue(new PartyManager(saveDir).apply(dismiss));
		check("partyDismisses", saveDir, known);

		final Map<String, Boolean> recruit = new LinkedHashMap<>();
		recruit.put(COMPANION_GUID, true);
		assertTrue(new PartyManager(saveDir).apply(recruit));
		check("partyRecruits", saveDir, known);
	}

	@Test
	public void questRestoresDurance () throws Exception {
		final File saveDir = saveFrom("MobileObjects.save");
		final Set<String> known = inputs(saveDir);
		final File mobileObjects = new File(saveDir, "MobileObjects.save");
		final DeserializedPackets packets = read(mobileObjects);

		// Durance's death: his quest failed with event 7, and his state global at 8.
		final Property trackers = QuestRestorer.findQuestTrackers(packets.getPackets()).get();
		final byte[] raw = unbox((Byte[]) ((SimpleProperty) trackers).value);
		final QuestTrackerBlob.Tracker tracker =
			QuestTrackerBlob.parse(raw).get().tracker(DURANCE_QUEST).get();
		tracker.setEndState(1);
		tracker.setFailed(true);
		tracker.setTriggeredEvent(7);
		assertTrue(Property.update(trackers, box(raw)));
		assertTrue(Property.update(inGameGlobal(packets.getPackets(), "nDuranceQuestState"), 8));

		assertTrue(QuestRestorer.restore(packets.getPackets(), DURANCE_QUEST));
		packets.replace(mobileObjects);
		check("questRestoresDurance", saveDir, known);
	}

	@Test
	public void saveWritesMoneySuppliesHealNameAndGlobals () throws Exception {
		final Environment environment = mockEnvironment();
		final File working = EKUtils.createTempDir(PREFIX).get();
		final File settings = new File(working, "settings.json");
		FileUtils.writeStringToFile(settings, "{}");
		when(environment.directory().settingsFile()).thenReturn(settings);
		when(environment.directory().working()).thenReturn(working);
		when(environment.factory().packetDeserializer()).thenReturn(new PacketDeserializerFactory());
		when(environment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject json = mock(JSONObject.class);
		mockSettings.json = json;
		doThrow(new JSONException("")).when(json).getString(anyString());
		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(json.optString(eq("savesLocation"), anyString())).thenReturn(savesLocation.getAbsolutePath());

		final File opened = new File(EKUtils.createTempDir(PREFIX).get(), "id 0 Encampment.savegame");
		FileUtils.copyDirectory(new File(resources(), "ChangesSaverTest/id 0 Encampment.savegame"), opened);
		FileUtils.copyFile(new File(resources(), "MobileObjects.save"), new File(opened, "MobileObjects.save"));
		final Set<String> known = inputs(opened);

		final JSONObject request = new JSONObject()
			.put("savedYet", false)
			.put("saveName", "UNCHANGED")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", new org.json.JSONArray()
					.put(new JSONObject()
						.put("GUID", PLAYER_GUID)
						.put("stats", new JSONObject()
							.put("OverrideName", value("Newcomer"))))
					.put(new JSONObject()
						.put("GUID", COMPANION_GUID)
						.put("stats", new JSONObject())
						.put("health", new JSONObject()
							.put("m_needs_current_values", value("true")))))
				.put("currency", 1234.0)
				.put("campingSupplies", 4)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject())
					.put("InGameGlobal", new JSONObject()
						.put("GlobalVariables", new JSONObject()
							.put("nDuranceQuestState", value("3"))))));

		final File written = new ChangesSaver(request).saveChanges();
		check("saveWritesMoneySuppliesHealNameAndGlobals", written, known);
	}

	private static JSONObject value (final String value) {
		return new JSONObject().put("value", value);
	}

	private static Property inGameGlobal (final List<Property> packets, final String name) {
		for (final Property packet : packets) {
			final Optional<Property> objectName = Property.find(packet, "ObjectName");
			if (!objectName.isPresent()
				|| !String.valueOf(((SimpleProperty) objectName.get()).value).startsWith("InGameGlobal(")) {
				continue;
			}

			final Optional<Property> entry = ((ComplexProperty) packet)
				.<SingleDimensionalArrayProperty>findProperty("ComponentPackets")
				.flatMap(components -> EKUtils.findSubComponent(components, "GlobalVariables"))
				.flatMap(globals -> globals.<DictionaryProperty>findProperty("Variables"))
				.flatMap(variables -> variables.<DictionaryProperty>findEntry("m_data"))
				.flatMap(data -> data.findEntry(name));
			assertTrue(entry.isPresent());
			return entry.get();
		}

		throw new AssertionError("no InGameGlobal in the fixture");
	}

	// -------------------- fingerprints --------------------

	private Set<String> inputs (final File saveDir) throws IOException {
		return ids(read(new File(saveDir, "MobileObjects.save")));
	}

	private static DeserializedPackets read (final File file) throws IOException {
		final Optional<DeserializedPackets> packets = new PacketDeserializer(file).deserialize();
		assertTrue(file.getName(), packets.isPresent());
		return packets.get();
	}

	private static Set<String> ids (final DeserializedPackets packets) {
		final Set<String> ids = new HashSet<>();
		final Matcher found = GUID.matcher(dump(packets));
		while (found.find()) {
			ids.add(found.group().toLowerCase(Locale.ROOT));
		}

		return ids;
	}

	private void check (final String scenario, final File saveDir, final Set<String> known)
		throws Exception {

		final String fingerprint = fingerprint(read(new File(saveDir, "MobileObjects.save")), known);
		final File recordedSource = new File("src/test/resources/" + RECORDED);

		if (Boolean.getBoolean("ek.recordWrites")) {
			final Properties recorded = new Properties();
			if (recordedSource.isFile()) {
				try (InputStream in = new FileInputStream(recordedSource)) {
					recorded.load(in);
				}
			}

			recorded.setProperty(scenario, fingerprint);
			recordedSource.getParentFile().mkdirs();
			try (OutputStream out = new FileOutputStream(recordedSource)) {
				recorded.store(out, "UnchangedWritesTest: what the moved writers wrote before the move");
			}

			return;
		}

		final Properties recorded = new Properties();
		try (InputStream in = getClass().getResourceAsStream("/" + RECORDED)) {
			assertNotNull("nothing recorded; run once with -Dek.recordWrites=true", in);
			recorded.load(in);
		}

		assertEquals(scenario + " writes something other than it did", recorded.getProperty(scenario), fingerprint);
	}

	private static String fingerprint (final DeserializedPackets packets, final Set<String> known)
		throws NoSuchAlgorithmException {

		final Map<String, String> fresh = new LinkedHashMap<>();
		final Matcher found = GUID.matcher(dump(packets));
		final StringBuffer normalised = new StringBuffer();
		while (found.find()) {
			final String id = found.group().toLowerCase(Locale.ROOT);
			final String replacement = known.contains(id)
				? id
				: fresh.computeIfAbsent(id, k -> "<new " + fresh.size() + ">");
			found.appendReplacement(normalised, Matcher.quoteReplacement(replacement));
		}

		found.appendTail(normalised);
		final byte[] digest = MessageDigest.getInstance("SHA-256")
			.digest(normalised.toString().getBytes(StandardCharsets.UTF_8));
		return String.format("%064x", new BigInteger(1, digest));
	}

	private static String dump (final DeserializedPackets packets) {
		final StringBuilder out = new StringBuilder();
		out.append("count=").append(packets.getCount().value).append('\n');
		int index = 0;
		for (final Property packet : packets.getPackets()) {
			dump(out, "#" + index++, packet);
		}

		return out.toString();
	}

	private static void dump (final StringBuilder out, final String path, final Object item) {
		if (!(item instanceof Property)) {
			out.append(path).append(" raw ").append(item).append('\n');
			return;
		}

		final Property property = (Property) item;
		final String here = path + "/" + property.name;
		out.append(here).append(' ')
			.append(property.getClass().getSimpleName()).append(' ')
			.append(property.type == null ? null : property.type.cSharpType);

		if (property instanceof ReferenceTargetProperty
			&& ((ReferenceTargetProperty) property).reference != null) {

			out.append(" ref=").append(((ReferenceTargetProperty) property).reference.id);
		}

		if (property instanceof SimpleProperty) {
			final Object value = ((SimpleProperty) property).value;
			out.append(" = ").append(value instanceof Byte[] ? Arrays.toString((Byte[]) value) : value);
		}

		out.append('\n');

		if (property instanceof ComplexProperty) {
			int i = 0;
			for (final Property child : ((ComplexProperty) property).properties) {
				dump(out, here + "." + i++, child);
			}
		}

		if (property instanceof CollectionProperty) {
			int i = 0;
			for (final Property child : ((CollectionProperty) property).items) {
				dump(out, here + "[" + i++ + "]", child);
			}
		}

		if (property instanceof DictionaryProperty) {
			int i = 0;
			for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
				dump(out, here + "{k" + i + "}", entry.getKey());
				dump(out, here + "{v" + i++ + "}", entry.getValue());
			}
		}

		if (property instanceof SingleDimensionalArrayProperty) {
			int i = 0;
			for (final Object child : ((SingleDimensionalArrayProperty) property).items) {
				dump(out, here + "[" + i++ + "]", child);
			}
		}

		if (property instanceof MultiDimensionalArrayProperty) {
			for (final MultiDimensionalArrayProperty.MultiDimensionalArrayItem child
				: ((MultiDimensionalArrayProperty) property).items) {

				dump(out, here + Arrays.toString(child.indexes), child.value);
			}
		}
	}

	private static byte[] unbox (final Byte[] boxed) {
		final byte[] raw = new byte[boxed.length];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = boxed[i];
		}

		return raw;
	}

	private static Byte[] box (final byte[] raw) {
		final Byte[] boxed = new Byte[raw.length];
		for (int i = 0; i < raw.length; i++) {
			boxed[i] = raw[i];
		}

		return boxed;
	}
}
