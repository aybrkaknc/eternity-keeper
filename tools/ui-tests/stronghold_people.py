# Dismissing hirelings and releasing prisoners in the Stronghold tab.
#
# The test saves have hirelings but nobody in the dungeon, so the second half
# borrows a save that does: the real saves folder's "2.0 Save Games Backup"
# holds one with Kestorik the vithrack locked up. A copy goes into the
# test-env saves folder for the run and is deleted afterwards, with anything
# the run writes.
import json, os, shutil, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
PRISON_SOURCE = os.path.join(os.path.expanduser("~"), "Saved Games", "Pillars of Eternity",
                             "2.0 Save Games Backup",
                             "0945952c89c640e4a18cdb293e3946b4 26429508 Odlewnia.savegame")
PRISON = "0945952c89c640e4a18cdb293e3946b4 26429508 Odlewnia.savegame"

before_files = set(os.listdir(SAVES))
page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)

E = page.eval
D = "Eternity.SavedGame.state.saveData"
PRESTIGE = "Number(%s.globals.InGameGlobal.Stronghold.Prestige.value)" % D
SECURITY = "Number(%s.globals.InGameGlobal.Stronghold.Security.value)" % D


def view(name, wait=2.4):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)


def gauge(label):
    return E("""(function(){ var g = $('#shGauges .sh-gauge').filter(function(){
      return $(this).find('.sh-gauge-label').text() === %s; });
      return Number(g.find('.sh-gauge-value').text()); })()""" % json.dumps(label))


def names(panel):
    return E("$('#%s .sh-person-name').toArray().map(function(e){ return $(e).text(); })" % panel)


def wait_status(label):
    page.wait_for("/updated|failed|could not/i.test($('#shStatus').text())", 300, label)
    time.sleep(1.0)
    return E("$('#shStatus').text()")


# ---- the hirelings, by the game's own names --------------------------------
view("STRONGHOLD")
hired = E("%s.stronghold.hirelings" % D)
print("   hirelings: %s" % [(h["name"], h["key"], h["paid"]) for h in hired])
check("the save's hirelings are listed", names("shHirelings") == [h["name"] for h in hired]
      and len(hired) == 3, names("shHirelings"))
check("by the game's own names, not their globals",
      "Crucible Knight" in names("shHirelings") and "Warden of the Wilds" in names("shHirelings"),
      names("shHirelings"))
check("the count reads 3 of 8", E("$('#shHirelingCount').text()") == "3 of 8",
      E("$('#shHirelingCount').text()"))
check("an empty dungeon says so",
      "The dungeon is empty." in E("$('#shPrisoners').text()"), E("$('#shPrisoners').text()"))
check("the residents no longer claim these are read-only",
      "not editable" not in E("$('#strongholdView').text()"))
check("nothing is staged, so Apply is off", E("$('#shApply').prop('disabled')"))

# ---- staging a dismissal ------------------------------------------------------
knight = [h for h in hired if h["key"] == "b_crucible_hireling"][0]
prestige, security = gauge("Prestige"), gauge("Security")
E("""$('#shHirelings .sh-person').filter(function(){
  return $(this).find('.sh-person-name').text() === 'Crucible Knight'; }).find('.sh-person-btn').click()""")
time.sleep(0.6)
check("Dismiss stages the row", E("$('#shHirelings .sh-person-staged').length") == 1)
check("and its button now says Keep",
      E("$('#shHirelings .sh-person-staged .sh-person-btn').text()") == "Keep")
check("the gauges project what a paid knight takes with him",
      gauge("Prestige") == prestige - knight["prestige"]
      and gauge("Security") == security - knight["security"],
      "%s/%s -> %s/%s" % (prestige, security, gauge("Prestige"), gauge("Security")))
check("the count drops to 2 of 8", E("$('#shHirelingCount').text()") == "2 of 8")
check("Apply is armed", not E("$('#shApply').prop('disabled')"))

E("$('#shHirelings .sh-person-staged .sh-person-btn').click()")
time.sleep(0.6)
check("Keep takes it back", E("$('#shHirelings .sh-person-staged').length") == 0
      and gauge("Prestige") == prestige)

E("$('#shHirelings .sh-person-btn').first().click()")
time.sleep(0.4)
E("$('#shRevert').click()")
time.sleep(0.6)
check("Revert clears a staged dismissal", E("$('#shHirelings .sh-person-staged').length") == 0
      and E("$('#shApply').prop('disabled')"))

# ---- a batch the save cannot take goes in whole or not at all ----------------
# The same dismissal, sent with a release of someone the dungeon does not hold.
# The server used to skip that one, write the dismissal and report success.
# The router's window.updateStronghold is read-only, so the extra change goes
# in as the tab writes its request: once, then JSON.stringify is itself again.
E("""$('#shHirelings .sh-person').filter(function(){
  return $(this).find('.sh-person-name').text() === 'Crucible Knight'; }).find('.sh-person-btn').click()""")
time.sleep(0.5)
E("""window.__stringify = JSON.stringify;
  JSON.stringify = function (value) {
    if (value && value.oldSave && $.isArray(value.changes)) {
      JSON.stringify = window.__stringify;
      value = $.extend({}, value, {changes: value.changes.concat(
        [{kind: 'releasePrisoner', name: 'b_nobody_prisoner'}])});
    }
    return window.__stringify.apply(JSON, [value].concat([].slice.call(arguments, 1)));
  }""")
E("$('#shApply').click()")
try:
    page.wait_for("/Nothing was changed/.test($('#shStatus').text())", 300, "the refused batch")
finally:
    E("JSON.stringify = window.__stringify")
time.sleep(0.5)
status = E("$('#shStatus').text()")
check("a batch with a refused change says which", "b_nobody_prisoner" in status, status)
check("and the dismissal in it did not go in either", len(E("%s.stronghold.hirelings" % D)) == 3
      and E(PRESTIGE) == prestige, "%s hirelings, prestige %s"
      % (len(E("%s.stronghold.hirelings" % D)), E(PRESTIGE)))
check("the draft is kept to try again", E("$('#shHirelings .sh-person-staged').length") == 1)
E("$('#shRevert').click()")
time.sleep(0.6)

# ---- dismissing for real ------------------------------------------------------
E("""$('#shHirelings .sh-person').filter(function(){
  return $(this).find('.sh-person-name').text() === 'Crucible Knight'; }).find('.sh-person-btn').click()""")
time.sleep(0.5)
E("$('#shApply').click()")
status = wait_status("the dismissal")
check("Apply dismisses him", "updated" in status, status)
check("two hirelings left", len(E("%s.stronghold.hirelings" % D)) == 2,
      [h["name"] for h in E("%s.stronghold.hirelings" % D)])
check("Prestige and Security really dropped",
      E(PRESTIGE) == prestige - knight["prestige"] and E(SECURITY) == security - knight["security"],
      "%s/%s" % (E(PRESTIGE), E(SECURITY)))
check("his global is back to 0",
      str(E("%s.globals.InGameGlobal.GlobalVariables.b_crucible_hireling.value" % D)) == "0")
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")

# ---- a prisoner -----------------------------------------------------------------
if not os.path.isfile(PRISON_SOURCE):
    check("the save with a prisoner is there to borrow", False, PRISON_SOURCE)
else:
    shutil.copyfile(PRISON_SOURCE, os.path.join(SAVES, PRISON))
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(PRISON),
                  300, "the borrowed save in the list")
    open_save(page, PRISON)
    time.sleep(1.5)
    view("STRONGHOLD")

    check("Kestorik is in the dungeon", names("shPrisoners") == ["Kestorik"], names("shPrisoners"))
    check("with the game's own description of him", "vithrack" in E(
        "$('#shPrisoners .sh-person-desc').text()"), E("$('#shPrisoners .sh-person-desc').text()"))
    check("four hirelings here", len(names("shHirelings")) == 4, names("shHirelings"))
    prestige = E(PRESTIGE)

    E("$('#shPrisoners .sh-person-btn').click()")
    time.sleep(0.5)
    check("Release stages him", E("$('#shPrisoners .sh-person-staged').length") == 1)
    check("releasing a prisoner moves no gauge", gauge("Prestige") == prestige)
    E("$('#shApply').click()")
    status = wait_status("the release")
    check("Apply releases him", "updated" in status, status)
    check("the dungeon is empty", "The dungeon is empty." in E("$('#shPrisoners').text()"))

    E("Eternity.Modifications.state.saveName = 'EK prisoner test'; Eternity.Modifications.save()")
    page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
                  600, "the Save")
    time.sleep(1.0)
    written = sorted(set(os.listdir(SAVES)) - before_files - {PRISON})
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
        check("written: nobody in the dungeon", len(E("%s.stronghold.prisoners" % D)) == 0)
        check("written: his global is 0", str(E(
            "%s.globals.InGameGlobal.GlobalVariables.b_kestorik_prisoner.value" % D)) == "0")
        check("written: the hirelings are untouched", len(E("%s.stronghold.hirelings" % D)) == 4)
        check("written: Prestige is unchanged", E(PRESTIGE) == prestige, E(PRESTIGE))
        problems = E("((%s.validation || {}).problems || []).length" % D)
        check("written: the validator finds nothing wrong", problems == 0,
              E("JSON.stringify((%s.validation || {}).problems || [])" % D))
        E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

for name in sorted(set(os.listdir(SAVES)) - before_files):
    os.remove(os.path.join(SAVES, name))

sys.exit(summary(page))
