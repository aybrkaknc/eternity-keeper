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

import org.junit.Test;
import uk.me.mantas.eternity.save.QuestTrackerBlob;
import uk.me.mantas.eternity.save.QuestTrackerBlob.Tracker;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Fixtures are real QuestTrackers blobs lifted from paired saves of the same
 * playthrough: one taken with the companion's quest active, one taken just
 * after the companion died (which fails the quest). The pairs establish the
 * exact fail-transition: EndState -1 -> N and Failed false -> true on the
 * companion quest's tracker — and, only for Durance, a TriggeredEvents bit
 * for his "Durance Died" quest event.
 */
public class QuestTrackerBlobTest {
	private static final String EDER_QUEST = "data/quests/companions/companion_qst_eder.quest";
	private static final String DURANCE_QUEST =
		"data/quests/companions/companion_qst_durance.quest";

	private byte[] fixture (final String name) throws IOException {
		try (final InputStream in =
			getClass().getResourceAsStream("/QuestTrackerBlobTest/" + name)) {

			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[8192];
			int read;
			while ((read = in.read(buffer)) > -1) {
				out.write(buffer, 0, read);
			}

			return out.toByteArray();
		}
	}

	@Test
	public void readsFailedTrackerState () throws IOException {
		final Optional<QuestTrackerBlob> blob = QuestTrackerBlob.parse(fixture("eder-failed.bin"));
		assertTrue(blob.isPresent());

		final Optional<Tracker> tracker = blob.get().tracker(EDER_QUEST);
		assertTrue(tracker.isPresent());
		assertEquals(1, tracker.get().endState());
		assertTrue(tracker.get().failed());
	}

	// Comparing two saves walks every quest the party has met, so the blob
	// has to say which those are: its dictionary's keys, each the quest's file.
	@Test
	public void listsEveryQuestItTracks () throws IOException {
		final QuestTrackerBlob blob = QuestTrackerBlob.parse(fixture("eder-alive.bin")).get();
		final java.util.List<String> quests = blob.quests();

		assertTrue(quests.contains(EDER_QUEST));
		assertTrue(quests.size() > 1);
		for (final String quest : quests) {
			assertTrue(quest, quest.startsWith("data/quests/") && quest.endsWith(".quest"));
			assertTrue(quest, blob.tracker(quest).isPresent());
		}

		assertEquals(quests, QuestTrackerBlob.parse(fixture("eder-failed.bin")).get().quests());
	}

	// How far a quest has got, as the count of its events that have fired:
	// Edér's death fires none of his quest's, so the count is the same.
	@Test
	public void countsTheEventsThatHaveFired () throws IOException {
		final Tracker alive = QuestTrackerBlob.parse(fixture("eder-alive.bin")).get().tracker(EDER_QUEST).get();
		final Tracker failed = QuestTrackerBlob.parse(fixture("eder-failed.bin")).get().tracker(EDER_QUEST).get();

		int fired = 0;
		for (int event = 0; event < 64; event++) {
			if (alive.triggeredEvent(event)) {
				fired++;
			}
		}

		assertEquals(fired, alive.eventsFired());
		assertEquals(alive.eventsFired(), failed.eventsFired());
	}

	@Test
	public void readsActiveTrackerState () throws IOException {
		final QuestTrackerBlob blob = QuestTrackerBlob.parse(fixture("eder-alive.bin")).get();
		final Tracker tracker = blob.tracker(EDER_QUEST).get();
		assertEquals(-1, tracker.endState());
		assertFalse(tracker.failed());
	}

	@Test
	public void unknownQuestIsAbsent () throws IOException {
		final QuestTrackerBlob blob = QuestTrackerBlob.parse(fixture("eder-failed.bin")).get();
		assertFalse(blob.tracker("data/quests/no_such_quest.quest").isPresent());
	}

	@Test
	public void garbageIsRejected () {
		assertFalse(QuestTrackerBlob.parse(new byte[]{1, 2, 3, 4, 5}).isPresent());
		assertFalse(QuestTrackerBlob.parse(new byte[0]).isPresent());
	}

	// The strongest possible guarantee: un-failing the quest in the post-death
	// blob reproduces the pre-death blob byte for byte.
	@Test
	public void patchingFailedBlobReproducesAliveBlobExactly () throws IOException {
		final byte[] failed = fixture("eder-failed.bin");
		final QuestTrackerBlob blob = QuestTrackerBlob.parse(failed).get();
		final Tracker tracker = blob.tracker(EDER_QUEST).get();

		tracker.setEndState(-1);
		tracker.setFailed(false);

		assertArrayEquals(fixture("eder-alive.bin"), failed);
	}

	@Test
	public void readsAndClearsTriggeredEvents () throws IOException {
		final byte[] failed = fixture("durance-failed.bin");
		final QuestTrackerBlob blob = QuestTrackerBlob.parse(failed).get();
		final Tracker tracker = blob.tracker(DURANCE_QUEST).get();

		// Death triggered event 7 ("Durance Died"); event 0 ("Joined") had
		// already fired in normal play.
		assertTrue(tracker.triggeredEvent(0));
		assertTrue(tracker.triggeredEvent(7));
		assertEquals(1, tracker.endState());
		assertTrue(tracker.failed());

		tracker.setEndState(-1);
		tracker.setFailed(false);
		tracker.clearTriggeredEvent(7);

		assertFalse(tracker.triggeredEvent(7));
		assertTrue(tracker.triggeredEvent(0));

		// The patched blob matches the pre-death blob except for one BitArray
		// _version counter byte, which the death write bumped and which the
		// game treats as runtime-only bookkeeping.
		final byte[] alive = fixture("durance-alive.bin");
		int differing = 0;
		for (int i = 0; i < alive.length; i++) {
			if (alive[i] != failed[i]) {
				differing++;
			}
		}

		assertEquals(1, differing);
	}
}
