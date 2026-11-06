# Every way the editor writes a save, driven once, saved, and read back.
#
# Scalar edits ride Save; the managers write on Apply into the working copy.
# The file on disk is the only thing the game reads, so each edit is checked
# in a save reopened from the list rather than in the UI's own copy.
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)

E = lambda js: page.eval(js)
D = "Eternity.SavedGame.state.saveData"
PLAYER = "(%s.characters.filter(function(c){ return c.isMainCharacter; })[0])" % D
dirty = "Eternity.Modifications.transition({modifications: true});"
expect = {}


def view(name, wait=2.4):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)


def wait_status(element, label):
    page.wait_for("/updated|failed|could not|Could not/i.test($('#%s').text())" % element,
                  300, label)
    time.sleep(1.0)
    return E("$('#%s').text()" % element)


guid = E("%s.GUID" % PLAYER)
E("Eternity.SavedGame.switchCharacter(%s)" % json.dumps(guid))
time.sleep(1.0)

# ---- scalar edits (ride Save) ------------------------------------------------
expect["might"] = int(E("%s.stats.BaseMight.value" % PLAYER)) + 2
E("%s.stats.BaseMight.value = %d; %s" % (PLAYER, expect["might"], dirty))

expect["portrait"] = "data/art/gui/portraits/companion/portrait_eder_lg.png"
E("""(function(){ var p = %s.portraitPaths;
  p.m_textureLargePath.value = 'data/art/gui/portraits/companion/portrait_eder_lg.png';
  p.m_textureSmallPath.value = 'data/art/gui/portraits/companion/portrait_eder_sm.png'; %s })()"""
  % (PLAYER, dirty))

gender = E("String(%s.stats.Gender.value)" % PLAYER)
expect["gender"] = "Female" if gender != "Female" else "Male"
E("%s.stats.Gender.value = %s; %s" % (PLAYER, json.dumps(expect["gender"]), dirty))

difficulty = E("String(%s.globals.Global.GameState.Difficulty.value)" % D)
expect["difficulty"] = "Hard" if difficulty != "Hard" else "Easy"
E("%s.globals.Global.GameState.Difficulty.value = %s; %s"
  % (D, json.dumps(expect["difficulty"]), dirty))

expect["global"] = 4242
E("%s.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value = 4242; %s" % (D, dirty))

view("CONSOLE", 1.5)
money = float(E("%s.currency" % D))
E("$('#consoleInput').val('GivePlayerMoney 1234'); $('#consoleForm').submit()")
time.sleep(0.8)
check("the console's GivePlayerMoney credits the party", float(E("%s.currency" % D)) == money + 1234,
      "%s -> %s" % (money, E("%s.currency" % D)))

# ---- achievements (writes immediately) --------------------------------------
achievements = E("!!%s.achievementsDisabled" % D)
E("$('#achievementsToggle').click()")
page.wait_for("!!%s.achievementsDisabled !== %s" % (D, json.dumps(achievements)), 240,
              "the achievements toggle")
time.sleep(1.0)
expect["achievementsDisabled"] = not achievements

# ---- inventory: sell junk, Apply --------------------------------------------
view("INVENTORY", 3.0)
stash_before = E("%s.inventory.stash.items.map(function(i){ return i.guid; })" % D)
E("$('#invSellMode').click()")
time.sleep(1.0)
E("$('#invSellJunk').click()")
time.sleep(1.0)
summary_text = E("$('#invSellSummary').text()")
E("$('#invSellConfirm').click()")
time.sleep(1.0)
# The sale is paid when its Apply succeeds, on top of the console's money,
# which is still unsaved at that point.
owed = float(E("Eternity.InventoryEditor.stagedSale().total"))
check("a staged sale is owed its price, and the purse has not moved",
      owed > 0 and float(E("%s.currency" % D)) == money + 1234, (owed, E("%s.currency" % D)))
expect["currency"] = money + 1234 + owed
E("$('#invApply').click()")
page.wait_for("!Eternity.InventoryEditor.state.working", 300, "the inventory Apply")
time.sleep(2.0)
stash_after = E("%s.inventory.stash.items.map(function(i){ return i.guid; })" % D)
sold = sorted(set(stash_before) - set(stash_after))
print("   sold: %s (%d stash items gone)" % (summary_text, len(sold)))
check("selling junk removes items from the stash", len(sold) > 0, len(sold))
check("and the Apply pays for them on top of the unsaved money",
      float(E("%s.currency" % D)) == expect["currency"], E("%s.currency" % D))
expect["sold"] = sold

# ---- abilities: add a talent, Apply -----------------------------------------
view("ABILITIES", 2.5)
talents_before = E("""(%s.abilities.characters.filter(function(c){ return c.guid === %s; })[0]
  .talents || []).map(function(t){ return t.prefab; })""" % (D, json.dumps(guid)))
E("$('#ablBrowseSearch').val('weapon focus').trigger('keyup')")
page.wait_for("$('#ablBrowseGrid .abl-add:not(:disabled)').length > 0", 90, "a talent to add")
time.sleep(0.8)
added = E("""(function(){ var b = $('#ablBrowseGrid .abl-add:not(:disabled)').first();
  var name = b.closest('.abl-row').find('.abl-name').text(); b.click(); return name; })()""")
time.sleep(0.8)
E("$('#ablApply').click()")
page.wait_for("!Eternity.AbilityEditor.state.working", 300, "the ability Apply")
time.sleep(2.0)
talents_after = E("""(%s.abilities.characters.filter(function(c){ return c.guid === %s; })[0]
  .talents || []).map(function(t){ return t.prefab; })""" % (D, json.dumps(guid)))
new_talents = sorted(set(talents_after) - set(talents_before))
print("   added %s -> %s" % (added, new_talents))
check("adding a talent puts it on the character", len(new_talents) == 1, new_talents)
expect["talents"] = new_talents

# ---- stronghold: demolish one and dismiss a hireling, Apply ------------------
view("STRONGHOLD", 2.2)
E("""(function(){ var b = $('#shUpgrades .sh-upgrade-btn').filter(function(){
  return !$(this).prop('disabled'); }).first(); b.click(); })()""")
time.sleep(1.0)
E("$('#shHirelings .sh-person-btn').first().click()")
time.sleep(0.5)
E("$('#shApply').click()")
wait_status("shStatus", "the stronghold Apply")
expect["prestige"] = E("String(%s.globals.InGameGlobal.Stronghold.Prestige.value)" % D)
expect["upgrades"] = E("%s.stronghold.built.length" % D) if E(
    "!!(%s.stronghold && %s.stronghold.built)" % (D, D)) else None
expect["hirelings"] = sorted(E("%s.stronghold.hirelings.map(function(h){ return h.key; })" % D))
check("the dismissal went through with the demolition", len(expect["hirelings"]) == 2,
      expect["hirelings"])
print("   Prestige now %s" % expect["prestige"])

# ---- grimoire: remove a spell, Apply ----------------------------------------
view("GRIMOIRE", 2.5)
book = E("Eternity.GrimoireEditor && $('#grmList .grm-book.active, #grmList .grm-book-active')"
         ".first().data('guid')")
E("$('#grmChapters .grm-spell-remove').first().click()")
time.sleep(0.8)
E("$('#grmApply').click()")
wait_status("grmStatus", "the grimoire Apply")
books = E("%s.grimoires.map(function(g){ return g.guid + '=' + g.spells.length; })" % D)
expect["grimoires"] = sorted(books)

# ---- every scalar edit is still on screen after four Applies ----------------
check("four Applies later, the Might edit is intact",
      int(E("%s.stats.BaseMight.value" % PLAYER)) == expect["might"], E("%s.stats.BaseMight.value" % PLAYER))
check("and the portrait", E("%s.portraitPaths.m_textureLargePath.value" % PLAYER) == expect["portrait"])
check("and the difficulty", E("String(%s.globals.Global.GameState.Difficulty.value)" % D)
      == expect["difficulty"])

# ---- Save, then read the file back ------------------------------------------
E("Eternity.Modifications.state.saveName = 'EK write check'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
              600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)

if written:
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(written[0]),
                  300, "the written save in the list")
    open_save(page, written[0])
    time.sleep(1.5)

    check("written: Might", int(E("%s.stats.BaseMight.value" % PLAYER)) == expect["might"],
          E("%s.stats.BaseMight.value" % PLAYER))
    check("written: portrait", E("%s.portraitPaths.m_textureLargePath.value" % PLAYER)
          == expect["portrait"], E("%s.portraitPaths.m_textureLargePath.value" % PLAYER))
    check("written: gender", E("String(%s.stats.Gender.value)" % PLAYER) == expect["gender"],
          E("String(%s.stats.Gender.value)" % PLAYER))
    check("written: difficulty", E("String(%s.globals.Global.GameState.Difficulty.value)" % D)
          == expect["difficulty"], E("String(%s.globals.Global.GameState.Difficulty.value)" % D))
    check("written: global variable", str(E(
        "%s.globals.InGameGlobal.GlobalVariables.n_Lafda_State.value" % D)) == "4242")
    check("written: money (sale + console)", abs(float(E("%s.currency" % D)) - expect["currency"]) < 0.5,
          "%s, expected %s" % (E("%s.currency" % D), expect["currency"]))
    check("written: achievements flag", E("!!%s.achievementsDisabled" % D)
          == expect["achievementsDisabled"])
    stash_now = set(E("%s.inventory.stash.items.map(function(i){ return i.guid; })" % D))
    check("written: sold items are gone", not (set(expect["sold"]) & stash_now),
          len(set(expect["sold"]) & stash_now))
    talents_now = E("""(%s.abilities.characters.filter(function(c){ return c.guid === %s; })[0]
      .talents || []).map(function(t){ return t.prefab; })""" % (D, json.dumps(guid)))
    check("written: the new talent", set(expect["talents"]) <= set(talents_now), talents_now[-3:])
    check("written: Prestige", E("String(%s.globals.InGameGlobal.Stronghold.Prestige.value)" % D)
          == expect["prestige"])
    check("written: the dismissed hireling is gone", sorted(E(
        "%s.stronghold.hirelings.map(function(h){ return h.key; })" % D)) == expect["hirelings"])
    check("written: grimoire contents", sorted(E(
        "%s.grimoires.map(function(g){ return g.guid + '=' + g.spells.length; })" % D))
          == expect["grimoires"])
    problems = E("((%s.validation || {}).problems || []).length" % D)
    check("written: the validator finds nothing wrong", problems == 0,
          E("JSON.stringify((%s.validation || {}).problems || [])" % D))
    check("written: the validation strip is hidden", not E("$('.save-validation, #saveValidation')"
                                                         ".filter(':visible').length"))
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

for name in written:
    os.remove(os.path.join(SAVES, name))

sys.exit(summary(page))
