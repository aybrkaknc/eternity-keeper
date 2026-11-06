# The Abilities and Stronghold tabs at several window sizes.
#
# Asked for: the three ability panels side by side and the same height, the
# stronghold's upgrades and rail across the whole width, and both behaving as
# the window changes size. The Vendors tab joined them: its rail and stock
# side by side at one height, clear of the Save button. Each size is emulated (the window itself stays put)
# and measured; a screenshot of each lands in target/ui-tests/shots.
import base64, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from harness import Page, open_save, to_list, boot, check, summary

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
OUT = os.path.join(config.OUT, "shots")
os.makedirs(OUT, exist_ok=True)

SIZES = [(1100, 800), (1280, 800), (1440, 900), (1920, 1080), (1920, 800), (2560, 1369)]

page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval


def shot(name):
    data = page.call("Page.captureScreenshot", format="png")
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(base64.b64decode(data["data"]))


def view(name, wait=3.0):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)
    E("window.scrollTo(0, 0)")


def overflow():
    return E("""$('.view:visible *').filter(function(){
      var b = this.getBoundingClientRect(); return b.width > 0 && b.right > window.innerWidth + 1
        && $(this).css('position') !== 'fixed'; }).length""")


BOXES = """(function(sel){ return $(sel).filter(':visible').toArray().map(function(e){
  var b = e.getBoundingClientRect();
  return {l: Math.round(b.left), t: Math.round(b.top), r: Math.round(b.right), b: Math.round(b.bottom),
          w: Math.round(b.width), h: Math.round(b.height)}; }); })(%s)"""


def boxes(selector):
    return E(BOXES % json.dumps(selector))


def spills(selector):
    """Children drawn outside their panel's box (content that does not scroll)."""
    return E("""(function(sel){ var n = 0; $(sel).filter(':visible').each(function(){
      var p = this.getBoundingClientRect();
      $(this).children(':visible').each(function(){
        var c = this.getBoundingClientRect();
        if (c.bottom > p.bottom + 1 || c.right > p.right + 1) n++; }); });
      return n; })(%s)""" % json.dumps(selector))


heights = {}
for width, height in SIZES:
    label = "%dx%d" % (width, height)
    page.call("Emulation.setDeviceMetricsOverride", width=width, height=height,
              deviceScaleFactor=1, mobile=False, fitWindow=False)
    time.sleep(1.2)

    # ---- the navbar ----------------------------------------------------------
    # The menus end at the same place whatever the width, and the theme toggle
    # is pinned to the right edge: at 1,100 it used to sit on "Console".
    nav = E("""(function(){ var m = $('.navbar .navbar-nav').get(0).getBoundingClientRect(),
      t = $('#themeToggle').get(0).getBoundingClientRect(); return [m.right, t.left]; })()""")
    check("%s navbar: the theme toggle clears the menus" % label, nav[1] >= nav[0] + 4, nav)

    # ---- abilities ---------------------------------------------------------
    view("ABILITIES", 3.5)
    tab = boxes("#abilitiesView")[0]
    panels = boxes("#abilitiesView .abl-panel, #abilitiesView .abl-browser-panel")
    inner = E("window.innerHeight")
    print("   %s abilities: %s" % (label, [(p["l"], p["t"], p["w"], p["h"]) for p in panels]))
    check("%s abilities: nothing runs off the right edge" % label, overflow() == 0, overflow())
    check("%s abilities: the two lists are equally wide" % label,
          len(panels) == 3 and abs(panels[0]["w"] - panels[1]["w"]) <= 2, [p["w"] for p in panels])

    first_line = [p for p in panels if abs(p["t"] - panels[0]["t"]) <= 2]
    check("%s abilities: panels sharing a line share a height" % label,
          max(p["h"] for p in first_line) - min(p["h"] for p in first_line) <= 2,
          [p["h"] for p in first_line])
    check("%s abilities: the first line ends inside the window" % label,
          max(p["b"] for p in first_line) <= inner, "%s of %s" % (max(p["b"] for p in first_line), inner))
    if len(first_line) == 3:
        check("%s abilities: all three side by side, reaching the tab's edge" % label,
              tab["r"] - 18 - panels[2]["r"] <= 4, "tab %s, browser %s" % (tab["r"], panels[2]["r"]))
    else:
        browser = panels[2]
        check("%s abilities: the browser wraps under them at full width" % label,
              browser["t"] > panels[0]["b"] and abs(browser["l"] - panels[0]["l"]) <= 2
              and tab["r"] - 18 - browser["r"] <= 4, browser)
    check("%s abilities: nothing spills out of a panel" % label,
          spills("#abilitiesView .abl-panel, #abilitiesView .abl-browser-panel, #abilitiesView .abl-browse-list") == 0,
          spills("#abilitiesView .abl-panel, #abilitiesView .abl-browser-panel, #abilitiesView .abl-browse-list"))
    # The floating Undo, Redo and Save stay put while this tab does not
    # scroll, so a panel under them would stay under them.
    fab = E("""(function(){ var e = $('#saveButton:visible')[0] ? $('#fabRow')[0] : null; if (!e) return null;
      var b = e.getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; })()""")
    if fab:
        under = [p for p in first_line
                 if not (p["r"] <= fab[0] or fab[2] <= p["l"] or p["b"] <= fab[1] or fab[3] <= p["t"])]
        check("%s abilities: no panel runs under the Undo, Redo and Save buttons" % label, not under,
              "button %s, panels %s" % (fab, [(p["r"], p["b"]) for p in under]))

    pager = E("""(function(){
      function box(sel){ var b = $(sel)[0].getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; }
      return [box('#ablBrowsePrev'), box('#ablBrowsePage'), box('#ablBrowseNext')]; })()""")
    check("%s abilities: the pager reads left to right, nothing on top of anything" % label,
          pager[0][2] <= pager[1][0] and pager[1][2] <= pager[2][0], pager)
    heights[label] = panels[0]["h"]
    shot("responsive-abilities-" + label)

    # ---- stronghold --------------------------------------------------------
    view("STRONGHOLD", 2.5)
    tab = boxes("#strongholdView")[0]
    rows = boxes("#shUpgrades .sh-upgrade")
    listbox = boxes(".sh-upgrades-panel")[0]
    rail = boxes(".sh-side")[0]
    columns = len([r for r in rows if r["t"] == rows[0]["t"]]) if rows else 0
    print("   %s stronghold: list %s, rail %s, %d column(s), row width %s"
          % (label, (listbox["l"], listbox["r"]), (rail["l"], rail["r"]), columns, rows[0]["w"] if rows else None))
    check("%s stronghold: nothing runs off the right edge" % label, overflow() == 0, overflow())
    check("%s stronghold: an upgrade row is no wider than 1200px" % label,
          rows and max(r["w"] for r in rows) <= 1200, rows and max(r["w"] for r in rows))
    check("%s stronghold: rows sharing a line share a height" % label,
          all(len({r["h"] for r in rows if r["t"] == t}) == 1 for t in {r["t"] for r in rows}))
    if rail["t"] < listbox["b"]:
        check("%s stronghold: list and rail span the tab" % label,
              tab["r"] - 18 - rail["r"] <= 4 and listbox["l"] - tab["l"] <= 22, (tab["r"], rail["r"]))
    else:
        check("%s stronghold: the rail stacks under the list at full width" % label,
              abs(rail["l"] - listbox["l"]) <= 2 and tab["r"] - 18 - listbox["r"] <= 4, (listbox, rail))
    shot("responsive-stronghold-" + label)

    # ---- vendors -------------------------------------------------------------
    view("VENDORS", 1.5)
    page.wait_for("$('#vndList .vnd-vendor').length > 0", 120, "the vendor list")
    time.sleep(0.5)
    tab = boxes("#vendorsView")[0]
    rail, body = boxes(".vnd-rail")[0], boxes(".vnd-body")[0]
    print("   %s vendors: rail %s, stock %s" % (label, (rail["l"], rail["r"], rail["h"]),
                                               (body["l"], body["r"], body["h"])))
    check("%s vendors: nothing runs off the right edge" % label, overflow() == 0, overflow())
    check("%s vendors: the rail and the stock side by side, one height" % label,
          rail["t"] == body["t"] and rail["r"] <= body["l"] and abs(rail["h"] - body["h"]) <= 2,
          (rail, body))
    check("%s vendors: the stock reaches the tab's edge" % label, tab["r"] - 18 - body["r"] <= 4,
          (tab["r"], body["r"]))
    check("%s vendors: both columns end inside the window" % label,
          max(rail["b"], body["b"]) <= inner, "%s of %s" % (max(rail["b"], body["b"]), inner))
    fab = E("""(function(){ var e = $('#saveButton:visible')[0] ? $('#fabRow')[0] : null; if (!e) return null;
      var b = e.getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; })()""")
    if fab:
        check("%s vendors: nothing runs under the Undo, Redo and Save buttons" % label,
              body["r"] <= fab[0] or body["b"] <= fab[1], "button %s, stock %s" % (fab, body))
    check("%s vendors: nothing spills out of a column" % label,
          spills(".vnd-rail, .vnd-body") == 0, spills(".vnd-rail, .vnd-body"))
    shot("responsive-vendors-" + label)

check("a shorter window gives the ability panels less height",
      heights["1920x800"] < heights["1920x1080"], heights)
page.call("Emulation.clearDeviceMetricsOverride")
sys.exit(summary(page))
