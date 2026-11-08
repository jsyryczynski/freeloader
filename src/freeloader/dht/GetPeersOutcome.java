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

package freeloader.dht;

import freeloader.tracker.TrackerResponse;

/**
 * The result of a single outbound get_peers query (BEP 5). A DHT node
 * replying to get_peers sends back exactly one of two things: a "values"
 * list of peers that are actually downloading/seeding the requested
 * torrent, or a "nodes" list of other DHT nodes that are closer to the
 * target info hash to continue the search with. This class represents
 * whichever one came back.
 */
public class GetPeersOutcome {

  public static final GetPeersOutcome EMPTY = forNodes(new TrackerResponse.PeerInfo[0]);

  private final boolean match;
  private final TrackerResponse.PeerInfo[] entries;

  private GetPeersOutcome(boolean match, TrackerResponse.PeerInfo[] entries) {
    this.match = match;
    this.entries = entries;
  }

  public static GetPeersOutcome forPeers(TrackerResponse.PeerInfo[] peers) {
    return new GetPeersOutcome(true, peers);
  }

  public static GetPeersOutcome forNodes(TrackerResponse.PeerInfo[] nodes) {
    return new GetPeersOutcome(false, nodes);
  }

  public boolean isMatch() {
    return match;
  }

  public TrackerResponse.PeerInfo[] getPeers() {
    return match ? entries : new TrackerResponse.PeerInfo[0];
  }

  public TrackerResponse.PeerInfo[] getNodes() {
    return match ? new TrackerResponse.PeerInfo[0] : entries;
  }
}
