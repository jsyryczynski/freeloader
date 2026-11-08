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

package freeloader.network;

import org.apache.log4j.Logger;
import freeloader.Environment;
import freeloader.downloader.Block;
import freeloader.downloader.PeerFinder;
import freeloader.downloader.PieceAnnounceStrategy;
import freeloader.downloader.TorrentDownload;
import freeloader.network.packets.BitFieldPacket;
import freeloader.network.packets.CancelPacket;
import freeloader.network.packets.ExtendedPacket;
import freeloader.network.packets.ChokePacket;
import freeloader.network.packets.HandshakePacket;
import freeloader.network.packets.HavePacket;
import freeloader.network.packets.InterestedPacket;
import freeloader.network.packets.KeepAlivePacket;
import freeloader.network.packets.NotInterestedPacket;
import freeloader.network.packets.Packet;
import freeloader.network.packets.PacketHandler;
import freeloader.network.packets.PiecePacket;
import freeloader.network.packets.RequestPacket;
import freeloader.network.packets.UnchokePacket;
import freeloader.stats.CounterRepository;
import freeloader.stats.recorder.Bucket;
import freeloader.storage.BitField;
import freeloader.storage.DataBlock;
import freeloader.torrent.PeerId;
import freeloader.util.PrefixLogger;
import freeloader.util.SystemTimeSource;
import freeloader.util.TimeSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

public class BitTorrentConnection implements PacketHandler {

  private final PacketSocket socket;
  private final Environment environment;

  private TorrentDownload torrentDownload;

  private boolean remote_choked = true;
  private boolean remote_interested = false;

  private boolean local_interested = false;

  private BitField remote_having;

  private static final int PENDING_REQUESTS = 6;
  private Set<Block> requestedBlocks = new HashSet<Block>();

  private boolean properlySetUp = false;

  private boolean inbound;

  private PacketSocket.PacketSocketFactory factory =
      new PacketSocketImpl.Factory();

  private IntConsumer pieceListener;

  private final InetSocketAddress remoteAddress;

  private static final int RECEIVE_TIMEOUT = 300;
  private static final int KEEPALIVE_TIMEOUT = 90;

  private static final Logger logger = Logger.getLogger(BitTorrentConnection.class);
  private final PrefixLogger prefixLogger;

  private PieceAnnounceStrategy pieceAnnounceStrategy;

  private boolean closed = false;

  private TimeSource timeSource = SystemTimeSource.INSTANCE;
  private long totalDownloaded = 0;
  private final Bucket downloadRateBucket = new Bucket(20, 10 * 1000, timeSource.getTime());
  private static final int DOWNLOAD_SCORE_PERIOD = 180 * 1000;

  public static BitTorrentConnection incomingConnection(
      SocketChannel channel, Environment environment, EncryptedHandshake.Result encryption) throws IOException {
    BitTorrentConnection connection =
        new BitTorrentConnection(channel, environment, true, null, encryption);
    environment.getConnectionRepository().addIncomingConnection(connection);
    environment.getCounterRepository().getCounter("network.connections.opened.in").increase(1);
    return connection;
  }

  public static BitTorrentConnection outgoingConnection(
      SocketChannel channel, TorrentDownload torrentDownload, EncryptedHandshake.Result encryption)
      throws IOException {
    BitTorrentConnection connection =
        new BitTorrentConnection(channel, torrentDownload.getEnvironment(), false, torrentDownload, encryption);
    torrentDownload.getEnvironment().getConnectionRepository().addOutgoingConnection(connection);
    torrentDownload.getEnvironment().getCounterRepository().getCounter("network.connections.opened.out").increase(1);
    return connection;
  }

  public CounterRepository getCounterRepository() {
    if (torrentDownload != null) {
      return torrentDownload.getCounterRepository();
    } else {
      return environment.getCounterRepository();
    }
  }

  private BitTorrentConnection(
      SocketChannel channel, Environment environment, boolean inbound, TorrentDownload torrentDownload,
      EncryptedHandshake.Result encryption) throws IOException {
    assertValidTorrentDownload(inbound, torrentDownload);
    this.environment = environment;
    this.inbound = inbound;
    this.torrentDownload = torrentDownload;
    socket = factory.createPacketSocket(channel, environment, encryption);
    if (torrentDownload != null) {
      socket.setCounterRepository(torrentDownload.getCounterRepository());
    }

    remoteAddress = new InetSocketAddress(socket.getRemoteAddress(), socket.getRemotePort());
    prefixLogger = new PrefixLogger(logger, socket.getInstrumentationKey() + " ");
    if (isInbound()) {
      prefixLogger.info("new inbound connection from " + remoteAddress);
    } else {
      prefixLogger.info("new outbound connection to " + remoteAddress);
      sendHandshakePacket();
    }
    getCounterRepository().getCounter("network.interested", socket.getInstrumentationKey()).set(0);
    getCounterRepository().getCounter("network.choke", socket.getInstrumentationKey()).set(1);
    // last: messages are handled from here on, so everything they use is set up
    socket.addPacketHandler(this);
  }

  private void assertValidTorrentDownload(boolean inbound, TorrentDownload torrentDownload) {
    if (inbound && torrentDownload == null || !inbound && torrentDownload != null) {
      return;
    }
    throw new IllegalArgumentException("inbound/torrentDownload do not macth");
  }

  private synchronized void close(ConnectionCloseReason reason) {
    if (closed) {
      prefixLogger.debug("connection is already closed");
      return;
    }

    prefixLogger.debug("closing connection: " + reason);
    if (isOutbound() && !properlySetUp && reason != ConnectionCloseReason.TORRENT_STOPED) {
      // never got as far as the BitTorrent handshake: counts towards
      // giving up on this peer
      torrentDownload.getPeerManager().connectionFailed(remoteAddress);
    }
    returnRequestedBlocks();
    try {
      socket.close();
    } catch (IOException e) {
      prefixLogger.warn("exception during closing of socket", e);
    }
    unregisterPieceListener();
    environment.getConnectionRepository().closeConnection(this);
    getCounterRepository().getCounter("network.interested", socket.getInstrumentationKey()).set(0);
    getCounterRepository().getCounter("network.choke", socket.getInstrumentationKey()).set(1);
    if (isInbound()) {
      getCounterRepository().getCounter("network.connections.closed.in", reason.name()).increase(1);
    } else {
      getCounterRepository().getCounter("network.connections.closed.out", reason.name()).increase(1);
    }
    closed = true;
  }

  public boolean isInbound() {
    return inbound;
  }

  public boolean isOutbound() {
    return !inbound;
  }

  private synchronized void removePendingBlock(Block block) {
    requestedBlocks.remove(new Block(block));
  }

  private synchronized void requestBlocks() {
    if (!allowedToRequestBlocksFromPeer()) {
      return;
    }

    int newRequestsRequired = PENDING_REQUESTS - requestedBlocks.size();
    if (newRequestsRequired <= 0) {
      prefixLogger.debug("already " + requestedBlocks.size() + " pending requests, won't request new ones");
      return;
    }
    requestNewBlocks(newRequestsRequired);
  }

  private boolean allowedToRequestBlocksFromPeer() {
    if (!local_interested || remote_choked) {
      return false;
    }

    return !(!torrentDownload.getConfiguration().isDownloadingFromSeeders() && remote_having.hasAll());
  }

  private void requestNewBlocks(int numberOfBlocks) {
    List<Block> blocks = torrentDownload.getNextBlockStrategy().getMoreBlocks(remote_having, numberOfBlocks);
    for (Block block : blocks) {
      prefixLogger.debug("requesting block " + block);
      safeSend(new RequestPacket(block));
      requestedBlocks.add(block);
      incrementCounter("network.request.out");
    }
  }

  private void sendHandshakePacket() {
    prefixLogger.debug("sending handshake packet");
    if (torrentDownload == null) {
      throw new IllegalStateException("torrent download must be set");
    }
    safeSend(HandshakePacket.withExtensionProtocol(
        torrentDownload.getMetaInfo().getInfoHash(), torrentDownload.getTrackerRegisteredPeerId()));
  }

  private void sendKeepAlivePacket() {
    prefixLogger.debug("sending a keep alive packet");
    incrementCounter("network.keepalive.out");
    safeSend(new KeepAlivePacket());
  }

  public void doMaintenance() {
    if (haventHeardAnythingInALongTime()) {
      close(ConnectionCloseReason.INACTIVITY);
      return;
    }

    if (!isOpen()) {
      close(ConnectionCloseReason.SOCKET_CLOSED);
      return;
    }

    if (remote_having != null && torrentDownload != null) {
      if (!torrentDownload.getConfiguration().isDownloadingFromSeeders() && remote_having.hasAll()) {
        close(ConnectionCloseReason.NOT_ALLOWED_TO_LEECH);
        return;
      }
    }

    determineIfInterested();
    sendKeepAlivePacketIfNeeded();
  }

  private void sendKeepAlivePacketIfNeeded() {
    if (timeToSendKeepAlivePacket()) {
      sendKeepAlivePacket();
    }
  }

  public boolean isOpen() {
    return socket.available();
  }

  private boolean haventHeardAnythingInALongTime() {
    return socket.getTimeSinceLastReceive() > RECEIVE_TIMEOUT * 1000;
  }

  private boolean timeToSendKeepAlivePacket() {
    return properlySetUp && socket.getTimeSinceLastSend() > KEEPALIVE_TIMEOUT * 1000;
  }

  public InetSocketAddress getRemoteAddress() {
    return remoteAddress;
  }

  private synchronized void returnRequestedBlocks() {
    if (!requestedBlocks.isEmpty()) {
      torrentDownload.getNextBlockStrategy().returnBlocks(requestedBlocks);
      requestedBlocks.clear();
    }
  }

  private synchronized void determineIfInterested() {
    if (torrentDownload == null || !properlySetUp) {
      return;
    }

    if (torrentDownload.getPieceManager().isBitFieldOfInterest(remote_having)) {
      // interested
      if (!local_interested) {
        prefixLogger.debug("interested in remote peer");
        safeSend(new InterestedPacket());
        local_interested = true;
        getCounterRepository().getCounter("network.interested", socket.getInstrumentationKey()).set(1);
        incrementCounter("network.interested.out");
      }
    } else {
      // not interested
      if (local_interested) {
        prefixLogger.debug("not interested in remote peer ");
        safeSend(new NotInterestedPacket());
        local_interested = false;
        getCounterRepository().getCounter("network.interested", socket.getInstrumentationKey()).set(0);
        incrementCounter("network.notinterested.out");
        returnRequestedBlocks();
      }
    }
  }

  private void registerPieceListener() {
    pieceListener = pieceIndex -> {
      synchronized (BitTorrentConnection.this) {
        cancelPendingRequestsForPiece(pieceIndex);
        determineIfInterested();
        requestBlocks();
        sendHaving(pieceIndex);
      }
    };
    torrentDownload.getPieceManager().addPieceListener(pieceListener);
  }

  private void unregisterPieceListener() {
    if (pieceListener != null) {
      torrentDownload.getPieceManager().removePieceListener(pieceListener);
    }
  }

  private synchronized void cancelPendingRequestsForPiece(int pieceIndex) {
    for (Block block : requestedBlocks) {
      if (block.getPieceIndex() == pieceIndex) {
        cancelRequest(block);
      }
    }
  }

  private void cancelRequest(Block block) {
    prefixLogger.debug("canceling block " + block);
    incrementCounter("network.cancel.out");
    safeSend(new CancelPacket(block));
  }

  public TorrentDownload getTorrentDownload() {
    return torrentDownload;
  }

  public BitField getRemoteBitField() {
    return remote_having != null ? remote_having.clone() : null;
  }

  private synchronized void sendHaving(int pieceIndex) {
    if (getPieceAnnounceStrategy().announcePiece(pieceIndex)) {
      String counterName = "network.have.out";
      incrementCounter(counterName);
      safeSend(new HavePacket(pieceIndex));
    }
  }

  private synchronized void safeSend(Packet packet) {
    try {
      socket.sendPacket(packet);
    } catch (IOException e) {
      prefixLogger.warn("exception during sending of packet: " + packet, e);
      close(ConnectionCloseReason.EXCEPTION_WHILE_SENDING);
    }
  }

  private synchronized void sendBitField() {
    prefixLogger.debug("sending bitfield packet");
    safeSend(new BitFieldPacket(getPieceAnnounceStrategy().getBitFieldToSend()));
  }

  private synchronized PieceAnnounceStrategy getPieceAnnounceStrategy() {
    if (pieceAnnounceStrategy == null && torrentDownload != null) {
      pieceAnnounceStrategy = torrentDownload.getPieceAnnounceStrategy(this);
    }
    return pieceAnnounceStrategy;
  }

  private void incrementCounter(String counterName) {
    getCounterRepository().getCounter(counterName, socket.getInstrumentationKey()).increase(1);
  }

  public long getDownloadScore() {
    long amountThen =
        downloadRateBucket.get(timeSource.getTime() - DOWNLOAD_SCORE_PERIOD);
    long amountNow =
        downloadRateBucket.get(timeSource.getTime());
    return amountNow - amountThen;
  }

  public synchronized void handleHandshakePacket(HandshakePacket packet) {
    prefixLogger.info("Handshake from peer " + packet.getPeerId());
    if (torrentDownload == null) {
      torrentDownload = environment.getDownloadRepository().getDownload(packet.getInfoHash());
      if (torrentDownload == null) {
        prefixLogger.info("no torrent running for info hash " + packet.getInfoHash());
        close(ConnectionCloseReason.NO_TORRENT_RUNNING);
        return;
      }
      socket.setCounterRepository(torrentDownload.getCounterRepository());
    }
    if (isMyPeerId(packet.getPeerId())) {
      close(ConnectionCloseReason.CONNECT_TO_MYSELF);
      return;
    }
    if (isInbound()) {
      sendHandshakePacket();
    }
    sendBitField();
    registerPieceListener();
    remote_having = new BitField(torrentDownload.getMetaInfo().getPieceCount());
    properlySetUp = true;
    if (isOutbound()) {
      torrentDownload.getPeerManager().connectionSucceeded(remoteAddress);
    }
    if (packet.supportsExtensionProtocol()) {
      // ask for peer exchange messages (see PeerExchange)
      safeSend(new ExtendedPacket(ExtendedPacket.HANDSHAKE_ID,
          PeerExchange.handshakePayload(torrentDownload.getConfiguration().getListeningPort())));
    }
  }

  private boolean isMyPeerId(PeerId remoteId) {
    return torrentDownload.getTrackerRegisteredPeerId().equals(remoteId);
  }

  public synchronized void handleBitFieldPacket(BitFieldPacket packet) {
    prefixLogger.trace("got a bit field: " + packet.getBitField().toString());
    try {
      remote_having = packet.getBitField().downcastTo(torrentDownload.getMetaInfo().getPieceCount());
    } catch (IllegalArgumentException e) {
      prefixLogger.debug("bitfield downcast error", e);
      close(ConnectionCloseReason.BITFIELD_DOWNCAST_ERROR);
      return;
    }
    determineIfInterested();
    getCounterRepository().getCounter("network.choke", socket.getInstrumentationKey()).set(1);
  }

  public synchronized void handleUnchokePacket(UnchokePacket packet) {
    getCounterRepository().getCounter("network.choke", socket.getInstrumentationKey()).set(0);
    incrementCounter("network.unchoke.in");
    prefixLogger.debug("i'm being unchoked");
    remote_choked = false;
    requestBlocks();
  }

  public synchronized void handleInterestedPacket(InterestedPacket packet) {
    incrementCounter("network.interested.in");
    prefixLogger.debug("other peer is interested");
    remote_interested = true;
  }

  public synchronized void handleNotInterestedPacket(NotInterestedPacket packet) {
    incrementCounter("network.notinterested.in");
    prefixLogger.debug("other peer is not interested");
    remote_interested = false;
  }

  public synchronized void handleKeepAlivePacket(KeepAlivePacket packet) {
    incrementCounter("network.keepalive.in");
    sendKeepAlivePacketIfNeeded();
  }

  public synchronized void handleHavePacket(HavePacket packet) {
    incrementCounter("network.have.in");
    prefixLogger.debug("got a have packet for piece " + packet.getPieceIndex());
    remote_having.gotPiece(packet.getPieceIndex());
    determineIfInterested();
    requestBlocks();
  }

  public synchronized void handlePiecePacket(PiecePacket packet) {
    prefixLogger.debug("got a block: " + packet);
    incrementCounter("network.piece.in");
    if (remote_having.hasAll()) {
      incrementCounter("torrent.blocks.seed");
    } else {
      incrementCounter("torrent.blocks.leecher");
    }
    DataBlock block = DataBlock.fromPiecePacket(packet);
    totalDownloaded += block.getLength();
    downloadRateBucket.addValue(timeSource.getTime(), totalDownloaded);
    removePendingBlock(block);
    torrentDownload.getPieceManager().setBlock(block);
    determineIfInterested();
    requestBlocks();
  }

  public void handleExtendedPacket(ExtendedPacket packet) {
    TorrentDownload download = torrentDownload;
    if (download == null || packet.getExtendedId() != PeerExchange.OUR_UT_PEX_ID) {
      // the peer's own extension handshake, or something we didn't ask for
      return;
    }
    List<InetSocketAddress> peers = PeerExchange.addedPeers(packet.getPayload());
    prefixLogger.debug("peer exchange: " + peers.size() + " peer(s)");
    if (!peers.isEmpty()) {
      getCounterRepository().getCounter("network.pex.peers").increase(peers.size());
      download.getPeerManager().addPeersFromTrackerResponse(PeerFinder.toPeerInfoList(peers));
    }
  }

  public synchronized void handleChokePacket(ChokePacket packet) {
    getCounterRepository().getCounter("network.choke", socket.getInstrumentationKey()).set(1);
    incrementCounter("network.choke.in");
    prefixLogger.debug("i'm being choked");
    remote_choked = true;
    returnRequestedBlocks();
  }

  public void handleRequestPacket(RequestPacket packet) {
    // never answered: Freeloader uploads nothing. Peers stay choked (no
    // unchoke is ever sent), so well-behaved ones don't even ask.
    incrementCounter("network.request.in");
  }

  public void handleCancelPacket(CancelPacket packet) {
    incrementCounter("network.cancel.in");
  }

  public boolean isRemoteInterested() {
    return remote_interested;
  }

  public boolean isProperlySetUp() {
    return properlySetUp;
  }

  public void closeBecauseTorrentStops() {
    close(ConnectionCloseReason.TORRENT_STOPED);
  }
}
