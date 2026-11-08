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

import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.Optional;

/** Small helpers shared by the dialogs and the main window. */
final class Dialogs {

  static final ButtonType START = new ButtonType("Start Download", ButtonBar.ButtonData.OK_DONE);

  private Dialogs() {
  }

  static void error(Window owner, String title, String message) {
    alert(Alert.AlertType.ERROR, owner, title, message).showAndWait();
  }

  static void warning(Window owner, String title, String message) {
    alert(Alert.AlertType.WARNING, owner, title, message).showAndWait();
  }

  static boolean confirm(Window owner, String title, String header, String message) {
    Alert alert = alert(Alert.AlertType.CONFIRMATION, owner, title, message);
    alert.setHeaderText(header);
    Optional<ButtonType> result = alert.showAndWait();
    return result.isPresent() && result.get() == ButtonType.OK;
  }

  private static Alert alert(Alert.AlertType type, Window owner, String title, String message) {
    Alert alert = new Alert(type, message);
    alert.initOwner(owner);
    alert.setTitle(title);
    alert.setHeaderText(null);
    return alert;
  }

  /** Two-column label/field form layout, with the field column growing. */
  static GridPane form() {
    GridPane grid = new GridPane();
    grid.setHgap(8);
    grid.setVgap(10);
    // labels and the Browse buttons never shrink below their text; only
    // the field column gives way
    ColumnConstraints labels = new ColumnConstraints();
    labels.setMinWidth(Region.USE_PREF_SIZE);
    ColumnConstraints fields = new ColumnConstraints(360, 420, Double.MAX_VALUE);
    fields.setHgrow(Priority.ALWAYS);
    ColumnConstraints buttons = new ColumnConstraints();
    buttons.setMinWidth(Region.USE_PREF_SIZE);
    grid.getColumnConstraints().addAll(labels, fields, buttons);
    return grid;
  }

  /**
   * Adds a "Save to" row (path field + Browse button) to a form,
   * prefilled with the last directory picked in either dialog.
   */
  static TextField addDestinationRow(GridPane form, int row, Dialog<?> dialog) {
    final TextField field = new TextField();
    File lastDirectory = LastDirectoryPreference.get();
    if (lastDirectory != null) {
      field.setText(lastDirectory.getAbsolutePath());
    }
    Button browse = new Button("Browse...");
    browse.setOnAction(event -> {
      DirectoryChooser chooser = new DirectoryChooser();
      chooser.setTitle("Save Downloads To");
      File current = new File(field.getText());
      if (current.isDirectory()) {
        chooser.setInitialDirectory(current);
      }
      File chosen = chooser.showDialog(dialog.getDialogPane().getScene().getWindow());
      if (chosen != null) {
        field.setText(chosen.getAbsolutePath());
      }
    });
    form.add(new Label("Save to:"), 0, row);
    form.add(field, 1, row);
    form.add(browse, 2, row);
    return field;
  }

  /** The directory typed into a "Save to" field, or null if it isn't writable. */
  static File writableDirectory(TextField field) {
    String path = field.getText().trim();
    if (path.isEmpty()) {
      return null;
    }
    File directory = new File(path);
    return directory.isDirectory() && directory.canWrite() ? directory : null;
  }
}
