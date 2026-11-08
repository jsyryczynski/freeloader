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

import org.apache.log4j.Logger;
import freeloader.Environment;
import freeloader.downloader.TorrentDownload;
import freeloader.torrent.Hash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Opens and accepts peer connections, encrypted where possible (see
 * EncryptedHandshake), before handing them to BitTorrentConnection.
 * The handshakes are a few blocking round trips, so they run on their
 * own threads rather than on the network thread.
 *
 * Outgoing connections try encryption first and, if the peer hangs up on
 * it (older clients don't support it), connect again unencrypted.
 * Incoming connections are accepted either way.
 */
public class ConnectionSetup {

  private static final Logger logger = Logger.getLogger(ConnectionSetup.class);

  private static final int CONNECT_TIMEOUT_MS = 10000;
  private static final int HANDSHAKE_TIMEOUT_MS = 20000;

  // peers that hung up on an encrypted handshake: connect to them
  // unencrypted straight away next time instead of failing first again
  private static final Set<InetSocketAddress> plainOnly = ConcurrentHashMap.newKeySet();

  private static final ExecutorService threads = Executors.newCachedThreadPool(runnable -> {
    Thread thread = new Thread(runnable, "connection-setup");
    thread.setDaemon(true);
    return thread;
  });

  private ConnectionSetup() {
  }

  /** Connects to a peer in the background; done is called either way once finished. */
  public static void connect(InetSocketAddress address, TorrentDownload download, Runnable done) {
    threads.execute(() -> {
      try {
        connectNow(address, download);
      } finally {
        done.run();
      }
    });
  }

  /** Sets up an accepted connection in the background. */
  public static void accept(SocketChannel channel, Environment environment) {
    threads.execute(() -> acceptNow(channel, environment));
  }

  private static void connectNow(InetSocketAddress address, TorrentDownload download) {
    SocketChannel channel = null;
    try {
      channel = open(address);
      EncryptedHandshake.Result result = null;
      if (!plainOnly.contains(address)) {
        result = tryEncryption(channel, address, download);
        if (result == null) {
          // the peer hung up on encryption: start over unencrypted
          close(channel);
          channel = open(address);
        }
      }
      if (result == null) {
        result = EncryptedHandshake.Result.plain(new byte[0]);
      }
      count(download.getEnvironment(), result.isEncrypted()
          ? "network.connections.encrypted.out" : "network.connections.plain.out");
      if (!download.isRunning()) {
        close(channel);
        return;
      }
      channel.configureBlocking(false);
      BitTorrentConnection.outgoingConnection(channel, download, result);
    } catch (IOException e) {
      logger.debug("could not connect to " + address + ": " + e.getMessage());
      close(channel);
      download.getPeerManager().connectionFailed(address);
    } catch (RuntimeException e) {
      logger.warn("error while connecting to " + address, e);
      close(channel);
      download.getPeerManager().connectionFailed(address);
    }
  }

  /** The encrypted handshake on a fresh connection; null if the peer won't do it. */
  private static EncryptedHandshake.Result tryEncryption(
      SocketChannel channel, InetSocketAddress address, TorrentDownload download) {
    try {
      return new EncryptedHandshake().initiate(in(channel), out(channel), download.getInfoHash());
    } catch (IOException e) {
      logger.debug("encrypted handshake with " + address + " failed (" + e.getMessage() + "), retrying unencrypted");
      plainOnly.add(address);
      return null;
    }
  }

  private static void acceptNow(SocketChannel channel, Environment environment) {
    try {
      channel.socket().setSoTimeout(HANDSHAKE_TIMEOUT_MS);
      InputStream in = in(channel);
      byte[] start = new byte[EncryptedHandshake.DETECTION_LENGTH];
      int offset = 0;
      while (offset < start.length) {
        int read = in.read(start, offset, start.length - offset);
        if (read < 0) {
          throw new IOException("closed before the handshake");
        }
        offset += read;
      }
      EncryptedHandshake.Result result;
      if (EncryptedHandshake.isPlainHandshake(start)) {
        result = EncryptedHandshake.Result.plain(start);
        count(environment, "network.connections.plain.in");
      } else {
        result = new EncryptedHandshake().respond(in, out(channel), start, runningTorrents(environment));
        count(environment, result.isEncrypted()
            ? "network.connections.encrypted.in" : "network.connections.plain.in");
      }
      channel.configureBlocking(false);
      BitTorrentConnection.incomingConnection(channel, environment, result);
    } catch (IOException e) {
      logger.debug("incoming connection failed: " + e.getMessage());
      close(channel);
    } catch (RuntimeException e) {
      logger.warn("error while accepting a connection", e);
      close(channel);
    }
  }

  private static SocketChannel open(InetSocketAddress address) throws IOException {
    SocketChannel channel = SocketChannel.open();
    try {
      channel.socket().connect(address, CONNECT_TIMEOUT_MS);
      channel.socket().setSoTimeout(HANDSHAKE_TIMEOUT_MS);
      return channel;
    } catch (IOException e) {
      close(channel);
      throw e;
    }
  }

  // the socket's own streams, which (unlike Channels.newInputStream) honor
  // the read timeout; never closed here, as that would close the channel
  private static InputStream in(SocketChannel channel) throws IOException {
    return channel.socket().getInputStream();
  }

  private static OutputStream out(SocketChannel channel) throws IOException {
    return channel.socket().getOutputStream();
  }

  private static List<Hash> runningTorrents(Environment environment) {
    List<Hash> result = new ArrayList<Hash>();
    for (TorrentDownload download : environment.getDownloadRepository().getAllDownloads()) {
      result.add(download.getInfoHash());
    }
    return result;
  }

  private static void count(Environment environment, String counter) {
    environment.getCounterRepository().getCounter(counter).increase(1);
  }

  private static void close(SocketChannel channel) {
    if (channel != null) {
      try {
        channel.close();
      } catch (IOException e) {
        // nothing more to do
      }
    }
  }
}
