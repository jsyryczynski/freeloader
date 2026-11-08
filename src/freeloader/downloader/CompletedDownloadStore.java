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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Remembers downloads that finished, so they stay visible (name, size,
 * output directory, when they finished) after the app is closed and
 * reopened, rather than just disappearing once ResumeStateStore stops
 * tracking them as active. This is purely a history/display record --
 * it does not reconstruct a live TorrentDownload or touch the
 * downloaded files in any way.
 */
public class CompletedDownloadStore {

  private static final Logger logger = Logger.getLogger(CompletedDownloadStore.class);

  private static final File DEFAULT_HISTORY_DIRECTORY = new File(System.getProperty("user.home"), ".freeloader-history");
  private static File historyDir = DEFAULT_HISTORY_DIRECTORY;

  /** Package-private: lets tests point this at a throwaway temp directory instead of the real home directory. */
  static void setHistoryDirectoryForTesting(File directory) {
    historyDir = directory;
  }

  private static File indexFile() {
    return new File(historyDir, "completed-downloads.txt");
  }

  public static class Entry {
    public final Hash infoHash;
    public final String name;
    public final File outputDirectory;
    public final long totalLength;
    public final long completedAtMillis;

    Entry(Hash infoHash, String name, File outputDirectory, long totalLength, long completedAtMillis) {
      this.infoHash = infoHash;
      this.name = name;
      this.outputDirectory = outputDirectory;
      this.totalLength = totalLength;
      this.completedAtMillis = completedAtMillis;
    }
  }

  private CompletedDownloadStore() {
  }

  /**
   * Records a download as finished. Safe to call repeatedly for the same
   * torrent; later calls just overwrite the entry (e.g. with a newer
   * completion timestamp).
   */
  public static synchronized void add(
      Hash infoHash, String name, File outputDirectory, long totalLength, long completedAtMillis) {
    try {
      historyDir.mkdirs();
      String key = infoHash.toString();
      String newLine = formatLine(infoHash, name, outputDirectory, totalLength, completedAtMillis);
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
      logger.warn("could not persist completed-download history for " + infoHash, e);
    }
  }

  /** Drops a download from the history list (e.g. the user cleared it). */
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
    } catch (IOException e) {
      logger.warn("could not remove completed-download history entry for " + infoHash, e);
    }
  }

  /** Everything that has finished downloading, most recently completed first. */
  public static synchronized List<Entry> loadAll() {
    List<Entry> result = new ArrayList<Entry>();
    for (String line : readLinesQuietly()) {
      Entry entry = parseLine(line);
      if (entry != null) {
        result.add(entry);
      }
    }
    Collections.sort(result, new Comparator<Entry>() {
      public int compare(Entry a, Entry b) {
        return Long.compare(b.completedAtMillis, a.completedAtMillis);
      }
    });
    return result;
  }

  private static String formatLine(Hash infoHash, String name, File outputDirectory, long totalLength, long completedAtMillis) {
    return infoHash.toString() + "\t" + completedAtMillis + "\t" + totalLength + "\t"
        + outputDirectory.getAbsolutePath() + "\t" + name;
  }

  private static Entry parseLine(String line) {
    String[] parts = line.split("\t", 5);
    if (parts.length != 5) {
      return null;
    }
    try {
      Hash infoHash = Hash.fromHexString(parts[0]);
      long completedAtMillis = Long.parseLong(parts[1]);
      long totalLength = Long.parseLong(parts[2]);
      File outputDirectory = new File(parts[3]);
      String name = parts[4];
      return new Entry(infoHash, name, outputDirectory, totalLength, completedAtMillis);
    } catch (Exception e) {
      logger.warn("skipping unreadable completed-download history entry: " + line, e);
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
      logger.warn("could not read completed-download history", e);
      return new ArrayList<String>();
    }
  }

  private static void writeLines(List<String> lines) throws IOException {
    historyDir.mkdirs();
    Files.write(indexFile().toPath(), lines, StandardCharsets.UTF_8);
  }
}
