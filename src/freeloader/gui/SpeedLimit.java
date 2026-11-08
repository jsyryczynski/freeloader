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

import freeloader.network.DownloadRateLimiter;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.HBox;
import javafx.geometry.Pos;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.Optional;
import java.util.prefs.Preferences;

/**
 * The total download speed limit, in MB/s (the unit every speed in the
 * app is shown in), remembered across restarts in the same per-user
 * Preferences store as the other GUI settings. Off (0) by default.
 */
final class SpeedLimit {

  private static final String KEY = "downloadLimitMBps";
  private static final String LAST_KEY = "lastDownloadLimitMBps";
  // the limit used to be set in Mbit/s; read once to carry it over
  private static final String OLD_KEY = "downloadLimitMbit";
  private static final String OLD_LAST_KEY = "lastDownloadLimitMbit";
  private static final Preferences PREFS = Preferences.userNodeForPackage(SpeedLimit.class);

  // offered when the limit is first switched on: leaves some headroom on
  // a typical 20 Mbit line
  private static final double SUGGESTED_MBPS = 2;
  private static final double MIN_MBPS = 0.1;
  private static final double MAX_MBPS = 1000;
  private static final double STEP_MBPS = 0.1;
  private static final long BYTES_PER_MB = 1000000;

  private SpeedLimit() {
  }

  /** The saved limit in MB/s, 0 if off. */
  static double getMegabytesPerSecond() {
    migrate();
    return Math.max(0, PREFS.getDouble(KEY, 0));
  }

  static void apply(DownloadRateLimiter limiter) {
    limiter.setBytesPerSecond(Math.round(getMegabytesPerSecond() * BYTES_PER_MB));
  }

  /** Shows the setting; applies and saves it if confirmed. */
  static void showDialog(Window owner, DownloadRateLimiter limiter) {
    double current = getMegabytesPerSecond();
    CheckBox enabled = new CheckBox("Limit download speed to");
    enabled.setSelected(current > 0);
    Spinner<Double> mbps = new Spinner<Double>(MIN_MBPS, MAX_MBPS,
        current > 0 ? current : PREFS.getDouble(LAST_KEY, SUGGESTED_MBPS), STEP_MBPS);
    // shown the way the rest of the window shows numbers, and typed with
    // either a dot or a comma whatever the system language
    mbps.getValueFactory().setConverter(new StringConverter<Double>() {
      public String toString(Double value) {
        return value == null ? "" : Format.number("0.##").format(value);
      }

      public Double fromString(String text) {
        try {
          return parse(text);
        } catch (NumberFormatException e) {
          return mbps.getValue();
        }
      }
    });
    mbps.setEditable(true);
    mbps.setPrefWidth(110);
    mbps.disableProperty().bind(enabled.selectedProperty().not());
    HBox row = new HBox(8, enabled, mbps, new Label("MB/s"));
    row.setAlignment(Pos.CENTER_LEFT);

    Dialog<ButtonType> dialog = new Dialog<ButtonType>();
    dialog.initOwner(owner);
    dialog.setTitle("Speed Limit");
    dialog.setHeaderText("Total download speed, for all downloads together");
    dialog.getDialogPane().setContent(row);
    dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

    Optional<ButtonType> result = dialog.showAndWait();
    if (!result.isPresent() || result.get() != ButtonType.OK) {
      return;
    }
    // a number typed into the spinner isn't committed until Enter or
    // focus loss, which pressing OK doesn't reliably cause
    try {
      mbps.getValueFactory().setValue(parse(mbps.getEditor().getText()));
    } catch (NumberFormatException e) {
      // keep the last valid value
    }
    double chosen = enabled.isSelected() ? mbps.getValue() : 0;
    PREFS.putDouble(KEY, chosen);
    if (chosen > 0) {
      PREFS.putDouble(LAST_KEY, chosen);
    }
    apply(limiter);
  }

  /** Accepts both 1.5 and 1,5. */
  static double parse(String text) {
    return Double.parseDouble(text.trim().replace(',', '.'));
  }

  /** Converts a limit saved in Mbit/s to MB/s (8 bits to a byte). */
  private static void migrate() {
    if (PREFS.get(OLD_KEY, null) != null) {
      PREFS.putDouble(KEY, PREFS.getInt(OLD_KEY, 0) / 8.0);
      PREFS.remove(OLD_KEY);
    }
    if (PREFS.get(OLD_LAST_KEY, null) != null) {
      PREFS.putDouble(LAST_KEY, PREFS.getInt(OLD_LAST_KEY, 0) / 8.0);
      PREFS.remove(OLD_LAST_KEY);
    }
  }
}
