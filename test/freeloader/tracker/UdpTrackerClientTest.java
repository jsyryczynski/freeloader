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

package freeloader.tracker;

import junit.framework.TestCase;
import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Exercises UdpTrackerClient against a real, wire-protocol-correct fake
 * UDP tracker (BEP 15) over a real loopback DatagramSocket, the same
 * "genuine local protocol round-trip, no mocks" approach used elsewhere
 * in this codebase (e.g. MagnetMetadataFetcherTest's FakePeer) since
 * there's no internet access available to test against a real tracker.
 */
public class UdpTrackerClientTest extends TestCase {

  private static final long PROTOCOL_ID = 0x41727101980L;
  private static final int ACTION_CONNECT = 0;
  private static final int ACTION_ANNOUNCE = 1;

  public void testAnnounceReturnsPeersFromFakeTracker() throws Exception {
    Hash infoHash = Hash.forByteArray("some fake torrent info dictionary".getBytes());
    PeerId ourPeerId = PeerId.createRandomMainlineId();

    InetSocketAddress expectedPeer = new InetSocketAddress(InetAddress.getLoopbackAddress(), 55123);

    FakeUdpTracker fakeTracker = new FakeUdpTracker(expectedPeer);
    Thread thread = new Thread(fakeTracker);
    thread.setDaemon(true);
    thread.start();

    try {
      InetSocketAddress trackerAddress =
          new InetSocketAddress(InetAddress.getLoopbackAddress(), fakeTracker.getPort());
      UdpTrackerClient client = new UdpTrackerClient();
      List<TrackerResponse.PeerInfo> peers = client.announce(trackerAddress, infoHash, ourPeerId, 6881);

      assertEquals(1, peers.size());
      assertEquals(expectedPeer.getAddress(), peers.get(0).getAddress());
      assertEquals(expectedPeer.getPort(), peers.get(0).getPort());
    } finally {
      fakeTracker.stop();
    }
  }

  /**
   * A minimal, real UDP tracker implementing just enough of BEP 15
   * (connect + announce) to answer UdpTrackerClient's requests.
   */
  private static class FakeUdpTracker implements Runnable {
    private final DatagramSocket socket;
    private final InetSocketAddress peerToReturn;
    private volatile boolean running = true;

    FakeUdpTracker(InetSocketAddress peerToReturn) throws Exception {
      this.socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
      this.socket.setSoTimeout(5000);
      this.peerToReturn = peerToReturn;
    }

    int getPort() {
      return socket.getLocalPort();
    }

    void stop() {
      running = false;
      socket.close();
    }

    public void run() {
      try {
        handleConnectThenAnnounce();
      } catch (Exception e) {
        if (running) {
          throw new RuntimeException(e);
        }
        // socket was closed on purpose during shutdown, ignore
      }
    }

    private void handleConnectThenAnnounce() throws Exception {
      byte[] buffer = new byte[512];

      // connect request
      DatagramPacket connectRequestPacket = new DatagramPacket(buffer, buffer.length);
      socket.receive(connectRequestPacket);
      ByteBuffer connectRequest = ByteBuffer.wrap(connectRequestPacket.getData(), 0, connectRequestPacket.getLength());
      long protocolId = connectRequest.getLong();
      int connectAction = connectRequest.getInt();
      int connectTransactionId = connectRequest.getInt();
      if (protocolId != PROTOCOL_ID || connectAction != ACTION_CONNECT) {
        throw new IllegalStateException("unexpected connect request");
      }

      long connectionId = 0x1122334455667788L;
      ByteBuffer connectResponse = ByteBuffer.allocate(16);
      connectResponse.putInt(ACTION_CONNECT);
      connectResponse.putInt(connectTransactionId);
      connectResponse.putLong(connectionId);
      send(connectResponse.array(), connectRequestPacket.getSocketAddress());

      // announce request
      DatagramPacket announceRequestPacket = new DatagramPacket(buffer, buffer.length);
      socket.receive(announceRequestPacket);
      ByteBuffer announceRequest = ByteBuffer.wrap(announceRequestPacket.getData(), 0, announceRequestPacket.getLength());
      long requestConnectionId = announceRequest.getLong();
      int announceAction = announceRequest.getInt();
      int announceTransactionId = announceRequest.getInt();
      if (requestConnectionId != connectionId || announceAction != ACTION_ANNOUNCE) {
        throw new IllegalStateException("unexpected announce request");
      }

      byte[] compactPeer = new byte[6];
      System.arraycopy(peerToReturn.getAddress().getAddress(), 0, compactPeer, 0, 4);
      compactPeer[4] = (byte) ((peerToReturn.getPort() >> 8) & 0xff);
      compactPeer[5] = (byte) (peerToReturn.getPort() & 0xff);

      ByteBuffer announceResponse = ByteBuffer.allocate(20 + compactPeer.length);
      announceResponse.putInt(ACTION_ANNOUNCE);
      announceResponse.putInt(announceTransactionId);
      announceResponse.putInt(1800); // interval
      announceResponse.putInt(0);    // leechers
      announceResponse.putInt(1);    // seeders
      announceResponse.put(compactPeer);
      send(announceResponse.array(), announceRequestPacket.getSocketAddress());
    }

    private void send(byte[] data, java.net.SocketAddress recipient) throws Exception {
      socket.send(new DatagramPacket(data, data.length, recipient));
    }
  }
}
