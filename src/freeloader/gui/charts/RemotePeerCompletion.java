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

package freeloader.gui.charts;

import freeloader.downloader.TorrentDownload;
import freeloader.storage.BitField;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

// How far along the connected peers are: a bar per 5% step of completion
// ("40" = from 40% up to 45%), complete peers counted in the top bar.
public class RemotePeerCompletion extends StackPane {

  private static final int STEP = 5;
  private static final int BARS = 100 / STEP;
  private static final List<String> LABELS =
      IntStream.range(0, BARS).mapToObj(bar -> Integer.toString(bar * STEP)).toList();

  private final TorrentDownload torrent;
  private final BarChart<String, Number> chart = Charts.barChart("Completion % (5% buckets)", "Peers");

  public RemotePeerCompletion(TorrentDownload torrent) {
    this.torrent = torrent;
    getChildren().add(chart);
  }

  /** Redraws the bars from the open connections. */
  public void refresh() {
    int[] peersPerBar = new int[BARS];
    torrent.getAllValidConnections().stream()
        .filter(c -> c.isOpen())
        .map(c -> c.getRemoteBitField())
        .filter(Objects::nonNull)
        .mapToInt(RemotePeerCompletion::bar)
        .forEach(bar -> peersPerBar[bar]++);

    XYChart.Series<String, Number> bars = new XYChart.Series<>();
    for (int bar = 0; bar < BARS; bar++) {
      bars.getData().add(new XYChart.Data<>(LABELS.get(bar), peersPerBar[bar]));
    }
    Charts.replaceCategoryData(chart, LABELS, bars);
  }

  private static int bar(BitField have) {
    int percent = (int) (100L * have.getAvailablePieceCount() / have.getPieceCount());
    return Math.min(BARS - 1, percent / STEP);
  }
}
