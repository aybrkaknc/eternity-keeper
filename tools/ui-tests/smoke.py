# A cold boot smoke test: every view opens, nothing throws, and the two fixes
# from this session are in place.
import io, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, boot, open_save, to_list

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
EARLY = "cf88c16dc9564d77a49e89c8894d5c4e 1 CilantLs.savegame"

page = Page()
boot(page)

# The startup chain completing at all is the check that no data-bound element
# is missing -- Editor.initialise() has no try/catch, so one bad binding
# silently aborts everything after it.
check("the save list populated on a cold boot",
      page.eval("$('#saveBlocks .save-info').length") > 0,
      page.eval("$('#saveBlocks .save-info').length"))
check("every component was constructed",
      page.eval("""['SaveSearch','SavedGame','CurrencyEditor','DifficultyEditor',
        'InventoryEditor','AbilityEditor','StrongholdEditor','GrimoireEditor',
        'PortraitPicker','ConsoleTab','Modifications','ImportCharacter',
        'ExportCharacter','PartyManagement','VendorEditor','PartyCare','Finder','CompareSaves']
        .every(function(n){ return !!Eternity[n]; })"""))

# Editor.render used to hide the views by a hand-written list of ids, so a new
# view stayed on screen behind the save-loading panel until someone added it.
# Checking the property instead of the list covers whatever gets added next.
VISIBLE_VIEWS = ("$('.view').filter(':visible').map("
                 "function(){ return this.id; }).get()")
check("no editor view shows on the save list",
      not page.eval(VISIBLE_VIEWS), json.dumps(page.eval(VISIBLE_VIEWS)))

open_save(page, SAVE)

for label, view, selector in [
        ("Attributes", "ATTR", "#character"),
        ("Raw", "RAW", "#rawTable"),
        ("Globals", "GLOBALS", "#globalsTable"),
        ("Inventory", "INVENTORY", "#inventoryView"),
        ("Abilities", "ABILITIES", "#abilitiesView"),
        ("Stronghold", "STRONGHOLD", "#strongholdView"),
        ("Vendors", "VENDORS", "#vendorsView"),
        ("Console", "CONSOLE", "#consoleView")]:
    page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % view)
    time.sleep(1.4)
    check("%s view opens" % label, page.eval("$(%s).is(':visible')"
                                             % json.dumps(selector)))

# The two fixes.
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.ABILITIES)")
time.sleep(2.5)
check("no ability tile is a bare black square",
      page.eval("$('.abl-icon-empty').filter(function(){"
                "return $(this).text().trim() === '';}).length") == 0)

page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.ATTR)")
time.sleep(1)
page.eval("Eternity.PartyManagement.open()")
time.sleep(2)
check("the dead companion is inert in the party dialog",
      page.eval("""(function(){
        var t = $('.pm-tile').filter(function(){
          return String($(this).data('guid')).indexOf('dead:') === 0;});
        if (t.length !== 1) return false;
        var e = $._data(t[0], 'events') || {};
        return t.hasClass('pm-dead') && !e.click;
      })()"""))
page.eval("$('#partyManagementDialog').modal('hide')")
time.sleep(1)

# The identity panel, and that it does not offer the field the game derives.
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.ATTR)")
time.sleep(1.5)
check("the identity panel is showing",
      page.eval("$('#identityPanel').is(':visible') && $('.identity-field').length >= 6"),
      page.eval("$('.identity-field').length"))
check("every identity dropdown says what it is worth",
      page.eval("$('#identityGrid .identity-effect').length")
      == page.eval("$('.identity-field').length"),
      page.eval("$('#identityGrid .identity-effect').length"))
check("the sheet totals are drawn",
      page.eval("$('#identitySheet .identity-chip').length") >= 6,
      page.eval("$('#identitySheet .identity-chip').length"))
check("RacialBodyType is not offered",
      page.eval("$('.identity-field label').map(function(){return this.textContent;})"
                ".get().join(',').indexOf('Body') < 0"))

# The portrait picker, which is a modal on the character view.
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.ATTR)")
time.sleep(1.5)
check("the character view offers a portrait picker",
      page.eval("$('.character .portrait .portrait-btn').length") == 1)
page.eval("$('.character .portrait .portrait-btn').click()")
time.sleep(3)
check("the picker draws portraits off the game install",
      page.eval("$('#portraitGrid .ptr-tile img.ptr-image').length") > 0,
      page.eval("$('#portraitGrid .ptr-tile').length"))
page.eval("$('#portraitDialog').modal('hide')")
time.sleep(1)

# The grimoire view: books rather than characters, eight chapters of four.
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.GRIMOIRE)")
time.sleep(2.5)
check("the grimoire view draws its chapters",
      page.eval("$('#grmChapters .grm-chapter').length") == 8,
      page.eval("$('#grmChapters .grm-chapter').length"))
check("and a book's spells are in them",
      page.eval("$('#grmChapters .grm-spell').not('.grm-spell-empty').length") > 0,
      page.eval("$('#grmChapters .grm-spell').not('.grm-spell-empty').length"))

# A save nobody has broken has nothing to say about itself. The strip firing
# on a healthy save would be worse than no strip at all.
check("the validation strip stays quiet on a healthy save",
      not page.eval("$('#saveWarning').is(':visible')")
      and page.eval("((Eternity.SavedGame.state.saveData.validation || {})"
                    ".problems || []).length") == 0,
      page.eval("JSON.stringify((Eternity.SavedGame.state.saveData.validation"
                " || {}).problems || [])"))

# Going back to the list has to clear whatever view was open.
to_list(page)
time.sleep(1.5)
check("going back to the list clears every view",
      not page.eval(VISIBLE_VIEWS), json.dumps(page.eval(VISIBLE_VIEWS)))
open_save(page, SAVE)

# And a save with no stronghold still behaves.
to_list(page)
open_save(page, EARLY)
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.STRONGHOLD)")
time.sleep(1.5)
check("a save with no stronghold says so",
      page.eval("$('#shUnavailable').is(':visible') && !$('#shMain').is(':visible')"))

page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.GRIMOIRE)")
time.sleep(1.5)
check("a save with no grimoire says so",
      page.eval("$('#grmUnavailable').is(':visible') && !$('#grmMain').is(':visible')"))

sys.exit(summary(page))
