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

import freeloader.torrent.MagnetUri;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.io.File;

/**
 * "Add Magnet Link" dialog: a text field to paste or drop a magnet: link
 * into, plus the usual destination directory picker. (Links can also be
 * dropped onto the main window, which opens this dialog prefilled.)
 *
 * This dialog only collects and validates input -- it doesn't do any of
 * the actual tracker/DHT/metadata work itself. As soon as the input is
 * valid it hands off to the given listener and closes immediately,
 * rather than sitting open while the (potentially slow) peer/metadata
 * lookup runs; progress after that point shows up in the main window.
 */
class MagnetDownloadDialog {

  interface OnStartListener {
    void onStart(MagnetUri magnetUri, File outputDirectory);
  }

  private final Dialog<ButtonType> dialog = new Dialog<ButtonType>();
  private final TextField magnetUriField = new TextField();
  private final TextField outputDirectoryField;

  MagnetDownloadDialog(Window owner, String magnetUri, final OnStartListener listener) {
    dialog.initOwner(owner);
    dialog.setTitle("Add Magnet Link");
    dialog.setHeaderText("Download from a magnet link");

    GridPane form = Dialogs.form();

    magnetUriField.setPromptText("Paste or drop a magnet link here");
    MagnetDrop.acceptDrops(magnetUriField);
    if (magnetUri != null) {
      magnetUriField.setText(magnetUri);
    }
    form.add(new Label("Magnet link:"), 0, 0);
    form.add(magnetUriField, 1, 0, 2, 1);

    outputDirectoryField = Dialogs.addDestinationRow(form, 1, dialog);

    dialog.getDialogPane().setContent(form);
    dialog.getDialogPane().getButtonTypes().addAll(Dialogs.START, ButtonType.CANCEL);
    Node startButton = dialog.getDialogPane().lookupButton(Dialogs.START);
    startButton.addEventFilter(ActionEvent.ACTION, event -> {
      if (!startDownload(listener)) {
        event.consume();
      }
    });
  }

  void show() {
    dialog.show();
  }

  /** Returns false (after telling the user why) if the input isn't usable. */
  private boolean startDownload(OnStartListener listener) {
    String uriText = magnetUriField.getText().trim();
    if (uriText.isEmpty()) {
      return fail("Please paste a magnet link first.");
    }

    MagnetUri magnetUri;
    try {
      magnetUri = MagnetUri.parse(uriText);
    } catch (MagnetUri.MagnetUriParseException e) {
      return fail("Not a valid magnet link: " + e.getMessage());
    }

    File outputDirectory = Dialogs.writableDirectory(outputDirectoryField);
    if (outputDirectory == null) {
      return fail("Cannot write to the destination directory.");
    }

    LastDirectoryPreference.set(outputDirectory);

    // hand off and close right away -- the lookup that follows can take
    // a while (trackers, possibly the DHT), and there's no reason to
    // keep this dialog open and blocked on it; the main window takes
    // over showing progress from here
    listener.onStart(magnetUri, outputDirectory);
    return true;
  }

  private boolean fail(String message) {
    Dialogs.error(dialog.getDialogPane().getScene().getWindow(), "Add Magnet Link", message);
    return false;
  }
}
