# Quick slots and weapon sets under the game's own rules, on a real save.
#
# Refusals: a two-hander in the off-hand, a two-hander beside another weapon,
# a soulbound weapon handed to someone else, and the same sceptre knocked out
# of its owner's hands by a swap. Then what should work: swapping the halves
# of a set, splitting an oversized stash stack into a quick slot the way the
# game does, and a quick item traded for a pack item -- applied, saved and
# read back from the written file. The server's own guard is driven directly
# too, since the UI would never send what it refuses.
import atexit, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
before_files = set(os.listdir(SAVES))


# Whatever this run writes goes, even if it stops half-way: the other suites
# expect the test-env folder to hold its twelve saves.
@atexit.register
def remove_written():
    for name in set(os.listdir(SAVES)) - before_files:
        os.remove(os.path.join(SAVES, name))

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


def weapon(set_index, hand):
    click("#invWeaponSets .inv-weapon-set:eq(%d) .inv-tile" % set_index, hand)


def status():
    return E("$('#invStatus').text()")


def names(selector, counts=True):
    # A tile's title is its name, " ×N" for a stack, then a hint line.
    found = E("$(%s).toArray().map(function(t){ return ($(t).attr('title') || '-').split('\\n')[0]; })"
              % json.dumps(selector))
    return found if counts else [n.split(" ×")[0] for n in found]


def sets():
    return [names("#invWeaponSets .inv-weapon-set:eq(%d) .inv-tile" % s) for s in range(2)]


def char(who):
    return [c for c in E("%s.inventory.characters" % D) if c["guid"] == guid[who]][0]


def drop_held():
    E("$(document).trigger($.Event('keyup', {which: 27, keyCode: 27}))")
    time.sleep(0.3)


# ---- refusals ----------------------------------------------------------------
select("Player")
before = sets()
weapon(0, 0)
weapon(0, 1)
check("a two-hander is refused in the off-hand", "takes both hands" in status(), status())
check("and nothing moved", sets() == before, sets())
drop_held()

# The arquebus in set II: two-handed and bound to nobody. (St. Ydwen's
# Redeemer in set I is soulbound to the Watcher, which is refused first.)
weapon(1, 0)
select("Maneha")
maneha = sets()
weapon(0, 0)
check("a two-hander is refused beside another weapon", "requires two slots" in status(), status())
check("and Maneha's set is as it was", sets() == maneha, sets())
drop_held()

select("Aloth")
weapon(0, 0)
select("Kana")
kana = sets()
weapon(1, 0)
check("a soulbound weapon is refused to someone else", "soulbound to Aloth" in status(), status())
check("and Kana's sets are as they were", sets() == kana, sets())
drop_held()

# Kana's pistol dropped on Aloth's sceptre would send the sceptre back to Kana.
select("Kana")
weapon(0, 0)
select("Aloth")
aloth = sets()
weapon(0, 0)
check("a swap that would hand a soulbound weapon over is refused too",
      "soulbound to Aloth" in status(), status())
check("and Aloth still holds it", sets() == aloth, sets())
drop_held()

# ---- what works ---------------------------------------------------------------
select("Maneha")
weapon(0, 1)
weapon(0, 0)
swapped = sets()
check("the two halves of a one-handed set swap", swapped[0] == [maneha[0][1], maneha[0][0]], swapped)

stash = E("""(function(){ var s = %s.inventory.stash.items.filter(function(i){
  return i.maxStack > 1 && i.stackSize > i.maxStack; })[0];
  return s ? {guid: s.guid, name: s.displayName, stack: s.stackSize, cap: s.maxStack} : null; })()""" % D)
print("   stash stack to split: %s" % stash)
check("the save has a stash stack over its cap to split", bool(stash), stash)

if stash:
    E("$('#invStashSearch').val(%s).trigger('keyup')" % json.dumps(stash["name"]))
    time.sleep(0.6)
    index = E("""$('#invStashGrid .inv-tile').toArray().findIndex(function(t){
      return ($(t).attr('title') || '').indexOf(%s) === 0; })""" % json.dumps(stash["name"]))

    # The stash stacks without limit, so its quantity panel must not clamp
    # to the item's cap: opening it and pressing OK used to keep 5 of 155.
    E("$('#invStashGrid .inv-tile').eq(%d).trigger('dblclick')" % index)
    page.wait_for("$('#stackDialog').is(':visible')", 10, "the quantity panel")
    time.sleep(0.4)
    E("$('#stackAccept').click()")
    time.sleep(0.8)
    kept = [i["stackSize"] for i in E("%s.inventory.stash.items" % D) if i["guid"] == stash["guid"]]
    shown = E("$('#invStashGrid .inv-tile').eq(%d).find('.inv-tile-count').text()" % index)
    check("the stash's quantity panel keeps all %d" % stash["stack"], shown == str(stash["stack"]),
          "tile shows %s" % shown)

    select("Kana")
    free = names("#invQuickSlots .inv-tile").index("-")
    click("#invStashGrid .inv-tile", index)
    click("#invQuickSlots .inv-tile", free)
    check("an oversized stack splits at its cap", "stacks to %d" % stash["cap"] in status(), status())
    quick = names("#invQuickSlots .inv-tile")
    check("the quick slot holds %d of them" % stash["cap"],
          E("$('#invQuickSlots .inv-tile').eq(%d).find('.inv-tile-count').text()" % free)
          == str(stash["cap"]), quick)
    E("$('#invStashSearch').val('').trigger('keyup')")
    time.sleep(0.4)

select("Player")
pack_item = char("Player")["pack"]["items"][0]
quick_item = [i for i in char("Player")["quickbar"]["items"] if i["uiSlot"] == 3][0]
print("   trading %s (quick slot 4) for %s (pack)" % (quick_item["displayName"], pack_item["displayName"]))
click("#invQuickSlots .inv-tile", 3)
empty = E("$('#invPacks .inv-pack-row:eq(0) .inv-tile').toArray().findIndex(function(t){"
          " return $(t).hasClass('inv-tile-empty'); })")
click("#invPacks .inv-pack-row:eq(0) .inv-tile", empty)
pack_index = E("""$('#invPacks .inv-pack-row:eq(0) .inv-tile').toArray().findIndex(function(t){
  return ($(t).attr('title') || '').indexOf(%s) === 0; })""" % json.dumps(pack_item["displayName"]))
click("#invPacks .inv-pack-row:eq(0) .inv-tile", pack_index)
click("#invQuickSlots .inv-tile", 3)
check("a pack item goes into the freed quick slot",
      names("#invQuickSlots .inv-tile", counts=False)[3] == pack_item["displayName"],
      names("#invQuickSlots .inv-tile"))

E("$('#invApply').click()")
time.sleep(0.5)
page.wait_for("!Eternity.InventoryEditor.state.working", 300, "the Apply")
time.sleep(1.5)
check("a successful Apply says so", status() == "Inventory updated. Save to write it to a file.", status())

m = char("Maneha")["equipment"]["weaponSets"][0]
check("reply: Maneha's set swapped",
      [(m["primary"] or {}).get("displayName"), (m["secondary"] or {}).get("displayName")] == swapped[0],
      [(m["primary"] or {}).get("displayName"), (m["secondary"] or {}).get("displayName")])
if stash:
    k = [(i["displayName"], i["stackSize"]) for i in char("Kana")["quickbar"]["items"]]
    check("reply: Kana's quick slots hold the split stack", (stash["name"], stash["cap"]) in k, k)
    left = [i["stackSize"] for i in E("%s.inventory.stash.items" % D) if i["guid"] == stash["guid"]]
    check("reply: the rest stayed in the stash", left == [stash["stack"] - stash["cap"]], left)
p = [(i["displayName"], i["uiSlot"]) for i in char("Player")["quickbar"]["items"]]
check("reply: the player's fourth quick slot holds the pack item", (pack_item["displayName"], 3) in p, p)
check("the validator finds nothing wrong", E("((%s.validation || {}).problems || []).length" % D) == 0,
      E("JSON.stringify((%s.validation || {}).problems || [])" % D))

# ---- the server's own guard ---------------------------------------------------
aloth = char("Aloth")
sceptre = aloth["equipment"]["weaponSets"][0]["primary"]["guid"]
E("""window.__inv = null; window.updateInventory({request: JSON.stringify({
  oldSave: Eternity.SavedGame.state.info.absolutePath, savedYet: Eternity.Modifications.state.savedYet,
  changes: [
    {character: %s, component: 'Equipment', itemGuid: %s, stackSize: 1, destCharacter: %s,
     destComponent: 'Inventory', destSlot: -1, fromEquipmentSlot: 0, weaponSet: true},
    {character: %s, component: 'Inventory', itemGuid: %s, stackSize: 1, destCharacter: %s,
     destComponent: 'Equipment', destSlot: -1, toEquipmentSlot: 1, weaponSet: true}]}),
  onSuccess: function(){ window.__inv = 'accepted'; },
  onFailure: function(code, message){ window.__inv = message; }})"""
  % (json.dumps(guid["Aloth"]), json.dumps(sceptre), json.dumps(guid["Aloth"]),
     json.dumps(guid["Aloth"]), json.dumps(sceptre), json.dumps(guid["Aloth"])))
page.wait_for("window.__inv !== null", 120, "the server's answer")
answer = E("window.__inv")
check("the server refuses a two-hander in the off-hand by itself", "both hands" in answer, answer)

# ---- saved and read back ------------------------------------------------------
E("Eternity.Modifications.state.saveName = 'EK quick and weapons'; Eternity.Modifications.save()")
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
    open_save(page, written[0])
    time.sleep(1.5)

    m = char("Maneha")["equipment"]["weaponSets"][0]
    check("written: Maneha's set swapped",
          [(m["primary"] or {}).get("displayName"), (m["secondary"] or {}).get("displayName")] == swapped[0])
    if stash:
        k = [(i["displayName"], i["stackSize"]) for i in char("Kana")["quickbar"]["items"]]
        check("written: Kana's quick slots hold the split stack", (stash["name"], stash["cap"]) in k, k)
    p = [(i["displayName"], i["uiSlot"]) for i in char("Player")["quickbar"]["items"]]
    check("written: the player's quick slots", (pack_item["displayName"], 3) in p, p)
    check("written: the sceptre is still Aloth's",
          char("Aloth")["equipment"]["weaponSets"][0]["primary"]["guid"] == sceptre)
    check("written: the validator finds nothing wrong",
          E("((%s.validation || {}).problems || []).length" % D) == 0,
          E("JSON.stringify((%s.validation || {}).problems || [])" % D))
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

sys.exit(summary(page))
