/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2006 Patrick Moor <patrick@moor.ws>
 * Modified by Jan Syryczyński, 2022-2026.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class TorrentDownloadRepository {

  private Map<Hash, TorrentDownload> downloads = new HashMap<Hash, TorrentDownload>();

  private static final Logger logger = Logger.getLogger(TorrentDownloadRepository.class);

  public synchronized TorrentDownload getDownload(Hash infoHash) {
    return downloads.get(infoHash);
  }

  public synchronized void addDownload(TorrentDownload download) {
    Hash infoHash = download.getMetaInfo().getInfoHash();
    TorrentDownload existingDownload = downloads.get(infoHash);
    if (existingDownload != null) {
      if (download != existingDownload) {
        logger.fatal("there's already a downloader registered for the given info hash");
        throw new IllegalArgumentException("cannot add multiple downloads for the same info hash");
      } else {
        logger.info("downloader was already registered for info hash " + infoHash);
        return;
      }
    }
    downloads.put(infoHash, download);
  }

  public synchronized Collection<TorrentDownload> getAllDownloads() {
    return new ArrayList<TorrentDownload>(downloads.values());
  }

  public synchronized void removeDownload(TorrentDownload torrentDownload) {
    downloads.remove(torrentDownload.getInfoHash());
  }
}
