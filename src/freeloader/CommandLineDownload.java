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

import freeloader.bencoding.ParseException;
import freeloader.downloader.MagnetDownloadStarter;
import freeloader.downloader.TorrentDownload;
import freeloader.downloader.TorrentDownloadConfiguration;
import freeloader.storage.BitField;
import freeloader.torrent.MagnetUri;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.MetaInfoBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A download started from the command line: from a .torrent file or a
 * magnet link, reporting its progress on the console. Running the same
 * command again picks up where it left off (already-downloaded data is
 * checked and kept). Command-line downloads stay out of the GUI's resume
 * list and completed history.
 */
class CommandLineDownload {

  private static final long REFRESH_MS = 1000;
  // without a console (output redirected to a file), print a line now and
  // then rather than rewriting one in place
  private static final long LOG_EVERY_MS = 10000;
  // the speed shown is over this long: short enough to follow changes,
  // long enough not to jump around
  private static final long SPEED_WINDOW_MS = 20000;

  private final Environment environment;
  private final PrintStream out = System.out;
  private final boolean console = System.console() != null;
  private int lastLineLength = 0;

  CommandLineDownload(Environment environment) {
    this.environment = environment;
  }

  /** Starts the download; returns null (having said why) if it can't. */
  TorrentDownload start(String torrent, File outputDirectory, int listeningPort) throws InterruptedException {
    if (torrent.regionMatches(true, 0, "magnet:", 0, 7)) {
      return startMagnet(torrent, outputDirectory, listeningPort);
    }
    MetaInfo metaInfo;
    try {
      metaInfo = MetaInfoBuilder.fromFile(new File(torrent));
    } catch (IOException | ParseException e) {
      System.err.println("Could not read the torrent file " + torrent + ": " + e.getMessage());
      return null;
    }
    out.println("Checking existing data for " + metaInfo.getName() + "...");
    TorrentDownloadConfiguration configuration = new TorrentDownloadConfiguration(metaInfo, outputDirectory);
    configuration.setListeningPort(listeningPort);
    configuration.setTrackedByGui(false);
    TorrentDownload download = new TorrentDownload(environment, configuration);
    download.start();
    return download;
  }

  private TorrentDownload startMagnet(String link, File outputDirectory, int listeningPort)
      throws InterruptedException {
    MagnetUri magnet;
    try {
      magnet = MagnetUri.parse(link);
    } catch (MagnetUri.MagnetUriParseException e) {
      System.err.println("Not a usable magnet link: " + e.getMessage());
      return null;
    }
    CompletableFuture<TorrentDownload> result = new CompletableFuture<TorrentDownload>();
    MagnetDownloadStarter starter = new MagnetDownloadStarter(environment);
    starter.setListeningPort(listeningPort);
    starter.setRememberForResume(false);
    starter.start(magnet, outputDirectory, new MagnetDownloadStarter.StatusListener() {
      public void onStatus(String message) {
        out.println(capitalize(message));
      }

      public void onSuccess(TorrentDownload download) {
        result.complete(download);
      }

      public void onFailure(String message) {
        System.err.println("Magnet download failed: " + message);
        result.complete(null);
      }
    });
    try {
      return result.get();
    } catch (ExecutionException e) {
      System.err.println("Magnet download failed: " + e.getCause());
      return null;
    }
  }

  /** Shows the download's progress until it has finished. */
  void showProgressUntilFinished(TorrentDownload download) throws InterruptedException {
    out.println("Downloading " + download.getMetaInfo().getName() + " ("
        + size(download.getMetaInfo().getTotalLength()) + ") to "
        + download.getConfiguration().getTargetDirectory().getAbsolutePath());
    long lastLogged = 0;
    while (true) {
      BitField pieces = download.getPieceManager().getValidPieces();
      if (pieces.hasAll()) {
        show("100.0%  done", true);
        out.println("Finished: " + download.getMetaInfo().getName());
        return;
      }
      long now = System.currentTimeMillis();
      if (console || now - lastLogged >= LOG_EVERY_MS) {
        show(progressLine(download, pieces), !console);
        lastLogged = now;
      }
      Thread.sleep(REFRESH_MS);
    }
  }

  private String progressLine(TorrentDownload download, BitField pieces) {
    double fraction = (double) pieces.getAvailablePieceCount() / pieces.getPieceCount();
    // the counter gives bytes per millisecond; 1000 of those make 1 MB/s
    double megabytesPerSecond =
        download.getCounterRepository().getStatistics("network.rawbytes.in")
            .getRecentIncrease(System.currentTimeMillis(), SPEED_WINDOW_MS) / 1000;
    int connected = download.getAllValidConnections().size();
    StringBuilder line = new StringBuilder();
    line.append(String.format(Locale.ROOT, "%5.1f%%  %s  %d peer%s", fraction * 100,
        rate(megabytesPerSecond), connected, connected == 1 ? "" : "s"));
    if (connected == 0) {
      int known = download.getPeerManager().distinctPeersSeen();
      line.append(known > 0 ? "  (connecting, " + known + " known)"
          : download.isSearchingForPeers() ? "  (searching for peers)" : "  (no peers found yet)");
    } else if (megabytesPerSecond >= 0.0005) {
      long bytesLeft = (long) (download.getMetaInfo().getTotalLength() * (1 - fraction));
      line.append("  ETA ").append(duration((long) (bytesLeft / (megabytesPerSecond * 1000000))));
    }
    return line.toString();
  }

  private void show(String line, boolean newLine) {
    if (newLine) {
      out.println(line);
      lastLineLength = 0;
      return;
    }
    // overwrite the previous line, padding over whatever of it is longer
    StringBuilder padded = new StringBuilder("\r").append(line);
    for (int i = line.length(); i < lastLineLength; i++) {
      padded.append(' ');
    }
    out.print(padded);
    out.flush();
    lastLineLength = line.length();
  }

  static String size(long bytes) {
    if (bytes < 1000) {
      return bytes + " B";
    }
    String[] units = {"KB", "MB", "GB", "TB"};
    double value = bytes;
    int unit = -1;
    while (value >= 1000 && unit < units.length - 1) {
      value /= 1000;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
  }

  /** Always in MB/s, like the speed limit; two decimals below 10. */
  static String rate(double megabytesPerSecond) {
    return String.format(Locale.ROOT, megabytesPerSecond < 10 ? "%.2f MB/s" : "%.1f MB/s", megabytesPerSecond);
  }

  static String duration(long seconds) {
    if (seconds < 60) {
      return seconds + "s";
    } else if (seconds < 3600) {
      return (seconds / 60) + "m " + (seconds % 60) + "s";
    }
    return (seconds / 3600) + "h " + (seconds % 3600 / 60) + "m";
  }

  private static String capitalize(String message) {
    return message.isEmpty() ? message : Character.toUpperCase(message.charAt(0)) + message.substring(1);
  }
}
