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

package freeloader.torrent;

import junit.framework.TestCase;

import java.net.URI;

public class MagnetUriTest extends TestCase {

  // 40 lowercase hex chars = 20 bytes
  private static final String HEX_HASH_40 = "0123456789abcdef0123456789abcdef01234567";

  public void testIsMagnetUri() {
    assertTrue(MagnetUri.isMagnetUri("magnet:?xt=urn:btih:" + HEX_HASH_40));
    assertTrue(MagnetUri.isMagnetUri("  magnet:?xt=urn:btih:" + HEX_HASH_40));
    assertFalse(MagnetUri.isMagnetUri("http://example.com/file.torrent"));
    assertFalse(MagnetUri.isMagnetUri(null));
  }

  public void testParseHexHashOnly() throws Exception {
    MagnetUri magnet = MagnetUri.parse("magnet:?xt=urn:btih:" + HEX_HASH_40);
    assertEquals(new Hash(hexToBytes(HEX_HASH_40)), magnet.getInfoHash());
    assertNull(magnet.getDisplayName());
    assertTrue(magnet.getTrackers().isEmpty());
  }

  public void testParseWithDisplayNameAndTrackers() throws Exception {
    String uri = "magnet:?xt=urn:btih:" + HEX_HASH_40
        + "&dn=Some+File+Name"
        + "&tr=http%3A%2F%2Ftracker.example.com%3A6969%2Fannounce"
        + "&tr=udp%3A%2F%2Ftracker2.example.com%3A80";
    MagnetUri magnet = MagnetUri.parse(uri);
    assertEquals(new Hash(hexToBytes(HEX_HASH_40)), magnet.getInfoHash());
    assertEquals("Some File Name", magnet.getDisplayName());
    // both trackers must survive parsing -- most real-world magnet links
    // are udp:// only, and java.net.URL (unlike java.net.URI) would
    // throw MalformedURLException on that scheme and silently drop it
    assertEquals(2, magnet.getTrackers().size());
    assertEquals(1, countTrackersWithScheme(magnet, "http"));
    assertEquals(1, countTrackersWithScheme(magnet, "udp"));
  }

  public void testParseKeepsUdpOnlyTrackers() throws Exception {
    // the overwhelmingly common case in the wild: a magnet link whose
    // trackers are exclusively udp://
    String uri = "magnet:?xt=urn:btih:" + HEX_HASH_40
        + "&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337"
        + "&tr=udp%3A%2F%2Fopen.stealth.si%3A80%2Fannounce";
    MagnetUri magnet = MagnetUri.parse(uri);
    assertEquals(2, magnet.getTrackers().size());
    for (URI tracker : magnet.getTrackers()) {
      assertEquals("udp", tracker.getScheme());
      assertNotNull(tracker.getHost());
      assertTrue(tracker.getPort() > 0);
    }
  }

  private int countTrackersWithScheme(MagnetUri magnet, String scheme) {
    int count = 0;
    for (URI tracker : magnet.getTrackers()) {
      if (scheme.equals(tracker.getScheme())) {
        count++;
      }
    }
    return count;
  }

  public void testParseUppercaseHex() throws Exception {
    MagnetUri magnet = MagnetUri.parse("magnet:?xt=urn:btih:" + HEX_HASH_40.toUpperCase());
    assertEquals(new Hash(hexToBytes(HEX_HASH_40)), magnet.getInfoHash());
  }

  public void testParseBase32Hash() throws Exception {
    // base32 of the same 20 bytes as HEX_HASH_40
    byte[] rawHash = hexToBytes(HEX_HASH_40);
    String base32 = bytesToBase32(rawHash);
    assertEquals(32, base32.length());
    MagnetUri magnet = MagnetUri.parse("magnet:?xt=urn:btih:" + base32);
    assertEquals(new Hash(rawHash), magnet.getInfoHash());
  }

  public void testMissingXtFails() {
    try {
      MagnetUri.parse("magnet:?dn=NoHashHere");
      fail("expected parse exception");
    } catch (MagnetUri.MagnetUriParseException e) {
      // expected
    }
  }

  public void testNotAMagnetUriFails() {
    try {
      MagnetUri.parse("http://example.com/file.torrent");
      fail("expected parse exception");
    } catch (MagnetUri.MagnetUriParseException e) {
      // expected
    }
  }

  public void testIgnoresUnknownParameters() throws Exception {
    MagnetUri magnet = MagnetUri.parse(
        "magnet:?xt=urn:btih:" + HEX_HASH_40 + "&xl=12345&kt=some+keywords");
    assertEquals(new Hash(hexToBytes(HEX_HASH_40)), magnet.getInfoHash());
  }

  private static byte[] hexToBytes(String hex) {
    byte[] result = new byte[hex.length() / 2];
    for (int i = 0; i < result.length; i++) {
      result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }
    return result;
  }

  private static String bytesToBase32(byte[] data) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    StringBuilder result = new StringBuilder();
    int buffer = 0;
    int bitsLeft = 0;
    for (byte b : data) {
      buffer = (buffer << 8) | (b & 0xFF);
      bitsLeft += 8;
      while (bitsLeft >= 5) {
        bitsLeft -= 5;
        result.append(alphabet.charAt((buffer >> bitsLeft) & 0x1F));
      }
    }
    if (bitsLeft > 0) {
      result.append(alphabet.charAt((buffer << (5 - bitsLeft)) & 0x1F));
    }
    return result.toString();
  }
}
