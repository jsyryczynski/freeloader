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

import freeloader.storage.BitField;
import freeloader.torrent.Hash;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.SingleFileMetaInfo;
import junit.framework.TestCase;

import java.util.Date;

public class TorrentDownloadBytesTest extends TestCase {

  private static final int PIECE_LENGTH = 4 * 1024 * 1024;

  public void testLargeTorrentsDoNotOverflow() {
    // 6 GB: the piece count times the piece length doesn't fit in an int
    long total = 6L * 1024 * 1024 * 1024;
    MetaInfo metaInfo = metaInfo(total);
    BitField pieces = new BitField(metaInfo.getPieceCount());
    for (int i = 0; i < 1000; i++) {
      pieces.gotPiece(i);
    }
    assertEquals(1000L * PIECE_LENGTH, TorrentDownload.bytesIn(pieces, metaInfo));
  }

  public void testCountsTheShorterLastPieceAtItsSize() {
    long total = 10L * PIECE_LENGTH + 1234;
    MetaInfo metaInfo = metaInfo(total);
    BitField pieces = new BitField(metaInfo.getPieceCount());
    pieces.gotPiece(metaInfo.getPieceCount() - 1);
    assertEquals(1234, TorrentDownload.bytesIn(pieces, metaInfo));

    for (int i = 0; i < metaInfo.getPieceCount(); i++) {
      pieces.gotPiece(i);
    }
    assertEquals(total, TorrentDownload.bytesIn(pieces, metaInfo));
  }

  private static MetaInfo metaInfo(long totalLength) {
    int pieceCount = (int) ((totalLength + PIECE_LENGTH - 1) / PIECE_LENGTH);
    Hash[] hashes = new Hash[pieceCount];
    for (int i = 0; i < pieceCount; i++) {
      hashes[i] = Hash.fromHexString("0123456789abcdef0123456789abcdef01234567");
    }
    return new SingleFileMetaInfo(Hash.fromHexString("0123456789abcdef0123456789abcdef01234567"),
        null, null, new Date(), PIECE_LENGTH, "file", hashes, totalLength);
  }
}
