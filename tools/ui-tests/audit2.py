# The second half of the sweep: every dialog opened the way a user opens it,
# with the data it would really be holding, and audited in both themes. A
# dialog shown by modal('show') alone has empty text, and empty text is what
# hid the Save dialog's unreadable file name for so long.
import base64, io, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from harness import Page, check, summary, open_save, to_list, reload_ui

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(config.OUT, "shots")
os.makedirs(OUT, exist_ok=True)
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
findings = []


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


def audit_open(page, dialog, label=None, screenshot=None):
    """Audit whatever is on screen in #dialog, in both themes."""
    label = label or dialog
    visible = page.eval("$('#%s').is(':visible')" % dialog)
    check("%s is open" % label, visible)
    if not visible:
        return

    words = len(page.eval("$('#%s').text().trim()" % dialog))
    check("%s has something in it" % label, words > 20, words)

    for light in (False, True):
        set_theme(page, light)
        theme = "light" if light else "dark"
        result = page.eval(AUDIT + "('#%s')" % dialog)
        for issue in result.get("issues", []):
            findings.append(dict(issue, where="%s (%s)" % (label, theme)))
        check("%s is clean in %s mode" % (label, theme), not result.get("issues"),
              json.dumps(result.get("issues", [])[:2]))
        if screenshot and light:
            shot(page, screenshot)
    set_theme(page, False)


def close(page, dialog):
    page.eval("$('#%s').modal('hide')" % dialog)
    time.sleep(0.9)


page = reload_ui()
page.call("Page.enable")
set_theme(page, False)
to_list(page)

# ---- the three actions on a selected save ----------------------------------
page.eval("Eternity.SaveSearch.select(0)")
time.sleep(0.6)

for action, dialog in [("saveActionRename", "renameSaveDialog"),
                       ("saveActionDelete", "deleteSaveDialog")]:
    page.eval("$('#%s').click()" % action)
    time.sleep(1.1)
    audit_open(page, dialog)
    close(page, dialog)

page.eval("$('#saveActionConvert').click()")
page.wait_for("$('#convertSaveBody .cnv-format').length > 0", 120, "the format reading")
time.sleep(0.5)
audit_open(page, "convertSaveDialog", screenshot="300-convert-light.png")
close(page, "convertSaveDialog")

# ---- settings --------------------------------------------------------------
page.eval("$('#menu-settings').click()")
time.sleep(1.2)
audit_open(page, "settingsDialog")
close(page, "settingsDialog")

# Empty here; backups_ui.py measures it again with a real backup in it.
page.eval("$('#menuBackups').click()")
page.wait_for("$('#backupsDialog').is(':visible') && !Eternity.Backups.state.loading",
              30, "the Backups dialog")
time.sleep(0.6)
audit_open(page, "backupsDialog")
close(page, "backupsDialog")

# ---- with a save open ------------------------------------------------------
open_save(page, SAVE)

for menu, dialog in [("menu-party-management", "partyManagementDialog"),
                     ("menuCurrencyEditor", "currencyDialog"),
                     ("menuDifficultyEditor", "difficultyDialog"),
                     ("menuPartyCare", "partyCareDialog"),
                     ("menu-export-character", "exportCharacterDialog")]:
    page.eval("$('#%s').click()" % menu)
    time.sleep(1.4)
    audit_open(page, dialog)
    close(page, dialog)

# ---- the save dialog, with the real file name filled in --------------------
page.eval("Eternity.Modifications.state.saveName = null; Eternity.Modifications.save()")
page.wait_for("$('#saveTargetFile').text().indexOf('working it out') < 0", 120,
              "the save target")
time.sleep(0.5)
target = page.eval("$('#saveTargetFile').text() + ' in ' + $('#saveTargetFolder').text()")
print("   save target: " + str(target))
check("the save dialog names the file it would write",
      "savegame" in str(target).lower(), target)
audit_open(page, "saveNameDialog", screenshot="301-savename-light.png")
close(page, "saveNameDialog")

# ---- unsaved-changes prompt ------------------------------------------------
page.eval("""Eternity.Modifications.transition({modifications: true});
             Eternity.Modifications.switchPrompt()""")
time.sleep(1.3)
audit_open(page, "saveChangesDialog")
close(page, "saveChangesDialog")
page.eval("Eternity.Modifications.state.switching = false;"
          "Eternity.Modifications.transition({modifications: false})")

# ---- the portrait picker, loaded from the game install ---------------------
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.ATTR)")
time.sleep(1.4)
page.eval("$('.portrait-btn').first().click()")
page.wait_for("$('#portraitDialog .ptr-tile').length > 0", 120, "the portraits")
time.sleep(0.6)
audit_open(page, "portraitDialog")
close(page, "portraitDialog")

# ---- the quantity panel, on a real stackable -------------------------------
page.eval("Eternity.SavedGame.switchView(Eternity.SavedGame.views.INVENTORY)")
time.sleep(2.2)
opened = page.eval("""(function(){
  // Only a stackable opens the panel, and a count badge is exactly what marks
  // one. The modal fades in, so the caller waits for it rather than checking
  // straight after the click.
  var tile = $('#invStashGrid .inv-tile').has('.inv-tile-count').first();
  if (!tile.length) return -1;
  tile.trigger('dblclick');
  return 1;
})()""")
page.wait_for("$('#stackDialog').is(':visible')", 30, "the quantity panel")
time.sleep(1.2)
check("a stackable item opens the quantity panel", opened >= 0, opened)
if opened >= 0:
    audit_open(page, "stackDialog")
    close(page, "stackDialog")

check("no dialog was left open",
      page.eval("$('.modal:visible').length") == 0,
      page.eval("$('.modal:visible').map(function(){return this.id;}).get().join()"))

to_list(page)
print("\n---- every finding, grouped ----")
for f in findings:
    print("  %-34s %-14s %s" % (f.get("where"), f.get("kind"), json.dumps(
        {k: v for k, v in f.items() if k not in ("where", "kind")})[:150]))

sys.exit(summary(page))
