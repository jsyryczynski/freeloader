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

import freeloader.torrent.PeerId;
import freeloader.tracker.TrackerResponse;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;

public class TrackerState {

  private final PeerId ourId;

  private final int listeningPort;

  private final Set<TrackerResponse.PeerInfo> peerInfos;

  public PeerId getOurId() {
    return ourId;
  }

  private TrackerState(PeerId ourId, int listeningPort, Set<TrackerResponse.PeerInfo> peerInfos) {
    this.ourId = ourId;
    this.listeningPort = listeningPort;
    this.peerInfos = peerInfos;
  }

  public int getListeningPort() {
    return listeningPort;
  }

  public Set<TrackerResponse.PeerInfo> getPeers() {
    return peerInfos;
  }

  public static TrackerState createNew(int listeningPort) {
    Set<TrackerResponse.PeerInfo> peers = new HashSet<TrackerResponse.PeerInfo>();
    InetSocketAddress routerAddress = new InetSocketAddress("router.bittorrent.com", 6881);
    peers.add(new TrackerResponse.PeerInfo(PeerId.createRandom(), routerAddress));

    return new TrackerState(PeerId.createRandom(), listeningPort, peers);
  }
}
