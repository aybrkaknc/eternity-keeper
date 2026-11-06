# The save-format dialog, end to end against the test environment: what the
# save is, what conversion would write, and what it actually wrote.
import base64, io, json, os, shutil, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, to_list, reload_ui

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(config.OUT, "shots")
os.makedirs(OUT, exist_ok=True)
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()

CONVERTED = os.path.join(SAVES, "converted")
SAVE = "cf88c16dc9564d77a49e89c8894d5c4e 1 CilantLs.savegame"


def shot(page, name):
    data = page.call("Page.captureScreenshot", format="png")
    open(os.path.join(OUT, name), "wb").write(
        base64.b64decode(data.get("data") or data.get("result", {}).get("data")))


# Start from nothing converted.
shutil.rmtree(CONVERTED, ignore_errors=True)

page = reload_ui()
page.call("Page.enable")
page.eval("if (document.body.className.indexOf('theme-light') >= 0) "
          "$('#themeToggle').click();")
to_list(page)

# ---- the action only appears for a selected save ---------------------------
check("the format action is hidden with nothing selected",
      not page.eval("$('#saveActionConvert').is(':visible')"))

index = page.eval("""(function(){
  var found = -1;
  Eternity.SaveSearch.state.saves.forEach(function(s, i){
    if ((s.absolutePath || '').indexOf(%s) >= 0) found = i;
  });
  return found;
})()""" % json.dumps(SAVE))
check("the test save is listed", index >= 0, index)

page.eval("Eternity.SaveSearch.select(%d)" % index)
time.sleep(0.8)
check("selecting a save reveals the format action",
      page.eval("$('#saveActionConvert').is(':visible')"))

# ---- what it says the save is ----------------------------------------------
page.eval("$('#saveActionConvert').click()")
page.wait_for("$('#convertSaveBody .cnv-format').length > 0", 60,
              "the format reading")
time.sleep(0.4)

heading = page.eval("$('#convertSaveBody .cnv-format').text()")
print("   heading: " + str(heading))
check("it names the newer format", "CoreModule" in str(heading), heading)
check("it names the save it is talking about",
      len(page.eval("$('#convertSaveDialog .pm-dialog-subject').text()")) > 0)

destination = page.eval("$('#convertSaveBody .cnv-path').text()")
print("   destination: " + str(destination))
check("it says where the copy would go",
      destination.endswith(SAVE) and "converted" in destination, destination)
check("and that the original is left alone",
      "not touched" in page.eval("$('#convertSaveBody').text()"))
check("the Convert button is available",
      not page.eval("$('#convertSaveConfirm').prop('disabled')"))

result = page.eval(AUDIT + "('#convertSaveDialog')")
check("the dialog lays out cleanly", not result.get("issues"),
      json.dumps(result.get("issues", [])[:3]))
shot(page, "200-format-dialog.png")

# ---- converting -------------------------------------------------------------
before = os.path.getsize(os.path.join(SAVES, SAVE))
page.eval("$('#convertSaveConfirm').click()")
page.wait_for("$('#convertSaveBody .cnv-format').text().indexOf('Converted') >= 0",
              300, "the conversion")
time.sleep(0.5)

report = page.eval("$('#convertSaveBody').text()")
print("   " + " ".join(report.split())[:220])
check("it reports how many type names moved", "type names rewritten" in report, report[:120])
check("and how long it took", "seconds" in report, report[:120])
shot(page, "201-format-converted.png")

check("the converted file is on disk", os.path.isfile(os.path.join(CONVERTED, SAVE)))
check("the original save is untouched",
      os.path.getsize(os.path.join(SAVES, SAVE)) == before)

import zipfile
with zipfile.ZipFile(os.path.join(CONVERTED, SAVE)) as z:
    world = z.read("MobileObjects.save")
with zipfile.ZipFile(os.path.join(SAVES, SAVE)) as z:
    original = z.read("MobileObjects.save")
check("the copy no longer names CoreModule",
      world.count(b"UnityEngine.CoreModule") == 0,
      world.count(b"UnityEngine.CoreModule"))
check("but the original still does",
      original.count(b"UnityEngine.CoreModule") > 0,
      original.count(b"UnityEngine.CoreModule"))
check("and it shrank by exactly 11 bytes per name",
      (len(original) - len(world)) == 11 * original.count(b"UnityEngine.CoreModule"),
      "%d vs %d" % (len(original) - len(world),
                    11 * original.count(b"UnityEngine.CoreModule")))

# ---- asking a second time ---------------------------------------------------
page.eval("$('#convertSaveDialog').modal('hide')")
time.sleep(0.8)
page.eval("$('#saveActionConvert').click()")
page.wait_for("$('#convertSaveBody .cnv-format').length > 0", 60, "the second reading")
time.sleep(0.4)
check("it notices the copy already made",
      "already a converted copy" in page.eval("$('#convertSaveBody').text()"),
      page.eval("$('#convertSaveBody').text()")[:160])
check("and will not write over it",
      page.eval("$('#convertSaveConfirm').prop('disabled')"))

page.eval("$('#convertSaveDialog').modal('hide')")
time.sleep(0.8)

# ---- the converted folder must not pollute the list ------------------------
page.eval("Eternity.SaveSearch.search()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 300, "the save list")
time.sleep(1.0)
check("the converted folder is not listed as a save",
      page.eval("$('#saveBlocks .save-info').length") == 12,
      page.eval("$('#saveBlocks .save-info').length"))

# ---- and the dialog is not a view ------------------------------------------
check("the dialog is not one of the editor views",
      not page.eval("$('#convertSaveDialog').hasClass('view')"))
check("nothing is left showing on the list",
      not page.eval("$('.view, .save-only').filter(':visible').length"))

shutil.rmtree(CONVERTED, ignore_errors=True)
sys.exit(summary(page))
