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
import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class EncryptedHandshakeTest extends TestCase {

  private final Hash infoHash = Hash.fromHexString("0123456789abcdef0123456789abcdef01234567");
  private final Hash otherHash = Hash.fromHexString("fedcba9876543210fedcba9876543210fedcba98");
  private ExecutorService executor;

  protected void setUp() {
    executor = Executors.newFixedThreadPool(2);
  }

  protected void tearDown() {
    executor.shutdownNow();
  }

  public void testRc4MatchesPublishedTestVectors() {
    assertEquals("bbf316e8d940af0ad3", rc4Hex("Key", "Plaintext"));
    assertEquals("1021bf0420", rc4Hex("Wiki", "pedia"));
    assertEquals("45a01f645fc35b383552544b9bf5", rc4Hex("Secret", "Attack at dawn"));
  }

  public void testKeyExchangeUsesThe768BitPrime() throws Exception {
    Field field = EncryptedHandshake.class.getDeclaredField("P");
    field.setAccessible(true);
    BigInteger p = (BigInteger) field.get(null);
    assertEquals(768, p.bitLength());
    assertTrue(p.isProbablePrime(64));
  }

  public void testConnectionSetupCountersAreDeclared() throws Exception {
    // an undeclared counter throws, which would fail every connection
    freeloader.stats.CounterRepository counters =
        freeloader.stats.RealCounterRepository.fromResource("stats.properties");
    for (String name : new String[] {"network.connections.encrypted.in", "network.connections.encrypted.out",
        "network.connections.plain.in", "network.connections.plain.out"}) {
      counters.getCounter(name).increase(1);
    }
  }

  public void testRecognizesAPlainHandshake() {
    byte[] plain = Arrays.copyOf("\u0013BitTorrent protocol".getBytes(StandardCharsets.ISO_8859_1),
        EncryptedHandshake.DETECTION_LENGTH);
    assertTrue(EncryptedHandshake.isPlainHandshake(plain));
    byte[] other = plain.clone();
    other[0] = 42;
    assertFalse(EncryptedHandshake.isPlainHandshake(other));
  }

  public void testBothSidesAgreeOnEncryptedStream() throws Exception {
    Pipe aToB = new Pipe();
    Pipe bToA = new Pipe();
    Future<EncryptedHandshake.Result> initiator = executor.submit(
        () -> new EncryptedHandshake().initiate(bToA.in, aToB.out, infoHash));
    Future<EncryptedHandshake.Result> responder = executor.submit(
        () -> respond(aToB.in, bToA.out, Arrays.asList(otherHash, infoHash)));
    EncryptedHandshake.Result a = get(initiator);
    EncryptedHandshake.Result b = get(responder);

    assertTrue(a.isEncrypted());
    assertTrue(b.isEncrypted());
    assertEquals(0, b.initialData.length);
    // what one side encrypts, the other decrypts, in both directions
    assertRoundTrip(a.outgoing, b.incoming, "hello from the initiator");
    assertRoundTrip(b.outgoing, a.incoming, "hello from the responder");
    assertRoundTrip(a.outgoing, b.incoming, "and the stream carries on");
  }

  public void testResponderRejectsUnknownTorrent() throws Exception {
    Pipe aToB = new Pipe();
    Pipe bToA = new Pipe();
    executor.submit(() -> new EncryptedHandshake().initiate(bToA.in, aToB.out, infoHash));
    Future<EncryptedHandshake.Result> responder = executor.submit(
        () -> respond(aToB.in, bToA.out, Collections.singletonList(otherHash)));
    try {
      get(responder);
      fail("should not accept a torrent we're not running");
    } catch (ExecutionException e) {
      assertTrue(e.getCause() instanceof IOException);
    }
  }

  public void testResponderReadsTheInitialPayloadAndPlaintextChoice() throws Exception {
    // a peer that only offers plaintext after the handshake, and sends
    // its BitTorrent handshake along as the initial payload
    Pipe aToB = new Pipe();
    Pipe bToA = new Pipe();
    byte[] payload = "the initial payload".getBytes(StandardCharsets.ISO_8859_1);
    executor.submit(() -> initiatePlaintextOnly(bToA.in, aToB.out, payload));
    EncryptedHandshake.Result b = get(executor.submit(
        () -> respond(aToB.in, bToA.out, Collections.singletonList(infoHash))));
    assertFalse(b.isEncrypted());
    assertTrue(Arrays.equals(payload, b.initialData));
  }

  private EncryptedHandshake.Result respond(InputStream in, OutputStream out, List<Hash> hashes) throws IOException {
    byte[] start = new byte[EncryptedHandshake.DETECTION_LENGTH];
    int offset = 0;
    while (offset < start.length) {
      offset += in.read(start, offset, start.length - offset);
    }
    return new EncryptedHandshake().respond(in, out, start, hashes);
  }

  /** A hand-rolled initiator offering only plaintext, with an initial payload. */
  private Void initiatePlaintextOnly(InputStream in, OutputStream out, byte[] payload) throws Exception {
    BigInteger p = new BigInteger(1, hex("FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74"
        + "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437"
        + "4FE1356D6D51C245E485B576625E7EC6F44C42E9A63A36210000000000090563"));
    BigInteger x = BigInteger.valueOf(123456789);
    out.write(fixed(BigInteger.TWO.modPow(x, p)));
    out.flush();
    byte[] yb = new byte[96];
    int offset = 0;
    while (offset < 96) {
      offset += in.read(yb, offset, 96 - offset);
    }
    byte[] s = fixed(new BigInteger(1, yb).modPow(x, p));
    out.write(sha1("req1".getBytes(StandardCharsets.ISO_8859_1), s));
    byte[] req2 = sha1("req2".getBytes(StandardCharsets.ISO_8859_1), infoHash.getBytes());
    byte[] req3 = sha1("req3".getBytes(StandardCharsets.ISO_8859_1), s);
    for (int i = 0; i < 20; i++) {
      req2[i] ^= req3[i];
    }
    out.write(req2);
    Rc4 keyA = new Rc4(sha1("keyA".getBytes(StandardCharsets.ISO_8859_1), s, infoHash.getBytes()));
    keyA.skip(1024);
    byte[] request = new byte[8 + 4 + 2 + 2 + payload.length];
    request[11] = EncryptedHandshake.CRYPTO_PLAINTEXT;
    request[15] = (byte) payload.length;
    System.arraycopy(payload, 0, request, 16, payload.length);
    keyA.process(request);
    out.write(request);
    out.flush();
    return null;
  }

  private static void assertRoundTrip(Rc4 encrypt, Rc4 decrypt, String text) {
    byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
    encrypt.process(data);
    assertFalse(text.equals(new String(data, StandardCharsets.ISO_8859_1)));
    decrypt.process(data);
    assertEquals(text, new String(data, StandardCharsets.ISO_8859_1));
  }

  private static <T> T get(Future<T> future) throws Exception {
    return future.get(10, TimeUnit.SECONDS);
  }

  private static String rc4Hex(String key, String text) {
    byte[] data = text.getBytes(StandardCharsets.ISO_8859_1);
    new Rc4(key.getBytes(StandardCharsets.ISO_8859_1)).process(data);
    StringBuilder result = new StringBuilder();
    for (byte b : data) {
      result.append(String.format("%02x", b & 0xff));
    }
    return result.toString();
  }

  private static byte[] fixed(BigInteger value) {
    byte[] raw = value.toByteArray();
    byte[] result = new byte[96];
    int length = Math.min(raw.length, 96);
    System.arraycopy(raw, raw.length - length, result, 96 - length, length);
    return result;
  }

  private static byte[] sha1(byte[]... parts) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
    for (byte[] part : parts) {
      digest.update(part);
    }
    return digest.digest();
  }

  private static byte[] hex(String hex) {
    return new BigInteger(hex, 16).toByteArray();
  }

  private static class Pipe {
    final PipedInputStream in = new PipedInputStream(64 * 1024);
    final PipedOutputStream out;

    Pipe() throws IOException {
      out = new PipedOutputStream(in);
    }
  }
}
