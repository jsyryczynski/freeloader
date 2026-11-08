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


package freeloader.util;

import java.net.MalformedURLException;
import java.net.URL;

public final class Urls {

  private Urls() {
  }

  /**
   * Parses a URL as leniently as java.net.URL always has. Tracker URLs in
   * real torrents aren't always valid URIs (spaces, '|', a second '?'),
   * and URI.create(...).toURL(), the replacement Java recommends, rejects
   * those, which would quietly lose the tracker.
   */
  @SuppressWarnings("deprecation")
  public static URL lenient(String url) throws MalformedURLException {
    return new URL(url);
  }
}
