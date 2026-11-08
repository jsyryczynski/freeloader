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

import java.io.File;
import java.util.prefs.Preferences;

/**
 * Remembers the last output directory the user picked for a download
 * (regular or magnet), persisted across app restarts via the JDK's
 * per-user Preferences store (on Windows this lives in the registry
 * under HKEY_CURRENT_USER, nothing Freeloader-specific to clean up by
 * hand). Shared between NewTorrentDownloadDialog and
 * MagnetDownloadDialog so picking a directory in either one updates the
 * default for both.
 */
class LastDirectoryPreference {

  private static final String KEY = "lastDownloadDirectory";
  private static final Preferences PREFS = Preferences.userNodeForPackage(LastDirectoryPreference.class);

  static File get() {
    String path = PREFS.get(KEY, null);
    if (path == null) {
      return null;
    }
    File directory = new File(path);
    return directory.isDirectory() ? directory : null;
  }

  static void set(File directory) {
    if (directory != null) {
      PREFS.put(KEY, directory.getAbsolutePath());
    }
  }
}
