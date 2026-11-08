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

package freeloader.network;

import freeloader.network.packets.Packet;
import freeloader.network.packets.PacketFactory;
import freeloader.network.packets.PiecePacket;
import freeloader.network.packets.UnchokePacket;
import freeloader.util.ArrayUtil;

public class Serializer {

  private final PacketFactory packetFactory;

  private byte[] pendingData = new byte[0];

  public Serializer(PacketFactory packetFactory) {
    this.packetFactory = packetFactory;
  }

  public synchronized int dataAvailable() {
    return pendingData.length;
  }

  public void addPacket(Packet packet) {
    refuseUploads(packet);
    byte[] buffer = new byte[packetFactory.getExpectedBytesOnWire(packet)];
    packetFactory.serializePacket(packet, buffer, 0);
    addPacketData(buffer);
  }

  /**
   * Freeloader never uploads. Every message to a peer passes through
   * here, so this is the last line of defence: a piece (file data) or an
   * unchoke (permission to ask for file data) is never sent, whatever
   * code tried to.
   */
  static void refuseUploads(Packet packet) {
    if (packet instanceof PiecePacket || packet instanceof UnchokePacket) {
      throw new IllegalArgumentException(
          "Freeloader never uploads: refusing to send a " + packet.getClass().getSimpleName());
    }
  }

  private synchronized void addPacketData(byte[] buffer) {
    pendingData = ArrayUtil.append(pendingData, buffer);
  }

  public synchronized byte[] lendBytes(int count) {
    count = Math.min(count, pendingData.length);
    return ArrayUtil.subArray(pendingData, 0, count);
  }

  public synchronized void confirmWrittenBytes(byte[] bytes, int count) {
    if (!isPrefix(bytes, count)) {
      throw new IllegalArgumentException("wrong data confirmed");
    }
    removeBytes(count);
  }

  private void removeBytes(int count) {
    pendingData = ArrayUtil.cutFront(pendingData, count);
  }

  private boolean isPrefix(byte[] bytes, int count) {
    for (int i = 0; i < count; i++) {
      if (bytes[i] != pendingData[i]) {
        return false;
      }
    }
    return true;
  }
}
