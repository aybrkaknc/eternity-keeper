# Backups, end to end in the running editor: a save deleted from the list is
# copied first, File -> Backups shows it and puts it back, a second restore
# refuses to replace the save that is now there, and a rename is copied too.
#
# Works on a throwaway copy of one test save, so the test environment's own
# twelve are never touched; the copy is removed at the end.
import hashlib, io, json, os, shutil, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import config
from config import SAVES
from harness import Page, check, summary, boot

HERE = os.path.dirname(os.path.abspath(__file__))
AUDIT = io.open(os.path.join(HERE, "shaudit.js"), encoding="utf-8").read()
SOURCE = "cf88c16dc9564d77a49e89c8894d5c4e 1 CilantLs.savegame"
COPY = "cf88c16dc9564d77a49e89c8894d5c4e 424242 CilantLs.savegame"
BACKUPS = os.path.join(config.OUT, "data", "backups")


def md5(path):
    with open(path, "rb") as handle:
        return hashlib.md5(handle.read()).hexdigest()


def listed(page):
    return page.eval("Eternity.SaveSearch.state.saves.map(function(s){"
                     " return (s.absolutePath || '').split(/[\\\\/]/).pop(); })")


def index_of(page, name):
    names = listed(page)
    return names.index(name) if name in names else -1


def rescan(page, present):
    page.eval("Eternity.SaveSearch.search()")
    page.wait_for("Eternity.SaveSearch.state.saves.some(function(s){"
                  " return (s.absolutePath || '').indexOf(%s) >= 0; }) === %s"
                  % (json.dumps(COPY), "true" if present else "false"), 120, "the save list")


def open_backups(page):
    page.eval("$('#menuBackups').click()")
    page.wait_for("$('#backupsDialog').is(':visible') && !Eternity.Backups.state.loading",
                  30, "the Backups dialog")
    time.sleep(0.6)


def rows(page):
    return page.eval("""$('#backupsList .bk-row').map(function(){ return {
        name: $(this).find('.bk-name').text(), details: $(this).find('.bk-details').text(),
        file: $(this).find('.bk-file').text(),
        button: $(this).find('.bk-restore').text(), done: $(this).find('.bk-done').text()};
      }).get()""")


def close(page):
    page.eval("$('#backupsDialog').modal('hide')")
    time.sleep(0.9)


shutil.rmtree(BACKUPS, ignore_errors=True)
copy = os.path.join(SAVES, COPY)
shutil.copyfile(os.path.join(SAVES, SOURCE), copy)
original = md5(copy)

page = Page()
page.call("Page.enable")
try:
    boot(page)
    rescan(page, True)

    # ---- nothing yet --------------------------------------------------------
    open_backups(page)
    check("with no backups the dialog says so",
          "No backups yet" in page.eval("$('#backupsList').text()"),
          page.eval("$('#backupsList').text()"))
    check("it says where they are kept", BACKUPS.lower() in
          page.eval("$('#backupsIntro').text()").lower().replace("/", "\\"),
          page.eval("$('#backupsIntro').text()"))
    check("it says the limit is for each kind of copy",
          "10 most recent of each kind" in page.eval("$('#backupsIntro').text()"),
          page.eval("$('#backupsIntro').text()"))
    close(page)

    # ---- delete from the save list -----------------------------------------
    page.eval("Eternity.SaveSearch.select(%d)" % index_of(page, COPY))
    time.sleep(0.5)
    page.eval("$('#saveActionDelete').click()")
    time.sleep(1.1)
    warning = page.eval("$('#deleteSaveDialog .modal-body').text()")
    check("the delete dialog says a copy is kept", "Backups" in warning
          and "cannot be undone" not in warning, " ".join(warning.split()))
    page.eval("$('#deleteSaveConfirm').click()")
    page.wait_for("!$('#deleteSaveDialog').is(':visible')", 60, "the delete")
    time.sleep(0.5)
    check("the save is gone from the disk", not os.path.exists(copy))
    check("and from the list", index_of(page, COPY) < 0)

    kept = [os.path.join(root, name) for root, _, names in os.walk(BACKUPS)
            for name in names if name == COPY]
    check("a copy of it was taken first", len(kept) == 1 and md5(kept[0]) == original, kept)

    # ---- File -> Backups ----------------------------------------------------
    open_backups(page)
    found = rows(page)
    check("the dialog lists it", len(found) == 1, found)
    if found:
        check("by the save's own name, and why", found[0]["name"] and
              "before it was deleted" in found[0]["details"] and found[0]["file"] == COPY,
              found[0])
        check("with a Restore button", found[0]["button"] == "Restore", found[0])

    # Measured with a real row in it: an empty dialog has no text to fail on.
    for light in (False, True):
        if light != page.eval("document.body.className.indexOf('theme-light') >= 0"):
            page.eval("$('#themeToggle').click()")
            time.sleep(0.6)
        result = page.eval(AUDIT + "('#backupsDialog')")
        check("the dialog is clean in %s mode" % ("light" if light else "dark"),
              not result.get("issues"), json.dumps(result.get("issues", [])[:2]))
    page.eval("if (document.body.className.indexOf('theme-light') >= 0) $('#themeToggle').click()")
    time.sleep(0.5)
    data = page.call("Page.captureScreenshot")["data"]
    import base64
    with open(os.path.join(config.OUT, "backups-dialog.png"), "wb") as handle:
        handle.write(base64.b64decode(data))

    page.eval("$('#backupsList .bk-restore').first().click()")
    page.wait_for("$('#backupsList .bk-done').length === 1", 60, "the restore")
    status = page.eval("$('#backupsStatus').text()")
    check("the restore says where it went", "Put back as" in status and COPY in status, status)
    check("the save is back, byte for byte", os.path.exists(copy) and md5(copy) == original)
    page.wait_for("Eternity.SaveSearch.state.saves.some(function(s){"
                  " return (s.absolutePath || '').indexOf(%s) >= 0; })" % json.dumps(COPY),
                  120, "the list to show it again")
    check("and back on the list", index_of(page, COPY) >= 0)
    close(page)

    # ---- restoring again must not replace the save that is there now ------
    open_backups(page)
    page.eval("$('#backupsList .bk-restore').first().click()")
    page.wait_for("Eternity.Backups.state.failed", 60, "the refusal")
    status = page.eval("$('#backupsStatus').text()")
    check("a second restore refuses to replace the save", "already" in status, status)
    check("which is untouched", md5(copy) == original)
    close(page)

    # ---- rename -------------------------------------------------------------
    page.eval("Eternity.SaveSearch.select(%d)" % index_of(page, COPY))
    time.sleep(0.5)
    page.eval("$('#saveActionRename').click()")
    time.sleep(1.1)
    page.eval("$('#renameSaveInput').val('Backups test, renamed')")
    page.eval("$('#renameSaveConfirm').click()")
    page.wait_for("!$('#renameSaveDialog').is(':visible')", 60, "the rename")
    time.sleep(0.5)
    open_backups(page)
    found = rows(page)
    check("a rename is copied first too", len(found) == 2 and
          "before it was renamed" in found[0]["details"], found[:1])
    close(page)
    # The file is replaced whole by a finished copy made beside it.
    import zipfile
    with zipfile.ZipFile(copy) as renamed:
        infos = [n for n in renamed.namelist() if n == "saveinfo.xml"]
        named = "Backups test, renamed" in renamed.read("saveinfo.xml").decode("utf-8-sig")
    check("the renamed file holds saveinfo.xml once, with the new name",
          len(infos) == 1 and named, infos)
    check("nothing is left beside it", not [n for n in os.listdir(SAVES) if ".renaming" in n],
          [n for n in os.listdir(SAVES) if ".renaming" in n])
finally:
    if os.path.exists(copy):
        os.remove(copy)

saves = [n for n in os.listdir(SAVES) if n.endswith(".savegame")]
check("the test saves are as they were", len(saves) == 12, len(saves))
sys.exit(summary(page))
