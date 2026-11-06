# A sweep over every element of the editor: each view and each dialog, in both
# themes, checked for anything clipped, pushed off screen, too small to hit or
# faded into its background -- plus every control that leads nowhere.
import base64, io, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from harness import Page, check, summary, open_save, to_list, reload_ui

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(config.OUT, "shots")
os.makedirs(OUT, exist_ok=True)
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"

VIEWS = [("Attributes", "ATTR", "#character"),
         ("Raw", "RAW", "#rawTable"),
         ("Globals", "GLOBALS", "#globalsTable"),
         ("Inventory", "INVENTORY", "#inventoryView"),
         ("Abilities", "ABILITIES", "#abilitiesView"),
         ("Stronghold", "STRONGHOLD", "#strongholdView"),
         ("Grimoire", "GRIMOIRE", "#grimoireView"),
         ("Vendors", "VENDORS", "#vendorsView"),
         ("Console", "CONSOLE", "#consoleView")]

DIALOGS = ["settingsDialog", "saveNameDialog", "saveChangesDialog",
           "exportCharacterDialog", "renameSaveDialog", "deleteSaveDialog",
           "convertSaveDialog", "partyManagementDialog", "stackDialog",
           "importOverwriteDialog", "currencyDialog", "portraitDialog",
           "difficultyDialog", "partyCareDialog"]

findings = []


def note(where, issues):
    for issue in issues:
        findings.append(dict(issue, where=where))


def shot(page, name):
    data = page.call("Page.captureScreenshot", format="png")
    open(os.path.join(OUT, name), "wb").write(
        base64.b64decode(data.get("data") or data.get("result", {}).get("data")))


def set_theme(page, light):
    page.eval("""(function(l){
      var isLight = document.body.className.indexOf('theme-light') >= 0;
      if (isLight !== l) { $('#themeToggle').click(); }
    })(%s)""" % ("true" if light else "false"))
    time.sleep(0.6)


page = reload_ui()
page.call("Page.enable")

# ---- A. every binding in the markup actually resolves ----------------------
broken = page.eval("""JSON.stringify($('[data-bound]').map(function(){
  var id = $(this).attr('id'), to = $(this).data('bound');
  if (!id || !to) return {el: this.outerHTML.slice(0, 70), why: 'missing id or component'};
  if (!Eternity[to]) return {el: '#' + id, why: 'no component ' + to};
  if (!Eternity[to].html || !Eternity[to].html[id]) return {el: '#' + id, why: 'not bound'};
  return null;
}).get())""")
check("every data-bound element resolves to its component",
      json.loads(broken) == [], broken[:300])

ids = page.eval("""JSON.stringify((function(){
  var seen = {}, dupes = [];
  $('[id]').each(function(){
    if (seen[this.id]) dupes.push(this.id); else seen[this.id] = 1;
  });
  return dupes;
})())""")
check("no two elements share an id", json.loads(ids) == [], ids[:200])

# ---- B. the save list ------------------------------------------------------
to_list(page)
for light in (False, True):
    set_theme(page, light)
    label = "light" if light else "dark"
    result = page.eval(AUDIT + "('body')")
    note("save list (%s)" % label, result.get("issues", []))
    check("the save list is clean in %s mode" % label, not result.get("issues"),
          json.dumps(result.get("issues", [])[:2]))
    check("the save list does not scroll sideways in %s mode" % label,
          page.eval("document.documentElement.scrollWidth <= window.innerWidth + 2"),
          page.eval("document.documentElement.scrollWidth + ' vs ' + window.innerWidth"))

set_theme(page, False)
open_save(page, SAVE)

# ---- C. every view, in both themes -----------------------------------------
for label, view, selector in VIEWS:
    page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % view)
    time.sleep(1.6)
    if view == "VENDORS":
        # Read from the area files when the tab opens; measure the list,
        # not the loading message.
        page.wait_for("$('#vndList .vnd-vendor').length > 0", 120, "the vendor list")
        time.sleep(0.8)
    check("%s opens" % label, page.eval("$(%s).is(':visible')" % json.dumps(selector)))

    for light in (False, True):
        set_theme(page, light)
        theme = "light" if light else "dark"
        result = page.eval(AUDIT + "(%s)" % json.dumps(selector))
        note("%s view (%s)" % (label, theme), result.get("issues", []))
        check("%s is clean in %s mode" % (label, theme), not result.get("issues"),
              json.dumps(result.get("issues", [])[:2]))
        check("%s does not scroll sideways in %s mode" % (label, theme),
              page.eval("document.documentElement.scrollWidth <= window.innerWidth + 2"),
              page.eval("document.documentElement.scrollWidth + ' vs ' + window.innerWidth"))
    set_theme(page, False)

# ---- D. every dialog, in both themes ---------------------------------------
for dialog in DIALOGS:
    page.eval("$('#%s').modal('show')" % dialog)
    time.sleep(1.1)
    visible = page.eval("$('#%s').is(':visible')" % dialog)
    check("%s opens" % dialog, visible)
    if not visible:
        continue

    for light in (False, True):
        set_theme(page, light)
        theme = "light" if light else "dark"
        result = page.eval(AUDIT + "('#%s')" % dialog)
        note("%s (%s)" % (dialog, theme), result.get("issues", []))
        check("%s is clean in %s mode" % (dialog, theme), not result.get("issues"),
              json.dumps(result.get("issues", [])[:2]))

    set_theme(page, False)
    page.eval("$('#%s').modal('hide')" % dialog)
    time.sleep(0.9)

check("no dialog was left open",
      page.eval("$('.modal:visible').length") == 0,
      page.eval("$('.modal:visible').map(function(){return this.id;}).get().join()"))

# ---- E. every control leads somewhere --------------------------------------
dead = page.eval("""JSON.stringify($('button, a').filter(function(){
  var $e = $(this);
  if (!$e.is(':visible')) return false;
  if ($e.attr('data-dismiss') || $e.attr('data-toggle')) return false;
  var href = $e.attr('href') || '';
  if (href && href !== 'javascript:void(0);' && href.charAt(0) !== '#') return false;
  var events = $._data(this, 'events');
  if (events && (events.click || events.change || events.mousedown)) return false;
  return !$e.prop('disabled');
}).map(function(){
  return (this.id ? '#' + this.id : this.tagName.toLowerCase() + '.' + this.className)
    + ' :: ' + $(this).text().trim().slice(0, 24);
}).get())""")
check("every visible control has something bound to it",
      json.loads(dead) == [], dead[:400])

# ---- F. and nothing threw --------------------------------------------------
to_list(page)
check("nothing is left showing on the save list",
      not page.eval("$('.view, .save-only').filter(':visible').length"))

print("\n---- every finding, grouped ----")
for f in findings:
    print("  %-28s %-14s %s" % (f.get("where"), f.get("kind"), json.dumps(
        {k: v for k, v in f.items() if k not in ("where", "kind")})[:150]))

sys.exit(summary(page))
