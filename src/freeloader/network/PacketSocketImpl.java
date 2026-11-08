/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2006 Patrick Moor <patrick@moor.ws>
 * Modified by Jan Syryczyński, 2022-2026.
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
import freeloader.network.packets.Packet;
import freeloader.network.packets.PacketFactory;
import freeloader.network.packets.PacketHandler;
import freeloader.stats.Counter;
import freeloader.stats.CounterRepository;
import freeloader.util.PrefixLogger;
import freeloader.util.SystemTimeSource;
import freeloader.util.TimeSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

public class PacketSocketImpl extends AbstractSocketEventHandler implements PacketSocket {

  private final static Logger logger = Logger.getLogger(PacketSocketImpl.class);
  private final PrefixLogger prefixLogger;

  private final SocketChannel channel;
  private final Environment environment;

  private final Packetizer packetizer;
  private final Serializer serializer;

  private final List<PacketHandler> packetHandlers = new ArrayList<PacketHandler>();

  private static final PacketFactory packetFactory = new PacketFactory();

  private static final int DEFAULT_BYTE_BUFFER_SIZE = 64 * 1024;
  private Counter bytesRead;
  private Counter bytesWritten;

  private final ByteBuffer temporaryBuffer = ByteBuffer.allocate(DEFAULT_BYTE_BUFFER_SIZE);

  private TimeSource timeSource = SystemTimeSource.INSTANCE;

  private long lastSendAt = timeSource.getTime();
  private long lastReceiveAt = timeSource.getTime();
  private final InetSocketAddress socketAddress;

  // null when the connection isn't encrypted (see EncryptedHandshake)
  private final Rc4 incomingCipher;
  private final Rc4 outgoingCipher;
  // peer data read during connection setup, handled once there's a handler
  private byte[] initialData;
  // serialized (and encrypted) data the socket hasn't taken yet: encrypting
  // advances the cipher, so leftovers of a partial write must be kept as
  // they are rather than encrypted again
  private ByteBuffer outgoing = ByteBuffer.allocate(0);
  // whether the network thread reads from the channel yet
  private boolean reading = false;

  PacketSocketImpl(SocketChannel channel, Environment environment, EncryptedHandshake.Result encryption)
      throws IOException {
    this.channel = channel;
    this.environment = environment;
    this.incomingCipher = encryption.incoming;
    this.outgoingCipher = encryption.outgoing;
    this.initialData = encryption.initialData;
    socketAddress = (InetSocketAddress) channel.socket().getRemoteSocketAddress();
    packetizer = new Packetizer(packetFactory);
    serializer = new Serializer(packetFactory);
    setCounterRepository(environment.getCounterRepository());
    prefixLogger = new PrefixLogger(logger, getInstrumentationKey() + " ");
    // reading starts once there is a handler (see addPacketHandler), so
    // nothing that arrives first can be lost
  }

  public synchronized void close() throws IOException {
    channel.close();
  }

  public boolean available() {
    return channel.isConnected() && channel.isOpen() && channel.isRegistered();
  }

  public String getInstrumentationKey() {
    return socketAddress.getAddress().getHostAddress() + "@" + hashCode() % 100000;
  }

  public void setCounterRepository(CounterRepository repository) {
    bytesRead = repository.getCounter("network.rawbytes.in", getInstrumentationKey());
    bytesWritten = repository.getCounter("network.rawbytes.out", getInstrumentationKey());
  }

  synchronized public void becomesReadable(SelectionKey key) throws IOException {
    DownloadRateLimiter limiter = getNetworkManager().getDownloadLimiter();
    int allowed = limiter.acquire(DEFAULT_BYTE_BUFFER_SIZE);
    if (allowed == 0) {
      // download rate limit reached: leave the data in the socket (so
      // TCP slows the sender down) until there's budget again
      getNetworkManager().pauseReading(this, channel);
      return;
    }
    temporaryBuffer.clear();
    temporaryBuffer.limit(allowed);
    int read;
    try {
      read = channel.read(temporaryBuffer);
    } catch (IOException e) {
      // e.g. the peer reset the connection: close it like a regular
      // hang-up, rather than leave the dead socket to keep firing read
      // events until maintenance notices it
      limiter.giveBack(allowed);
      prefixLogger.debug("read failed, closing the connection: " + e.getMessage());
      close();
      return;
    }
    limiter.giveBack(allowed - Math.max(read, 0));
    if (read > 0 && incomingCipher != null) {
      incomingCipher.process(temporaryBuffer.array(), 0, read);
    }
    if (read < 0) {
      prefixLogger.info("negative read, closing the connection");
      close();
    } else if (read == 0) {
      prefixLogger.debug("short read, continuing");
    } else {
      prefixLogger.trace("read " + read + " bytes");
      bytesRead.increase(read);
      lastReceiveAt = timeSource.getTime();
      try {
        addPendingData(temporaryBuffer, read);
      } catch (IllegalStateException ise) {
        prefixLogger.warn("packetizer is in an illegal state", ise);
        close();
      }
    }
  }

  public void becomesWritable(SelectionKey key) {
    try {
      getNetworkManager().unregisterForWriteEvents(this, channel);
    } catch (ClosedChannelException e) {
      prefixLogger.warn("could not unregister channel for write events", e);
    }
    environment.getExecutor().execute(packetSender);
  }

  public void sendPacket(Packet packet) throws IOException {
    prefixLogger.debug(getDebugMessage(packet, "queueing a "));
    serializer.addPacket(packet);
    environment.getExecutor().execute(packetSender);
  }

  public synchronized void addPacketHandler(PacketHandler handler) {
    if (!packetHandlers.contains(handler)) {
      packetHandlers.add(handler);
    }
    if (initialData != null && initialData.length > 0) {
      bytesRead.increase(initialData.length);
      addPendingData(ByteBuffer.wrap(initialData), initialData.length);
    }
    initialData = null;
    if (!reading) {
      reading = true;
      try {
        getNetworkManager().registerForReadEvents(this, channel);
      } catch (ClosedChannelException e) {
        prefixLogger.debug("closed before it could be read from");
      }
    }
  }

  public InetAddress getRemoteAddress() {
    return socketAddress.getAddress();
  }

  public int getRemotePort() {
    return socketAddress.getPort();
  }

  public long getTimeSinceLastSend() {
    return timeSource.getTime() - lastSendAt;
  }

  public long getTimeSinceLastReceive() {
    return timeSource.getTime() - lastReceiveAt;
  }

  private void addPendingData(ByteBuffer temporaryBuffer, int length) {
    temporaryBuffer.position(0);
    synchronized (packetizer) {
      packetizer.addData(temporaryBuffer, length);
      if (packetizer.packetAvailable()) {
        environment.getExecutor().execute(packetReader);
      }
    }
  }

  private void notifyPacketHandlers(Packet packet) {
    if (packet == null) {
      prefixLogger.error("packet is null");
      return;
    }
    for (PacketHandler packetHandler : packetHandlers) {
      packet.handle(packetHandler);
    }
  }

  private NetworkManager getNetworkManager() {
    return environment.getNetworkManager();
  }

  private final PacketSender packetSender = new PacketSender();

  private class PacketSender implements Runnable {
    public void run() {
      try {
        process();
      } catch (IOException e) {
        prefixLogger.warn("error in packet sender", e);
      }
    }

    private void process() throws IOException {
      synchronized (serializer) {
        if (!outgoing.hasRemaining() && serializer.dataAvailable() > 0) {
          byte[] buffer = serializer.lendBytes(128 * 1024);
          serializer.confirmWrittenBytes(buffer, buffer.length);
          if (outgoingCipher != null) {
            outgoingCipher.process(buffer);
          }
          outgoing = ByteBuffer.wrap(buffer);
        }
        if (outgoing.hasRemaining()) {
          int written = channel.write(outgoing);
          prefixLogger.trace("wrote " + written + " bytes");
          bytesWritten.increase(written);
          lastSendAt = timeSource.getTime();
        }
        if (outgoing.hasRemaining() || serializer.dataAvailable() > 0) {
          getNetworkManager().registerForWriteEvents(PacketSocketImpl.this, channel);
        }
      }
    }
  }

  private String getDebugMessage(Packet packet, String prefix) {
    StringBuilder builder = new StringBuilder();
    builder.append(prefix).append(packet.getClass().getSimpleName());
    builder.append(": ");
    builder.append(packet.toString());
    return builder.toString();
  }

  private final PacketReader packetReader = new PacketReader();

  private class PacketReader implements Runnable {
    public void run() {
      try {
        process();
      } catch (IOException e) {
        prefixLogger.warn("error in pending packet processor", e);
      }
    }

    private void process() throws IOException {
      try {
        prefixLogger.trace("running pending packet processor");
        synchronized (packetizer) {
          while (packetizer.packetAvailable()) {
            Packet packet = packetizer.getNextPacket();
            prefixLogger.debug(getDebugMessage(packet, "got a "));
            notifyPacketHandlers(packet);
          }
        }
      } catch (IllegalStateException e) {
        prefixLogger.warn("socket is in an invalid state");
        close();
      }
    }
  }

  public static class Factory implements PacketSocketFactory {
    public PacketSocket createPacketSocket(SocketChannel channel, Environment environment,
                                           EncryptedHandshake.Result encryption) throws IOException {
      return new PacketSocketImpl(channel, environment, encryption);
    }
  }
}
