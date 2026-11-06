<div align="center">

<img src="src/main/resources/icon.png" alt="" width="104">

# Eternity Keeper

**A save editor for *Pillars of Eternity***

Change your characters, their gear and abilities, your party and your stronghold,<br>
then load the result in the game. Works with the current Steam and GOG versions.

[![Download: Windows installer and zip](https://img.shields.io/badge/Download-Windows_installer_and_zip-2ea043?style=for-the-badge)](../../releases)

![Windows: 10 and 11, 64-bit](https://img.shields.io/badge/Windows-10_and_11,_64--bit-0078D4)
![Pillars of Eternity: tested with v3.9.5](https://img.shields.io/badge/Pillars_of_Eternity-tested_with_v3.9.5-7b3fb3)
![License: GPL v3](https://img.shields.io/badge/license-GPL_v3-blue)

</div>

<br>

![The Inventory tab: the character's equipment around their portrait, quick items and weapon sets below, and every party member's pack beside them](docs/screenshots/inventory.png)

## What you can change

| Area | What you can do |
|---|---|
| **Characters** | Attributes, skills, experience and level. Race, subrace, class, culture, background, deity and paladin order, with what each choice is worth on the character sheet. Portraits and names. |
| **Inventory** | Every party member's pack, equipment, quick slots and weapon sets, and the shared stash, with the game's own names and icons. Move and equip items by the game's rules, change stack sizes, add any item in the game, and sell junk in bulk. |
| **Abilities** | Abilities, spells and talents: anything a character's class, race or companion story allows, including what a talent grants. A wizard's grimoire, four spells to a level. |
| **Party** | Swap companions with the stronghold's roster from anywhere, and bring dead companions back to life, their personal quest included. Heal, level up and resupply everyone at once. |
| **Stronghold** | Build and demolish upgrades, with Prestige and Security worked out as the game does it. Dismiss hirelings, release prisoners, and set turns, debt and taxes. |
| **Vendors** | See every store's stock, and take out what you sold there. |
| **Game settings** | Difficulty, Expert Mode, Trial of Iron and turn-based mode. Party money and camping supplies. Achievements turned back on. |

**And also:**

- **Find anything** in the open save with <kbd>Ctrl</kbd>+<kbd>F</kbd>: characters, items wherever they are, abilities and game variables.
- **Undo and Redo** for every change since you opened or last saved the save.
- **Compare two saves** in plain words: experience gained, items moved, quests advanced.
- **Take characters and gear to another save**: export a character as a `.chr` file, or what they wear and carry as a `.loadout`, and bring it into another save or playthrough.
- **Your game's language**: items, abilities and upgrades carry the names your game shows.
- **Older game versions**: convert a save for Steam and GOG copies of the game from before its 2017 engine update.

| A character | Abilities and talents | The stronghold |
|---|---|---|
| ![Attributes, skills and identity, with what each choice is worth](docs/screenshots/character.png) | ![What a character knows, and what their talents grant](docs/screenshots/abilities.png) | ![Upgrades, Prestige and Security, hirelings](docs/screenshots/stronghold.png) |

## Getting started

1. **Download** the installer or the zip from the [Releases page](../../releases). They hold the same editor, and neither needs Java or anything else installed.
   - `EternityKeeper-<version>-win64-setup.exe` installs it for your user, with no administrator rights, and adds it to the Start menu.
   - `EternityKeeper-<version>-win64.zip` needs no installing: extract the whole zip anywhere and run `Eternity Keeper.exe`.
2. **Start it.** It finds your game and your saves by itself, and offers to read item names and icons from your game. That takes a few minutes, once.
3. **Edit.** Open a save, change what you like, and press **Save**. The editor writes a *new* save; the one you opened is never touched.
4. **Play.** Load the new save in the game. It is listed beside the original, under the name you gave it.

> [!NOTE]
> The downloads are not signed, so the first time you run one, Windows may say it protected your PC. Choose **More info**, then **Run anyway**.

> [!IMPORTANT]
> **Your saves are safe.** The editor never writes to the save you open, and before it deletes, renames or replaces a save it keeps a copy: **File → Backups…** puts any recent one back. Even so, keep a copy of `%USERPROFILE%\Saved Games\Pillars of Eternity` until you have loaded an edited save in the game.

## Requirements

- **Windows 10 or 11**, 64-bit.
- ***Pillars of Eternity*** from Steam, GOG or Epic, installed on the same computer. The editor reads item names, icons and portraits from your copy of the game; without it, items go by their file names. A Microsoft Store copy keeps its files locked, so they cannot be read from it.

## Questions

<details>
<summary><b>The editor didn't find my game</b></summary>

Open **File → Settings…** and choose the folder that holds `PillarsOfEternity_Data`, for example `C:\Program Files (x86)\Steam\steamapps\common\Pillars of Eternity`.

</details>

<details>
<summary><b>Items have odd names, like "Ring Preorder Gauns Pledge"</b></summary>

The editor hasn't read your game's data yet. Open **File → Settings…** and choose **Read game data**. It takes a few minutes, and only has to be done again after the game updates.

</details>

<details>
<summary><b>The names are in English, but my game is in another language</b></summary>

Open **File → Settings…** and choose a language under **Names of items and abilities**. Items, abilities and upgrades then carry the names your game shows; the editor's own menus stay in English.

</details>

<details>
<summary><b>I can't change a companion's attributes</b></summary>

The game puts a companion's base attributes back to its own values every time a save loads, so the editor doesn't offer to change them. A companion's skills, class, background and everything else stay changed.

</details>

<details>
<summary><b>Does editing a save switch off achievements?</b></summary>

No: the editor never changes the setting the game uses to switch achievements off. If the game's own console switched them off, the **Achievements** button on the editor's **Console** tab switches them back on.

</details>

<details>
<summary><b>Can I start a new character later in the story?</b></summary>

Yes, using a save from an earlier playthrough that is already where you want to start, say at Caed Nua. Make your new character in a new game and save, then in the editor open that save, choose **Character → Export character to file…**, open the later save and choose **Character → Import character from file…**. The newcomer replaces the main character there, and the story, reputation, companions and money stay as that save has them. Bring gear across with a loadout from the Inventory tab. This works from version 1.1.1 on, and was tested in the game.

</details>

<details>
<summary><b>What is "Edit raw stats"?</b></summary>

Every number the game stores on a character, for experienced users. Most of them have never been tried in the game, so the other tabs are the safe way to change things.

</details>

<details>
<summary><b>It won't start from the folder I put it in</b></summary>

Java 8, which the editor runs on, can't start from a folder whose name has letters from outside your Windows language: Cyrillic or Japanese on a Western Windows, for example. Move the editor to a folder like `C:\Games\Eternity Keeper`; the installer checks this for you. Your user name, your saves and your game can be in folders named in any language.

</details>

<details>
<summary><b>Where are my settings, the log and the backups?</b></summary>

In `%APPDATA%\Eternity Keeper`, whichever way you installed the editor. **File → Settings…** shows the exact path of the log, and **File → Backups…** lists the backups.

</details>

<details>
<summary><b>How do I uninstall it?</b></summary>

If you used the installer: **Settings → Apps → Installed apps → Eternity Keeper → Uninstall** in Windows. If you used the zip: delete its folder. Either way, your settings and save backups stay in `%APPDATA%\Eternity Keeper` until you delete them.

</details>

<details>
<summary><b>Does it work on Mac or Linux?</b></summary>

Not yet. Windows is the only system it is tested on: Linux builds compile but are untested, and there is no Mac build.

</details>

## Reporting a problem

[Open an issue](../../issues) and attach `eternity.log` (**File → Settings…** shows where it is). Say what you did, what you expected and what happened. If you can, attach the save too: it helps most of all.

## For developers

Eternity Keeper is a Java 8 desktop application with an embedded Chromium browser for its interface. To build and run it from source:

```bash
mvn install -Pwin64
run.bat
```

[CONTRIBUTING.md](CONTRIBUTING.md) covers the setup, the tests, making a release and the rules a save edit has to keep. [docs/](docs/README.md) has the notes on the save format and on what the game does with a save when it loads, [ROADMAP.md](ROADMAP.md) the plan, and [CHANGELOG.md](CHANGELOG.md) what changed.

## Credits

Eternity Keeper was created by **Kim Mantas** in 2015 and 2016 ([original on Bitbucket](https://bitbucket.org/Fyorl/eternity-keeper)). **ktully** added the conversion of Windows Store saves (0.20a, 2020), and **[aybrkaknc](https://github.com/aybrkaknc/eternity-keeper)** brought it up to the current game, the turn-based patch included (0.21a, 2026). This version builds on theirs.

The icon is by [Alexander Loginov](http://alexanderloginov.deviantart.com/). Everyone who has worked on the editor is listed in [CONTRIBUTORS](CONTRIBUTORS), and the software it includes in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## License

[GNU General Public License v3](LICENSE) or later.

<sub>Eternity Keeper is an unofficial fan project, not affiliated with Obsidian Entertainment or Paradox Interactive. It contains no game files: item names, icons and portraits are read from your own copy of the game.</sub>
