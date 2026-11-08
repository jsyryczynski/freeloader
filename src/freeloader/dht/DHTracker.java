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

package freeloader.dht;

import org.apache.log4j.Logger;
import freeloader.Environment;
import freeloader.dht.messages.ErrorMessage;
import freeloader.dht.messages.QueryMessage;
import freeloader.dht.messages.ResponseMessage;
import freeloader.stats.CounterRepository;
import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DHTracker implements PacketHandler, GetPeersSender {

  private static final long GET_PEERS_QUERY_TIMEOUT_MS = 3000;

  // well-known, always-on bootstrap nodes for the public (Mainline) DHT;
  // used to seed the routing table the first time it's needed, rather
  // than waiting for the periodic Maintenance job (which doesn't run
  // until 20 seconds after startup) to get around to it
  private static final InetSocketAddress[] BOOTSTRAP_NODES = new InetSocketAddress[]{
      new InetSocketAddress("router.bittorrent.com", 6881),
      new InetSocketAddress("router.utorrent.com", 6881),
      new InetSocketAddress("dht.transmissionbt.com", 6881),
      new InetSocketAddress("dht.libtorrent.org", 25401),
  };
  private static final long BOOTSTRAP_TIMEOUT_MS = 8000;
  private static final long BOOTSTRAP_POLL_INTERVAL_MS = 200;
  // wait for more than just the first bootstrap node to reply if we can:
  // a handful of nodes from a single reply are all clustered around
  // whatever that one node happens to know about, where nodes from
  // several independent bootstrap servers give a more varied, more
  // useful starting point for the iterative lookup that follows
  private static final int MIN_BOOTSTRAP_NODES = 16;
  // The bootstrap routers only answer with *other* nodes' addresses, and
  // those normally reach the routing table only once the spider pings
  // them (first Maintenance run, 20s after startup). While bootstrapping,
  // query them right away instead -- their replies are what fill the
  // table -- up to this many queries per bootstrap.
  private static final int MAX_BOOTSTRAP_QUERIES = 64;

  private final Environment environment;
  private final int port;
  private final DHTSocket socket;
  private final PeerId ourId;
  private final RoutingTable table;
  private final AnnounceStore announces;
  private final PeerSpider spider;
  private final ConcurrentMap<TransactionId, CompletableFuture<GetPeersOutcome>> pendingGetPeers =
      new ConcurrentHashMap<TransactionId, CompletableFuture<GetPeersOutcome>>();
  // > 0 only while bootstrapIfNeeded() is waiting for the table to fill
  private final AtomicInteger bootstrapQueriesLeft = new AtomicInteger();

  private static final Logger logger = Logger.getLogger(DHTracker.class);

  public DHTracker(Environment environment, TrackerState state) throws IOException {
    this.environment = environment;
    this.port = state.getListeningPort();
    this.ourId = state.getOurId();
    logger.info("starting tracker on port " + port + " with id " + ourId);

    table = new RoutingTable(ourId, this::ping, environment.getCounterRepository());
    announces = new AnnounceStore(8192, 512, environment.getCounterRepository());
    spider = new PeerSpider(ourId, this::findNode, environment.getCounterRepository());

    for (TrackerResponse.PeerInfo info : state.getPeers()) {
      spider.heardAbout(info);
    }

    socket = new DHTSocket(environment, this, port);
    environment.getScheduledExecutor().scheduleWithFixedDelay(
        new Maintenance(), 20, 20, TimeUnit.SECONDS);
  }

  public void findNode(InetSocketAddress sender, TransactionId transactionId, PeerId id, PeerId target) throws IOException {
    TrackerResponse.PeerInfo[] nodes = table.getNodesCloseTo(target, 8);
    ResponseMessage message = ResponseMessage.constructFindNodesReply(transactionId, ourId, nodes);
    socket.respond(message, sender);
    spider.heardAbout(new TrackerResponse.PeerInfo(id, sender));
    getCounterRepository().getCounter("dht.findnode.in").increase(1);
  }

  private CounterRepository getCounterRepository() {
    return environment.getCounterRepository();
  }

  public void findNodeReply(InetSocketAddress sender, TransactionId transactionId, PeerId id,
                            TrackerResponse.PeerInfo[] nodes) throws IOException {
    gotReplyFromPeer(id, sender);
    for (TrackerResponse.PeerInfo info : nodes) {
      spider.heardAbout(info);
      if (bootstrapQueriesLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
        findNode(info.getSocketAddress(), ourId.flipLastBit());
      }
    }
  }

  public void getPeers(InetSocketAddress sender, TransactionId transactionId, PeerId id, Hash infoHash) throws IOException {
    PeerId hashId = new PeerId(infoHash.getBytes());
    Token token = Token.createForAddress(sender);
    ResponseMessage message = null;
    TrackerResponse.PeerInfo[] peers = announces.getPeers(infoHash, 256);
    if (peers != null) {
      getCounterRepository().getCounter("dht.getpeers.matches").increase(1);
      message = ResponseMessage.constructGetPeersReply(transactionId, ourId, token, peers);
    } else {
      TrackerResponse.PeerInfo[] nodes = table.getNodesCloseTo(hashId, 8);
      message = ResponseMessage.constructGetPeersReplyNoPeers(transactionId, ourId, token, nodes);
    }
    socket.respond(message, sender);
    spider.heardAbout(new TrackerResponse.PeerInfo(id, sender));
    getCounterRepository().getCounter("dht.getpeers.in").increase(1);
  }

  public void getPeersReply(InetSocketAddress sender, TransactionId transactionId, PeerId id, Token token,
                            TrackerResponse.PeerInfo[] nodes) {
    gotReplyFromPeer(id, sender);
    for (TrackerResponse.PeerInfo info : nodes) {
      spider.heardAbout(info);
    }
    completePendingGetPeers(transactionId, GetPeersOutcome.forNodes(nodes));
  }

  public void getPeersReplyWithMatches(InetSocketAddress sender, TransactionId transactionId, PeerId id, Token token,
                                       TrackerResponse.PeerInfo[] matches) {
    gotReplyFromPeer(id, sender);
    completePendingGetPeers(transactionId, GetPeersOutcome.forPeers(matches));
  }

  private void completePendingGetPeers(TransactionId transactionId, GetPeersOutcome outcome) {
    CompletableFuture<GetPeersOutcome> future = pendingGetPeers.remove(transactionId);
    if (future != null) {
      future.complete(outcome);
    }
  }

  /**
   * Sends an outbound get_peers query (BEP 5) to a single DHT node,
   * asking whether it knows about peers for the given info hash. Part of
   * the GetPeersSender interface used by DHTPeerFinder to perform an
   * iterative lookup across many nodes.
   */
  public CompletableFuture<GetPeersOutcome> send(InetSocketAddress address, Hash infoHash) {
    final TransactionId transactionId = TransactionId.createRandom();
    final CompletableFuture<GetPeersOutcome> future = new CompletableFuture<GetPeersOutcome>();
    pendingGetPeers.put(transactionId, future);

    QueryMessage message = QueryMessage.constructGetPeersQuery(transactionId, ourId, infoHash);
    try {
      socket.query(message, address);
      getCounterRepository().getCounter("dht.getpeers.out").increase(1);
    } catch (IOException e) {
      logger.debug("exception while sending get_peers to " + address, e);
      pendingGetPeers.remove(transactionId);
      future.complete(GetPeersOutcome.EMPTY);
      return future;
    }

    environment.getScheduledExecutor().schedule(new Runnable() {
      public void run() {
        CompletableFuture<GetPeersOutcome> pending = pendingGetPeers.remove(transactionId);
        if (pending != null) {
          pending.complete(GetPeersOutcome.EMPTY);
        }
      }
    }, GET_PEERS_QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);

    return future;
  }

  /**
   * Performs an iterative DHT lookup to find peers for a torrent, given
   * only its info hash -- no tracker required. Used for trackerless
   * (DHT-only) magnet links. Blocks the calling thread until either some
   * peers are found or the lookup times out, so should be called from a
   * background thread.
   */
  public List<InetSocketAddress> findPeers(Hash infoHash, long overallTimeoutMs) {
    return findPeers(infoHash, overallTimeoutMs, DHTPeerFinder.DEFAULT_ENOUGH_PEERS, peers -> { });
  }

  /** See DHTPeerFinder: looks for up to enoughPeers, reporting them as they come in. */
  public List<InetSocketAddress> findPeers(Hash infoHash, long overallTimeoutMs, int enoughPeers,
                                           DHTPeerFinder.PeersListener listener) {
    long deadline = System.currentTimeMillis() + overallTimeoutMs;
    bootstrapIfNeeded();
    long remaining = Math.max(0, deadline - System.currentTimeMillis());
    return new DHTPeerFinder(table, this).findPeers(infoHash, remaining, enoughPeers, listener);
  }

  /**
   * A freshly-created DHTracker's routing table starts out completely
   * empty -- the only thing it knows is a couple of well-known bootstrap
   * addresses sitting unqueried in the PeerSpider's cache, which normally
   * only get pinged once the periodic Maintenance job first runs, 20
   * seconds after startup. A lookup that runs before then would search
   * an empty table and immediately report no peers, regardless of how
   * well-seeded the torrent actually is. This queries the bootstrap
   * nodes directly and waits (bounded by BOOTSTRAP_TIMEOUT_MS) for at
   * least one of them to reply and populate the table, so the very first
   * lookup after startup has something to search. A no-op once the table
   * already has nodes in it (from a previous lookup, or once regular
   * Maintenance has had a chance to run).
   */
  private synchronized void bootstrapIfNeeded() {
    if (table.size() >= MIN_BOOTSTRAP_NODES) {
      return;
    }
    logger.info("dht routing table has only " + table.size() + " node(s), bootstrapping...");
    PeerId closeByAddress = ourId.flipLastBit();
    bootstrapQueriesLeft.set(MAX_BOOTSTRAP_QUERIES);
    try {
      for (InetSocketAddress address : BOOTSTRAP_NODES) {
        findNode(address, closeByAddress);
      }

      long deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS;
      while (table.size() < MIN_BOOTSTRAP_NODES && System.currentTimeMillis() < deadline) {
        try {
          Thread.sleep(BOOTSTRAP_POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    } finally {
      bootstrapQueriesLeft.set(0);
    }
    logger.info("dht bootstrap finished with " + table.size() + " node(s) in the routing table");
  }

  public void ping(InetSocketAddress sender, TransactionId transactionId, PeerId id) throws IOException {
    ResponseMessage message = ResponseMessage.constructPingReply(transactionId, ourId);
    socket.respond(message, sender);
    spider.heardAbout(new TrackerResponse.PeerInfo(id, sender));
    getCounterRepository().getCounter("dht.ping.in").increase(1);
  }

  public void pingReply(InetSocketAddress sender, TransactionId transactionId, PeerId id) {
    gotReplyFromPeer(id, sender);
  }

  public void announcePeer(InetSocketAddress sender, TransactionId transactionId,
                           PeerId id, Hash infoHash, Token token,
                           int port) throws IOException {
    if (!Token.isValid(token, sender)) {
      logger.info("invalid token provided by " + sender);
      ErrorMessage error =
          new ErrorMessage(transactionId, ErrorMessage.PROTOTOL_ERROR, "invalid token");
      socket.error(error, sender);
      getCounterRepository().getCounter("dht.announce.invalidtoken").increase(1);
      return;
    }
    announces.announce(infoHash, new InetSocketAddress(sender.getAddress(), sender.getPort()));
    ResponseMessage message = ResponseMessage.constructPingReply(transactionId, ourId);
    socket.respond(message, sender);
    spider.heardAbout(new TrackerResponse.PeerInfo(id, sender));
    getCounterRepository().getCounter("dht.announce.in").increase(1);
  }


  private void gotReplyFromPeer(PeerId id, InetSocketAddress sender) {
    TrackerResponse.PeerInfo info = new TrackerResponse.PeerInfo(id, sender);
    table.addNode(info);
  }

  public void ping(InetSocketAddress address) {
    QueryMessage message =
        QueryMessage.constructPingQuery(TransactionId.createRandom(), ourId);
    try {
      socket.query(message, address);
      getCounterRepository().getCounter("dht.ping.out").increase(1);
    } catch (IOException e) {
      logger.error("exception during ping of " + address, e);
    }
  }

  public void findNode(InetSocketAddress address, PeerId target) {
    logger.trace("sending finde node query to " + target + " at " + address);
    QueryMessage message =
        QueryMessage.constructFindNodeQuery(TransactionId.createRandom(), ourId, target);
    try {
      socket.query(message, address);
      getCounterRepository().getCounter("dht.findnode.out").increase(1);
    } catch (IOException e) {
      logger.error("exception while sending finde node to " + address, e);
    }
  }

  public RoutingTable getRoutingTable() {
    return table;
  }

  public PeerId getPeerId() {
    return ourId;
  }

  public int getPort() {
    return port;
  }

  private class Maintenance implements Runnable {

    public void run() {
      try {
        safeRun();
      } catch (Exception e) {
        logger.error("exception during DHT maintenance", e);
      }
    }

    private void safeRun() {
      PeerId closeByAddress = ourId.flipLastBit();
      for (TrackerResponse.PeerInfo info : getRandomKnownPeersSubset(8)) {
        findNode(info.getSocketAddress(), closeByAddress);
      }

      table.maintain();
      spider.maintain();
      announces.maintain();
    }

    private List<TrackerResponse.PeerInfo> getRandomKnownPeersSubset(int count) {
      List<TrackerResponse.PeerInfo> result = new ArrayList<TrackerResponse.PeerInfo>(count);
      result.addAll(Arrays.asList(table.getNodesCloseTo(PeerId.createRandom(), count * 2)));
      result.addAll(Arrays.asList(table.getNodesCloseTo(ourId, count * 4)));
      Collections.shuffle(result);
      return result.subList(0, Math.min(result.size(), count));
    }
  }
}
