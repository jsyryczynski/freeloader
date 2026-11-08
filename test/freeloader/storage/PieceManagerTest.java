/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2006 Patrick Moor <patrick@moor.ws>
 * Modified by Jan Syryczyński, 2022-2026.
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

package freeloader.storage;

import freeloader.util.Urls;
import org.easymock.EasyMock;
import freeloader.Environment;
import freeloader.downloader.Block;
import freeloader.stats.FakeRepository;
import freeloader.torrent.Hash;
import freeloader.torrent.HashTest;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.MultiFileMetaInfo;
import freeloader.util.ArrayUtil;
import freeloader.util.ByteUtil;
import freeloader.util.ExtendedTestCase;

import java.io.File;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.IntConsumer;

public class PieceManagerTest extends ExtendedTestCase {

  private MetaInfo metaFile;

  private static final int PIECE_LENGTH = 128;
  private static final int LAST_PIECE_LENGTH = 64;
  private byte[][] pieces;
  private Hash[] hashes;
  private MetaInfo.FileInfo[] infos;
  private Storage storage;
  private Environment environment;

  protected void setUp() throws Exception {
    super.setUp();

    pieces = new byte[33][];
    for (int i = 0; i < pieces.length - 1; i++) {
      pieces[i] = ByteUtil.randomByteArray(PIECE_LENGTH);
    }
    pieces[pieces.length - 1] = ByteUtil.randomByteArray(LAST_PIECE_LENGTH);

    hashes = new Hash[33];
    for (int i = 0; i < hashes.length; i++) {
      hashes[i] = Hash.forByteArray(pieces[i]);
    }

    infos = new MetaInfo.FileInfo[3];
    infos[0] = new MetaInfo.FileInfo(new String[] {"a"}, 16 * 128);
    infos[1] = new MetaInfo.FileInfo(new String[] {"b"},  8 * 128);
    infos[2] = new MetaInfo.FileInfo(new String[] {"c"}, 17 *  64);

    metaFile = new MultiFileMetaInfo(
        HashTest.randomHash(),
        Urls.lenient("http://tracker.example.com/"),
        null,
        new Date(),
        PIECE_LENGTH,
        "basedir", hashes, infos);

    StorageBuilder storageBuilder = new StorageBuilder();
    storageBuilder.setFileRepository(new VirtualFileRepository());
    storage = storageBuilder.buildStorage(metaFile, new File("/"));

    environment = EasyMock.createMock(Environment.class);
    EasyMock.expect(environment.getExecutor()).andReturn(new Executor() {
      public void execute(Runnable command) {
        command.run();
      }
    }).anyTimes();
    EasyMock.replay(environment);
  }

  public void testPristineStorage() {
    PieceManager pieceManager = createDefaultPieceManager();
    assertEquals(new BitField(33), pieceManager.getValidPieces());
  }

  public void testStorageWithSomeData() {
    assertEquals(pieces[7].length, storage.writePiece(7, pieces[7]));
    assertEquals(pieces[13].length, storage.writePiece(13, pieces[13]));
    PieceManager pieceManager = createDefaultPieceManager();
    assertEquals(new BitField(33, 7, 13), pieceManager.getValidPieces());
  }

  public void testPieceAvailable() {
    assertEquals(pieces[7].length, storage.writePiece(7, pieces[7]));
    assertEquals(pieces[13].length, storage.writePiece(13, pieces[13]));
    PieceManager pieceManager = createDefaultPieceManager();

    assertTrue(pieceManager.isPieceAvailable(7));
    assertTrue(pieceManager.isPieceAvailable(13));
    assertFalse(pieceManager.isPieceAvailable(2));
    assertFalse(pieceManager.isPieceAvailable(0));
    assertFalse(pieceManager.isPieceAvailable(32));

    try {
      assertFalse(pieceManager.isPieceAvailable(33));
      fail("should fail");
    } catch (IndexOutOfBoundsException e) {
      // expected
    }
  }

  public void testAddSomeCompletePieces() {
    PieceManager pieceManager = createDefaultPieceManager();

    byte[] currentStorageContent = new byte[PIECE_LENGTH];
    storage.readPiece(0, currentStorageContent);
    assertArrayEquals(new byte[PIECE_LENGTH], currentStorageContent);

    byte[] dataToWrite = new byte[32];
    System.arraycopy(pieces[0], 70, dataToWrite, 0, 32);
    pieceManager.setBlock(new DataBlock(0, 70, dataToWrite));

    System.arraycopy(pieces[0], 30, dataToWrite, 0, 32);
    pieceManager.setBlock(new DataBlock(0, 30, dataToWrite));

    System.arraycopy(pieces[0], 60, dataToWrite, 0, 32);
    pieceManager.setBlock(new DataBlock(0, 60, dataToWrite));

    System.arraycopy(pieces[0], 96, dataToWrite, 0, 32);
    pieceManager.setBlock(new DataBlock(0, 96, dataToWrite));

    System.arraycopy(pieces[0], 0, dataToWrite, 0, 32);
    pieceManager.setBlock(new DataBlock(0, 0, dataToWrite));

    byte[] newStorageContent = new byte[PIECE_LENGTH];
    storage.readPiece(0, newStorageContent);
    assertArrayEquals(pieces[0], newStorageContent);
  }

  public void testAddSomeIncompletePieces() {
    PieceManager pieceManager = createDefaultPieceManager();

    byte[] currentStorageContent = new byte[PIECE_LENGTH];
    storage.readPiece(0, currentStorageContent);
    assertArrayEquals(new byte[PIECE_LENGTH], currentStorageContent);

    byte[] dataToWrite = new byte[32];
    System.arraycopy(pieces[0], 30, dataToWrite, 0, 32);
    pieceManager.setBlock(new DataBlock(0, 30, dataToWrite));

    System.arraycopy(pieces[0], 60, dataToWrite, 0, 32);
    pieceManager.setBlock(new DataBlock(0, 60, dataToWrite));

    byte[] newStorageContent = new byte[PIECE_LENGTH];
    storage.readPiece(0, newStorageContent);
    assertArrayEquals(new byte[PIECE_LENGTH], newStorageContent);
  }

  public void testAddWrongPieces() {
    PieceManager pieceManager = createDefaultPieceManager();

    byte[] currentStorageContent = new byte[PIECE_LENGTH];
    storage.readPiece(0, currentStorageContent);
    assertArrayEquals(new byte[PIECE_LENGTH], currentStorageContent);

    pieceManager.setBlock(new DataBlock(0, 0, new byte[PIECE_LENGTH]));

    byte[] newStorageContent = new byte[PIECE_LENGTH];
    storage.readPiece(0, newStorageContent);
    assertArrayEquals(new byte[PIECE_LENGTH], newStorageContent);
  }

  public void testGetMissingBlocksNone() {
    assertEquals(pieces[7].length, storage.writePiece(7, pieces[7]));
    PieceManager pieceManager = createDefaultPieceManager();

    List<Block> blocks = pieceManager.getMissingBlocks(7, 32);
    assertTrue(blocks.isEmpty());
  }

  public void testUpdatesBitFieldCorrectly() {
    PieceManager pieceManager = createDefaultPieceManager();
    assertFalse(pieceManager.getValidPieces().hasPiece(7));

    pieceManager.setBlock(new DataBlock(7, 0, pieces[7]));
    assertTrue(pieceManager.getValidPieces().hasPiece(7));
  }

  public void testListeners() {
    IntConsumer listener = EasyMock.createMock(IntConsumer.class);
    listener.accept(1);
    listener.accept(5);
    EasyMock.replay(listener);

    PieceManager pieceManager = createDefaultPieceManager();
    pieceManager.setBlock(new DataBlock(0, 0, pieces[0]));
    pieceManager.addPieceListener(listener);
    pieceManager.setBlock(new DataBlock(1, 0, pieces[1]));
    pieceManager.setBlock(new DataBlock(5, 0, pieces[5]));
    pieceManager.removePieceListener(listener);
    pieceManager.setBlock(new DataBlock(6, 0, pieces[6]));

    EasyMock.verify(listener);
  }

  public void testIsBitFieldOfInterest() {
    PieceManager pieceManager = createDefaultPieceManager();
    assertFalse(pieceManager.isBitFieldOfInterest(null));

    pieceManager.setBlock(new DataBlock(7, 0, pieces[7]));
    BitField testing = pieceManager.getValidPieces();
    assertFalse(pieceManager.isBitFieldOfInterest(testing));

    testing.gotPiece(2);
    assertTrue(pieceManager.isBitFieldOfInterest(testing));
  }

  private PieceManager createDefaultPieceManager() {
    return new PieceManager(metaFile, storage, environment, new FakeRepository());
  }
}
