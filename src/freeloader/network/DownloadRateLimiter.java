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

package freeloader.network;

import freeloader.util.SystemTimeSource;
import freeloader.util.TimeSource;

/**
 * A token bucket for the total download rate across all connections.
 * Each socket read first asks for permission to read some bytes; once
 * the budget for the moment is spent, connections stop being read until
 * it refills, so TCP flow control slows the senders down and the line
 * itself stays under the limit (rather than data arriving anyway and
 * just being processed later).
 *
 * A limit of 0 means unlimited.
 */
public class DownloadRateLimiter {

  // how much may be read in one go after a quiet spell: a tenth of a
  // second's worth keeps the rate smooth without starving small reads
  private static final double BURST_SECONDS = 0.1;
  private static final long MIN_BURST_BYTES = 16 * 1024;

  private TimeSource timeSource = SystemTimeSource.INSTANCE;

  private long bytesPerSecond = 0;
  private double available = 0;
  private long lastRefill;

  public synchronized void setBytesPerSecond(long bytesPerSecond) {
    this.bytesPerSecond = Math.max(0, bytesPerSecond);
    lastRefill = timeSource.getTime();
    available = Math.min(available, burstBytes());
  }

  public synchronized long getBytesPerSecond() {
    return bytesPerSecond;
  }

  public synchronized boolean isLimited() {
    return bytesPerSecond > 0;
  }

  /** Grants up to wanted bytes (all of them when unlimited); 0 means wait. */
  public synchronized int acquire(int wanted) {
    if (bytesPerSecond == 0) {
      return wanted;
    }
    refill();
    int granted = (int) Math.min(wanted, Math.floor(available));
    available -= granted;
    return granted;
  }

  /** Returns what was granted but not actually read. */
  public synchronized void giveBack(int unused) {
    if (bytesPerSecond > 0 && unused > 0) {
      available = Math.min(burstBytes(), available + unused);
    }
  }

  /** Whether reading could continue right now. */
  public synchronized boolean hasBudget() {
    if (bytesPerSecond == 0) {
      return true;
    }
    refill();
    return available >= 1;
  }

  private void refill() {
    long now = timeSource.getTime();
    available = Math.min(burstBytes(), available + (now - lastRefill) * bytesPerSecond / 1000.0);
    lastRefill = now;
  }

  private double burstBytes() {
    return Math.max(MIN_BURST_BYTES, bytesPerSecond * BURST_SECONDS);
  }

  void setTimeSource(TimeSource timeSource) {
    this.timeSource = timeSource;
    lastRefill = timeSource.getTime();
  }
}
