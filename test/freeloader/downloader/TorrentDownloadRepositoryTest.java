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

package freeloader.downloader;

import org.easymock.EasyMock;
import freeloader.torrent.Hash;
import freeloader.torrent.HashTest;
import freeloader.torrent.MetaInfo;
import freeloader.util.ExtendedTestCase;

public class TorrentDownloadRepositoryTest extends ExtendedTestCase {

  public void testAddingAndQuerying() {
    Hash infoHash = HashTest.randomHash();
    TorrentDownloadRepository repository = new TorrentDownloadRepository();
    assertNull(repository.getDownload(infoHash));

    MetaInfo metaInfo = EasyMock.createMock(MetaInfo.class);
    TorrentDownload download = EasyMock.createMock(TorrentDownload.class);

    EasyMock.expect(metaInfo.getInfoHash()).andReturn(infoHash);
    EasyMock.expect(download.getMetaInfo()).andReturn(metaInfo);

    EasyMock.replay(download);
    EasyMock.replay(metaInfo);

    repository.addDownload(download);

    assertSame(download, repository.getDownload(infoHash));

    EasyMock.verify(download);
    EasyMock.verify(metaInfo);
  }

  public void testAddingSameInstanceTwice() {
    Hash infoHash = HashTest.randomHash();
    TorrentDownloadRepository repository = new TorrentDownloadRepository();

    MetaInfo metaInfo = EasyMock.createMock(MetaInfo.class);
    TorrentDownload download = EasyMock.createMock(TorrentDownload.class);

    EasyMock.expect(metaInfo.getInfoHash()).andReturn(infoHash).times(2);
    EasyMock.expect(download.getMetaInfo()).andReturn(metaInfo).times(2);

    EasyMock.replay(download);
    EasyMock.replay(metaInfo);

    repository.addDownload(download);
    assertSame(download, repository.getDownload(infoHash));

    repository.addDownload(download);
    assertSame(download, repository.getDownload(infoHash));

    EasyMock.verify(download);
    EasyMock.verify(metaInfo);
  }

  public void testAddingTwice() {
    Hash infoHash = HashTest.randomHash();
    TorrentDownloadRepository repository = new TorrentDownloadRepository();

    MetaInfo metaInfoA = EasyMock.createMock(MetaInfo.class);
    TorrentDownload downloadA = EasyMock.createMock(TorrentDownload.class);
    MetaInfo metaInfoB = EasyMock.createMock(MetaInfo.class);
    TorrentDownload downloadB = EasyMock.createMock(TorrentDownload.class);

    EasyMock.expect(metaInfoA.getInfoHash()).andReturn(infoHash);
    EasyMock.expect(downloadA.getMetaInfo()).andReturn(metaInfoA);
    EasyMock.expect(metaInfoB.getInfoHash()).andReturn(infoHash);
    EasyMock.expect(downloadB.getMetaInfo()).andReturn(metaInfoB);

    EasyMock.replay(downloadA);
    EasyMock.replay(metaInfoA);
    EasyMock.replay(downloadB);
    EasyMock.replay(metaInfoB);

    repository.addDownload(downloadA);
    assertSame(downloadA, repository.getDownload(infoHash));

    try {
      repository.addDownload(downloadB);
      fail("should not be possible to add same info hash twice with different instances");
    } catch (IllegalArgumentException e) {
      // expected
    }

    assertSame(downloadA, repository.getDownload(infoHash));

    EasyMock.verify(downloadA);
    EasyMock.verify(metaInfoA);
    EasyMock.verify(downloadB);
    EasyMock.verify(metaInfoB);
  }

  public void testAddingTwoDownloads() {
    TorrentDownloadRepository repository = new TorrentDownloadRepository();

    Hash infoHashA = HashTest.randomHash();
    MetaInfo metaInfoA = EasyMock.createMock(MetaInfo.class);
    TorrentDownload downloadA = EasyMock.createMock(TorrentDownload.class);
    EasyMock.expect(metaInfoA.getInfoHash()).andReturn(infoHashA);
    EasyMock.expect(downloadA.getMetaInfo()).andReturn(metaInfoA);
    EasyMock.replay(downloadA);
    EasyMock.replay(metaInfoA);

    Hash infoHashB = HashTest.randomHash();
    MetaInfo metaInfoB = EasyMock.createMock(MetaInfo.class);
    TorrentDownload downloadB = EasyMock.createMock(TorrentDownload.class);
    EasyMock.expect(metaInfoB.getInfoHash()).andReturn(infoHashB);
    EasyMock.expect(downloadB.getMetaInfo()).andReturn(metaInfoB);
    EasyMock.replay(downloadB);
    EasyMock.replay(metaInfoB);

    repository.addDownload(downloadA);
    repository.addDownload(downloadB);
    
    EasyMock.verify(downloadB);
    EasyMock.verify(metaInfoB);
    EasyMock.verify(downloadA);
    EasyMock.verify(metaInfoA);
  }
}
