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

import freeloader.stats.CounterStatistics;
import freeloader.util.SystemTimeSource;
import freeloader.util.TimeSource;
import javafx.scene.chart.XYChart;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;

// One line of a time chart, filled from a counter's recorded history
// (x = epoch millis). Each refresh adds only the points recorded since
// the last one; every few minutes the line is redrawn from scratch,
// because the statistics merge old samples into coarser ones over time.
public final class CounterLine {

  private static final long REDRAW_EVERY_MS = 5 * 60 * 1000;

  private final CounterStatistics history;
  private final LongToDoubleFunction valueAt;
  private final XYChart.Series<Number, Number> line = new XYChart.Series<>();
  private TimeSource clock = SystemTimeSource.INSTANCE;
  private long nextPoint;
  private long redrawnAt = Long.MIN_VALUE;

  /** Plots the counter's value. */
  public CounterLine(CounterStatistics history, String name) {
    this(history, name, history::getValueAt);
  }

  /** Plots whatever valueAt makes of the counter at each point in time. */
  public CounterLine(CounterStatistics history, String name, LongToDoubleFunction valueAt) {
    this.history = history;
    this.valueAt = valueAt;
    line.setName(name);
  }

  public XYChart.Series<Number, Number> series() {
    return line;
  }

  public void refresh() {
    long now = clock.getTime();
    if (redrawnAt == Long.MIN_VALUE || now - redrawnAt > REDRAW_EVERY_MS) {
      line.getData().clear();
      nextPoint = history.oldestData();
      redrawnAt = now;
    }
    // the newest interval is still filling up and would show as a dip
    long upTo = now - history.intervalResolutionAt(now);
    List<XYChart.Data<Number, Number>> added = new ArrayList<>();
    for (; nextPoint < upTo; nextPoint += history.intervalResolutionAt(nextPoint)) {
      added.add(new XYChart.Data<>(nextPoint, valueAt.applyAsDouble(nextPoint)));
    }
    line.getData().addAll(added);
  }
}
