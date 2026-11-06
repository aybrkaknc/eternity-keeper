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
import net.lingala.zip4j.model.ZipParameters;
import org.apache.commons.io.FileUtils;
import org.cef.callback.CefQueryCallback;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.Settings;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.factory.PacketDeserializerFactory;
import uk.me.mantas.eternity.factory.SharpSerializerFactory;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.handlers.SaveChanges;
import uk.me.mantas.eternity.save.AbilityManager;
import uk.me.mantas.eternity.save.AchievementsEnabler;
import uk.me.mantas.eternity.save.ChangesSaver;
import uk.me.mantas.eternity.save.CharacterExporter;
import uk.me.mantas.eternity.save.CharacterImporter;
import uk.me.mantas.eternity.save.GrimoireManager;
import uk.me.mantas.eternity.save.InventoryManager;
import uk.me.mantas.eternity.save.PartyManager;
import uk.me.mantas.eternity.save.Resurrector;
import uk.me.mantas.eternity.save.SavedGameOpener;
import uk.me.mantas.eternity.save.StrongholdManager;
import uk.me.mantas.eternity.save.VendorManager;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SerializerFormat;
import uk.me.mantas.eternity.serializer.ShortReadException;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * A packet file read short is never written back.
 *
 * <p>{@code PacketDeserializer} reads a file's leading count and then that many
 * packets. On a file cut short it used to hand back whatever came before the
 * damage -- no exception, nothing to say the read was partial -- and every
 * editor that writes a save would then write that partial read over the file,
 * dropping everything after the damage. (Measured: 1 packet of 6 from an area
 * file cut short; 6,043 of 6,953 from a real world state cut at 90%.)
 *
 * <p>Each test damages a copy of a fixture the way a copy cut short is damaged
 * and drives one writer at it, with an edit that succeeds on the whole file.
 * The object that goes missing is always one the edit never touches, so the
 * only thing wrong with the edit is that it would lose it. Every writer must
 * refuse, say why, and leave the file exactly as it was.
 */
public class ShortReadTest extends TestHarness {
	private static final String ELWYN = "09517a0d-4fec-407c-a749-a531f3be64e0";
	private static final String CALISCA = "b1a7e809-0000-0000-0000-000000000000";
	private static final String CALISCA_PREFIX = "Companion_Calisca";
	private static final String GAUNS_PLEDGE = "c4b7033d-c452-4946-a9ba-9998cb411201";
	private static final String KNOCKDOWN = "3ffdbced-4efb-4a6a-afcc-686fed65381b";
	private static final String GRIMOIRE = "11111111-2222-3333-4444-555555555555";
	private static final String WORLD = "MobileObjects.save";

	// The prologue fixtures end with Global(Clone), which holds GameState and
	// nothing any of these edits reads or writes.
	private static final String LOST = "Global(Clone)";

	private static final String WHAT_THE_USER_READS = "Only 22 of the 23 objects in "
		+ "MobileObjects.save could be read, so nothing was written: the rest would "
		+ "have been lost.";

	private interface Edit {
		Object run () throws Exception;
	}

	/** The edit must be refused because of the short read, and nothing else. */
	private static ShortReadException refuses (final Edit edit) throws Exception {
		try {
			edit.run();
		} catch (final ShortReadException e) {
			return e;
		}

		fail("a short read was taken for the whole file");
		return null;
	}

	private static void untouched (final byte[] damaged, final File file) throws IOException {
		assertArrayEquals(
			file.getName() + " was written over", damaged, Files.readAllBytes(file.toPath()));
	}

	private File resources () throws URISyntaxException {
		return new File(getClass().getResource("/").toURI());
	}

	/** A save directory of its own, holding a copy of a fixture as its world state. */
	private File saveWith (final String fixture, final String name) throws Exception {
		final File saveDir = new File(EKUtils.createTempDir(PREFIX).get(), name);
		assertTrue(saveDir.mkdir());
		FileUtils.copyFile(new File(resources(), fixture), new File(saveDir, WORLD));
		return saveDir;
	}

	private File saveWith (final String fixture) throws Exception {
		return saveWith(fixture, "damaged.savegame");
	}

	/**
	 * Damages a packet file the way a copy cut short is damaged. The object
	 * named {@code lost} is moved to the end -- the reader makes nothing of the
	 * order -- and the file stops half-way through it, so its leading count
	 * still promises that object and everything before it still reads.
	 *
	 * @return the damaged file's bytes, to show that nothing wrote over them
	 */
	static byte[] cutShort (final File file, final String lost) throws IOException {
		final DeserializedPackets read = new PacketDeserializer(file).deserialize().get();
		final List<Property> packets = new ArrayList<>(read.getPackets());
		final Property victim = packets.stream()
			.filter(p -> p.obj instanceof ObjectPersistencePacket
				&& lost.equals(((ObjectPersistencePacket) p.obj).ObjectName))
			.findFirst()
			.orElseThrow(() -> new AssertionError("There is no " + lost + " in " + file));

		packets.remove(victim);
		packets.add(victim);
		read.setPackets(packets);
		assertTrue(file.delete() && file.createNewFile());
		read.reserialize(file);

		// Where the lost object starts: the length of the same file without it.
		// That file's count no longer matches, so it is put together in memory
		// -- judged against itself as it stands -- rather than written as an
		// edit of what was read, which the writer would refuse.
		final File without = new File(EKUtils.createTempDir(PREFIX).get(), file.getName());
		assertTrue(without.createNewFile());
		new DeserializedPackets(packets.subList(0, packets.size() - 1), read.getCount())
			.reserialize(without);

		final long start = without.length();
		final long end = file.length();
		assertTrue(start > 0 && end > start);

		try (final RandomAccessFile cut = new RandomAccessFile(file, "rw")) {
			cut.setLength(start + (end - start) / 2);
		}

		return Files.readAllBytes(file.toPath());
	}

	@Test
	public void aFileCutShortSaysHowShort () throws Exception {
		final File world = new File(saveWith(WORLD), WORLD);
		cutShort(world, LOST);

		final ShortReadException e = refuses(() -> new PacketDeserializer(world).deserialize());
		assertEquals(WORLD, e.file);
		assertEquals(23, e.declared);
		assertEquals(22, e.read);
		assertEquals(WHAT_THE_USER_READS, e.getMessage());

		// It can still be looked at -- the opener shows what is there -- but
		// what was looked at knows it is not the whole file.
		final DeserializedPackets partial = new PacketDeserializer(world).deserializeEvenIfShort().get();
		assertEquals(22, partial.getPackets().size());
		assertFalse(partial.isWhole());
		assertTrue(new PacketDeserializer(new File(resources(), WORLD)).deserialize().get().isWhole());
	}

	@Test
	public void aShortReadCannotBeWrittenEvenByAccident () throws Exception {
		final File world = new File(saveWith(WORLD), WORLD);
		final byte[] damaged = cutShort(world, LOST);
		final DeserializedPackets partial = new PacketDeserializer(world).deserializeEvenIfShort().get();

		// Nor does a count brought into line with what was read make it whole:
		// that is exactly what every manager does before it writes.
		assertTrue(Property.update(partial.getCount(), partial.getPackets().size()));

		assertEquals(WHAT_THE_USER_READS, refuses(() -> {
			partial.replace(world);
			return null;
		}).getMessage());

		untouched(damaged, world);
		assertArrayEquals("no half-written sibling is left beside it"
			, new String[] {WORLD}, world.getParentFile().list());

		final File elsewhere = new File(EKUtils.createTempDir(PREFIX).get(), WORLD);
		assertTrue(elsewhere.createNewFile());
		refuses(() -> {
			partial.reserialize(elsewhere);
			return null;
		});

		assertEquals(0, elsewhere.length());
	}

	@Test
	public void theInventoryIsNotWritten () throws Exception {
		final File saveDir = saveWith(WORLD);
		final byte[] damaged = cutShort(new File(saveDir, WORLD), LOST);

		assertEquals(WHAT_THE_USER_READS, refuses(() ->
			new InventoryManager(saveDir).apply(Collections.singletonList(
				new InventoryManager.Change(ELWYN, "PlayerInventory", GAUNS_PLEDGE, 0))))
			.getMessage());

		untouched(damaged, new File(saveDir, WORLD));
	}

	@Test
	public void abilitiesAreNotWritten () throws Exception {
		final File saveDir = saveWith(WORLD);
		final byte[] damaged = cutShort(new File(saveDir, WORLD), LOST);

		refuses(() -> new AbilityManager(saveDir).apply(Collections.singletonList(
			AbilityManager.Change.removeAbility(ELWYN, KNOCKDOWN))));

		untouched(damaged, new File(saveDir, WORLD));
	}

	@Test
	public void aGrimoireIsNotWritten () throws Exception {
		// The grimoire is this fixture's last object, so Global(Clone) is moved
		// past it: what is lost is still something the edit never needs.
		final File saveDir = saveWith("GrimoireManagerTest/" + WORLD);
		final byte[] damaged = cutShort(new File(saveDir, WORLD), LOST);

		refuses(() -> new GrimoireManager(saveDir).apply(Collections.singletonList(
			new GrimoireManager.Change(GRIMOIRE, Collections.emptyList()))));

		untouched(damaged, new File(saveDir, WORLD));
	}

	@Test
	public void theStrongholdIsNotWritten () throws Exception {
		final File saveDir = saveWith(WORLD);
		final byte[] damaged = cutShort(new File(saveDir, WORLD), LOST);

		refuses(() -> new StrongholdManager(saveDir).apply(Arrays.asList(
			StrongholdManager.Change.activate(true)
			, StrongholdManager.Change.setNumber("Prestige", 5))));

		untouched(damaged, new File(saveDir, WORLD));
	}

	@Test
	public void thePartyIsNotWritten () throws Exception {
		final File saveDir = saveWith(WORLD);
		final byte[] damaged = cutShort(new File(saveDir, WORLD), LOST);

		final Map<String, Boolean> desired = new LinkedHashMap<>();
		desired.put(CALISCA, false);
		refuses(() -> new PartyManager(saveDir).apply(desired));

		untouched(damaged, new File(saveDir, WORLD));
	}

	@Test
	public void theAchievementsFlagIsNotWritten () throws Exception {
		final File saveDir = saveWith(WORLD);
		final byte[] damaged = cutShort(new File(saveDir, WORLD), LOST);

		refuses(() -> new AchievementsEnabler(saveDir).set(false));

		untouched(damaged, new File(saveDir, WORLD));
	}

	/** Takes Calisca out of a save the way her death would, with her death flag. */
	private static void killCalisca (final File saveDir) throws IOException {
		final File world = new File(saveDir, WORLD);
		final DeserializedPackets read = new PacketDeserializer(world).deserialize().get();
		final List<Property> alive = new ArrayList<>();

		for (final Property packet : read.getPackets()) {
			final ObjectPersistencePacket object = (ObjectPersistencePacket) packet.obj;
			final boolean hers = (object.ObjectName != null
					&& object.ObjectName.startsWith(CALISCA_PREFIX)
					&& !object.ObjectName.endsWith("_stored"))
				|| (object.Parent != null && object.Parent.startsWith(CALISCA_PREFIX));

			if (!hers) {
				alive.add(packet);
			}
		}

		assertTrue(alive.size() < read.getPackets().size());
		read.setPackets(alive);
		assertTrue(Property.update(read.getCount(), alive.size()));
		assertTrue(world.delete() && world.createNewFile());
		read.reserialize(world);

		DeadCompanionsTest.setGlobalFlag(saveDir, "b_Eder_Dead", 1);
	}

	private Edit resurrectCalisca (final File deadDir, final File donorDir) {
		return () -> new Resurrector(deadDir).transplant(new File(donorDir, WORLD), CALISCA_PREFIX
			, "b_Eder_Dead", Collections.emptyList(), null, null);
	}

	@Test
	public void aResurrectionIsNotWrittenIntoADamagedSave () throws Exception {
		final File deadDir = saveWith(WORLD, "cadena 0 Dead.savegame");
		killCalisca(deadDir);
		final byte[] damaged = cutShort(new File(deadDir, WORLD), LOST);

		refuses(resurrectCalisca(deadDir, saveWith(WORLD, "cadena 5 Donor.savegame")));

		untouched(damaged, new File(deadDir, WORLD));
	}

	@Test
	public void aDamagedDonorIsNotTransplantedFrom () throws Exception {
		final File deadDir = saveWith(WORLD, "cadena 0 Dead.savegame");
		killCalisca(deadDir);
		final byte[] dead = Files.readAllBytes(new File(deadDir, WORLD).toPath());

		final File donorDir = saveWith(WORLD, "cadena 5 Donor.savegame");
		cutShort(new File(donorDir, WORLD), LOST);

		// Calisca herself is all there; what is gone is only what the donor
		// save held after her. Nothing says that part held none of hers.
		refuses(resurrectCalisca(deadDir, donorDir));

		untouched(dead, new File(deadDir, WORLD));
	}

	@Test
	public void aDamagedSaveIsNeverChosenAsADonor () throws Exception {
		final File deadDir = saveWith(WORLD, "cadena 0 Dead.savegame");
		killCalisca(deadDir);

		final File donorDir = saveWith(WORLD, "cadena 5 Donor.savegame");
		cutShort(new File(donorDir, WORLD), LOST);

		final File saves = EKUtils.createTempDir(PREFIX).get();
		new ZipFile(new File(saves, donorDir.getName())).addFiles(
			new ArrayList<>(Arrays.asList(donorDir.listFiles())), new ZipParameters());

		final Settings settings = mockSettings();
		settings.json = new JSONObject();
		settings.json.put("savesLocation", saves.getAbsolutePath());

		assertFalse(new Resurrector(deadDir).findDonor(CALISCA_PREFIX).isPresent());
	}

	private File exportCalisca () throws Exception {
		final File chr = new File(EKUtils.createTempDir(PREFIX).get(), "calisca.chr");
		assertTrue(new CharacterExporter(
			resources().getAbsolutePath(), CALISCA, chr.getAbsolutePath()).export());

		return chr;
	}

	private static String importRequest (final File saveDir) {
		return new JSONObject()
			.put("oldSave", saveDir.getAbsolutePath())
			.put("savedYet", false)
			.toString();
	}

	@Test
	public void aCharacterIsNotImportedIntoADamagedSave () throws Exception {
		final File chr = exportCalisca();
		final File saveDir = saveWith(WORLD);
		final byte[] damaged = cutShort(new File(saveDir, WORLD), LOST);

		final CharacterImporter importer =
			new CharacterImporter(importRequest(saveDir), chr.getAbsolutePath());

		refuses(importer::detectConflict);
		refuses(importer::importCharacter);
		refuses(importer::overwriteCharacter);

		untouched(damaged, new File(importer.saveFile(), WORLD));
		untouched(damaged, new File(saveDir, WORLD));
	}

	@Test
	public void aDamagedCharacterFileIsNotImported () throws Exception {
		final File chr = exportCalisca();
		final int objects = new PacketDeserializer(chr).deserialize().get().getPackets().size();
		cutShort(chr, "Torch01(Clone)");

		final File saveDir = saveWith(WORLD);
		final byte[] save = Files.readAllBytes(new File(saveDir, WORLD).toPath());
		final CharacterImporter importer =
			new CharacterImporter(importRequest(saveDir), chr.getAbsolutePath());

		final ShortReadException e = refuses(importer::importCharacter);
		assertEquals("calisca.chr", e.file);
		assertEquals(objects, e.declared);
		assertEquals(objects - 1, e.read);
		assertTrue(e.getMessage(), e.getMessage().contains(" objects in calisca.chr could be read"));

		untouched(save, new File(importer.saveFile(), WORLD));
	}

	@Test
	public void aCharacterIsNotExportedFromADamagedSave () throws Exception {
		final File saveDir = saveWith(WORLD);
		cutShort(new File(saveDir, WORLD), LOST);

		final File chr = new File(EKUtils.createTempDir(PREFIX).get(), "elwyn.chr");
		refuses(() -> new CharacterExporter(
			saveDir.getAbsolutePath(), ELWYN, chr.getAbsolutePath()).export());

		assertFalse("a refused export leaves no file behind", chr.exists());
	}

	@Test
	public void aDamagedSaveIsNotConverted () throws Exception {
		final File world = new File(saveWith(WORLD), WORLD);
		cutShort(world, LOST);

		final File converted = new File(EKUtils.createTempDir(PREFIX).get(), WORLD);
		assertTrue(converted.createNewFile());

		refuses(() -> {
			EKUtils.reserializeFile(world, converted, SerializerFormat.UNITY_2017);
			return null;
		});

		assertEquals(0, converted.length());
	}

	@Test
	public void aVendorInADamagedAreaIsLeftAlone () throws Exception {
		final File save = VendorStockTest.setupSave(getClass());
		final File hall = new File(save, VendorStockTest.ARTIFICER_HALL);
		final byte[] damaged = cutShort(hall, "Examinable_Crate");

		final VendorManager manager = new VendorManager(save);
		assertFalse(manager.apply(Collections.singletonList(new VendorManager.Removal(
			VendorStockTest.ARTIFICER_HALL
			, VendorStockTest.STORE_ARTIFICER
			, Collections.singletonList(
				new VendorManager.Entry(0, VendorStockTest.TRAP_ARROW))))));

		// The same words every other writer uses for the same thing.
		assertEquals("Only 7 of the 8 objects in AR_0611_Artificer_Hall.lvl could be read, "
				+ "so nothing was written: the rest would have been lost."
			, manager.problem().orElse(null));

		untouched(damaged, hall);
	}

	@Test
	public void aDamagedSaveIsNotSaved () throws Exception {
		final Environment mockEnvironment = mockEnvironment();
		final File workingDirectory = EKUtils.createTempDir(PREFIX).get();
		final File settingsFile = new File(workingDirectory, "settings.json");

		FileUtils.writeStringToFile(settingsFile, "{}", "UTF-8");
		when(mockEnvironment.directory().settingsFile()).thenReturn(settingsFile);
		when(mockEnvironment.directory().working()).thenReturn(workingDirectory);
		when(mockEnvironment.factory().packetDeserializer())
			.thenReturn(new PacketDeserializerFactory());
		when(mockEnvironment.factory().sharpSerializer()).thenReturn(new SharpSerializerFactory());

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		doThrow(new JSONException("")).when(mockJSON).getString(anyString());

		final File savesLocation = EKUtils.createTempDir(PREFIX).get();
		when(mockJSON.optString(eq("savesLocation"), anyString()))
			.thenReturn(savesLocation.getAbsolutePath());

		final File opened = new File(EKUtils.createTempDir(PREFIX).get(), "id 0 Encampment.savegame");
		FileUtils.copyDirectory(
			new File(resources(), "ChangesSaverTest/id 0 Encampment.savegame"), opened);

		final byte[] damaged = cutShort(new File(opened, WORLD), LOST);

		final String request = new JSONObject()
			.put("savedYet", false)
			.put("saveName", "DAMAGED")
			.put("absolutePath", opened.getAbsolutePath())
			.put("saveData", new JSONObject()
				.put("characters", new JSONArray())
				.put("currency", 1.0)
				.put("globals", new JSONObject()
					.put("Global", new JSONObject())
					.put("InGameGlobal", new JSONObject())))
			.toString();

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new ChangesSaver(request, callback).run();

		verify(callback).failure(-1, SaveChanges.genericError("Only 16 of the 17 objects in "
			+ "MobileObjects.save could be read, so nothing was written: the rest would have "
			+ "been lost."));

		verify(callback, never()).success(anyString());
		assertArrayEquals("no save reached the saves folder", new String[0], savesLocation.list());
		untouched(damaged, new File(opened, WORLD));
	}

	@Test
	public void openingADamagedSaveShowsWhatIsThereAndSaysWhatIsNot () throws Exception {
		final File saveDir = saveWith(WORLD);
		cutShort(new File(saveDir, WORLD), LOST);

		final Settings mockSettings = mockSettings();
		final JSONObject mockJSON = mock(JSONObject.class);
		mockSettings.json = mockJSON;
		when(mockJSON.getString("gameLocation")).thenReturn(
			new File(resources(), "SavedGameOpenerTest").getAbsolutePath());

		final CefQueryCallback callback = mock(CefQueryCallback.class);
		new SavedGameOpener(saveDir.getAbsolutePath(), callback).run();

		final ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
		verify(callback).success(response.capture());
		final JSONObject reply = new JSONObject(response.getValue());

		// Both characters come before the damage, so both are there to see.
		assertEquals(2, reply.getJSONArray("characters").length());

		final JSONArray problems = reply.getJSONObject("validation").getJSONArray("problems");
		assertEquals(1, problems.length());
		assertEquals("SHORT_READ", problems.getJSONObject(0).getString("kind"));
		assertTrue(problems.getJSONObject(0).getString("detail"), problems.getJSONObject(0)
			.getString("detail").startsWith("only 22 of the 23 objects in MobileObjects.save"));
	}
}
