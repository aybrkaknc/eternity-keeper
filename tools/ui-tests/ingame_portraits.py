# The load list's party thumbnails (0.png…5.png inside a save) follow an
# edit. Gives the Watcher the Grieving Mother's portrait and sends Durance to
# the stronghold, saves, and checks the written file: four thumbnails, in
# party order, each one the right face. The save stays in the test-env saves
# folder for loading in the game ("EK portraits test").
import io, json, os, sys, time, zipfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot
from PIL import Image, ImageChops, ImageStat

SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
PORTRAITS = os.path.join(os.path.dirname(SAVES), "poe", "PillarsOfEternity_Data",
                         "data", "art", "gui", "portraits")
before_files = set(os.listdir(SAVES))

page = Page()
boot(page)
to_list(page)
open_save(page, SAVE)
time.sleep(1.5)
E = page.eval
CHARS = "Eternity.SavedGame.state.saveData.characters"
PLAYER = "(%s.filter(function(c){ return c.isMainCharacter; })[0])" % CHARS

player_guid = E(PLAYER + ".GUID")
party_before = E("%s.filter(function(c){ return c.inParty && !c.resurrectable; }).map(function(c){ return c.name; })" % CHARS)
check("the save's party is the Watcher and four companions", len(party_before) == 5, party_before)

# ---- the Watcher gets the Grieving Mother's face -----------------------------
E("""window.__p = null; window.browsePortraits({request: JSON.stringify({category: 'companion', offset: 0, limit: 40}),
  onSuccess: function(r){ window.__p = JSON.parse(r); }, onFailure: function(){ window.__p = {portraits: []}; }})""")
page.wait_for("window.__p !== null", 60, "portraits")
chosen = E("""(function(){ var p = window.__p.portraits.filter(function(x){
  return /grieving_mother/.test(x.large); })[0];
  Eternity.PortraitPicker.open(%s); Eternity.PortraitPicker.choose(p); return p.small; })()"""
           % json.dumps(player_guid))
time.sleep(1.5)
check("the portrait was picked", chosen and "grieving_mother" in chosen, chosen)

# ---- Durance goes to the stronghold -------------------------------------------
# By portrait rather than name: the test saves are Polish, where Durance is "Niezłomny".
DURANCE = ("(%s.filter(function(c){ return /portrait_durance/.test(((c.portraitPaths || {})"
           ".m_textureSmallPath || {}).value || ''); })[0] || {})" % CHARS)
durance = E(DURANCE + ".GUID")
check("Durance is found", bool(durance), durance)
E("Eternity.PartyManagement.open()")
time.sleep(1.5)
E("Eternity.PartyManagement.toggle(%s); Eternity.PartyManagement.accept()" % json.dumps(durance))
page.wait_for("!$('#partyManagementDialog').is(':visible')", 300, "the party change")
page.wait_for("!" + DURANCE + ".inParty", 300, "Durance to leave the party")
time.sleep(1.5)

E("Eternity.Modifications.state.saveName = 'EK portraits test'; Eternity.Modifications.save()")
page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving", 600, "the Save")
time.sleep(1.0)
written = sorted(set(os.listdir(SAVES)) - before_files)
check("Save wrote one new file", len(written) == 1, written)

# ---- the thumbnails inside it ---------------------------------------------------
def closest(image):
    best = None
    for root, _, names in os.walk(PORTRAITS):
        for name in names:
            if not name.endswith("_sm.png"):
                continue
            source = Image.open(os.path.join(root, name)).convert("RGBA").resize(image.size, Image.BILINEAR)
            difference = sum(ImageStat.Stat(ImageChops.difference(image, source)).mean)
            if best is None or difference < best[0]:
                best = (difference, name)
    return best[1]

if written:
    with zipfile.ZipFile(os.path.join(SAVES, written[0])) as archive:
        names = set(archive.namelist())
        thumbnails = sorted(n for n in names if n[:-4].isdigit() and n.endswith(".png"))
        faces = [closest(Image.open(io.BytesIO(archive.read(n))).convert("RGBA")) for n in thumbnails]
        sizes = {Image.open(io.BytesIO(archive.read(n))).size for n in thumbnails}
    check("one thumbnail per member of the new party", thumbnails == ["0.png", "1.png", "2.png", "3.png"], thumbnails)
    check("each 32x41, like the game's", sizes == {(32, 41)}, sizes)
    check("in party order, with the new face first", faces == [
        "portrait_grieving_mother_sm.png", "portrait_aloth_sm.png",
        "portrait_pallegina_sm.png", "portrait_sagani_sm.png"], faces)

    # The editor's own save list draws its tiles from the same files.
    to_list(page)
    E("Eternity.SaveSearch.search()")
    page.wait_for("Eternity.SaveSearch.state.saves.some(function(s){ return (s.absolutePath || '').indexOf(%s) >= 0; })"
                  % json.dumps(written[0]), 120, "the list")
    tile = E("(Eternity.SaveSearch.state.saves.filter(function(s){ return (s.absolutePath || '').indexOf(%s) >= 0; })[0] || {}).portraits.length"
             % json.dumps(written[0]))
    check("the save list's tile shows four faces", tile == 4, tile)

print(json.dumps({"file": written[0] if written else None}))
sys.exit(summary(page))
