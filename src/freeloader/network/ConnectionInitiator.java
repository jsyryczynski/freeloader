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
import freeloader.downloader.TorrentDownload;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ConnectionInitiator {

  private final TorrentDownload torrentDownload;

  // connections being set up (see ConnectionSetup)
  private final Set<InetSocketAddress> pendingConnects = new HashSet<InetSocketAddress>();

  private static final int MAX_NUMBER_OF_PENDING_CONNECTS = 64;

  private static final Logger logger = Logger.getLogger(ConnectionInitiator.class);

  public ConnectionInitiator(TorrentDownload torrentDownload) {
    this.torrentDownload = torrentDownload;
  }

  public synchronized void maintenance() {
    int numberOfAvailableSpots = MAX_NUMBER_OF_PENDING_CONNECTS - pendingConnects.size();
    if (numberOfAvailableSpots < 1) {
      return;
    }

    List<InetSocketAddress> addresses =
        torrentDownload.getPeerManager().getConnectCandidates(Math.max(4, numberOfAvailableSpots));
    for (InetSocketAddress address : addresses) {
      openConnectionTo(address);
    }
  }

  private synchronized void openConnectionTo(final InetSocketAddress remoteAddress) {
    if (hasAlreadyAnOpenConnection(remoteAddress) || !pendingConnects.add(remoteAddress)) {
      return;
    }
    logger.info("trying to start a connection to " + remoteAddress);
    updatePendingCounter();
    ConnectionSetup.connect(remoteAddress, torrentDownload, () -> removeEntry(remoteAddress));
  }

  private boolean hasAlreadyAnOpenConnection(InetSocketAddress remoteAddress) {
    return torrentDownload.getEnvironment().getConnectionRepository().hasConnectionOpenTo(remoteAddress.getAddress());
  }

  private synchronized void removeEntry(InetSocketAddress address) {
    pendingConnects.remove(address);
    updatePendingCounter();
  }

  private void updatePendingCounter() {
    torrentDownload.getCounterRepository().getCounter(
        "network.connections.pending").set(pendingConnects.size());
  }
}
