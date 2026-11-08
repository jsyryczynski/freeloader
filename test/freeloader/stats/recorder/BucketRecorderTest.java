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

package freeloader.stats.recorder;

import freeloader.stats.CounterStatistics;
import junit.framework.TestCase;

public class BucketRecorderTest extends TestCase {

  private static final long START = 1000000;

  private BucketRecorder recorder;
  private long counter;
  private long now;

  protected void setUp() {
    recorder = new BucketRecorder(START, "test");
    counter = 0;
    now = START;
  }

  public void testRecentIncreaseFollowsASpeedChange() {
    // a slow minute, then two minutes at 1250 bytes per millisecond
    run(60000, 300);
    run(120000, 1250);
    CounterStatistics statistics = recorder.createCounterStatistics();

    assertEquals(1250, statistics.getRecentIncrease(now, 20000), 1250 * 0.03);
    // the average still includes the slow minute
    assertTrue(statistics.getAverageIncrease() < 1000);
  }

  public void testRecentIncreaseDropsWhenNothingArrives() {
    run(120000, 1250);
    // nothing recorded at all for 30 seconds: the counter didn't change
    now += 30000;
    assertEquals(0, recorder.createCounterStatistics().getRecentIncrease(now, 20000), 0.001);
  }

  public void testRecentIncreaseFallsBackToTheAverageAtFirst() {
    run(3000, 500);
    CounterStatistics statistics = recorder.createCounterStatistics();
    assertEquals(statistics.getAverageIncrease(), statistics.getRecentIncrease(now, 20000), 0.001);
  }

  /** The counter rising at the given bytes per millisecond, recorded every 100 ms. */
  private void run(long millis, long perMilli) {
    for (long elapsed = 0; elapsed < millis; elapsed += 100) {
      now += 100;
      counter += 100 * perMilli;
      recorder.record(now, counter);
    }
  }
}
