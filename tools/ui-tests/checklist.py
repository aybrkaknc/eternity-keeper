# The manual test-run checklist, item by item, for the items no other suite
# asserts in the checklist's own words. Every check is named after the item it
# answers ("sheet-skills: ..."), so a run can be read back against the list.
#
# What the other suites already pin is left to them: format_ui, backups_ui,
# find, loadout, vendors, compare, undo, sale, party_care, import_chr,
# quick_and_weapons, stronghold_people, layout_rules, responsive, damaged.
#
# Works on copies and on staged changes; the one Apply it makes per tab is
# thrown away with the save, and nothing is written to the saves folder but a
# throwaway copy that is removed at the end.
import json, os, shutil, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, boot, open_save

MID = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
LATER = "0945952c89c640e4a18cdb293e3946b4 4 SocewCieniu.savegame"
SOURCE = "cf88c16dc9564d77a49e89c8894d5c4e 1 CilantLs.savegame"
COPY = "cf88c16dc9564d77a49e89c8894d5c4e 515151 CilantLs.savegame"
D = "Eternity.SavedGame.state.saveData"
PLAYER = "%s.characters.filter(function(c){ return c.isMainCharacter; })[0]" % D

page = Page()
page.call("Page.enable")
E = page.eval
before_files = sorted(os.listdir(SAVES))


def view(name, wait=2.2):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)


def leave():
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
    time.sleep(0.6)


def search():
    E("window.__searched = false; Eternity.SaveSearch.search(); window.__searched = true")
    time.sleep(0.4)
    page.wait_for("window.__searched && !Eternity.SaveSearch.state.searching", 300, "the search")
    time.sleep(0.5)


def index_of(name):
    return E("""(function(n){ var hit = -1; Eternity.SaveSearch.state.saves.forEach(function(s, i){
        if ((s.absolutePath || '').indexOf(n) >= 0) hit = i; }); return hit; })(%s)""" % json.dumps(name))


def cmd(text):
    E("$('#consoleInput').val(%s); $('#consoleForm').trigger('submit')" % json.dumps(text))
    time.sleep(0.8)
    return E("$('#consoleOutput').children().slice(-3).map(function(){ return $(this).text(); }).get().join(' | ')")


def select_character(guid):
    E("Eternity.SavedGame.switchCharacter(%s)" % json.dumps(guid))
    time.sleep(1.2)


try:
    boot(page)

    # ==== start: the save list ================================================
    shutil.copyfile(os.path.join(SAVES, SOURCE), os.path.join(SAVES, COPY))
    search()
    tile = E("""(function(){ var t = $('#saveBlocks .save-info').first(); return {
        name: t.find('.name').text(), date: t.find('.date').text(),
        portraits: t.find('.portraits img').length,
        shot: (t.find('.screenshot img').attr('src') || '').length}; })()""")
    check("start-list: a tile shows the save's name, date, screenshot and party",
          " - " in tile["name"] and tile["date"] and tile["portraits"] > 0 and tile["shot"] > 100, tile)
    check("start-list: nothing is offered until a save is selected", not E("$('#saveActions').is(':visible')"))
    at = index_of(COPY)
    E("Eternity.SaveSearch.select(%d)" % at)
    time.sleep(0.5)
    offered = E("$('#saveActions button:visible').map(function(){ return $.trim($(this).text()); }).get()")
    check("start-list: selecting one offers Load, Rename, Compare, Format and Delete",
          all(any(word in label for label in offered) for word in ("Load", "Rename", "Compare", "Format", "Delete")),
          offered)

    E("Eternity.SaveSearch.renamePrompt()")
    time.sleep(0.9)
    E("$('#renameSaveInput').val('Checklist rename'); $('#renameSaveConfirm').click()")
    page.wait_for("!$('#renameSaveDialog').is(':visible') && !Eternity.SaveSearch.state.busy", 60, "the rename")
    time.sleep(0.6)
    shown = E("$('#saveBlocks .save-info').eq(%d).find('.name').text()" % at)
    check("start-rename: the tile shows the new name at once", "Checklist rename" in shown, shown)

    # ==== start-open ===========================================================
    started = time.time()
    count = open_save(page, MID)
    took = time.time() - started
    check("start-open: the mid-game save opens in a few seconds", count > 1 and took < 15, "%d characters, %.1fs" % (count, took))
    check("start-open: the player's sheet is showing", E("$('#character').is(':visible')")
          and E("$('#characterList li').length") == count, E("$('#characterList li').length"))
    check("start-open: no validation strip", not E("$('#saveWarning').is(':visible')"))
    player = E("%s.GUID" % PLAYER)

    # ==== sheet ================================================================
    select_character(player)
    might = int(E("%s.stats.BaseMight.value" % PLAYER))
    sheet_before = E("$('#identitySheet').text()")
    E("""(function(v){ var i = $('#character .stats input[data-fullkey="BaseMight"]');
         i.val(v).trigger('change').trigger('keyup'); })(%d)""" % (might + 2))
    time.sleep(0.8)
    sheet_after = E("$('#identitySheet').text()")
    check("sheet-attr: Might +2 is in the model", int(E("%s.stats.BaseMight.value" % PLAYER)) == might + 2)
    check("sheet-attr: the sheet shows the working with the new base",
          ("%d base" % (might + 2)) in sheet_after and sheet_after != sheet_before, sheet_after[:160])
    check("sheet-attr: the panel bar says there is an unconfirmed change",
          "1" in E("$('#charPanelNote').text()") and not E("$('#charPanelRevert').prop('disabled')"),
          E("$('#charPanelNote').text()"))

    E("$('#charPanelRevert').click()")
    time.sleep(0.8)
    check("sheet-revert: Revert puts Might back", int(E("%s.stats.BaseMight.value" % PLAYER)) == might)
    E("""(function(v){ var i = $('#character .stats input[data-fullkey="BaseMight"]');
         i.val(v).trigger('change').trigger('keyup'); })(%d)""" % (might + 2))
    time.sleep(0.5)
    E("$('#charPanelApply').click()")
    time.sleep(0.8)
    check("sheet-revert: Apply keeps the value and clears the note",
          int(E("%s.stats.BaseMight.value" % PLAYER)) == might + 2 and E("$('#charPanelRevert').prop('disabled')"),
          E("$('#charPanelNote').text()"))
    check("sheet-revert: Save stays enabled, since nothing is written until Save",
          not E("$('#saveButton').prop('disabled')"))

    companion = E("""%s.characters.filter(function(c){
        return c.isCompanion && !c.isDead && !c.resurrectable; })[0]""" % D)
    select_character(companion["GUID"])
    locked = E("""['BaseMight','BaseConstitution','BaseDexterity','BasePerception','BaseIntellect','BaseResolve']
        .filter(function(s){ return $('#character .stats input[data-fullkey="' + s + '"]').prop('disabled'); }).length""")
    check("sheet-lock: a companion's six base attributes cannot be edited", locked == 6,
          "%d of 6 locked for %s" % (locked, companion["name"]))
    view("RAW", 1.4)
    check("sheet-lock: nor in the raw table", E("$('#rawTable .raw-locked').length") >= 6,
          E("$('#rawTable .raw-locked').length"))
    view("ATTR", 1.4)

    select_character(player)
    E("$('#skillsGrid .skill-rank').first().val(4).trigger('change')")
    time.sleep(0.6)
    check("sheet-skills: rank 4 reads 10 pts", E("$('#skillsGrid .skill-points').first().text()").startswith("10 pts"),
          E("$('#skillsGrid .skill-points').first().text()"))

    races = E("""(function(){ var race = $('#identityGrid select[data-stat="CharacterRace"]');
        var pick = race.find('option').filter(function(){ return $(this).text().indexOf('Dwarf') === 0; }).first();
        if (!pick.length) return {error: race.length + ' race selects'};
        var was = race.val(); race.val(pick.val()).trigger('change');
        var sub = $('#identityGrid select[data-stat="CharacterSubrace"] option').map(function(){ return $(this).text(); }).get();
        var note = race.closest('.identity-field, .id-field, div').text();
        race = $('#identityGrid select[data-stat="CharacterRace"]'); race.val(was).trigger('change');
        return {subraces: sub, note: note.slice(0, 200)}; })()""")
    check("sheet-identity: a dwarf is offered only dwarven subraces",
          races.get("subraces") and all("Dwarf" in s or "unusual" in s for s in races["subraces"]), races)
    check("sheet-identity: the line under Race lists what it is worth",
          "Might" in races.get("note", "") or "+" in races.get("note", ""), races.get("note"))

    E("""(function(){ var i = $('#character .stats input[data-fullkey="OverrideName"]');
         i.val('Checklist Name').trigger('input').trigger('change').trigger('keyup'); })()""")
    time.sleep(0.8)
    listed = E("$('#characterList li').map(function(){ return $(this).text().trim(); }).get()")
    check("sheet-name: the sidebar follows the name box", any("Checklist Name" in n for n in listed), listed[:3])

    face_before = E("$('.character .portrait').css('background-image')")
    E("$('.character .portrait .portrait-btn').click()")
    page.wait_for("$('#portraitGrid .ptr-tile').length > 0", 60, "the portrait picker")
    time.sleep(0.8)
    all_tiles = E("$('#portraitGrid .ptr-tile').length")
    E("$('#portraitSearch').val('aloth').trigger('keyup').trigger('input')")
    time.sleep(1.5)
    found = E("$('#portraitGrid .ptr-tile').length")
    check("sheet-portrait: the search box narrows the grid", 0 < found < all_tiles, "%s of %s" % (found, all_tiles))
    E("$('#portraitGrid .ptr-tile').first().click()")
    page.wait_for("!$('#portraitDialog').is(':visible')", 60, "the picker to close")
    time.sleep(1.0)
    check("sheet-portrait: the picked face appears on the sheet",
          E("$('.character .portrait').css('background-image')") != face_before
          and "aloth" in E("%s.portraitPaths.m_textureLargePath.value" % PLAYER).lower(),
          E("%s.portraitPaths.m_textureLargePath.value" % PLAYER))

    # ==== con: the console and the Save data menu ===============================
    view("CONSOLE", 1.6)
    said = cmd("help")
    check("con-help: help lists the commands", "giveplayermoney" in E("$('#consoleOutput').text()").lower(), said[:120])
    said = cmd("NoSuchCommand")
    check("con-help: a bad command gets a clear error", "Unknown command" in said and "help" in said, said)
    money = float(E("%s.currency" % D))
    said = cmd("GivePlayerMoney 1000")
    check("con-money: GivePlayerMoney says what it changed and moves the purse",
          abs(float(E("%s.currency" % D)) - money - 1000) < 0.5 and "1" in said, said)
    xp = float(E("%s.stats.Experience.value" % PLAYER))
    said = cmd("AddExperiencePlayer 500")
    check("con-money: AddExperiencePlayer adds to the player's experience",
          abs(float(E("%s.stats.Experience.value" % PLAYER)) - xp - 500) < 0.5, said)
    said = cmd("AttributeScore player might 20")
    check("con-stats: AttributeScore sets the base", int(E("%s.stats.BaseMight.value" % PLAYER)) == 20, said)
    said = cmd("Skill player athletics 5")
    check("con-stats: Skill sets the points for the rank", int(E("%s.stats.AthleticsSkill.value" % PLAYER)) == 15, said)
    name = E("Object.keys(%s.globals.InGameGlobal.GlobalVariables).filter(function(k){ return /^b_/.test(k); })[0]" % D)
    cmd("SetGlobalValue %s 1" % name)
    said = cmd("PrintGlobal %s" % name)
    check("con-globals: PrintGlobal shows what SetGlobalValue set", "1" in said and name in said, said)
    prestige = int(E("%s.globals.InGameGlobal.Stronghold.Prestige.value" % D))
    said = cmd("AdjustPrestige 2")
    check("con-globals: AdjustPrestige moves the stronghold's Prestige",
          int(E("%s.globals.InGameGlobal.Stronghold.Prestige.value" % D)) == prestige + 2, said)
    view("ATTR", 1.2)
    check("con-stats: the sheet shows Might 20 base and Athletics rank 5",
          "20 base" in E("$('#identitySheet').text()")
          and E("""$('#skillsGrid .skill-row').filter(function(){
                return $(this).find('.skill-name').text() === 'Athletics'; }).find('.skill-rank').val()""") == "5",
          E("$('#skillsGrid').text()")[:120])

    E("$('#menuCurrencyEditor').click()")
    time.sleep(1.0)
    shown_money = E("$('#currency').val()")
    check("con-dialogs: the currency dialog opens with the purse as it is",
          E("$('#currencyDialog').is(':visible')") and abs(float(shown_money) - (money + 1000)) < 1, shown_money)
    E("$('#currency').val(%d); $('#currencyEditorSet').click()" % int(money + 1234))
    time.sleep(0.8)
    check("con-dialogs: setting it changes the purse and arms Save",
          abs(float(E("%s.currency" % D)) - (money + 1234)) < 1 and E("Eternity.Modifications.state.modifications"))
    E("$('#currencyDialog').modal('hide')")
    time.sleep(0.6)
    E("$('#menuDifficultyEditor').click()")
    time.sleep(1.0)
    current = E("String(%s.globals.Global.GameState.Difficulty.value)" % D)
    active = E("$('#difficultyRow .console-option.active .console-option-label').text()")
    check("con-dialogs: the difficulty dialog opens on the save's difficulty",
          E("$('#difficultyDialog').is(':visible')") and len(active) > 0, "%s shown for %s" % (active, current))
    E("$('#difficultyRow .console-option').not('.active').not('.unavailable').first().click()")
    time.sleep(0.6)
    check("con-dialogs: picking another one changes it",
          E("String(%s.globals.Global.GameState.Difficulty.value)" % D) != current,
          E("String(%s.globals.Global.GameState.Difficulty.value)" % D))
    E("$('#difficultyDialog').modal('hide')")
    time.sleep(0.6)

    view("CONSOLE", 1.4)
    flag = E("!!%s.achievementsDisabled" % D)
    label = E("$.trim($('#achievementsToggle').text())")
    E("$('#achievementsToggle').click()")
    page.wait_for("$.trim($('#achievementsToggle').text()).indexOf('Working') < 0"
                  " && $.trim($('#achievementsToggle').text()) !== %s" % json.dumps(label), 120, "the toggle")
    time.sleep(0.6)
    check("con-ach: the Achievements button flips the flag",
          E("!!%s.achievementsDisabled" % D) != flag and E("$.trim($('#achievementsToggle').text())") != label,
          "%s -> %s" % (label, E("$.trim($('#achievementsToggle').text())")))

    # ==== save: prompt and discard ================================================
    E("$('#menuOpen').click()")
    time.sleep(1.0)
    check("save-prompt: leaving with changes asks whether to save or discard",
          E("$('#saveChangesDialog').is(':visible')") and E("$('#dontSaveChanges').is(':visible')")
          and E("$('#saveChanges').is(':visible')"))
    E("$('#dontSaveChanges').click()")
    page.wait_for("$('#saveBlocks .save-info').length > 0 && !Eternity.state.saveView", 60, "the save list")
    time.sleep(0.8)
    open_save(page, MID)
    time.sleep(1.0)
    check("save-discard: the discarded edits are not there on reopening",
          int(E("%s.stats.BaseMight.value" % PLAYER)) == might
          and abs(float(E("%s.currency" % D)) - money) < 0.5
          and E("!!%s.achievementsDisabled" % D) == flag,
          "Might %s, money %s, achievements flag %s" % (E("%s.stats.BaseMight.value" % PLAYER),
                                                       E("%s.currency" % D), E("!!%s.achievementsDisabled" % D)))

    # ==== inv ====================================================================
    view("INVENTORY", 3.0)
    pets = E("""%s.inventory.characters.map(function(c){
        return {player: !!c.isPlayer, noPet: (c.unavailableSlots || []).indexOf('Pet') >= 0}; })""" % D)
    check("inv-layout: only the main character has a pet slot",
          all(p["noPet"] != p["player"] for p in pets), pets[:4])

    first = E("($('#invPacks .inv-pack-row:eq(0) .inv-tile:not(.inv-tile-empty)').first().attr('title') || '').split('\\n')[0]")
    E("$('#invPacks .inv-pack-row:eq(0) .inv-tile:not(.inv-tile-empty)').first().click()")
    time.sleep(0.5)
    check("inv-move: the item picked up is shown as carried", first.split(" ×")[0] in E("$('#invCarry').text()")
          or E("$('#invCarry .inv-tile').length") > 0, E("$('#invCarry').text()")[:80])
    E("$(document).trigger($.Event('keyup', {which: 27, keyCode: 27}))")
    time.sleep(0.5)
    check("inv-move: Escape puts it back", not E("Eternity.InventoryEditor.unapplied()")
          and first == E("($('#invPacks .inv-pack-row:eq(0) .inv-tile:not(.inv-tile-empty)').first().attr('title') || '').split('\\n')[0]"))
    E("$('#invPacks .inv-pack-row:eq(0) .inv-tile:not(.inv-tile-empty)').first().click()")
    time.sleep(0.4)
    E("$('#invPacks .inv-pack-row:eq(1) .inv-tile.inv-tile-empty').first().click()")
    time.sleep(0.6)
    moved = E("$('#invPacks .inv-pack-row:eq(1) .inv-tile:not(.inv-tile-empty)').map(function(){"
              " return ($(this).attr('title') || '').split('\\n')[0]; }).get()")
    check("inv-move: dropped on another member's pack, it is theirs", first in moved and E("Eternity.InventoryEditor.unapplied()"),
          first)
    E("$('#invRevert').click()")
    time.sleep(1.0)
    check("inv-apply: Revert undoes what was staged", not E("Eternity.InventoryEditor.unapplied()"))

    total = E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').length")
    counts = []
    for n in range(E("$('#invStashFilters .inv-filter').length")):
        E("$('#invStashFilters .inv-filter').eq(%d).click()" % n)
        time.sleep(0.5)
        counts.append(E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').length"))
    check("inv-stash: each category shows its own part of the stash",
          len(counts) >= 6 and all(c <= total for c in counts) and len(set(counts)) > 2, "%s of %s" % (counts, total))
    # Back to the widest one, whichever button that is.
    E("$('#invStashFilters .inv-filter').eq(%d).click()" % counts.index(max(counts)))
    time.sleep(0.5)
    print("   stash filters: %s" % E("$('#invStashFilters .inv-filter').map(function(){"
                                     " return $(this).attr('title') || $.trim($(this).text()); }).get()"))
    E("$('#invStashSearch').val('potion').trigger('keyup')")
    time.sleep(0.6)
    titles = E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').map(function(){"
               " return ($(this).attr('title') || '').split('\\n')[0]; }).get()")
    check("inv-stash: the search shows only what matches", titles and all("potion" in t.lower() for t in titles),
          "%d tiles, e.g. %s" % (len(titles), titles[:2]))
    check("inv-stash: and the count follows",
          E("$('#invStashCount').text()") == "%d of %d items" % (len(titles), total), E("$('#invStashCount').text()"))

    stack = E("""(function(){ var hit = null; $('#invStashGrid .inv-tile:not(.inv-tile-empty)').each(function(i){
        var t = ($(this).attr('title') || '').split('\\n')[0]; var m = / ×(\\d+)$/.exec(t);
        if (!hit && m && parseInt(m[1], 10) >= 4) hit = {index: i, title: t}; }); return hit; })()""")
    name_of = stack["title"].split(" ×")[0]
    E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').eq(%d).trigger('dblclick')" % stack["index"])
    time.sleep(0.9)
    check("inv-stack: a double-click opens the quantity panel", E("$('#stackDialog').is(':visible')"), stack)
    E("$('#stackAmount').val(3); $('#stackAccept').click()")
    time.sleep(0.9)
    now = [t for t in E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').map(function(){"
                        " return ($(this).attr('title') || '').split('\\n')[0]; }).get()") if t.startswith(name_of)]
    check("inv-stack: set to 3, the stack shows 3", (name_of + " ×3") in now, now)
    at = E("""(function(n){ var hit = -1; $('#invStashGrid .inv-tile:not(.inv-tile-empty)').each(function(i){
        if (hit < 0 && ($(this).attr('title') || '').split('\\n')[0] === n) hit = i; }); return hit; })(%s)"""
           % json.dumps(name_of + " ×3"))
    E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').eq(%d).trigger('dblclick')" % at)
    time.sleep(0.9)
    E("$('#stackAmount').val(0); $('#stackAccept').click()")
    time.sleep(0.9)
    now = [t for t in E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').map(function(){"
                        " return ($(this).attr('title') || '').split('\\n')[0]; }).get()") if t == name_of + " ×3"]
    check("inv-stack: set to 0, the item is removed", not now, now)
    E("$('#invStashSearch').val('').trigger('keyup'); $('#invRevert').click()")
    time.sleep(1.0)

    # A ring from the stash: refused on the feet, worn on the hand, and back.
    E("$('#invStashSearch').val('ring').trigger('keyup')")
    time.sleep(0.6)
    ring = E("""(function(){ var hit = null; $('#invStashGrid .inv-tile:not(.inv-tile-empty)').each(function(i){
        var t = ($(this).attr('title') || '').split('\\n')[0];
        if (!hit && /^(Ring|Signet)|Ring of| Ring$/i.test(t)) hit = {index: i, title: t}; }); return hit; })()""")
    print("   ring: %s" % ring)
    E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').eq(%d).click()" % ring["index"])
    time.sleep(0.5)
    E("$('#invSlotsLeft .inv-tile').eq(3).click()")
    time.sleep(0.6)
    refused = E("$('#invStatus').text()")
    check("inv-equip: a ring is refused on the feet, in words", "cannot be worn in the feet slot" in refused, refused)
    E("$('#invSlotsLeft .inv-tile').eq(2).click()")
    time.sleep(0.6)
    worn = E("($('#invSlotsLeft .inv-tile').eq(2).attr('title') || '').split('\\n')[0]")
    check("inv-equip: dropped on a ring slot, it is worn", worn == ring["title"], worn)
    E("$(document).trigger($.Event('keyup', {which: 27, keyCode: 27}))")
    time.sleep(0.4)
    E("$('#invSlotsLeft .inv-tile').eq(2).click()")
    time.sleep(0.5)
    E("$('#invPacks .inv-pack-row .inv-tile.inv-tile-empty').first().click()")
    time.sleep(0.6)
    packs = E("$('#invPacks .inv-tile:not(.inv-tile-empty)').map(function(){"
              " return ($(this).attr('title') || '').split('\\n')[0]; }).get()")
    check("inv-equip: unequipped, it is back in a pack", ring["title"] in packs, ring["title"])
    E("$('#invStashSearch').val('').trigger('keyup'); $('#invRevert').click()")
    time.sleep(1.0)

    # Tidy: two stacks of one thing become one.
    E("$('#invBrowseTarget').val($('#invBrowseTarget option').filter(function(){"
      " return /stash/i.test($(this).text()); }).val()).trigger('change')")
    E("$('#invBrowseSearch').val('lockpick').trigger('keyup')")
    page.wait_for("$('#invBrowseGrid .inv-tile').length > 0", 60, "the item browser")
    time.sleep(0.8)
    E("$('#invBrowseGrid .inv-tile').first().click()")
    time.sleep(0.6)
    E("$('#invBrowseGrid .inv-tile').first().click()")
    time.sleep(0.6)
    E("$('#invBrowseSearch').val('').trigger('keyup')")
    E("$('#invStashSearch').val('lockpick').trigger('keyup')")
    time.sleep(0.8)
    stacks_before = E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').map(function(){"
                      " return ($(this).attr('title') || '').split('\\n')[0]; }).get()")
    E("$('#invTidy').click()")
    time.sleep(1.0)
    stacks_after = E("$('#invStashGrid .inv-tile:not(.inv-tile-empty)').map(function(){"
                     " return ($(this).attr('title') || '').split('\\n')[0]; }).get()")
    check("inv-tidy: split stacks of one item are merged", len(stacks_before) > 1 and len(stacks_after) == 1,
          "%s -> %s" % (stacks_before, stacks_after))
    check("inv-tidy: and none of them is lost",
          sum(int(t.split(" ×")[1]) if " ×" in t else 1 for t in stacks_before)
          == sum(int(t.split(" ×")[1]) if " ×" in t else 1 for t in stacks_after),
          "%s -> %s" % (stacks_before, stacks_after))
    E("$('#invStashSearch').val('').trigger('keyup'); $('#invRevert').click()")
    time.sleep(1.0)

    # ==== abl ====================================================================
    view("ABILITIES", 2.6)
    select_character(player)
    time.sleep(1.2)
    E("$('#ablBrowseSort button, #ablBrowseSort .abl-sort').filter(function(){"
      " return /level/i.test($(this).text()); }).first().click()")
    page.wait_for("$('#ablBrowseGrid .abl-row').length > 0", 60, "the ability browser")
    time.sleep(1.2)
    check("abl-browser: By level adds level markers", E("$('#ablBrowseGrid .abl-level-mark').length") > 0,
          E("$('#ablBrowseGrid .abl-level-mark').length"))
    E("$('#ablBrowseGrid .abl-row').first().click()")
    time.sleep(0.8)
    check("abl-browser: clicking a row shows its description", len(E("$('#ablDetail').text()")) > 40,
          E("$('#ablDetail').text()")[:100])
    check("abl-browser: and changes nothing", not E("Eternity.AbilityEditor.unapplied()"))

    # A talent that brings an ability with it: the talent is a name in a
    # list, the ability an object of its own that the Apply has to mint.
    granted_before = E("$('#ablGranted .abl-row').length")
    E("$('#ablBrowseKinds button, #ablBrowseKinds .abl-kind').filter(function(){"
      " return /talent/i.test($(this).text()); }).first().click()")
    time.sleep(0.8)
    picked = None
    for wanted in ("Field Triage", "Wound Binding"):
        E("$('#ablBrowseSearch').val(%s).trigger('keyup')" % json.dumps(wanted))
        time.sleep(2.0)
        row = E("""(function(n){ var hit = null; $('#ablBrowseGrid .abl-row').each(function(i){
            if (!hit && $.trim($(this).find('.abl-name').text()) === n)
                hit = {index: i, owned: $(this).hasClass('abl-row-owned')}; }); return hit; })(%s)""" % json.dumps(wanted))
        if row and not row["owned"]:
            picked = wanted
            E("$('#ablBrowseGrid .abl-row').eq(%d).find('.abl-add').click()" % row["index"])
            break
    time.sleep(0.8)
    print("   talent picked: %s" % picked)
    E("$('#ablApply').click()")
    page.wait_for("!Eternity.AbilityEditor.state.working", 300, "the abilities Apply")
    time.sleep(2.0)
    talents = E("$('#ablTalents .abl-row .abl-name').map(function(){ return $.trim($(this).text()); }).get()")
    granted = E("$('#ablGranted .abl-row .abl-name').map(function(){ return $.trim($(this).text()); }).get()")
    check("abl-add: the talent appears under Talents after the Apply", picked in talents, picked)
    check("abl-add: and the ability it grants under Granted by talents",
          len(granted) == granted_before + 1 and picked in granted, granted)
    check("abl-add: and the save still validates", not E("$('#saveWarning').is(':visible')"))
    E("$('#ablBrowseSearch').val('').trigger('keyup')")
    E("$('#ablBrowseKinds button, #ablBrowseKinds .abl-kind').first().click()")
    time.sleep(1.0)

    have = E("$('#ablList .abl-row').length")
    gone = E("$.trim($('#ablList .abl-row').first().find('.abl-name').text())")
    E("$('#ablList .abl-row').first().find('.abl-remove').click()")
    time.sleep(0.6)
    E("$('#ablApply').click()")
    page.wait_for("!Eternity.AbilityEditor.state.working", 300, "the abilities Apply")
    time.sleep(2.0)
    check("abl-remove: the ability is gone after the Apply", E("$('#ablList .abl-row').length") == have - 1,
          "%s: %s -> %s" % (gone, have, E("$('#ablList .abl-row').length")))
    check("abl-remove: and the save still validates", not E("$('#saveWarning').is(':visible')"))

    # ==== grm ====================================================================
    view("GRIMOIRE", 2.6)
    where = E("$('#grmList .grm-book .grm-book-where').map(function(){ return $.trim($(this).text()); }).get()")
    order = ("equipped", "carried", "stash", "elsewhere")
    rank = [next((i for i, word in enumerate(order) if w.lower().startswith(word)), 1) for w in where]
    check("grm-books: equipped books first, then carried, then the stash", rank == sorted(rank) and len(where) > 1,
          sorted(set(where)))
    E("$('#grmList .grm-book').first().click()")
    time.sleep(1.0)
    chapters = E("$('#grmChapters .grm-chapter').map(function(){ return $(this).find('.grm-spell:not(.grm-spell-empty)').length; }).get()")
    check("grm-books: eight levels of at most four spells", len(chapters) == 8 and max(chapters) <= 4, chapters)
    level = next(i for i, n in enumerate(chapters) if n == 4)
    removed = E("$.trim($('#grmChapters .grm-chapter').eq(%d).find('.grm-spell:not(.grm-spell-empty) .grm-spell-name').first().text())" % level)
    E("$('#grmSort button, #grmSort .grm-sort').filter(function(){ return /level/i.test($(this).text()); }).first().click()")
    page.wait_for("$('#grmBrowse .grm-browse-row').length > 0", 60, "the spell list")
    time.sleep(1.0)
    full = E("""$('#grmBrowse .grm-browse-row').filter(function(){
        return $(this).find('.grm-browse-level').text() === 'Level %d'; }).map(function(){
        return $.trim($(this).find('.grm-btn-add').text()); }).get()""" % (level + 1))
    check("grm-edit: a full level cannot take a fifth spell", full and all(f in ("In book", "Level full") for f in full),
          full[:6])
    E("$('#grmChapters .grm-chapter').eq(%d).find('.grm-spell-remove').first().click()" % level)
    time.sleep(0.8)
    chapters_now = E("$('#grmChapters .grm-chapter').map(function(){ return $(this).find('.grm-spell:not(.grm-spell-empty)').length; }).get()")
    check("grm-edit: a removed spell leaves its level", chapters_now[level] == 3, chapters_now)
    E("""$('#grmBrowse .grm-browse-row').filter(function(){
        return $.trim($(this).find('.grm-browse-name').text()) === %s; }).find('.grm-btn-add').click()""" % json.dumps(removed))
    time.sleep(0.8)
    chapters_now = E("$('#grmChapters .grm-chapter').map(function(){ return $(this).find('.grm-spell:not(.grm-spell-empty)').length; }).get()")
    check("grm-edit: and Add puts it back", chapters_now[level] == 4, chapters_now)
    E("$('#grmChapters .grm-chapter').eq(%d).find('.grm-spell-remove').first().click()" % level)
    time.sleep(0.6)
    E("$('#grmApply').click()")
    page.wait_for("!Eternity.GrimoireEditor.state.working && /updated/i.test($('#grmStatus').text())", 300, "the grimoire Apply")
    check("grm-apply: the status line says it is updated and that Save writes it",
          "Grimoire updated" in E("$('#grmStatus').text()") and "Save" in E("$('#grmStatus').text()"),
          E("$('#grmStatus').text()"))

    # ==== sh =====================================================================
    view("STRONGHOLD", 2.4)
    everything = E("$('#shUpgrades .sh-upgrade').length")
    by_filter = {}
    for n in range(E("$('#shUpgradeFilters .sh-filter').length")):
        E("$('#shUpgradeFilters .sh-filter').eq(%d).click()" % n)
        time.sleep(0.5)
        by_filter[E("$.trim($('#shUpgradeFilters .sh-filter').eq(%d).text())" % n)] = E("$('#shUpgrades .sh-upgrade').length")
    E("$('#shUpgradeFilters .sh-filter').first().click()")
    time.sleep(0.5)
    check("sh-list: All, Built, Available and Locked each show their own",
          len(by_filter) == 4 and max(by_filter.values()) == everything
          and sum(v for k, v in by_filter.items() if not k.startswith("All")) == everything, by_filter)
    row = E("""(function(){ var r = $('#shUpgrades .sh-upgrade').first(); return {
        picture: r.find('img').length, text: r.text().length, button: $.trim(r.find('.sh-upgrade-btn').text())}; })()""")
    check("sh-list: a row has its picture, its text and a Built or Build button",
          row["picture"] > 0 and row["text"] > 60 and row["button"] in ("Built", "Build"), row)
    hint = E("$('#shNumbers .sh-number label').filter(function(){ return $(this).text() === 'Turns available'; }).attr('title')")
    check("sh-numbers: the hint says the game plays the turns on loading", "as soon as the save loads" in (hint or ""), hint)
    E("""(function(){ var i = $('#shNumbers .sh-number').filter(function(){
        return $(this).find('label').text() === 'Debt'; }).find('input'); i.val(parseInt(i.val() || '0', 10) + 7).trigger('change'); })()""")
    time.sleep(0.6)
    check("sh-numbers: a changed number is staged", not E("$('#shApply').prop('disabled')") and E("Eternity.StrongholdEditor.unapplied()"))
    E("$('#shApply').click()")
    page.wait_for("!Eternity.StrongholdEditor.state.working && /updated/i.test($('#shStatus').text())", 300, "the stronghold Apply")
    check("sh-apply: the status line says it is updated and that Save writes it",
          "Stronghold updated" in E("$('#shStatus').text()") and "Save" in E("$('#shStatus').text()"),
          E("$('#shStatus').text()"))

    # ==== party ==================================================================
    in_party = E("%s.characters.filter(function(c){ return c.inParty && !c.isDead; }).length" % D)
    E("Eternity.PartyManagement.open()")
    time.sleep(1.5)
    said = E("$('#partyManagementDialog .pm-count').text()")
    check("party-manage: the dialog counts the party against its limit of six", said == "%d/6" % in_party, said)
    benched = E("""%s.characters.filter(function(c){
        return !c.inParty && !c.isDead && !c.resurrectable && c.isCompanion; }).map(function(c){ return c.GUID; })""" % D)
    for guid in benched[:8]:
        E("Eternity.PartyManagement.toggle(%s)" % json.dumps(guid))
    time.sleep(0.6)
    check("party-manage: it will not take a seventh", E("$('#partyManagementDialog .pm-count').text()") == "6/6",
          E("$('#partyManagementDialog .pm-count').text()"))
    E("$('#partyManagementCancel').click()")
    time.sleep(1.0)

    # One companion out, accepted: the party is one smaller everywhere.
    out = E("""%s.characters.filter(function(c){
        return c.inParty && !c.isDead && c.isCompanion; })[0]""" % D)
    E("Eternity.PartyManagement.open()")
    time.sleep(1.2)
    E("Eternity.PartyManagement.toggle(%s); Eternity.PartyManagement.accept()" % json.dumps(out["GUID"]))
    page.wait_for("!$('#partyManagementDialog').is(':visible') && !Eternity.PartyManagement.state.saving", 300,
                  "the party change")
    time.sleep(2.5)
    now_in = E("%s.characters.filter(function(c){ return c.inParty && !c.isDead; }).length" % D)
    moved = E("%s.characters.filter(function(c){ return c.GUID === %s; })[0]" % (D, json.dumps(out["GUID"])))
    check("party-manage: Accept moves the companion out of the party",
          now_in == in_party - 1 and not moved.get("inParty"), "%s: %d -> %d in the party" % (out["name"], in_party, now_in))
    view("INVENTORY", 3.0)
    check("party-manage: the Inventory still has a card for everyone who carries a pack",
          E("$('#invPacks .inv-pack-row').length") == E("%s.inventory.characters.length" % D),
          "%s cards, %s characters" % (E("$('#invPacks .inv-pack-row').length"), E("%s.inventory.characters.length" % D)))
    check("party-manage: and the save still validates", not E("$('#saveWarning').is(':visible')"))
    leave()

    # ==== party-resurrect: on the save with a dead companion ======================
    open_save(page, MID)
    time.sleep(1.2)
    dead = E("%s.characters.filter(function(c){ return c.resurrectable; }).map(function(c){ return {guid: c.GUID, name: c.name}; })" % D)
    print("   resurrectable: %s" % dead)
    if dead:
        size = E("%s.characters.filter(function(c){ return !c.resurrectable; }).length" % D)
        select_character(dead[0]["guid"])
        E("$('#character .resurrect-btn').click()")
        # Brought back, the companion is listed under their own ID, not the
        # stand-in the dead one had.
        page.wait_for("%s.characters.filter(function(c){ return c.resurrectable; }).length === 0"
                      " || $('#error').is(':visible')" % D, 420, "the resurrection")
        time.sleep(2.5)
        back = E("%s.characters.filter(function(c){ return !c.resurrectable && c.name.indexOf(%s) === 0; })[0]"
                 % (D, json.dumps(dead[0]["name"][:2]))) or {}
        dead[0]["guid"] = back.get("GUID", dead[0]["guid"])
        check("party-resurrect: the companion is a normal character again",
              not back.get("resurrectable") and not back.get("isDead") and len(back.get("stats", {})) > 50,
              "%s, %d stats" % (back.get("name"), len(back.get("stats", {}))))
        view("INVENTORY", 3.0)
        check("party-resurrect: with a pack of their own in the Inventory",
              E("%s.inventory.characters.filter(function(c){ return c.guid === %s; }).length" % (D, json.dumps(dead[0]["guid"]))) == 1)
        check("party-resurrect: and the save validates", not E("$('#saveWarning').is(':visible')"),
              E("$('#saveWarningList').text()")[:200])
    else:
        check("party-resurrect: the test save has a dead companion to bring back", False, "none resurrectable in " + MID)

    # ==== look-sidebar ============================================================
    def handle():
        return E("""(function(){ var h = $('#sidebarToggle')[0].getBoundingClientRect();
            var s = $('#sidebar')[0].getBoundingClientRect();
            return {mid: Math.round(h.top + h.height / 2), view: Math.round(window.innerHeight / 2),
                    left: Math.round(h.left), edge: Math.round(s.right), open: s.width > 100}; })()""")
    open_at = handle()
    E("$('#sidebarToggle').click()")
    time.sleep(0.8)
    folded = handle()
    E("$('#sidebarToggle').click()")
    time.sleep(0.8)
    again = handle()
    check("look-sidebar: the handle folds the sidebar and brings it back",
          open_at["open"] and not folded["open"] and again["open"], [open_at, folded, again])
    check("look-sidebar: and stays centred on the sidebar's edge either way",
          all(abs(h["mid"] - h["view"]) <= 40 and abs(h["left"] - h["edge"]) <= 30 for h in (open_at, folded, again)),
          [open_at, folded, again])
    leave()
finally:
    for name in os.listdir(SAVES):
        if name not in before_files:
            os.remove(os.path.join(SAVES, name))

check("the saves folder holds what it held", sorted(os.listdir(SAVES)) == before_files,
      sorted(set(os.listdir(SAVES)) ^ set(before_files)))
sys.exit(1 if summary(page) else 0)
