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

import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;
import freeloader.tracker.UdpTrackerClient;
import junit.framework.TestCase;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PeerFinderTest extends TestCase {

  private final Hash infoHash = Hash.fromHexString("0123456789abcdef0123456789abcdef01234567");
  private final List<URI> tracker = Collections.singletonList(URI.create("udp://tracker.example.com:6969"));

  public void testWaitsForTheDHTWhenTrackersHaveFewPeers() {
    PeerFinder finder = finder(peers(1, 1), peers(2, 5), 300);
    List<List<InetSocketAddress>> early = new CopyOnWriteArrayList<List<InetSocketAddress>>();
    List<InetSocketAddress> found = finder.find(
        infoHash, PeerId.createRandom(), 6881, tracker, message -> { }, early::add);

    assertEquals(6, found.size());
    // the tracker's peer was handed over before the DHT search was done
    assertEquals(1, early.size());
    assertEquals(peers(1, 1), early.get(0));
  }

  public void testDoesNotWaitForTheDHTWhenTrackersHaveEnoughPeers() {
    PeerFinder finder = finder(peers(1, 40), peers(2, 5), 10000);
    long start = System.currentTimeMillis();
    List<InetSocketAddress> found = finder.find(infoHash, PeerId.createRandom(), 6881, tracker, message -> { });

    assertEquals(40, found.size());
    assertTrue("took " + (System.currentTimeMillis() - start) + "ms",
        System.currentTimeMillis() - start < 5000);
  }

  public void testUsesTheDHTAloneWithoutTrackers() {
    PeerFinder finder = finder(peers(1, 1), peers(2, 3), 0);
    List<InetSocketAddress> found = finder.find(
        infoHash, PeerId.createRandom(), 6881, Collections.<URI>emptyList(), message -> { });
    assertEquals(peers(2, 3), found);
  }

  private PeerFinder finder(final List<InetSocketAddress> trackerPeers, final List<InetSocketAddress> dhtPeers,
                            final long dhtDelayMs) {
    PeerFinder finder = new PeerFinder(null);
    finder.setUdpTrackerClient(new UdpTrackerClient() {
      public List<TrackerResponse.PeerInfo> announce(
          InetSocketAddress trackerAddress, Hash hash, PeerId peerId, int listeningPort) {
        return PeerFinder.toPeerInfoList(trackerPeers);
      }
    });
    finder.setDhtSearch((hash, enough, listener) -> {
      try {
        Thread.sleep(dhtDelayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return dhtPeers;
    });
    return finder;
  }

  /** count distinct addresses in 10.0.network.x */
  private static List<InetSocketAddress> peers(int network, int count) {
    List<InetSocketAddress> result = new ArrayList<InetSocketAddress>();
    for (int i = 1; i <= count; i++) {
      result.add(new InetSocketAddress("10.0." + network + "." + i, 6881));
    }
    return result;
  }
}
