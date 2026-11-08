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
import freeloader.util.Urls;
import freeloader.Environment;
import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.ParseException;
import freeloader.dht.DHTPeerFinder;
import freeloader.dht.DHTracker;
import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerRequest;
import freeloader.tracker.TrackerResponse;
import freeloader.tracker.UdpTrackerClient;
import freeloader.util.URLFetcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Finds peers for a torrent the way modern clients do: announces to all
 * of its trackers at once, HTTP and UDP alike (most real-world trackers
 * are udp://, see UdpTrackerClient), while searching the DHT alongside.
 * Used both to get a magnet link going and, periodically, to keep every
 * running download supplied with peers -- TorrentDownload's own
 * TrackerClient only speaks HTTP to a single announce URL, which leaves
 * udp-only and trackerless torrents with no peer source at all.
 *
 * Blocks while it searches (up to TRACKER_ANNOUNCE_TIMEOUT_MS, or
 * DHT_LOOKUP_TIMEOUT_MS when the trackers come up empty), so call it from
 * a background thread.
 */
public class PeerFinder {

  private static final Logger logger = Logger.getLogger(PeerFinder.class);

  // how long to wait, in total, for all of the trackers to answer
  private static final long TRACKER_ANNOUNCE_TIMEOUT_MS = 15000;

  // a cold DHT lookup can genuinely need several dozen queries to
  // converge (see DHTPeerFinder); give it real room to work rather than
  // cutting it off after a couple of seconds
  private static final long DHT_LOOKUP_TIMEOUT_MS = 60000;

  // fewer peers than this from the trackers and the DHT's are worth waiting
  // for too: a tracker may hand out just one or two (torrent.ubuntu.com
  // answers with a single peer) while the DHT knows dozens
  private static final int ENOUGH_TRACKER_PEERS = 30;

  public interface StatusListener {
    void onStatus(String message);
  }

  /** Gets peers as soon as they're found, before the whole search is done. */
  public interface EarlyPeersListener {
    void onPeers(List<InetSocketAddress> peers);
  }

  private final Environment environment;
  private Function<URL, URLFetcher> urlFetcherFactory = URLFetcher::new;
  private UdpTrackerClient udpTrackerClient = new UdpTrackerClient();
  private DhtSearch dht = this::findPeersViaDHT;
  private int dhtPeerTarget = DHTPeerFinder.DEFAULT_ENOUGH_PEERS;

  interface DhtSearch {
    List<InetSocketAddress> findPeers(Hash infoHash, int enoughPeers, DHTPeerFinder.PeersListener listener);
  }

  public PeerFinder(Environment environment) {
    this.environment = environment;
  }

  void setURLFetcherFactory(Function<URL, URLFetcher> factory) {
    this.urlFetcherFactory = factory;
  }

  void setUdpTrackerClient(UdpTrackerClient client) {
    this.udpTrackerClient = client;
  }

  void setDhtSearch(DhtSearch dhtSearch) {
    this.dht = dhtSearch;
  }

  /**
   * How many peers the DHT search looks for before it stops. A running
   * download wants plenty (it gets them as they come in); a magnet link
   * only needs a few to fetch the torrent's info from.
   */
  public void setDhtPeerTarget(int dhtPeerTarget) {
    this.dhtPeerTarget = dhtPeerTarget;
  }

  public List<InetSocketAddress> find(Hash infoHash, PeerId peerId, int listeningPort,
                                      List<URI> trackers, StatusListener listener) {
    return find(infoHash, peerId, listeningPort, trackers, listener, peers -> { });
  }

  /**
   * Peers from the trackers, plus whatever the DHT has found by the time
   * they're done; if the trackers find only a few (or nobody), waits for
   * the DHT as well, handing the trackers' peers to earlyPeers meanwhile.
   */
  public List<InetSocketAddress> find(Hash infoHash, PeerId peerId, int listeningPort,
                                      List<URI> trackers, StatusListener listener,
                                      EarlyPeersListener earlyPeers) {
    // start the DHT search right away, alongside the trackers, rather than
    // only once they've all failed: a cold DHT lookup (first one since the
    // app started) needs most of its time budget, and many trackers are
    // long dead anyway
    FutureTask<List<InetSocketAddress>> dhtSearch =
        new FutureTask<List<InetSocketAddress>>(() -> dht.findPeers(infoHash, dhtPeerTarget, earlyPeers::onPeers));
    Thread dhtThread = new Thread(dhtSearch, "dht-peer-search");
    dhtThread.setDaemon(true);
    dhtThread.start();

    Set<InetSocketAddress> found = new LinkedHashSet<InetSocketAddress>(
        announceToAllTrackers(infoHash, peerId, listeningPort, trackers, listener));
    if (found.size() < ENOUGH_TRACKER_PEERS) {
      if (found.isEmpty()) {
        listener.onStatus(trackers.isEmpty()
            ? "searching the DHT for peers..."
            : "no peers from trackers, still searching the DHT...");
      } else {
        earlyPeers.onPeers(new ArrayList<InetSocketAddress>(found));
        listener.onStatus("only " + found.size() + " peer(s) from trackers, also searching the DHT...");
      }
      found.addAll(dhtResult(dhtSearch, DHT_LOOKUP_TIMEOUT_MS));
    } else if (dhtSearch.isDone()) {
      found.addAll(dhtResult(dhtSearch, 0));
    }
    return new ArrayList<InetSocketAddress>(found);
  }

  /** The same addresses as tracker-style peer records, for PeerManager. */
  public static List<TrackerResponse.PeerInfo> toPeerInfoList(List<InetSocketAddress> addresses) {
    List<TrackerResponse.PeerInfo> result = new ArrayList<TrackerResponse.PeerInfo>();
    for (InetSocketAddress address : addresses) {
      // we only know where these peers are, not their peer ids -- those
      // are only revealed once we actually handshake with them, so use a
      // placeholder for now (same approach TrackerState uses for its DHT
      // bootstrap node)
      result.add(new TrackerResponse.PeerInfo(PeerId.createRandom(), address));
    }
    return result;
  }

  /**
   * Announces to every tracker, in parallel, and combines whatever peers
   * come back. A tracker list is typically a mix of udp:// (most common)
   * and http(s):// trackers, and plenty of them are dead or slow, so
   * trying them one at a time in sequence would be both slow and fragile.
   */
  private List<InetSocketAddress> announceToAllTrackers(
      Hash infoHash, PeerId peerId, int listeningPort, List<URI> trackers, StatusListener listener) {
    if (trackers.isEmpty()) {
      return new ArrayList<InetSocketAddress>();
    }
    listener.onStatus("announcing to " + trackers.size() + " tracker(s) to find peers...");

    // one thread per tracker (within reason): dead udp:// trackers each hold a
    // thread for ~8s of retries, so with fewer threads than trackers the
    // live ones further down the list never get asked before the deadline
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(trackers.size(), 32));
    try {
      List<Future<List<InetSocketAddress>>> futures = new ArrayList<Future<List<InetSocketAddress>>>();
      for (final URI tracker : trackers) {
        futures.add(pool.submit(new Callable<List<InetSocketAddress>>() {
          public List<InetSocketAddress> call() {
            return announceToOneTracker(infoHash, peerId, listeningPort, tracker);
          }
        }));
      }

      Set<InetSocketAddress> combined = new LinkedHashSet<InetSocketAddress>();
      long deadline = System.currentTimeMillis() + TRACKER_ANNOUNCE_TIMEOUT_MS;
      for (Future<List<InetSocketAddress>> future : futures) {
        long remaining = Math.max(0, deadline - System.currentTimeMillis());
        try {
          combined.addAll(future.get(remaining, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
          // this tracker didn't answer in time, or failed; move on to
          // whatever the others found
        }
      }
      return new ArrayList<InetSocketAddress>(combined);
    } finally {
      pool.shutdownNow();
    }
  }

  private List<InetSocketAddress> announceToOneTracker(Hash infoHash, PeerId peerId, int listeningPort, URI tracker) {
    String scheme = tracker.getScheme();
    try {
      if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
        return announceHttp(infoHash, peerId, listeningPort, tracker.toURL());
      } else if ("udp".equalsIgnoreCase(scheme)) {
        return announceUdp(infoHash, peerId, listeningPort, tracker);
      } else {
        logger.debug("unsupported tracker scheme, skipping: " + tracker);
        return new ArrayList<InetSocketAddress>();
      }
    } catch (Exception e) {
      logger.info("tracker announce failed for " + tracker + ": " + e.getMessage());
      return new ArrayList<InetSocketAddress>();
    }
  }

  private List<InetSocketAddress> announceHttp(Hash infoHash, PeerId peerId, int listeningPort, URL tracker)
      throws IOException {
    List<InetSocketAddress> result = new ArrayList<InetSocketAddress>();
    try {
      TrackerRequest request = new TrackerRequest(
          infoHash, peerId, listeningPort, TrackerRequest.Event.STARTED, 0, 0, Long.MAX_VALUE);
      request.setCompactFormatWanted(true);
      URL url = Urls.lenient(tracker + "?" + request.getHttpEncodedExtendedParameters());
      URLFetcher urlFetcher = urlFetcherFactory.apply(url);
      byte[] data = urlFetcher.fetch();
      if (data == null) {
        return result;
      }
      BEntity entity = new BDecoder().decode(data);
      if (!(entity instanceof BDictionary)) {
        return result;
      }
      TrackerResponse response = new TrackerResponse((BDictionary) entity);
      if (response.hasError()) {
        logger.info("tracker " + tracker + " returned an error: " + response.getError());
        return result;
      }
      for (TrackerResponse.PeerInfo peerInfo : response.getPeerInformation()) {
        result.add(peerInfo.getSocketAddress());
      }
    } catch (ParseException e) {
      logger.info("tracker response from " + tracker + " could not be parsed: " + e.getMessage());
    }
    return result;
  }

  private List<InetSocketAddress> announceUdp(Hash infoHash, PeerId peerId, int listeningPort, URI tracker)
      throws IOException {
    if (tracker.getHost() == null || tracker.getPort() <= 0) {
      throw new IOException("udp tracker uri is missing a host or port: " + tracker);
    }
    InetSocketAddress trackerAddress = new InetSocketAddress(tracker.getHost(), tracker.getPort());
    List<TrackerResponse.PeerInfo> peers = udpTrackerClient.announce(trackerAddress, infoHash, peerId, listeningPort);
    List<InetSocketAddress> result = new ArrayList<InetSocketAddress>();
    for (TrackerResponse.PeerInfo peer : peers) {
      result.add(peer.getSocketAddress());
    }
    return result;
  }

  /** The DHT search's peers, waiting up to timeoutMs for it; empty if it fails or isn't done by then. */
  private List<InetSocketAddress> dhtResult(FutureTask<List<InetSocketAddress>> dhtSearch, long timeoutMs) {
    try {
      return dhtSearch.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      logger.info("DHT search gave no result: " + e);
    }
    return new ArrayList<InetSocketAddress>();
  }

  private List<InetSocketAddress> findPeersViaDHT(Hash infoHash, int enoughPeers, DHTPeerFinder.PeersListener listener) {
    try {
      DHTracker dhTracker = environment.getDHTracker();
      return dhTracker.findPeers(infoHash, DHT_LOOKUP_TIMEOUT_MS, enoughPeers, listener);
    } catch (IOException e) {
      logger.warn("could not start DHT node", e);
      return new ArrayList<InetSocketAddress>();
    }
  }
}
