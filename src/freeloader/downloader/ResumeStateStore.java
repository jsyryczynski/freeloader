/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2022-2026 Jan Syryczyński
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 */

package freeloader.downloader;

import org.apache.log4j.Logger;
import freeloader.torrent.Hash;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Remembers which downloads were active so they can be resumed the next
 * time the app starts, instead of being lost when it's closed.
 *
 * Actual file-data resume (picking up already-downloaded pieces rather
 * than starting over) already happens automatically: Storage/PieceManager
 * re-validate whatever bytes already exist in a download's output
 * directory against the torrent's piece hashes every time a
 * TorrentDownload is constructed. What was missing is remembering *which*
 * torrents to reconstruct after a restart -- that's what this class
 * covers.
 *
 * For each active download, a canonical copy of its .torrent-equivalent
 * bencoded bytes (the original file for a regular download, or the
 * dictionary synthesized from fetched magnet metadata) is stored under
 * the resume directory, named by info hash, alongside a small index file
 * recording the output directory and a couple of settings needed to
 * reconstruct a TorrentDownloadConfiguration.
 */
public class ResumeStateStore {

  private static final Logger logger = Logger.getLogger(ResumeStateStore.class);

  private static final File DEFAULT_RESUME_DIRECTORY = new File(System.getProperty("user.home"), ".freeloader-resume");
  private static File resumeDir = DEFAULT_RESUME_DIRECTORY;

  /** Package-private: lets tests point this at a throwaway temp directory instead of the real home directory. */
  static void setResumeDirectoryForTesting(File directory) {
    resumeDir = directory;
  }

  private static File indexFile() {
    return new File(resumeDir, "active-downloads.txt");
  }

  public static class Entry {
    public final Hash infoHash;
    public final File metaFile;
    public final File outputDirectory;
    public final int listeningPort;
    public final double shareRatio;

    Entry(Hash infoHash, File metaFile, File outputDirectory, int listeningPort, double shareRatio) {
      this.infoHash = infoHash;
      this.metaFile = metaFile;
      this.outputDirectory = outputDirectory;
      this.listeningPort = listeningPort;
      this.shareRatio = shareRatio;
    }
  }

  private ResumeStateStore() {
  }

  /**
   * Records a download as active, so it'll be resumed automatically if
   * the app is restarted before it finishes. Safe to call repeatedly for
   * the same torrent (e.g. once at startup); later calls just overwrite
   * the entry.
   */
  public static synchronized void add(
      Hash infoHash, byte[] metaInfoBytes, File outputDirectory, int listeningPort, double shareRatio) {
    try {
      resumeDir.mkdirs();
      Files.write(metaFileFor(infoHash).toPath(), metaInfoBytes);

      String key = infoHash.toString();
      String newLine = formatLine(infoHash, outputDirectory, listeningPort, shareRatio);
      List<String> updated = new ArrayList<String>();
      boolean replaced = false;
      for (String line : readLinesQuietly()) {
        if (line.startsWith(key + "\t")) {
          updated.add(newLine);
          replaced = true;
        } else {
          updated.add(line);
        }
      }
      if (!replaced) {
        updated.add(newLine);
      }
      writeLines(updated);
    } catch (IOException e) {
      logger.warn("could not persist resume state for " + infoHash, e);
    }
  }

  /** Stops tracking a download as active, e.g. because it finished. */
  public static synchronized void remove(Hash infoHash) {
    try {
      String key = infoHash.toString();
      List<String> updated = new ArrayList<String>();
      for (String line : readLinesQuietly()) {
        if (!line.startsWith(key + "\t")) {
          updated.add(line);
        }
      }
      writeLines(updated);
      metaFileFor(infoHash).delete();
    } catch (IOException e) {
      logger.warn("could not remove resume state for " + infoHash, e);
    }
  }

  /** Everything that was still active last time the app ran. */
  public static synchronized List<Entry> loadAll() {
    List<Entry> result = new ArrayList<Entry>();
    for (String line : readLinesQuietly()) {
      Entry entry = parseLine(line);
      if (entry != null) {
        result.add(entry);
      }
    }
    return result;
  }

  private static File metaFileFor(Hash infoHash) {
    return new File(resumeDir, infoHash.toString() + ".torrent");
  }

  private static String formatLine(Hash infoHash, File outputDirectory, int listeningPort, double shareRatio) {
    return infoHash.toString() + "\t" + outputDirectory.getAbsolutePath() + "\t" + listeningPort + "\t" + shareRatio;
  }

  private static Entry parseLine(String line) {
    String[] parts = line.split("\t");
    if (parts.length != 4) {
      return null;
    }
    try {
      Hash infoHash = Hash.fromHexString(parts[0]);
      File outputDirectory = new File(parts[1]);
      int listeningPort = Integer.parseInt(parts[2]);
      double shareRatio = Double.parseDouble(parts[3]);
      File metaFile = metaFileFor(infoHash);
      if (!metaFile.canRead()) {
        return null;
      }
      return new Entry(infoHash, metaFile, outputDirectory, listeningPort, shareRatio);
    } catch (Exception e) {
      logger.warn("skipping unreadable resume entry: " + line, e);
      return null;
    }
  }

  private static List<String> readLinesQuietly() {
    try {
      if (!indexFile().canRead()) {
        return new ArrayList<String>();
      }
      return Files.readAllLines(indexFile().toPath(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      logger.warn("could not read resume state", e);
      return new ArrayList<String>();
    }
  }

  private static void writeLines(List<String> lines) throws IOException {
    resumeDir.mkdirs();
    Files.write(indexFile().toPath(), lines, StandardCharsets.UTF_8);
  }
}
