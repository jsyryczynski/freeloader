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

import java.util.Arrays;

/**
 * An extension protocol message (BEP 10): a one-byte extension message
 * id followed by that extension's payload. Id 0 is the extension
 * handshake, where each side says which extensions it speaks and under
 * which ids it wants to receive them.
 */
public class ExtendedPacket implements Packet {

  public static final int ID = 20;
  public static final int HANDSHAKE_ID = 0;

  private final int extendedId;
  private final byte[] payload;

  public ExtendedPacket(int extendedId, byte[] payload) {
    this.extendedId = extendedId;
    this.payload = payload;
  }

  public int writeIntoBuffer(byte[] buffer, int offset) {
    if (buffer.length < offset + getPayloadLength()) {
      throw new IllegalArgumentException("buffer is too small");
    }
    buffer[offset] = (byte) extendedId;
    System.arraycopy(payload, 0, buffer, offset + 1, payload.length);
    return offset + getPayloadLength();
  }

  public int getPayloadLength() {
    return 1 + payload.length;
  }

  public int getId() {
    return ID;
  }

  public void handle(PacketHandler handler) {
    handler.handleExtendedPacket(this);
  }

  public int getExtendedId() {
    return extendedId;
  }

  public byte[] getPayload() {
    return payload;
  }

  public static PacketConstructor<ExtendedPacket> getConstructor() {
    return new Constructor();
  }

  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ExtendedPacket that = (ExtendedPacket) o;
    return extendedId == that.extendedId && Arrays.equals(payload, that.payload);
  }

  public int hashCode() {
    return 31 * extendedId + Arrays.hashCode(payload);
  }

  public String toString() {
    return "extended message " + extendedId + ", " + payload.length + " bytes";
  }

  private static class Constructor implements PacketConstructor<ExtendedPacket> {
    public int getId() {
      return ID;
    }

    public ExtendedPacket constructPacket(byte[] buffer, int offset, int length) {
      if (length < 1 || buffer.length < offset + length) {
        throw new IllegalArgumentException("buffer length is not correct");
      }
      return new ExtendedPacket(buffer[offset] & 0xff, Arrays.copyOfRange(buffer, offset + 1, offset + length));
    }
  }
}
