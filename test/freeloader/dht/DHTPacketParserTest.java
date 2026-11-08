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

import freeloader.bencoding.BEntity;
import freeloader.bencoding.BList;
import freeloader.bencoding.BString;
import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;
import junit.framework.TestCase;
import org.easymock.EasyMock;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class DHTPacketParserTest extends TestCase {

  private static final byte[] NODE_ID = filled((byte) 0x11);
  private static final byte[] INFO_HASH = filled((byte) 0x22);
  private static final InetSocketAddress SENDER = new InetSocketAddress("10.0.0.1", 6881);

  public void testAcceptsTwentyByteIds() throws ProtocolError {
    assertNotNull(DHTPacketParser.toId(new byte[20], "id"));
  }

  public void testRejectsMalformedIdsAsProtocolErrors() {
    for (int length : new int[] {0, 19, 21, 32}) {
      try {
        DHTPacketParser.toId(new byte[length], "id");
        fail("accepted an id of " + length + " bytes");
      } catch (ProtocolError e) {
        // expected: dropped instead of crashing the network thread
      }
    }
  }

  public void testRejectsMalformedInfoHashesAsProtocolErrors() {
    for (int length : new int[] {0, 19, 21}) {
      try {
        DHTPacketParser.toInfoHash(new byte[length]);
        fail("accepted an info_hash of " + length + " bytes");
      } catch (ProtocolError e) {
        // expected
      }
    }
  }

  public void testGetPeersQueryCarriesTheInfoHashAsSent() throws Exception {
    PacketHandler handler = EasyMock.createMock(PacketHandler.class);
    handler.getPeers(EasyMock.eq(SENDER), EasyMock.anyObject(TransactionId.class),
        EasyMock.eq(new PeerId(NODE_ID)), EasyMock.eq(new Hash(INFO_HASH)));
    EasyMock.replay(handler);

    new DHTPacketParser(handler, null).parse(query("get_peers",
        "2:id20:", NODE_ID, "9:info_hash20:", INFO_HASH), SENDER);

    EasyMock.verify(handler);
  }

  public void testAnnouncePeerQueryCarriesTheInfoHashAsSent() throws Exception {
    PacketHandler handler = EasyMock.createMock(PacketHandler.class);
    handler.announcePeer(EasyMock.eq(SENDER), EasyMock.anyObject(TransactionId.class),
        EasyMock.eq(new PeerId(NODE_ID)), EasyMock.eq(new Hash(INFO_HASH)),
        EasyMock.anyObject(Token.class), EasyMock.eq(6881));
    EasyMock.replay(handler);

    new DHTPacketParser(handler, null).parse(query("announce_peer",
        "2:id20:", NODE_ID, "9:info_hash20:", INFO_HASH, "4:porti6881e5:token4:abcd"), SENDER);

    EasyMock.verify(handler);
  }

  /** A bencoded query; the argument parts are text or raw bytes, in key order. */
  private static byte[] query(String type, Object... arguments) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(out, "d1:ad");
    for (Object part : arguments) {
      if (part instanceof byte[]) {
        out.writeBytes((byte[]) part);
      } else {
        write(out, (String) part);
      }
    }
    write(out, "e1:q" + type.length() + ":" + type + "1:t2:aa1:y1:qe");
    return out.toByteArray();
  }

  private static void write(ByteArrayOutputStream out, String text) {
    out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static byte[] filled(byte value) {
    byte[] bytes = new byte[20];
    Arrays.fill(bytes, value);
    return bytes;
  }

  public void testEveryPeerInAReplyIsKeptAndBadOnesAreSkipped() throws ProtocolError {
    BList<BEntity> values = new BList<BEntity>();
    values.add(new BString(compactPeer(10, 0, 0, 1, 6881)));
    values.add(new BString(compactPeer(10, 0, 0, 2, 0)));
    values.add(new BString(new byte[5]));
    values.add(new BString(compactPeer(10, 0, 0, 3, 51413)));

    TrackerResponse.PeerInfo[] peers = DHTPacketParser.listToPeerInfo(values);

    assertEquals(2, peers.length);
    assertEquals("10.0.0.1", peers[0].getAddress().getHostAddress());
    assertEquals(6881, peers[0].getPort());
    assertEquals("10.0.0.3", peers[1].getAddress().getHostAddress());
    assertEquals(51413, peers[1].getPort());
  }

  public void testNodesWithPortZeroAreSkipped() throws ProtocolError {
    byte[] nodes = new byte[2 * 26];
    System.arraycopy(compactPeer(10, 0, 0, 1, 0), 0, nodes, 20, 6);
    System.arraycopy(compactPeer(10, 0, 0, 2, 6881), 0, nodes, 46, 6);

    TrackerResponse.PeerInfo[] found = DHTPacketParser.nodeBytesToArray(nodes);

    assertEquals(1, found.length);
    assertEquals("10.0.0.2", found[0].getAddress().getHostAddress());
  }

  private static byte[] compactPeer(int a, int b, int c, int d, int port) {
    return new byte[] {(byte) a, (byte) b, (byte) c, (byte) d, (byte) (port >> 8), (byte) port};
  }
}
