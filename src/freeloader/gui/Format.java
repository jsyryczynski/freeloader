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

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Human-readable sizes, rates, durations and dates for the UI. */
final class Format {

  private Format() {
  }

  static String size(long bytes) {
    double kb = bytes / 1024.0;
    if (kb < 1024) {
      return oneDecimal(kb) + " KB";
    }
    double mb = kb / 1024.0;
    if (mb < 1024) {
      return oneDecimal(mb) + " MB";
    }
    return oneDecimal(mb / 1024.0) + " GB";
  }

  /**
   * Every speed is shown in MB/s, like the speed limit, with two
   * decimals below 10 so that slow speeds don't all read 0.
   */
  static String rate(double megabytesPerSecond) {
    return number(megabytesPerSecond < 10 ? "0.00" : "0.0").format(megabytesPerSecond) + " MB/s";
  }

  static String duration(long seconds) {
    if (seconds < 60) {
      return seconds + "s";
    }
    long minutes = seconds / 60;
    if (minutes < 60) {
      return minutes + "m";
    }
    long hours = minutes / 60;
    if (hours < 48) {
      return hours + "h " + (minutes % 60) + "m";
    }
    return (hours / 24) + "d " + (hours % 24) + "h";
  }

  static String percent(double fraction) {
    return oneDecimal(100 * fraction) + "%";
  }

  static String date(long millis) {
    return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(millis));
  }

  /** Numbers always use a dot, whatever the Windows region is set to. */
  static DecimalFormat number(String pattern) {
    return new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
  }

  private static String oneDecimal(double value) {
    return number("0.#").format(value);
  }
}
