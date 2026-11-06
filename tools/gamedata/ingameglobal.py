# The InGameGlobal prefab: where the game keeps it, and its behaviours as
# typetrees. The Stronghold behaviour (stronghold.py) and the Religion
# behaviour (identity.py) both hang off it.
#
# The game loads the prefab with Resources.Load("Prefabs/InGameGlobal"), so
# it lives in PillarsOfEternity_Data/resources.assets, the player's own data
# file, along with the icons and the hireling prefabs it points at.
#
# Until 2026-10-09 this reader opened objectbundle/ingameglobal.unity3d
# instead. That file is the 2015 build's Unity 4.6 bundle, which the current
# game (Unity 2019.4, since the 2025 rebuild) neither loads nor can load:
# Steam's updates left it on disk beside 2,998 other *.unity3d leftovers, a
# clean install has no such file (a GOG player reported the reader stopping
# on "no ingameglobal bundle"), and what it held was stale -- two upgrade
# costs and two hirelings differed from what the game has now.
#
# The prefab's MonoBehaviours are stored by script reference alone, so
# shaping them needs a typetree generated from Assembly-CSharp.dll
# (TypeTreeGeneratorAPI; load_local_game wants the game's root folder, not
# Managed).
import os
import sys

import UnityPy
from UnityPy.helpers.TypeTreeGenerator import TypeTreeGenerator

NAME = "InGameGlobal"

# Both stages read the same file in the same run, and the typetree generator
# is the slow part, so the second asks for what the first loaded.
_loaded = {}


def resources_file(game_root):
    return os.path.join(game_root, "PillarsOfEternity_Data", "resources.assets")


def load(game_root):
    """The prefab's behaviours as typetrees, and every object of the file that
    holds them by path id (icons and prefabs are pointers into it), as
    (trees, by_id).

    Stops the stage with the reason (sys.exit, which extract_gamedata.py
    reports to the editor) when the install has no resources.assets or no
    InGameGlobal in it.
    """
    if game_root in _loaded:
        return _loaded[game_root]

    path = resources_file(game_root)
    if not os.path.isfile(path):
        sys.exit("no %s: the game's data file that holds the InGameGlobal prefab"
                 % path)

    env = UnityPy.load(path)
    objects = list(env.objects)

    root = None
    for obj in objects:
        if obj.type.name != "GameObject":
            continue
        try:
            if obj.read().m_Name == NAME:
                root = obj
                break
        except Exception:
            continue
    if root is None:
        sys.exit("no InGameGlobal prefab in %s" % path)

    version = next((a.unity_version for a in env.assets
                    if getattr(a, "unity_version", None)), "2019.4.41f1")
    generator = TypeTreeGenerator(version)
    generator.load_local_game(game_root)
    env.typetree_generator = generator

    trees = []
    for pair in root.read().m_Components:
        pointer = getattr(pair, "component", pair)
        reader = pointer.deref() if hasattr(pointer, "deref") else pointer
        if reader.type.name != "MonoBehaviour":
            continue
        try:
            trees.append(reader.read_typetree())
        except Exception:
            # A behaviour whose class the assembly no longer has.
            continue

    _loaded[game_root] = (trees, {obj.path_id: obj for obj in objects})
    return _loaded[game_root]


def prefab_name(target):
    """The name of the prefab a pointer lands on: the GameObject's own, or that
    of the GameObject a component belongs to (HirelingPrefab points at the
    hireling's CharacterStats). Empty when it cannot be read."""
    if target is None:
        return ""

    try:
        data = target.read()
        if target.type.name == "GameObject":
            return data.m_Name or ""
        owner = data.m_GameObject
        owner = owner.deref() if hasattr(owner, "deref") else owner
        return owner.read().m_Name or ""
    except Exception:
        return ""
