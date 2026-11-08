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

import junit.framework.TestCase;

import java.util.Locale;

public class FormatTest extends TestCase {

  /** Numbers use a dot whatever the region is set to, e.g. one with a decimal comma. */
  public void testNumbersAlwaysUseADot() {
    Locale before = Locale.getDefault();
    try {
      Locale.setDefault(Locale.GERMANY);
      assertEquals("1.25 MB/s", Format.rate(1.25));
      assertEquals("12.3 MB/s", Format.rate(12.345));
      assertEquals("1.5", Format.number("0.##").format(1.5));
    } finally {
      Locale.setDefault(before);
    }
  }
}
