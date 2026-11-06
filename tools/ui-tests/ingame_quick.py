# A save for checking quick slots and weapon sets in the game itself, built
# through the Inventory tab's own clicks on two people who are in the party:
# Aloth's sets traded -- Bittercut from set II onto his soulbound sceptre in
# set I, which sends the sceptre to set II -- and the Watcher's fourth quick
# slot emptied (the Dragon Meat Dish back to the pack) and refilled with five
# of the stash's 155 lockpicks, split off the way the game splits a stack.
# The lockpicks ask for the slot while the dish is still on it, which is the
# case the slot settling exists for. Saved as "EK quick test" and left in the
# test-env saves folder for loading in the game; delete it afterwards (the
# suites expect twelve saves).
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(3)

D = "Eternity.SavedGame.state.saveData"
guid = {}
for g, name in E("%s.inventory.characters.map(function(c){ return [c.guid, c.objectName]; })" % D):
    guid[name.split("(")[0].replace("Companion_", "").replace("Player_New_Game", "Player")] = g


def select(who):
    E("Eternity.InventoryEditor.transition({enabled: true, character: %s})" % json.dumps(guid[who]))
    time.sleep(0.7)


def click(selector, index=0):
    E("$(%s).eq(%d).click()" % (json.dumps(selector), index))
    time.sleep(0.4)


def status():
    return E("$('#invStatus').text()")


def char(who):
    return [c for c in E("%s.inventory.characters" % D) if c["guid"] == guid[who]][0]


select("Aloth")
click("#invWeaponSets .inv-weapon-set:eq(1) .inv-tile", 0)   # Bittercut
click("#invWeaponSets .inv-weapon-set:eq(0) .inv-tile", 0)   # onto the sceptre

select("Player")
click("#invQuickSlots .inv-tile", 3)                          # the Dragon Meat Dish
empty = E("$('#invPacks .inv-pack-row:eq(0) .inv-tile').toArray().findIndex(function(t){"
          " return $(t).hasClass('inv-tile-empty'); })")
click("#invPacks .inv-pack-row:eq(0) .inv-tile", empty)

E("$('#invStashSearch').val('Lockpick').trigger('keyup')")
time.sleep(0.6)
click("#invStashGrid .inv-tile", 0)
click("#invQuickSlots .inv-tile", 3)
check("five lockpicks split off", "stacks to 5" in status(), status())
E("$('#invStashSearch').val('').trigger('keyup')")
time.sleep(0.4)

E("$('#invApply').click()")
time.sleep(0.5)
page.wait_for("!Eternity.InventoryEditor.state.working", 300, "the Apply")
time.sleep(1.5)
check("applied", status() == "Inventory updated. Save to write it to a file.", status())

a = char("Aloth")["equipment"]["weaponSets"]
held = [((s["primary"] or {}).get("displayName"), (s["secondary"] or {}).get("displayName")) for s in a[:2]]
check("Aloth: Bittercut in set I, the sceptre in set II"
      , held == [("Bittercut", None), ("Gyrd Háewanes Sténes", None)], held)
p = [(i["displayName"], i["stackSize"], i["uiSlot"]) for i in char("Player")["quickbar"]["items"]]
check("Watcher: five lockpicks in the fourth quick slot", ("Lockpick", 5, 3) in p, p)
pack = [(i["displayName"], i["stackSize"]) for i in char("Player")["pack"]["items"]]
check("Watcher: the Dragon Meat Dish in the pack", ("Dragon Meat Dish", 6) in pack, pack)

E("Eternity.Modifications.state.saveName = 'EK quick test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)
print(json.dumps({"file": written[0] if written else None}))
sys.exit(summary(page))
