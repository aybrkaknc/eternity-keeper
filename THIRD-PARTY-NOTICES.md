# Third-party notices

Eternity Keeper is free software under the GNU General Public License, version 3
or later (see `LICENSE`). The Windows download also carries the software below,
each under its own license. Nothing in it comes from Pillars of Eternity:
item names, icons and other game data are read from **your own** installation,
on your machine, when you ask the editor to.

Eternity Keeper is an unofficial fan project. It is not affiliated with, endorsed
by or sponsored by Obsidian Entertainment or Paradox Interactive. Pillars of
Eternity is a trademark of its respective owners.

## Java runtime (`jre\`)

| Component | License |
|---|---|
| Eclipse Temurin 8 JRE (OpenJDK 8u492-b09) | GNU GPL v2 with the Classpath Exception. Its own `LICENSE`, `ASSEMBLY_EXCEPTION` and `THIRD_PARTY_README` are inside `jre\`. Corresponding source: <https://github.com/adoptium/jdk8u>, tag `jdk8u492-b09` |

## Embedded browser (`lib\native\win64\`, and inside `eternity-keeper.jar`)

| Component | License |
|---|---|
| Java Chromium Embedded Framework (JCEF) | BSD 3-Clause, © The Chromium Embedded Framework Authors |
| Chromium Embedded Framework (CEF) and Chromium (`libcef.dll` and resources) | BSD 3-Clause, © The Chromium Authors, plus the licenses of Chromium's own third-party components, listed by Chromium's `about:credits` |
| JOGL and GlueGen | BSD 2-Clause (JogAmp Community) |

## Libraries inside `eternity-keeper.jar`

| Component | Version | License |
|---|---|---|
| JSON in Java (org.json) | 20250517 | Public domain |
| Apache Commons IO | 2.22.0 | Apache 2.0 |
| zip4j | 2.11.6 | Apache 2.0 |
| jOOX | 1.3.0 | Apache 2.0 |
| jOOλ | 0.9.6 | Apache 2.0 |
| Joda-Time | 2.7 | Apache 2.0 |
| Guava, failureaccess, listenablefuture | 33.7.2-jre | Apache 2.0 |
| Error Prone annotations | 2.50.0 | Apache 2.0 |
| J2ObjC annotations | 3.1 | Apache 2.0 |
| JSpecify annotations | 1.0.1 | Apache 2.0 |
| Logback | 1.3.16 | EPL 1.0 or LGPL 2.1 (dual) |
| SLF4J API | 2.0.7 | MIT |
| Jargo | 0.1.1 | Apache 2.0 |

The save-file reader in `uk.me.mantas.eternity.serializer` is a Java
reimplementation written against the source of
[SharpSerializer](https://github.com/polenter/SharpSerializer) (MIT License,
© Pawel Idzikowski), the library Pillars of Eternity serializes its saves with.

## Game-data reader (`gamedata\`)

A Python program frozen with PyInstaller. It reads your game install and writes
item, ability, stronghold and deity data into Eternity Keeper's data folder.

| Component | License |
|---|---|
| Python 3.14 runtime | Python Software Foundation License |
| PyInstaller bootloader | GPL v2 or later, with the bootloader exception that allows distributing frozen programs under any license |
| UnityPy | MIT |
| TypeTreeGeneratorAPI | MIT; bundles Capstone (BSD 3-Clause) |
| Pillow | MIT-CMU (HPND) |
| texture2ddecoder, etcpak, astc-encoder-py | MIT |
| lz4 (Python bindings and library) | BSD 3-Clause |
| Brotli | MIT |
| attrs, charset-normalizer, setuptools, tqdm | MIT (tqdm: MIT and MPL 2.0) |
| fsspec | BSD 3-Clause |
| certifi | MPL 2.0, unmodified; source at <https://github.com/certifi/python-certifi> |
| OpenSSL 3 (`libssl-3.dll`, `libcrypto-3.dll`) | Apache 2.0 |
| libffi | MIT |
| Microsoft Visual C++ runtime and Universal CRT DLLs | Redistributable under the Microsoft Visual Studio license terms |

FMOD, which UnityPy can use for audio, is deliberately left out: it is
proprietary and the reader never touches audio.

## Artwork

The application icon was created by
[Alexander Loginov](http://alexanderloginov.deviantart.com/).
