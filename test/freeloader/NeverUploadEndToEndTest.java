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


package freeloader;

import freeloader.dht.DHTracker;
import freeloader.downloader.TorrentDownload;
import freeloader.downloader.TorrentDownloadConfiguration;
import freeloader.torrent.Hash;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.PeerId;
import freeloader.torrent.SingleFileMetaInfo;
import junit.framework.TestCase;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * Freeloader never uploads, checked on a real connection: Freeloader has
 * a complete torrent, a peer connects, says it is interested, and asks
 * for every block again and again. Not one byte of the file comes back,
 * and the peer is never unchoked. No internet is used.
 */
public class NeverUploadEndToEndTest extends TestCase {

  private static final int PIECE_LENGTH = 32768;
  private static final int BLOCK_LENGTH = 16384;
  private static final long LISTEN_MS = 6000;

  private static final int UNCHOKE = 1;
  private static final int INTERESTED = 2;
  private static final int REQUEST = 6;
  private static final int PIECE = 7;

  private File directory;
  private TorrentDownload download;

  protected void tearDown() throws Exception {
    if (download != null && download.isRunning()) {
      download.stop();
    }
    if (directory != null) {
      for (File file : directory.listFiles()) {
        file.delete();
      }
      directory.delete();
    }
  }

  public void testACompleteTorrentSendsNothingToAPeerAskingForEverything() throws Exception {
    byte[] content = new byte[3 * PIECE_LENGTH + 1000];
    new Random(42).nextBytes(content);
    directory = Files.createTempDirectory("freeloader-never-upload").toFile();
    Files.write(new File(directory, "data.bin").toPath(), content);
    MetaInfo metaInfo = metaInfo(content);

    TorrentDownloadConfiguration configuration = new TorrentDownloadConfiguration(metaInfo, directory);
    configuration.setListeningPort(freePort());
    configuration.setTrackedByGui(false);
    download = new TorrentDownload(new NoDhtEnvironment(), configuration);
    download.start();
    assertTrue("the test file was not recognised as complete", waitUntilComplete(download));
    // no tracker here: starting the download must have opened the port

    ByteArrayOutputStream received = new ByteArrayOutputStream();
    List<Integer> messageIds = Collections.synchronizedList(new ArrayList<Integer>());
    try (Socket peer = new Socket("127.0.0.1", configuration.getListeningPort())) {
      peer.setSoTimeout(10000);
      DataOutputStream out = new DataOutputStream(peer.getOutputStream());
      DataInputStream in = new DataInputStream(peer.getInputStream());
      out.write(handshake(metaInfo.getInfoHash()));
      out.flush();
      byte[] theirHandshake = new byte[68];
      in.readFully(theirHandshake);
      received.write(theirHandshake);

      // read everything that arrives, like a real client, until the socket closes
      peer.setSoTimeout(0);
      Thread reader = new Thread(() -> readMessages(in, received, messageIds));
      reader.setDaemon(true);
      reader.start();

      sendMessage(out, INTERESTED, new byte[0]);
      long end = System.currentTimeMillis() + LISTEN_MS;
      while (System.currentTimeMillis() < end) {
        requestEverything(out, content.length);
        Thread.sleep(1000);
      }
      assertEquals("the connection should still be open", 1, download.getAllValidConnections().size());
      peer.close();
      reader.join(5000);
    }

    assertFalse("a piece was sent: " + messageIds, messageIds.contains(PIECE));
    assertFalse("the peer was unchoked: " + messageIds, messageIds.contains(UNCHOKE));
    byte[] bytes = received.toByteArray();
    for (int offset = 0; offset + 32 <= content.length; offset += BLOCK_LENGTH) {
      assertFalse("file data at offset " + offset + " was sent",
          contains(bytes, Arrays.copyOfRange(content, offset, offset + 32)));
    }
  }

  private static boolean waitUntilComplete(TorrentDownload download) throws InterruptedException {
    long end = System.currentTimeMillis() + 10000;
    while (System.currentTimeMillis() < end) {
      if (download.getPieceManager().getValidPieces().hasAll()) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  private static void requestEverything(DataOutputStream out, int length) throws IOException {
    for (int piece = 0; piece * PIECE_LENGTH < length; piece++) {
      int pieceLength = Math.min(PIECE_LENGTH, length - piece * PIECE_LENGTH);
      for (int begin = 0; begin < pieceLength; begin += BLOCK_LENGTH) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream fields = new DataOutputStream(payload);
        fields.writeInt(piece);
        fields.writeInt(begin);
        fields.writeInt(Math.min(BLOCK_LENGTH, pieceLength - begin));
        sendMessage(out, REQUEST, payload.toByteArray());
      }
    }
    out.flush();
  }

  /** Reads length-prefixed messages until the connection closes, noting the id of each. */
  private static void readMessages(DataInputStream in, ByteArrayOutputStream received, List<Integer> ids) {
    try {
      while (true) {
        int length = in.readInt();
        byte[] message = new byte[length];
        in.readFully(message);
        received.write(message);
        if (length > 0) {
          ids.add(message[0] & 0xFF);
        }
      }
    } catch (IOException e) {
      // the connection closed
    }
  }

  private static void sendMessage(DataOutputStream out, int id, byte[] payload) throws IOException {
    out.writeInt(1 + payload.length);
    out.write(id);
    out.write(payload);
  }

  private static byte[] handshake(Hash infoHash) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(19);
    out.write("BitTorrent protocol".getBytes(StandardCharsets.US_ASCII));
    out.write(new byte[8]);
    out.write(infoHash.getBytes());
    out.write(PeerId.createRandom().getBytes());
    return out.toByteArray();
  }

  private static MetaInfo metaInfo(byte[] content) {
    int pieces = (content.length + PIECE_LENGTH - 1) / PIECE_LENGTH;
    Hash[] hashes = new Hash[pieces];
    for (int i = 0; i < pieces; i++) {
      int from = i * PIECE_LENGTH;
      hashes[i] = Hash.forByteArray(Arrays.copyOfRange(content, from, Math.min(content.length, from + PIECE_LENGTH)));
    }
    byte[] infoHash = new byte[20];
    new Random(7).nextBytes(infoHash);
    return new SingleFileMetaInfo(new Hash(infoHash), null, null, new Date(), PIECE_LENGTH, "data.bin",
        hashes, content.length);
  }

  private static boolean contains(byte[] haystack, byte[] needle) {
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) {
        return true;
      }
    }
    return false;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /** An environment whose DHT cannot be used, so the test never goes online. */
  private static class NoDhtEnvironment extends Environment {
    NoDhtEnvironment() throws IOException {
      super(FreeloaderConfiguration.fromPropertiesFile());
    }

    public synchronized DHTracker getDHTracker() throws IOException {
      throw new IOException("the DHT is not used in this test");
    }
  }
}