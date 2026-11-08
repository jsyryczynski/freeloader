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

import freeloader.stats.CounterRepository;
import freeloader.util.SystemTimeSource;
import freeloader.util.TimeSource;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.StackPane;

import java.util.Comparator;
import java.util.List;

/** Bar chart of the 20 peers sending to us fastest, measured over the last minute. */
public class DownloadRatePerPeer extends StackPane {

  private static final int SHOWN = 20;
  private static final long WINDOW_MS = 60 * 1000;
  private static final String COUNTER = "network.rawbytes.in";

  private final CounterRepository counters;
  private final BarChart<String, Number> chart = Charts.barChart("Remote IP", "MB/s");
  private TimeSource clock = SystemTimeSource.INSTANCE;

  public DownloadRatePerPeer(CounterRepository counters) {
    this.counters = counters;
    getChildren().add(chart);
  }

  private record PeerRate(String peer, double megabytesPerSecond) {}

  /** Redraws the bars from the counters. */
  public void refresh() {
    long to = clock.getTime();
    List<PeerRate> fastest = counters.getKeys(COUNTER).stream()
        .map(peer -> {
          var bytes = counters.getStatistics(COUNTER, peer);
          long received = bytes.getValueAt(to) - bytes.getValueAt(to - WINDOW_MS);
          return new PeerRate(peer, received * 1000.0 / WINDOW_MS / 1000000);
        })
        .sorted(Comparator.comparingDouble(PeerRate::megabytesPerSecond).reversed())
        .limit(SHOWN)
        .toList();

    XYChart.Series<String, Number> bars = new XYChart.Series<>();
    fastest.forEach(p -> bars.getData().add(new XYChart.Data<>(p.peer(), p.megabytesPerSecond())));
    Charts.replaceCategoryData(chart, fastest.stream().map(PeerRate::peer).toList(), bars);
  }
}
