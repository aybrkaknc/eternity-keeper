# One-time item and ability catalog extraction from the user's own Pillars of
# Eternity install. For every object bundle that holds an Item-derived component
# we resolve the real display name (DatabaseString -> *.stringtable) and export
# the inventory icon as a PNG; bundles holding an ability or a talent instead go
# into a second catalog, since the save stores those as their own objects too.
#
# The editor already reads portraits straight out of the game install; this is
# the same idea, just precomputed because parsing Unity bundles in Java 8 isn't
# realistic. Output lands OUTSIDE the git repo.
import json
import os
import re
import sys

import UnityPy

# Set by configure(), which extract_gamedata.py calls; never hard-coded.
GAME = BUNDLES = TEXT = OUT = ICONS = None

# Called with (bundles read, bundles in all) while main() runs.
on_progress = None

# Why icons could not be written, the first few.
icon_failures = []


def configure(game_root, out):
    """Read from the install at `game_root` and write into `out`."""
    global GAME, BUNDLES, TEXT, OUT, ICONS
    GAME = os.path.join(game_root, "PillarsOfEternity_Data")
    BUNDLES = os.path.join(GAME, "assetbundles", "prefabs", "objectbundle")
    TEXT = os.path.join(GAME, "data", "localized", "en", "text", "game")
    OUT = out
    ICONS = os.path.join(OUT, "icons")

# DatabaseString.StringTableType -> stringtable file name (decompiled enum).
TABLES = {
    1: "gui", 4: "characters", 5: "items", 6: "abilities", 7: "tutorial",
    9: "areanotifications", 10: "interactables", 12: "debug", 13: "recipes",
    14: "factions", 15: "loadingtips", 16: "itemmods", 17: "maps",
    19: "afflictions", 20: "backercontent", 900: "cyclopedia",
    938: "stronghold", 942: "backstory",
}

# CharacterStats.Class, only as far as the player-facing classes go — anything
# past Chanter is a creature type and never restricts a wearable item.
CLASSES = [
    "Undefined", "Fighter", "Rogue", "Priest", "Wizard", "Barbarian", "Ranger",
    "Druid", "Paladin", "Monk", "Cipher", "Chanter",
]

# Equippable's own slot flags, in the order the game lays the panel out.
SLOT_FLAGS = [
    "HeadSlot", "NeckSlot", "ArmorSlot", "RingRightHandSlot", "RingLeftHandSlot",
    "HandSlot", "FeetSlot", "WaistSlot", "GrimoireSlot", "PrimaryWeaponSlot",
    "SecondaryWeaponSlot", "BothPrimaryAndSecondarySlot", "PetSlot",
]

# CharacterStats.Subrace, in enum order — a few racial abilities are gated on it.
SUBRACES = [
    "Undefined", "Meadow_Human", "Ocean_Human", "Savannah_Human", "Wood_Elf",
    "Snow_Elf", "Mountain_Dwarf", "Boreal_Dwarf", "Death_Godlike",
    "Fire_Godlike", "Nature_Godlike", "Moon_Godlike", "Hearth_Orlan",
    "Wild_Orlan", "Coastal_Aumaua", "Island_Aumaua", "Avian_Godlike",
    "Advanced_Construct",
]

# EconomyManager.ItemModCostMultiplier: "The currency value of an item mod is
# determined by multiplying its 'Cost' by this."
ITEM_MOD_COST_MULTIPLIER = 1000

# CharacterStats.SkillType, in enum order — talents award their bonus by index.
SKILLS = ["Stealth", "Athletics", "Lore", "Mechanics", "Survival", "Crafting"]

# GenericTalent.TalentType and TalentCategory.
TALENT_TYPES = ["GrantNewAbility", "ModExistingAbility"]
TALENT_CATEGORIES = ["Undefined", "Class", "Offense", "Defense", "MixedOrUtility"]

# Every component class that IS an ability. A save writes the component's exact
# class name as the packet's TypeString, so the catalog has to remember which
# one each prefab uses rather than assuming GenericAbility.
ABILITY_BASES = ("GenericAbility", "GenericSpell", "GenericCipherAbility")

ENTRY = re.compile(
    r"<ID>(\d+)</ID>\s*<DefaultText>(.*?)</DefaultText>", re.S)
UNESCAPE = [("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
            ("&quot;", '"'), ("&apos;", "'")]

_tables = {}


def table(idx):
    """Lazily parse a stringtable into {id: text}."""
    if idx in _tables:
        return _tables[idx]
    name = TABLES.get(idx)
    result = {}
    if name:
        path = os.path.join(TEXT, name + ".stringtable")
        if os.path.exists(path):
            with open(path, encoding="utf-8-sig") as handle:
                for sid, text in ENTRY.findall(handle.read()):
                    for a, b in UNESCAPE:
                        text = text.replace(a, b)
                    result[int(sid)] = text.strip()
    _tables[idx] = result
    return result


def resolve(dbstring):
    if not isinstance(dbstring, dict):
        return None
    sid = dbstring.get("StringID", -1)
    if sid is None or sid < 0:
        return None
    return table(dbstring.get("StringTable", 0)).get(sid)


def source(dbstring):
    """Where a text resolve() found came from, as [table, id].

    The catalogs keep every name in English. The editor shows it in the
    language the game is played in by looking the same entry up in that
    language's copy of the table, on the player's own install.
    """
    if not isinstance(dbstring, dict):
        return None
    sid = dbstring.get("StringID", -1)
    index = dbstring.get("StringTable", 0)
    if sid is None or sid < 0 or sid not in table(index):
        return None
    return [index, sid]


def with_source(entry, field, dbstring):
    """Note beside entry[field] where its text came from."""
    found = source(dbstring)
    if found:
        entry[field + "Id"] = found


def save_icon(by_id, pointer, icons_seen):
    """Export a PPtr<Texture2D> as a PNG and return its file name."""
    path_id = (pointer or {}).get("m_PathID", 0)
    if not path_id or path_id not in by_id:
        return ""
    try:
        texture = by_id[path_id].read()
        icon_file = re.sub(r"[^A-Za-z0-9_.-]", "_", texture.m_Name) + ".png"
        if icon_file not in icons_seen:
            texture.image.save(os.path.join(ICONS, icon_file))
            icons_seen.add(icon_file)
        return icon_file
    except Exception as failure:
        # Remembered rather than raised: one bad texture should not cost the
        # whole catalog, but none written at all is a broken reader, and
        # extract_gamedata.py reports the first reason.
        if len(icon_failures) < 3:
            icon_failures.append("%s: %s" % (type(failure).__name__, failure))
        return ""


def prefab_path(tree_by_owner, owner, fallback):
    """The exact-cased prefab path, which Persistence carries verbatim."""
    persistence = tree_by_owner.get((owner, "Persistence"))
    if persistence:
        path = persistence.get("Prefab")
        if path:
            return path
    return fallback


def ability_entry(bundle, wanted, own, by_id, gameobject_names, script_names,
                  env, icons_seen):
    """Build a catalog entry if this bundle describes an ability or a talent.

    Returns None for anything else so the caller falls through to the item scan.
    """
    # A potion or a trap carries a GenericAbility component describing what it
    # does, so it would otherwise look exactly like an ability here. Anything
    # with an inventory icon is an item and belongs in the other catalog.
    for tree in own.values():
        if "IconTexture" in tree:
            return None

    talent = ability = ability_class = None
    for (owner, cls), tree in own.items():
        if "Abilities" in tree and "SkillBonuses" in tree and "Category" in tree:
            talent = tree
        elif "EffectType" in tree and "Icon" in tree and "AcquisitionLevel" in tree:
            # Several bundles hold more than one ability component (a spell and
            # the passive it applies); the one named like the bundle wins, and
            # among those the most derived class does — but they all share the
            # GenericAbility fields, so first match is enough.
            if ability is None:
                ability, ability_class = tree, cls

    if talent is None and ability is None:
        return None

    # The prefab path with its real casing. GameResources.LoadPrefab lowercases
    # the file name and drops the directory, so this is for display and for
    # writing PrefabResource — nothing depends on it resolving literally.
    fallback = ""
    for container_path in (env.container or {}):
        if container_path.endswith(".prefab"):
            fallback = container_path
            break
    tree_by_owner = {key: value for key, value in own.items()}
    path = prefab_path(tree_by_owner, bundle_owner(own), fallback)

    if talent is not None:
        name = resolve(talent.get("DisplayName")) or ""
        if not name:
            return None
        entry = {
            "name": name,
            "kind": "talent",
            "icon": save_icon(by_id, talent.get("Icon"), icons_seen),
            "path": path,
        }
        with_source(entry, "name", talent.get("DisplayName"))
        description = resolve(talent.get("Description"))
        if description:
            entry["desc"] = description
            with_source(entry, "desc", talent.get("Description"))
        category = talent.get("Category")
        if isinstance(category, int) and 0 < category < len(TALENT_CATEGORIES):
            entry["category"] = TALENT_CATEGORIES[category]
        kind = talent.get("Type")
        if isinstance(kind, int) and 0 <= kind < len(TALENT_TYPES):
            entry["type"] = TALENT_TYPES[kind]

        # The Abilities list means two different things. For GrantNewAbility it
        # is what Purchase() instantiates as its own object, so adding the
        # talent to a save has to mint the same packets — the game does not
        # recreate them on load. For ModExistingAbility it is instead the list
        # of abilities the talent modifies, and those mods ARE reapplied on
        # load (CharacterStats.Restored walks m_talents and re-adds them), so
        # the editor must not mint anything for them.
        referenced = []
        for reference in (talent.get("Abilities") or []):
            granted = by_id.get(reference.get("m_PathID"))
            if granted is None:
                continue
            try:
                granted_tree = granted.read_typetree()
            except Exception:
                continue
            granted_name = gameobject_names.get(
                (granted_tree.get("m_GameObject") or {}).get("m_PathID"), "")
            if granted_name:
                referenced.append(granted_name.lower())
        if referenced:
            key = "modifies" if entry.get("type") == "ModExistingAbility" else "grants"
            entry[key] = sorted(set(referenced))

        # Skill bonuses are baked into <Skill>Bonus when the talent is bought
        # and are never recomputed on load, so the editor has to apply them too.
        bonuses = {}
        for bonus in (talent.get("SkillBonuses") or []):
            index = bonus.get("Skill")
            amount = bonus.get("Bonus")
            if isinstance(index, int) and 0 <= index < len(SKILLS) and amount:
                bonuses[SKILLS[index]] = bonuses.get(SKILLS[index], 0) + amount
        if bonuses:
            entry["skills"] = bonuses
        return entry

    name = resolve(ability.get("DisplayName")) or ""
    if not name:
        return None

    spell_level = ability.get("SpellLevel")
    is_spell = isinstance(spell_level, int) and "SpellClass" in ability
    entry = {
        "name": name,
        "kind": "spell" if is_spell else "ability",
        "icon": save_icon(by_id, ability.get("Icon"), icons_seen),
        "path": path,
        # The exact component class, written straight into the packet's
        # TypeString — GenericSpell, Chant, Carnage and ~50 others all appear.
        "component": ability_class or "GenericAbility",
        # InstantiateAbility forces Spell for anything spell-shaped and
        # otherwise keeps the prefab's own value.
        "effect": 3 if is_spell else (ability.get("EffectType") or 5),
    }
    with_source(entry, "name", ability.get("DisplayName"))
    description = resolve(ability.get("Description"))
    if description:
        entry["desc"] = description
        with_source(entry, "desc", ability.get("Description"))
    level = ability.get("AcquisitionLevel")
    if isinstance(level, int) and level:
        entry["level"] = level
    if ability.get("Passive"):
        entry["passive"] = 1
    if is_spell:
        entry["spellLevel"] = spell_level
        owner_class = ability.get("SpellClass")
        if isinstance(owner_class, int) and 0 < owner_class < len(CLASSES):
            entry["class"] = CLASSES[owner_class]
    else:
        # Class abilities are filed under RPG/Abilities/<Class>/, which is the
        # only place the owning class is recorded.
        segments = [s for s in (path or "").replace("\\", "/").split("/")]
        for index, segment in enumerate(segments):
            if segment.lower() == "abilities" and index + 1 < len(segments):
                candidate = segments[index + 1]
                if candidate.lower() in {c.lower() for c in CLASSES[1:]}:
                    entry["class"] = candidate
                break
    return entry


def bundle_owner(own):
    for owner, _ in own:
        return owner
    return ""


def progression_tables():
    """Which abilities and talents each class (and companion) may take.

    AbilityProgressionTable is the game's own answer to that question, so the
    editor can offer a chanter chanter things instead of the whole catalogue.
    Every table is a single ScriptableObject in a bundle named after it.
    """
    tables = {}
    names = [n for n in os.listdir(BUNDLES)
             if n.endswith("abilityprogressiontable")]
    for bundle in sorted(names):
        try:
            env = UnityPy.load(os.path.join(BUNDLES, bundle))
            objects = list(env.objects)
        except Exception:
            continue

        by_id = {o.path_id: o for o in objects}
        gameobject_names = {}
        for obj in objects:
            if obj.type.name == "GameObject":
                try:
                    gameobject_names[obj.path_id] = obj.read().m_Name
                except Exception:
                    pass

        table_tree = None
        for obj in objects:
            if obj.type.name != "MonoBehaviour":
                continue
            try:
                tree = obj.read_typetree()
            except Exception:
                continue
            if "AbilityUnlocks" in tree:
                table_tree = tree
                break
        if table_tree is None:
            continue

        entries = {}
        for unlock in (table_tree.get("AbilityUnlocks") or []):
            target = by_id.get((unlock.get("Ability") or {}).get("m_PathID"))
            if target is None:
                continue
            key = gameobject_names.get(target.path_id)
            if key is None:
                # The unlock points at the prefab's GameObject directly, but a
                # few point at a component instead; fall back through it.
                try:
                    sub = target.read_typetree()
                except Exception:
                    continue
                key = gameobject_names.get(
                    (sub.get("m_GameObject") or {}).get("m_PathID"))
            if not key:
                continue

            # RequirementSets are OR-ed, so a set with no class requirement
            # opens the entry to everyone; only when EVERY set names a class is
            # it really restricted. The shared talent table relies on this —
            # 92 of its 140 entries are class talents sitting next to the
            # general ones, and ignoring that offers a wizard Accurate Carnage.
            sets = unlock.get("RequirementSets") or []
            level = 99
            classes, subraces = set(), set()
            unrestricted = not sets
            player_only = bool(sets)

            for requirement in sets:
                minimum = requirement.get("MinimumLevel")
                if isinstance(minimum, int) and minimum:
                    level = min(level, minimum)

                owner_class = requirement.get("Class") or 0
                subrace = requirement.get("Subrace") or 0
                if 0 < owner_class < len(CLASSES):
                    classes.add(CLASSES[owner_class])
                if 0 < subrace < len(SUBRACES):
                    subraces.add(SUBRACES[subrace])
                if not owner_class and not subrace:
                    unrestricted = True
                if not requirement.get("MustBePlayerCharacter"):
                    player_only = False

            category = unlock.get("Category")
            record = {
                "cat": "Talent" if category == 4 else (
                    "Racial" if category == 2 else "General"),
                "level": 1 if level == 99 else level,
            }
            if not unrestricted and classes:
                record["classes"] = sorted(classes)
            if not unrestricted and subraces:
                record["subraces"] = sorted(subraces)
            if player_only:
                record["player"] = 1
            if unlock.get("UnlockStyle") == 0:
                record["auto"] = 1
            # A key can appear more than once in the same table, once per way
            # of qualifying for it. Merge rather than replace: the easiest
            # level wins, the eligible classes are the union, and one
            # unrestricted route makes the whole entry unrestricted.
            existing = entries.get(key.lower())
            if existing is None:
                entries[key.lower()] = record
            else:
                existing["level"] = min(existing["level"], record["level"])
                for field in ("classes", "subraces"):
                    if field not in record or field not in existing:
                        existing.pop(field, None)
                    else:
                        existing[field] = sorted(
                            set(existing[field]) | set(record[field]))
                if not record.get("player"):
                    existing.pop("player", None)
                if not record.get("auto"):
                    existing.pop("auto", None)

        if entries:
            tables[bundle[:-len("abilityprogressiontable")]] = entries
    return tables


def main():
    os.makedirs(ICONS, exist_ok=True)
    # The bundles the game loads have no extension, each with a .mainasset
    # sidecar naming the prefab inside. The *.unity3d files beside them are the
    # 2015 build's Unity 4 bundles, left on disk by Steam's updates and absent
    # from a clean install (see ingameglobal.py).
    names = sorted(
        n for n in os.listdir(BUNDLES)
        if not n.endswith(".unity3d") and not n.endswith(".mainasset"))

    catalog = {}
    abilities = {}
    saved_icons = set()
    failures = 0

    for index, bundle in enumerate(names, 1):
        if index % 250 == 0:
            print("  %d/%d  (%d catalogued)" % (index, len(names), len(catalog)),
                  flush=True)
        if on_progress and (index % 25 == 0 or index == len(names)):
            on_progress(index, len(names))
        try:
            env = UnityPy.load(os.path.join(BUNDLES, bundle))
            objects = list(env.objects)
        except Exception:
            failures += 1
            continue

        by_id = {o.path_id: o for o in objects}

        # A bundle holds its item AND that item's dependencies, which can
        # include other real items (a belt that summons a weapon ships the
        # weapon's prefab too). Only the component hanging off the GameObject
        # named like the bundle describes the item we were asked about.
        wanted = bundle.lower()
        gameobject_names = {}
        for obj in objects:
            if obj.type.name == "GameObject":
                try:
                    gameobject_names[obj.path_id] = obj.read().m_Name
                except Exception:
                    pass

        script_names = {}
        for obj in objects:
            if obj.type.name == "MonoScript":
                try:
                    script_names[obj.path_id] = obj.read().m_ClassName
                except Exception:
                    pass

        # Abilities and talents live in bundles of exactly the same shape as
        # items, so they get caught here before the item scan gives up on them.
        # An ability is any component carrying EffectType + Icon (every ability
        # class derives from GenericAbility, and there are ~50 of them); a
        # talent is the one carrying Abilities + SkillBonuses.
        own_components = {}
        for obj in objects:
            if obj.type.name != "MonoBehaviour":
                continue
            try:
                tree = obj.read_typetree()
            except Exception:
                continue
            owner = gameobject_names.get(
                (tree.get("m_GameObject") or {}).get("m_PathID"), "")
            if owner.lower() != wanted:
                continue
            cls = script_names.get((tree.get("m_Script") or {}).get("m_PathID"))
            if cls:
                own_components[(owner, cls)] = tree

        entry = ability_entry(
            bundle, wanted, own_components, by_id, gameobject_names,
            script_names, env, saved_icons)
        if entry is not None:
            abilities[wanted] = entry
            continue

        # Soulbound items are the ones carrying an EquipmentSoulbind component.
        soulbound = False
        for obj in objects:
            if obj.type.name != "MonoBehaviour":
                continue
            try:
                tree = obj.read_typetree()
            except Exception:
                continue
            owner = gameobject_names.get(
                (tree.get("m_GameObject") or {}).get("m_PathID"), "")
            if owner.lower() != wanted:
                continue
            if script_names.get(
                (tree.get("m_Script") or {}).get("m_PathID")) == "EquipmentSoulbind":
                soulbound = True
                break

        # The item component is whichever MonoBehaviour carries both a display
        # name and an inventory icon (Equippable, Consumable, Weapon, ...).
        best = None
        for obj in objects:
            if obj.type.name != "MonoBehaviour":
                continue
            try:
                tree = obj.read_typetree()
            except Exception:
                continue
            if "DisplayName" not in tree or "IconTexture" not in tree:
                continue
            name = resolve(tree.get("DisplayName"))
            if not name:
                continue

            owner = gameobject_names.get(
                (tree.get("m_GameObject") or {}).get("m_PathID"), "")
            icon_id = (tree.get("IconTexture") or {}).get("m_PathID", 0)
            exact = owner.lower() == wanted
            candidate = (name, icon_id, tree, exact)

            if best is None:
                best = candidate
            elif exact and not best[3]:
                best = candidate
            elif exact == best[3] and icon_id and not best[1]:
                best = candidate

        if best is None:
            continue

        name, icon_id, tree, _ = best
        icon_file = ""
        if icon_id and icon_id in by_id:
            try:
                texture = by_id[icon_id].read()
                icon_file = re.sub(r"[^A-Za-z0-9_.-]", "_", texture.m_Name) + ".png"
                if icon_file not in saved_icons:
                    texture.image.save(os.path.join(ICONS, icon_file))
                    saved_icons.add(icon_file)
            except Exception:
                icon_file = ""

        # Which equipment slots this item is legal in. The names match the
        # game's own Equippable flags so the editor can refuse nonsense like
        # a sword in the boots slot.
        slots = [flag for flag in SLOT_FLAGS if tree.get(flag)]

        # Quality drives the tile tint, in the same order the game ranks it:
        # soulbound beats unique beats an enchantment-quality mod.
        quality = ""
        if soulbound:
            quality = "soulbound"
        elif tree.get("Unique"):
            quality = "unique"
        else:
            for reference in (tree.get("ItemMods") or []):
                mod = by_id.get(reference.get("m_PathID"))
                if mod is None:
                    continue
                try:
                    mod_tree = mod.read_typetree()
                except Exception:
                    continue
                mod_name = gameobject_names.get(
                    (mod_tree.get("m_GameObject") or {}).get("m_PathID"), "")
                if mod_name.split("_")[0] in ("Fine", "Exceptional", "Superb", "Legendary"):
                    quality = "fine"
                    break

        # The prefab path a save records in InventoryItem.BaseItem. The bundle
        # container only keeps it lowercased; Persistence.Prefab has the real
        # casing, which is what the game itself writes into an object's name.
        # Nothing depends on it — GameResources.LoadPrefab reduces the path to
        # its lowercased file name — but an edited save that spells items the
        # way the game does is a lot easier to compare against one that wasn't.
        entry_path = ""
        for container_path in (env.container or {}):
            if container_path.endswith(".prefab"):
                entry_path = container_path
                break

        for (owner, cls), component in own_components.items():
            if cls == "Persistence" and component.get("Prefab"):
                entry_path = component["Prefab"]
                break

        entry = {"name": name, "icon": icon_file}
        with_source(entry, "name", tree.get("DisplayName"))
        stack = tree.get("MaxStackSize")
        if isinstance(stack, int) and stack > 1:
            entry["maxStack"] = stack
        if slots:
            entry["slots"] = slots
        if tree.get("IsQuestItem"):
            entry["quest"] = 1
        # Value is a CurrencyValue struct ({"v": 50.0}), not a bare number, so
        # the obvious isinstance check silently dropped every price. Base value
        # only: Equippable.GetValue adds each item mod's Cost on top, so an
        # enchanted weapon is worth more than this says.
        # What the game would price this at. Item.GetValue returns the base
        # Value, and Equippable.GetValue adds every mod's Cost on top —
        # multiplied by the economy's ItemModCostMultiplier, and doubled for a
        # weapon that occupies both hands. Fine/Exceptional/Superb versions are
        # separate prefabs carrying the mod, so summing here covers almost
        # everything a save contains.
        value = tree.get("Value")
        if isinstance(value, dict):
            value = value.get("v")
        if not isinstance(value, (int, float)):
            value = 0

        hands = 2 if tree.get("BothPrimaryAndSecondarySlot") else 1
        for reference in (tree.get("ItemMods") or []):
            mod = by_id.get(reference.get("m_PathID"))
            if mod is None:
                continue
            try:
                mod_tree = mod.read_typetree()
            except Exception:
                continue
            cost = mod_tree.get("Cost")
            if isinstance(cost, (int, float)):
                value += cost * ITEM_MOD_COST_MULTIPLIER * hands

        if value:
            entry["value"] = int(value)
        if tree.get("FullValueSell"):
            # Stores buy these back at full price instead of the usual fifth.
            entry["fullValueSell"] = 1
        if quality:
            entry["quality"] = quality
        if entry_path:
            entry["path"] = entry_path


        filter_type = tree.get("FilterType")
        if isinstance(filter_type, int) and filter_type:
            entry["filter"] = filter_type

        # Empty means every class can use it.
        restricted = [
            CLASSES[c] for c in (tree.get("RestrictedToClass") or [])
            if isinstance(c, int) and 0 < c < len(CLASSES)]
        if restricted:
            entry["classes"] = restricted

        catalog[bundle.lower()] = entry

    with open(os.path.join(OUT, "catalog.json"), "w", encoding="utf-8") as handle:
        json.dump(catalog, handle, ensure_ascii=False, indent=0, sort_keys=True)

    with open(os.path.join(OUT, "abilities.json"), "w", encoding="utf-8") as handle:
        json.dump(abilities, handle, ensure_ascii=False, indent=0, sort_keys=True)

    print("  reading progression tables...", flush=True)
    tables = progression_tables()
    with open(os.path.join(OUT, "progression.json"), "w", encoding="utf-8") as handle:
        json.dump(tables, handle, ensure_ascii=False, indent=0, sort_keys=True)
    print("  %d progression tables, %d unlocks"
          % (len(tables), sum(len(t) for t in tables.values())))

    kinds = {}
    for entry in abilities.values():
        kinds[entry["kind"]] = kinds.get(entry["kind"], 0) + 1
    print("DONE: %d items, %s, %d icons, %d bundles unreadable"
          % (len(catalog),
             ", ".join("%d %ss" % (v, k) for k, v in sorted(kinds.items())),
             len(saved_icons), failures))


if __name__ == "__main__":
    sys.exit("Run extract_gamedata.py, which runs this with the game and output folders.")
