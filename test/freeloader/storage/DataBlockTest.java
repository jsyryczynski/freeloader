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

package freeloader.storage;

import freeloader.network.packets.PiecePacket;
import freeloader.util.ByteUtil;
import freeloader.util.ExtendedTestCase;

public class DataBlockTest extends ExtendedTestCase {

  public void testConstructor() {
    byte[] data = ByteUtil.randomByteArray(48);
    DataBlock dataBlock = new DataBlock(71, 841, data);
    assertEquals(71, dataBlock.getPieceIndex());
    assertEquals(841, dataBlock.getOffset());
    assertArrayEquals(data, dataBlock.getData());
    assertNotSame(data, dataBlock.getData());
  }

  public void testFromPacket() {
    byte[] data = ByteUtil.randomByteArray(25);
    DataBlock dataBlock = new DataBlock(5, 15, data);

    assertEquals(dataBlock, DataBlock.fromPiecePacket(new PiecePacket(5, 15, data)));
  }
}
