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

import junit.framework.TestCase;
import freeloader.stats.FakeRepository;
import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Exercises the iterative BEP 5 get_peers lookup logic in isolation, with
 * a fake GetPeersSender standing in for the real DHT socket. This is
 * deliberately not a live-socket test (unlike MagnetMetadataFetcherTest's
 * FakePeer, which talks real TCP): DHTracker requires a full, always-on
 * Environment (thread pools, a live NetworkManager selector loop) to
 * construct, none of which shuts down cleanly, so building one inside a
 * JUnit run would leave the JVM unable to exit. Injecting GetPeersSender
 * keeps the actual lookup algorithm fully testable without any of that.
 */
public class DHTPeerFinderTest extends TestCase {

  private RoutingTable table;
  private Hash infoHash;

  protected void setUp() throws Exception {
    PeerId ourId = PeerId.createRandom();
    table = new RoutingTable(ourId, address -> { }, new FakeRepository());
    infoHash = Hash.forByteArray("some fake torrent info dictionary".getBytes());
  }

  public void testFindsPeersFromDirectMatch() throws Exception {
    InetSocketAddress node1 = loopbackAddress(30001);
    table.addNode(new TrackerResponse.PeerInfo(PeerId.createRandom(), node1));

    final InetSocketAddress foundPeer = loopbackAddress(40001);
    GetPeersSender fakeSender = new GetPeersSender() {
      public CompletableFuture<GetPeersOutcome> send(InetSocketAddress address, Hash hash) {
        return completedFuture(GetPeersOutcome.forPeers(new TrackerResponse.PeerInfo[]{
            new TrackerResponse.PeerInfo(PeerId.createRandom(), foundPeer)
        }));
      }
    };

    List<InetSocketAddress> result = new DHTPeerFinder(table, fakeSender).findPeers(infoHash, 5000);

    assertEquals(1, result.size());
    assertEquals(foundPeer, result.get(0));
  }

  public void testFollowsCloserNodesBeforeFindingPeers() throws Exception {
    final InetSocketAddress node1 = loopbackAddress(30002);
    table.addNode(new TrackerResponse.PeerInfo(PeerId.createRandom(), node1));

    final InetSocketAddress node2 = loopbackAddress(30003);
    final PeerId node2Id = PeerId.createRandom();
    final InetSocketAddress foundPeer = loopbackAddress(40002);

    GetPeersSender fakeSender = new GetPeersSender() {
      public CompletableFuture<GetPeersOutcome> send(InetSocketAddress address, Hash hash) {
        if (address.equals(node1)) {
          // node1 doesn't have the torrent, but points us at node2
          return completedFuture(GetPeersOutcome.forNodes(new TrackerResponse.PeerInfo[]{
              new TrackerResponse.PeerInfo(node2Id, node2)
          }));
        } else if (address.equals(node2)) {
          // node2 actually has peers for this torrent
          return completedFuture(GetPeersOutcome.forPeers(new TrackerResponse.PeerInfo[]{
              new TrackerResponse.PeerInfo(PeerId.createRandom(), foundPeer)
          }));
        }
        fail("unexpected query to " + address);
        return null;
      }
    };

    List<InetSocketAddress> result = new DHTPeerFinder(table, fakeSender).findPeers(infoHash, 5000);

    assertEquals(1, result.size());
    assertEquals(foundPeer, result.get(0));
  }

  public void testReportsPeersAsTheyAreFoundAndStopsAtTheTarget() throws Exception {
    // two nodes, each knowing different peers
    final InetSocketAddress node1 = loopbackAddress(30010);
    final InetSocketAddress node2 = loopbackAddress(30011);
    table.addNode(new TrackerResponse.PeerInfo(PeerId.createRandom(), node1));
    table.addNode(new TrackerResponse.PeerInfo(PeerId.createRandom(), node2));
    GetPeersSender fakeSender = new GetPeersSender() {
      public CompletableFuture<GetPeersOutcome> send(InetSocketAddress address, Hash hash) {
        int base = address.equals(node1) ? 41000 : 42000;
        TrackerResponse.PeerInfo[] peers = new TrackerResponse.PeerInfo[3];
        for (int i = 0; i < peers.length; i++) {
          peers[i] = new TrackerResponse.PeerInfo(PeerId.createRandom(), loopbackAddress(base + i));
        }
        return completedFuture(GetPeersOutcome.forPeers(peers));
      }
    };
    final List<InetSocketAddress> reported = new java.util.ArrayList<InetSocketAddress>();
    List<InetSocketAddress> result = new DHTPeerFinder(table, fakeSender)
        .findPeers(infoHash, 5000, 4, reported::addAll);

    assertEquals(6, result.size());
    assertEquals(result, reported);
  }

  public void testReturnsEmptyWhenNoNodesAreKnown() throws Exception {
    GetPeersSender fakeSender = new GetPeersSender() {
      public CompletableFuture<GetPeersOutcome> send(InetSocketAddress address, Hash hash) {
        fail("should never query anything, routing table is empty");
        return null;
      }
    };

    List<InetSocketAddress> result = new DHTPeerFinder(table, fakeSender).findPeers(infoHash, 1000);

    assertTrue(result.isEmpty());
  }

  public void testReturnsEmptyWhenEveryNodeTimesOutOrFails() throws Exception {
    InetSocketAddress node1 = loopbackAddress(30004);
    table.addNode(new TrackerResponse.PeerInfo(PeerId.createRandom(), node1));

    GetPeersSender fakeSender = new GetPeersSender() {
      public CompletableFuture<GetPeersOutcome> send(InetSocketAddress address, Hash hash) {
        return completedFuture(GetPeersOutcome.EMPTY);
      }
    };

    List<InetSocketAddress> result = new DHTPeerFinder(table, fakeSender).findPeers(infoHash, 2000);

    assertTrue(result.isEmpty());
  }

  private CompletableFuture<GetPeersOutcome> completedFuture(GetPeersOutcome outcome) {
    CompletableFuture<GetPeersOutcome> future = new CompletableFuture<GetPeersOutcome>();
    future.complete(outcome);
    return future;
  }

  private InetSocketAddress loopbackAddress(int port) {
    return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
  }
}
