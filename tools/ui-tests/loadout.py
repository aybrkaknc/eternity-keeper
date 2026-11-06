# Loadouts through the editor's own controls: the Watcher's gear saved to a
# file from the Inventory tab, put on Aloth in the same save and on the main
# character of another playthrough.
#
# What has to hold: everything goes on as a copy under an ID of its own, so
# the original owner keeps theirs; what each copy replaces lands in the stash
# as it was; what cannot go on (a pet slot only the main character has, gear
# soulbound to someone else) is named in the plan and left out; and the result
# is saved and read back from the written file, with nothing the validator
# objects to. Both buttons wait while the tab has changes of its own staged,
# and the dialog cannot be closed while the loadout goes on.
#
# The game knows the main character by the same ID in every playthrough, so
# the Watcher's soulbound sword goes on the Watcher of another one -- and is
# refused to anyone else there in the name that save knows its owner by.
import atexit, io, json, os, shutil, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot
import filedialog

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()
SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
OTHER = "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame"
FOLDER = os.path.join(config.OUT, "loadout-test")
FILE = os.path.join(FOLDER, "Phantom.loadout")
JUNK = os.path.join(FOLDER, "notes.loadout")
CHR = os.path.join(FOLDER, "Phantom.chr")
D = "Eternity.SavedGame.state.saveData"
PET = 10

os.makedirs(FOLDER, exist_ok=True)
for stale in (FILE, JUNK, CHR):
    if os.path.exists(stale):
        os.remove(stale)
with io.open(JUNK, "w", encoding="utf-8") as handle:
    handle.write(u"Aloth's gear, from memory")
before_files = set(os.listdir(SAVES))


# Whatever this run writes goes, even if it stops half-way: the other suites
# expect the test-env folder to hold its twelve saves.
@atexit.register
def remove_written():
    for name in set(os.listdir(SAVES)) - before_files:
        os.remove(os.path.join(SAVES, name))
    for made in (FILE, JUNK, CHR):
        if os.path.exists(made):
            os.remove(made)


page = Page()
boot(page)
E = page.eval


def inventory_of(guid):
    return [c for c in E("%s.inventory.characters" % D) if c["guid"] == guid][0]


def guid_of(name):
    return E("(%s.characters.filter(function(c){ return c.name === %s; })[0] || {}).GUID"
             % (D, json.dumps(name)))


def player():
    return [c for c in E("%s.inventory.characters" % D) if c["isPlayer"]][0]


def select(guid):
    E("Eternity.InventoryEditor.transition({enabled: true, character: %s})" % json.dumps(guid))
    time.sleep(0.8)


def status():
    return E("$('#invStatus').text()")


def buttons():
    return E("[$('#invSaveLoadout').prop('disabled'), $('#invPutLoadout').prop('disabled'),"
             " $('#invPutLoadout').attr('title') || '']")


def gear(character):
    """{place: (name, guid, stack)} for everything worn, held or to hand."""
    found = {}
    for slot in character["equipment"]["slots"]:
        if slot.get("item"):
            found["worn %d" % slot["index"]] = slot["item"]
    for index, held in enumerate(character["equipment"]["weaponSets"]):
        for hand in ("primary", "secondary"):
            if held.get(hand):
                found["set %d %s" % (index, hand)] = held[hand]
    for item in character["quickbar"]["items"]:
        found["quick %d" % item["uiSlot"]] = item
    return dict((k, (v["displayName"], v["guid"], v["stackSize"])) for k, v in found.items())


def stash():
    return dict((i["guid"], i["displayName"]) for i in E("%s.inventory.stash.items" % D))


def problems():
    return E("JSON.stringify((%s.validation || {}).problems || [])" % D)


def choose_loadout(path):
    answering = threading.Thread(target=filedialog.answer, args=("Choose a loadout", path))
    answering.start()
    E("$('#invPutLoadout').click()")
    answering.join()


def plan_rows():
    return E("""$('#loadoutList .ld-row').toArray().map(function(r){ return {
      name: $(r).find('.ld-name').text(), place: $(r).find('.ld-place').text(),
      outcome: $(r).find('.ld-outcome').text(), skipped: $(r).hasClass('ld-skipped'),
      icon: $(r).find('.ld-icon img').length > 0}; })""")


def put_it_on():
    busy = E("""(function(){ $('#loadoutConfirm').click();
      return [$('#loadoutDialog [data-dismiss=modal]').toArray().every(function(b){ return b.disabled; }),
              $('#loadoutConfirm').prop('disabled'), $('#loadoutConfirm').text().trim()]; })()""")
    page.wait_for("!$('#loadoutDialog').is(':visible') || $('#loadoutStatus').hasClass('ld-failed')",
                  300, "the loadout to go on")
    time.sleep(1.5)
    return busy


def leave():
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")


# ---- the Watcher's gear, to a file --------------------------------------------
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(3)

watcher = player()
select(watcher["guid"])
check("both loadout buttons are offered", buttons()[:2] == [False, False]
      and E("$('#invSaveLoadout').is(':visible') && $('#invPutLoadout').is(':visible')"), buttons())

# A staged change would be read past by both, and lost when the reply comes
# back, so they wait for it to be applied or reverted.
E("$('#invPacks .inv-pack-row:eq(0) .inv-tile').eq(0).click()")
time.sleep(0.4)
empty = E("$('#invPacks .inv-pack-row:eq(0) .inv-tile').toArray().findIndex(function(t){"
          " return $(t).hasClass('inv-tile-empty'); })")
E("$('#invPacks .inv-pack-row:eq(0) .inv-tile').eq(%d).click()" % empty)
time.sleep(0.6)
staged = buttons()
check("with a change staged both buttons wait", staged[:2] == [True, True], staged)
check("and say why", staged[2] == "Apply or revert the changes first", staged[2])
E("$('#invRevert').click()")
time.sleep(0.6)
check("Revert gives them back", buttons()[:2] == [False, False], buttons())

answering = threading.Thread(target=filedialog.cancel, args=("Save loadout",))
answering.start()
E("$('#invStatus').text('')")
E("$('#invSaveLoadout').click()")
answering.join()
time.sleep(1.5)
check("cancelling the file dialog writes nothing and says nothing",
      not os.path.exists(FILE) and status() == "", status())

answering = threading.Thread(target=filedialog.answer, args=("Save loadout", FILE))
answering.start()
E("$('#invSaveLoadout').click()")
answering.join()
page.wait_for("$('#invStatus').text().indexOf('loadout') >= 0", 120, "the loadout to be saved")
items = len(gear(watcher))
check("the loadout is saved", os.path.isfile(FILE)
      and status() == u"Saved Phantom’s loadout, %d items, to %s." % (items, FILE), status())
check("nothing in the save changed", E("Eternity.Modifications.state.modifications") is False)

# ---- not a loadout --------------------------------------------------------------
aloth = guid_of("Aloth")
select(aloth)
choose_loadout(JUNK)
page.wait_for("$('#invStatus').text().indexOf('notes.loadout') >= 0", 120, "the refusal")
check("a file that is not a loadout is refused in words",
      "notes.loadout is not a loadout" in status() and not E("$('#loadoutDialog').is(':visible')"),
      status())

# A .chr reads like a loadout, a character and their things, but only a
# .loadout goes on: refused before the plan rather than after it.
shutil.copyfile(FILE, CHR)
choose_loadout(CHR)
page.wait_for("$('#invStatus').text().indexOf('Phantom.chr') >= 0", 120, "the refusal")
check("a file of another kind is refused before the plan",
      status() == "Phantom.chr is not a loadout: only a .loadout file can be put on."
      and not E("$('#loadoutDialog').is(':visible')"), status())

# ---- onto Aloth, in the same save ---------------------------------------------------
theirs = gear(inventory_of(aloth))
mine = gear(watcher)
in_stash = stash()
choose_loadout(FILE)
page.wait_for("$('#loadoutDialog').is(':visible')", 120, "the plan")
time.sleep(0.8)
check("the plan names whose gear goes on whom",
      E("$('#loadoutSubject').text()") == u"Phantom’s gear (paladin), onto Aloth",
      E("$('#loadoutSubject').text()"))
rows = plan_rows()
by_name = dict((r["name"].split(u" ×")[0], r) for r in rows)
check("one row an item, each with its icon", len(rows) == items and all(r["icon"] for r in rows),
      [(r["name"], r["icon"]) for r in rows])
pet = mine.get("worn %d" % PET)
check("the pet is left out: only the main character has a pet slot",
      pet and by_name[pet[0]]["skipped"]
      and by_name[pet[0]]["outcome"] == "Left out: only the main character has a pet slot",
      by_name.get(pet[0] if pet else ""))
redeemer = by_name.get("St. Ydwen's Redeemer") or {}
check("soulbound gear stays with its owner", redeemer.get("skipped")
      and redeemer.get("outcome") == "Left out: soulbound to Phantom", redeemer)
neck = theirs.get("worn 1")
check("a row says what it replaces", by_name[mine["worn 1"][0]]["outcome"] == "Replaces " + neck[0],
      by_name[mine["worn 1"][0]])
fitting = [r for r in rows if not r["skipped"]]
check("everything else fits", len(fitting) == items - 2, [r["name"] for r in rows if r["skipped"]])

for light in (False, True):
    E("""(function(l){ var isLight = document.body.className.indexOf('theme-light') >= 0;
      if (isLight !== l) { $('#themeToggle').click(); } })(%s)""" % ("true" if light else "false"))
    time.sleep(0.5)
    issues = E(AUDIT + "('#loadoutDialog .modal-content')").get("issues", [])
    check("the plan is clean in %s mode" % ("light" if light else "dark"), not issues, json.dumps(issues[:2]))
    issues = E(AUDIT + "('.inv-loadout')").get("issues", [])
    check("the loadout row is clean in %s mode" % ("light" if light else "dark"), not issues,
          json.dumps(issues[:2]))
E("(function(){ if (document.body.className.indexOf('theme-light') >= 0) $('#themeToggle').click(); })()")
time.sleep(0.4)

busy = put_it_on()
check("while it goes on the dialog cannot be closed", busy[0] and busy[1] and "Working" in busy[2], busy)
check("the loadout went on", status() == "Put the loadout on Aloth; whatever it replaced is in the stash."
      " Save to write it to a file.", status() or E("$('#loadoutStatus').text()"))
check("and the save is waiting to be written", E("Eternity.Modifications.state.modifications") is True)

now = gear(inventory_of(aloth))
went_on = [place for place in mine if place != "worn %d" % PET and mine[place][0] != "St. Ydwen's Redeemer"]
check("Aloth wears and holds every item that fits",
      all(now.get(place, ("-",))[0] == mine[place][0] for place in went_on),
      [(place, now.get(place), mine[place][0]) for place in went_on if now.get(place, ("-",))[0] != mine[place][0]])
check("as copies with IDs of their own", all(now[place][1] != mine[place][1] for place in went_on if place in now))
check("quick items keep their tile and their stack",
      all(now.get(place) and now[place][2] == mine[place][2] for place in went_on if place.startswith("quick")))
kept = [place for place in theirs if place not in went_on]
check("what the loadout leaves alone stays on (hood, grimoire, set I)",
      all(now.get(place) == theirs[place] for place in kept), [(p, theirs[p][0]) for p in kept])
check("the Watcher keeps the originals", gear(inventory_of(watcher["guid"])) == mine)
replaced = [theirs[place] for place in went_on if place in theirs]
after_stash = stash()
check("what was replaced is in the stash, as it was",
      all(after_stash.get(guid) == name for name, guid, _ in replaced)
      and len(after_stash) == len(in_stash) + len(replaced),
      [name for name, guid, _ in replaced if after_stash.get(guid) != name])
check("the validator finds nothing wrong", problems() == "[]", problems())

# ---- saved and read back -----------------------------------------------------------
E("Eternity.Modifications.state.saveName = 'EK loadout'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)

if written:
    leave()
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(written[0]),
                  300, "the written save in the list")
    open_save(page, written[0])
    time.sleep(1.5)
    read_back = gear(inventory_of(aloth))
    check("written: Aloth wears what went on", all(read_back.get(place) == now[place] for place in went_on),
          [(p, read_back.get(p)) for p in went_on if read_back.get(p) != now[p]])
    check("written: the Watcher keeps the originals", gear(player()) == mine)
    check("written: the replaced items are in the stash",
          all(stash().get(guid) == name for name, guid, _ in replaced))
    check("written: the validator finds nothing wrong", problems() == "[]", problems())
leave()

# ---- into another playthrough ---------------------------------------------------
# The game knows the main character (and every companion) by the same ID in
# every playthrough, so to the game this save's Watcher is the sword's owner,
# and anyone else here is refused it in the name this save knows its owner by.
open_save(page, OTHER)
time.sleep(1.5)
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(3)
other = player()
name = E("(%s.characters.filter(function(c){ return c.GUID === %s; })[0] || {}).name"
         % (D, json.dumps(other["guid"])))
check("the other playthrough's main character has the same ID", other["guid"] == watcher["guid"],
      [other["guid"], name])

calisca = guid_of("Calisca")
select(calisca)
choose_loadout(FILE)
page.wait_for("$('#loadoutDialog').is(':visible')", 120, "the plan")
time.sleep(0.8)
rows = dict((r["name"].split(u" ×")[0], r) for r in plan_rows())
check("someone else is refused it in this save's name for its owner",
      rows["St. Ydwen's Redeemer"]["outcome"] == "Left out: soulbound to " + name, rows["St. Ydwen's Redeemer"])
untouched = gear(inventory_of(calisca))
E("$('#loadoutDialog .modal-footer [data-dismiss=modal]').click()")
time.sleep(1.0)
check("Cancel closes the plan and changes nothing", not E("$('#loadoutDialog').is(':visible')")
      and gear(inventory_of(calisca)) == untouched
      and E("Eternity.Modifications.state.modifications") is False)

select(other["guid"])
choose_loadout(FILE)
page.wait_for("$('#loadoutDialog').is(':visible')", 120, "the plan")
time.sleep(0.8)
rows = dict((r["name"].split(u" ×")[0], r) for r in plan_rows())
print("   %s: %s" % (name, [(r["name"], r["outcome"]) for r in plan_rows()]))
check("another main character has a pet slot", pet and not rows[pet[0]]["skipped"], rows.get(pet[0]))
check("and is the sword's owner to the game", not rows["St. Ydwen's Redeemer"]["skipped"],
      rows["St. Ydwen's Redeemer"])
put_it_on()
check("it went on there too", status().startswith("Put the loadout on " + name), status())
there = gear(player())
check("%s has the pet now" % name, there.get("worn %d" % PET, ("-",))[0] == pet[0], there.get("worn %d" % PET))
check("and the sword", there.get("set 0 primary", ("-",))[0] == "St. Ydwen's Redeemer", there.get("set 0 primary"))
check("and that save validates clean", problems() == "[]", problems())
leave()

check("nothing else was written to the saves folder",
      set(os.listdir(SAVES)) - before_files == set(written), sorted(set(os.listdir(SAVES)) ^ before_files))
sys.exit(summary(page))
