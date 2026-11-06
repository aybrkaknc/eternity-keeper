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

package uk.me.mantas.eternity.tests;

import org.cef.handler.CefMessageRouterHandler;
import org.junit.Test;
import uk.me.mantas.eternity.JSHandlers;
import uk.me.mantas.eternity.handlers.SaveTarget;

import java.io.File;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * Every handler the page can call is registered, under the name the page uses.
 *
 * <p>JSHandlers declared twenty-six routers two different ways — some added to
 * the client on the next line, the rest collected in a block at the bottom —
 * so a router declared and never added was one missed line away, and the
 * symptom would have been a page call that silently never answers. The
 * registry is a table now, and this checks it against the classes that exist.
 */
public class JSHandlersTest extends TestHarness {
	/** The concrete handler classes compiled into the handlers package. */
	private TreeSet<String> handlerClasses () throws Exception {
		final File folder = new File(SaveTarget.class
			.getProtectionDomain().getCodeSource().getLocation().toURI());
		final File handlers = new File(folder, "uk/me/mantas/eternity/handlers");
		assertTrue("compiled handlers not found at " + handlers, handlers.isDirectory());

		final TreeSet<String> names = new TreeSet<>();
		for (final String file : handlers.list()) {
			if (!file.endsWith(".class") || file.contains("$")) {
				continue;
			}

			final Class<?> cls = Class.forName(
				"uk.me.mantas.eternity.handlers." + file.substring(0, file.length() - 6));

			if (CefMessageRouterHandler.class.isAssignableFrom(cls)
				&& !Modifier.isAbstract(cls.getModifiers())) {

				names.add(cls.getSimpleName());
			}
		}

		return names;
	}

	private static String pageName (final String className) {
		return Character.toLowerCase(className.charAt(0)) + className.substring(1);
	}

	@Test
	public void everyHandlerClassIsRegistered () throws Exception {
		final Map<String, CefMessageRouterHandler> registered = JSHandlers.handlers(null);

		final TreeSet<String> missing = new TreeSet<>();
		for (final String name : handlerClasses()) {
			if (!registered.containsKey(pageName(name))) {
				missing.add(name);
			}
		}

		assertTrue("handlers the page cannot reach: " + missing, missing.isEmpty());
	}

	@Test
	public void eachIsRegisteredUnderItsOwnClassesName () {
		for (final Map.Entry<String, CefMessageRouterHandler> entry
			: JSHandlers.handlers(null).entrySet()) {

			assertEquals(pageName(entry.getValue().getClass().getSimpleName()), entry.getKey());
		}
	}

	@Test
	public void nothingIsRegisteredThatIsNotAHandlerClass () throws Exception {
		final TreeSet<String> classes = new TreeSet<>();
		handlerClasses().forEach(name -> classes.add(pageName(name)));

		assertEquals(classes, new TreeSet<>(JSHandlers.handlers(null).keySet()));
	}
}
