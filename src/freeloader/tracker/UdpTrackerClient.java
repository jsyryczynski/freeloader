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

import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A UDP tracker client (BEP 15). Most public BitTorrent trackers today
 * are UDP-only (udp://... in a magnet link's tr= parameters), so this is
 * needed alongside the existing HTTP-only TrackerClient to actually make
 * use of the trackers a real-world magnet link provides.
 *
 * Protocol: a "connect" request/response establishes a short-lived
 * connection id, which is then used in an "announce" request/response to
 * get back a compact peer list -- both over plain UDP datagrams, no
 * persistent connection.
 */
public class UdpTrackerClient {

  private static final long PROTOCOL_ID = 0x41727101980L;
  private static final int ACTION_CONNECT = 0;
  private static final int ACTION_ANNOUNCE = 1;
  private static final int EVENT_STARTED = 2;

  private static final int CONNECT_REQUEST_LENGTH = 16;
  private static final int CONNECT_RESPONSE_LENGTH = 16;
  private static final int ANNOUNCE_REQUEST_LENGTH = 98;
  private static final int ANNOUNCE_RESPONSE_HEADER_LENGTH = 20;
  private static final int MAX_RESPONSE_LENGTH = 4096;

  private static final int SOCKET_TIMEOUT_MS = 4000;
  private static final int MAX_ATTEMPTS = 2;

  private final Random random = new Random();

  /**
   * Connects to and announces to a single UDP tracker, returning
   * whatever peers it reports for the given info hash. Blocks the
   * calling thread; callers running this from a pool should do so with
   * their own timeout/deadline handling around the call.
   */
  public List<TrackerResponse.PeerInfo> announce(
      InetSocketAddress trackerAddress, Hash infoHash, PeerId peerId, int listeningPort) throws IOException {
    DatagramSocket socket = new DatagramSocket();
    try {
      socket.setSoTimeout(SOCKET_TIMEOUT_MS);
      long connectionId = connect(socket, trackerAddress);
      return announceWithConnectionId(socket, trackerAddress, connectionId, infoHash, peerId, listeningPort);
    } finally {
      socket.close();
    }
  }

  private long connect(DatagramSocket socket, InetSocketAddress trackerAddress) throws IOException {
    int transactionId = random.nextInt();
    ByteBuffer request = ByteBuffer.allocate(CONNECT_REQUEST_LENGTH);
    request.putLong(PROTOCOL_ID);
    request.putInt(ACTION_CONNECT);
    request.putInt(transactionId);

    IOException lastException = null;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      try {
        send(socket, request.array(), trackerAddress);
        byte[] response = receive(socket);
        if (response.length < CONNECT_RESPONSE_LENGTH) {
          throw new IOException("connect response too short: " + response.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(response);
        int action = buffer.getInt();
        int replyTransactionId = buffer.getInt();
        if (action != ACTION_CONNECT || replyTransactionId != transactionId) {
          throw new IOException("unexpected connect response (action=" + action + ")");
        }
        return buffer.getLong();
      } catch (SocketTimeoutException e) {
        lastException = e;
      }
    }
    throw lastException != null ? lastException : new IOException("connect to " + trackerAddress + " failed");
  }

  private List<TrackerResponse.PeerInfo> announceWithConnectionId(
      DatagramSocket socket, InetSocketAddress trackerAddress, long connectionId,
      Hash infoHash, PeerId peerId, int listeningPort) throws IOException {
    int transactionId = random.nextInt();
    ByteBuffer request = ByteBuffer.allocate(ANNOUNCE_REQUEST_LENGTH);
    request.putLong(connectionId);
    request.putInt(ACTION_ANNOUNCE);
    request.putInt(transactionId);
    request.put(infoHash.getBytes());
    request.put(peerId.getBytes());
    request.putLong(0);              // downloaded
    request.putLong(Long.MAX_VALUE); // left (unknown; claim we want everything)
    request.putLong(0);              // uploaded
    request.putInt(EVENT_STARTED);
    request.putInt(0);               // ip, 0 = let the tracker use the packet's source address
    request.putInt(random.nextInt()); // key
    request.putInt(-1);              // num_want, -1 = default
    request.putShort((short) listeningPort);

    IOException lastException = null;
    for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
      try {
        send(socket, request.array(), trackerAddress);
        byte[] response = receive(socket);
        return parseAnnounceResponse(response, transactionId);
      } catch (SocketTimeoutException e) {
        lastException = e;
      }
    }
    throw lastException != null ? lastException : new IOException("announce to " + trackerAddress + " failed");
  }

  private List<TrackerResponse.PeerInfo> parseAnnounceResponse(byte[] response, int expectedTransactionId)
      throws IOException {
    if (response.length < ANNOUNCE_RESPONSE_HEADER_LENGTH) {
      throw new IOException("announce response too short: " + response.length + " bytes");
    }
    ByteBuffer buffer = ByteBuffer.wrap(response);
    int action = buffer.getInt();
    int transactionId = buffer.getInt();
    if (action != ACTION_ANNOUNCE || transactionId != expectedTransactionId) {
      throw new IOException("unexpected announce response (action=" + action + ")");
    }
    buffer.getInt(); // interval, not used
    buffer.getInt(); // leechers, not used
    buffer.getInt(); // seeders, not used

    int peerBytesAvailable = response.length - ANNOUNCE_RESPONSE_HEADER_LENGTH;
    int peerCount = peerBytesAvailable / 6;
    List<TrackerResponse.PeerInfo> peers = new ArrayList<TrackerResponse.PeerInfo>(peerCount);
    for (int i = 0; i < peerCount; i++) {
      TrackerResponse.PeerInfo peer =
          TrackerResponse.PeerInfo.fromCompactForm(response, ANNOUNCE_RESPONSE_HEADER_LENGTH + i * 6);
      if (peer != null) {
        peers.add(peer);
      }
    }
    return peers;
  }

  private void send(DatagramSocket socket, byte[] data, InetSocketAddress address) throws IOException {
    socket.send(new DatagramPacket(data, data.length, address));
  }

  private byte[] receive(DatagramSocket socket) throws IOException {
    byte[] buffer = new byte[MAX_RESPONSE_LENGTH];
    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    socket.receive(packet);
    byte[] result = new byte[packet.getLength()];
    System.arraycopy(buffer, 0, result, 0, packet.getLength());
    return result;
  }
}
