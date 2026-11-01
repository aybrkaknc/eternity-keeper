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


package uk.me.mantas.eternity;

import org.apache.commons.io.FileUtils;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.OS;
import org.cef.browser.CefBrowser;
import org.cef.handler.CefAppHandlerAdapter;
import org.json.JSONObject;
import uk.me.mantas.eternity.environment.AppPaths;
import uk.me.mantas.eternity.environment.Environment;
import uk.me.mantas.eternity.environment.TempSweep;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class EternityKeeper extends JFrame {
	private static final Color OBSIDIAN_BG = new Color(0x1b, 0x16, 0x11);
	private final CefApp cefApp;
	private final CefClient cefClient;
	private final CefBrowser browser;

	private EternityKeeper (final File ui) {
		setBackground(OBSIDIAN_BG);
		getContentPane().setBackground(OBSIDIAN_BG);

		CefApp.addAppHandler(new CefAppHandlerAdapter(null) {
			@Override
			public void stateHasChanged (final CefAppState state) {
				if (state == CefAppState.TERMINATED) {
					shutdown();
				}
			}
		});

		final AppPaths paths = Environment.getInstance().directory().paths();
		final CefSettings settings = new CefSettings();
		settings.windowless_rendering_enabled = OS.isLinux();
		settings.remote_debugging_port = paths.remoteDebuggingPort();
		settings.log_file = paths.cefLogFile().getAbsolutePath();

		cefApp = CefApp.getInstance(settings);
		cefClient = cefApp.createClient();
		JSHandlers.register(cefClient, this);

		// An absolute file: URL, so the UI loads whichever folder the app was
		// started from (a shortcut's "Start in", an install under Program Files).
		final String index = new File(ui, "index.html").toPath().toUri().toString();
		browser = cefClient.createBrowser(index, OS.isLinux(), false);
		setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

		final Component uiComponent = browser.getUIComponent();
		if (uiComponent != null) {
			uiComponent.setBackground(OBSIDIAN_BG);
		}
		getContentPane().add(uiComponent, BorderLayout.CENTER);
		pack();

		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing (final WindowEvent e) {
				if (Environment.getInstance().state().closing) {
					cefApp.dispose();
					dispose();
				} else {
					browser.executeJavaScript("checkForModifications();", browser.getURL(), 0);
				}
			}
		});
	}

	private void shutdown () {
		saveWindowState();
		Environment.getInstance().directory().deleteWorking();
		Environment.joinAllWorkers();
		System.exit(0);
	}

	private static void rolloverLogFile (final File log) {
		final long maxSize = Environment.getInstance().config().maxLogSize();
		if (!log.exists() || !log.isFile() || log.length() < maxSize) {
			return;
		}

		// We want to delete the first half of the file's lines.
		final long halfMax = maxSize / 2;
		final List<String> storedLines = new ArrayList<>();
		String line;
		long currentByteCount = 0L;

		try (final BufferedReader reader =
			new BufferedReader(new InputStreamReader(new FileInputStream(log), "UTF-8"))) {

			while ((line = reader.readLine()) != null) {
				currentByteCount += line.length();
				if (currentByteCount >= halfMax) {
					storedLines.add(line);
				}
			}

			FileUtils.writeLines(log, "UTF-8", storedLines, "\n");
		} catch (final IOException e) {
			// We obviously don't have access to the log file to write to here so the best we can
			// do is to output to the console.
			System.err.printf("Error rolling over log file: %s%n", e.getMessage());
		}
	}

	private void saveWindowState () {
		final Rectangle windowBounds = getBounds();
		final JSONObject settings = Settings.getInstance().json;

		settings.put("width", windowBounds.width);
		settings.put("height", windowBounds.height);
		settings.put("x", windowBounds.x);
		settings.put("y", windowBounds.y);
		Settings.getInstance().save();
	}

	// Started through Launcher, which settles what AWT reads as it loads.
	public static void main (final String[] args) {
		Locale.setDefault(Locale.UK);
		final ImageIcon icon = new ImageIcon(EternityKeeper.class.getResource("/icon.png"));

		// We set up various environment properties and dependency injections
		// here in order to make it easier to test classes later.
		Environment.initialise();
		final AppPaths paths = Environment.getInstance().directory().paths();
		try {
			paths.adoptLegacySettings();
		} catch (final IOException e) {
			System.err.printf("Could not copy the old settings.json: %s%n", e.getMessage());
		}

		Settings.initialise();
		rolloverLogFile(paths.logFile());

		// A copy of a save for every editor that crashed or was killed.
		TempSweep.inBackground(Environment.getInstance().directory().working());

		final Optional<File> ui = paths.ui();
		if (!ui.isPresent()) {
			JOptionPane.showMessageDialog(null
				, "Eternity Keeper can't find its user interface files.\n\n"
					+ "Expected a \"ui\" folder beside the program in\n" + paths.home()
					+ "\n\nRe-extract the download, keeping every folder in it."
				, "Eternity Keeper", JOptionPane.ERROR_MESSAGE);
			System.exit(1);
		}

		final Rectangle windowBounds = EKUtils.getDefaultWindowBounds();
		final Frame frame = new EternityKeeper(ui.get());
		frame.setTitle("Eternity Keeper");
		frame.setBounds(windowBounds);
		frame.setVisible(true);
		frame.setIconImage(icon.getImage());
	}
}
