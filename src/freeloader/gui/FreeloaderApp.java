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

import freeloader.Environment;
import javafx.application.Application;
import javafx.stage.Stage;

/**
 * JavaFX entry point. Deliberately not the jar's main class: the JavaFX
 * jars sit on the plain classpath (not the module path), and the JDK
 * launcher refuses to start a main class that extends Application in
 * that setup. freeloader.Freeloader calls run() instead.
 */
public class FreeloaderApp extends Application {

  // Application.launch() instantiates this class itself, so the
  // environment can't go through a constructor
  private static Environment environment;

  /** Shows the main window; blocks until the JavaFX toolkit exits. */
  public static void run(Environment environment) {
    FreeloaderApp.environment = environment;
    Application.launch(FreeloaderApp.class);
  }

  public void start(Stage stage) {
    ThemePreference.apply();
    new MainWindow(environment, stage, getHostServices()).show();
  }
}
