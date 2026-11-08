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

import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.BInteger;
import freeloader.bencoding.BString;
import freeloader.bencoding.ParseException;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Peer exchange (PEX, BEP 11): connected peers regularly tell each other
 * which other peers they're connected to. Those are live, reachable
 * peers, unlike the often stale lists from the DHT, so in a big swarm
 * they're the best source of new connections.
 *
 * We only listen: our extension handshake says we'd like PEX messages
 * (under OUR_UT_PEX_ID), and the "added" peers in them go to the
 * download's PeerManager. We don't send any.
 */
public final class PeerExchange {

  public static final String UT_PEX = "ut_pex";
  /** The id peers use for the PEX messages they send us. */
  public static final int OUR_UT_PEX_ID = 1;

  // a PEX message carries up to 50 added peers; anything far beyond that
  // isn't a well-behaved peer
  private static final int MAX_PEERS_PER_MESSAGE = 200;
  private static final int COMPACT_IPV4_LENGTH = 6;

  private PeerExchange() {
  }

  /** Our extension handshake: we take PEX messages, and listen on this port. */
  public static byte[] handshakePayload(int listeningPort) {
    BDictionary<BEntity> supported = new BDictionary<BEntity>();
    supported.put(new BString(UT_PEX), new BInteger(OUR_UT_PEX_ID));
    BDictionary<BEntity> handshake = new BDictionary<BEntity>();
    handshake.put(new BString("m"), supported);
    handshake.put(new BString("p"), new BInteger(listeningPort));
    try {
      return handshake.encode();
    } catch (IOException e) {
      throw new IllegalStateException("encoding to memory failed", e);
    }
  }

  /** The newly added (IPv4) peers in a PEX message; empty if it can't be read. */
  public static List<InetSocketAddress> addedPeers(byte[] payload) {
    List<InetSocketAddress> result = new ArrayList<InetSocketAddress>();
    BEntity entity;
    try {
      entity = new BDecoder().decode(payload);
    } catch (IOException | ParseException e) {
      return result;
    }
    if (!(entity instanceof BDictionary)) {
      return result;
    }
    BEntity added = ((BDictionary<?>) entity).getByString("added");
    if (!(added instanceof BString)) {
      return result;
    }
    byte[] compact = ((BString) added).getBytes();
    for (int offset = 0; offset + COMPACT_IPV4_LENGTH <= compact.length
        && result.size() < MAX_PEERS_PER_MESSAGE; offset += COMPACT_IPV4_LENGTH) {
      int port = ((compact[offset + 4] & 0xff) << 8) | (compact[offset + 5] & 0xff);
      if (port == 0) {
        continue;
      }
      try {
        InetAddress address = InetAddress.getByAddress(Arrays.copyOfRange(compact, offset, offset + 4));
        result.add(new InetSocketAddress(address, port));
      } catch (UnknownHostException e) {
        // can't happen for a 4-byte address
      }
    }
    return result;
  }
}
