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

package freeloader.util;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** SHA-1, which BitTorrent uses for info hashes and piece checks. */
public final class DigestUtil {

  private DigestUtil() {
  }

  public static byte[] sha1(byte[] data) {
    return sha1(data, 0, data.length);
  }

  public static byte[] sha1(byte[] data, int from, int count) {
    MessageDigest sha1 = newSha1();
    sha1.update(data, from, count);
    return sha1.digest();
  }

  private static MessageDigest newSha1() {
    try {
      return MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      // every Java runtime has to provide SHA-1
      throw new IllegalStateException(e);
    }
  }
}
