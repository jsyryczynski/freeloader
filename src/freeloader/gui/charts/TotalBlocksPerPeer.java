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
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.StackPane;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Stacked bars of the 20 peers that sent us the most blocks, split into seeder and leecher. */
public class TotalBlocksPerPeer extends StackPane {

  private static final int SHOWN = 20;
  private static final String FROM_SEEDS = "torrent.blocks.seed";
  private static final String FROM_LEECHERS = "torrent.blocks.leecher";

  private final CounterRepository counters;
  private final StackedBarChart<String, Number> chart = Charts.stackedBarChart("Remote IP", "Blocks");

  public TotalBlocksPerPeer(CounterRepository counters) {
    this.counters = counters;
    getChildren().add(chart);
  }

  private record PeerBlocks(String peer, long asSeed, long asLeecher) {
    long total() {
      return asSeed + asLeecher;
    }
  }

  /** Redraws the bars from the counters. */
  public void refresh() {
    List<PeerBlocks> top = Stream.concat(counters.getKeys(FROM_SEEDS).stream(), counters.getKeys(FROM_LEECHERS).stream())
        .distinct()
        .map(peer -> new PeerBlocks(peer, blocks(FROM_SEEDS, peer), blocks(FROM_LEECHERS, peer)))
        .sorted(Comparator.comparingLong(PeerBlocks::total).reversed())
        .limit(SHOWN)
        .toList();

    XYChart.Series<String, Number> seeds = new XYChart.Series<>();
    seeds.setName("From seeder");
    XYChart.Series<String, Number> leechers = new XYChart.Series<>();
    leechers.setName("From leecher");
    for (PeerBlocks p : top) {
      seeds.getData().add(new XYChart.Data<>(p.peer(), p.asSeed()));
      leechers.getData().add(new XYChart.Data<>(p.peer(), p.asLeecher()));
    }
    Charts.replaceCategoryData(chart, top.stream().map(PeerBlocks::peer).toList(), seeds, leechers);
  }

  private long blocks(String counter, String peer) {
    return counters.getStatistics(counter, peer).latestValue();
  }
}
