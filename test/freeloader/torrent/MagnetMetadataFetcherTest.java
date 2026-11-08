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
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.BInteger;
import freeloader.bencoding.BString;
import freeloader.network.EncryptedHandshake;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;

/**
 * Exercises MagnetMetadataFetcher's full BEP 10 / BEP 9 exchange against an
 * in-process fake peer speaking the real wire protocol over a real loopback
 * TCP socket -- no actual internet access needed, but it's genuinely
 * testing the wire format, framing, and reassembly logic end to end rather
 * than mocking pieces of it away.
 */
public class MagnetMetadataFetcherTest extends TestCase {

  private static final int PEER_UT_METADATA_ID = 3;

  public void testFetchesAndVerifiesSmallSinglePieceMetadata() throws Exception {
    byte[] metadata = buildFakeInfoDictionary(100);
    assertTrue("test is only valid for metadata smaller than one piece",
        metadata.length < 16 * 1024);
    runFetchTest(metadata);
  }

  public void testFetchesAndVerifiesMultiPieceMetadata() throws Exception {
    // force more than one 16KB metadata piece
    byte[] metadata = buildFakeInfoDictionary(20000);
    assertTrue(metadata.length > 16 * 1024);
    runFetchTest(metadata);
  }

  public void testFetchesFromAPeerThatRequiresEncryption() throws Exception {
    byte[] metadata = buildFakeInfoDictionary(20000);
    FakePeer peer = new FakePeer(metadata);
    peer.requireEncryption();
    peer.start();
    try {
      byte[] result = new MagnetMetadataFetcher(2000, 2000)
          .fetchFromPeer(Hash.forByteArray(metadata), peer.getAddress(), PeerId.createRandom());
      assertTrue(Arrays.equals(metadata, result));
    } finally {
      peer.stop();
    }
  }

  public void testRejectsMetadataThatDoesNotMatchInfoHash() throws Exception {
    byte[] realMetadata = buildFakeInfoDictionary(100);
    Hash realHash = Hash.forByteArray(realMetadata);
    byte[] tamperedMetadata = buildFakeInfoDictionary(101); // different content, different hash

    FakePeer peer = new FakePeer(tamperedMetadata);
    peer.start();
    try {
      MagnetMetadataFetcher fetcher = new MagnetMetadataFetcher(2000, 2000);
      try {
        fetcher.fetchFromPeer(realHash, peer.getAddress(), PeerId.createRandom());
        fail("expected an IOException due to hash mismatch");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("does not match"));
      }
    } finally {
      peer.stop();
    }
  }

  public void testFailsCleanlyWhenPeerDoesNotSupportExtensionProtocol() throws Exception {
    byte[] metadata = buildFakeInfoDictionary(50);
    Hash hash = Hash.forByteArray(metadata);
    FakePeer peer = new FakePeer(metadata);
    peer.disableExtensionProtocol();
    peer.start();
    try {
      MagnetMetadataFetcher fetcher = new MagnetMetadataFetcher(2000, 2000);
      try {
        fetcher.fetchFromPeer(hash, peer.getAddress(), PeerId.createRandom());
        fail("expected an IOException");
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("extension protocol"));
      }
    } finally {
      peer.stop();
    }
  }

  public void testFetchFromAnyPeerDoesNotWaitOnUnresponsivePeersFirst() throws Exception {
    byte[] metadata = buildFakeInfoDictionary(100);
    Hash infoHash = Hash.forByteArray(metadata);
    // accept connections but never say anything, like a stalled real peer
    ServerSocket silent1 = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
    ServerSocket silent2 = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
    FakePeer good = new FakePeer(metadata);
    good.start();
    try {
      MagnetMetadataFetcher fetcher = new MagnetMetadataFetcher(2000, 2000);
      long start = System.currentTimeMillis();
      byte[] result = fetcher.fetchFromAnyPeer(infoHash, Arrays.asList(
          new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), silent1.getLocalPort()),
          new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), silent2.getLocalPort()),
          good.getAddress()), PeerId.createRandom());
      long elapsed = System.currentTimeMillis() - start;

      assertNotNull(result);
      assertTrue(Arrays.equals(metadata, result));
      // trying peers one by one would have sat through both silent peers'
      // 2s read timeouts before ever reaching the good one
      assertTrue("took " + elapsed + "ms", elapsed < 2000);
    } finally {
      good.stop();
      silent1.close();
      silent2.close();
    }
  }

  public void testFetchFromAnyPeerReportsProgressAndGivesUpWhenAllFail() throws Exception {
    byte[] metadata = buildFakeInfoDictionary(100);
    Hash infoHash = Hash.forByteArray(metadata);
    FakePeer noExtension = new FakePeer(metadata);
    noExtension.disableExtensionProtocol();
    noExtension.start();
    // nothing listens here once the socket is closed: connection refused
    ServerSocket closed = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
    InetSocketAddress refused = new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), closed.getLocalPort());
    closed.close();
    try {
      final int[] lastProgress = new int[2];
      byte[] result = new MagnetMetadataFetcher(2000, 2000).fetchFromAnyPeer(
          infoHash, Arrays.asList(noExtension.getAddress(), refused), PeerId.createRandom(),
          (tried, total) -> {
            lastProgress[0] = tried;
            lastProgress[1] = total;
          });
      assertNull(result);
      assertEquals(2, lastProgress[0]);
      assertEquals(2, lastProgress[1]);
    } finally {
      noExtension.stop();
    }
  }

  private void runFetchTest(byte[] metadata) throws Exception {
    Hash infoHash = Hash.forByteArray(metadata);
    MagnetMetadataFetcher fetcher = new MagnetMetadataFetcher(2000, 2000);

    // each FakePeer only accepts a single connection (like a real one-shot
    // TCP connection would be per attempt), so use a fresh instance per
    // fetch call rather than reusing one across two separate connections
    FakePeer peer1 = new FakePeer(metadata);
    peer1.start();
    try {
      byte[] result = fetcher.fetchFromPeer(infoHash, peer1.getAddress(), PeerId.createRandom());
      assertNotNull(result);
      assertTrue(Arrays.equals(metadata, result));
    } finally {
      peer1.stop();
    }

    // also exercise fetchFromAnyPeer's candidate-list handling, against a
    // second fresh peer instance
    FakePeer peer2 = new FakePeer(metadata);
    peer2.start();
    try {
      byte[] resultViaAnyPeer = fetcher.fetchFromAnyPeer(
          infoHash, Collections.singletonList(peer2.getAddress()), PeerId.createRandom());
      assertNotNull(resultViaAnyPeer);
      assertTrue(Arrays.equals(metadata, resultViaAnyPeer));
    } finally {
      peer2.stop();
    }
  }

  private byte[] buildFakeInfoDictionary(int paddingBytes) throws IOException {
    BDictionary<BEntity> info = new BDictionary<BEntity>();
    info.put(new BString("name"), new BString("fake-test-torrent"));
    info.put(new BString("piece length"), new BInteger(16384));
    // one fake piece hash (20 bytes of padding value, repeated up to Hash.LENGTH)
    byte[] fakePieceHash = new byte[Hash.LENGTH];
    Arrays.fill(fakePieceHash, (byte) 7);
    info.put(new BString("pieces"), new BString(fakePieceHash));
    info.put(new BString("length"), new BInteger(paddingBytes));
    // pad with a filler field so we can control the exact encoded size
    byte[] padding = new byte[Math.max(0, paddingBytes)];
    Arrays.fill(padding, (byte) 'x');
    info.put(new BString("padding"), new BString(padding));
    return info.encode();
  }

  /**
   * A minimal but wire-protocol-correct fake BitTorrent peer that knows how
   * to do the BEP 10 extension handshake and serve ut_metadata pieces for
   * one fixed metadata blob. Runs its own accept loop on a background
   * thread against an ephemeral localhost port.
   */
  private static class FakePeer {
    private final byte[] metadata;
    private final ServerSocket serverSocket;
    private volatile boolean running = true;
    private volatile boolean supportsExtension = true;
    private volatile boolean requiresEncryption = false;
    private Thread thread;

    FakePeer(byte[] metadata) throws IOException {
      this.metadata = metadata;
      this.serverSocket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
    }

    void disableExtensionProtocol() {
      supportsExtension = false;
    }

    /** Like many real peers: hangs up on unencrypted connections. */
    void requireEncryption() {
      requiresEncryption = true;
    }

    InetSocketAddress getAddress() {
      return new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), serverSocket.getLocalPort());
    }

    void start() {
      thread = new Thread(new Runnable() {
        public void run() {
          try {
            // like an older client: hangs up on encrypted connection
            // attempts, so the fetcher comes back unencrypted
            boolean served = false;
            while (!served) {
              Socket socket = serverSocket.accept();
              try {
                served = handleConnection(socket);
              } finally {
                socket.close();
              }
            }
          } catch (IOException e) {
            // connection closed / test finished, that's fine
          }
        }
      }, "fake-bittorrent-peer");
      thread.setDaemon(true);
      thread.start();
    }

    void stop() {
      running = false;
      try {
        serverSocket.close();
      } catch (IOException ignored) {
        // ignore
      }
    }

    private boolean handleConnection(Socket socket) throws IOException {
      socket.setSoTimeout(2000);
      InputStream in = socket.getInputStream();
      OutputStream out = socket.getOutputStream();

      // read client handshake: 1 + 19 + 8 + 20 + 20 bytes
      byte[] start = readFully(in, 1 + 19);
      if (EncryptedHandshake.isPlainHandshake(start)) {
        if (requiresEncryption) {
          return false;
        }
      } else if (requiresEncryption) {
        EncryptedHandshake.Result encryption = new EncryptedHandshake().respond(
            in, out, start, Collections.singletonList(Hash.forByteArray(metadata)));
        assertTrue("should agree on RC4", encryption.isEncrypted());
        in = encryption.decrypting(in);
        out = encryption.encrypting(out);
        start = readFully(in, 1 + 19);
      } else {
        return false;
      }
      byte[] handshake = new byte[1 + 19 + 8 + 20 + 20];
      System.arraycopy(start, 0, handshake, 0, start.length);
      System.arraycopy(readFully(in, handshake.length - start.length), 0, handshake, start.length,
          handshake.length - start.length);
      byte[] reserved = Arrays.copyOfRange(handshake, 20, 28);
      Hash infoHash = new Hash(Arrays.copyOfRange(handshake, 28, 48));
      boolean clientSupportsExtension = (reserved[5] & 0x10) != 0;
      assertTrue("client (Freeloader) should always set the extension protocol bit",
          clientSupportsExtension);

      // write our own handshake back
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      response.write(19);
      response.write("BitTorrent protocol".getBytes("US-ASCII"));
      byte[] ourReserved = new byte[8];
      if (supportsExtension) {
        ourReserved[5] |= 0x10;
      }
      response.write(ourReserved);
      response.write(infoHash.getBytes());
      response.write(PeerId.createRandom().getBytes());
      out.write(response.toByteArray());
      out.flush();

      if (!supportsExtension) {
        // don't speak the extension protocol any further; the client
        // should give up cleanly once it realizes this
        return true;
      }

      // read client's extended handshake message
      Message clientExtHandshake = readMessage(in);
      assertEquals(20, clientExtHandshake.id);
      // client always uses its own fixed ut_metadata id of 1 (see
      // MagnetMetadataFetcher.OUR_UT_METADATA_ID)
      int clientUtMetadataId = 1;

      // reply with our own extended handshake, advertising PEER_UT_METADATA_ID
      // and the real metadata size
      BDictionary<BEntity> m = new BDictionary<BEntity>();
      m.put(new BString("ut_metadata"), new BInteger(PEER_UT_METADATA_ID));
      BDictionary<BEntity> handshakeDict = new BDictionary<BEntity>();
      handshakeDict.put(new BString("m"), m);
      handshakeDict.put(new BString("metadata_size"), new BInteger(metadata.length));
      writeExtendedMessage(out, 0, handshakeDict.encode());

      // serve piece requests until the connection closes
      int pieceSize = 16 * 1024;
      int pieceCount = (metadata.length + pieceSize - 1) / pieceSize;
      int handled = 0;
      while (handled < pieceCount) {
        Message request = readMessage(in);
        if (request == null || request.id != 20) {
          continue;
        }
        int extendedId = request.payload[0] & 0xFF;
        assertEquals(PEER_UT_METADATA_ID, extendedId);

        byte[] rest = Arrays.copyOfRange(request.payload, 1, request.payload.length);
        BEntity entity;
        try {
          entity = new freeloader.bencoding.BDecoder().decode(rest);
        } catch (freeloader.bencoding.ParseException e) {
          throw new IOException("could not parse metadata request", e);
        }
        BDictionary requestDict = (BDictionary) entity;
        int piece = ((BInteger) requestDict.getByString("piece")).intValue();

        BDictionary<BEntity> dataHeader = new BDictionary<BEntity>();
        dataHeader.put(new BString("msg_type"), new BInteger(1));
        dataHeader.put(new BString("piece"), new BInteger(piece));
        dataHeader.put(new BString("total_size"), new BInteger(metadata.length));

        int offset = piece * pieceSize;
        int length = Math.min(pieceSize, metadata.length - offset);
        byte[] pieceBytes = Arrays.copyOfRange(metadata, offset, offset + length);

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(dataHeader.encode());
        payload.write(pieceBytes);

        writeExtendedMessage(out, clientUtMetadataId, payload.toByteArray());
        handled++;
      }
      return true;
    }

    private void writeExtendedMessage(OutputStream out, int extendedId, byte[] bencodedAndMaybeRawBytes)
        throws IOException {
      int payloadLength = 1 + 1 + bencodedAndMaybeRawBytes.length;
      out.write((payloadLength >>> 24) & 0xFF);
      out.write((payloadLength >>> 16) & 0xFF);
      out.write((payloadLength >>> 8) & 0xFF);
      out.write(payloadLength & 0xFF);
      out.write(20);
      out.write(extendedId);
      out.write(bencodedAndMaybeRawBytes);
      out.flush();
    }

    private Message readMessage(InputStream in) throws IOException {
      byte[] lengthBytes = readFully(in, 4);
      int length = ((lengthBytes[0] & 0xFF) << 24) | ((lengthBytes[1] & 0xFF) << 16)
          | ((lengthBytes[2] & 0xFF) << 8) | (lengthBytes[3] & 0xFF);
      if (length == 0) {
        return null;
      }
      byte[] full = readFully(in, length);
      Message message = new Message();
      message.id = full[0] & 0xFF;
      message.payload = Arrays.copyOfRange(full, 1, full.length);
      return message;
    }

    private byte[] readFully(InputStream in, int length) throws IOException {
      byte[] buffer = new byte[length];
      int total = 0;
      while (total < length) {
        int read = in.read(buffer, total, length - total);
        if (read < 0) {
          throw new IOException("unexpected end of stream");
        }
        total += read;
      }
      return buffer;
    }

    private class Message {
      int id;
      byte[] payload;
    }
  }
}
