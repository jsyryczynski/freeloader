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
import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.BInteger;
import freeloader.bencoding.BList;
import freeloader.bencoding.BString;
import freeloader.bencoding.ParseException;
import freeloader.dht.messages.DHTMessage;
import freeloader.dht.messages.ErrorMessage;
import freeloader.dht.messages.QueryMessage;
import freeloader.torrent.Hash;
import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

public class DHTPacketParser {

  private final BDecoder decoder = new BDecoder();
  private final PacketHandler packetHandler;
  private final DHTSocket socket;

  private final static Logger logger = Logger.getLogger(DHTPacketParser.class);

  public DHTPacketParser(PacketHandler packetHandler, DHTSocket socket) {
    this.packetHandler = packetHandler;
    this.socket = socket;
  }

  public void parse(byte[] data, InetSocketAddress sender) throws IOException {
    BEntity entity;
    try {
      entity = decoder.decode(data);
    } catch (IOException e) {
      logger.warn("exception during parsing of message", e);
      return;
    } catch (ParseException e) {
      logger.warn("parsing exception", e);
      return;
    }

    if (!(entity instanceof BDictionary)) {
      logger.warn("invalid message format");
      return;
    }
    processDictionary((BDictionary<BEntity>) entity, sender);
  }

  private void processDictionary(BDictionary<BEntity> dictionary, InetSocketAddress sender) throws
      IOException {
    BEntity entity = dictionary.getByString("t");
    if (entity == null || !(entity instanceof BString)) {
      logger.warn("invalid transaction id");
      return;
    }
    TransactionId transactionId = new TransactionId(((BString) entity).getBytes());

    try {
      dispatchToMessageType(dictionary, transactionId, sender);
    } catch (ProtocolError protocolError) {
      ErrorMessage errorMessage =
          new ErrorMessage(transactionId, ErrorMessage.PROTOTOL_ERROR, protocolError.getMessage());
      socket.error(errorMessage, sender);
    }
  }

  private void dispatchToMessageType(BDictionary<BEntity> dictionary, TransactionId transactionId, InetSocketAddress sender) throws
      ProtocolError, IOException {
    BEntity entity;
    entity = dictionary.getByString("y");
    if (entity == null || !(entity instanceof BString)) {
      logger.warn("invalid message type");
      return;
    }
    char messageType = ((BString) entity).toString().charAt(0);

    if (DHTMessage.QUERY_MESSAGE_TYPE == messageType) {
      processQueryMessage(dictionary, transactionId, sender);
    } else if (DHTMessage.RESPONSE_MESSAGE_TYPE == messageType) {
      processResponseMessage(dictionary, transactionId, sender);
    } else if (DHTMessage.ERROR_MESSAGE_TYPE == messageType) {
      logger.info("receiving an error message: " + dictionary);
    } else {
      throw new ProtocolError("unknown message type");
    }
  }

  private void processResponseMessage(BDictionary<BEntity> dictionary, TransactionId transactionId, InetSocketAddress sender)
      throws ProtocolError, IOException {
    BEntity entity = dictionary.getByString("r");
    if (entity == null || !(entity instanceof BDictionary)) {
      throw new ProtocolError("missing reply values");
    }
    BDictionary<BEntity> reply = (BDictionary<BEntity>) entity;

    String queryType = socket.getQueryType(transactionId);
    if (queryType == null) {
      logger.warn("cannot determine query type of transaction id " + transactionId);
      return;
    }

    if (QueryMessage.PING_QUERY.equals(queryType)) {
      processPingReply(reply, transactionId, sender);
    } else if (QueryMessage.FIND_NODE_QUERY.equals(queryType)) {
      processFindNodeReply(reply, transactionId, sender);
    } else if (QueryMessage.GET_PEERS_QUERY.equals(queryType)) {
      processGetPeersReply(reply, transactionId, sender);
    } else if (QueryMessage.ANNOUNCE_PEER_QUERY.equals(queryType)) {

    } else {
      logger.error("unknown query type: " + queryType);
    }
  }

  private void processGetPeersReply(BDictionary<BEntity> reply, TransactionId transactionId, InetSocketAddress sender)
      throws ProtocolError {
    PeerId peerId = extractPeerId(reply);

    BEntity entity = reply.getByString("token");
    if (entity == null || !(entity instanceof BString)) {
      throw new ProtocolError("missing token");
    }
    Token token = new Token(((BString) entity).getBytes());

    // "values" (actual peer matches) takes priority over "nodes" (closer
    // nodes to keep searching) if a reply happens to include both --
    // per BEP 5 a compliant node sends only one or the other, but we'd
    // rather not silently throw away a real answer if some node in the
    // wild doesn't follow that strictly
    entity = reply.getByString("values");
    if (entity != null) {
      if (!(entity instanceof BList)) {
        throw new ProtocolError("invalid values type");
      }
      BList<BEntity> list = (BList<BEntity>) entity;
      TrackerResponse.PeerInfo[] peers = listToPeerInfo(list);
      logger.debug("got get_peers reply with matches from " + sender + " (" + peerId + ") and token " + token);
      packetHandler.getPeersReplyWithMatches(sender, transactionId, peerId, token, peers);
      return;
    }

    entity = reply.getByString("nodes");
    if (entity == null || !(entity instanceof BString)) {
      throw new ProtocolError("missing both values and nodes");
    }
    TrackerResponse.PeerInfo[] nodes = nodeBytesToArray(((BString) entity).getBytes());
    logger.debug("got get_peers reply from " + sender + " (" + peerId + ") and token " + token);
    packetHandler.getPeersReply(sender, transactionId, peerId, token, nodes);
  }

  /**
   * The peers in a get_peers reply. Entries that are not a usable peer
   * (wrong length, port 0) are skipped rather than failing the reply: some
   * nodes send a few, and the rest are still good.
   */
  static TrackerResponse.PeerInfo[] listToPeerInfo(BList<BEntity> list) throws ProtocolError {
    List<TrackerResponse.PeerInfo> peers = new ArrayList<TrackerResponse.PeerInfo>();
    for (BEntity entry : list) {
      if (!(entry instanceof BString)) {
        throw new ProtocolError("invalid entry format");
      }
      byte[] bytes = ((BString) entry).getBytes();
      if (bytes.length != 6) {
        continue;
      }
      try {
        TrackerResponse.PeerInfo peer = TrackerResponse.PeerInfo.fromCompactForm(bytes, 0);
        if (peer != null) {
          peers.add(peer);
        }
      } catch (IllegalArgumentException e) {
        // e.g. port 0
      }
    }
    return peers.toArray(new TrackerResponse.PeerInfo[0]);
  }

  private void processFindNodeReply(BDictionary<BEntity> reply, TransactionId transactionId, InetSocketAddress sender)
      throws ProtocolError, IOException {
    PeerId peerId = extractPeerId(reply);

    BEntity entity = reply.getByString("nodes");
    if (entity == null || !(entity instanceof BString)) {
      throw new ProtocolError("missing nodes");
    }
    TrackerResponse.PeerInfo[] nodes = nodeBytesToArray(((BString) entity).getBytes());

    logger.debug("got a find_node reply from " + sender + " (" + peerId + ")");
    packetHandler.findNodeReply(sender, transactionId, peerId, nodes);
  }

  /** The nodes in a reply; like peers, unusable entries are skipped. */
  static TrackerResponse.PeerInfo[] nodeBytesToArray(byte[] nodeBytes) throws ProtocolError {
    if (nodeBytes.length % 26 != 0) {
      throw new ProtocolError("nodes must be of length n * 26");
    }
    List<TrackerResponse.PeerInfo> nodes = new ArrayList<TrackerResponse.PeerInfo>();
    for (int offset = 0; offset < nodeBytes.length; offset += 26) {
      try {
        TrackerResponse.PeerInfo node = TrackerResponse.PeerInfo.fromCompactLongForm(nodeBytes, offset);
        if (node != null) {
          nodes.add(node);
        }
      } catch (IllegalArgumentException e) {
        // e.g. port 0
      }
    }
    return nodes.toArray(new TrackerResponse.PeerInfo[0]);
  }

  private void processPingReply(BDictionary<BEntity> reply, TransactionId transactionId, InetSocketAddress sender) throws
      ProtocolError {
    PeerId peerId = extractPeerId(reply);
    logger.debug("got a ping reply from " + sender + " (" + peerId + ")");
    packetHandler.pingReply(sender, transactionId, peerId);
  }

  private void processQueryMessage(BDictionary dictionary, TransactionId transactionId, InetSocketAddress sender)
      throws ProtocolError,
      IOException {
    BEntity queryType = dictionary.getByString("q");
    if (queryType == null || !(queryType instanceof BString)) {
      throw new ProtocolError("missing query type");
    }
    String queryTypeString = ((BString) queryType).toString().toLowerCase();

    BEntity queryArguments = dictionary.getByString("a");
    if (queryArguments == null || !(queryArguments instanceof BDictionary)) {
      throw new ProtocolError("missing query arguments");
    }
    BDictionary<BEntity> queryArgumentsDictionary = (BDictionary<BEntity>) queryArguments;

    if (QueryMessage.PING_QUERY.equals(queryTypeString)) {
      processPingQuery(queryArgumentsDictionary, transactionId, sender);
    } else if (QueryMessage.GET_PEERS_QUERY.equals(queryTypeString)) {
      processGetPeersQuery(queryArgumentsDictionary, transactionId, sender);
    } else if (QueryMessage.FIND_NODE_QUERY.equals(queryTypeString)) {
      processFindNodeQuery(queryArgumentsDictionary, transactionId, sender);
    } else if (QueryMessage.ANNOUNCE_PEER_QUERY.equals(queryTypeString)) {
      processAnnouncePeerQuery(queryArgumentsDictionary, transactionId, sender);
    } else {
      throw new ProtocolError("unknown query type");
    }
  }

  private void processAnnouncePeerQuery(BDictionary<BEntity> arguments, TransactionId transactionId,
                                        InetSocketAddress sender) throws ProtocolError, IOException {
    PeerId peerId = extractPeerId(arguments);

    BEntity entity = arguments.getByString("info_hash");
    if (entity == null || !(entity instanceof BString)) {
      throw new ProtocolError("missing info_hash");
    }
    Hash infoHash = toInfoHash(((BString) entity).getBytes());

    entity = arguments.getByString("token");
    if (entity == null || !(entity instanceof BString)) {
      throw new ProtocolError("missing token");
    }
    Token token = new Token(((BString) entity).getBytes());

    entity = arguments.getByString("port");
    if (entity == null || !(entity instanceof BInteger)) {
      throw new ProtocolError("missing port");
    }
    int port = ((BInteger) entity).intValue();

    logger.debug("got announce_peer query from "
        + sender + "(" + peerId + ") for info_hash "
        + infoHash + " with token " + token + " on port " + port);
    packetHandler.announcePeer(sender, transactionId, peerId, infoHash, token, port);
  }

  private void processFindNodeQuery(BDictionary<BEntity> arguments, TransactionId transactionId,
                                    InetSocketAddress sender) throws ProtocolError, IOException {
    PeerId peerId = extractPeerId(arguments);

    BEntity targ = arguments.getByString("target");
    if (targ == null || !(targ instanceof BString)) {
      throw new ProtocolError("missing target");
    }

    PeerId target = toId(((BString) targ).getBytes(), "target");

    logger.debug("got find_node query from " + sender + "(" + peerId + ") for target " + target);
    packetHandler.findNode(sender, transactionId, peerId, target);
  }

  private void processGetPeersQuery(BDictionary<BEntity> arguments, TransactionId transactionId,
                                    InetSocketAddress sender) throws ProtocolError, IOException {
    PeerId peerId = extractPeerId(arguments);

    BEntity infoHash = arguments.getByString("info_hash");
    if (infoHash == null || !(infoHash instanceof BString)) {
      throw new ProtocolError("missing info_hash");
    }
    Hash hash = toInfoHash(((BString) infoHash).getBytes());

    logger.debug("got get_peers query from " + sender + "(" + peerId + ") for info_hash " + hash);
    packetHandler.getPeers(sender, transactionId, peerId, hash);
  }

  private void processPingQuery(BDictionary<BEntity> arguments, TransactionId transactionId,
                                InetSocketAddress sender) throws ProtocolError, IOException {
    PeerId peerId = extractPeerId(arguments);
    logger.debug("got ping query from " + sender + "(" + peerId + ")");
    packetHandler.ping(sender, transactionId, peerId);
  }

  private PeerId extractPeerId(BDictionary<BEntity> arguments) throws ProtocolError {
    BEntity id = arguments.getByString("id");
    if (id == null || !(id instanceof BString)) {
      throw new ProtocolError("missing id");
    }
    return toId(((BString) id).getBytes(), "id");
  }

  /**
   * The info hash as sent: it already is the torrent's hash, so it must
   * not be hashed again (Hash.forByteArray would).
   */
  static Hash toInfoHash(byte[] bytes) throws ProtocolError {
    if (bytes.length != Hash.LENGTH) {
      throw new ProtocolError("invalid info_hash length " + bytes.length);
    }
    return new Hash(bytes);
  }

  static PeerId toId(byte[] bytes, String what) throws ProtocolError {
    if (bytes.length != 20) {
      // some nodes send malformed ids; PeerId would throw on them
      throw new ProtocolError("invalid " + what + " length " + bytes.length);
    }
    return new PeerId(bytes);
  }
}
