ETERNITY KEEPER 1.0 (beta)
A save editor for Pillars of Eternity

Unofficial fan project, not affiliated with Obsidian Entertainment or Paradox
Interactive. It contains no game files; everything it shows about items,
abilities and the stronghold is read from your own installation.


STARTING IT

If you used the installer, Eternity Keeper is in the Start menu.

If you downloaded the zip:
1. Extract the whole zip anywhere, keeping every folder in it.
2. Double-click "Eternity Keeper.exe".

Nothing else needs installing: the Java runtime and browser the editor uses
are in the "jre" and "lib" folders beside it.

Windows may warn about an unrecognised app the first time. Choose
"More info", then "Run anyway".

If it says it cannot find the Java runtime it ships with although the "jre"
folder is there, the folder's path has letters Windows cannot pass on to that
runtime (a folder named in another script than your Windows' own language).
Move the Eternity Keeper folder to a path with plain letters, for example
C:\Games, and start it again.


FIRST LAUNCH

- The editor looks for your game and your saves. If it cannot find the game,
  it opens Settings: enter the folder that holds PillarsOfEternity_Data.
- It then offers to read item names and icons, abilities, stronghold upgrades
  and deities from your game. This takes a few minutes and is needed only
  once. Until it is done, items show their file names.


YOUR SAVES ARE SAFE

Eternity Keeper never writes to the save you opened. Save always writes a
new save file, and the game lists it beside the original.

Before it deletes or renames a save, or replaces one of the same name, it
keeps a copy. File > Backups lists the ten most recent and puts any of them
back.

Keep a copy of your saves folder anyway, at least until you have loaded an
edited save in the game:
    %USERPROFILE%\Saved Games\Pillars of Eternity


IF SOMETHING GOES WRONG

The editor keeps its settings and a log in
    %APPDATA%\Eternity Keeper
Settings shows the exact path. Please attach eternity.log to a bug report.

To start over, close the editor and delete that folder.


REMOVING IT

Installed: Settings > Apps > Installed apps > Eternity Keeper > Uninstall.
From the zip: delete the folder you extracted.

Either way your settings and the backups of your saves stay in
    %APPDATA%\Eternity Keeper
until you delete that folder as well.


LICENSE

GNU General Public License v3 or later: see LICENSE. The software it
includes is listed in THIRD-PARTY-NOTICES.md.
