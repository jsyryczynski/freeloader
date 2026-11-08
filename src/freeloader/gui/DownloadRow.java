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

import freeloader.downloader.CompletedDownloadStore;
import freeloader.downloader.ResumeStateStore;
import freeloader.downloader.TorrentDownload;
import freeloader.storage.BitField;
import freeloader.torrent.MetaInfo;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.io.File;

/**
 * One line of the main window's download table: a live TorrentDownload,
 * a stopped one (still on the resume list, but not running), or an entry
 * from the completed-downloads history. Exactly one of getDownload(),
 * getStopped() and getCompleted() is non-null. Rows are updated in place
 * on every refresh so the table keeps its selection and scroll position.
 */
class DownloadRow {

  /** Progress value meaning "not known" (a stopped download never seen running). */
  static final double UNKNOWN_PROGRESS = -1;

  // below this (MB/s, i.e. 500 bytes/s) only protocol chatter is arriving -- handshakes,
  // keep-alives -- and a time-left estimate would be nonsense
  private static final double MIN_DOWNLOADING_RATE = 0.0005;

  // the speed shown is over this long: short enough to follow changes,
  // long enough not to jump around
  private static final long SPEED_WINDOW_MS = 20000;

  private TorrentDownload download;
  private ResumeStateStore.Entry stopped;
  private CompletedDownloadStore.Entry completed;

  private final StringProperty name = new SimpleStringProperty();
  private final LongProperty size = new SimpleLongProperty();
  private final DoubleProperty progress = new SimpleDoubleProperty();
  private final DoubleProperty speed = new SimpleDoubleProperty();
  private final StringProperty status = new SimpleStringProperty();

  void showDownload(TorrentDownload download) {
    this.download = download;
    this.stopped = null;
    this.completed = null;

    BitField validPieces = download.getPieceManager().getValidPieces();
    double fraction = (double) validPieces.getAvailablePieceCount() / validPieces.getPieceCount();
    // the counter gives bytes per millisecond; 1000 of those make 1 MB/s
    double megabytesPerSecond =
        download.getCounterRepository().getStatistics("network.rawbytes.in")
            .getRecentIncrease(System.currentTimeMillis(), SPEED_WINDOW_MS) / 1000;

    name.set(download.getMetaInfo().getName());
    size.set(download.getMetaInfo().getTotalLength());
    progress.set(fraction);
    speed.set(megabytesPerSecond);
    // not download.getBytesLeft(): that's the (deliberately skewed) figure reported to trackers
    long bytesLeft = (long) (download.getMetaInfo().getTotalLength() * (1 - fraction));
    status.set(validPieces.hasAll() || megabytesPerSecond >= MIN_DOWNLOADING_RATE
        ? describe(validPieces.hasAll(), megabytesPerSecond, bytesLeft)
        : describeNoTraffic(download));
  }

  /** Why nothing is arriving: no peers known yet, or none accepting us. */
  private static String describeNoTraffic(TorrentDownload download) {
    int connected = download.getAllValidConnections().size();
    if (connected > 0) {
      return "Waiting for data (" + connected + " connected)";
    }
    int known = download.getPeerManager().distinctPeersSeen();
    if (known > 0) {
      return "Connecting (" + known + " peers known)";
    }
    if (download.isSearchingForPeers() || download.getLastPeerSearchTime() == 0) {
      return "Searching for peers...";
    }
    return "No peers found yet, retrying";
  }

  /**
   * A download on the resume list that isn't running: stopped by the
   * user, or still being checked before it (re)starts. Keeps the progress
   * it last showed while running, if it was ever seen running.
   */
  void showStopped(ResumeStateStore.Entry entry, MetaInfo metaInfo, boolean starting) {
    boolean neverShown = download == null && stopped == null && completed == null;
    this.download = null;
    this.stopped = entry;
    this.completed = null;

    name.set(metaInfo != null ? metaInfo.getName() : entry.infoHash.toString());
    size.set(metaInfo != null ? metaInfo.getTotalLength() : 0);
    if (neverShown) {
      progress.set(UNKNOWN_PROGRESS);
    }
    speed.set(Double.NaN);
    status.set(starting ? "Starting, checking existing data..." : "Stopped");
  }

  void showCompleted(CompletedDownloadStore.Entry entry) {
    this.download = null;
    this.stopped = null;
    this.completed = entry;

    name.set(entry.name);
    size.set(entry.totalLength);
    progress.set(1.0);
    speed.set(Double.NaN);
    status.set("Completed " + Format.date(entry.completedAtMillis));
  }

  private static String describe(boolean finished, double megabytesPerSecond, long bytesLeft) {
    if (finished) {
      return "Finished";
    }
    if (megabytesPerSecond < MIN_DOWNLOADING_RATE) {
      return "Waiting for peers";
    }
    long secondsLeft = (long) (bytesLeft / (megabytesPerSecond * 1000000));
    return "Downloading, " + Format.duration(secondsLeft) + " left";
  }

  /** The live download, or null if it isn't running. */
  TorrentDownload getDownload() {
    return download;
  }

  /** The resume list entry of a download that isn't running, or null. */
  ResumeStateStore.Entry getStopped() {
    return stopped;
  }

  /** The history entry of a finished download, or null. */
  CompletedDownloadStore.Entry getCompleted() {
    return completed;
  }

  boolean isActive() {
    return download != null;
  }

  boolean isStopped() {
    return stopped != null;
  }

  File getOutputDirectory() {
    if (download != null) {
      return download.getConfiguration().getTargetDirectory();
    }
    return stopped != null ? stopped.outputDirectory : completed.outputDirectory;
  }

  StringProperty nameProperty() {
    return name;
  }

  LongProperty sizeProperty() {
    return size;
  }

  DoubleProperty progressProperty() {
    return progress;
  }

  DoubleProperty speedProperty() {
    return speed;
  }

  StringProperty statusProperty() {
    return status;
  }
}
