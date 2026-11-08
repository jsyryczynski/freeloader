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

import org.apache.commons.codec.binary.Hex;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses "magnet:?xt=urn:btih:..." links (BEP 9 style, the format most
 * clients produce). Supports the exact-topic info hash (xt), display name
 * (dn) and any number of tracker URLs (tr).
 *
 * Trackers are kept as java.net.URI rather than java.net.URL: most
 * real-world magnet links carry udp:// tracker URLs (BEP 15), and
 * java.net.URL has no built-in handler for "udp" and throws
 * MalformedURLException trying to parse one, which would silently drop
 * every tracker a typical magnet link actually has. URI has no such
 * restriction -- it's a generic syntax parser, not scheme-aware.
 */
public class MagnetUri {

  private static final String SCHEME_PREFIX = "magnet:?";
  private static final String BTIH_PREFIX_LOWER = "urn:btih:";

  private final Hash infoHash;
  private final String displayName;
  private final List<URI> trackers;

  private MagnetUri(Hash infoHash, String displayName, List<URI> trackers) {
    this.infoHash = infoHash;
    this.displayName = displayName;
    this.trackers = trackers;
  }

  public Hash getInfoHash() {
    return infoHash;
  }

  public String getDisplayName() {
    return displayName;
  }

  public List<URI> getTrackers() {
    return trackers;
  }

  public static boolean isMagnetUri(String candidate) {
    return candidate != null && candidate.trim().toLowerCase().startsWith("magnet:?");
  }

  public static MagnetUri parse(String uri) throws MagnetUriParseException {
    if (uri == null) {
      throw new MagnetUriParseException("uri is null");
    }
    String trimmed = uri.trim();
    if (!trimmed.toLowerCase().startsWith(SCHEME_PREFIX)) {
      throw new MagnetUriParseException("not a magnet uri, must start with \"" + SCHEME_PREFIX + "\"");
    }

    String query = trimmed.substring(SCHEME_PREFIX.length());
    Hash infoHash = null;
    String displayName = null;
    List<URI> trackers = new ArrayList<URI>();

    for (String rawParam : query.split("&")) {
      if (rawParam.length() == 0) {
        continue;
      }
      String[] parts = rawParam.split("=", 2);
      if (parts.length != 2) {
        continue;
      }
      String key = parts[0];
      String value = urlDecode(parts[1]);

      if ("xt".equals(key)) {
        Hash parsed = parseExactTopic(value);
        if (parsed != null) {
          infoHash = parsed;
        }
      } else if ("dn".equals(key)) {
        displayName = value;
      } else if ("tr".equals(key)) {
        try {
          trackers.add(new URI(value));
        } catch (URISyntaxException e) {
          // ignore malformed tracker entries, they're optional/best-effort
        }
      }
    }

    if (infoHash == null) {
      throw new MagnetUriParseException("no valid \"xt=urn:btih:...\" parameter found");
    }

    return new MagnetUri(infoHash, displayName, trackers);
  }

  private static Hash parseExactTopic(String xt) {
    String lower = xt.toLowerCase();
    if (!lower.startsWith(BTIH_PREFIX_LOWER)) {
      return null;
    }
    String hashPart = xt.substring(BTIH_PREFIX_LOWER.length());
    return decodeInfoHash(hashPart);
  }

  private static Hash decodeInfoHash(String hashPart) {
    if (hashPart.length() == 40) {
      // 40 hex characters
      try {
        return new Hash(Hex.decodeHex(hashPart.toCharArray()));
      } catch (Exception e) {
        return null;
      }
    } else if (hashPart.length() == 32) {
      // base32 encoded, 32 chars -> 20 bytes. commons-codec 1.3 (the version
      // this project vendors) predates Base32 support (added in 1.4), so
      // it's decoded by hand here instead of pulling in a newer dependency
      // for one small piece of functionality.
      byte[] decoded = decodeBase32(hashPart.toUpperCase());
      if (decoded == null || decoded.length != Hash.LENGTH) {
        return null;
      }
      return new Hash(decoded);
    }
    return null;
  }

  private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private static byte[] decodeBase32(String input) {
    byte[] output = new byte[input.length() * 5 / 8];
    int buffer = 0;
    int bitsLeft = 0;
    int outputIndex = 0;
    for (int i = 0; i < input.length(); i++) {
      int value = BASE32_ALPHABET.indexOf(input.charAt(i));
      if (value < 0) {
        return null;
      }
      buffer = (buffer << 5) | value;
      bitsLeft += 5;
      if (bitsLeft >= 8) {
        bitsLeft -= 8;
        if (outputIndex < output.length) {
          output[outputIndex++] = (byte) ((buffer >> bitsLeft) & 0xFF);
        }
      }
    }
    return output;
  }

  private static String urlDecode(String value) {
    try {
      return URLDecoder.decode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      // UTF-8 is always supported
      throw new RuntimeException(e);
    }
  }

  public static class MagnetUriParseException extends Exception {
    public MagnetUriParseException(String message) {
      super(message);
    }
  }
}
