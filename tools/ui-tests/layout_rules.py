# Layout promises, measured on the running editor at the user's window size.
#
# Reported: the party packs stacked one to a row, and the grimoire's sort
# buttons stranded at the far edge from the spell search. The sweep that
# followed found the same two faults elsewhere -- things that belong together
# drawn far apart, and panel furniture in a different place on every tab.
import json, os, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from harness import Page, check, summary, open_save, to_list, boot

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
page = Page()
boot(page)
to_list(page)
E = page.eval

# ---- the save list ------------------------------------------------------------
# A tile is as tall as its title needs, and one test save's name takes two
# lines. Its neighbours on the same line used to hang 18px lower: the tiles
# are inline blocks, which sit on each other's baselines unless told otherwise.
page.wait_for("$('#saveBlocks .save-info').length > 0 && !Eternity.SaveSearch.state.searching", 120, "the save list")
time.sleep(1.0)
lines = json.loads(E("""JSON.stringify((function(){
  var tiles = $('#saveBlocks .save-info:visible').toArray().map(function(e){
    var b = e.getBoundingClientRect(); return {t: Math.round(b.top), b: Math.round(b.bottom)}; });
  tiles.sort(function(x, y){ return x.t - y.t; });
  var lines = [];
  tiles.forEach(function(tile){
    var line = lines[lines.length - 1];
    if (line && tile.t < line.b - 20) { line.tops.push(tile.t); line.heights.push(tile.b - tile.t); line.b = Math.max(line.b, tile.b); }
    else lines.push({b: tile.b, tops: [tile.t], heights: [tile.b - tile.t]});
  });
  return lines;
})())"""))
uneven = [line for line in lines if len(set(line["heights"])) > 1]
check("the save list has a line of tiles of different heights to measure", len(uneven) > 0,
      [line["heights"] for line in lines])
ragged = [line["tops"] for line in lines if max(line["tops"]) - min(line["tops"]) > 1]
check("save tiles on one line start at one height", not ragged, ragged)

open_save(page, SAVE)
time.sleep(1.5)

RECT = r"""(function(sel){ var el = $(sel).filter(':visible')[0]; if (!el) return null;
  var b = el.getBoundingClientRect();
  return {l: Math.round(b.left), t: Math.round(b.top), r: Math.round(b.right), b: Math.round(b.bottom),
          w: Math.round(b.width), h: Math.round(b.height)}; })(%s)"""


def rect(sel):
    return E(RECT % json.dumps(sel))


def view(name, wait=3.0):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)
    E("window.scrollTo(0, 0)")


def beside(control, anchor, most=24):
    """control starts within `most` px to the right of anchor, centred on it."""
    c, a = rect(control), rect(anchor)
    if not c or not a:
        return False, "missing %s / %s" % (control, anchor)
    dx = c["l"] - a["r"]
    dy = (c["t"] + c["b"]) / 2.0 - (a["t"] + a["b"]) / 2.0
    return 0 <= dx <= most and abs(dy) <= 8, "dx %s, dy %.0f" % (dx, dy)


# ---- inventory: party packs three to a row ----------------------------------
view("INVENTORY", 3.5)
rows = E("""$('#invPacks .inv-pack-row:visible').toArray().map(function(e){
  var b = e.getBoundingClientRect(); return [Math.round(b.left), Math.round(b.top), Math.round(b.right)]; })""")
panel = rect("#invPacks")
first = [r for r in rows if r[1] == rows[0][1]]
check("party packs: three to a row", len(first) == 3, "%d of %d on the first row" % (len(first), len(rows)))
check("party packs: every card inside the panel", all(r[2] <= panel["r"] + 1 for r in rows),
      "panel right %s, card right %s" % (panel["r"], max(r[2] for r in rows)))
check("party packs: no pack grid spills out of its card", E("""$('#invPacks .inv-grid').filter(function(){
  var g = this.getBoundingClientRect(), c = $(this).closest('.inv-pack-row')[0].getBoundingClientRect();
  return g.right > c.right + 1 || g.left < c.left - 1; }).length""") == 0)
widths = sorted(set(r[2] - r[0] for r in rows))
check("party packs: the cards line up as columns", len(widths) == 1, widths)

# ---- inventory: the paper doll as the game draws it -------------------------
# Read off the game's own inventory screen (v3.9.5, 2026-09-21): Head, Chest,
# the right-hand ring, Feet and Pet down the left of the doll; Neck, Hands, the
# left-hand ring, Waist and Grimoire down the right. The doll faces you, so the
# character's right hand is on your left. Quick items sit three to a row and
# weapon sets two by two, I and II over III and IV.
slots = E("""$('#inventoryView .inv-equip-slot:visible').toArray().map(function(e){
  var b = e.getBoundingClientRect();
  return {slot: e.getAttribute('data-slot'), l: Math.round(b.left), r: Math.round(b.right),
          t: Math.round(b.top), b: Math.round(b.bottom)}; })""")
portrait = rect("#invPortrait")
left = sorted([s for s in slots if portrait and s["r"] <= portrait["l"]], key=lambda s: s["t"])
right = sorted([s for s in slots if portrait and s["l"] >= portrait["r"]], key=lambda s: s["t"])
check("doll: left of the portrait, top to bottom, as in the game",
      [s["slot"] for s in left] == ["Head", "Chest", "RightRing", "Feet", "Pet"], [s["slot"] for s in left])
check("doll: right of the portrait, top to bottom, as in the game",
      [s["slot"] for s in right] == ["Neck", "Hands", "LeftRing", "Waist", "Grimoire"], [s["slot"] for s in right])
check("doll: no slot anywhere else", len(slots) == 10 and len(left) + len(right) == 10, len(slots))
check("doll: each side is one straight column",
      len({s["l"] for s in left}) == 1 and len({s["l"] for s in right}) == 1,
      [sorted({s["l"] for s in left}), sorted({s["l"] for s in right})])
if left and right and portrait:
    check("doll: the columns sit against the portrait",
          portrait["l"] - max(s["r"] for s in left) <= 16 and min(s["l"] for s in right) - portrait["r"] <= 16,
          "gaps %s / %s" % (portrait["l"] - max(s["r"] for s in left), min(s["l"] for s in right) - portrait["r"]))
    check("doll: the columns run the portrait's height",
          left[0]["t"] - portrait["t"] <= 12 and portrait["b"] - left[-1]["b"] <= 24,
          "top %s, bottom %s" % (left[0]["t"] - portrait["t"], portrait["b"] - left[-1]["b"]))

quick = E("""$('#invQuickSlots .inv-tile:visible').toArray().map(function(e){
  return Math.round(e.getBoundingClientRect().top); })""")
check("quick items: three to a row, as in the game", quick and quick.count(quick[0]) == 3, quick)
sets = E("""$('#invWeaponSets .inv-weapon-set:visible').toArray().map(function(e){
  var b = e.getBoundingClientRect(); return [Math.round(b.left), Math.round(b.top)]; })""")
check("weapon sets: I and II side by side, III and IV under them",
      len(sets) == 4 and sets[0][1] == sets[1][1] and sets[2][1] == sets[3][1]
      and sets[2][1] > sets[0][1] and sets[0][0] == sets[2][0] and sets[1][0] > sets[0][0], sets)
doll_panel = rect(".inv-doll-panel")
check("doll: nothing spills out of its panel", E("""$('.inv-doll-panel .inv-tile:visible').filter(function(){
  var t = this.getBoundingClientRect(), p = $('.inv-doll-panel')[0].getBoundingClientRect();
  return t.right > p.right + 1 || t.left < p.left - 1; }).length""") == 0, doll_panel)

# ---- grimoire: sort beside the spell search ---------------------------------
view("GRIMOIRE", 3.5)
ok, detail = beside("#grmSort", "#grmSearch")
check("grimoire: sort buttons beside the spell search", ok, detail)

# ---- abilities: three panels, one height ------------------------------------
# Asked for: the abilities panel as wide as the talents one, the browser moved
# up beside them, and all three the same length so the tab looks tidy.
view("ABILITIES", 3.5)
PANELS = """(function(){
  var view = $('#abilitiesView')[0].getBoundingClientRect();
  var boxes = $('#abilitiesView .abl-panel:visible, #abilitiesView .abl-browser-panel:visible').toArray()
    .map(function(e){ var b = e.getBoundingClientRect();
      return {l: Math.round(b.left), t: Math.round(b.top), r: Math.round(b.right), b: Math.round(b.bottom),
              w: Math.round(b.width), h: Math.round(b.height)}; });
  return {view: {l: Math.round(view.left), r: Math.round(view.right)}, panels: boxes,
          inner: window.innerHeight}; })()"""
layout = E(PANELS)
panels = layout["panels"]
print("   ability panels: %s" % panels)
check("abilities: three panels side by side",
      len(panels) == 3 and len({p["t"] for p in panels}) == 1
      and panels[0]["r"] <= panels[1]["l"] and panels[1]["r"] <= panels[2]["l"],
      [(p["l"], p["t"]) for p in panels])
check("abilities: the abilities panel is as wide as the talents panel",
      len(panels) >= 2 and abs(panels[0]["w"] - panels[1]["w"]) <= 2,
      [p["w"] for p in panels[:2]])
check("abilities: all three panels are the same height",
      len(panels) == 3 and max(p["h"] for p in panels) - min(p["h"] for p in panels) <= 2,
      [p["h"] for p in panels])
check("abilities: together they fill the width of the tab",
      len(panels) == 3 and layout["view"]["r"] - 18 - panels[2]["r"] <= 4
      and panels[0]["l"] - layout["view"]["l"] <= 22,
      "view %s, panels %s-%s" % (layout["view"], panels[0]["l"] if panels else None,
                                 panels[-1]["r"] if panels else None))
check("abilities: and end inside the window",
      len(panels) == 3 and max(p["b"] for p in panels) <= layout["inner"],
      "bottom %s of %s" % (max(p["b"] for p in panels) if panels else None, layout["inner"]))

ok, detail = beside("#ablBrowseSort", ".abl-search .abl-any-class", 32)
check("abilities: sort buttons in the search toolbar", ok, detail)

# The pager: the same compact buttons the inventory's pager has, and a label
# neither of them covers (the dialog-sized buttons hid part of it).
E("$('#ablBrowseSearch').val('').trigger('keyup')")
page.wait_for("$('#ablBrowseGrid .abl-row-add').length > 0", 60, "ability rows")
pager = E("""(function(){
  function box(sel){ var b = $(sel)[0].getBoundingClientRect(); return [b.left, b.top, b.right, b.bottom]; }
  return {prev: box('#ablBrowsePrev'), next: box('#ablBrowseNext'), label: box('#ablBrowsePage'),
          text: $('#ablBrowsePage').text(), font: parseFloat($('#ablBrowseNext').css('font-size')),
          invFont: parseFloat($('#invBrowseNext').css('font-size'))}; })()""")
apart = lambda a, b: a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1]
check("abilities: the pager's label is clear of both buttons",
      apart(pager["label"], pager["prev"]) and apart(pager["label"], pager["next"]), pager)
check("abilities: the pager says which of how many", re.match(r"^\d+–\d+ of \d+$", pager["text"]) is not None,
      pager["text"])
check("abilities: pager buttons the size of the inventory's", pager["font"] == pager["invFont"],
      "%s vs %s" % (pager["font"], pager["invFont"]))

E("$('#ablBrowseSearch').val('').trigger('keyup')")
page.wait_for("$('#ablBrowseGrid .abl-row-add').length > 0", 60, "ability rows")
reach = E("""(function(){ var row = $('#ablBrowseGrid .abl-row-add:visible').first();
  return Math.round(row.find('.abl-add')[0].getBoundingClientRect().left
    - row.find('.abl-text')[0].getBoundingClientRect().left); })()""")
check("abilities: a row's + button within 520px of its name", reach <= 520, "%spx" % reach)
wraps = E("""(function(){ var s = $('.abl-search')[0].getBoundingClientRect(), i = $('#ablBrowseSearch')[0].getBoundingClientRect();
  return Math.round(s.height - i.height); })()""")
check("abilities: the search toolbar is still one row", wraps <= 12, "%spx taller than the search box" % wraps)

# ---- raw and globals: a value sits near its name ----------------------------
for name, table in (("RAW", "#rawTable"), ("GLOBALS", "#globalsTable")):
    view(name, 2.5)
    key = rect("%s tbody tr:visible:not(.raw-section-header) td:first-child" % table)
    check("%s: a value is within 480px of its name" % name.lower(), key and key["w"] <= 480,
          key and "name column %spx" % key["w"])

# ---- attributes: an input sits near its label -------------------------------
view("ATTR", 2.0)
gap = E("""(function(){ var row = $('.attributes .form-group:visible').first();
  var l = row.find('label')[0].getBoundingClientRect(), i = row.find('input')[0].getBoundingClientRect();
  return Math.round(i.left - l.left); })()""")
check("attributes: an input within 200px of where its label starts", gap <= 200, "%spx" % gap)

# ---- stronghold: an upgrade's cost and button stay near its words ----------
view("STRONGHOLD", 2.5)
row = rect("#shUpgrades .sh-upgrade")
check("stronghold: an upgrade row is no wider than 1200px", row and row["w"] <= 1200,
      row and "%spx" % row["w"])

# Asked for: the upgrades panel and the rail beside it spanning the whole tab.
# The rows use the width by flowing into two columns, which is what keeps the
# rule above true on a wide screen.
span = E("""(function(){
  var view = $('#strongholdView')[0].getBoundingClientRect(),
      list = $('.sh-upgrades-panel')[0].getBoundingClientRect(),
      rail = $('.sh-side')[0].getBoundingClientRect();
  return {view: [Math.round(view.left), Math.round(view.right)],
          list: [Math.round(list.left), Math.round(list.right)],
          rail: [Math.round(rail.left), Math.round(rail.right)]}; })()""")
check("stronghold: the upgrades and the rail span the tab",
      span["list"][0] - span["view"][0] <= 22 and span["view"][1] - 18 - span["rail"][1] <= 4
      and span["rail"][0] - span["list"][1] <= 24, span)
tops = E("""$('#shUpgrades .sh-upgrade:visible').toArray().map(function(e){
  return Math.round(e.getBoundingClientRect().top); })""")
check("stronghold: the upgrades flow in two columns on a wide screen",
      len(tops) > 1 and tops.count(tops[0]) == 2, tops[:4])

# A hireling's Dismiss button sits on the same line as their name, inside the
# rail -- a float placed after the name would drop to a line of its own.
people = E("""$('#shHirelings .sh-person').toArray().map(function(e){
  var n = $(e).find('.sh-person-name')[0].getBoundingClientRect(),
      b = $(e).find('.sh-person-btn')[0].getBoundingClientRect(),
      p = $(e).closest('.sh-people-panel')[0].getBoundingClientRect();
  return {dy: Math.round(Math.abs((n.top + n.bottom) - (b.top + b.bottom)) / 2),
          inside: b.right <= p.right + 1 && n.left >= p.left - 1, gap: Math.round(b.left - n.right)}; })""")
check("stronghold: each hireling's button on their name's line",
      people and all(p["dy"] <= 3 for p in people), people)
check("stronghold: the hireling rows stay inside the rail",
      people and all(p["inside"] and p["gap"] >= 0 for p in people), people)

# ---- every tab: the panel bar in the same place ----------------------------
bars = {}
for name in ("ATTR", "RAW", "GLOBALS", "INVENTORY", "ABILITIES", "STRONGHOLD", "GRIMOIRE", "VENDORS",
             "CONSOLE"):
    view(name, 2.2)
    bar, actions = rect(".panel-bar"), rect(".panel-bar .panel-bar-actions")
    bars[name] = (bar["l"], bar["t"], actions["r"])
print("   panel bars (left, top, actions right): %s" % bars)
for index, label in ((0, "left edge"), (1, "top"), (2, "Revert/Apply right edge")):
    values = sorted(set(v[index] for v in bars.values()))
    check("every tab: panel bar %s in one place" % label, values[-1] - values[0] <= 2, values)

sys.exit(summary(page))
