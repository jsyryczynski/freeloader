# Freeloader

Freeloader is a BitTorrent client that downloads at full speed without
uploading anything. Trackers are told a plausible upload figure, so the
client doesn't stand out.

It runs as a window (JavaFX) or entirely from the command line; the window
is optional.

## Features

- **.torrent files and magnet links.** For a magnet link, the torrent's
  details are fetched from other peers first.
- **Finding peers:** trackers (HTTP and UDP), the DHT, and peer exchange
  (peers telling us about other peers). Peers that can't be reached after
  three attempts are dropped.
- **Encrypted connections** (BitTorrent's message stream encryption), with
  a fallback to unencrypted for peers that don't support it.
- **Speed limit** in MB/s, for all downloads together.
- **Resuming:** stopped and unfinished downloads pick up where they left
  off, after checking the data already on disk.
- **Nothing about peers or trackers is saved.** What Freeloader learns
  about them is kept in memory only and gone when it exits.

## What other clients and trackers see

**Uploading.** Freeloader never sends any downloaded data to anyone: it
never unchokes a peer and never answers a request for a piece (the tests
check this). What it reports is a different matter:

- The torrent's main tracker is told an upload total that matches the
  download so far times a share ratio, so the client looks like an
  ordinary one that shares. The ratio is picked at random between 1.00
  and 1.33 for each .torrent added in the window, and is 1.092 for
  magnet links and command-line downloads. The figure is made up;
  nothing is actually uploaded.
- The extra announces made while searching for peers (to every tracker
  in the torrent, HTTP and UDP) report 0 uploaded and 0 downloaded.
- Other peers are not told any upload figure; the BitTorrent protocol
  has no message for it.

**Identifying itself.** Freeloader presents itself as **BitTorrent 4.4.0**,
the official BitTorrent client of that version, never as Freeloader or
BitThief:

- its peer ID starts with `M4-4-0--` (the rest is random, and new for
  each download), which is how BitTorrent 4.4.0 marks its peer IDs;
- it sends `BitTorrent/4.4.0` as the User-Agent to HTTP trackers;
- its extension handshake carries no client name (the optional `v`
  field is left out), and neither do its DHT messages.

## Download

From the [Releases page](https://github.com/jsyryczynski/freeloader/releases):

- **Freeloader-<version>-setup.exe**: run it to install Freeloader for your
  Windows user (no administrator rights needed), with a Start menu entry
  and an uninstaller.
- **Freeloader-<version>.7z**: no install. Unpack it anywhere and run
  `Freeloader.exe` in the `Freeloader` folder. Windows 11 opens `.7z`
  files in Explorer; on older Windows use [7-Zip](https://www.7-zip.org/).

No Java install is needed either way. The files aren't code-signed, so
Windows may warn "Windows protected your PC" the first time: choose "More
info", then "Run anyway".

Each release also has `SHA256SUMS.txt`, the SHA-256 checksum of each file.
To check that a download is complete and unchanged, compare it with what
`Get-FileHash <file>` prints in PowerShell.

## Running it

### The window

Start `Freeloader.exe` (or the jar) with no options. Add downloads from the
menu, or drop `.torrent` files and magnet links onto the window. The table
lists live downloads on top and finished ones from earlier runs below. The
selected download's overview and statistics charts are shown underneath.
View -> Dark Theme switches to the dark theme.

### The command line

Give a torrent or magnet link and a folder. Freeloader then downloads
without opening the window, shows its progress on one line, and exits when
the download is finished:

```
Freeloader.exe -m ubuntu.torrent -o D:\Downloads
Freeloader.exe -m "magnet:?xt=urn:btih:..." -o D:\Downloads --limit 1.5
```

Put magnet links in quotes: they contain `&`. If a download is interrupted
(Ctrl+C stops it), run the same command again to continue it.

| Option | |
|---|---|
| `-m`, `--metafile` | the .torrent file or magnet link to download |
| `-o`, `--output` | the folder to save it in (needed with `-m`) |
| `-l`, `--limit` | download speed limit in MB/s, e.g. `1.5` |
| `-p`, `--port` | listening port (default 6811) |
| `-g`, `--gui` | open the window too, with the download in it |
| `-q`, `--quitonfinish` | with `--gui`: quit when the download finishes |
| `-h`, `--help` | list the options |

Command-line downloads stay out of the window's resume list and history.

### Where things are kept

- **Window settings** (speed limit, theme, last folder): the Java
  preferences of your Windows user (in the registry, under
  `HKEY_CURRENT_USER\Software\JavaSoft\Prefs`).
- **Downloads to resume:** `%USERPROFILE%\.freeloader-resume`.
- **Finished downloads shown in the window:** `%USERPROFILE%\.freeloader-history`.

## Building

### Prerequisites: Java 25 and Maven on Windows

Both are a zip you extract and point `PATH`/`JAVA_HOME` at; no installer.

**Java 25** (any JDK 25 build works, e.g. Eclipse Temurin or Amazon Corretto):

1. Download a JDK 25 zip and extract it somewhere permanent -- not `Downloads`,
   since that's meant to be temporary. For example:
   ```
   C:\Java\jdk-25
   ```
2. Set `JAVA_HOME` to that folder (the JDK root, not its `bin` subfolder) and
   add `%JAVA_HOME%\bin` to `PATH`:
   - Press **Win**, type `environment variables`, open
     "Edit environment variables for your account"
   - Under "User variables," click **New...** -- name `JAVA_HOME`, value the
     path above
   - Still under "User variables," select `Path` -> **Edit** -> **New** ->
     add `%JAVA_HOME%\bin`
   - OK everything, then open a **new** terminal window (already-open ones
     won't see the change)
3. Verify: `java -version` should print `25.x`.

**Maven** (only needed for the jar and the tests; the `.exe` build below
doesn't use it):

1. Download the Binary zip from https://maven.apache.org/download.cgi and
   extract it somewhere permanent, e.g.:
   ```
   C:\Java\apache-maven
   ```
2. Add its `bin` folder to `PATH` the same way as above (User variables ->
   `Path` -> Edit -> New -> `C:\Java\apache-maven\bin`)
3. New terminal window, verify: `mvn -v` should print the Maven version and
   confirm it's running on Java 25.

(Avoid `setx` for this -- it silently truncates `PATH` if the value goes over
1024 characters, which a typical Windows `PATH` often already is. The GUI
editor above doesn't have that problem.)

### Windows executable

```
build-exe.bat
dist\Freeloader\Freeloader.exe
```

`build-exe.bat` downloads the libraries it needs into `lib\`, compiles, and
packages a self-contained `Freeloader.exe` with `jpackage`. The
`dist\Freeloader` folder includes its own Java runtime, so it runs without
a Java install and can be copied anywhere. It uses `JAVA_HOME` if that is a
JDK 25. Otherwise put `set "JDK25=<your JDK 25 folder>"` in a
`build-exe.local.bat` next to it; that file is never committed.

The runtime is built with `jlink` and holds only the JDK modules the app
uses (listed in `build-exe.bat`) plus JavaFX, without debug data. If a new
feature needs another JDK module, add it to the `JDKMODULES` line there.

The script also packs `dist\Freeloader-<version>.7z` (about 22 MB), the file
to hand out: unpacked, it is the same `Freeloader` folder (about 95 MB).
Windows 11 opens `.7z` files in Explorer; older Windows needs 7-Zip. The
runtime is left uncompressed on purpose: the `.7z` compresses it far better
that way (a zip of a compressed runtime is about 42 MB).

### Jar and tests (Maven)

```
mvn clean package
java -jar target/freeloader-0.2.1.jar --help
```

`mvn clean package` also runs the tests (`mvn test` runs only those). The
jar carries the JavaFX native libraries of the OS that built it, so build
it on the OS you'll run it on. Run this way, with JavaFX on the plain
classpath, the window prints a harmless "Unsupported JavaFX configuration"
warning on startup; the `.exe` doesn't.

### Pull requests

Changes go through pull requests to `master`. The tests run on every pull
request, and it can only be merged once they pass. Claude also reviews
each pull request from a branch of this repository and comments on it
(`.github/workflows/claude-review.yml`); Dependabot opens weekly pull
requests for newer library and action versions.

### Releasing

1. Set the new version in `static/version.properties` and `pom.xml` (a
   test checks that they match) and merge that.
2. Tag the commit and push the tag:
   ```
   git tag v0.3.0
   git push origin v0.3.0
   ```

The Release workflow (`.github/workflows/release.yml`) then builds the app
on Windows and publishes a release with `Freeloader-<version>-setup.exe` (an installer
built by jpackage with WiX), `Freeloader-<version>.7z` and
`Freeloader-<version>-source.zip`, the source code of that version. The
installer is tested on the way: installed silently, started and
uninstalled. A
tag that doesn't match the version stops it. Running the workflow by hand
only builds, to check that a release would work.

Each release also gets `SHA256SUMS.txt` with the files' checksums, and
its notes list the pull requests merged since the previous release.

## License

Freeloader is free software under the **GNU General Public License,
version 2 or (at your option) any later version**; the full text is in
[LICENSE](LICENSE). You may use, copy, change and pass it on, also for
money, as long as what you pass on stays under the same license and comes
with its source code. It comes with no warranty. The copyright notices are
in the source files.

### BitThief

Freeloader uses code from **BitThief** by Patrick Moor (Copyright (C)
2006 Patrick Moor), licensed under the GNU General Public License,
version 2 or (at your option) any later version. It is used for:

- communication with peers: connections, handshakes and the messages
  peers exchange;
- trackers and the DHT, for finding peers;
- storing downloaded pieces on disk and checking them;
- reading torrent files (bencoding), and the download statistics.

### Libraries

The app is built with, and its download includes, these libraries under
their own licenses:

| Library | License |
|---|---|
| OpenJDK runtime (Eclipse Temurin) | GPL v2 with the Classpath Exception |
| JavaFX (OpenJFX) | GPL v2 with the Classpath Exception |
| AtlantaFX | MIT |
| Apache Commons CLI, Apache Commons Codec | Apache License 2.0 |
| reload4j | Apache License 2.0 |

The Apache License 2.0 is compatible with version 3 of the GPL, which this
license allows ("or any later version").
