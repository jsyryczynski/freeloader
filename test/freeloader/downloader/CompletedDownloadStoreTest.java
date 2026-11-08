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

import junit.framework.TestCase;
import freeloader.torrent.Hash;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class CompletedDownloadStoreTest extends TestCase {

  private File tempDir;

  protected void setUp() throws Exception {
    tempDir = Files.createTempDirectory("freeloader-history-test").toFile();
    CompletedDownloadStore.setHistoryDirectoryForTesting(tempDir);
  }

  protected void tearDown() throws Exception {
    deleteRecursively(tempDir);
  }

  public void testAddThenLoadRoundTrips() throws Exception {
    Hash infoHash = Hash.forByteArray("finished torrent one".getBytes());
    File outputDirectory = new File("/tmp/some-output-dir");

    CompletedDownloadStore.add(infoHash, "Some Torrent", outputDirectory, 123456L, 1_000_000L);

    List<CompletedDownloadStore.Entry> entries = CompletedDownloadStore.loadAll();
    assertEquals(1, entries.size());
    CompletedDownloadStore.Entry entry = entries.get(0);
    assertEquals(infoHash, entry.infoHash);
    assertEquals("Some Torrent", entry.name);
    assertEquals(outputDirectory.getAbsolutePath(), entry.outputDirectory.getAbsolutePath());
    assertEquals(123456L, entry.totalLength);
    assertEquals(1_000_000L, entry.completedAtMillis);
  }

  public void testAddTwiceForSameHashOverwritesRatherThanDuplicates() throws Exception {
    Hash infoHash = Hash.forByteArray("finished torrent two".getBytes());
    CompletedDownloadStore.add(infoHash, "First Name", new File("/tmp/dir-a"), 111L, 1000L);
    CompletedDownloadStore.add(infoHash, "Second Name", new File("/tmp/dir-b"), 222L, 2000L);

    List<CompletedDownloadStore.Entry> entries = CompletedDownloadStore.loadAll();
    assertEquals(1, entries.size());
    assertEquals("Second Name", entries.get(0).name);
    assertEquals(2000L, entries.get(0).completedAtMillis);
  }

  public void testLoadAllOrdersMostRecentlyCompletedFirst() throws Exception {
    Hash hashOne = Hash.forByteArray("finished torrent three".getBytes());
    Hash hashTwo = Hash.forByteArray("finished torrent four".getBytes());
    CompletedDownloadStore.add(hashOne, "Older", new File("/tmp/dir-one"), 100L, 1000L);
    CompletedDownloadStore.add(hashTwo, "Newer", new File("/tmp/dir-two"), 200L, 2000L);

    List<CompletedDownloadStore.Entry> entries = CompletedDownloadStore.loadAll();
    assertEquals(2, entries.size());
    assertEquals("Newer", entries.get(0).name);
    assertEquals("Older", entries.get(1).name);
  }

  public void testRemoveDropsTheEntry() throws Exception {
    Hash infoHash = Hash.forByteArray("finished torrent five".getBytes());
    CompletedDownloadStore.add(infoHash, "To Remove", new File("/tmp/dir-five"), 300L, 3000L);
    assertEquals(1, CompletedDownloadStore.loadAll().size());

    CompletedDownloadStore.remove(infoHash);

    assertTrue(CompletedDownloadStore.loadAll().isEmpty());
  }

  public void testLoadAllOnEmptyStoreReturnsEmptyList() {
    assertTrue(CompletedDownloadStore.loadAll().isEmpty());
  }

  private void deleteRecursively(File file) {
    File[] children = file.listFiles();
    if (children != null) {
      for (File child : children) {
        deleteRecursively(child);
      }
    }
    file.delete();
  }
}
