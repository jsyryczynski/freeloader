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

import org.apache.log4j.Appender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.RollingFileAppender;

import java.io.File;
import java.io.IOException;
import java.util.prefs.Preferences;

/**
 * The optional full log: everything Freeloader logs at debug level, in
 * .freeloader.log in the user's folder (at most 10 MB, plus up to four
 * older files). Off unless switched on, with --log or in the window's Help
 * menu, because it records the addresses of peers and trackers, which
 * Freeloader otherwise never writes to disk.
 */
public class FullLog {

  public static final File FILE = new File(System.getProperty("user.home"), ".freeloader.log");

  private static final String APPENDER_NAME = "FullLog";
  private static final String PATTERN = "%d (%r) [%t] %-5p %c %x - %m%n";
  private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
  private static final int OLDER_FILES = 4;

  // the window's setting, in the per-user Preferences store
  private static final String KEY = "fullLog";
  private static final Preferences PREFS = Preferences.userNodeForPackage(FullLog.class);

  // the root level from the logging configuration, restored by stop()
  private static Level configuredLevel;

  private FullLog() {
  }

  /** Whether the window should write the full log (remembered across starts). */
  public static boolean isWanted() {
    return PREFS.getBoolean(KEY, false);
  }

  /**
   * Starts or stops the log and remembers the choice. Returns whether the
   * log is now running: false if it was wanted but the file couldn't be
   * opened, in which case nothing is remembered.
   */
  public static boolean setWanted(boolean wanted) {
    if (wanted) {
      start();
      if (!isRunning()) {
        return false;
      }
    } else {
      stop();
    }
    PREFS.putBoolean(KEY, wanted);
    return isRunning();
  }

  public static synchronized boolean isRunning() {
    return Logger.getRootLogger().getAppender(APPENDER_NAME) != null;
  }

  /** Starts writing the full log; does nothing if it already is. */
  public static synchronized void start() {
    start(FILE);
  }

  // the tests use their own file
  static synchronized void start(File file) {
    Logger root = Logger.getRootLogger();
    if (root.getAppender(APPENDER_NAME) != null) {
      return;
    }
    try {
      RollingFileAppender appender = new RollingFileAppender(new PatternLayout(PATTERN), file.getPath(), true);
      appender.setName(APPENDER_NAME);
      appender.setMaximumFileSize(MAX_FILE_SIZE);
      appender.setMaxBackupIndex(OLDER_FILES);
      configuredLevel = root.getLevel();
      root.addAppender(appender);
      // the console keeps its own, higher threshold
      root.setLevel(Level.DEBUG);
    } catch (IOException e) {
      System.err.println("Could not write the log file " + file + ": " + e.getMessage());
    }
  }

  /** Stops writing the full log; the file stays. */
  public static synchronized void stop() {
    Logger root = Logger.getRootLogger();
    Appender appender = root.getAppender(APPENDER_NAME);
    if (appender == null) {
      return;
    }
    root.removeAppender(appender);
    appender.close();
    root.setLevel(configuredLevel);
  }
}
