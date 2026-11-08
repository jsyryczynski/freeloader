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

/**
 * The RC4 stream cipher, as used by BitTorrent's connection encryption
 * (see EncryptedHandshake). Encrypting and decrypting are the same
 * operation; each direction of a connection has its own instance, whose
 * state carries on from one call to the next.
 */
public class Rc4 {

  private final byte[] state = new byte[256];
  private int i = 0;
  private int j = 0;

  public Rc4(byte[] key) {
    for (int k = 0; k < 256; k++) {
      state[k] = (byte) k;
    }
    for (int k = 0, l = 0; k < 256; k++) {
      l = (l + (state[k] & 0xff) + (key[k % key.length] & 0xff)) & 0xff;
      swap(k, l);
    }
  }

  /** Encrypts (or decrypts) length bytes of data in place. */
  public synchronized void process(byte[] data, int offset, int length) {
    for (int k = offset; k < offset + length; k++) {
      i = (i + 1) & 0xff;
      j = (j + (state[i] & 0xff)) & 0xff;
      swap(i, j);
      data[k] ^= state[((state[i] & 0xff) + (state[j] & 0xff)) & 0xff];
    }
  }

  public void process(byte[] data) {
    process(data, 0, data.length);
  }

  /** Throws away the next count bytes of keystream. */
  public void skip(int count) {
    process(new byte[count]);
  }

  private void swap(int a, int b) {
    byte tmp = state[a];
    state[a] = state[b];
    state[b] = tmp;
  }
}
