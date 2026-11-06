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

package uk.me.mantas.eternity.handlers;

import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefDialogHandler.FileDialogMode;
import uk.me.mantas.eternity.EKUtils;
import uk.me.mantas.eternity.environment.Environment;

import java.util.Vector;
import java.util.function.Consumer;

/**
 * Choosing a character or loadout file, which importing and exporting both
 * start with.
 *
 * <p>The dialog is CEF's own, opened from the worker pool so it never holds up
 * the mutation queue; what is done with the chosen file runs on that queue
 * (invariant 9), since it reads or writes against the working save.
 */
public final class ChrDialog {
	private ChrDialog () {}

	/**
	 * A {@code .chr} file.
	 *
	 * @param cancelled what the client is told when no file was chosen
	 * @param chosen    given the chosen path, on the mutation worker
	 */
	public static void choose (
		final CefBrowser browser
		, final FileDialogMode mode
		, final String title
		, final CefQueryCallback callback
		, final String cancelled
		, final Consumer<String> chosen) {

		choose(browser, mode, title, "chr", "", callback, cancelled, chosen);
	}

	/**
	 * A file of the given extension.
	 *
	 * @param suggested the file name a save dialog starts with, or empty
	 */
	public static void choose (
		final CefBrowser browser
		, final FileDialogMode mode
		, final String title
		, final String extension
		, final String suggested
		, final CefQueryCallback callback
		, final String cancelled
		, final Consumer<String> chosen) {

		final Vector<String> filters = new Vector<>();
		filters.add("." + extension);

		Environment.getInstance().workers().execute(Answered.to(callback, () -> browser.runFileDialog(
			mode, title, suggested, filters, 0, (selectedFilter, files) -> {
				if (files == null || files.isEmpty() || files.get(0).isEmpty()) {
					callback.failure(-1, cancelled);
					return;
				}

				final String path = files.get(0);
				Environment.getInstance().mutationWorker().execute(
					Answered.to(callback, () -> chosen.accept(path)));
			})));
	}

	/** The name typed into a save dialog, with {@code .chr} on the end unless it has it. */
	public static String withChrExtension (final String filename) {
		return withExtension(filename, "chr");
	}

	/** The name typed into a save dialog, with {@code extension} on the end unless it has it. */
	public static String withExtension (final String filename, final String extension) {
		return EKUtils.getExtension(filename).filter(extension::equalsIgnoreCase).isPresent()
			? filename : filename + "." + extension;
	}
}
