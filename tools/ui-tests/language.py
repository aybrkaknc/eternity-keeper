# The game's own names in the language the game is played in.
#
# The editor showed every item, ability, upgrade and hireling in English,
# whatever language the player's game is set to. Settings now offers the
# languages the install has text for, and "the same as the game" follows the
# game's own setting. This drives the choice through the Settings dialog and
# checks the names against the game's own tables, read here independently:
# the catalog's ID for a name, looked up in that language's string table.
#
# The suites are pinned to English (config.SETTINGS), so this one chooses
# Polish, looks, saves a save in it, and goes back.
import io, json, os, re, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, open_save, to_list, boot

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()
SAVE = "0945952c89c640e4a18cdb293e3946b4 32111932 CaedNua.savegame"
D = "Eternity.SavedGame.state.saveData"
TEXT = os.path.join(config.SETTINGS["gameLocation"], "PillarsOfEternity_Data", "data", "localized")
# DatabaseString.StringTableType: the number a catalog keeps for each table.
TABLES = {
    1: "gui", 4: "characters", 5: "items", 6: "abilities", 7: "tutorial",
    9: "areanotifications", 10: "interactables", 12: "debug", 13: "recipes",
    14: "factions", 15: "loadingtips", 16: "itemmods", 17: "maps",
    19: "afflictions", 20: "backercontent", 900: "cyclopedia",
    938: "stronghold", 942: "backstory",
}

before_files = set(os.listdir(SAVES))
page = Page()
boot(page)
E = page.eval

ENTRY = re.compile(r"<ID>(\d+)</ID>\s*<DefaultText>(.*?)</DefaultText>", re.S)
_tables = {}


def table(language, number):
    key = (language, number)
    if key not in _tables:
        path = os.path.join(TEXT, language, "text", "game", TABLES[number] + ".stringtable")
        entries = {}
        if os.path.isfile(path):
            with io.open(path, encoding="utf-8-sig") as handle:
                for sid, text in ENTRY.findall(handle.read()):
                    for a, b in (("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"), ("&quot;", '"'), ("&apos;", "'")):
                        text = text.replace(a, b)
                    entries[int(sid)] = text.strip()
        _tables[key] = entries
    return _tables[key]


def said(language, entry, field="name"):
    """What the game's own table in `language` calls a catalog entry."""
    source = entry.get(field + "Id")
    word = table(language, source[0]).get(source[1]) if source else None
    return word or entry[field]


with io.open(os.path.join(config.GAMEDATA, "catalog.json"), encoding="utf-8") as handle:
    ITEMS = json.load(handle)
with io.open(os.path.join(config.GAMEDATA, "stronghold.json"), encoding="utf-8") as handle:
    UPGRADES = json.load(handle)["upgrades"]
with io.open(os.path.join(config.GAMEDATA, "abilities.json"), encoding="utf-8") as handle:
    ABILITIES = json.load(handle)


def ask(handler, request):
    E("""window.__reply = null; window.%s({request: %s,
      onSuccess: function(r){ window.__reply = r; },
      onFailure: function(c, m){ window.__reply = JSON.stringify({error: m}); }})""" % (
        handler, json.dumps(json.dumps(request))))
    page.wait_for("window.__reply !== null", 60, handler)
    return json.loads(E("window.__reply"))


def leave():
    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")


def open_settings():
    E("$('#settingsDialog').modal('show')")
    page.wait_for("$('#settingsDialog').is(':visible') && $('#settingsLanguage').is(':visible')"
                  " && $('#gameLanguage option').length > 1", 30, "the language choice")
    time.sleep(0.6)


def choose(code):
    open_settings()
    E("$('#gameLanguage').val(%s).change()" % json.dumps(code))
    time.sleep(0.3)
    E("$('#saveSettings').click()")
    page.wait_for("!$('#settingsDialog').is(':visible')", 30, "the settings to be saved")
    time.sleep(0.8)


def view(name, wait=3.0):
    E("Eternity.SavedGame.switchView(Eternity.SavedGame.views.%s)" % name)
    time.sleep(wait)


def carried():
    """Every item the party carries or keeps: {catalog key: name shown}."""
    return json.loads(E("""JSON.stringify((function(){ var seen = {};
      function take(container) { ((container || {}).items || []).forEach(function(i){
        if (i.key) seen[i.key] = i.displayName; }); }
      var inventory = %s.inventory;
      inventory.characters.forEach(function(c){ take(c.pack); take(c.quickbar); });
      take(inventory.stash);
      return seen; })())""" % D))


def upgrades_shown():
    return E("$('#strongholdView .sh-upgrade-name:visible').map(function(){ return $(this).text(); }).get()")


def abilities_shown():
    return E("$('#abilitiesView .abl-name:visible').map(function(){ return $(this).text(); }).get()")


# ---- what Settings offers -------------------------------------------------------
to_list(page)
open_settings()
offered = E("$('#gameLanguage option').map(function(){ return {value: this.value, text: $(this).text()}; }).get()")
check("the first choice is to follow the game", offered and offered[0]["value"] == ""
      and offered[0]["text"].startswith("The same as the game"), offered[:1])
check("then every language the install has, each by its own name",
      [o["value"] for o in offered[1:]][0] == "en"
      and {"Polski", "Deutsch", "English", "Français"} <= {o["text"] for o in offered},
      [o["text"] for o in offered])
check("the suites' own choice, English, is the one selected", E("$('#gameLanguage').val()") == "en",
      E("$('#gameLanguage').val()"))
check("the note says when it takes effect", "saves opened from now on" in E("$('#gameLanguageNote').text()"),
      E("$('#gameLanguageNote').text()"))
issues = E(AUDIT + "('#settingsDialog .modal-content')").get("issues", [])
check("nothing in the dialog is clipped, off it or too faint to read", not issues, issues[:4])
box = E("""(function(){ var d = $('#settingsDialog .modal-content')[0].getBoundingClientRect(),
  s = $('#gameLanguage')[0].getBoundingClientRect(), n = $('#gameLanguageNote')[0].getBoundingClientRect();
  return {inside: s.left >= d.left && s.right <= d.right && n.right <= d.right && n.bottom <= d.bottom,
          select: Math.round(s.width), note: Math.round(n.height)}; })()""")
check("the choice and its note sit inside the dialog", box["inside"] and box["select"] >= 200, box)
# Game data read by an earlier version has no record of where its names came
# from, and can only be shown in English until it is read again. The dialog
# is handed that answer here, since the data on this machine is new.
described = E("JSON.stringify(Eternity.Settings.state.languages)")
E("""Eternity.Settings.transition({language: 'pl', languages: $.extend({}, %s,
  {catalogued: true, localizable: false})})""" % described)
time.sleep(0.3)
check("data read before the names could be translated is said to need reading again",
      "read it again" in E("$('#gameLanguageNote').text()") and "Polski" in E("$('#gameLanguageNote').text()"),
      E("$('#gameLanguageNote').text()"))
E("Eternity.Settings.transition({language: null, languages: %s})" % described)
time.sleep(0.3)
check("and with data that can be, nothing of the kind", "read it again" not in E("$('#gameLanguageNote').text()"),
      E("$('#gameLanguageNote').text()"))
E("$('#settingsDialog').modal('hide')")
time.sleep(0.6)

# ---- English first ----------------------------------------------------------------
open_save(page, SAVE)
time.sleep(1.5)
english_items = carried()
check("in English every item carries the catalog's own name",
      english_items and all(name == ITEMS[key]["name"] for key, name in english_items.items() if key in ITEMS),
      [(k, v) for k, v in english_items.items() if k in ITEMS and v != ITEMS[k]["name"]][:3])
view("STRONGHOLD")
english_upgrades = upgrades_shown()
view("ABILITIES")
english_abilities = abilities_shown()
leave()

# ---- Polish -----------------------------------------------------------------------
choose("pl")
described = ask("getLanguages", True)
check("Settings holds the choice", described.get("chosen") == "pl" and described.get("shown") == "pl", described)
check("the game data that was read can be shown in it", described.get("localizable") is True, described)

open_save(page, SAVE)
time.sleep(1.5)
polish_items = carried()
expected = {key: said("pl", ITEMS[key]) for key in polish_items if key in ITEMS}
wrong = [(key, polish_items[key], expected[key]) for key in expected if polish_items[key] != expected[key]]
check("every item is named as the game's Polish table names it", expected and not wrong, wrong[:4])
changed = [key for key in expected if polish_items[key] != english_items.get(key)]
check("which for most of them is not the English name", len(changed) > len(expected) * 0.6,
      "%d of %d" % (len(changed), len(expected)))
print("   e.g. %s" % ", ".join("%s -> %s" % (english_items[k], polish_items[k]) for k in sorted(changed)[:3]))

view("STRONGHOLD")
polish_upgrades = upgrades_shown()
wanted = sorted(said("pl", upgrade) for upgrade in UPGRADES.values())
check("the keep's upgrades carry their Polish names", sorted(polish_upgrades) == wanted
      and polish_upgrades != english_upgrades, (polish_upgrades[:3], wanted[:3]))
description = E("$('#strongholdView .sh-upgrade-desc:visible').first().text()")
check("and their Polish descriptions", description in {said("pl", u, "description") for u in UPGRADES.values()},
      description[:80])
hirelings = E("(%s.stronghold.hirelings || []).map(function(h){ return h.name; })" % D)
polish_characters = set(table("pl", 4).values())
check("the hirelings are named in Polish too", hirelings and all(name in polish_characters for name in hirelings),
      hirelings)

view("ABILITIES")
polish_abilities = abilities_shown()
known = {said("pl", ability) for ability in ABILITIES.values()}
check("what the character knows is listed in Polish", polish_abilities and polish_abilities != english_abilities
      and all(name in known for name in polish_abilities),
      [name for name in polish_abilities if name not in known][:4])

# The name on screen is the name things are found by.
sample = sorted(changed, key=lambda key: -len(polish_items[key]))[0]
needle = polish_items[sample][:8].lower()
found = ask("browseItems", {"search": needle, "offset": 0, "limit": 200})
check("the item browser finds an item by its Polish name",
      any(item.get("key") == sample for item in found.get("items", [])), (needle, found.get("total")))
E("$('#finderInput').focus().val(%s).trigger('input')" % json.dumps(polish_items[sample]))
time.sleep(0.5)
rows = E("$('#finderResults .finder-row.finder-item .finder-label').map(function(){ return $(this).text(); }).get()")
check("and so does Find anything", polish_items[sample] in rows, rows[:4])
E("$('#finderInput').val('').trigger('input').blur()")

# ---- a Save made in Polish changes the edit and nothing else ------------------------
money = float(E(D + ".currency"))
E("%s.currency = %d; Eternity.Modifications.transition({modifications: true})" % (D, money + 777))
E("Eternity.Modifications.state.saveName = 'EK language check'; Eternity.Modifications.save()")
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
    def index_of(needle):
        return E("""(function(n){ var hit = -1; Eternity.SaveSearch.state.saves.forEach(function(s, i){
          if (s.absolutePath.indexOf(n) >= 0) hit = i; }); return hit; })(%s)""" % json.dumps(needle))

    def wait_results():
        page.wait_for("!Eternity.CompareSaves.state.loading && ($('#compareResults .cmp-headline').length > 0"
                      " || $('#compareResults .cmp-problem').length > 0)", 300, "the comparison")
        time.sleep(0.3)

    E("Eternity.SaveSearch.transition({selected: %d})" % index_of(written[0]))
    time.sleep(0.3)
    E("$('#saveActionCompare').click()")
    page.wait_for("$('#compareSavesDialog').is(':visible')", 20, "the comparison")
    wait_results()
    # Three test saves were made at the same moment of this game, so the save
    # this one was made from is named rather than left to the dialog's guess.
    E("$('#compareBefore').val(%d).change()" % index_of(SAVE))
    time.sleep(0.2)
    E("$('#compareAfter').val(%d).change()" % index_of(written[0]))
    wait_results()
    sections = E("$('#compareResults .cmp-section').map(function(){ return $(this).attr('data-section'); }).get()")
    labels = E("$('#compareResults .cmp-section tr').not('.cmp-row-note').map(function(){"
               " return $(this).children('td').eq(0).text(); }).get()")
    check("compared with the save it was made from, only the money and the name differ",
          sections == ["save"] and sorted(labels) == ["Money", "Name in the load list"], (sections, labels))
    E("$('#compareSavesDialog').modal('hide')")
    time.sleep(0.5)
else:
    leave()

# ---- and back -----------------------------------------------------------------------
choose("en")
open_save(page, SAVE)
time.sleep(1.5)
again = carried()
check("back in English every name is the catalog's own again", again == english_items,
      [(k, again.get(k), english_items[k]) for k in english_items if again.get(k) != english_items[k]][:3])
leave()

for name in written:
    os.remove(os.path.join(SAVES, name))
check("the test saves folder is as it was", set(os.listdir(SAVES)) == before_files)
sys.exit(summary(page))
