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
import org.junit.Test;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.game.ObjectPersistencePacket;
import uk.me.mantas.eternity.save.QuestRestorer;
import uk.me.mantas.eternity.save.QuestTrackerBlob;
import uk.me.mantas.eternity.save.QuestTrackerBlob.Tracker;
import uk.me.mantas.eternity.serializer.DeserializedPackets;
import uk.me.mantas.eternity.serializer.PacketDeserializer;
import uk.me.mantas.eternity.serializer.properties.ComplexProperty;
import uk.me.mantas.eternity.serializer.properties.DictionaryProperty;
import uk.me.mantas.eternity.serializer.properties.Property;
import uk.me.mantas.eternity.serializer.properties.SimpleProperty;
import uk.me.mantas.eternity.serializer.properties.SingleDimensionalArrayProperty;
import uk.me.mantas.eternity.tests.TestHarness;
import uk.me.mantas.eternity.save.model.SaveObject;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class QuestRestorerTest extends TestHarness {
	private static final String EDER_QUEST = "data/quests/companions/companion_qst_eder.quest";
	private static final String DURANCE_QUEST =
		"data/quests/companions/companion_qst_durance.quest";

	private DeserializedPackets fixturePackets () throws URISyntaxException, IOException {
		final Optional<File> workingDir = EKUtils.createTempDir(PREFIX);
		assertTrue(workingDir.isPresent());
		final File resources = new File(getClass().getResource("/").toURI());
		final File mobileObjects = new File(workingDir.get(), "MobileObjects.save");
		FileUtils.copyFile(new File(resources, "MobileObjects.save"), mobileObjects);

		final Optional<DeserializedPackets> deserialized =
			new PacketDeserializer(mobileObjects).deserialize();
		assertTrue(deserialized.isPresent());
		return deserialized.get();
	}

	private Property trackersProperty (final List<Property> packets) {
		final Optional<Property> property = QuestRestorer.findQuestTrackers(packets);
		assertTrue(property.isPresent());
		return property.get();
	}

	private byte[] blobBytes (final List<Property> packets) {
		final Byte[] boxed = (Byte[]) ((SimpleProperty) trackersProperty(packets)).value;
		final byte[] raw = new byte[boxed.length];
		for (int i = 0; i < raw.length; i++) {
			raw[i] = boxed[i];
		}

		return raw;
	}

	private void writeBlob (final List<Property> packets, final byte[] raw) {
		final Byte[] boxed = new Byte[raw.length];
		for (int i = 0; i < raw.length; i++) {
			boxed[i] = raw[i];
		}

		assertTrue(Property.update(trackersProperty(packets), boxed));
	}

	// Arranges a quest tracker state directly in the packets' blob.
	private void setTrackerState (
		final List<Property> packets
		, final String questPath
		, final int endState
		, final boolean failed
		, final int triggeredEventToSet) {

		final byte[] raw = blobBytes(packets);
		final Tracker tracker = QuestTrackerBlob.parse(raw).get().tracker(questPath).get();
		tracker.setEndState(endState);
		tracker.setFailed(failed);
		if (triggeredEventToSet >= 0) {
			tracker.setTriggeredEvent(triggeredEventToSet);
			assertTrue(tracker.triggeredEvent(triggeredEventToSet));
		}

		writeBlob(packets, raw);
	}

	private Optional<Property> globalEntry (final List<Property> packets, final String name) {
		for (final Property p : packets) {
			if (!(p.obj instanceof ObjectPersistencePacket)) continue;
			final ObjectPersistencePacket packet = (ObjectPersistencePacket) p.obj;
			if (packet.ObjectName == null || !packet.ObjectName.startsWith("InGameGlobal")) {
				continue;
			}

			final Optional<Property> entry = SaveObject.of(p)
				.flatMap(o -> o.component("GlobalVariables"))
				.flatMap(g -> g.variable("m_data"))
				.flatMap(d -> ((DictionaryProperty) d).findEntry(name));

			if (entry.isPresent()) {
				return entry;
			}
		}

		return Optional.empty();
	}

	private int globalValue (final List<Property> packets, final String name) {
		final Optional<Property> entry = globalEntry(packets, name);
		assertTrue("global " + name + " not found", entry.isPresent());
		return ((Number) ((SimpleProperty) entry.get()).value).intValue();
	}

	@Test
	public void restoresFailedCompanionQuest () throws URISyntaxException, IOException {
		final DeserializedPackets packets = fixturePackets();
		setTrackerState(packets.getPackets(), EDER_QUEST, 1, true, -1);

		assertTrue(QuestRestorer.restore(packets.getPackets(), EDER_QUEST));

		final Tracker after = QuestTrackerBlob.parse(blobBytes(packets.getPackets())).get()
			.tracker(EDER_QUEST).get();
		assertEquals(-1, after.endState());
		assertFalse(after.failed());
	}

	@Test
	public void leavesUnfailedQuestAlone () throws URISyntaxException, IOException {
		final DeserializedPackets packets = fixturePackets();
		// EndState set with Failed=false is a successfully completed quest.
		setTrackerState(packets.getPackets(), EDER_QUEST, 2, false, -1);

		assertTrue(QuestRestorer.restore(packets.getPackets(), EDER_QUEST));

		final Tracker after = QuestTrackerBlob.parse(blobBytes(packets.getPackets())).get()
			.tracker(EDER_QUEST).get();
		assertEquals(2, after.endState());
		assertFalse(after.failed());
	}

	@Test
	public void toleratesMissingTracker () throws URISyntaxException, IOException {
		final DeserializedPackets packets = fixturePackets();
		final byte[] before = blobBytes(packets.getPackets());

		assertTrue(QuestRestorer.restore(packets.getPackets(), "data/quests/no_such.quest"));

		assertArrayEquals(before, blobBytes(packets.getPackets()));
	}

	@Test
	public void restoresDuranceQuestStateGlobal () throws URISyntaxException, IOException {
		final DeserializedPackets packets = fixturePackets();
		// Death fails the quest, triggers quest event 7 ("Durance Died") and
		// sets nDuranceQuestState to its dedicated death value 8.
		setTrackerState(packets.getPackets(), DURANCE_QUEST, 1, true, 7);
		final Optional<Property> global = globalEntry(packets.getPackets(), "nDuranceQuestState");
		assertTrue(global.isPresent());
		assertTrue(Property.update(global.get(), 8));

		assertTrue(QuestRestorer.restore(packets.getPackets(), DURANCE_QUEST));

		final Tracker after = QuestTrackerBlob.parse(blobBytes(packets.getPackets())).get()
			.tracker(DURANCE_QUEST).get();
		assertEquals(-1, after.endState());
		assertFalse(after.failed());
		assertFalse(after.triggeredEvent(7));
		assertEquals(1, globalValue(packets.getPackets(), "nDuranceQuestState"));
	}

	@Test
	public void duranceGlobalLeftAloneWhenNotDeathValue () throws URISyntaxException, IOException {
		final DeserializedPackets packets = fixturePackets();
		setTrackerState(packets.getPackets(), DURANCE_QUEST, 1, true, 7);
		final Optional<Property> global = globalEntry(packets.getPackets(), "nDuranceQuestState");
		assertTrue(global.isPresent());
		assertTrue(Property.update(global.get(), 3));

		assertTrue(QuestRestorer.restore(packets.getPackets(), DURANCE_QUEST));

		// 3 is live progression state, not the death sentinel — hands off.
		assertEquals(3, globalValue(packets.getPackets(), "nDuranceQuestState"));
	}
}
