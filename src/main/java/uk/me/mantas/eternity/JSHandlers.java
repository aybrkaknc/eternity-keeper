/**
 * Eternity Keeper, a Pillars of Eternity save game editor.
 * Copyright (C) 2015 the authors.
 * <p>
 * Eternity Keeper is free software: you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * Eternity Keeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.me.mantas.eternity;

import org.cef.CefClient;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.CefMessageRouter.CefMessageRouterConfig;
import org.cef.handler.CefMessageRouterHandler;
import uk.me.mantas.eternity.handlers.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the page can call. Each entry becomes {@code window.<name>(...)} in the
 * page, with {@code <name>Cancel} to abandon it.
 */
public class JSHandlers {
	private JSHandlers () {
	}

	/**
	 * Every handler, by the name the page calls it. A table rather than a
	 * create-then-add pair per handler: the old file declared them two ways,
	 * and a router declared but never added would have been a page call that
	 * silently never answers. {@code JSHandlersTest} checks this against the
	 * handler classes that exist.
	 */
	public static Map<String, CefMessageRouterHandler> handlers (final EternityKeeper frame) {
		final Map<String, CefMessageRouterHandler> handlers = new LinkedHashMap<>();

		// The save list.
		handlers.put("getDefaultSaveLocation", new GetDefaultSaveLocation());
		handlers.put("listSavedGames", new ListSavedGames());
		handlers.put("checkExtractionProgress", new CheckExtractionProgress());
		handlers.put("openSavedGame", new OpenSavedGame());
		handlers.put("renameSavedGame", new RenameSavedGame());
		handlers.put("deleteSavedGame", new DeleteSavedGame());
		handlers.put("convertSave", new ConvertSave());
		handlers.put("compareSaves", new CompareSaves());
		handlers.put("backups", new Backups());

		// Writing.
		handlers.put("saveChanges", new SaveChanges());
		handlers.put("saveTarget", new SaveTarget());
		handlers.put("saveSettings", new SaveSettings());

		// Editing the working save; each hands the reopened save back.
		handlers.put("updateParty", new UpdateParty());
		handlers.put("updateInventory", new UpdateInventory());
		handlers.put("updateAbilities", new UpdateAbilities());
		handlers.put("updateStronghold", new UpdateStronghold());
		handlers.put("updateGrimoires", new UpdateGrimoires());
		handlers.put("updateVendors", new UpdateVendors());
		handlers.put("resurrectCharacter", new ResurrectCharacter());
		handlers.put("enableAchievements", new EnableAchievements());

		// Read-only: the vendors in the working save's area files.
		handlers.put("getVendors", new GetVendors());

		// Characters in and out of .chr files.
		handlers.put("exportCharacter", new ExportCharacter());
		handlers.put("importCharacter", new ImportCharacter());
		handlers.put("exportLoadout", new ExportLoadout());
		handlers.put("readLoadout", new ReadLoadout());
		handlers.put("applyLoadout", new ApplyLoadout());

		// Undo and Redo for what the Applies above wrote.
		handlers.put("changeHistory", new ChangeHistory());

		// Read-only lookups into the game's own data.
		handlers.put("getGameStructures", new GetGameStructures());
		handlers.put("getIdentityEffects", new GetIdentityEffects());
		handlers.put("getLanguages", new GetLanguages());
		handlers.put("browseItems", new BrowseItems());
		handlers.put("browseAbilities", new BrowseAbilities());
		handlers.put("browsePortraits", new BrowsePortraits());
		handlers.put("gameData", new GameData());

		handlers.put("closeWindow", new CloseWindow(frame));
		return handlers;
	}

	public static void register (final CefClient cefClient, final EternityKeeper frame) {
		// Guarded: whatever becomes of a handler's work, its query is answered,
		// and answered once.
		handlers(frame).forEach((name, handler) -> cefClient.addMessageRouter(
			CefMessageRouter.create(new CefMessageRouterConfig(name, name + "Cancel")
				, Answered.guarded(handler))));
	}
}
