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

import freeloader.torrent.Hash;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

// Sends one get_peers query (BEP 5) to a DHT node. The future completes
// with the reply, or with GetPeersOutcome.EMPTY when the query fails or
// times out. It is an interface so DHTPeerFinder can be tested without a
// real UDP socket.
public interface GetPeersSender {
  CompletableFuture<GetPeersOutcome> send(InetSocketAddress address, Hash infoHash);
}
