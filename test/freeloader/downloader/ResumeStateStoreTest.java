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

public class ResumeStateStoreTest extends TestCase {

  private File tempDir;

  protected void setUp() throws Exception {
    tempDir = Files.createTempDirectory("freeloader-resume-test").toFile();
    ResumeStateStore.setResumeDirectoryForTesting(tempDir);
  }

  protected void tearDown() throws Exception {
    deleteRecursively(tempDir);
  }

  public void testAddThenLoadRoundTrips() throws Exception {
    Hash infoHash = Hash.forByteArray("torrent one".getBytes());
    byte[] fakeTorrentBytes = "d4:infod6:lengthi123eee".getBytes();
    File outputDirectory = new File("/tmp/some-output-dir");

    ResumeStateStore.add(infoHash, fakeTorrentBytes, outputDirectory, 6881, 1.5);

    List<ResumeStateStore.Entry> entries = ResumeStateStore.loadAll();
    assertEquals(1, entries.size());
    ResumeStateStore.Entry entry = entries.get(0);
    assertEquals(infoHash, entry.infoHash);
    assertEquals(outputDirectory.getAbsolutePath(), entry.outputDirectory.getAbsolutePath());
    assertEquals(6881, entry.listeningPort);
    assertEquals(1.5, entry.shareRatio, 0.0001);
    assertTrue(entry.metaFile.canRead());
    assertTrue(java.util.Arrays.equals(fakeTorrentBytes, Files.readAllBytes(entry.metaFile.toPath())));
  }

  public void testAddTwiceForSameHashOverwritesRatherThanDuplicates() throws Exception {
    Hash infoHash = Hash.forByteArray("torrent two".getBytes());
    ResumeStateStore.add(infoHash, "first".getBytes(), new File("/tmp/dir-a"), 1111, 1.0);
    ResumeStateStore.add(infoHash, "second".getBytes(), new File("/tmp/dir-b"), 2222, 2.0);

    List<ResumeStateStore.Entry> entries = ResumeStateStore.loadAll();
    assertEquals(1, entries.size());
    assertEquals(new File("/tmp/dir-b").getAbsolutePath(), entries.get(0).outputDirectory.getAbsolutePath());
    assertEquals(2222, entries.get(0).listeningPort);
  }

  public void testMultipleDistinctEntriesAllLoad() throws Exception {
    Hash hashOne = Hash.forByteArray("torrent three".getBytes());
    Hash hashTwo = Hash.forByteArray("torrent four".getBytes());
    ResumeStateStore.add(hashOne, "one".getBytes(), new File("/tmp/dir-one"), 1000, 1.0);
    ResumeStateStore.add(hashTwo, "two".getBytes(), new File("/tmp/dir-two"), 2000, 2.0);

    List<ResumeStateStore.Entry> entries = ResumeStateStore.loadAll();
    assertEquals(2, entries.size());
  }

  public void testRemoveDropsTheEntryAndItsMetaFile() throws Exception {
    Hash infoHash = Hash.forByteArray("torrent five".getBytes());
    ResumeStateStore.add(infoHash, "bytes".getBytes(), new File("/tmp/dir-five"), 3000, 1.0);
    assertEquals(1, ResumeStateStore.loadAll().size());

    ResumeStateStore.remove(infoHash);

    assertTrue(ResumeStateStore.loadAll().isEmpty());
  }

  public void testLoadAllOnEmptyStoreReturnsEmptyList() {
    assertTrue(ResumeStateStore.loadAll().isEmpty());
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
