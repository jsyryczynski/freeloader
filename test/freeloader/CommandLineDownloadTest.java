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

package freeloader;

import junit.framework.TestCase;

public class CommandLineDownloadTest extends TestCase {

  public void testSizes() {
    assertEquals("512 B", CommandLineDownload.size(512));
    assertEquals("1.5 KB", CommandLineDownload.size(1500));
    assertEquals("2.1 MB", CommandLineDownload.size(2100000));
    assertEquals("6.7 GB", CommandLineDownload.size(6655619072L));
  }

  public void testRatesAreAlwaysInMegabytesPerSecond() {
    assertEquals("0.00 MB/s", CommandLineDownload.rate(0));
    assertEquals("0.05 MB/s", CommandLineDownload.rate(0.05));
    assertEquals("1.25 MB/s", CommandLineDownload.rate(1.25));
    assertEquals("12.3 MB/s", CommandLineDownload.rate(12.345));
  }

  public void testSpeedLimitIsInMegabytesPerSecond() {
    assertEquals(1.5, Freeloader.parseLimit("1.5"), 0);
    assertEquals(1.5, Freeloader.parseLimit("1,5"), 0);
    assertEquals(10.0, Freeloader.parseLimit("10"), 0);
  }

  public void testDurations() {
    assertEquals("42s", CommandLineDownload.duration(42));
    assertEquals("8m 12s", CommandLineDownload.duration(492));
    assertEquals("2h 5m", CommandLineDownload.duration(2 * 3600 + 5 * 60 + 30));
  }
}
