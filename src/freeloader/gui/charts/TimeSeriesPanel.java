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
import javafx.scene.chart.LineChart;
import javafx.scene.layout.StackPane;

import java.util.ArrayList;
import java.util.List;

// A time chart with one line per counter. Subclasses choose the counters
// (addCounter, or addLine for a computed value) and the value axis label.
abstract class TimeSeriesPanel extends StackPane {

  protected final LineChart<Number, Number> chart;
  private final CounterRepository counters;
  private final List<CounterLine> lines = new ArrayList<>();

  TimeSeriesPanel(CounterRepository counters, String valueLabel, boolean showLegend) {
    this.counters = counters;
    chart = Charts.timeSeriesChart(valueLabel, showLegend);
    getChildren().add(chart);
  }

  protected void addCounter(String counter, String lineName) {
    addLine(new CounterLine(counters.getStatistics(counter), lineName));
  }

  protected void addLine(CounterLine line) {
    lines.add(line);
    chart.getData().add(line.series());
  }

  public void refresh() {
    lines.forEach(CounterLine::refresh);
  }
}
