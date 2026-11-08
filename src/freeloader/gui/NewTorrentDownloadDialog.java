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
import freeloader.bencoding.ParseException;
import freeloader.downloader.ResumeStateStore;
import freeloader.downloader.TorrentDownload;
import freeloader.downloader.TorrentDownloadConfiguration;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.MetaInfoBuilder;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * "Add Torrent" dialog: pick a .torrent file and where to save it, plus
 * the listening port. Validation happens when Start is pressed; the
 * dialog stays open (with an error) until the input is usable, then
 * starts the download in the background and closes.
 *
 * The share ratio isn't asked for: it only sets how much upload gets
 * (falsely) reported to trackers, so a random plausible one is used.
 */
class NewTorrentDownloadDialog {

  private final Environment environment;
  private final Dialog<ButtonType> dialog = new Dialog<ButtonType>();

  private final TextField torrentFileField = new TextField();
  private final TextField outputDirectoryField;
  private final TextField listeningPortField =
      new TextField(Integer.toString(TorrentDownloadConfiguration.DEFAULT_LISTENING_PORT));

  NewTorrentDownloadDialog(Window owner, Environment environment, File torrentFile) {
    this.environment = environment;

    dialog.initOwner(owner);
    dialog.setTitle("Add Torrent");
    dialog.setHeaderText("Download from a .torrent file");

    GridPane form = Dialogs.form();

    torrentFileField.setPromptText("Path to a .torrent file");
    if (torrentFile != null) {
      torrentFileField.setText(torrentFile.getAbsolutePath());
    }
    Button browse = new Button("Browse...");
    browse.setOnAction(event -> chooseTorrentFile());
    form.add(new Label("Torrent file:"), 0, 0);
    form.add(torrentFileField, 1, 0);
    form.add(browse, 2, 0);

    outputDirectoryField = Dialogs.addDestinationRow(form, 1, dialog);

    form.add(new Label("Listening port:"), 0, 2);
    form.add(listeningPortField, 1, 2, 2, 1);

    dialog.getDialogPane().setContent(form);
    dialog.getDialogPane().getButtonTypes().addAll(Dialogs.START, ButtonType.CANCEL);
    Node startButton = dialog.getDialogPane().lookupButton(Dialogs.START);
    startButton.addEventFilter(ActionEvent.ACTION, event -> {
      if (!startDownload()) {
        event.consume();
      }
    });
  }

  void show() {
    dialog.show();
  }

  private void chooseTorrentFile() {
    FileChooser chooser = new FileChooser();
    chooser.setTitle("Open Torrent File");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Torrent files", "*.torrent"));
    File current = new File(torrentFileField.getText());
    if (current.getParentFile() != null && current.getParentFile().isDirectory()) {
      chooser.setInitialDirectory(current.getParentFile());
    }
    File chosen = chooser.showOpenDialog(dialog.getDialogPane().getScene().getWindow());
    if (chosen != null) {
      torrentFileField.setText(chosen.getAbsolutePath());
    }
  }

  /** Returns false (after telling the user why) if the input isn't usable. */
  private boolean startDownload() {
    File torrentFile = new File(torrentFileField.getText().trim());
    if (torrentFileField.getText().trim().isEmpty() || !torrentFile.canRead()) {
      return fail("Cannot read the torrent file.");
    }

    MetaInfo metaInfo;
    byte[] torrentFileBytes;
    try {
      torrentFileBytes = Files.readAllBytes(torrentFile.toPath());
      metaInfo = MetaInfoBuilder.fromFile(torrentFile);
    } catch (IOException e) {
      return fail("Could not read the torrent file: " + e.getMessage());
    } catch (ParseException e) {
      return fail("Not a valid torrent file: " + e.getMessage());
    }

    File outputDirectory = Dialogs.writableDirectory(outputDirectoryField);
    if (outputDirectory == null) {
      return fail("Cannot write to the destination directory.");
    }

    // between 1.0 and 1.33, so not every download reports the same ratio
    double shareRatio = Math.round((Math.random() / 3.0 + 1.0) * 100) / 100.0;

    int listeningPort;
    try {
      listeningPort = Integer.parseInt(listeningPortField.getText().trim());
    } catch (NumberFormatException e) {
      return fail("Invalid listening port.");
    }
    if (listeningPort < 1 || listeningPort > 0xffff) {
      return fail("Invalid listening port.");
    }

    LastDirectoryPreference.set(outputDirectory);
    ResumeStateStore.add(metaInfo.getInfoHash(), torrentFileBytes, outputDirectory, listeningPort, shareRatio);

    TorrentDownloadConfiguration settings = new TorrentDownloadConfiguration(metaInfo, outputDirectory);
    settings.setShareRatio(shareRatio);
    settings.setListeningPort(listeningPort);
    // off the UI thread: starting checks the data already on disk, which takes a while
    environment.getScheduledExecutor().execute(() -> new TorrentDownload(environment, settings).start());
    return true;
  }

  private boolean fail(String message) {
    Dialogs.error(dialog.getDialogPane().getScene().getWindow(), "Add Torrent", message);
    return false;
  }
}
