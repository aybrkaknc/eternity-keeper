# Find anything: the box at the top of the character list, driven the way a
# user drives it on the Caed Nua save. Every kind of result opens its tab on
# the right thing -- a character's sheet, an item's tile picked out on its
# holder's doll or in the stash, an ability's row, the globals table filtered
# to one name -- and the box follows the Inventory tab's working copy, so an
# item moved but not yet applied is found where it now is.
import io, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()
SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"

page = Page()
boot(page)
E = page.eval
D = "Eternity.SavedGame.state.saveData"
VIEW = "Eternity.SavedGame.state.view"
VIEWS = "Eternity.SavedGame.views"

to_list(page)
check("there is no box on the save list", not E("$('#finder').is(':visible')"))
open_save(page, SAVE)
time.sleep(1.5)


def guid_of(name):
    return E("(%s.characters.filter(function(c){ return c.name === %s; })[0] || {}).GUID"
             % (D, json.dumps(name)))


def type_in(text):
    E("$('#finderInput').focus().val(%s).trigger('input')" % json.dumps(text))
    time.sleep(0.35)


def rows():
    return E("""$('#finderResults .finder-row').map(function(){
      return {label: $(this).find('.finder-label').text(), detail: $(this).find('.finder-detail').text(),
              kind: (this.className.match(/finder-(character|item|ability|global)/) || [])[1],
              active: $(this).hasClass('active')}; }).get()""")


def pick(kind, label, detail=None):
    """Clicks the first row of `kind` called `label` (and placed at `detail`)."""
    found = E("""(function(){ var hit = -1;
      $('#finderResults .finder-row.finder-' + %s).each(function(){
        if (hit < 0 && $(this).find('.finder-label').text() === %s
            && (%s === null || $(this).find('.finder-detail').text() === %s)) {
          hit = +$(this).attr('data-index'); } });
      if (hit >= 0) { $('#finderResults .finder-row[data-index=' + hit + ']').trigger('mousedown'); }
      return hit; })()""" % (json.dumps(kind), json.dumps(label), json.dumps(detail),
                            json.dumps(detail)))
    time.sleep(1.2)
    return found >= 0


def key(code, ctrl=False):
    E("$('#finderInput').trigger($.Event('keydown', {which: %d, keyCode: %d}))" % (code, code)
      if not ctrl else
      "$(document).trigger($.Event('keydown', {which: %d, keyCode: %d, ctrlKey: true}))"
      % (code, code))
    time.sleep(0.4)


# ---- the box -----------------------------------------------------------------
check("the box tops the character list with a save open", E("$('#finder').is(':visible')"))
box = E("""(function(){ var b = $('#finderInput').get(0).getBoundingClientRect(),
  s = $('#sidebar').get(0).getBoundingClientRect(); return [b.left, b.right, s.left, s.right]; })()""")
check("and fits inside the sidebar", box[0] >= box[2] and box[1] <= box[3] - 16, box)

E("$('#finderInput').blur()")
key(70, ctrl=True)
check("Ctrl+F puts the cursor in the box", E("document.activeElement.id") == "finderInput")

# ---- characters ----------------------------------------------------------------
type_in("aloth")
found = rows()
check("a name finds its character first", found and found[0]["kind"] == "character"
      and found[0]["label"] == "Aloth" and found[0]["active"], found[:2])
check("and other things that carry the name after it", any(r["kind"] == "global" for r in found),
      [r["kind"] for r in found])
key(13)
aloth = guid_of("Aloth")
check("Enter opens that character's sheet", E(VIEW) == E("%s.ATTR" % VIEWS)
      and E("Eternity.SavedGame.state.activeCharacter") == aloth,
      (E(VIEW), E("Eternity.SavedGame.state.activeCharacter")))
check("and leaves the box empty and the list closed", E("$('#finderInput').val()") == ""
      and not E("$('#finderResults').is(':visible')"))

type_in("niezlomny")
found = rows()
check("letters are matched without their accents (ł, ó)",
      found and found[0]["label"] == "Niezłomny", found[:1])

# ---- an item someone wears ------------------------------------------------------
type_in("aloth's grimoire")
check("an item says where it is", any(r["detail"] == "Aloth · grimoire" for r in rows()), rows()[:3])
check("opening it", pick("item", "Aloth's Grimoire", "Aloth · grimoire"))
check("shows the Inventory tab with its wearer on the doll", E(VIEW) == E("%s.INVENTORY" % VIEWS)
      and E("Eternity.InventoryEditor.state.character") == aloth)
check("and picks the tile out", E("$('#inventoryView .inv-tile.inv-tile-found').length") == 1
      and E("$('#inventoryView .inv-tile.inv-tile-found').closest('#invSlotsLeft, #invSlotsRight')"
            ".length") == 1)

# ---- an item in the stash, behind a filter ----------------------------------------
E("$('#invStashSearch').val('zzzz').trigger('keyup')")
time.sleep(0.4)
check("(the stash search hides everything first)",
      E("$('#invStashGrid .inv-tile[data-guid]').length") == 0)
type_in("ring of overseeing")
check("a stash item opens", pick("item", "Ring of Overseeing", "Stash"))
check("with the stash search cleared and the tile picked out",
      E("$('#invStashSearch').val()") == ""
      and E("$('#invStashGrid .inv-tile.inv-tile-found').length") == 1)

# ---- an item moved but not applied ---------------------------------------------
moved = E("""(function(){
  var tile = $('#invPacks .inv-tile[data-guid]').first();
  return {guid: tile.attr('data-guid'), name: (tile.attr('title') || '').split('\\n')[0]}; })()""")
E("$('#inventoryView .inv-tile[data-guid=\"%s\"]').first().click()" % moved["guid"])
time.sleep(0.5)
E("$('#invStashGrid .inv-tile-empty').last().click()")
time.sleep(0.6)
in_stash = E("$('#invStashGrid .inv-tile[data-guid=\"%s\"]').length" % moved["guid"])
check("(an item picked up from a pack and dropped in the stash)", in_stash == 1, moved)
type_in(moved["name"].split(" ×")[0])
check("is found in the stash before anything is applied",
      any(r["label"] == moved["name"].split(" ×")[0] and r["detail"].startswith("Stash")
          for r in rows()), rows()[:4])
E("$('#finderInput').val('').trigger('input').blur()")
E("$('#invRevert').click()")
time.sleep(0.6)

# ---- an ability ------------------------------------------------------------------
type_in("death ring")
check("a spell says whose it is", any(r["detail"] == "Aloth · spell" for r in rows()), rows()[:3])
check("opening it", pick("ability", "Death Ring", "Aloth · spell"))
check("shows Aloth's abilities with the row picked out", E(VIEW) == E("%s.ABILITIES" % VIEWS)
      and E("Eternity.SavedGame.state.activeCharacter") == aloth
      and E("$('#abilitiesView .abl-row.abl-row-found').length") == 1)

# ---- a global ------------------------------------------------------------------
type_in("b_aloth_joined")
check("a global is found by its name", pick("global", "b_Aloth_Joined"))
check("and opens the globals table filtered to it", E(VIEW) == E("%s.GLOBALS" % VIEWS)
      and E("$('#searchGlobals').val()") == "b_Aloth_Joined"
      and "b_Aloth_Joined" in E("$('#globalsTable tbody tr:visible td:first-child').map(function(){"
                                 " return $(this).text(); }).get()"))

# ---- the keyboard, and nothing found -----------------------------------------------
type_in("ring")
key(40)
key(40)
check("the arrow keys move down the list", [r["active"] for r in rows()][:3] == [False, False, True])
key(38)
check("and back up", [r["active"] for r in rows()][:3] == [False, True, False])
key(27)
check("Escape clears what was typed", E("$('#finderInput').val()") == ""
      and not E("$('#finderResults').is(':visible')")
      and E("document.activeElement.id") == "finderInput")
key(27)
check("and a second one leaves the box", E("document.activeElement.id") != "finderInput")

type_in("zzqx")
said = E("$('#finderResults').text()")
check("nothing found says so, and where vendors' stock is",
      "Nothing in this save matches" in said and "Vendors tab" in said, said)

# ---- what the list looks like ----------------------------------------------------
type_in("ring")
for light in (False, True):
    E("""(function(l){ var isLight = document.body.className.indexOf('theme-light') >= 0;
      if (isLight !== l) { $('#themeToggle').click(); } })(%s)""" % ("true" if light else "false"))
    time.sleep(0.6)
    type_in("ring")
    issues = E(AUDIT + "('#finderResults')").get("issues", [])
    check("the results are clean in %s mode" % ("light" if light else "dark"), not issues,
          json.dumps(issues[:2]))
E("$('#finderInput').val('').trigger('input').blur()")
E("(function(){ if (document.body.className.indexOf('theme-light') >= 0) $('#themeToggle').click(); })()")

# ---- a folded list, and the save closing --------------------------------------------
E("$('#sidebarToggle').click()")
time.sleep(0.5)
check("(the character list folded away)", E("$('body').hasClass('sidebar-collapsed')"))
key(70, ctrl=True)
check("Ctrl+F brings it back with the cursor in the box",
      not E("$('body').hasClass('sidebar-collapsed')")
      and E("document.activeElement.id") == "finderInput")

type_in("aloth")
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
check("the box and its list go with the save", not E("$('#finder').is(':visible')")
      and not E("$('#finderResults').is(':visible')") and E("$('#finderInput').val()") == "")

sys.exit(summary(page))
