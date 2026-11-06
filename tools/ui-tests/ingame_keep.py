# A save for checking hirelings and prisoners in the game itself. Borrows the
# real saves folder's Odlewnia save from "2.0 Save Games Backup" (four
# hirelings, Kestorik in the dungeon), dismisses the Crucible Knight, releases
# Kestorik, and saves it as "EK keep test". The borrowed copy is deleted; the
# written save stays in the test-env saves folder for loading in the game.
# Expected in the game: three hirelings, an empty dungeon, Prestige 35 and
# Security 33 (the knight's +4/+2 gone) plus whatever turns resolve on load.
import json, os, shutil, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SOURCE = os.path.join(os.path.expanduser("~"), "Saved Games", "Pillars of Eternity",
                      "2.0 Save Games Backup",
                      "0945952c89c640e4a18cdb293e3946b4 26429508 Odlewnia.savegame")
BORROWED = "0945952c89c640e4a18cdb293e3946b4 26429508 Odlewnia.savegame"
before_files = set(os.listdir(SAVES))
shutil.copyfile(SOURCE, os.path.join(SAVES, BORROWED))

page = Page()
boot(page)
to_list(page)
E = page.eval
D = "Eternity.SavedGame.state.saveData"
E("Eternity.SaveSearch.search()")
page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
              "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(BORROWED),
              300, "the borrowed save in the list")
open_save(page, BORROWED)
time.sleep(1.5)
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.STRONGHOLD)")
time.sleep(2.5)

E("""$('#shHirelings .sh-person').filter(function(){
  return $(this).find('.sh-person-name').text() === 'Crucible Knight'; }).find('.sh-person-btn').click()""")
E("$('#shPrisoners .sh-person-btn').click()")
time.sleep(0.6)
E("$('#shApply').click()")
page.wait_for("/updated|failed|could not/i.test($('#shStatus').text())", 300, "the Apply")
time.sleep(1.0)

hired = [h["name"] for h in E("%s.stronghold.hirelings" % D)]
check("three hirelings stay", hired == ["Goldpact Knight", "Skirmish Archer", "Warden of the Wilds"], hired)
check("the dungeon is empty", len(E("%s.stronghold.prisoners" % D)) == 0)
numbers = (E("Number(%s.globals.InGameGlobal.Stronghold.Prestige.value)" % D),
           E("Number(%s.globals.InGameGlobal.Stronghold.Security.value)" % D))
check("Prestige 35, Security 33", numbers == (35, 33), numbers)

E("Eternity.Modifications.state.saveName = 'EK keep test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
              600, "the Save")
time.sleep(1.0)
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

os.remove(os.path.join(SAVES, BORROWED))
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
print(json.dumps({"file": written[0] if written else None}))
sys.exit(summary(page))
