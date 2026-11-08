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
import freeloader.Environment;
import freeloader.downloader.peermanager.PeerManager;
import freeloader.network.BitTorrentConnection;
import freeloader.network.BitTorrentListener;
import freeloader.network.ConnectionInitiator;
import freeloader.stats.CounterRepository;
import freeloader.stats.RealCounterRepository;
import freeloader.storage.BitField;
import freeloader.storage.PieceManager;
import freeloader.storage.Storage;
import freeloader.storage.StorageBuilder;
import freeloader.torrent.Hash;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class TorrentDownload {

  private final Environment environment;
  private final TorrentDownloadConfiguration configuration;

  private final Storage storage;
  private final PieceManager pieceManager;
  private final NextBlockStrategy nextBlockStrategy;
  private final PeerId trackerRegisteredPeerId;
  private final PeerManager peerManager;
  private final TrackerClient trackerClient;
  private final ConnectionInitiator connectionInitiator;

  private CounterRepository counterRepository;

  private boolean running = false;
  private ScheduledFuture<?> peerSearch;

  private volatile boolean searchingForPeers;
  private volatile long lastPeerSearchTime;
  private volatile int lastPeerSearchFound;

  // how often PeerSearch looks for more peers while the download runs,
  // and how soon it tries again after a search that found nobody
  private static final int PEER_SEARCH_INTERVAL_MINUTES = 5;
  private static final int PEER_SEARCH_RETRY_SECONDS = 30;
  // while fewer peers than this are connected, search again sooner
  private static final int FEW_CONNECTED_PEERS = 10;
  private static final int PEER_SEARCH_FEW_PEERS_SECONDS = 60;
  // a running download takes peers as they come in, so it can afford a
  // much deeper DHT search than the default
  private static final int DHT_PEER_TARGET = 200;

  private boolean finishBookkeepingDone = false;
  private static final Logger logger = Logger.getLogger(TorrentDownload.class);

  public TorrentDownload(Environment environment,
                         TorrentDownloadConfiguration configuration) {
    this.environment = environment;
    this.configuration = configuration;

    MetaInfo metaInfo = configuration.getMetaInfo();
    logger.info("creating torrent download for \"" + metaInfo.getName() + "\"");
    storage = new StorageBuilder().buildStorage(metaInfo, configuration.getTargetDirectory());
    pieceManager = new PieceManager(metaInfo, storage, environment, getCounterRepository());
    nextBlockStrategy = new DummyNextBlockStrategy(metaInfo, pieceManager);
    peerManager = new PeerManager(getCounterRepository());
    connectionInitiator = createConnectionInitiator();
    trackerClient = new TrackerClient(getMetaInfo().getAnnounceUrl(), peerManager, this);

    trackerRegisteredPeerId = PeerId.createRandomMainlineId();
    environment.getDownloadRepository().addDownload(this);
  }

  private ConnectionInitiator createConnectionInitiator() {
    if (configuration.isInitiatingConnections()) {
      return new ConnectionInitiator(this);
    } else {
      return null;
    }
  }

  public synchronized void start() {
    assertNotRunning();
    running = true;
    // accept incoming peers from the start: the port is also given out
    // through the DHT, UDP trackers and peer exchange, not just HTTP
    // tracker announces (which used to be the only thing opening it)
    getDefaultListener();
    environment.getScheduledExecutor().scheduleWithFixedDelay(
        new Maintenance(), 0, 15, TimeUnit.SECONDS);
    scheduleNextPeerSearch(0);
  }

  private void assertNotRunning() {
    if (running) {
      throw new IllegalStateException("should not be running");
    }
  }

  public synchronized boolean isRunning() {
    return running;
  }

  public void stop() {
    synchronized (this) {
      assertRunning();
      running = false;
      if (peerSearch != null) {
        peerSearch.cancel(false);
      }
    }
    // outside of our lock: closing a connection takes the connection's
    // lock, and a network thread holding that one while handling incoming
    // data may itself be waiting for ours
    environment.getDownloadRepository().removeDownload(this);
    environment.getConnectionRepository().closeAllConnections(this);
  }

  private void assertRunning() {
    if (!running) {
      throw new IllegalStateException("should be running");
    }
  }

  public BitTorrentListener getDefaultListener() {
    try {
      return environment.getListener(configuration.getListeningPort());
    } catch (IOException e) {
      logger.fatal("could not obtain a listener", e);
    }
    return null;
  }

  public PeerId getTrackerRegisteredPeerId() {
    return trackerRegisteredPeerId;
  }

  public MetaInfo getMetaInfo() {
    return configuration.getMetaInfo();
  }

  public NextBlockStrategy getNextBlockStrategy() {
    return nextBlockStrategy;
  }

  public PieceManager getPieceManager() {
    return pieceManager;
  }

  public Environment getEnvironment() {
    return environment;
  }

  public long getBytesUploaded() {
    return (long) (getBytesDownloaded() * configuration.getShareRatio());
  }

  public long getBytesDownloaded() {
    return bytesIn(pieceManager.getValidPieces(), getMetaInfo());
  }

  /** The size of the given pieces; the last piece is usually shorter. */
  static long bytesIn(BitField pieces, MetaInfo metaInfo) {
    // in long: torrents over 2 GB overflow an int
    long bytes = (long) pieces.getAvailablePieceCount() * metaInfo.getPieceLength();
    int last = metaInfo.getPieceCount() - 1;
    if (last >= 0 && pieces.hasPiece(last)) {
      long lastPieceLength = metaInfo.getTotalLength() - (long) last * metaInfo.getPieceLength();
      bytes -= metaInfo.getPieceLength() - lastPieceLength;
    }
    return bytes;
  }

  public long getBytesLeft() {
    return (long) (getMetaInfo().getTotalLength() - getBytesDownloaded() * 0.9);
  }

  public Set<BitTorrentConnection> getAllValidConnections() {
    return environment.getConnectionRepository().getAllValidConnections(this);
  }

  public TorrentDownloadConfiguration getConfiguration() {
    return configuration;
  }

  public Hash getInfoHash() {
    return getMetaInfo().getInfoHash();
  }

  public int getPort() {
    return getDefaultListener().getPort();
  }

  public PieceAnnounceStrategy getPieceAnnounceStrategy(BitTorrentConnection connection) {
    if (configuration.sendRealBitField()) {
      return new RealPieceAnnounceStrategy(this);
    } else {
      return new RandomPieceAnnounceStrategy(this,
          connection, configuration.getAnnouncePercent());
    }
  }

  private boolean isFinished() {
    return pieceManager.getValidPieces().hasAll();
  }

  public PeerManager getPeerManager() {
    return peerManager;
  }

  public CounterRepository getCounterRepository() {
    if (counterRepository == null) {
      createCounterRepository();
    }
    return counterRepository;
  }

  private synchronized void createCounterRepository() {
    if (counterRepository == null) {
      RealCounterRepository repo =
          RealCounterRepository.fromResource("stats.properties");
      if (environment.getConfiguration().doLogStats()) {
        repo.setFileForWriting(environment.getConfiguration().getStatsLogFile());
      }
      counterRepository = repo;
    }
  }

  /** Whether a peer search (trackers + DHT) is running right now. */
  public boolean isSearchingForPeers() {
    return searchingForPeers;
  }

  /** When the last peer search finished (epoch millis), 0 if none has yet. */
  public long getLastPeerSearchTime() {
    return lastPeerSearchTime;
  }

  /** How many peers the last peer search found. */
  public int getLastPeerSearchFound() {
    return lastPeerSearchFound;
  }

  private synchronized void scheduleNextPeerSearch(long delaySeconds) {
    if (running) {
      peerSearch = environment.getScheduledExecutor().schedule(new PeerSearch(), delaySeconds, TimeUnit.SECONDS);
    }
  }

  /**
   * Looks for peers via all of the torrent's trackers (HTTP and UDP) and
   * the DHT: when the download starts, then every
   * PEER_SEARCH_INTERVAL_MINUTES -- or after PEER_SEARCH_RETRY_SECONDS if
   * a search found nobody (a DHT search right after startup can come up
   * empty), or PEER_SEARCH_FEW_PEERS_SECONDS while few peers are connected.
   * Peers are handed to the PeerManager as they turn up, not just at the end. The TrackerClient that Maintenance re-announces with only
   * handles a single http(s) "announce" URL, so without this a torrent
   * with only udp:// trackers -- or none, like most magnet links' saved
   * metadata -- never finds a single peer once its initial ones (if any)
   * are gone, including after every restart. Each search runs on its own
   * thread: it can take over a minute and shouldn't hold up the shared
   * executors meanwhile.
   */
  private class PeerSearch implements Runnable {
    public void run() {
      if (!isRunning() || isFinished()) {
        return;
      }
      Thread thread = new Thread(() -> {
        int found = 0;
        searchingForPeers = true;
        try {
          List<URI> trackers = getMetaInfo().getTrackers();
          PeerFinder finder = new PeerFinder(environment);
          finder.setDhtPeerTarget(DHT_PEER_TARGET);
          List<InetSocketAddress> peers = finder.find(
              getInfoHash(), PeerId.createRandomMainlineId(), configuration.getListeningPort(),
              trackers, message -> { }, this::addPeers);
          found = peers.size();
          logger.info("peer search for \"" + getMetaInfo().getName() + "\" found " + found + " peer(s)");
          addPeers(peers);
        } catch (RuntimeException e) {
          logger.warn("peer search failed", e);
        } finally {
          lastPeerSearchFound = found;
          lastPeerSearchTime = System.currentTimeMillis();
          searchingForPeers = false;
          scheduleNextPeerSearch(nextPeerSearchDelaySeconds(found));
        }
      }, "peer-search");
      thread.setDaemon(true);
      thread.start();
    }

    private long nextPeerSearchDelaySeconds(int found) {
      if (found == 0) {
        return PEER_SEARCH_RETRY_SECONDS;
      } else if (getAllValidConnections().size() < FEW_CONNECTED_PEERS) {
        return PEER_SEARCH_FEW_PEERS_SECONDS;
      }
      return PEER_SEARCH_INTERVAL_MINUTES * 60;
    }

    private void addPeers(List<InetSocketAddress> peers) {
      if (isRunning()) {
        peerManager.addPeersFromTrackerResponse(PeerFinder.toPeerInfoList(peers));
      }
    }
  }

  private class Maintenance implements Runnable {
    public void run() {
      logger.info("torrent download maintenance");
      if (!running) {
        logger.info("we're not running anymore, killing the maintenance job");
        throw new IllegalStateException("quitting now");
      }
      trackerClient.announce();
      if (connectionInitiator != null) {
        connectionInitiator.maintenance();
      }
      updateConnectionStatistics();
      recordFinishBookkeepingIfNeeded();
      quitOnFinish();
    }

    private void quitOnFinish() {
      if (configuration.doQuitOnFinish() && isFinished()) {
        logger.info("exiting, finished download");
        System.exit(0);
      }
    }
  }

  /**
   * Once a download completes there's nothing left to resume, so drop it
   * from the resume list (see ResumeStateStore) -- otherwise a finished
   * torrent would get needlessly re-validated and re-started forever on
   * every app launch. At the same time, record it in CompletedDownloadStore
   * so it still shows up (as history, not as a live download) the next
   * time the app starts -- otherwise a finished torrent would simply
   * vanish from the UI on restart.
   */
  private void recordFinishBookkeepingIfNeeded() {
    if (!finishBookkeepingDone && isFinished() && configuration.isTrackedByGui()) {
      ResumeStateStore.remove(getInfoHash());
      CompletedDownloadStore.add(
          getInfoHash(),
          getMetaInfo().getName(),
          configuration.getTargetDirectory(),
          getMetaInfo().getTotalLength(),
          System.currentTimeMillis());
      finishBookkeepingDone = true;
    }
  }

  private void updateConnectionStatistics() {
    int seeds = 0;
    int leechers = 0;
    int in = 0;
    int out = 0;
    Set<BitTorrentConnection> connections = getAllValidConnections();
    for (BitTorrentConnection connection : connections) {
      BitField bitField = connection.getRemoteBitField();
      if (bitField != null) {
        if (bitField.hasAll()) {
          seeds++;
        } else {
          leechers++;
        }
      }

      if (connection.isInbound()) {
        in++;
      } else {
        out++;
      }
    }

    getCounterRepository().getCounter("network.connections.seed").set(seeds);
    getCounterRepository().getCounter("network.connections.leecher").set(leechers);
    getCounterRepository().getCounter("network.connections.in").set(in);
    getCounterRepository().getCounter("network.connections.out").set(out);
  }
}
