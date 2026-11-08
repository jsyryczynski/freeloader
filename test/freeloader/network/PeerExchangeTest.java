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


package freeloader.network;

import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BInteger;
import junit.framework.TestCase;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class PeerExchangeTest extends TestCase {

  public void testHandshakeAsksForPeerExchange() throws Exception {
    BDictionary<?> handshake = (BDictionary<?>) new BDecoder().decode(PeerExchange.handshakePayload(6881));
    BDictionary<?> supported = (BDictionary<?>) handshake.getByString("m");
    assertEquals(PeerExchange.OUR_UT_PEX_ID, ((BInteger) supported.getByString("ut_pex")).intValue());
    assertEquals(6881, ((BInteger) handshake.getByString("p")).intValue());
  }

  public void testReadsAddedPeers() {
    // d5:added12:<1.2.3.4:6881><10.0.0.1:51413>7:added.f2:..7:dropped0:e
    byte[] payload = message(new byte[] {1, 2, 3, 4, 0x1a, (byte) 0xe1, 10, 0, 0, 1, (byte) 0xc8, (byte) 0xd5});
    List<InetSocketAddress> peers = PeerExchange.addedPeers(payload);
    assertEquals(Arrays.asList(
        new InetSocketAddress("1.2.3.4", 6881), new InetSocketAddress("10.0.0.1", 51413)), peers);
  }

  public void testSkipsPortZeroAndTrailingBytes() {
    byte[] payload = message(new byte[] {1, 2, 3, 4, 0, 0, 5, 6, 7, 8, 0, 80, 9});
    assertEquals(Arrays.asList(new InetSocketAddress("5.6.7.8", 80)), PeerExchange.addedPeers(payload));
  }

  public void testIgnoresGarbage() {
    assertTrue(PeerExchange.addedPeers("not bencoded".getBytes(StandardCharsets.ISO_8859_1)).isEmpty());
    assertTrue(PeerExchange.addedPeers("d7:dropped0:e".getBytes(StandardCharsets.ISO_8859_1)).isEmpty());
  }

  private static byte[] message(byte[] added) {
    byte[] head = ("d5:added" + added.length + ":").getBytes(StandardCharsets.ISO_8859_1);
    byte[] tail = "7:dropped0:e".getBytes(StandardCharsets.ISO_8859_1);
    byte[] result = new byte[head.length + added.length + tail.length];
    System.arraycopy(head, 0, result, 0, head.length);
    System.arraycopy(added, 0, result, head.length, added.length);
    System.arraycopy(tail, 0, result, head.length + added.length, tail.length);
    return result;
  }
}
