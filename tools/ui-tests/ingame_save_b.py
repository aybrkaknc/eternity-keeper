# Save B for the in-game test: export Aloth from the Caed Nua save to a .chr,
# import him into the Cilant Lis prologue save, Save, then rename the written
# save from the save list. Everything through the editor's own flows; the two
# native file dialogs are answered by filedialog.py.
import json, os, sys, threading, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot
import filedialog

HERE = os.path.dirname(os.path.abspath(__file__))
CHR = os.path.join(config.OUT, "ingame-aloth.chr")
before_files = set(os.listdir(SAVES))
if os.path.exists(CHR):
    os.remove(CHR)

page = Page()
boot(page)
E = page.eval
D = "Eternity.SavedGame.state.saveData"
expect = {}

# ---- export Aloth ------------------------------------------------------------
to_list(page)
open_save(page, "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame")
time.sleep(1.5)
aloth = E("%s.characters.filter(function(c){ return c.name === 'Aloth'; })[0].GUID" % D)
answering = threading.Thread(target=filedialog.answer, args=("Save Character", CHR))
answering.start()
E("""window.__export = null; window.exportCharacter({request: JSON.stringify({GUID: %s,
  absolutePath: Eternity.SavedGame.state.info.absolutePath, savedYet: false}),
  onSuccess: function(r){ window.__export = 'ok'; }, onFailure: function(c, m){ window.__export = 'failed: ' + m; }})"""
  % json.dumps(aloth))
answering.join()
page.wait_for("window.__export !== null", 120, "the export")
check("Aloth exported to a .chr", E("window.__export") == "ok" and os.path.exists(CHR),
      "%s, %s bytes" % (E("window.__export"), os.path.getsize(CHR) if os.path.exists(CHR) else 0))

# ---- import him into the prologue --------------------------------------------
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
open_save(page, "cf88c16dc9564d77a49e89c8894d5c4e 7763974 CilantLs.savegame")
time.sleep(1.5)
names_before = E("%s.characters.map(function(c){ return c.name; })" % D)
answering = threading.Thread(target=filedialog.answer, args=("Choose a character", CHR))
answering.start()
E("Eternity.ImportCharacter.importCharacter()")
answering.join()
page.wait_for("%s.characters.length > %d || $('#importOverwriteDialog').is(':visible')" % (D, len(names_before)),
              300, "the import")
time.sleep(1.5)
names_after = E("%s.characters.map(function(c){ return c.name; })" % D)
check("Aloth imported into the prologue save", "Aloth" in names_after and "Aloth" not in names_before,
      names_after)
problems = E("JSON.stringify(((%s.validation || {}).problems || []))" % D)
check("the working save validates clean after the import", problems == "[]", problems)
expect["prologue party before"] = names_before
expect["prologue characters after"] = names_after

E("Eternity.Modifications.state.saveName = 'EK import test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)

# ---- rename it from the save list --------------------------------------------
E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")
E("Eternity.SaveSearch.search()")
page.wait_for("!Eternity.SaveSearch.state.searching && Eternity.SaveSearch.state.saves.some(function(s){"
              " return s.absolutePath.indexOf(%s) >= 0; })" % json.dumps(written[0]), 300, "the written save")
index = E("""(function(){ var i = -1; Eternity.SaveSearch.state.saves.forEach(function(s, n){
  if (s.absolutePath.indexOf(%s) >= 0) i = n; }); return i; })()""" % json.dumps(written[0]))
E("Eternity.SaveSearch.transition({selected: %d}); Eternity.SaveSearch.renamePrompt()" % index)
time.sleep(1.5)
E("$('#renameSaveInput').val('EK import test, renamed'); Eternity.SaveSearch.renameConfirmed()")
page.wait_for("!$('#renameSaveDialog').is(':visible')", 120, "the rename")
time.sleep(1.0)
renamed = E("Eternity.SaveSearch.state.saves[%d].userSaveName" % index)
check("the save list shows the new name", renamed == "EK import test, renamed", renamed)

expect["file"] = written[0]
expect["renamed to"] = renamed
with open(os.path.join(HERE, "ingame-expected-b.json"), "w", encoding="utf-8") as f:
    json.dump(expect, f, indent=1, ensure_ascii=False)
print(json.dumps(expect, indent=1, ensure_ascii=False))
sys.exit(summary(page))
