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

import freeloader.Version;
import javafx.application.Platform;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub once per start of the window whether a newer release of
 * Freeloader exists. Besides the downloads themselves, this is the only
 * request the app makes; it sends nothing but the app's version (in the
 * User-Agent), and it can be switched off in the Help menu, remembered in
 * the same per-user Preferences store as ThemePreference.
 */
class UpdateCheck {

  static final String RELEASES_PAGE = "https://github.com/jsyryczynski/freeloader/releases";
  private static final String LATEST_RELEASE = "https://api.github.com/repos/jsyryczynski/freeloader/releases/latest";
  private static final int TIMEOUT_MS = 10000;

  // a regular expression rather than a JSON parser, which the app doesn't
  // have: the release's own tag_name is the first one in GitHub's answer,
  // and the release workflow only accepts tags like v1.2.3 (no quotes)
  private static final Pattern TAG_NAME = Pattern.compile("\"tag_name\" *: *\"([^\"]+)\"");
  private static final Pattern VERSION = Pattern.compile("v?([0-9]{1,6})[.]([0-9]{1,6})[.]([0-9]{1,6})");

  private static final String KEY = "checkForUpdates";
  private static final Preferences PREFS = Preferences.userNodeForPackage(UpdateCheck.class);

  private static final Logger logger = Logger.getLogger(UpdateCheck.class);

  static boolean isEnabled() {
    return PREFS.getBoolean(KEY, true);
  }

  static void setEnabled(boolean enabled) {
    PREFS.putBoolean(KEY, enabled);
  }

  /**
   * Checks in the background. If there is a newer release, its tag (e.g.
   * "v0.3.0") is handed to whenNewer on the JavaFX thread; otherwise, and
   * on any failure, nothing happens.
   */
  static void start(Consumer<String> whenNewer) {
    // a daemon thread, so a slow answer never holds up quitting
    Thread thread = new Thread(() -> {
      try {
        String tag = latestTag(fetchLatestRelease());
        if (tag != null && isNewer(tag, Version.getMajor(), Version.getMinor(), Version.getTiny())) {
          Platform.runLater(() -> whenNewer.accept(tag));
        }
      } catch (IOException | RuntimeException e) {
        logger.info("Could not check for a newer version: " + e);
      }
    }, "update check");
    thread.setDaemon(true);
    thread.start();
  }

  private static String fetchLatestRelease() throws IOException {
    HttpURLConnection connection = (HttpURLConnection) URI.create(LATEST_RELEASE).toURL().openConnection();
    connection.setConnectTimeout(TIMEOUT_MS);
    connection.setReadTimeout(TIMEOUT_MS);
    connection.setRequestProperty("Accept", "application/vnd.github+json");
    connection.setRequestProperty("User-Agent", "Freeloader/" + Version.getShortVersionString());
    try {
      int status = connection.getResponseCode();
      if (status != HttpURLConnection.HTTP_OK) {
        throw new IOException("HTTP " + status);
      }
      try (InputStream in = connection.getInputStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    } finally {
      connection.disconnect();
    }
  }

  /** The tag_name in GitHub's answer about a release, or null. */
  static String latestTag(String json) {
    Matcher matcher = TAG_NAME.matcher(json);
    return matcher.find() ? matcher.group(1) : null;
  }

  /** Whether a tag like "v0.3.0" is a later version than major.minor.tiny. */
  static boolean isNewer(String tag, int major, int minor, int tiny) {
    Matcher matcher = VERSION.matcher(tag.trim());
    if (!matcher.matches()) {
      return false;
    }
    int[] release = {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
        Integer.parseInt(matcher.group(3))};
    int[] running = {major, minor, tiny};
    for (int i = 0; i < 3; i++) {
      if (release[i] != running[i]) {
        return release[i] > running[i];
      }
    }
    return false;
  }
}
