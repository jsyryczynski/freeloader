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

import freeloader.stats.FakeRepository;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;
import freeloader.util.AdjustableTimeSource;
import freeloader.util.ByteUtil;
import freeloader.util.ExtendedTestCase;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class PeerManagerTest extends ExtendedTestCase {

  private static int uniquePort = 7341;

  public void testTwoDistinctPeers() throws IOException {
    PeerManager peerManager = createPeerManager();
    Collection<TrackerResponse.PeerInfo> peers = new ArrayList<TrackerResponse.PeerInfo>();
    peers.add(getRandomPeerInfo());
    peers.add(getRandomPeerInfo());
    peerManager.addPeersFromTrackerResponse(peers);
    assertEquals(2, peerManager.distinctPeersSeen());
  }

  private PeerManager createPeerManager() {
    return new PeerManager(new FakeRepository());
  }

  public void testSamePeerTwice() throws IOException {
    PeerManager peerManager = createPeerManager();
    Collection<TrackerResponse.PeerInfo> peers = new ArrayList<TrackerResponse.PeerInfo>();
    peers.add(getRandomPeerInfo());
    peers.add(getRandomPeerInfo());
    peers.add(getRandomPeerInfo());
    peers.add(peers.iterator().next());
    peerManager.addPeersFromTrackerResponse(peers);
    assertEquals(3, peerManager.distinctPeersSeen());
  }

  public void testGetConnectCandidates() throws IOException, InterruptedException {
    AdjustableTimeSource timeSource = new AdjustableTimeSource();

    PeerManager peerManager = createPeerManager();
    peerManager.setTimeSource(timeSource);

    timeSource.increaseTime(1);
    Collection<TrackerResponse.PeerInfo> peers = new ArrayList<TrackerResponse.PeerInfo>();
    TrackerResponse.PeerInfo a = getRandomPeerInfo();
    peers.add(a);
    TrackerResponse.PeerInfo b = getRandomPeerInfo();
    peers.add(b);
    peerManager.addPeersFromTrackerResponse(peers);

    timeSource.increaseTime(1);
    TrackerResponse.PeerInfo c = getRandomPeerInfo();
    peers.add(c);
    TrackerResponse.PeerInfo d = getRandomPeerInfo();
    peers.add(d);
    peerManager.addPeersFromTrackerResponse(peers);

    assertEquals(4, peerManager.distinctPeersSeen());

    timeSource.increaseTime(1);
    List<InetSocketAddress> candidates = peerManager.getConnectCandidates(2);
    assertEquals(2, candidates.size());
    assertContains(a.getSocketAddress(), candidates);
    assertContains(b.getSocketAddress(), candidates);

    timeSource.increaseTime(1);
    candidates = peerManager.getConnectCandidates(2);
    assertEquals(2, candidates.size());
    assertContains(c.getSocketAddress(), candidates);
    assertContains(d.getSocketAddress(), candidates);

    timeSource.increaseTime(1);
    candidates = peerManager.getConnectCandidates(2);
    assertEquals(2, candidates.size());
    assertContains(a.getSocketAddress(), candidates);
    assertContains(b.getSocketAddress(), candidates);

    timeSource.increaseTime(1);
    candidates = peerManager.getConnectCandidates(4);
    assertEquals(4, candidates.size());
    assertContains(a.getSocketAddress(), candidates);
    assertContains(b.getSocketAddress(), candidates);
    assertContains(c.getSocketAddress(), candidates);
    assertContains(d.getSocketAddress(), candidates);

    candidates = peerManager.getConnectCandidates(8);
    assertEquals(4, candidates.size());
  }

  public void testDropsAPeerAfterTwoRetries() throws IOException {
    PeerManager peerManager = createPeerManager();
    TrackerResponse.PeerInfo peer = getRandomPeerInfo();
    peerManager.addPeersFromTrackerResponse(Collections.singletonList(peer));

    peerManager.connectionFailed(peer.getSocketAddress());
    peerManager.connectionFailed(peer.getSocketAddress());
    assertEquals("still worth a third try", 1, peerManager.distinctPeersSeen());
    peerManager.connectionFailed(peer.getSocketAddress());
    assertEquals(0, peerManager.distinctPeersSeen());
    assertEquals(1, peerManager.droppedPeers());

    // reported again (by the DHT, peer exchange, ...): still ignored
    peerManager.addPeersFromTrackerResponse(Collections.singletonList(peer));
    assertEquals(0, peerManager.distinctPeersSeen());
    assertTrue(peerManager.getConnectCandidates(10).isEmpty());
  }

  public void testASuccessfulConnectionForgivesEarlierFailures() throws IOException {
    PeerManager peerManager = createPeerManager();
    TrackerResponse.PeerInfo peer = getRandomPeerInfo();
    peerManager.addPeersFromTrackerResponse(Collections.singletonList(peer));

    peerManager.connectionFailed(peer.getSocketAddress());
    peerManager.connectionFailed(peer.getSocketAddress());
    peerManager.connectionSucceeded(peer.getSocketAddress());
    peerManager.connectionFailed(peer.getSocketAddress());
    peerManager.connectionFailed(peer.getSocketAddress());
    assertEquals(1, peerManager.distinctPeersSeen());
  }

  private TrackerResponse.PeerInfo getRandomPeerInfo() throws UnknownHostException {
    return new TrackerResponse.PeerInfo(
        PeerId.createRandomMainlineId(),
        InetAddress.getByAddress(ByteUtil.randomByteArray(4)),
        uniquePort++);
  }
}
