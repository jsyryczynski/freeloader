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

import javafx.collections.FXCollections;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.chart.XYChart;
import javafx.util.StringConverter;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Shared construction/styling for the download detail charts, so all of
 * them look alike: no animations (data is replaced wholesale every few
 * seconds, animating that is just noise), no point markers on line
 * charts, and HH:mm:ss labels on time axes. Colors come from the active
 * AtlantaFX theme.
 */
final class Charts {

  private Charts() {
  }

  static LineChart<Number, Number> timeSeriesChart(String valueLabel, boolean legendVisible) {
    NumberAxis timeAxis = new NumberAxis();
    timeAxis.setForceZeroInRange(false);
    timeAxis.setMinorTickVisible(false);
    timeAxis.setTickLabelFormatter(new TimeLabelConverter());

    NumberAxis valueAxis = new NumberAxis();
    valueAxis.setLabel(valueLabel);

    LineChart<Number, Number> chart = new LineChart<Number, Number>(timeAxis, valueAxis);
    chart.setAnimated(false);
    chart.setCreateSymbols(false);
    chart.setLegendVisible(legendVisible);
    return chart;
  }

  static BarChart<String, Number> barChart(String categoryLabel, String valueLabel) {
    BarChart<String, Number> chart = new BarChart<String, Number>(
        categoryAxis(categoryLabel), valueAxis(valueLabel));
    chart.setAnimated(false);
    chart.setLegendVisible(false);
    chart.setCategoryGap(4);
    chart.setBarGap(0);
    return chart;
  }

  static StackedBarChart<String, Number> stackedBarChart(String categoryLabel, String valueLabel) {
    StackedBarChart<String, Number> chart = new StackedBarChart<String, Number>(
        categoryAxis(categoryLabel), valueAxis(valueLabel));
    chart.setAnimated(false);
    chart.setCategoryGap(4);
    return chart;
  }

  /**
   * Category charts get confused (stale or overlapping bars) when the set
   * of categories changes between updates unless the axis is told the new
   * categories up front, before the data is swapped in.
   */
  @SafeVarargs
  static void replaceCategoryData(
      XYChart<String, Number> chart, List<String> categories, XYChart.Series<String, Number>... series) {
    ((CategoryAxis) chart.getXAxis()).setCategories(FXCollections.observableArrayList(categories));
    chart.getData().setAll(series);
  }

  private static CategoryAxis categoryAxis(String label) {
    CategoryAxis axis = new CategoryAxis();
    axis.setLabel(label);
    axis.setTickLabelRotation(-90);
    return axis;
  }

  private static NumberAxis valueAxis(String label) {
    NumberAxis axis = new NumberAxis();
    axis.setLabel(label);
    return axis;
  }

  private static class TimeLabelConverter extends StringConverter<Number> {
    private final SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");

    public String toString(Number millis) {
      return format.format(new Date(millis.longValue()));
    }

    public Number fromString(String string) {
      throw new UnsupportedOperationException();
    }
  }
}
