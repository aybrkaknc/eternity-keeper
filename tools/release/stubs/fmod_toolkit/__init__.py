# Stands in for fmod_toolkit in the frozen game-data reader.
#
# UnityPy's export package imports its audio converter, and with it
# fmod_toolkit, whenever anything is exported -- including the item icons the
# reader writes. The real fmod_toolkit loads FMOD's DLL on import, and FMOD is
# proprietary: it must not ship. The reader never touches audio, so this
# module only has to exist; build-release.ps1 puts it ahead of the real one.

__version__ = "0.0.0-stub"


def _no_audio(*args, **kwargs):
    raise NotImplementedError(
        "Eternity Keeper's game-data reader does not export audio (FMOD is not included).")


get_pyfmodex_system_instance = _no_audio
raw_to_wav = _no_audio
sound_to_wav = _no_audio
subsound_to_wav = _no_audio
