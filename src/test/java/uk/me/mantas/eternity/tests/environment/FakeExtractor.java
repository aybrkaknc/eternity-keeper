/**
 *  Eternity Keeper, a Pillars of Eternity save game editor.
 *  Copyright (C) 2016 the authors.
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

package uk.me.mantas.eternity.tests.environment;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * Stands in for {@code tools/gamedata/extract_gamedata.py}: speaks the same
 * stdout protocol, so the editor's side can be tested without Python, UnityPy
 * or a game install. The first argument picks how it behaves; the rest are the
 * real extractor's {@code --game} and {@code --out}.
 */
public class FakeExtractor {
	public static void main (final String[] args) throws Exception {
		final String mode = args[0];
		final String game = args[2];
		final File out = new File(args[4]);

		switch (mode) {
			case "succeed":
				System.out.println("PROGRESS 0 Reading item and ability bundles");
				System.out.println("  250/4604  (97 catalogued)");
				System.out.println("PROGRESS 42 Reading item and ability bundles (1933 of 4604)");
				write(new File(out, "catalog.json"), "{}");
				write(new File(out, "gamedata.json")
					, "{\"format\":1,\"game\":\"" + game.replace("\\", "\\\\")
						+ "\",\"items\":2156,\"written\":\"2026-09-19T10:00:00\"}");
				System.out.println("PROGRESS 100 Done");
				System.out.println("DONE " + out.getAbsolutePath());
				break;

			case "refuse":
				System.out.println("ERROR " + game + " is not a Pillars of Eternity install.");
				System.exit(2);
				break;

			case "crash":
				System.out.println("PROGRESS 12 Reading item and ability bundles");
				System.err.println("Traceback (most recent call last):");
				System.err.println("MemoryError");
				System.exit(3);
				break;

			case "hang":
				// Part of the way through, as the real one is when it is
				// stopped: what it has read so far is in "<out>.new".
				write(new File(out.getPath() + ".new", "catalog.json"), "{\"half\":true}");
				System.out.println("PROGRESS 5 Reading item and ability bundles");
				Thread.sleep(60000);
				break;

			default:
				throw new IllegalArgumentException(mode);
		}
	}

	private static void write (final File file, final String contents) throws IOException {
		file.getParentFile().mkdirs();
		try (final Writer writer =
			new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {

			writer.write(contents);
		}
	}
}
