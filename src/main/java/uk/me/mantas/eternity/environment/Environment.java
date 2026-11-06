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


package uk.me.mantas.eternity.environment;

import org.cef.OS;
import uk.me.mantas.eternity.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Environment {
	private static final Logger logger = Logger.getLogger(Environment.class);
	private static Environment instance = null;
	private static final long SHUTDOWN_TIMEOUT_SECONDS = 20;
	private ExecutorService workers =
		Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

	// All operations that modify (or copy) an extracted save run here, one at
	// a time. Letting them overlap on the parallel pool corrupted copies when
	// e.g. a save was packaged while a party update was still writing
	// MobileObjects.save.
	private ExecutorService mutationWorker = Executors.newSingleThreadExecutor();

	private final Factories factories = new Factories();
	public Factories factory () { return factories; }

	private final Directories directories = new Directories();
	public Directories directory () { return directories; }

	private final State state = new State();
	public State state () { return state; }

	private final Configuration configuration = new Configuration();
	public Configuration config () { return configuration; }

	private final Variables variables = new Variables();
	public Variables variables () { return variables; }

	private final ClassFinder classFinder = new ClassFinder();
	public ClassFinder classFinder () { return classFinder; }

	private Environment () {}

	public static Environment getInstance () {
		if (instance == null) {
			initialise();
		}

		return instance;
	}

	public static void initialise () {
		if (instance != null) {
			joinAllWorkers();
		}

		instance = new Environment();
	}

	public ExecutorService workers () {
		return workers;
	}

	public ExecutorService mutationWorker () {
		return mutationWorker;
	}

	public boolean isWindows () {
		// This method just exists so we can mock it in tests.
		return OS.isWindows();
	}

	public static void joinAllWorkers () {
		shutdownPool(getInstance().workers());
		shutdownPool(getInstance().mutationWorker());

		// Nothing is editing any more; don't leave an unsaved copy in temp.
		getInstance().state().workingSave().opening();
	}

	private static void shutdownPool (final ExecutorService pool) {
		pool.shutdown();

		try {
			if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				pool.shutdownNow();
				if (!pool.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
					logger.error("Thread pool did not terminate!%n");
				}
			}
		} catch (final InterruptedException e) {
			pool.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
