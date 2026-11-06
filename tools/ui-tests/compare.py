# Compare saves: the save list's Compare button, driven the way a user drives
# it. Two editor-made copies of one late-game save are compared and every
# difference is read back off the dialog in the editor's own words; then a
# save is edited, saved, and the written save compared with the one it came
# from -- the question the feature exists to answer. The save written here is
# deleted again, so the test saves folder keeps its twelve.
import io, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()
FIRST = "0945952c89c640e4a18cdb293e3946b4 1 SocewCieniu.savegame"
SECOND = "0945952c89c640e4a18cdb293e3946b4 2 SocewCieniu.savegame"
PROLOGUE = "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
E = page.eval
to_list(page)


def index_of(needle):
    return E("""(function(n){ var hit = -1; Eternity.SaveSearch.state.saves.forEach(function(s, i){
      if (s.absolutePath.indexOf(n) >= 0) hit = i; }); return hit; })(%s)""" % json.dumps(needle))


def select(needle):
    E("Eternity.SaveSearch.transition({selected: %d})" % index_of(needle))
    time.sleep(0.3)


def wait_results(what="the comparison"):
    page.wait_for("!Eternity.CompareSaves.state.loading && ($('#compareResults .cmp-headline').length > 0"
                  " || $('#compareResults .cmp-problem').length > 0)", 180, what)
    time.sleep(0.3)


def pick(before, after):
    E("$('#compareBefore').val(%d).change()" % index_of(before))
    time.sleep(0.2)
    E("$('#compareAfter').val(%d).change()" % index_of(after))
    wait_results()


def rows(section):
    """Every row of a section as the dialog shows it."""
    return E("""$('#compareResults .cmp-section[data-section=' + %s + '] tr').not('.cmp-row-note').map(function(){
      var cells = $(this).children('td');
      return {group: $(this).closest('.cmp-group').find('.cmp-group-title').contents().first().text(),
              label: cells.eq(0).text(), before: cells.eq(1).text(), after: cells.eq(3).text(),
              change: cells.eq(4).text(), note: $(this).find('.cmp-note').text()}; }).get()""" % json.dumps(section))


def row(section, label, group=None):
    for r in rows(section):
        if r["label"] == label and (group is None or r["group"] == group):
            return r
    return None


def sections():
    return E("$('#compareResults .cmp-section').map(function(){ return $(this).attr('data-section'); }).get()")


# ---- the button ---------------------------------------------------------------
check("with no save picked there is no Compare", not E("$('#saveActionCompare').is(':visible')"))
select(SECOND)
check("picking a save offers Compare beside Load", E("$('#saveActionCompare').is(':visible')")
      and not E("$('#saveActionCompare').prop('disabled')"))
E("$('#saveActionCompare').click()")
page.wait_for("$('#compareSavesDialog').is(':visible')", 20, "the dialog")
wait_results()

picked = (int(E("$('#compareBefore').val()")), int(E("$('#compareAfter').val()")))
check("the save picked is one side and its own playthrough the other",
      index_of(SECOND) in picked and E("Eternity.SaveSearch.state.saves[%d].guid === Eternity.SaveSearch"
                                         ".state.saves[%d].guid" % picked), picked)
check("the earlier is Before", E("Eternity.SaveSearch.state.saves[%d].date <= Eternity.SaveSearch"
                                 ".state.saves[%d].date" % picked))

# ---- two copies of one save, every difference in words ---------------------------
pick(FIRST, SECOND)
money = row("save", "Money")
check("money in copper, with how far it moved", money and money["before"] == "303,593 cp"
      and money["after"] == "291,248 cp" and money["change"] == "−12,345 cp", money)
difficulty = row("save", "Difficulty")
check("the difficulty by name", difficulty and difficulty["before"] == "Path of the Damned"
      and difficulty["after"] == "Normal", difficulty)
check("the name in the load list", (row("save", "Name in the load list") or {}).get("after")
      == "Słońce w Cieniu (edited)", row("save", "Name in the load list"))
mechanics = row("characters", "Mechanics bonus", "Phantom")
check("a stat under its character", mechanics and mechanics["before"] == "5" and mechanics["after"] == "1"
      and mechanics["change"] == "−4", mechanics)
potion = row("items", "Potion of Barring Death's Door")
check("an item by where it is and how many", potion and potion["before"].endswith("pack · ×3")
      and potion["after"].endswith("pack · ×5"), potion)
check("the keep's Prestige", (row("stronghold", "Prestige") or {}).get("after") == "66", row("stronghold", "Prestige"))
check("a global variable by its name", (row("globals", "nDuranceMaxQuestionsPerRest") or {}).get("after") == "3",
      row("globals", "nDuranceMaxQuestionsPerRest"))
check("nothing left over for Everything else", "other" not in sections(), sections())
headline = E("$('#compareResults .cmp-headline').text()")
shown = E("$('#compareResults .cmp-table tr').not('.cmp-row-note').length")
check("the headline counts what is listed", headline.startswith("%d differences" % shown), (headline, shown))

E("$('#compareSwap').click()")
wait_results()
money = row("save", "Money")
check("Swap turns it round", money and money["before"] == "291,248 cp" and money["change"] == "+12,345 cp", money)

# ---- the same save twice, two playthroughs ----------------------------------------
E("$('#compareAfter').val($('#compareBefore').val()).change()")
time.sleep(0.4)
check("one save against itself is not compared",
      E("$('#compareResults .cmp-problem').text()") == "Pick two different saves to compare.")

pick(FIRST, PROLOGUE)
check("two playthroughs say so", "different playthroughs" in E("$('#compareResults .cmp-warning').text()"))
check("Everything else starts folded", "other" in sections()
      and E("$('#compareResults .cmp-section[data-section=other] .cmp-table').length") == 0)
E("$('#compareResults .cmp-section[data-section=other] .cmp-section-toggle').click()")
time.sleep(0.3)
check("and opens when asked", E("$('#compareResults .cmp-section[data-section=other] .cmp-table').length") > 0)

# ---- how it looks -------------------------------------------------------------------
for light in (False, True):
    E("""(function(l){ var isLight = document.body.className.indexOf('theme-light') >= 0;
      if (isLight !== l) { $('#themeToggle').click(); } })(%s)""" % ("true" if light else "false"))
    time.sleep(0.5)
    issues = E(AUDIT + "('#compareSavesDialog .modal-content')").get("issues", [])
    check("the dialog is clean in %s mode" % ("light" if light else "dark"), not issues, json.dumps(issues[:2]))
E("(function(){ if (document.body.className.indexOf('theme-light') >= 0) $('#themeToggle').click(); })()")

page.call("Emulation.setDeviceMetricsOverride", width=1100, height=800, deviceScaleFactor=1, mobile=False,
          fitWindow=False)
time.sleep(0.8)
box = E("""(function(){ var d = $('#compareSavesDialog .modal-content')[0].getBoundingClientRect(),
  r = $('#compareResults')[0]; return [d.left, d.right, window.innerWidth, r.scrollHeight > r.clientHeight,
  document.documentElement.scrollWidth]; })()""")
check("at 1100x800 the dialog fits across and its results scroll", box[0] >= 0 and box[1] <= box[2]
      and box[3] and box[4] <= box[2], box)
page.call("Emulation.clearDeviceMetricsOverride")

E("$('#compareSavesDialog').modal('hide')")
time.sleep(0.6)

# ---- an edit, saved, compared with the save it came from ----------------------------
open_save(page, PROLOGUE)
time.sleep(1.0)
money_before = float(E("Eternity.SavedGame.state.saveData.currency"))
E("Eternity.SavedGame.state.saveData.currency = %d; Eternity.Modifications.transition({modifications: true})"
  % (money_before + 777))
E("Eternity.Modifications.state.saveName = 'EK compare check'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
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
    select(written[0])
    E("$('#saveActionCompare').click()")
    page.wait_for("$('#compareSavesDialog').is(':visible')", 20, "the dialog")
    wait_results()

    check("the written save is compared with the save it was made from",
          int(E("$('#compareBefore').val()")) == index_of(PROLOGUE)
          and int(E("$('#compareAfter').val()")) == index_of(written[0]),
          (E("$('#compareBefore option:selected').text()"), E("$('#compareAfter option:selected').text()")))
    money = row("save", "Money")
    check("and says what the edit was", money and money["change"] == "+777 cp", money)
    check("the name typed on Save", (row("save", "Name in the load list") or {}).get("after") == "EK compare check",
          row("save", "Name in the load list"))
    check("and nothing else", sections() == ["save"] and len(rows("save")) == 2,
          [(name, [r["label"] + ": " + r["before"] + " -> " + r["after"] + " " + r["note"] for r in rows(name)])
           for name in sections()])
    E("$('#compareSavesDialog').modal('hide')")
    time.sleep(0.5)

for name in written:
    os.remove(os.path.join(SAVES, name))
check("the test saves folder is as it was", set(os.listdir(SAVES)) == before_files)

sys.exit(summary(page))
