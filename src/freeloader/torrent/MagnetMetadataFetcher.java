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

import org.apache.log4j.Logger;
import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.BInteger;
import freeloader.bencoding.BString;
import freeloader.bencoding.ParseException;
import freeloader.network.EncryptedHandshake;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Fetches torrent metadata (the bencoded "info" dictionary) from a single
 * peer over a direct TCP connection, using the BitTorrent extension
 * protocol (BEP 10) and its metadata exchange extension, ut_metadata
 * (BEP 9). This is what makes magnet links (which only carry an info hash,
 * not the full torrent metadata) usable: a peer that already has the full
 * metadata is asked to hand over the "info" dictionary piece by piece, and
 * the result is verified against the requested info hash before being
 * trusted (since the peer is untrusted and could otherwise hand back
 * anything).
 *
 * Deliberately implemented as a small, self-contained, blocking-socket
 * client rather than being wired into the existing NIO-based
 * PacketFactory/Packetizer/BitTorrentConnection machinery: those classes
 * all assume a MetaInfo (and therefore a TorrentDownload) already exists,
 * which is exactly the chicken-and-egg problem magnet links create. Once
 * this class produces a verified MetaInfo, everything downstream (peer
 * connections, piece downloading, etc.) goes through the existing,
 * unmodified code path.
 */
public class MagnetMetadataFetcher {

  private static final Logger logger = Logger.getLogger(MagnetMetadataFetcher.class);

  private static final String PROTOCOL = "BitTorrent protocol";
  private static final int RESERVED_LENGTH = 8;
  private static final int EXTENSION_PROTOCOL_RESERVED_BYTE = 5;
  private static final int EXTENSION_PROTOCOL_BIT = 0x10;

  private static final int EXTENDED_MESSAGE_ID = 20;
  private static final int EXTENDED_HANDSHAKE_ID = 0;
  private static final String UT_METADATA_NAME = "ut_metadata";
  private static final int OUR_UT_METADATA_ID = 1;

  private static final int METADATA_PIECE_SIZE = 16 * 1024;
  private static final int MAX_METADATA_SIZE = 16 * 1024 * 1024; // sanity cap, 16MB

  public static final int DEFAULT_CONNECT_TIMEOUT_MS = 8000;
  public static final int DEFAULT_READ_TIMEOUT_MS = 15000;

  private final int connectTimeoutMs;
  private final int readTimeoutMs;

  public MagnetMetadataFetcher() {
    this(DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
  }

  public MagnetMetadataFetcher(int connectTimeoutMs, int readTimeoutMs) {
    this.connectTimeoutMs = connectTimeoutMs;
    this.readTimeoutMs = readTimeoutMs;
  }

  // A real swarm's peer list is mostly peers that are gone, firewalled or
  // too busy to answer, so they're tried many at a time: one by one, the
  // 8s connect timeouts alone add up to hours for a few hundred peers.
  public static final int PARALLEL_PEERS = 30;
  // the read timeout restarts with every message (and keep-alives count),
  // so a slow or stalling peer also gets a hard cap on its whole exchange
  public static final long PER_PEER_DEADLINE_MS = 30000;
  public static final long OVERALL_TIMEOUT_MS = 5 * 60 * 1000;

  public interface ProgressListener {
    /** Called each time another peer has been tried (successfully or not). */
    void onProgress(int peersTried, int peersTotal);
  }

  public byte[] fetchFromAnyPeer(Hash infoHash, List<InetSocketAddress> candidates, PeerId ourPeerId) {
    return fetchFromAnyPeer(infoHash, candidates, ourPeerId, null);
  }

  /**
   * Asks the candidate peers, up to PARALLEL_PEERS at a time, until one
   * hands over a complete, hash-verified metadata blob; the remaining
   * attempts are abandoned at that point. Returns null if none work
   * within OVERALL_TIMEOUT_MS.
   */
  public byte[] fetchFromAnyPeer(final Hash infoHash, List<InetSocketAddress> candidates,
                                 final PeerId ourPeerId, ProgressListener progress) {
    if (candidates.isEmpty()) {
      return null;
    }
    ExecutorService pool = Executors.newFixedThreadPool(
        Math.min(PARALLEL_PEERS, candidates.size()), daemonThreads("metadata-fetch"));
    ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(daemonThreads("metadata-watchdog"));
    // blocking socket I/O ignores thread interrupts, so attempts still in
    // flight when we're done are stopped by closing their sockets
    final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    try {
      CompletionService<byte[]> attempts = new ExecutorCompletionService<byte[]>(pool);
      for (final InetSocketAddress address : candidates) {
        attempts.submit(() -> fetchWithDeadline(infoHash, address, ourPeerId, watchdog, openSockets));
      }

      long deadline = System.currentTimeMillis() + OVERALL_TIMEOUT_MS;
      for (int tried = 1; tried <= candidates.size(); tried++) {
        long remaining = deadline - System.currentTimeMillis();
        Future<byte[]> attempt = remaining > 0 ? attempts.poll(remaining, TimeUnit.MILLISECONDS) : null;
        if (attempt == null) {
          logger.info("gave up fetching metadata after " + OVERALL_TIMEOUT_MS + "ms");
          return null;
        }
        if (progress != null) {
          progress.onProgress(tried, candidates.size());
        }
        try {
          byte[] metadata = attempt.get();
          if (metadata != null) {
            return metadata;
          }
        } catch (ExecutionException e) {
          logger.debug("metadata fetch attempt failed: " + e.getCause().getMessage());
        }
      }
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    } finally {
      pool.shutdownNow();
      watchdog.shutdownNow();
      for (Socket socket : openSockets) {
        closeQuietly(socket);
      }
    }
  }

  private byte[] fetchWithDeadline(Hash infoHash, InetSocketAddress address, PeerId ourPeerId,
                                   ScheduledExecutorService watchdog, Set<Socket> openSockets) throws IOException {
    try {
      return fetchWithDeadline(infoHash, address, ourPeerId, watchdog, openSockets, true);
    } catch (EncryptedHandshake.FailedException e) {
      return fetchWithDeadline(infoHash, address, ourPeerId, watchdog, openSockets, false);
    }
  }

  private byte[] fetchWithDeadline(Hash infoHash, InetSocketAddress address, PeerId ourPeerId,
                                   ScheduledExecutorService watchdog, Set<Socket> openSockets, boolean encrypted)
      throws IOException {
    final Socket socket = new Socket();
    openSockets.add(socket);
    ScheduledFuture<?> timeout = watchdog.schedule(() -> closeQuietly(socket), PER_PEER_DEADLINE_MS, TimeUnit.MILLISECONDS);
    try {
      return fetchOverConnection(infoHash, address, ourPeerId, socket, encrypted);
    } catch (EncryptedHandshake.FailedException e) {
      throw e;
    } catch (IOException e) {
      throw new IOException(address + ": " + e.getMessage(), e);
    } finally {
      timeout.cancel(false);
      openSockets.remove(socket);
      closeQuietly(socket);
    }
  }

  public byte[] fetchFromPeer(Hash infoHash, InetSocketAddress address, PeerId ourPeerId) throws IOException {
    try {
      return fetchFromPeer(infoHash, address, ourPeerId, true);
    } catch (EncryptedHandshake.FailedException e) {
      return fetchFromPeer(infoHash, address, ourPeerId, false);
    }
  }

  private byte[] fetchFromPeer(Hash infoHash, InetSocketAddress address, PeerId ourPeerId, boolean encrypted)
      throws IOException {
    Socket socket = new Socket();
    try {
      return fetchOverConnection(infoHash, address, ourPeerId, socket, encrypted);
    } finally {
      closeQuietly(socket);
    }
  }

  /**
   * Many peers only accept encrypted connections (see EncryptedHandshake),
   * so that's tried first; a FailedException means the peer didn't go
   * along with it, and asks for a new, unencrypted attempt.
   */
  private byte[] fetchOverConnection(Hash infoHash, InetSocketAddress address, PeerId ourPeerId, Socket socket,
                                     boolean encrypted) throws IOException {
    socket.connect(address, connectTimeoutMs);
    socket.setSoTimeout(readTimeoutMs);
    InputStream in = socket.getInputStream();
    OutputStream out = socket.getOutputStream();
    if (encrypted) {
      EncryptedHandshake.Result result;
      try {
        result = new EncryptedHandshake().initiate(in, out, infoHash);
      } catch (IOException e) {
        throw new EncryptedHandshake.FailedException(e);
      }
      in = result.decrypting(in);
      out = result.encrypting(out);
    }
    return fetchOverSocket(infoHash, ourPeerId, in, out);
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // ignore
    }
  }

  private static ThreadFactory daemonThreads(final String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Runs the full exchange over already-open streams. Split out from
   * fetchFromPeer so it can be exercised directly against an in-process
   * fake peer in tests, without needing real network access.
   */
  public byte[] fetchOverSocket(Hash infoHash, PeerId ourPeerId, InputStream in, OutputStream out) throws IOException {
    writeHandshake(out, infoHash, ourPeerId);
    RemoteHandshake remote = readHandshake(in);
    if (!remote.infoHash.equals(infoHash)) {
      throw new IOException("peer handshake had a different info hash");
    }
    if (!remote.supportsExtensionProtocol) {
      throw new IOException("peer does not support the extension protocol (BEP 10)");
    }

    writeExtendedHandshake(out);

    Integer peerUtMetadataId = null;
    Integer metadataSize = null;
    // keep reading messages (skipping anything not relevant to us) until we
    // get the peer's extended handshake reply
    while (peerUtMetadataId == null) {
      Message message = readMessage(in);
      if (message == null) {
        continue; // keep-alive
      }
      if (message.id == EXTENDED_MESSAGE_ID && message.payload.length > 0
          && (message.payload[0] & 0xFF) == EXTENDED_HANDSHAKE_ID) {
        ExtendedHandshake handshake = parseExtendedHandshake(message.payload);
        peerUtMetadataId = handshake.utMetadataId;
        metadataSize = handshake.metadataSize;
      }
    }

    if (peerUtMetadataId == null) {
      throw new IOException("peer's extended handshake did not advertise ut_metadata support");
    }
    if (metadataSize == null || metadataSize <= 0 || metadataSize > MAX_METADATA_SIZE) {
      throw new IOException("peer did not advertise a usable metadata_size");
    }

    int pieceCount = (metadataSize + METADATA_PIECE_SIZE - 1) / METADATA_PIECE_SIZE;
    byte[] assembled = new byte[metadataSize];

    for (int piece = 0; piece < pieceCount; piece++) {
      writeMetadataRequest(out, peerUtMetadataId, piece);
      byte[] pieceData = readMetadataPieceResponse(in, piece);
      int offset = piece * METADATA_PIECE_SIZE;
      int length = Math.min(METADATA_PIECE_SIZE, metadataSize - offset);
      if (pieceData.length < length) {
        throw new IOException("metadata piece " + piece + " shorter than expected");
      }
      System.arraycopy(pieceData, 0, assembled, offset, length);
    }

    if (!infoHash.equalsHashOf(assembled, 0, assembled.length)) {
      throw new IOException("assembled metadata does not match the requested info hash "
          + "(peer sent bad/mismatched data)");
    }

    return assembled;
  }

  // ---- handshake ----

  static void writeHandshake(OutputStream out, Hash infoHash, PeerId peerId) throws IOException {
    byte[] protocolBytes = PROTOCOL.getBytes("US-ASCII");
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    buffer.write(protocolBytes.length);
    buffer.write(protocolBytes);
    byte[] reserved = new byte[RESERVED_LENGTH];
    reserved[EXTENSION_PROTOCOL_RESERVED_BYTE] |= EXTENSION_PROTOCOL_BIT;
    buffer.write(reserved);
    buffer.write(infoHash.getBytes());
    buffer.write(peerId.getBytes());
    out.write(buffer.toByteArray());
    out.flush();
  }

  static RemoteHandshake readHandshake(InputStream in) throws IOException {
    int protocolLength = readByteOrFail(in);
    byte[] protocolBytes = readFully(in, protocolLength);
    byte[] reserved = readFully(in, RESERVED_LENGTH);
    byte[] infoHashBytes = readFully(in, Hash.LENGTH);
    byte[] peerIdBytes = readFully(in, PeerId.LENGTH);

    boolean supportsExtension =
        (reserved[EXTENSION_PROTOCOL_RESERVED_BYTE] & EXTENSION_PROTOCOL_BIT) != 0;

    RemoteHandshake result = new RemoteHandshake();
    result.protocol = new String(protocolBytes, "US-ASCII");
    result.infoHash = new Hash(infoHashBytes);
    result.peerId = new PeerId(peerIdBytes);
    result.supportsExtensionProtocol = supportsExtension;
    return result;
  }

  static class RemoteHandshake {
    String protocol;
    Hash infoHash;
    PeerId peerId;
    boolean supportsExtensionProtocol;
  }

  // ---- extended handshake ----

  static void writeExtendedHandshake(OutputStream out) throws IOException {
    BDictionary<BEntity> supported = new BDictionary<BEntity>();
    supported.put(new BString(UT_METADATA_NAME), new BInteger(OUR_UT_METADATA_ID));

    BDictionary<BEntity> handshake = new BDictionary<BEntity>();
    handshake.put(new BString("m"), supported);

    byte[] bencoded = handshake.encode();
    writeExtendedMessage(out, EXTENDED_HANDSHAKE_ID, bencoded);
  }

  static ExtendedHandshake parseExtendedHandshake(byte[] payload) throws IOException {
    // payload[0] is the extended-message-id (0 for handshake), the rest is
    // the bencoded dictionary
    byte[] bencoded = new byte[payload.length - 1];
    System.arraycopy(payload, 1, bencoded, 0, bencoded.length);

    BEntity entity;
    try {
      entity = new BDecoder().decode(bencoded);
    } catch (ParseException e) {
      throw new IOException("could not parse extended handshake", e);
    }
    if (!(entity instanceof BDictionary)) {
      throw new IOException("extended handshake is not a dictionary");
    }
    BDictionary dictionary = (BDictionary) entity;

    ExtendedHandshake result = new ExtendedHandshake();
    BEntity mEntity = dictionary.getByString("m");
    if (mEntity instanceof BDictionary) {
      BEntity utMetadataEntity = ((BDictionary) mEntity).getByString(UT_METADATA_NAME);
      if (utMetadataEntity instanceof BInteger) {
        result.utMetadataId = ((BInteger) utMetadataEntity).intValue();
      }
    }
    BEntity metadataSizeEntity = dictionary.getByString("metadata_size");
    if (metadataSizeEntity instanceof BInteger) {
      result.metadataSize = ((BInteger) metadataSizeEntity).intValue();
    }
    return result;
  }

  static class ExtendedHandshake {
    Integer utMetadataId;
    Integer metadataSize;
  }

  // ---- ut_metadata piece request/response (BEP 9) ----

  static void writeMetadataRequest(OutputStream out, int peerUtMetadataId, int piece) throws IOException {
    BDictionary<BEntity> request = new BDictionary<BEntity>();
    request.put(new BString("msg_type"), new BInteger(0)); // request
    request.put(new BString("piece"), new BInteger(piece));
    writeExtendedMessage(out, peerUtMetadataId, request.encode());
  }

  /**
   * Reads messages until it sees the data (or reject) response for the
   * requested piece, ignoring anything else the peer sends in the
   * meantime (bitfield/have/choke/etc. are all valid and expected from a
   * real peer, we just don't care about them here).
   */
  static byte[] readMetadataPieceResponse(InputStream in, int expectedPiece) throws IOException {
    while (true) {
      Message message = readMessage(in);
      if (message == null) {
        continue; // keep-alive
      }
      if (message.id != EXTENDED_MESSAGE_ID || message.payload.length == 0) {
        continue;
      }
      int extendedId = message.payload[0] & 0xFF;
      if (extendedId != OUR_UT_METADATA_ID) {
        continue;
      }

      byte[] rest = new byte[message.payload.length - 1];
      System.arraycopy(message.payload, 1, rest, 0, rest.length);

      ByteArrayInputStream byteIn = new ByteArrayInputStream(rest);
      BEntity entity;
      try {
        entity = new BDecoder().decode(byteIn);
      } catch (ParseException e) {
        throw new IOException("could not parse ut_metadata message", e);
      }
      if (!(entity instanceof BDictionary)) {
        throw new IOException("ut_metadata message is not a dictionary");
      }
      BDictionary dictionary = (BDictionary) entity;

      BEntity msgTypeEntity = dictionary.getByString("msg_type");
      BEntity pieceEntity = dictionary.getByString("piece");
      if (!(msgTypeEntity instanceof BInteger) || !(pieceEntity instanceof BInteger)) {
        continue;
      }
      int msgType = ((BInteger) msgTypeEntity).intValue();
      int piece = ((BInteger) pieceEntity).intValue();
      if (piece != expectedPiece) {
        continue;
      }
      if (msgType == 2) {
        throw new IOException("peer rejected metadata request for piece " + piece);
      }
      if (msgType != 1) {
        continue;
      }

      int consumed = rest.length - byteIn.available();
      byte[] pieceBytes = new byte[rest.length - consumed];
      System.arraycopy(rest, consumed, pieceBytes, 0, pieceBytes.length);
      return pieceBytes;
    }
  }

  // ---- low level message framing ----

  private static void writeExtendedMessage(OutputStream out, int extendedMessageId, byte[] bencodedPayload)
      throws IOException {
    int payloadLength = 1 + 1 + bencodedPayload.length; // message id + extended id + payload
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    writeInt32(buffer, payloadLength);
    buffer.write(EXTENDED_MESSAGE_ID);
    buffer.write(extendedMessageId);
    buffer.write(bencodedPayload);
    out.write(buffer.toByteArray());
    out.flush();
  }

  static class Message {
    int id;
    byte[] payload;
  }

  /** Returns null for a keep-alive (zero length) message. */
  static Message readMessage(InputStream in) throws IOException {
    int length = readInt32(in);
    if (length == 0) {
      return null;
    }
    byte[] full = readFully(in, length);
    Message message = new Message();
    message.id = full[0] & 0xFF;
    message.payload = new byte[full.length - 1];
    System.arraycopy(full, 1, message.payload, 0, message.payload.length);
    return message;
  }

  private static void writeInt32(OutputStream out, int value) throws IOException {
    out.write((value >>> 24) & 0xFF);
    out.write((value >>> 16) & 0xFF);
    out.write((value >>> 8) & 0xFF);
    out.write(value & 0xFF);
  }

  private static int readInt32(InputStream in) throws IOException {
    byte[] bytes = readFully(in, 4);
    return ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
        | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
  }

  private static int readByteOrFail(InputStream in) throws IOException {
    int value = in.read();
    if (value < 0) {
      throw new IOException("unexpected end of stream");
    }
    return value;
  }

  private static byte[] readFully(InputStream in, int length) throws IOException {
    byte[] buffer = new byte[length];
    int totalRead = 0;
    while (totalRead < length) {
      int read = in.read(buffer, totalRead, length - totalRead);
      if (read < 0) {
        throw new IOException("unexpected end of stream, wanted " + length + " got " + totalRead);
      }
      totalRead += read;
    }
    return buffer;
  }
}
