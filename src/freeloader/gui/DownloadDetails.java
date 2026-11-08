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

package freeloader.gui;

import atlantafx.base.theme.Styles;
import freeloader.downloader.TorrentDownload;
import freeloader.gui.charts.BlockDownload;
import freeloader.gui.charts.BlockOrigin;
import freeloader.gui.charts.ConnectionTypes;
import freeloader.gui.charts.DownloadRatePerPeer;
import freeloader.gui.charts.OpenConnections;
import freeloader.gui.charts.PiecesStats;
import freeloader.gui.charts.RawDownloadRate;
import freeloader.gui.charts.RemotePeerCompletion;
import freeloader.gui.charts.TotalBlocksPerPeer;
import freeloader.stats.CounterRepository;
import freeloader.storage.BitField;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Under the table, for the selected running download: an overview tab and
// one tab per statistics chart. Made once per download and kept while it
// runs, so a chart keeps its history when another row is selected.
class DownloadDetails {

  private final TorrentDownload torrent;
  private final TabPane tabs = new TabPane();
  // what refresh() updates besides the overview: one per chart
  private final List<Runnable> chartRefreshes = new ArrayList<>();

  private final Label pieces = new Label();
  private final Label downloaded = new Label();
  private final Label peers = new Label();
  private final Label knownPeers = new Label();
  private final Label peerSearch = new Label();

  DownloadDetails(TorrentDownload torrent) {
    this.torrent = torrent;
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
    tabs.getTabs().add(new Tab("Overview", createOverview()));

    CounterRepository counters = torrent.getCounterRepository();
    RawDownloadRate rate = new RawDownloadRate(counters);
    addChart("Download Rate", rate, rate::refresh);
    DownloadRatePerPeer ratePerPeer = new DownloadRatePerPeer(counters);
    addChart("Rates Per Peer", ratePerPeer, ratePerPeer::refresh);
    BlockDownload blocks = new BlockDownload(counters);
    addChart("Blocks", blocks, blocks::refresh);
    TotalBlocksPerPeer blocksPerPeer = new TotalBlocksPerPeer(counters);
    addChart("Blocks Per Peer", blocksPerPeer, blocksPerPeer::refresh);
    BlockOrigin origin = new BlockOrigin(counters);
    addChart("Block Origin", origin, origin::refresh);
    PiecesStats pieceStats = new PiecesStats(counters);
    addChart("Pieces", pieceStats, pieceStats::refresh);
    OpenConnections connections = new OpenConnections(counters);
    addChart("Open Connections", connections, connections::refresh);
    ConnectionTypes connectionTypes = new ConnectionTypes(counters);
    addChart("Connection Types", connectionTypes, connectionTypes::refresh);
    RemotePeerCompletion completion = new RemotePeerCompletion(torrent);
    addChart("Peer Completion", completion, completion::refresh);

    refresh();
  }

  Node getNode() {
    return tabs;
  }

  private void addChart(String title, Node chart, Runnable refresh) {
    tabs.getTabs().add(new Tab(title, chart));
    chartRefreshes.add(refresh);
  }

  private Node createOverview() {
    GridPane grid = new GridPane();
    grid.setHgap(16);
    grid.setVgap(8);
    grid.setPadding(new Insets(16));
    ColumnConstraints labels = new ColumnConstraints();
    ColumnConstraints values = new ColumnConstraints();
    values.setHgrow(Priority.ALWAYS);
    grid.getColumnConstraints().addAll(labels, values);

    int row = 0;
    addOverviewRow(grid, row++, "Name", new Label(torrent.getMetaInfo().getName()));
    addOverviewRow(grid, row++, "Saving to",
        new Label(torrent.getConfiguration().getTargetDirectory().getAbsolutePath()));
    addOverviewRow(grid, row++, "Size", new Label(Format.size(torrent.getMetaInfo().getTotalLength())));
    addOverviewRow(grid, row++, "Pieces", pieces);
    addOverviewRow(grid, row++, "Downloaded", downloaded);
    addOverviewRow(grid, row++, "Connected peers", peers);
    addOverviewRow(grid, row++, "Known peers", knownPeers);
    addOverviewRow(grid, row++, "Peer search", peerSearch);
    addOverviewRow(grid, row++, "Listening port",
        new Label(Integer.toString(torrent.getConfiguration().getListeningPort())));
    addOverviewRow(grid, row, "Info hash", new Label(torrent.getInfoHash().toString()));
    return grid;
  }

  private static void addOverviewRow(GridPane grid, int row, String title, Label value) {
    Label titleLabel = new Label(title);
    titleLabel.getStyleClass().add(Styles.TEXT_MUTED);
    titleLabel.setMinWidth(Region.USE_PREF_SIZE);
    value.setWrapText(true);
    grid.add(titleLabel, 0, row);
    grid.add(value, 1, row);
  }

  /** Updates the overview and every chart. */
  void refresh() {
    BitField have = torrent.getPieceManager().getValidPieces();
    pieces.setText(have.getAvailablePieceCount() + " of " + have.getPieceCount());
    downloaded.setText(Format.size(torrent.getBytesDownloaded()));
    peers.setText(Integer.toString(torrent.getAllValidConnections().size()));
    knownPeers.setText(Integer.toString(torrent.getPeerManager().distinctPeersSeen()));
    if (torrent.isSearchingForPeers()) {
      peerSearch.setText("searching trackers and DHT...");
    } else if (torrent.getLastPeerSearchTime() == 0) {
      peerSearch.setText("not run yet");
    } else {
      peerSearch.setText("last at " + new SimpleDateFormat("HH:mm:ss").format(new Date(torrent.getLastPeerSearchTime()))
          + ", found " + torrent.getLastPeerSearchFound() + " peer(s)");
    }

    chartRefreshes.forEach(Runnable::run);
  }
}
