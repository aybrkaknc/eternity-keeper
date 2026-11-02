# Changelog

## 1.1.1-beta (unreleased)

### Fixed

- **A main character imported from another playthrough took the save into
  that playthrough.** The game keeps a playthrough's ID on the main
  character, so the save's autosaves went into the other game's autosave
  slots (where they could replace its autosaves) and the load list showed it
  with the other game's saves. The save now keeps its own. Starting a new
  character at a later point in the story this way is tested in the game; the
  README's questions say how.
- Saving writes the main character's name into the save's summary, which
  still named the old Watcher after a rename or an import.
- **Importing a character over your Watcher emptied the stash.** The stash,
  the quest items and the crafting materials belong to the main character in
  the save, so replacing the Watcher replaced them too, along with the money
  and the camping supplies, with whatever the imported character's own game
  had. The save now keeps its stash, quest items, crafting materials, money
  and camping supplies; only the character is new.
- An import that went wrong after it was written is now taken back, as every
  other change already was, instead of staying in the save unseen.
- **Saving often could push out the backup of a deleted save.** Every Save
  after the first backs up the file the one before it wrote, and File →
  Backups kept ten copies in all, so ten Saves were enough to lose the copy
  of a save you had deleted or renamed. It now keeps the ten most recent of
  each kind: deleted, renamed and replaced by Save.
- A change whose result could not be read back is now reported as not done
  and taken back. It used to stay in the save, and the page could take the
  error for the save itself.
- A Stronghold Apply that the save could only partly take reported success.
  It now goes in whole or not at all, and says which change was refused.
- Renaming a save replaces the file whole instead of editing it in place, so
  a rename that fails part of the way leaves the save as it was.
- **The editor would not start at all, with no message, on a computer where
  the Java Access Bridge is switched on** (screen readers and magnifiers
  often switch it on). It starts now. The editor's window is a web page
  with its own accessibility, which the bridge never served.
- **Save could quietly write a value the game does not have.** A choice the
  game does not know was saved as the first one in its list, and a number or
  a yes/no that could not be read was skipped without a word. Save now stops
  and says which value it could not write; nothing is saved.

## 1.1.0-beta (2026-10-23)

### New

- **Find a save quickly.** The save list has a filter box (character, area,
  save name, date, difficulty or chapter), sorts by date or by name, and shows
  the saves as tiles or as a list with columns. The layout you pick is kept.
- **Companions' names in your game's language** in the character list, the
  Inventory tab, the party and Compare: Edér, and "Mère en deuil" or
  "Trauernde Mutter" for the Grieving Mother. A name you gave a companion
  yourself is kept.
- **A refreshed look**: clearer panels and controls, gentle transitions
  between views, and an error banner you can close.

### Faster

- **Saves open in about half the time.** The editor reads a save's files
  once instead of piece by piece, and looks up what it has already seen. What
  it writes is unchanged: tested byte for byte against the previous version
  and against the game's own files.
- The save list unpacks the saves' pictures in parallel.

### Fixed

- In light mode, a party member's name on the Inventory tab turned white,
  and so unreadable, under the mouse.

## 1.0.1-beta (2026-10-09)

### Fixed

- **Reading game data stopped on a clean install** with "no ingameglobal
  bundle", and the editor was left without item, ability and stronghold data.
  The reader took the stronghold upgrades and the deities from
  `ingameglobal.unity3d`, a file of the 2015 game that Steam's updates leave
  behind and that a fresh install — GOG, or Steam installed since the 2025
  rebuild — never has. It now reads them from `resources.assets`, where the
  current game keeps them. That also corrects two numbers the old file had
  wrong (the Brighthollow Hearth and the Courtyard Pool cost 600 cp, not
  1,600) and adds the two hirelings it lacked (the Bleakwalker of The White
  March and Raedric's fampyr). **Read again** in Settings picks these up on
  an install where the data was read before.

## 1.0.0-beta (2026-10-06)

The first release with an installer and a ready-to-run download. Compared with
Eternity Keeper 0.21a (aybrkaknc's update of February 2026, which made the
editor read the current game's saves, the turn-based patch included):

### Getting started

- **A ready-to-run Windows download.** One zip holding `Eternity Keeper.exe`,
  the Java 8 runtime it needs and the embedded browser. Nothing to install, and
  whatever Java the machine has is never used.
- **Or an installer.** `EternityKeeper-<version>-win64-setup.exe` holds the same
  files and asks for no administrator rights: it installs for your user, adds a
  Start menu entry and an entry under Installed apps, upgrades an older version
  in place, and its uninstaller leaves your settings and save backups alone.
  The release workflow installs, starts and uninstalls every build of it on a
  clean Windows machine before attaching it to a release, and checks that
  it upgrades over an older copy, stops while the editor is open, and
  refuses a folder the editor could not start from.
- **The save list opens at once.** A search used to unpack every save whole
  (5.8 s and 767 MB of temporary files for twelve saves, at every start); it
  now unpacks only what a tile is drawn from, and the rest of a save when it
  is opened or compared.
- **A damaged file in the saves folder costs only its own tile.** A save cut
  short, a file that is not a save, a summary with a field missing: the list
  shows the others and names each one it left out, with the reason. One of
  them used to end the whole search, and a save that was simply missing left
  its owner looking for it.
- **Works from any folder.** Settings and the log live in
  `%APPDATA%\Eternity Keeper`, so the editor runs from Program Files, a
  shortcut or a USB stick. Settings from an older copy are brought across once.
- **Game data is read for you.** Item names and icons, abilities, stronghold
  upgrades and deities come from your own game install. The editor offers to
  read them on first launch (a few minutes, once), shows its progress, and
  can do it again from Settings after the game updates.
- **Names in your game's language.** Items, abilities, spells, talents,
  stronghold upgrades, hirelings and quests are shown as your game shows them:
  the editor follows the language the game is set to, and Settings lets you
  pick any other your install has. The editor's own words stay English, and
  what a language has no word for is shown in English.
- **Finds the game wherever it is installed**: Steam libraries on any drive,
  GOG, Epic, or common folders on every drive. A Microsoft Store copy is
  detected and explained, because Windows does not let other programs read its
  files.
- Saves from the current game (tested with Steam v3.9.5) open, edit and load
  back in the game correctly.

### Editing

- **Inventory**: every party member's pack, their equipment and the shared
  stash, with real names and icons, and the equipment laid out around the
  character the way the game's own inventory screen has it. Move items between characters, equip and
  unequip them (the game's slot, class and race rules are enforced), change
  stack sizes, add any of the game's items, sell junk in bulk, tidy stacks.
- **Quick slots and weapon sets** follow the game's own rules: a two-handed
  weapon only goes in the main hand and shares its set with nothing, a
  soulbound item stays with its owner, a big stash stack splits at the item's
  stack size, and swaps work even with full packs. A refused edit says why.
- **Abilities and talents**: see what a character knows and add anything their
  class, race or companion could learn, including the abilities a talent grants.
- **Grimoires**: edit a wizard's spellbook four spells to a level, the way the
  game lays it out.
- **Vendors**: every store in the save with what it holds, read from the area
  files the game keeps it in. Pick what to take out of a vendor's stock — what
  you sold there, say — and Apply removes exactly that. Nothing is picked for
  you, and the vendor's original stock, uniques included, is marked, because
  the game never restocks it.
- **Stronghold**: build and demolish Caed Nua's upgrades with the game's own
  Prestige and Security arithmetic, dismiss hirelings and release prisoners the
  way the game's own buttons do, and edit turns, debt and taxes.
- **Identity**: race, subrace, class, culture, background, deity and paladin
  order, showing what each choice does to the character sheet.
- **Skills** edited as the ranks the game shows, not raw points.
- **Portraits**: pick any portrait on your install, including ones you added.
  The game's load list shows the new faces too: Save redraws the party
  thumbnails stored in the save, which the game only updates when it saves.
- **Resurrect dead companions**, including their failed personal quest and
  Sagani's pet.
- **Party management** from anywhere: swap companions between the party and the
  stronghold roster.
- **Find anything** (Ctrl+F): one box above the character list that finds
  characters, items wherever they are (packs, quick slots, worn, weapon sets,
  the stash), abilities, talents and global variables, with or without
  accents, and opens each on its own tab with the item or ability picked out.
- **Compare two saves** (*Compare* on the save list): what differs between
  them, in the editor's own words — money, difficulty and game time; each
  character's stats, health and records; items gained, lost and moved, with
  where they went; abilities, grimoires, the stronghold, global variables,
  quests by their journal titles with the objectives reached, and each
  store's stock. The save picked is compared with the one it was made from,
  so after a Save it shows exactly what the edit changed. Positions and the
  game's timers are counted rather than listed; everything else is still
  shown.
- **Heal, level up and resupply** the party in one place: bring everyone in the
  party back to full health and stamina (the game works out each maximum
  itself when the save loads, as its own HealParty does), raise everyone below
  a level to it without lowering anyone's experience, and refill camping
  supplies to what the difficulty allows.
- **Console tab**: re-enable achievements, and run the save-representable
  console commands (experience, attributes, skills, money, globals,
  stronghold), with a searchable reference of the in-game-only ones.
- Difficulty, Expert Mode, Trial of Iron and turn-based mode, and party money.
- Import and export characters as `.chr` files.
- **Loadouts**: save what a character wears, holds and keeps in quick slots
  to a `.loadout` file from the Inventory tab, and put it on anyone, in the
  same save or another playthrough. Each item goes on as a copy with its
  enchantments, so the originals stay where they are, and whatever it
  replaces goes to the stash. Before anything changes, the plan says item
  by item where each piece goes and what is left out and why: a slot the
  character does not have, gear for another class, or a soulbound item
  bound to someone else.
- Revert and Apply on every panel. Save shows the exact file name and folder it
  will write.
- **Undo and Redo** beside the Save button (Ctrl+Z, Ctrl+Y): everything changed
  since the save was opened or last saved, one step at a time, each said in
  words (“Phantom’s Might, 18 to 25”, “Stronghold: 1 change”). That covers
  what was typed and what an Apply wrote: undoing an Apply puts the save back
  exactly as it was before it. A tab’s staged changes have their own Revert,
  and Undo waits until they are applied or reverted.
- Selling items in the Inventory tab is staged like every other change there:
  the money is added when Apply takes the items out of the save, as one step
  that Undo takes back whole. It used to be added at once, so Revert left it
  behind and a Save before Apply wrote the money beside the items.
- A resurrection, an import and a party change wait while a tab has changes
  that are not applied yet, and say which tab, instead of dropping them.
- The character sheet’s Revert puts back the portrait picture as well as the
  portrait’s paths.

### Safety

- **Your original save is never written to.** Edits go to a private working
  copy, and Save writes a new file.
- **Backups.** Before the editor deletes a save, renames one or replaces a file
  of the same name when saving, it keeps a copy. File → Backups lists the ten
  most recent and puts any of them back; restoring never replaces a save that
  is there.
- Delete on the save list only ever deletes a `.savegame` file.
- Unsaved edits survive every Apply: a change made on one tab is not lost when
  another tab reloads the save.
- A save that breaks the format's own rules is flagged when it opens, before
  the game quietly drops what it cannot read.
- **The editor always answers.** A save damaged into a shape the editor had
  never met could end the work behind a spinner without a word, and the spinner
  then turned for ever (one changed byte in a real save did it). Every request
  the page makes now gets an answer, and a failed edit is taken back.
- **A resurrected companion no longer brings back items the game still
  keeps elsewhere.** What a companion held in their quick slots when they died
  stays in the area they died in; the resurrection brought the same items back
  under the same IDs, and the game reported an error for each when that area
  loaded. They now come back as items of their own, and so does anything an
  imported character carries that the save has since sold to a store.
- **Saving never takes the name of a file already in the saves folder.** The
  number in a new save's name was the first one free among the saves the list
  had read; a file put there since (a backup restored, a save the list could
  not read) could be replaced by the first Save.
- **Leftovers are cleared out.** An editor that crashed or was killed left its
  working copy of the open save in the temp folder for good (2.5 GB had
  collected on the machine this was written on); they are removed at the next
  start, once a day old.
- **A damaged save is never written back.** A save that can only be read in
  part — cut short, or corrupted part of the way through — still opens, with a
  warning that says how much is missing, but no edit, Save, import or export
  writes from it: writing back only what could be read would lose the rest.
- **An edit that would break the save is not written.** Everything the editor
  writes is checked first against the rules the game relies on — every
  object's ID unique and its own, item lists that pair up, nothing listed that
  is not in the save, every value of the kind the file expects — and a change
  that would break one is refused with a message saying what and where,
  instead of becoming a save the game quietly loses things from. What the game
  itself left in a save never stops an edit.
- **Importing a character from another save of the same game keeps their
  items.** Anything they carry that the save already holds — an item handed to
  someone else since the export, say — comes in as a copy of its own. Before,
  the two shared an ID and the game dropped both.
- **Saving no longer renames companions.** A companion the game had not named
  keeps an empty name in the save, which is what lets the game show her name
  in the player's own language. Save wrote the editor's English one in its
  place, without its accents (Edér came back "Eder").
- Polish and other non-ASCII save names display correctly.

### Also

- Light and dark themes.
- Save tiles on one line of the list start at one height; a tile beside one
  whose title took two lines used to hang below it.
- Every tab adapts to the window. The Abilities tab puts what a character
  knows, their talents and the browser side by side at one height; the
  Stronghold tab spreads its upgrades across the whole width in two columns.
- Saving is about 15 times faster (roughly 2 seconds instead of 30).
- Save format conversion (*Format* on the save list) rewrites a save for Steam
  and GOG builds older than the 2017 Unity update.
- The embedded browser's remote-debugging port is off unless asked for
  (`-Dek.debugPort`): it let any program on the machine drive the editor.
- org.json is now a public-domain release. The 2014 one was under the JSON
  License, whose "Good, not Evil" clause is at odds with the GPL.
- The other libraries inside the editor are current ones: Logback 1.3.16,
  Commons IO 2.22.0, Guava 33.7.2 and zip4j 2.11.6, in place of releases
  from 2012 to 2020 that security scanners flag. None of their published
  flaws could be reached through the editor; the point is a download that
  nothing warns about.

### Removed

- The auto-updater and its bootstrapper.
- 32-bit Windows builds. Linux builds still compile but are untested and
  unsupported for this release.
