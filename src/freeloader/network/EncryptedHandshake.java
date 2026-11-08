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

import freeloader.torrent.Hash;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;

/**
 * BitTorrent connection encryption ("Message Stream Encryption", as
 * implemented by uTorrent, qBittorrent/libtorrent, Transmission, ...).
 * Many peers refuse unencrypted connections, closing them right after
 * the plain BitTorrent handshake arrives.
 *
 * Both sides first agree on a shared secret (Diffie-Hellman), prove they
 * want the same torrent without revealing which one, then agree whether
 * the rest of the connection is RC4-encrypted or plain. After that the
 * usual BitTorrent protocol follows, through the ciphers in the Result.
 *
 * Works on blocking streams: run it on its own thread, on a socket with
 * a read timeout, before handing the connection to the non-blocking
 * network code.
 */
public class EncryptedHandshake {

  private static final BigInteger P = new BigInteger(
      "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
          + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
          + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563", 16);
  private static final BigInteger G = BigInteger.valueOf(2);
  private static final int KEY_LENGTH = 96;
  private static final int PRIVATE_KEY_BITS = 160;
  private static final int MAX_PADDING = 512;
  private static final int KEYSTREAM_DISCARD = 1024;
  private static final byte[] VC = new byte[8];

  static final int CRYPTO_PLAINTEXT = 0x01;
  static final int CRYPTO_RC4 = 0x02;

  /** The first bytes of an unencrypted BitTorrent handshake. */
  private static final byte[] PLAIN_HANDSHAKE_START =
      ("\u0013BitTorrent protocol").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  public static final int DETECTION_LENGTH = PLAIN_HANDSHAKE_START.length;

  public static class Result {
    /** Decrypts what the peer sends; null if the connection stays plain. */
    public final Rc4 incoming;
    /** Encrypts what we send; null if the connection stays plain. */
    public final Rc4 outgoing;
    /** Peer data already read (and decrypted), to be handled first. */
    public final byte[] initialData;

    Result(Rc4 incoming, Rc4 outgoing, byte[] initialData) {
      this.incoming = incoming;
      this.outgoing = outgoing;
      this.initialData = initialData;
    }

    public boolean isEncrypted() {
      return outgoing != null;
    }

    /** An unencrypted connection, with whatever was already read from it. */
    public static Result plain(byte[] initialData) {
      return new Result(null, null, initialData);
    }

    /** For blocking use: the peer's stream, decrypted (and after initialData). */
    public InputStream decrypting(InputStream in) {
      InputStream rest = incoming == null ? in : new FilterInputStream(in) {
        public int read() throws IOException {
          byte[] one = new byte[1];
          return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        public int read(byte[] b, int off, int len) throws IOException {
          int read = super.read(b, off, len);
          if (read > 0) {
            incoming.process(b, off, read);
          }
          return read;
        }

        public long skip(long n) throws IOException {
          return read(new byte[(int) Math.min(n, 8192)]);
        }
      };
      return new SequenceInputStream(new ByteArrayInputStream(initialData), rest);
    }

    /** For blocking use: our stream to the peer, encrypted. */
    public OutputStream encrypting(OutputStream out) {
      if (outgoing == null) {
        return out;
      }
      return new FilterOutputStream(out) {
        public void write(int b) throws IOException {
          write(new byte[] {(byte) b}, 0, 1);
        }

        public void write(byte[] b, int off, int len) throws IOException {
          byte[] copy = Arrays.copyOfRange(b, off, off + len);
          outgoing.process(copy);
          out.write(copy);
        }
      };
    }
  }

  /** Thrown when the peer doesn't go along with the encryption handshake. */
  public static class FailedException extends IOException {
    public FailedException(IOException cause) {
      super("encryption handshake failed: " + cause.getMessage(), cause);
    }
  }

  private final SecureRandom random;
  private final BigInteger privateKey;

  public EncryptedHandshake() {
    this(new SecureRandom());
  }

  EncryptedHandshake(SecureRandom random) {
    this.random = random;
    this.privateKey = new BigInteger(PRIVATE_KEY_BITS, random);
  }

  /** Whether an incoming connection starts with an unencrypted handshake. */
  public static boolean isPlainHandshake(byte[] firstBytes) {
    return Arrays.equals(firstBytes, PLAIN_HANDSHAKE_START);
  }

  /** Our side of an outgoing connection, for the given torrent. */
  public Result initiate(InputStream in, OutputStream out, Hash infoHash) throws IOException {
    out.write(publicKey());
    out.write(padding());
    out.flush();

    byte[] secret = sharedSecret(readFully(in, KEY_LENGTH));
    byte[] skey = infoHash.getBytes();
    Rc4 outgoing = cipher("keyA", secret, skey);
    Rc4 incoming = cipher("keyB", secret, skey);

    out.write(hash(bytes("req1"), secret));
    out.write(xor(hash(bytes("req2"), skey), hash(bytes("req3"), secret)));
    byte[] padC = new byte[0];
    byte[] request = concat(VC, int32(CRYPTO_PLAINTEXT | CRYPTO_RC4), int16(padC.length), padC, int16(0));
    outgoing.process(request);
    out.write(request);
    out.flush();

    // the peer's random padding comes first; find where it ends by
    // looking for the verification constant, encrypted
    byte[] encryptedVC = VC.clone();
    incoming.process(encryptedVC);
    syncOn(in, encryptedVC, MAX_PADDING + encryptedVC.length);

    byte[] answer = readFully(in, 4 + 2);
    incoming.process(answer);
    int selected = readInt32(answer, 0);
    int padDLength = readInt16(answer, 4);
    if (padDLength > MAX_PADDING) {
      throw new IOException("peer sent too much padding: " + padDLength);
    }
    incoming.process(readFully(in, padDLength));

    if (selected == CRYPTO_RC4) {
      return new Result(incoming, outgoing, new byte[0]);
    } else if (selected == CRYPTO_PLAINTEXT) {
      return Result.plain(new byte[0]);
    }
    throw new IOException("peer selected an unknown encryption method: " + selected);
  }

  /**
   * Our side of an incoming connection, whose first DETECTION_LENGTH
   * bytes have already been read (and turned out not to be a plain
   * handshake). Only accepted for one of the given (running) torrents.
   */
  public Result respond(InputStream in, OutputStream out, byte[] alreadyRead, Collection<Hash> infoHashes)
      throws IOException {
    byte[] peerKey = concat(alreadyRead, readFully(in, KEY_LENGTH - alreadyRead.length));
    out.write(publicKey());
    out.write(padding());
    out.flush();

    byte[] secret = sharedSecret(peerKey);
    syncOn(in, hash(bytes("req1"), secret), MAX_PADDING + 20);

    byte[] torrentProof = xor(readFully(in, 20), hash(bytes("req3"), secret));
    Hash infoHash = null;
    for (Hash candidate : infoHashes) {
      if (Arrays.equals(torrentProof, hash(bytes("req2"), candidate.getBytes()))) {
        infoHash = candidate;
        break;
      }
    }
    if (infoHash == null) {
      throw new IOException("peer wants a torrent we're not running");
    }
    byte[] skey = infoHash.getBytes();
    Rc4 incoming = cipher("keyA", secret, skey);
    Rc4 outgoing = cipher("keyB", secret, skey);

    byte[] request = readFully(in, 8 + 4 + 2);
    incoming.process(request);
    if (!Arrays.equals(Arrays.copyOf(request, 8), VC)) {
      throw new IOException("bad verification constant");
    }
    int provided = readInt32(request, 8);
    int padCLength = readInt16(request, 12);
    if (padCLength > MAX_PADDING) {
      throw new IOException("peer sent too much padding: " + padCLength);
    }
    incoming.process(readFully(in, padCLength));
    byte[] initialPayloadLength = readFully(in, 2);
    incoming.process(initialPayloadLength);
    byte[] initialPayload = readFully(in, readInt16(initialPayloadLength, 0));
    incoming.process(initialPayload);

    int selected;
    if ((provided & CRYPTO_RC4) != 0) {
      selected = CRYPTO_RC4;
    } else if ((provided & CRYPTO_PLAINTEXT) != 0) {
      selected = CRYPTO_PLAINTEXT;
    } else {
      throw new IOException("no encryption method in common: " + provided);
    }
    byte[] answer = concat(VC, int32(selected), int16(0));
    outgoing.process(answer);
    out.write(answer);
    out.flush();

    if (selected == CRYPTO_RC4) {
      return new Result(incoming, outgoing, initialPayload);
    }
    // the initial payload is always encrypted, even if what follows isn't
    return Result.plain(initialPayload);
  }

  private byte[] publicKey() {
    return toFixedLength(G.modPow(privateKey, P));
  }

  private byte[] sharedSecret(byte[] peerPublicKey) throws IOException {
    BigInteger y = new BigInteger(1, peerPublicKey);
    if (y.compareTo(BigInteger.ONE) <= 0 || y.compareTo(P.subtract(BigInteger.ONE)) >= 0) {
      throw new IOException("invalid public key from peer");
    }
    return toFixedLength(y.modPow(privateKey, P));
  }

  private byte[] padding() {
    byte[] pad = new byte[random.nextInt(MAX_PADDING + 1)];
    random.nextBytes(pad);
    return pad;
  }

  private static Rc4 cipher(String name, byte[] secret, byte[] skey) {
    Rc4 rc4 = new Rc4(hash(bytes(name), secret, skey));
    rc4.skip(KEYSTREAM_DISCARD);
    return rc4;
  }

  /** Reads until the given marker has been read, within at most limit bytes. */
  private static void syncOn(InputStream in, byte[] marker, int limit) throws IOException {
    byte[] window = readFully(in, marker.length);
    int read = marker.length;
    while (!Arrays.equals(window, marker)) {
      if (read >= limit) {
        throw new IOException("could not find the start of the encrypted stream");
      }
      System.arraycopy(window, 1, window, 0, window.length - 1);
      window[window.length - 1] = readFully(in, 1)[0];
      read++;
    }
  }

  private static byte[] readFully(InputStream in, int length) throws IOException {
    byte[] result = new byte[length];
    int offset = 0;
    while (offset < length) {
      int read = in.read(result, offset, length - offset);
      if (read < 0) {
        throw new EOFException("connection closed during the encryption handshake");
      }
      offset += read;
    }
    return result;
  }

  private static byte[] toFixedLength(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] result = new byte[KEY_LENGTH];
    int length = Math.min(raw.length, KEY_LENGTH);
    System.arraycopy(raw, raw.length - length, result, KEY_LENGTH - length, length);
    return result;
  }

  private static byte[] hash(byte[]... parts) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      for (byte[] part : parts) {
        sha1.update(part);
      }
      return sha1.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static byte[] bytes(String s) {
    return s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
  }

  private static byte[] xor(byte[] a, byte[] b) {
    byte[] result = new byte[a.length];
    for (int i = 0; i < a.length; i++) {
      result[i] = (byte) (a[i] ^ b[i]);
    }
    return result;
  }

  private static byte[] concat(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, result, offset, part.length);
      offset += part.length;
    }
    return result;
  }

  private static byte[] int32(int value) {
    return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
  }

  private static byte[] int16(int value) {
    return new byte[] {(byte) (value >>> 8), (byte) value};
  }

  private static int readInt32(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16)
        | ((data[offset + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
  }

  private static int readInt16(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
  }
}
