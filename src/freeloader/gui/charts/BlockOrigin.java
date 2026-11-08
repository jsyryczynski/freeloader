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
import javafx.scene.chart.PieChart;
import javafx.scene.layout.StackPane;

/** Share of downloaded blocks that came from leechers vs. seeders. */
public class BlockOrigin extends StackPane {

  private final CounterRepository counters;
  private final PieChart.Data fromLeechers = new PieChart.Data("From leechers", 0);
  private final PieChart.Data fromSeeders = new PieChart.Data("From seeders", 0);

  public BlockOrigin(CounterRepository counters) {
    this.counters = counters;
    PieChart chart = new PieChart();
    chart.setAnimated(false);
    chart.getData().addAll(fromLeechers, fromSeeders);
    getChildren().add(chart);
  }

  public void refresh() {
    // update the slices in place -- replacing them rebuilds the legend
    fromLeechers.setPieValue(counters.getStatistics("torrent.blocks.leecher").latestValue());
    fromSeeders.setPieValue(counters.getStatistics("torrent.blocks.seed").latestValue());
  }
}
