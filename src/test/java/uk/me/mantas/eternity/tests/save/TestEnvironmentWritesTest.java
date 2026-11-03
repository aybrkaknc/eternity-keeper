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

import net.lingala.zip4j.ZipFile;
import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.factory.SharpSerializerFactory;
import uk.me.mantas.eternity.game.ComponentPersistencePacket;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.ChangesSaver;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.save.PartyManager;
import uk.me.mantas.eternity.save.QuestRestorer;
import uk.me.mantas.eternity.save.StrongholdCatalog;
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.save.StrongholdManager.Change;
import uk.me.mantas.eternity.save.model.SaveObject;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the writers write on every save of a folder of real saves (the test
 * environment's), fingerprinted as {@link UnchangedWritesTest} does: the
 * written tree, with every GUID the save did not already hold numbered in
 * order of appearance.
 *
 * <p>It runs only when asked, since it needs saves no checkout has, and takes
 * minutes:
 *
 * <pre>
 * mvn test -Pwin64 -Dtest=TestEnvironmentWritesTest
 *     -Dek.probeSaves=&lt;saves folder&gt; -Dek.probeOut=&lt;file to record into&gt;
 *     [-Dek.probeExpect=&lt;an earlier recording&gt;]
 * </pre>
 *
 * Record on the code before a change, then run with that recording as
 * {@code ek.probeExpect} after it: every scenario both runs know must come out
 * the same. A writer moved onto the save model is checked this way before the
 * move is committed (docs/testing.md).
 *
 * <p>Every file of every save is also checked for the save model knowing the
 * same objects the mirror does.
 */
public class TestEnvironmentWritesTest extends TestHarness {
	private static final Pattern GUID = Pattern.compile(
		"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private interface Scenario {
		File run (File saveDir) throws Exception;
	}

	private final List<String> problems = new ArrayList<>();
	private String outcomes = "";

	@Test
	public void writesWhatTheyWroteBefore () throws Throwable {
		Assume.assumeTrue("set ek.probeSaves to run it", System.getProperty("ek.probeSaves") != null);

		// The tree of a world state is deep, and some of it is shared: the
		// walk needs more stack than a test thread has.
		final Throwable[] failed = new Throwable[1];
		final Thread thread = new Thread(null, () -> {
			try {
				probeAll();
			} catch (final Throwable e) {
				failed[0] = e;
			}
		}, "probe", 1L << 30);
		thread.start();
		thread.join();
		if (failed[0] != null) {
			throw failed[0];
		}

		assertTrue(String.join("\n", problems), problems.isEmpty());
	}

	private void probeAll () throws Exception {
		final File saves = new File(System.getProperty("ek.probeSaves"));
		final Properties results = new Properties();
		StrongholdCatalog.useNoCatalog();

		final File[] archives = saves.listFiles((dir, name) -> name.endsWith(".savegame"));
		assertTrue("no saves in " + saves, archives != null && archives.length > 0);
		Arrays.sort(archives);
		for (final File archive : archives) {
			final String save = archive.getName();
			final File whole = unzip(archive, false);
			final DeserializedPackets original = read(new File(whole, "MobileObjects.save"));
			final String player = findPlayer(original);
			final String companion = findPartyCompanion(original);
			checkObjects(save, whole);
			FileUtils.deleteQuietly(whole.getParentFile());

			run(results, save, "party", archive, true, dir -> {
				if (companion == null) {
					return null;
				}

				final Map<String, Boolean> dismiss = new LinkedHashMap<>();
				dismiss.put(companion, false);
				outcome("dismiss", new PartyManager(dir).apply(dismiss));
				final Map<String, Boolean> recruit = new LinkedHashMap<>();
				recruit.put(companion, true);
				outcome("recruit", new PartyManager(dir).apply(recruit));
				return dir;
			});

			run(results, save, "stronghold", archive, true, dir -> {
				outcome("stronghold", new StrongholdManager(dir).apply(Arrays.asList(
					Change.setNumber("Prestige", 40), Change.setNumber("AvailableTurns", 9))));
				return dir;
			});

			run(results, save, "quest", archive, true, dir -> {
				final File file = new File(dir, "MobileObjects.save");
				final DeserializedPackets packets = read(file);
				outcome("quest", QuestRestorer.restore(packets.getPackets()
					, "data/quests/companions/companion_qst_durance.quest"));
				packets.replace(file);
				return dir;
			});

			run(results, save, "importPlayer", archive, false, dir -> overwrite(dir, player));
			run(results, save, "importCompanion", archive, false,
				dir -> companion == null ? null : overwrite(dir, companion));
			run(results, save, "save", archive, false, dir -> save(dir, player, companion));
		}

		final String out = System.getProperty("ek.probeOut");
		if (out != null) {
			try (OutputStream stream = new FileOutputStream(out)) {
				results.store(stream, "TestEnvironmentWritesTest");
			}
		}

		final String expect = System.getProperty("ek.probeExpect");
		if (expect != null) {
			compare(results, expect);
		}
	}

	private void compare (final Properties results, final String expect) throws IOException {
		final Properties expected = new Properties();
		try (InputStream stream = new FileInputStream(expect)) {
			expected.load(stream);
		}

		int compared = 0;
		for (final String key : new TreeSet<>(results.stringPropertyNames())) {
			final String before = expected.getProperty(key);
			if (before == null) {
				System.out.println("PROBE new scenario " + key);
				continue;
			}

			compared++;
			if (!before.equals(results.getProperty(key))) {
				problems.add(key + " was " + before + ", is " + results.getProperty(key));
			}
		}

		System.out.println("PROBE compared " + compared + " scenarios with " + expect);
	}

	// The save model knows an object by the tree alone; on every file of a
	// real save that has to be exactly the objects the mirror knows.
	private void checkObjects (final String save, final File dir) throws IOException {
		final File[] files = dir.listFiles((d, name) -> name.endsWith(".save") || name.endsWith(".lvl"));
		if (files == null) {
			return;
		}

		for (final File file : files) {
			final Optional<DeserializedPackets> packets = new PacketDeserializer(file).deserializeEvenIfShort();
			if (!packets.isPresent()) {
				continue;
			}

			for (final Property packet : packets.get().getPackets()) {
				final boolean mirror = packet.obj instanceof ObjectPersistencePacket;
				if (mirror != SaveObject.of(packet).isPresent()) {
					problems.add(save + "/" + file.getName() + ": " + packet.name
						+ (mirror ? " is an object the model does not know" : " is no object, but the model takes it for one"));
				}
			}
		}
	}

	private void outcome (final String what, final boolean result) {
		outcomes += what + "=" + result + " ";
	}

	private void run (
		final Properties results, final String save, final String scenario
		, final File archive, final boolean worldOnly, final Scenario body) throws Exception {

		final File dir = unzip(archive, worldOnly);
		final Set<String> known = ids(read(new File(dir, "MobileObjects.save")));
		outcomes = "";
		String result;
		try {
			final File written = body.run(dir);
			result = written == null
				? "skipped"
				: outcomes + fingerprint(read(new File(written, "MobileObjects.save")), known);
		} catch (final Exception e) {
			result = outcomes + "threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
		}

		results.setProperty(save + "/" + scenario, result);
		System.out.println("PROBE " + save + "/" + scenario + " " + result);
		FileUtils.deleteQuietly(dir.getParentFile());

		// A fresh environment (and temp) for each scenario, as each test gets.
		cleanup();
		setup();
	}

	private File overwrite (final File dir, final String guid) throws Exception {
		final File chr = new File(dir.getParentFile(), "subject.chr");
		if (!new CharacterExporter(dir.getAbsolutePath(), guid, chr.getAbsolutePath()).export()) {
			return null;
		}

		final JSONObject request = new JSONObject()
			.put("oldSave", dir.getAbsolutePath())
			.put("savedYet", false);
		final CharacterImporter importer = new CharacterImporter(request.toString(), chr.getAbsolutePath());
		outcome("import", importer.overwriteCharacter());
		return importer.saveFile();
	}

	private File save (final File opened, final String player, final String companion) throws Exception {
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

		final org.json.JSONArray characters = new org.json.JSONArray()
			.put(new JSONObject()
				.put("GUID", player)
				.put("stats", new JSONObject().put("OverrideName", value("Newcomer"))));
		if (companion != null) {
			characters.put(new JSONObject()
				.put("GUID", companion)
				.put("stats", new JSONObject())
				.put("health", new JSONObject().put("m_needs_current_values", value("true"))));
		}

		final JSONObject request = new JSONObject()
			.put("savedYet", false)
			.put("saveName", "PROBE")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", characters)
				.put("currency", 1234.0)
				.put("campingSupplies", 4)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject()
						.put("GameState", new JSONObject().put("TacticalMode", value("RoundBased"))))
					.put("InGameGlobal", new JSONObject()
						.put("GlobalVariables", new JSONObject()
							.put("nDuranceQuestState", value("3"))))));

		final File written = new ChangesSaver(request).saveChanges();
		FileUtils.deleteQuietly(savesLocation);
		return written;
	}

	private static JSONObject value (final String value) {
		return new JSONObject().put("value", value);
	}

	private static File unzip (final File archive, final boolean worldOnly) throws Exception {
		final File dir = new File(EKUtils.createTempDir(PREFIX).get(), archive.getName());
		final ZipFile zip = new ZipFile(archive);
		if (worldOnly) {
			zip.extractFile("MobileObjects.save", dir.getAbsolutePath());
		} else {
			zip.extractAll(dir.getAbsolutePath());
		}

		return dir;
	}

	// Found through the mirror, which nothing here changes: the probe must not
	// lean on the model it checks.
	private static String findPlayer (final DeserializedPackets packets) {
		for (final Property property : packets.getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (packet.ObjectName != null && packet.ObjectName.startsWith("Player_")) {
				return packet.ObjectID;
			}
		}

		return null;
	}

	private static String findPartyCompanion (final DeserializedPackets packets) {
		for (final Property property : packets.getPackets()) {
			if (!(property.obj instanceof ObjectPersistencePacket)) {
				continue;
			}

			final ObjectPersistencePacket packet = (ObjectPersistencePacket) property.obj;
			if (packet.ObjectName == null || !packet.ObjectName.startsWith("Companion_")
				|| packet.ObjectName.endsWith("_stored") || packet.ComponentPackets == null) {
				continue;
			}

			for (final ComponentPersistencePacket component : packet.ComponentPackets) {
				if ("PartyMemberAI".equals(component.TypeString)) {
					return packet.ObjectID;
				}
			}
		}

		return null;
	}

	private static DeserializedPackets read (final File file) throws IOException {
		return new PacketDeserializer(file).deserialize().get();
	}

	private interface Sink {
		void take (String chunk) throws Exception;
	}

	private static Set<String> ids (final DeserializedPackets packets) throws Exception {
		final Set<String> ids = new HashSet<>();
		dump(packets, chunk -> {
			final Matcher found = GUID.matcher(chunk);
			while (found.find()) {
				ids.add(found.group().toLowerCase(Locale.ROOT));
			}
		});

		return ids;
	}

	private static String fingerprint (final DeserializedPackets packets, final Set<String> known)
		throws Exception {

		final Map<String, String> fresh = new LinkedHashMap<>();
		final MessageDigest digest = MessageDigest.getInstance("SHA-256");
		dump(packets, chunk -> {
			final Matcher found = GUID.matcher(chunk);
			final StringBuffer normalised = new StringBuffer();
			while (found.find()) {
				final String id = found.group().toLowerCase(Locale.ROOT);
				final String replacement = known.contains(id)
					? id
					: fresh.computeIfAbsent(id, k -> "<new " + fresh.size() + ">");
				found.appendReplacement(normalised, Matcher.quoteReplacement(replacement));
			}

			found.appendTail(normalised);
			digest.update(normalised.toString().getBytes(StandardCharsets.UTF_8));
		});

		return String.format("%064x", new BigInteger(1, digest.digest()));
	}

	// One object at a time, so a world state never has to fit in one string;
	// a node the tree shares is written out once and named after that.
	private static void dump (final DeserializedPackets packets, final Sink sink) throws Exception {
		final Map<Object, Integer> seen = new IdentityHashMap<>();
		sink.take("count=" + packets.getCount().value + "\n");
		int index = 0;
		for (final Property packet : packets.getPackets()) {
			final StringBuilder out = new StringBuilder();
			dump(out, "#" + index++, packet, seen);
			sink.take(out.toString());
		}
	}

	private static void dump (
		final StringBuilder out, final String path, final Object item, final Map<Object, Integer> seen) {

		if (item instanceof Property) {
			final Integer first = seen.get(item);
			if (first != null) {
				out.append(path).append(" same as node ").append(first).append('\n');
				return;
			}

			seen.put(item, seen.size());
		}

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
				dump(out, here + "." + i++, child, seen);
			}
		}

		if (property instanceof CollectionProperty) {
			int i = 0;
			for (final Property child : ((CollectionProperty) property).items) {
				dump(out, here + "[" + i++ + "]", child, seen);
			}
		}

		if (property instanceof DictionaryProperty) {
			int i = 0;
			for (final Map.Entry<Property, Property> entry : ((DictionaryProperty) property).items) {
				dump(out, here + "{k" + i + "}", entry.getKey(), seen);
				dump(out, here + "{v" + i++ + "}", entry.getValue(), seen);
			}
		}

		if (property instanceof SingleDimensionalArrayProperty) {
			int i = 0;
			for (final Object child : ((SingleDimensionalArrayProperty) property).items) {
				dump(out, here + "[" + i++ + "]", child, seen);
			}
		}

		if (property instanceof MultiDimensionalArrayProperty) {
			for (final MultiDimensionalArrayProperty.MultiDimensionalArrayItem child
				: ((MultiDimensionalArrayProperty) property).items) {

				dump(out, here + Arrays.toString(child.indexes), child.value, seen);
			}
		}
	}
}
