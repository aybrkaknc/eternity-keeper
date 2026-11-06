# Two saves for checking loadouts in the game itself, built through the
# Inventory tab's own buttons. The Watcher's gear is saved to a .loadout from
# the Caed Nua save and put on:
#
#   "EK loadout test"    Aloth, in that same save: seven enchanted pieces of
#                        armour and jewellery, the arquebus in set II and four
#                        quick stacks, all copies; what they replaced goes to
#                        the stash. The pet and the soulbound sword stay out.
#   "EK loadout test B"  the main character of the prologue playthrough, who
#                        has the Watcher's ID and so takes the soulbound sword
#                        too, and the pet: a copied pet item has no creature
#                        of its own, and the game summons one.
#
# Both are left in the test-env saves folder for loading in the game; delete
# them afterwards (the suites expect twelve saves).
import json, os, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot
import filedialog

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
OTHER = "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
FILE = os.path.join(config.OUT, "ingame-loadout", "Phantom.loadout")
D = "Eternity.SavedGame.state.saveData"
os.makedirs(os.path.dirname(FILE), exist_ok=True)
if os.path.exists(FILE):
    os.remove(FILE)

page = Page()
boot(page)
E = page.eval
written = []


def status():
    return E("$('#invStatus').text()")


def inventory_view(save):
    to_list(page)
    open_save(page, save)
    time.sleep(1.5)
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
    time.sleep(3)


def select(guid):
    E("Eternity.InventoryEditor.transition({enabled: true, character: %s})" % json.dumps(guid))
    time.sleep(0.8)


def player():
    return [c for c in E("%s.inventory.characters" % D) if c["isPlayer"]][0]


def put_on(guid):
    select(guid)
    answering = threading.Thread(target=filedialog.answer, args=("Choose a loadout", FILE))
    answering.start()
    E("$('#invPutLoadout').click()")
    answering.join()
    page.wait_for("$('#loadoutDialog').is(':visible')", 120, "the plan")
    time.sleep(0.8)
    for row in E("$('#loadoutList .ld-row').toArray().map(function(r){ return $(r).text().replace(/\\s+/g, ' '); })"):
        print("   " + row)
    E("$('#loadoutConfirm').click()")
    page.wait_for("!$('#loadoutDialog').is(':visible') || $('#loadoutStatus').hasClass('ld-failed')",
                  300, "the loadout to go on")
    time.sleep(1.5)
    check("the loadout went on", status().startswith("Put the loadout on"), status())
    check("the validator finds nothing wrong",
          E("((%s.validation || {}).problems || []).length" % D) == 0,
          E("JSON.stringify((%s.validation || {}).problems || [])" % D))


def save_as(name):
    before = set(os.listdir(SAVES))
    E("Eternity.Modifications.state.saveName = %s; Eternity.Modifications.save()" % json.dumps(name))
    page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
    time.sleep(1.0)
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    new = sorted(set(os.listdir(SAVES)) - before)
    check("%s: Save wrote one new file" % name, len(new) == 1, new)
    written.extend(new)


inventory_view(SAVE)
select(player()["guid"])
answering = threading.Thread(target=filedialog.answer, args=("Save loadout", FILE))
answering.start()
E("$('#invSaveLoadout').click()")
answering.join()
page.wait_for("$('#invStatus').text().indexOf('loadout') >= 0", 120, "the loadout to be saved")
check("the Watcher's loadout is saved", os.path.isfile(FILE), status())

aloth = E("(%s.characters.filter(function(c){ return c.name === 'Aloth'; })[0] || {}).GUID" % D)
put_on(aloth)
save_as("EK loadout test")

inventory_view(OTHER)
put_on(player()["guid"])
save_as("EK loadout test B")

os.remove(FILE)
print(json.dumps({"files": written}))
sys.exit(summary(page))
