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
import freeloader.stats.CounterStatistics;
import javafx.scene.chart.XYChart;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

// The download speed over time, with a 5 minute running average on top.
public class RawDownloadRate extends TimeSeriesPanel {

  private static final long AVERAGE_OVER_MS = 5 * 60 * 1000;

  private final CounterLine speed;
  private final XYChart.Series<Number, Number> average = new XYChart.Series<>();

  public RawDownloadRate(CounterRepository counters) {
    super(counters, "MB/s", true);
    CounterStatistics received = counters.getStatistics("network.rawbytes.in");
    // the counter rises in bytes per millisecond; 1000 of those are 1 MB/s
    speed = new CounterLine(received, "Download rate", at -> received.getIncreaseAt(at) / 1000);
    addLine(speed);
    average.setName("5 minute average");
    chart.getData().add(average);
  }

  @Override
  public void refresh() {
    super.refresh();
    average.getData().setAll(runningAverage(speed.series().getData()));
  }

  // Each point becomes the mean of itself and the points up to 5 minutes
  // before it; the points are in time order, so one pass with a queue of
  // the points still in the window is enough.
  private static List<XYChart.Data<Number, Number>> runningAverage(List<XYChart.Data<Number, Number>> points) {
    ArrayDeque<XYChart.Data<Number, Number>> window = new ArrayDeque<>();
    double windowTotal = 0;
    List<XYChart.Data<Number, Number>> averaged = new ArrayList<>(points.size());
    for (XYChart.Data<Number, Number> point : points) {
      long at = point.getXValue().longValue();
      window.addLast(point);
      windowTotal += point.getYValue().doubleValue();
      while (window.peekFirst().getXValue().longValue() <= at - AVERAGE_OVER_MS) {
        windowTotal -= window.removeFirst().getYValue().doubleValue();
      }
      averaged.add(new XYChart.Data<>(at, windowTotal / window.size()));
    }
    return averaged;
  }
}
