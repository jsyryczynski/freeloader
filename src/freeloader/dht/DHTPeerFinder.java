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

package freeloader.dht;

import org.apache.log4j.Logger;
import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;
import freeloader.util.ByteUtil;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Iterative, Kademlia-style "find peers for this info hash" lookup (BEP
 * 5 get_peers). Used to find peers for a trackerless (DHT-only) magnet
 * link, where there's no tracker to ask for a peer list.
 *
 * Starts from the closest nodes already known in the local routing
 * table, queries them for the target info hash, and follows the "closer
 * nodes" they return until either some actual peers are found, or the
 * lookup runs out of closer nodes to try, or it runs out of time.
 *
 * The actual sending of queries is abstracted behind GetPeersSender so
 * this class can be (and is) unit tested without a live DHT socket.
 */
public class DHTPeerFinder {

  // A cold routing table only knows a handful of nodes that are close to
  // a *random* id, not to any particular info hash -- converging from
  // there down to nodes that actually have peer data for one specific
  // torrent on the real (tens-of-millions-of-nodes) mainline DHT
  // typically takes quite a few hops. Generous values here, bounded by
  // the caller's overall timeout, matter more than they would against a
  // richer, long-lived routing table.
  private static final int ALPHA = 8;
  private static final int SHORTLIST_SIZE = 100;
  private static final int MAX_ROUNDS = 30;
  private static final long PER_ROUND_TIMEOUT_MS = 3000;
  // by default, keep digging until this many peers are known (or time
  // runs out): most peers the DHT hands out turn out unreachable, full or
  // gone, and in practice only something like 1 in 20 will hand over a
  // magnet's metadata
  public static final int DEFAULT_ENOUGH_PEERS = 50;
  // when every known node has been asked, wait this long for the routing
  // table to learn new ones (late bootstrap replies, the spider) before
  // looking again
  private static final long OUT_OF_NODES_WAIT_MS = 1000;

  private static final Logger logger = Logger.getLogger(DHTPeerFinder.class);

  private final RoutingTable table;
  private final GetPeersSender sender;

  public DHTPeerFinder(RoutingTable table, GetPeersSender sender) {
    this.table = table;
    this.sender = sender;
  }

  /** Gets the peers found in each round of the lookup, as soon as they're in. */
  public interface PeersListener {
    void onPeers(List<InetSocketAddress> newPeers);
  }

  public List<InetSocketAddress> findPeers(Hash infoHash, long overallTimeoutMs) {
    return findPeers(infoHash, overallTimeoutMs, DEFAULT_ENOUGH_PEERS, peers -> { });
  }

  /**
   * Looks until enoughPeers are known, the lookup runs out of nodes to
   * ask, or time runs out; newly found peers go to the listener after
   * every round along the way.
   */
  public List<InetSocketAddress> findPeers(Hash infoHash, long overallTimeoutMs, int enoughPeers,
                                           PeersListener listener) {
    PeerId targetId = new PeerId(infoHash.getBytes());
    long deadline = System.currentTimeMillis() + overallTimeoutMs;

    Set<InetSocketAddress> queried = new LinkedHashSet<InetSocketAddress>();
    Set<InetSocketAddress> foundPeers = new LinkedHashSet<InetSocketAddress>();

    List<TrackerResponse.PeerInfo> shortlist =
        new ArrayList<TrackerResponse.PeerInfo>(Arrays.asList(table.getNodesCloseTo(targetId, SHORTLIST_SIZE)));
    sortByDistance(shortlist, targetId);

    for (int round = 0; round < MAX_ROUNDS && System.currentTimeMillis() < deadline; round++) {
      List<TrackerResponse.PeerInfo> toQuery = pickUnqueried(shortlist, queried);
      if (toQuery.isEmpty()) {
        if (!foundPeers.isEmpty()) {
          logger.debug("no more unqueried nodes in shortlist, stopping lookup");
          break;
        }
        // nothing found yet but there's time left: pick up whatever the
        // routing table has learned since the lookup started
        for (TrackerResponse.PeerInfo node : table.getNodesCloseTo(targetId, SHORTLIST_SIZE)) {
          if (!queried.contains(node.getSocketAddress()) && !containsAddress(shortlist, node.getSocketAddress())) {
            shortlist.add(node);
          }
        }
        sortByDistance(shortlist, targetId);
        toQuery = pickUnqueried(shortlist, queried);
        if (toQuery.isEmpty()) {
          logger.debug("no unqueried nodes known yet, waiting for the routing table to grow");
          sleepUntil(Math.min(deadline, System.currentTimeMillis() + OUT_OF_NODES_WAIT_MS));
          continue;
        }
      }

      Map<TrackerResponse.PeerInfo, CompletableFuture<GetPeersOutcome>> inFlight =
          new LinkedHashMap<TrackerResponse.PeerInfo, CompletableFuture<GetPeersOutcome>>();
      for (TrackerResponse.PeerInfo node : toQuery) {
        queried.add(node.getSocketAddress());
        inFlight.put(node, sender.send(node.getSocketAddress(), infoHash));
      }

      long roundDeadline = Math.min(deadline, System.currentTimeMillis() + PER_ROUND_TIMEOUT_MS);
      List<InetSocketAddress> newPeers = new ArrayList<InetSocketAddress>();
      List<TrackerResponse.PeerInfo> newlyDiscoveredNodes = new ArrayList<TrackerResponse.PeerInfo>();
      for (CompletableFuture<GetPeersOutcome> future : inFlight.values()) {
        long remaining = Math.max(0, roundDeadline - System.currentTimeMillis());
        try {
          GetPeersOutcome outcome = future.get(remaining, TimeUnit.MILLISECONDS);
          if (outcome.isMatch()) {
            for (TrackerResponse.PeerInfo peer : outcome.getPeers()) {
              if (foundPeers.add(peer.getSocketAddress())) {
                newPeers.add(peer.getSocketAddress());
              }
            }
          } else {
            newlyDiscoveredNodes.addAll(Arrays.asList(outcome.getNodes()));
          }
        } catch (TimeoutException e) {
          logger.debug("get_peers query timed out");
        } catch (Exception e) {
          logger.debug("get_peers query failed", e);
        }
      }

      if (!newPeers.isEmpty()) {
        listener.onPeers(newPeers);
      }

      for (TrackerResponse.PeerInfo node : newlyDiscoveredNodes) {
        if (!queried.contains(node.getSocketAddress()) && !containsAddress(shortlist, node.getSocketAddress())) {
          shortlist.add(node);
        }
      }
      sortByDistance(shortlist, targetId);
      trimToSize(shortlist, SHORTLIST_SIZE);

      if (foundPeers.size() >= enoughPeers) {
        // enough real peers; stop here rather than digging for more, to
        // keep a magnet download from stalling on the DHT lookup
        break;
      }
    }

    return new ArrayList<InetSocketAddress>(foundPeers);
  }

  private List<TrackerResponse.PeerInfo> pickUnqueried(
      List<TrackerResponse.PeerInfo> shortlist, Set<InetSocketAddress> queried) {
    List<TrackerResponse.PeerInfo> result = new ArrayList<TrackerResponse.PeerInfo>();
    for (TrackerResponse.PeerInfo node : shortlist) {
      if (!queried.contains(node.getSocketAddress())) {
        result.add(node);
        if (result.size() >= ALPHA) {
          break;
        }
      }
    }
    return result;
  }

  private static void sleepUntil(long time) {
    long millis = time - System.currentTimeMillis();
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean containsAddress(List<TrackerResponse.PeerInfo> list, InetSocketAddress address) {
    for (TrackerResponse.PeerInfo node : list) {
      if (node.getSocketAddress().equals(address)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Closest to the target first, by XOR distance. (Not via
   * PeerId.distance(): despite its name that's a closeness score that
   * grows as ids get nearer, so sorting by it ascending puts the farthest
   * nodes first and walks the lookup away from the target.)
   */
  static void sortByDistance(List<TrackerResponse.PeerInfo> list, final PeerId targetId) {
    final byte[] target = targetId.getBytes();
    Collections.sort(list, new Comparator<TrackerResponse.PeerInfo>() {
      public int compare(TrackerResponse.PeerInfo a, TrackerResponse.PeerInfo b) {
        return Arrays.compareUnsigned(
            ByteUtil.xor(a.getPeerId().getBytes(), target), ByteUtil.xor(b.getPeerId().getBytes(), target));
      }
    });
  }

  private void trimToSize(List<TrackerResponse.PeerInfo> list, int size) {
    while (list.size() > size) {
      list.remove(list.size() - 1);
    }
  }
}
