# The save list beside files that are not saves, in the running editor: a file
# cut short, noise called .savegame and a save whose summary has nothing in it
# cost their own tiles and nothing else, the list names each with the reason,
# a real save beside them still opens and saves -- under a name no file in the
# folder already has -- and a search that finds nothing leaves no tiles behind.
#
# Works beside the test environment's twelve saves and never touches them;
# what it adds is removed at the end.
import hashlib, io, json, os, shutil, sys, tempfile, time, zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from config import SAVES
from harness import Page, check, summary, boot, open_save

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()
SESSION = "cf88c16dc9564d77a49e89c8894d5c4e"
SOURCE = SESSION + " 1 CilantLs.savegame"
NOISE = "deadbeefdeadbeefdeadbeefdeadbeef 9001 Noise.savegame"
SHORT = "deadbeefdeadbeefdeadbeefdeadbeef 9002 Short.savegame"
BARE = "deadbeefdeadbeefdeadbeefdeadbeef 9003 Bare.savegame"
ONE_WORD = "renamed-by-hand.savegame"
ADDED = [NOISE, SHORT, BARE, ONE_WORD]


def md5(path):
    with open(path, "rb") as handle:
        return hashlib.md5(handle.read()).hexdigest()


def plant():
    source = os.path.join(SAVES, SOURCE)
    with open(source, "rb") as handle:
        whole = handle.read()

    with open(os.path.join(SAVES, NOISE), "wb") as handle:
        handle.write(os.urandom(4096))
    with open(os.path.join(SAVES, SHORT), "wb") as handle:
        handle.write(whole[:len(whole) // 2])

    # A save in every way but its summary, which names nothing.
    with zipfile.ZipFile(source) as real, \
            zipfile.ZipFile(os.path.join(SAVES, BARE), "w", zipfile.ZIP_DEFLATED) as bare:
        for name in ("screenshot.png", "0.png"):
            bare.writestr(name, real.read(name))
        bare.writestr("saveinfo.xml", '<Complex name="Root"/>')

    shutil.copyfile(source, os.path.join(SAVES, ONE_WORD))


def clear():
    for name in os.listdir(SAVES):
        if name in ADDED:
            os.remove(os.path.join(SAVES, name))


def search(page, folder=None):
    if folder is not None:
        page.eval("$('#savedGameLocation').val(%s)" % json.dumps(folder))
    page.eval("window.__searched = false; Eternity.SaveSearch.search();"
              " window.__searched = true")
    time.sleep(0.4)
    page.wait_for("window.__searched && !Eternity.SaveSearch.state.searching", 300, "the search")
    time.sleep(0.5)


def listed(page):
    return page.eval("Eternity.SaveSearch.state.saves.map(function(s){"
                     " return (s.absolutePath || '').split(/[\\\\/]/).pop(); })")


def note(page):
    return page.eval("""(function(){ var n = $('#unreadableSaves'); return {
        shown: n.is(':visible'), head: n.find('.us-head').text(),
        files: n.find('.us-file').map(function(){ return $(this).text(); }).get(),
        names: n.find('.us-file b').map(function(){ return $(this).text(); }).get(),
        more: n.find('.us-more').text()}; })()""")


def target(page):
    page.eval("""window.__target = null; window.saveTarget({
        request: JSON.stringify({action: 'preview',
                                 oldSave: Eternity.SavedGame.state.info.absolutePath}),
        onSuccess: function (r) { window.__target = JSON.parse(r); },
        onFailure: function (c, m) { window.__target = {error: m}; }})""")
    page.wait_for("!!window.__target", 30, "the save target")
    return page.eval("window.__target")


clear()
before = sorted(os.listdir(SAVES))
sums = {name: md5(os.path.join(SAVES, name)) for name in before}
empty = tempfile.mkdtemp(prefix="ek-ui-empty-")

page = Page()
page.call("Page.enable")
E = page.eval
try:
    boot(page)
    real = sorted(listed(page))
    check("the list starts with the twelve test saves", len(real) == 12, len(real))
    check("and no note about anything left out", not note(page)["shown"], note(page))

    # ---- four files that are not saves ---------------------------------------
    plant()
    search(page)
    check("with four damaged files beside them, the same twelve are listed",
          sorted(listed(page)) == real, sorted(set(listed(page)) ^ set(real)))
    said = note(page)
    check("the list says four files were left out", said["shown"] and said["head"].startswith("4 files"),
          said["head"])
    check("and names every one", sorted(said["names"]) == sorted(ADDED), said["names"])
    reasons = dict((line.split(" — ")[0], line.split(" — ")[-1]) for line in said["files"])
    check("noise is a file that could not be unpacked",
          "could not be unpacked" in reasons.get(NOISE, ""), reasons.get(NOISE))
    check("so is a save cut short", "could not be unpacked" in reasons.get(SHORT, ""), reasons.get(SHORT))
    check("an empty summary is named as that", "saveinfo.xml" in reasons.get(BARE, ""), reasons.get(BARE))
    check("a file renamed to one word is told about its name",
          "name" in reasons.get(ONE_WORD, ""), reasons.get(ONE_WORD))
    check("no error banner: the search itself worked", not E("$('#error').is(':visible')"),
          E("$('#error').text()"))

    for light in (False, True):
        E("""(function(l){ var isLight = document.body.className.indexOf('theme-light') >= 0;
          if (isLight !== l) { $('#themeToggle').click(); } })(%s)""" % ("true" if light else "false"))
        time.sleep(0.6)
        issues = E(AUDIT + "('#unreadableSaves')").get("issues", [])
        check("the note is clean in %s mode" % ("light" if light else "dark"), not issues,
              json.dumps(issues[:2]))
    E("(function(){ if (document.body.className.indexOf('theme-light') >= 0) $('#themeToggle').click(); })()")

    # ---- the saves beside them still open, and Save takes no name in use ------
    count = open_save(page, SOURCE)
    check("a real save beside them opens", count > 0, count)
    first = target(page)
    wanted = first.get("fileName", "")
    check("Save would use the first number free in the playthrough",
          wanted == SESSION + " 4 CilantLs.savegame", first)

    # A file turns up under that very name after the list was read: put back
    # from a backup, copied in by hand. It is not in the list and never will
    # be -- it is noise -- so only the folder knows the name is taken.
    squatter = os.path.join(SAVES, wanted)
    with open(squatter, "wb") as handle:
        handle.write(b"somebody's file, not a save")
    kept = md5(squatter)
    second = target(page)
    check("with a file already under that name, Save moves to the next",
          second.get("fileName") == SESSION + " 5 CilantLs.savegame", second)

    E("""Eternity.SavedGame.state.saveData.currency =
           Number(Eternity.SavedGame.state.saveData.currency) + 1;
         Eternity.Modifications.transition({modifications: true});
         Eternity.Modifications.state.saveName = 'EK damaged check';
         Eternity.Modifications.save()""")
    page.wait_for("Eternity.Modifications.state.savedYet && !Eternity.Modifications.state.saving",
                  300, "the Save")
    time.sleep(1.0)
    written = os.path.join(SAVES, SESSION + " 5 CilantLs.savegame")
    check("the file that was there is untouched", os.path.isfile(squatter) and md5(squatter) == kept)
    check("and the new save is beside it, a whole save",
          os.path.isfile(written) and zipfile.is_zipfile(written)
          and "MobileObjects.save" in zipfile.ZipFile(written).namelist(),
          os.path.isfile(written))
    for path in (squatter, written):
        if os.path.isfile(path):
            os.remove(path)

    E("Eternity.Modifications.state.switching = true; Eternity.Modifications.discardChanges()")
    page.wait_for("$('#saveBlocks .save-info').length > 0", 60, "the save list")

    # ---- a search that finds nothing ------------------------------------------
    search(page, empty)
    check("an empty folder says no saves were found",
          E("$('#error').is(':visible')") and "No saves found" in E("$('#error').text()"),
          E("$('#error').text()"))
    check("and leaves no tile of the last search behind", E("$('#saveBlocks .save-info').length") == 0,
          E("$('#saveBlocks .save-info').length"))
    check("nor a note about files that are not there", not note(page)["shown"], note(page))

    # A folder holding nothing but a damaged file: no saves, and the reason.
    shutil.copyfile(os.path.join(SAVES, NOISE), os.path.join(empty, NOISE))
    search(page, empty)
    said = note(page)
    check("a folder of nothing but a damaged file says which and why",
          said["shown"] and said["head"].startswith("One file") and said["names"] == [NOISE], said)

    # ---- and with them gone, nothing is said ----------------------------------
    clear()
    search(page, SAVES)
    check("with the damaged files gone the twelve are back", sorted(listed(page)) == real,
          sorted(set(listed(page)) ^ set(real)))
    check("and the note is gone", not note(page)["shown"], note(page))
    check("the error from the empty folder is gone too", not E("$('#error').is(':visible')"),
          E("$('#error').text()"))
finally:
    clear()
    shutil.rmtree(empty, ignore_errors=True)
    for name in os.listdir(SAVES):
        if name not in before:
            os.remove(os.path.join(SAVES, name))

after = sorted(os.listdir(SAVES))
check("the saves folder holds what it held", after == before, sorted(set(after) ^ set(before)))
check("and every file is as it was", all(md5(os.path.join(SAVES, n)) == sums[n] for n in before))
sys.exit(1 if summary(page) else 0)
