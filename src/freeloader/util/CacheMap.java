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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A map that holds at most a given number of entries and forgets the
 * oldest one to make room; with lastUsedLast, "oldest" means the one used
 * least recently.
 */
public class CacheMap<K, V> extends LinkedHashMap<K, V> {

  private final int capacity;

  public CacheMap(int capacity) {
    this(capacity, false);
  }

  public CacheMap(int capacity, boolean lastUsedLast) {
    super(16, 0.75f, lastUsedLast);
    this.capacity = capacity;
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
    return size() > capacity;
  }
}
