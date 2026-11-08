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


package freeloader.network.packets;

import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import junit.framework.TestCase;

import java.util.Arrays;

public class ExtendedPacketTest extends TestCase {

  private final PacketFactory factory = new PacketFactory();

  public void testRoundTripThroughThePacketFactory() {
    ExtendedPacket packet = new ExtendedPacket(1, new byte[] {'d', 'e'});
    byte[] wire = new byte[factory.getExpectedBytesOnWire(packet)];
    factory.serializePacket(packet, wire, 0);
    // length 4, id 20, extended id 1, payload
    assertTrue(Arrays.equals(new byte[] {0, 0, 0, 4, 20, 1, 'd', 'e'}, wire));
    assertEquals(packet, factory.buildPacket(wire, 0, wire.length));
  }

  public void testHandshakeOffersTheExtensionProtocol() {
    Hash hash = Hash.fromHexString("0123456789abcdef0123456789abcdef01234567");
    HandshakePacket plain = new HandshakePacket(hash, PeerId.createRandom());
    HandshakePacket extended = HandshakePacket.withExtensionProtocol(hash, PeerId.createRandom());
    assertFalse(plain.supportsExtensionProtocol());
    assertTrue(extended.supportsExtensionProtocol());

    // and the bit survives the wire: reserved byte 5, 0x10
    byte[] wire = new byte[factory.getExpectedBytesOnWire(extended)];
    factory.serializePacket(extended, wire, 0);
    assertEquals(0x10, wire[1 + 19 + 5]);
    assertTrue(factory.buildHandshakePacket(wire, 0, wire.length).supportsExtensionProtocol());
  }
}
