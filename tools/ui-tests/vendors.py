# The Vendors tab: every store in a save, read from its area files, and
# taking items out of their stock.
#
# Picks and clears on Winfrith's 255 items, the category filter, Revert, the
# everywhere selection; then a real Apply of the Artificer's two traps and
# three of Winfrith's sold items, a Save, and the written file checked area by
# area -- the two edited .lvl files changed, every other one byte for byte the
# same as the save it came from.
import hashlib, json, os, sys, time, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
ARTIFICER = "36696af5-8025-4532-a5d3-0068fe04e380"
WINFRITH = "8962e558-49ef-415e-81dd-aefe7029e77b"

before_files = set(os.listdir(SAVES))
page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval


def vendors():
    return E("""(function(){ var out = [];
      $('#vndList .vnd-vendor').each(function(){ out.push($(this).find('.vnd-vendor-name')
        .contents().first().text()); }); return out; })()""")


def pick_vendor(name):
    E("""$('#vndList .vnd-vendor').filter(function(){
      return $(this).find('.vnd-vendor-name').contents().first().text() === %s; }).click()""" % json.dumps(name))
    time.sleep(0.5)


def tiles(selector=""):
    return E("$('#vndGrid .vnd-tile%s').length" % selector)


def status():
    return E("$('#vndStatus').text()")


# Reading every area file costs about a second, so it waits for the tab.
E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(2.0)
check("the area files are not read until the tab opens", E("$('#vndList .vnd-vendor').length") == 0)

E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.VENDORS)")
page.wait_for("$('#vndList .vnd-vendor').length > 0 || /could not/i.test($('#vndMessage').text())",
              120, "the vendor list")
time.sleep(0.8)

# ---- the list ---------------------------------------------------------------------
listed = vendors()
print("   %d vendors listed: %s ..." % (len(listed), listed[:6]))
check("the vendors the party has traded with are listed", len(listed) == 40, len(listed))
check("the count says how many of how many", E("$('#vndCount').text()") == "40 of 68",
      E("$('#vndCount').text()"))
check("no loading message is left behind", not E("$('#vndMessage').is(':visible')"),
      E("$('#vndMessage').text()"))
check("nothing is picked to begin with", E("$('#vndApply').prop('disabled')")
      and E("$('#vndGrid .vnd-tile-marked').length") == 0)

E("$('#vndShowUnvisited').click()")
time.sleep(0.5)
check("vendors never opened can be listed too", len(vendors()) == 68, len(vendors()))
E("$('#vndShowUnvisited').click()")
time.sleep(0.5)
check("and hidden again", len(vendors()) == 40)

# ---- one vendor's stock --------------------------------------------------------
pick_vendor("Winfrith")
check("choosing a vendor shows its stock", E("$('#vndTitle').text()") == "Winfrith"
      and tiles() == 255, "%s, %d tiles" % (E("$('#vndTitle').text()"), tiles()))
check("where it is, and that the party traded there",
      "Dyrford Store" in E("$('#vndMeta').text()") and "traded with" in E("$('#vndMeta').text()"),
      E("$('#vndMeta').text()"))
original = E("""(function(){ var v = $('#vndGrid .vnd-tile-original').length; return v; })()""")
check("original stock is marked", 0 < original < 255, original)
page.wait_for("$('#vndGrid .vnd-tile img').length > 100", 60, "the icons")
check("icons arrive for the vendor on screen", E("$('#vndGrid .vnd-tile img').length") > 100,
      E("$('#vndGrid .vnd-tile img').length"))
check("a tile's tooltip says what it is and what it costs",
      "cp each" in (E("$('#vndGrid .vnd-tile').first().attr('title')") or ""),
      E("$('#vndGrid .vnd-tile').first().attr('title')"))

E("$('#vndGrid .vnd-tile:not(.vnd-tile-original)').first().click()")
time.sleep(0.4)
check("a click picks an item", tiles(".vnd-tile-marked") == 1)
check("the rail counts it", E("$('#vndList .vnd-vendor-selected .vnd-vendor-picked').text()") == "−1",
      E("$('#vndList .vnd-vendor-selected .vnd-vendor-picked').text()"))
check("the status line totals it", "1 item picked at 1 vendor" in status(), status())
check("Apply and Revert are armed", not E("$('#vndApply').prop('disabled')")
      and not E("$('#vndRevert').prop('disabled')"))
E("$('#vndGrid .vnd-tile-marked').first().click()")
time.sleep(0.4)
check("a second click keeps it", tiles(".vnd-tile-marked") == 0 and E("$('#vndApply').prop('disabled')"))

# The category buttons narrow the grid, and the selection buttons act on
# what is shown.
E("$('#vndFilters .inv-filter').first().click()")
time.sleep(0.4)
weapons = tiles()
check("the weapons filter narrows the grid", 0 < weapons < 255, weapons)
E("$('#vndSelectSold').click()")
time.sleep(0.4)
picked = tiles(".vnd-tile-marked")
check("'not original' picks the weapons shown that are not original stock",
      picked == tiles(":not(.vnd-tile-original)") and picked > 0, "%d of %d" % (picked, weapons))
E("$('#vndFilters .inv-filter').first().click()")
time.sleep(0.4)
check("the other categories were left alone", tiles(".vnd-tile-marked") == picked)
E("$('#vndSelectNone').click()")
time.sleep(0.4)
check("Clear drops the picks shown", tiles(".vnd-tile-marked") == 0)

E("$('#vndSelectEverywhere').click()")
time.sleep(0.8)
everywhere = E("""(function(){ var m = /([0-9,]+) items? picked at ([0-9]+) vendors?/.exec($('#vndStatus').text());
  return m ? [Number(m[1].replace(/,/g, '')), Number(m[2])] : null; })()""")
check("'everywhere' picks what was sold at every vendor traded with",
      everywhere and everywhere[0] > 1000 and everywhere[1] > 20, status())
check("original stock is never among them", tiles(".vnd-tile-original.vnd-tile-marked") == 0)
E("$('#vndRevert').click()")
time.sleep(0.5)
check("Revert clears every pick", "Reverted" in status() and E("$('#vndApply').prop('disabled')")
      and E("$('#vndList .vnd-vendor-picked').length") == 0, status())

# The stronghold's merchant repeats GUIDs: nine of them name eighteen of his
# entries, each a different item, none with a packet. An entry is picked by
# its place.
pick_vendor("General Goods Merchant")
dup = E("""(function(){ var seen = {}, found = null;
  $('#vndGrid .vnd-tile').each(function(){ var g = $(this).attr('data-guid'), i = Number($(this).attr('data-index'));
    if (found === null && seen[g] !== undefined) found = [seen[g], i]; seen[g] = i; });
  return found; })()""")
check("the merchant's list repeats a GUID", dup is not None, dup)
if dup:
    E("$('#vndGrid .vnd-tile[data-index=%d]').click()" % dup[1])
    time.sleep(0.4)
    check("picking one of those entries picks it alone", tiles(".vnd-tile-marked") == 1
          and E("$('#vndGrid .vnd-tile[data-index=%d]').hasClass('vnd-tile-marked')" % dup[1]),
          tiles(".vnd-tile-marked"))
E("$('#vndSelectSold').click()")
time.sleep(0.5)
merchant = tiles()
check("every entry counts, repeated GUID or not",
      ("%s items picked at 1 vendor" % "{:,}".format(merchant)) in status(), "%d tiles; %s" % (merchant, status()))
E("$('#vndRevert').click()")
time.sleep(0.5)

# ---- applying for real ---------------------------------------------------------------
pick_vendor("Artificer")
check("the Artificer holds two traps", tiles() == 2, tiles())
E("$('#vndSelectSold').click()")
pick_vendor("Winfrith")
# One at a time, as a player clicks: each click redraws the grid.
for _ in range(3):
    E("$('#vndGrid .vnd-tile:not(.vnd-tile-original):not(.vnd-tile-marked)').first().click()")
    time.sleep(0.3)
check("five picked at two vendors", "5 items picked at 2 vendors" in status(), status())
E("$('#vndApply').click()")
page.wait_for("/Took|could not|failed/i.test($('#vndStatus').text()) && $('#vndList .vnd-vendor').length > 0",
              300, "the Apply")
time.sleep(1.0)
check("Apply takes them out", "Took 5 items out of 2 vendors" in status(), status())
check("the list is read again: Winfrith holds 252", tiles() == 252, tiles())
pick_vendor("Artificer")
check("and the Artificer nothing", "nothing in stock" in E("$('#vndGrid').text()").lower(),
      E("$('#vndGrid').text()"))
check("Save is armed", not E("$('#saveButton').prop('disabled')"))

E("Eternity.Modifications.state.saveName = 'EK vendors test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
              600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)

if written:
    def digests(path):
        with zipfile.ZipFile(path) as z:
            return {n: hashlib.md5(z.read(n)).hexdigest() for n in z.namelist() if n.endswith(".lvl")}

    old = digests(os.path.join(SAVES, SAVE))
    new = digests(os.path.join(SAVES, written[0]))
    changed = sorted(n for n in old if new.get(n) != old[n])
    check("the written save has every area file", set(old) == set(new), len(new))
    check("exactly the two edited area files changed",
          changed == ["AR_0003_Dyrford_Store.lvl", "AR_0611_Artificer_Hall.lvl"], changed)

    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
    E("Eternity.SaveSearch.search()")
    page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some("
                  "function(s){ return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(written[0]),
                  300, "the written save in the list")
    open_save(page, written[0])
    time.sleep(1.5)
    check("the last save's vendors are gone when another opens",
          E("$('#vndList .vnd-vendor').length") == 0)
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.VENDORS)")
    page.wait_for("$('#vndList .vnd-vendor').length > 0", 120, "the written save's vendors")
    time.sleep(0.8)
    pick_vendor("Winfrith")
    check("written: Winfrith holds 252", tiles() == 252, tiles())
    pick_vendor("Artificer")
    check("written: the Artificer holds nothing", tiles() == 0, tiles())
    problems = E("((Eternity.SavedGame.state.saveData.validation || {}).problems || []).length")
    check("written: the validator finds nothing wrong", problems == 0, problems)
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")

for name in sorted(set(os.listdir(SAVES)) - before_files):
    os.remove(os.path.join(SAVES, name))

sys.exit(summary(page))
