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


package uk.me.mantas.eternity.tests.serializer;

import org.apache.commons.io.FileUtils;
import ch.qos.logback.classic.Level;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.Deserializer;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.SharpSerializer;
import uk.me.mantas.eternity.tests.TestHarness;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Reading a packet file that is damaged.
 *
 * <p>A read ends in one of three ways: every object the file promises, fewer
 * of them and the word that it is short, or nothing. What it must never do is
 * end the thread it runs on -- a length read out of damaged bytes asking for
 * two gigabytes, objects nested until the stack runs out -- because the page
 * is waiting for that thread's answer and would wait for ever.
 *
 * <p>The damage is drawn from a fixed seed, so a failure names a file that can
 * be made again.
 */
public class DamagedPacketFileTest extends TestHarness {
	private static final long SEED = 20261004L;
	private static final int AREA_CUTS = 60;
	private static final int AREA_CHANGES = 400;
	private static final int WORLD_CHANGES = 40;

	// A thousand damaged files are a thousand stack traces in the log, and
	// what is under test is how the read ends, not what it says on the way.
	private static final Class<?>[] LOUD = {
		PacketDeserializer.class, SharpSerializer.class, Deserializer.class, DeserializedPackets.class};
	private final Map<Class<?>, Level> levels = new HashMap<>();

	private static ch.qos.logback.classic.Logger logback (final Class<?> loud) {
		return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loud);
	}

	@Before
	public void quiet () {
		for (final Class<?> loud : LOUD) {
			levels.put(loud, logback(loud).getLevel());
			logback(loud).setLevel(Level.OFF);
		}
	}

	@After
	public void restore () {
		levels.forEach((loud, level) -> logback(loud).setLevel(level));
	}

	private static byte[] fixture (final String name) throws Exception {
		return FileUtils.readFileToByteArray(
			new File(DamagedPacketFileTest.class.getResource(name).toURI()));
	}

	/** How the read of {@code contents} ended: a count of objects, or -1 for nothing. */
	private static int read (final File folder, final byte[] contents, final String what) throws Exception {
		final File file = new File(folder, "damaged.lvl");
		FileUtils.writeByteArrayToFile(file, contents);

		try {
			final Optional<DeserializedPackets> read =
				new PacketDeserializer(file).deserializeEvenIfShort();

			return read.map(packets -> packets.getPackets().size()).orElse(-1);
		} catch (final Throwable e) {
			throw new AssertionError(what + " ended the read with " + e, e);
		}
	}

	@Test(timeout = 240000)
	public void aFileCutAnywhereStillAnswers () throws Exception {
		final byte[] whole = fixture("/VendorsTest/AR_0611_Artificer_Hall.lvl");
		final File folder = EKUtils.createTempDir(PREFIX).get();
		final int objects = read(folder, whole, "the file as it is");
		assertTrue(objects > 0);

		final Random random = new Random(SEED);
		for (int i = 0; i < AREA_CUTS; i++) {
			final int length = random.nextInt(whole.length);
			final int found = read(folder, Arrays.copyOf(whole, length), "a cut at byte " + length);
			assertTrue("a cut at " + length + " cannot read more than the file held", found < objects);
		}
	}

	@Test(timeout = 240000)
	public void aFileWithAnyByteChangedStillAnswers () throws Exception {
		final byte[] whole = fixture("/VendorsTest/AR_0611_Artificer_Hall.lvl");
		final File folder = EKUtils.createTempDir(PREFIX).get();
		final Random random = new Random(SEED + 1);

		for (int i = 0; i < AREA_CHANGES; i++) {
			final byte[] damaged = whole.clone();
			final int at = random.nextInt(whole.length);
			damaged[at] = (byte) random.nextInt(256);
			read(folder, damaged, "byte " + at + " set to " + (damaged[at] & 0xff));
		}
	}

	// A length or a count is a few bytes like any others, and damage makes
	// one of them enormous as easily as it makes it wrong.
	@Test(timeout = 240000)
	public void aLengthGoneWildStillAnswers () throws Exception {
		final byte[] whole = fixture("/VendorsTest/AR_0611_Artificer_Hall.lvl");
		final File folder = EKUtils.createTempDir(PREFIX).get();
		final Random random = new Random(SEED + 2);
		final byte[][] wild = {
			{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f}
			, {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}
			, {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x07}
			, {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x40}
			, {(byte) 0x04, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x7f}
		};

		for (int i = 0; i < AREA_CHANGES; i++) {
			final byte[] damaged = whole.clone();
			final byte[] with = wild[random.nextInt(wild.length)];
			final int at = random.nextInt(whole.length - with.length);
			System.arraycopy(with, 0, damaged, at, with.length);
			read(folder, damaged, "bytes from " + at + " set to " + Arrays.toString(with));
		}
	}

	@Test(timeout = 240000)
	public void aWorldStateWithAnyByteChangedStillAnswers () throws Exception {
		final byte[] whole = fixture("/MobileObjects.save");
		final File folder = EKUtils.createTempDir(PREFIX).get();
		final Random random = new Random(SEED + 3);

		for (int i = 0; i < WORLD_CHANGES; i++) {
			final byte[] damaged = whole.clone();
			final int at = random.nextInt(whole.length);
			damaged[at] = (byte) random.nextInt(256);
			read(folder, damaged, "byte " + at + " set to " + (damaged[at] & 0xff));
		}
	}

	@Test(timeout = 240000)
	public void aFileOfAnythingElseStillAnswers () throws Exception {
		final File folder = EKUtils.createTempDir(PREFIX).get();
		final Random random = new Random(SEED + 4);

		assertEquals(-1, read(folder, new byte[0], "an empty file"));
		read(folder, new byte[1 << 20], "a megabyte of zeros");

		final byte[] ones = new byte[1 << 20];
		Arrays.fill(ones, (byte) 0xff);
		read(folder, ones, "a megabyte of ones");

		for (int i = 0; i < 40; i++) {
			final byte[] noise = new byte[random.nextInt(1 << 16)];
			random.nextBytes(noise);
			read(folder, noise, "noise, round " + i);
		}

		// A count that is real and nothing after it that is.
		final byte[] whole = fixture("/VendorsTest/AR_0611_Artificer_Hall.lvl");
		for (int i = 0; i < 40; i++) {
			final byte[] headed = whole.clone();
			final int from = 64 + random.nextInt(whole.length / 2);
			final byte[] noise = new byte[whole.length - from];
			random.nextBytes(noise);
			System.arraycopy(noise, 0, headed, from, noise.length);
			read(folder, headed, "noise from byte " + from);
		}
	}
}
