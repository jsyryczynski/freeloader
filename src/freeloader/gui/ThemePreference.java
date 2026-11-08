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

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;

import java.util.prefs.Preferences;

/**
 * Light/dark AtlantaFX theme, remembered across restarts in the same
 * per-user Preferences store as LastDirectoryPreference. The theme is a
 * global user-agent stylesheet, so switching it restyles every open
 * window at once.
 */
class ThemePreference {

  private static final String KEY = "darkTheme";
  private static final Preferences PREFS = Preferences.userNodeForPackage(ThemePreference.class);

  static boolean isDark() {
    return PREFS.getBoolean(KEY, false);
  }

  static void setDark(boolean dark) {
    PREFS.putBoolean(KEY, dark);
    apply();
  }

  static void apply() {
    Application.setUserAgentStylesheet(isDark()
        ? new PrimerDark().getUserAgentStylesheet()
        : new PrimerLight().getUserAgentStylesheet());
  }
}
