A save editor for Pillars of Eternity, for the current Steam and GOG builds of the game (tested with v3.9.5).

## Download

For 64-bit Windows 10 or 11. Take either file below: they hold the same editor, and neither needs Java or anything else installed.

- **`EternityKeeper-{version}-win64-setup.exe`** installs it for your user, with no administrator rights, and adds it to the Start menu. It is removed again from *Settings → Apps → Installed apps*, and a newer version's installer upgrades it.
- **`EternityKeeper-{version}-win64.zip`** needs no installing: extract the whole zip anywhere and run `Eternity Keeper.exe`.

The downloads are not signed, so Windows may say it protected your PC: choose **More info**, then **Run anyway**. Each file has a `.sha256` beside it to check it against.

On first launch the editor finds your game and your saves, and offers to read item names and icons from your install (a few minutes, once). Names are shown in the language your game is set to.

**Your saves are safe.** The editor never writes to the save you open: Save writes a new save, which the game lists beside the original. Keep a copy of `%USERPROFILE%\Saved Games\Pillars of Eternity` all the same until you have loaded an edited save in the game.

Something wrong? Open an issue on this repository and attach `eternity.log`; Settings shows where it is.
