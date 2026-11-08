/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2006 Patrick Moor <patrick@moor.ws>
 * Modified by Jan Syryczyński, 2022-2026.
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

package freeloader.dht;

import org.apache.log4j.Logger;
import freeloader.Environment;
import freeloader.dht.messages.DHTMessage;
import freeloader.dht.messages.ErrorMessage;
import freeloader.dht.messages.QueryMessage;
import freeloader.dht.messages.ResponseMessage;
import freeloader.network.AbstractSocketEventHandler;
import freeloader.stats.CounterRepository;
import freeloader.util.CacheMap;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;

public class DHTSocket extends AbstractSocketEventHandler {

  private Environment environment;
  private final DHTPacketParser parser;
  private final int port;
  private DatagramChannel channel;

  private ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);

  private final CacheMap<TransactionId, String> transactionIdMap =
      new CacheMap<TransactionId, String>(2048);

  private final static Logger logger = Logger.getLogger(DHTSocket.class);

  public DHTSocket(Environment environment,
                   PacketHandler packetHandler,
                   int port)
      throws IOException {
    this.environment = environment;
    this.port = port;

    logger.info("creating new dht listener on udp port " + port);
    channel = DatagramChannel.open();
    channel.configureBlocking(false);
    DatagramSocket socket = channel.socket();
    socket.bind(new InetSocketAddress(port));

    this.parser = new DHTPacketParser(packetHandler, this);

    environment.getNetworkManager().registerForReadEvents(this, channel);
  }

  public synchronized void becomesReadable(SelectionKey key) throws IOException {
    if (key.channel() != channel) {
      logger.error("did expect a different channel");
      return;
    }

    buffer.clear();
    SocketAddress sender = channel.receive(buffer);
    if (sender == null) {
      logger.warn("did not read anything");
      return;
    }

    byte[] data = new byte[buffer.position()];
    buffer.position(0);
    buffer.get(data);

    getCounterRepository().getCounter("dht.bytes.in").increase(data.length);

    if (!(sender instanceof InetSocketAddress)) {
      logger.fatal("we can only handle inet socket addresses, was a " + sender.getClass());
      return;
    }
    parser.parse(data, (InetSocketAddress) sender);
  }

  private CounterRepository getCounterRepository() {
    return environment.getCounterRepository();
  }

  public void query(QueryMessage message, SocketAddress recipient) throws IOException {
    send(message, recipient);
    transactionIdMap.put(message.getTransactionId(), message.getQueryType());
  }

  public void respond(ResponseMessage message, InetSocketAddress recipient) throws IOException {
    send(message, recipient);
  }

  public String getQueryType(TransactionId transactionId) {
    return transactionIdMap.get(transactionId);
  }

  public void error(ErrorMessage errorMessage, InetSocketAddress recipient) throws IOException {
    send(errorMessage, recipient);
  }

  private void send(DHTMessage message, SocketAddress recipient) throws IOException {
    ByteBuffer buffer = ByteBuffer.wrap(message.encode());
    channel.send(buffer, recipient);
    getCounterRepository().getCounter("dht.bytes.out").increase(buffer.limit());
  }
}
