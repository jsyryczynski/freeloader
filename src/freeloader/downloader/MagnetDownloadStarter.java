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
import freeloader.Environment;
import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.BList;
import freeloader.bencoding.BString;
import freeloader.bencoding.ParseException;
import freeloader.torrent.MagnetMetadataFetcher;
import freeloader.torrent.MagnetUri;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.MetaInfoBuilder;
import freeloader.torrent.PeerId;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

/**
 * Turns a magnet link into a running TorrentDownload: finds some peers
 * via the magnet link's trackers and the DHT (see PeerFinder), asks them
 * for the torrent metadata over the BEP 10/9 extension protocol (see
 * MagnetMetadataFetcher), and once a verified MetaInfo is in hand, hands
 * off to the exact same TorrentDownloadConfiguration/TorrentDownload
 * machinery a regular .torrent file download uses.
 *
 * The peers found on the way are seeded straight into the new download,
 * and the magnet link's trackers are saved in the resume list's copy of
 * the torrent (as announce-list), so the download's own periodic peer
 * search can keep using them, also after a restart.
 */
public class MagnetDownloadStarter {

  private static final Logger logger = Logger.getLogger(MagnetDownloadStarter.class);

  public interface StatusListener {
    void onStatus(String message);
    void onSuccess(TorrentDownload download);
    void onFailure(String message);
  }

  private final Environment environment;
  private int listeningPort = TorrentDownloadConfiguration.DEFAULT_LISTENING_PORT;
  private boolean rememberForResume = true;

  public MagnetDownloadStarter(Environment environment) {
    this.environment = environment;
  }

  public void setListeningPort(int port) {
    listeningPort = port;
  }

  /**
   * Whether the GUI keeps track of this download: resumes it on its next
   * start (see ResumeStateStore) and lists it once it has finished.
   * Command-line downloads stay out of both.
   */
  public void setRememberForResume(boolean rememberForResume) {
    this.rememberForResume = rememberForResume;
  }

  /**
   * Runs entirely on the environment's executor (never blocks the caller),
   * reporting progress/success/failure via the listener.
   */
  public void start(final MagnetUri magnetUri, final File outputDirectory, final StatusListener listener) {
    environment.getExecutor().execute(() -> {
      try {
        runBlocking(magnetUri, outputDirectory, listener);
      } catch (Exception failure) {
        logger.warn("magnet download failed", failure);
        listener.onFailure(failure.getMessage() == null ? failure.toString() : failure.getMessage());
      }
    });
  }

  private void runBlocking(MagnetUri magnetUri, File outputDirectory, StatusListener listener) throws IOException {
    PeerId ourPeerId = PeerId.createRandomMainlineId();

    List<InetSocketAddress> candidates = new PeerFinder(environment).find(
        magnetUri.getInfoHash(), ourPeerId, listeningPort,
        magnetUri.getTrackers(), listener::onStatus);
    if (candidates.isEmpty()) {
      listener.onFailure(
          "could not find any peers for this torrent via its tracker(s) or the DHT");
      return;
    }
    listener.onStatus("found " + candidates.size() + " peer(s), fetching metadata...");

    MagnetMetadataFetcher fetcher = new MagnetMetadataFetcher();
    byte[] infoBytes = fetcher.fetchFromAnyPeer(magnetUri.getInfoHash(), candidates, ourPeerId,
        (tried, total) -> listener.onStatus("fetching metadata, asked " + tried + " of " + total + " peers..."));
    if (infoBytes == null) {
      listener.onFailure("could not fetch metadata from any of the " + candidates.size() + " peer(s) found");
      return;
    }
    listener.onStatus("got metadata (" + infoBytes.length + " bytes), starting download...");

    BuiltMetaInfo built = buildMetaInfo(infoBytes, magnetUri.getTrackers());
    MetaInfo metaInfo = built.metaInfo;

    TorrentDownloadConfiguration configuration = new TorrentDownloadConfiguration(metaInfo, outputDirectory);
    configuration.setListeningPort(listeningPort);
    configuration.setTrackedByGui(rememberForResume);
    if (rememberForResume) {
      ResumeStateStore.add(
          metaInfo.getInfoHash(), built.torrentFileBytes, outputDirectory,
          configuration.getListeningPort(), configuration.getShareRatio());
    }

    TorrentDownload started = new TorrentDownload(environment, configuration);
    // no need to wait for the download's own first peer search: hand it
    // the peers that were just found
    started.getPeerManager().addPeersFromTrackerResponse(PeerFinder.toPeerInfoList(candidates));
    started.start();
    listener.onSuccess(started);
  }

  /**
   * A MetaInfo built from fetched magnet metadata, along with the raw
   * bencoded bytes of the synthesized .torrent-equivalent dictionary it
   * came from -- kept around so the resume list can save an independently
   * re-parseable copy for restoring this download after a restart,
   * without needing to re-fetch metadata from peers again.
   */
  private static class BuiltMetaInfo {
    final MetaInfo metaInfo;
    final byte[] torrentFileBytes;

    BuiltMetaInfo(MetaInfo metaInfo, byte[] torrentFileBytes) {
      this.metaInfo = metaInfo;
      this.torrentFileBytes = torrentFileBytes;
    }
  }

  private BuiltMetaInfo buildMetaInfo(byte[] infoBytes, List<URI> trackers) throws IOException {
    BEntity infoEntity;
    try {
      infoEntity = new BDecoder().decode(infoBytes);
    } catch (ParseException e) {
      throw new IOException("fetched metadata is not valid bencoding", e);
    }
    if (!(infoEntity instanceof BDictionary)) {
      throw new IOException("fetched metadata's \"info\" entry is not a dictionary");
    }

    BDictionary<BEntity> torrentDictionary = new BDictionary<BEntity>();
    if (!trackers.isEmpty()) {
      // the usual .torrent layout: the first tracker as "announce", and
      // all of them in "announce-list" (one tier each)
      torrentDictionary.put(new BString(MetaInfoBuilder.ANNOUNCE_FIELD), new BString(trackers.get(0).toString()));
      BList<BEntity> tiers = new BList<BEntity>();
      for (URI tracker : trackers) {
        BList<BEntity> tier = new BList<BEntity>();
        tier.add(new BString(tracker.toString()));
        tiers.add(tier);
      }
      torrentDictionary.put(new BString(MetaInfoBuilder.ANNOUNCE_LIST_FIELD), tiers);
    }
    torrentDictionary.put(new BString(MetaInfoBuilder.INFO_FIELD), infoEntity);

    try {
      MetaInfo metaInfo = MetaInfoBuilder.fromDictionary(torrentDictionary);
      return new BuiltMetaInfo(metaInfo, torrentDictionary.encode());
    } catch (ParseException e) {
      throw new IOException("could not build torrent metadata from fetched info dictionary", e);
    }
  }
}
