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

package freeloader.downloader.peermanager;

import org.apache.log4j.Logger;
import freeloader.stats.CounterRepository;
import freeloader.tracker.TrackerResponse;
import freeloader.util.CollectionUtils;
import freeloader.util.SystemTimeSource;
import freeloader.util.TimeSource;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PeerManager {

  private Map<InetSocketAddress, PeerRecord> peers =
      new HashMap<InetSocketAddress, PeerRecord>();

  // a peer that never gets as far as a BitTorrent handshake (unreachable,
  // or hangs up first) is dropped after its first attempt and 2 retries,
  // and ignored if it's reported again. Kept in memory only.
  static final int MAX_FAILED_ATTEMPTS = 3;
  private final Map<InetSocketAddress, Integer> failedAttempts = new HashMap<InetSocketAddress, Integer>();
  private final Set<InetSocketAddress> dropped = new HashSet<InetSocketAddress>();

  private TimeSource timeSource = SystemTimeSource.INSTANCE;

  private final CounterRepository counterRepository;

  private static final Logger logger = Logger.getLogger(PeerManager.class);

  public PeerManager(CounterRepository counterRepository) {
    this.counterRepository = counterRepository;
  }

  public synchronized void addPeersFromTrackerResponse(Collection<TrackerResponse.PeerInfo> newPeers) {
    List<PeerRecord> newRecords =
        CollectionUtils.map(newPeers, new CollectionUtils.Function<TrackerResponse.PeerInfo, PeerRecord>() {
          public PeerRecord evaluate(TrackerResponse.PeerInfo source) {
            return new PeerRecord(source.getSocketAddress(), timeSource);
          }
        });
    newRecords =
        CollectionUtils.mapNullRemoves(newRecords, new CollectionUtils.Function<PeerRecord, PeerRecord>() {
          public PeerRecord evaluate(PeerRecord source) {
            if (dropped.contains(source.getAddress())) {
              return null;
            }
            PeerRecord existingPeer = peers.get(source.getAddress());
            if (existingPeer != null) {
              existingPeer.seenByTracker();
              return null;
            }
            return source;
          }
        });
    logger.debug("adding new peers from tracker response: " + newRecords.size());
    logger.trace(newRecords);

    for (PeerRecord peer : newRecords) {
      peers.put(peer.getAddress(), peer);
    }

    logger.debug(distinctPeersSeen() + " distinct peers seen");
    counterRepository.getCounter("torrent.distinctpeers").set(distinctPeersSeen());
  }

  /** A connection attempt that didn't reach the BitTorrent handshake. */
  public synchronized void connectionFailed(InetSocketAddress address) {
    if (!peers.containsKey(address)) {
      return;
    }
    int failures = failedAttempts.merge(address, 1, Integer::sum);
    if (failures >= MAX_FAILED_ATTEMPTS) {
      logger.debug("dropping " + address + " after " + failures + " failed attempts");
      peers.remove(address);
      failedAttempts.remove(address);
      dropped.add(address);
      counterRepository.getCounter("torrent.distinctpeers").set(distinctPeersSeen());
    }
  }

  /** A connection that got through the BitTorrent handshake: earlier failures are forgiven. */
  public synchronized void connectionSucceeded(InetSocketAddress address) {
    failedAttempts.remove(address);
  }

  public synchronized int droppedPeers() {
    return dropped.size();
  }

  public int distinctPeersSeen() {
    return peers.size();
  }

  public void setTimeSource(TimeSource timeSource) {
    this.timeSource = timeSource;
  }

  public synchronized List<InetSocketAddress> getConnectCandidates(int count) {
    List<PeerRecord> list = new ArrayList<PeerRecord>(peers.values());
    Collections.sort(list, new Comparator<PeerRecord>() {
      public int compare(PeerRecord o1, PeerRecord o2) {
        if (o1.getLastScheduledForConnectionTime() < o2.getLastScheduledForConnectionTime()) {
          return -1;
        } else if (o1.getLastScheduledForConnectionTime() > o2.getLastScheduledForConnectionTime()) {
          return +1;
        } else {
          return 0;
        }
      }
    });

    List<InetSocketAddress> result = new ArrayList<InetSocketAddress>(count);
    for (PeerRecord peer : list.subList(0, Math.min(count, list.size()))) {
      peer.scheduleForConnectionInitiation();
      result.add(peer.getAddress());
    }
    return result;
  }
}
