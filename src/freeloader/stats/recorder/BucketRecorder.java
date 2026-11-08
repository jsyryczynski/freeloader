/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2006 Patrick Moor <patrick@moor.ws>
 * Modified by Jan Syryczyński, 2022-2026.
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

public class BucketRecorder implements Recorder {
  // 15 minutes @ 5 second interval
  private Bucket secondBuckets;

  // 60 minutes @ 30 second interval
  private Bucket tenSecondBuckets;

  // 10 hours @ 2 minute interval
  private Bucket minuteBuckets;

  private final String name;

  public BucketRecorder(long startTime, String name) {
    secondBuckets = new Bucket(15 * 12, 5 * 1000, startTime);
    tenSecondBuckets = new Bucket(60 * 2, 30 * 1000, startTime);
    minuteBuckets = new Bucket(10 * 30, 60 * 1000, startTime);
    this.name = name;
  }

  public synchronized void record(long time, long value) {
    secondBuckets.addValue(time, value);
    tenSecondBuckets.addValue(time, value);
    minuteBuckets.addValue(time, value);
  }

  private Bucket determineBucketToUse(long time) {
    if (time >= secondBuckets.getOldestTime()) {
      return secondBuckets;
    } else if (time >= tenSecondBuckets.getOldestTime()) {
      return tenSecondBuckets;
    } else {
      return minuteBuckets;
    }
  }

  public CounterStatistics createCounterStatistics() {
    return new CounterStatistics() {
      public long latestValue() {
        return secondBuckets.getLatestValue();
      }

      public long getValueAt(long time) {
        Bucket bucketToUse = determineBucketToUse(time);
        return bucketToUse.get(time);
      }

      public double getIncreaseAt(long time) {
        Bucket bucketToUse = determineBucketToUse(time);
        return bucketToUse.getDerivative(time);
      }

      public double getAverageIncrease() {
        long oldest = secondBuckets.get(secondBuckets.getOldestTime());
        long newest = secondBuckets.get(secondBuckets.getNewestTime());
        return (double) (newest - oldest) / (secondBuckets.getNewestTime() - secondBuckets.getOldestTime());
      }

      public double getRecentIncrease(long now, long windowMillis) {
        // values are only recorded when they change, so the latest one
        // still holds now; the earlier one is a bucket's average, i.e.
        // the value around the middle of that bucket
        long earlyTime = Math.max(now - windowMillis, secondBuckets.getOldestTime());
        long earlyMiddle = secondBuckets.startOfBucketAt(earlyTime) + secondBuckets.getInterval() / 2;
        if (now - earlyMiddle < secondBuckets.getInterval()) {
          // too little data yet
          return getAverageIncrease();
        }
        return (double) (secondBuckets.getLatestValue() - secondBuckets.get(earlyTime)) / (now - earlyMiddle);
      }

      public long intervalResolutionAt(long time) {
        return determineBucketToUse(time).getInterval();
      }

      public long oldestData() {
        return minuteBuckets.getOldestTime();
      }

      public long newestData() {
        return secondBuckets.getNewestTime();
      }

      public String getName() {
        return name;
      }
    };
  }

}
