# Reads everything the editor needs out of a Pillars of Eternity install --
# item and ability catalogs, progression tables, stronghold upgrades, deities
# and paladin orders, and their icons -- into one folder.
#
#     extract_gamedata --game "<install folder>" --out "<data folder>"
#
# The editor runs this itself (the release ships it frozen as
# gamedata\extract_gamedata.exe) and reads its progress from stdout: one
# "PROGRESS <percent> <what>" line at a time, then "DONE <folder>" or
# "ERROR <reason>". Anything else on stdout is the extractors' own chatter and
# only goes to the log.
#
# The result is assembled in "<out>.new" and moved into place only once every
# part succeeded, so a failed or cancelled run leaves the previous data
# untouched rather than half overwritten.

import argparse
import datetime
import json
import os
import shutil
import sys
import traceback

FORMAT = 1
MANIFEST = "gamedata.json"

# Where the three stages sit on the progress bar. The item scan opens every
# object bundle in the game, which is nearly all of the time.
ITEMS_END = 85
STRONGHOLD_END = 95


def say(line):
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


def progress(percent, text):
    say("PROGRESS %d %s" % (percent, text))


def game_data_folder(game):
    return os.path.join(game, "PillarsOfEternity_Data")


def check_game(game):
    """Why `game` is not a Pillars of Eternity install, or None if it is."""
    if not os.path.isdir(game):
        return "The folder %s does not exist." % game
    if not os.path.isdir(game_data_folder(game)):
        return ("%s is not a Pillars of Eternity install: it has no "
                "PillarsOfEternity_Data folder." % game)
    bundles = os.path.join(game_data_folder(game), "assetbundles", "prefabs", "objectbundle")
    if not os.path.isdir(bundles):
        return "%s has no asset bundles to read (%s is missing)." % (game, bundles)
    return None


def remove(path):
    if os.path.isdir(path):
        shutil.rmtree(path)
    elif os.path.exists(path):
        os.remove(path)


def count_files(folder):
    return len(os.listdir(folder)) if os.path.isdir(folder) else 0


def manifest(game, staging):
    def entries(name):
        path = os.path.join(staging, name)
        if not os.path.isfile(path):
            return 0
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
        return len(data) if isinstance(data, (dict, list)) else 0

    return {
        "format": FORMAT,
        "game": os.path.abspath(game),
        "written": datetime.datetime.now().replace(microsecond=0).isoformat(),
        "items": entries("catalog.json"),
        "abilities": entries("abilities.json"),
        "progressionTables": entries("progression.json"),
        "icons": count_files(os.path.join(staging, "icons")),
        "strongholdIcons": count_files(os.path.join(staging, "stronghold-icons")),
    }


def swap_into_place(staging, out):
    previous = out + ".old"
    remove(previous)
    if os.path.exists(out):
        os.rename(out, previous)
    os.rename(staging, out)
    remove(previous)


def require_icons(staging, folder, what, failures):
    """A catalog without a single icon is a broken reader, not a quiet game."""
    if count_files(os.path.join(staging, folder)) > 0:
        return
    reason = failures[0] if failures else "no reason was given"
    raise RuntimeError("No %s icons could be written (%s)." % (what, reason))


def extract(game, out):
    # Imported here rather than at the top so a bad --game answers at once
    # instead of after UnityPy has loaded.
    import items
    import stronghold
    import identity

    staging = out + ".new"
    remove(staging)
    os.makedirs(staging)

    progress(0, "Reading item and ability bundles")
    items.configure(game, staging)
    items.on_progress = lambda done, total: progress(
        ITEMS_END * done // total,
        "Reading item and ability bundles (%d of %d)" % (done, total))
    items.main()
    require_icons(staging, "icons", "item and ability", items.icon_failures)

    progress(ITEMS_END, "Reading stronghold upgrades")
    stronghold.configure(game, staging)
    stronghold.main()
    require_icons(staging, "stronghold-icons", "stronghold", stronghold.icon_failures)

    progress(STRONGHOLD_END, "Reading deities and paladin orders")
    identity.configure(game, staging)
    identity.main()

    with open(os.path.join(staging, MANIFEST), "w", encoding="utf-8") as handle:
        json.dump(manifest(game, staging), handle, indent=1, sort_keys=True)

    swap_into_place(staging, out)


def self_test():
    """Load everything icon export needs, without a game install.

    A frozen build can read every catalog and still write no icon at all:
    texture decoding reaches native decoders, archspec's CPU tables and, via
    UnityPy.export, fmod_toolkit -- none of which PyInstaller finds by itself.
    This touches each of them, so the release build can check its own output
    in a second instead of a full run.
    """
    import archspec.cpu
    import astc_encoder
    import etcpak
    import texture2ddecoder
    from PIL import Image
    from UnityPy.export import Texture2DConverter

    archspec.cpu.host()
    say("OK UnityPy.export, texture2ddecoder, etcpak, astc_encoder, archspec, Pillow")
    return 0


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

    if argv is None:
        argv = sys.argv[1:]
    if argv == ["--self-test"]:
        return self_test()

    parser = argparse.ArgumentParser(
        description="Read item, ability, stronghold and identity data out of a "
                    "Pillars of Eternity install for Eternity Keeper.")
    parser.add_argument("--game", required=True,
                        help="the game's install folder (the one holding PillarsOfEternity_Data)")
    parser.add_argument("--out", required=True, help="the folder to write the data into")
    args = parser.parse_args(argv)

    problem = check_game(args.game)
    if problem:
        say("ERROR " + problem)
        return 2

    out = os.path.abspath(args.out)
    try:
        extract(args.game, out)
    except SystemExit as stop:
        # The stage scripts stop with sys.exit("reason") when the install
        # does not hold what they read.
        remove(out + ".new")
        say("ERROR " + (stop.code if isinstance(stop.code, str) else "an extractor stopped early"))
        return 1
    except Exception as failure:
        traceback.print_exc()
        remove(out + ".new")
        say("ERROR %s: %s" % (type(failure).__name__, failure))
        return 1

    progress(100, "Done")
    say("DONE " + out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
