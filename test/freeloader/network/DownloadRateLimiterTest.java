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

import freeloader.util.AdjustableTimeSource;
import junit.framework.TestCase;

public class DownloadRateLimiterTest extends TestCase {

  private final AdjustableTimeSource time = new AdjustableTimeSource();
  private final DownloadRateLimiter limiter = new DownloadRateLimiter();

  protected void setUp() {
    time.setTime(1000000);
    limiter.setTimeSource(time);
  }

  public void testUnlimitedGrantsEverything() {
    assertEquals(65536, limiter.acquire(65536));
    assertEquals(65536, limiter.acquire(65536));
    assertTrue(limiter.hasBudget());
  }

  public void testLimitsTheSustainedRate() {
    limiter.setBytesPerSecond(100000); // burst is 10000 bytes
    long total = 0;
    for (int tick = 0; tick < 100; tick++) { // 10 seconds in 100ms steps
      time.increaseTime(100);
      int granted;
      while ((granted = limiter.acquire(65536)) > 0) {
        total += granted;
      }
      assertFalse(limiter.hasBudget());
    }
    // 10s at 100000 bytes/s, give or take one burst
    assertTrue("got " + total, total >= 990000 && total <= 1010000);
  }

  public void testBudgetDoesNotPileUpWhileIdle() {
    limiter.setBytesPerSecond(100000);
    time.increaseTime(60000); // a minute of nothing
    assertEquals(16384, limiter.acquire(65536)); // just one burst (the 16KB minimum)
    assertEquals(0, limiter.acquire(65536));
  }

  public void testUnusedGrantCanBeGivenBack() {
    limiter.setBytesPerSecond(100000);
    time.increaseTime(1000);
    int granted = limiter.acquire(65536);
    assertEquals(0, limiter.acquire(1));
    limiter.giveBack(granted);
    assertEquals(granted, limiter.acquire(65536));
  }

  public void testZeroTurnsTheLimitOff() {
    limiter.setBytesPerSecond(100000);
    time.increaseTime(1000);
    limiter.acquire(65536);
    assertEquals(0, limiter.acquire(65536));
    limiter.setBytesPerSecond(0);
    assertEquals(65536, limiter.acquire(65536));
  }
}
