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

import atlantafx.base.theme.Styles;
import atlantafx.base.theme.Tweaks;
import freeloader.Environment;
import freeloader.FullLog;
import freeloader.Version;
import freeloader.downloader.CompletedDownloadStore;
import freeloader.downloader.MagnetDownloadStarter;
import freeloader.downloader.ResumeStateStore;
import freeloader.downloader.TorrentDownload;
import freeloader.downloader.TorrentDownloadConfiguration;
import freeloader.network.DownloadRateLimiter;
import freeloader.torrent.MagnetUri;
import freeloader.torrent.MetaInfo;
import freeloader.torrent.MetaInfoBuilder;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.apache.log4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The main window: a table of live and completed downloads, with the
 * selected download's details and charts underneath. Torrent files and
 * magnet links can be added from the toolbar/menu or dropped onto the
 * window.
 */
class MainWindow {

  private static final Duration TABLE_REFRESH = Duration.seconds(2);
  private static final Duration DETAILS_REFRESH = Duration.seconds(5);
  private static final long EXIT_TIMEOUT_MS = 5000;

  private final Environment environment;
  private final Stage stage;
  private final HostServices hostServices;

  private final ObservableList<DownloadRow> rows = FXCollections.observableArrayList();
  private final Map<String, DownloadRow> rowsByInfoHash = new HashMap<String, DownloadRow>();
  private final Map<TorrentDownload, DownloadDetails> details = new HashMap<TorrentDownload, DownloadDetails>();
  private DownloadDetails shownDetails;

  private final TableView<DownloadRow> table = new TableView<DownloadRow>(rows);
  private final StackPane detailsHolder = new StackPane();
  private final VBox magnetLookups = new VBox(4);
  private final Label summary = new Label();
  // shown when GitHub has a newer release (see UpdateCheck)
  private final Hyperlink updateLink = new Hyperlink();

  // info hashes of downloads being (re)started in the background: building
  // a TorrentDownload re-checks existing file data, which can take a while
  private final Set<String> starting = ConcurrentHashMap.newKeySet();
  // parsed .torrent files of stopped downloads, which have no live
  // TorrentDownload to ask for their name and size (null if unreadable)
  private final Map<String, MetaInfo> stoppedMetaInfo = new HashMap<String, MetaInfo>();

  private final Button startButton = new Button("Start");
  private final Button stopButton = new Button("Stop");
  private final Button removeButton = new Button("Remove");
  private final Button openFolderButton = new Button("Open Folder");

  private static final Logger logger = Logger.getLogger(MainWindow.class);

  MainWindow(Environment environment, Stage stage, HostServices hostServices) {
    this.environment = environment;
    this.stage = stage;
    this.hostServices = hostServices;
    // before any download (re)starts
    SpeedLimit.apply(downloadLimiter());

    BorderPane root = new BorderPane();
    root.setTop(new VBox(createMenuBar(), createToolBar()));
    root.setCenter(createContent());
    root.setBottom(createStatusArea());

    Scene scene = new Scene(root, 1000, 680);
    enableDropToAdd(scene);

    stage.setTitle("Freeloader (" + Version.getLongVersionString() + ")");
    stage.setScene(scene);
    stage.setMinWidth(640);
    stage.setMinHeight(420);
    // the app keeps several non-daemon background threads alive (network
    // I/O, scheduled maintenance, ...) so closing the window has to shut
    // everything down the same way as File -> Quit
    stage.setOnCloseRequest(event -> quit());
  }

  void show() {
    refresh();
    stage.show();
    resumePreviousDownloads();
    if (UpdateCheck.isEnabled()) {
      UpdateCheck.start(this::showNewerVersion);
    }

    Timeline tableRefresh = new Timeline(new KeyFrame(TABLE_REFRESH, event -> refresh()));
    tableRefresh.setCycleCount(Timeline.INDEFINITE);
    tableRefresh.play();

    Timeline detailsRefresh = new Timeline(new KeyFrame(DETAILS_REFRESH, event -> {
      if (shownDetails != null) {
        shownDetails.refresh();
      }
    }));
    detailsRefresh.setCycleCount(Timeline.INDEFINITE);
    detailsRefresh.play();
  }

  // ---------------------------------------------------------------- layout

  private MenuBar createMenuBar() {
    MenuItem addTorrent = new MenuItem("Add Torrent...");
    addTorrent.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
    addTorrent.setOnAction(event -> showAddTorrent(null));

    MenuItem addMagnet = new MenuItem("Add Magnet Link...");
    addMagnet.setAccelerator(KeyCombination.keyCombination("Shortcut+M"));
    addMagnet.setOnAction(event -> showAddMagnet(null));

    MenuItem speedLimit = new MenuItem("Speed Limit...");
    speedLimit.setOnAction(event -> {
      SpeedLimit.showDialog(stage, downloadLimiter());
      refresh();
    });

    MenuItem quit = new MenuItem("Quit");
    quit.setAccelerator(KeyCombination.keyCombination("Shortcut+Q"));
    quit.setOnAction(event -> quit());

    Menu file = new Menu("File");
    file.getItems().addAll(addTorrent, addMagnet, new SeparatorMenuItem(), speedLimit,
        new SeparatorMenuItem(), quit);

    CheckMenuItem darkTheme = new CheckMenuItem("Dark Theme");
    darkTheme.setSelected(ThemePreference.isDark());
    darkTheme.setOnAction(event -> ThemePreference.setDark(darkTheme.isSelected()));

    Menu view = new Menu("View");
    view.getItems().add(darkTheme);

    CheckMenuItem checkForUpdates = new CheckMenuItem("Check for Updates at Start");
    checkForUpdates.setSelected(UpdateCheck.isEnabled());
    checkForUpdates.setOnAction(event -> UpdateCheck.setEnabled(checkForUpdates.isSelected()));

    MenuItem releases = new MenuItem("Releases Page");
    releases.setOnAction(event -> hostServices.showDocument(UpdateCheck.RELEASES_PAGE));

    // records peer and tracker addresses, so only when asked for
    CheckMenuItem fullLog = new CheckMenuItem("Write Full Log");
    fullLog.setSelected(FullLog.isRunning());
    fullLog.setOnAction(event -> FullLog.setWanted(fullLog.isSelected()));

    MenuItem openLog = new MenuItem("Open Full Log");
    openLog.setOnAction(event -> hostServices.showDocument(FullLog.FILE.toURI().toString()));

    Menu help = new Menu("Help");
    help.setOnShowing(event -> openLog.setDisable(!FullLog.FILE.exists()));
    help.getItems().addAll(checkForUpdates, releases, new SeparatorMenuItem(), fullLog, openLog);

    return new MenuBar(file, view, help);
  }

  private ToolBar createToolBar() {
    Button addTorrent = new Button("Add Torrent...");
    addTorrent.setOnAction(event -> showAddTorrent(null));

    Button addMagnet = new Button("Add Magnet Link...");
    addMagnet.setOnAction(event -> showAddMagnet(null));

    startButton.setTooltip(new Tooltip("Resume this stopped download"));
    startButton.setOnAction(event -> startSelected());
    stopButton.setTooltip(new Tooltip(
        "Stop for now -- Start resumes it, and it also resumes automatically next time Freeloader starts"));
    stopButton.setOnAction(event -> stopSelected());
    removeButton.setOnAction(event -> removeSelected());
    openFolderButton.setOnAction(event -> openSelectedFolder());
    updateActions();

    return new ToolBar(addTorrent, addMagnet, new Separator(Orientation.VERTICAL),
        startButton, stopButton, removeButton, openFolderButton);
  }

  private Node createContent() {
    TableColumn<DownloadRow, String> name = new TableColumn<DownloadRow, String>("Name");
    name.setCellValueFactory(cell -> cell.getValue().nameProperty());
    name.setPrefWidth(340);

    TableColumn<DownloadRow, Number> size = new TableColumn<DownloadRow, Number>("Size");
    size.setCellValueFactory(cell -> cell.getValue().sizeProperty());
    size.setCellFactory(column -> textCell(value -> Format.size(value.longValue())));
    size.setPrefWidth(90);

    TableColumn<DownloadRow, Number> progress = new TableColumn<DownloadRow, Number>("Progress");
    progress.setCellValueFactory(cell -> cell.getValue().progressProperty());
    progress.setCellFactory(column -> new ProgressCell());
    progress.setPrefWidth(190);

    TableColumn<DownloadRow, Number> speed = new TableColumn<DownloadRow, Number>("Speed");
    speed.setCellValueFactory(cell -> cell.getValue().speedProperty());
    speed.setCellFactory(column -> new SpeedCell());
    speed.setPrefWidth(90);

    TableColumn<DownloadRow, String> status = new TableColumn<DownloadRow, String>("Status");
    status.setCellValueFactory(cell -> cell.getValue().statusProperty());
    status.setPrefWidth(200);

    table.getColumns().addAll(List.of(name, size, progress, speed, status));
    table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
    table.getStyleClass().addAll(Styles.STRIPED, Tweaks.EDGE_TO_EDGE);
    table.setPlaceholder(muted("No downloads yet. Add a .torrent file or a magnet link, or drop one here."));
    table.setRowFactory(view -> createTableRow());
    table.getSelectionModel().selectedItemProperty().addListener(
        (observable, previous, selected) -> {
          showDetails(selected);
          updateActions();
        });

    showDetails(null);

    SplitPane split = new SplitPane(table, detailsHolder);
    split.setOrientation(Orientation.VERTICAL);
    split.setDividerPositions(0.4);
    return split;
  }

  private TableRow<DownloadRow> createTableRow() {
    final TableRow<DownloadRow> row = new TableRow<DownloadRow>();

    MenuItem start = new MenuItem("Start");
    start.setOnAction(event -> startSelected());
    MenuItem stop = new MenuItem("Stop");
    stop.setOnAction(event -> stopSelected());
    MenuItem remove = new MenuItem("Remove");
    remove.setOnAction(event -> removeSelected());
    MenuItem openFolder = new MenuItem("Open Folder");
    openFolder.setOnAction(event -> openSelectedFolder());
    ContextMenu menu = new ContextMenu(start, stop, remove, openFolder);
    // the toolbar buttons already track what the selected row allows
    menu.setOnShowing(event -> {
      start.setDisable(startButton.isDisable());
      stop.setDisable(stopButton.isDisable());
      remove.setDisable(removeButton.isDisable());
    });

    row.emptyProperty().addListener((observable, wasEmpty, isEmpty) -> row.setContextMenu(isEmpty ? null : menu));
    row.setOnMouseClicked(event -> {
      if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !row.isEmpty()) {
        openSelectedFolder();
      }
    });
    return row;
  }

  private Node createStatusArea() {
    summary.getStyleClass().addAll(Styles.TEXT_SMALL, Styles.TEXT_MUTED);
    updateLink.getStyleClass().add(Styles.TEXT_SMALL);
    updateLink.setOnAction(event -> hostServices.showDocument(UpdateCheck.RELEASES_PAGE));
    updateLink.setVisible(false);
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox statusBar = new HBox(summary, spacer, updateLink);
    statusBar.setAlignment(Pos.CENTER_LEFT);
    statusBar.setPadding(new Insets(4, 12, 6, 12));

    magnetLookups.setPadding(new Insets(0, 12, 0, 12));
    return new VBox(magnetLookups, statusBar);
  }

  private void showNewerVersion(String tag) {
    updateLink.setText("Freeloader " + tag.replaceFirst("^v", "") + " is available");
    updateLink.setVisible(true);
  }

  private static Label muted(String text) {
    Label label = new Label(text);
    label.getStyleClass().add(Styles.TEXT_MUTED);
    label.setWrapText(true);
    return label;
  }

  // ------------------------------------------------------- periodic update

  /**
   * Brings the table in line with what's running and what's in the
   * completed-downloads history. Rows are matched by info hash and
   * updated in place, so selection and scroll position survive.
   */
  private void refresh() {
    Set<String> present = new HashSet<String>();
    double totalSpeed = 0;
    int running = 0;

    for (TorrentDownload download : environment.getDownloadRepository().getAllDownloads()) {
      String key = download.getInfoHash().toString();
      DownloadRow row = rowFor(key, RANK_RUNNING);
      row.showDownload(download);
      present.add(key);
      totalSpeed += row.speedProperty().get();
      running++;
    }
    // on the resume list but not running: stopped, or still starting up
    for (ResumeStateStore.Entry entry : ResumeStateStore.loadAll()) {
      String key = entry.infoHash.toString();
      if (present.add(key)) {
        rowFor(key, RANK_STOPPED).showStopped(entry, stoppedMetaInfo(key, entry), starting.contains(key));
      }
    }
    for (CompletedDownloadStore.Entry entry : CompletedDownloadStore.loadAll()) {
      String key = entry.infoHash.toString();
      if (present.add(key)) {
        rowFor(key, RANK_COMPLETED).showCompleted(entry);
      }
    }
    stoppedMetaInfo.keySet().retainAll(present);

    for (Iterator<Map.Entry<String, DownloadRow>> it = rowsByInfoHash.entrySet().iterator(); it.hasNext(); ) {
      Map.Entry<String, DownloadRow> entry = it.next();
      if (!present.contains(entry.getKey())) {
        rows.remove(entry.getValue());
        it.remove();
      }
    }
    forgetStoppedDetails();

    // a row can switch between live and history (finished, or restarted)
    // without the selection changing, so re-evaluate what's shown
    DownloadRow selected = table.getSelectionModel().getSelectedItem();
    if (selected != null && (!selected.isActive() || shownDetails == null)) {
      showDetails(selected);
    }
    updateActions();

    double limitMBps = SpeedLimit.getMegabytesPerSecond();
    String limit = limitMBps > 0 ? " (limit " + Format.rate(limitMBps) + ")" : "";
    summary.setText((running == 0
        ? "No downloads running"
        : running + (running == 1 ? " download" : " downloads") + " running, " + Format.rate(totalSpeed))
        + limit);
  }

  private DownloadRateLimiter downloadLimiter() {
    return environment.getNetworkManager().getDownloadLimiter();
  }

  private static final int RANK_RUNNING = 0;
  private static final int RANK_STOPPED = 1;
  private static final int RANK_COMPLETED = 2;

  private static int rank(DownloadRow row) {
    return row.isActive() ? RANK_RUNNING : row.isStopped() ? RANK_STOPPED : RANK_COMPLETED;
  }

  /**
   * New rows go in after the others of their kind: running downloads on
   * top, then stopped ones, then history. Existing rows keep their place
   * when they change kind (stopped, restarted, finished).
   */
  private DownloadRow rowFor(String infoHash, int rank) {
    DownloadRow row = rowsByInfoHash.get(infoHash);
    if (row == null) {
      row = new DownloadRow();
      rowsByInfoHash.put(infoHash, row);
      int position = 0;
      while (position < rows.size() && rank(rows.get(position)) <= rank) {
        position++;
      }
      rows.add(position, row);
    }
    return row;
  }

  private MetaInfo stoppedMetaInfo(String infoHash, ResumeStateStore.Entry entry) {
    if (!stoppedMetaInfo.containsKey(infoHash)) {
      MetaInfo metaInfo = null;
      try {
        metaInfo = MetaInfoBuilder.fromFile(entry.metaFile);
      } catch (Exception e) {
        logger.warn("could not read saved torrent file " + entry.metaFile, e);
      }
      stoppedMetaInfo.put(infoHash, metaInfo);
    }
    return stoppedMetaInfo.get(infoHash);
  }

  private void forgetStoppedDetails() {
    for (Iterator<TorrentDownload> it = details.keySet().iterator(); it.hasNext(); ) {
      TorrentDownload download = it.next();
      if (!download.isRunning()) {
        if (details.get(download) == shownDetails) {
          shownDetails = null;
        }
        it.remove();
      }
    }
  }

  private void showDetails(DownloadRow row) {
    if (row == null) {
      shownDetails = null;
      detailsHolder.getChildren().setAll(muted("Select a download to see its details and statistics."));
    } else if (row.isStopped()) {
      shownDetails = null;
      detailsHolder.getChildren().setAll(muted(starting.contains(row.getStopped().infoHash.toString())
          ? "Starting: checking the data already downloaded to " + row.getOutputDirectory().getAbsolutePath()
              + ". Large downloads can take a while."
          : "Stopped. Press Start to resume downloading to " + row.getOutputDirectory().getAbsolutePath() + "."));
    } else if (!row.isActive()) {
      shownDetails = null;
      detailsHolder.getChildren().setAll(muted(
          "Finished downloading to " + row.getOutputDirectory().getAbsolutePath()
              + ". Statistics are only available while a download is running."));
    } else {
      DownloadDetails downloadDetails = details.computeIfAbsent(row.getDownload(), DownloadDetails::new);
      shownDetails = downloadDetails;
      detailsHolder.getChildren().setAll(downloadDetails.getNode());
    }
  }

  private void updateActions() {
    DownloadRow selected = table.getSelectionModel().getSelectedItem();
    boolean selectedIsStarting = selected != null && selected.isStopped()
        && starting.contains(selected.getStopped().infoHash.toString());
    startButton.setDisable(selected == null || !selected.isStopped() || selectedIsStarting);
    stopButton.setDisable(selected == null || !selected.isActive());
    // a download that's starting up can't be removed until it's running
    removeButton.setDisable(selected == null || selectedIsStarting);
    openFolderButton.setDisable(selected == null);
    removeButton.setTooltip(new Tooltip(selected != null && selected.getCompleted() != null
        ? "Remove from this list -- downloaded files are left on disk"
        : "Stop and forget this download -- it will not resume next time Freeloader starts"));
  }

  // --------------------------------------------------------------- actions

  private void showAddTorrent(File torrentFile) {
    new NewTorrentDownloadDialog(stage, environment, torrentFile).show();
  }

  private void showAddMagnet(String magnetUri) {
    new MagnetDownloadDialog(stage, magnetUri, this::startMagnetDownload).show();
  }

  private void startSelected() {
    DownloadRow selected = table.getSelectionModel().getSelectedItem();
    if (selected == null || !selected.isStopped()) {
      return;
    }
    final ResumeStateStore.Entry entry = selected.getStopped();
    if (!starting.add(entry.infoHash.toString())) {
      return; // already on its way
    }
    environment.getExecutor().execute(() -> {
      try {
        resumeOne(entry);
      } catch (Exception e) {
        logger.warn("could not restart download " + entry.infoHash, e);
        final String message = "Could not start the download in " + entry.outputDirectory + ":\n"
            + (e.getMessage() != null ? e.getMessage() : e.toString());
        Platform.runLater(() -> Dialogs.error(stage, "Start Failed", message));
      } finally {
        starting.remove(entry.infoHash.toString());
        Platform.runLater(this::refresh);
      }
    });
    refresh();
  }

  private void stopSelected() {
    DownloadRow selected = table.getSelectionModel().getSelectedItem();
    if (selected == null || !selected.isActive()) {
      return;
    }
    stopInBackground(selected.getDownload());
  }

  /**
   * Closing a download's connections can wait on network threads, so it
   * runs off the UI thread to keep the window responsive.
   */
  private void stopInBackground(TorrentDownload download) {
    Thread thread = new Thread(() -> {
      try {
        if (download.isRunning()) {
          download.stop();
        }
      } catch (IllegalStateException e) {
        // already stopped
      }
      Platform.runLater(this::refresh);
    }, "stop-download");
    thread.setDaemon(true);
    thread.start();
  }

  private void removeSelected() {
    DownloadRow selected = table.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    if (selected.isActive()) {
      TorrentDownload download = selected.getDownload();
      boolean confirmed = Dialogs.confirm(stage, "Remove Download",
          "Remove \"" + download.getMetaInfo().getName() + "\"?",
          "This stops the download and it will not resume next time Freeloader starts.\n"
              + "Already-downloaded file data is left on disk.");
      if (!confirmed) {
        return;
      }
      ResumeStateStore.remove(download.getInfoHash());
      stopInBackground(download);
    } else if (selected.isStopped()) {
      boolean confirmed = Dialogs.confirm(stage, "Remove Download",
          "Remove \"" + selected.nameProperty().get() + "\"?",
          "It will not resume next time Freeloader starts.\n"
              + "Already-downloaded file data is left on disk.");
      if (!confirmed) {
        return;
      }
      ResumeStateStore.remove(selected.getStopped().infoHash);
    } else {
      CompletedDownloadStore.remove(selected.getCompleted().infoHash);
    }
    refresh();
  }

  private void openSelectedFolder() {
    DownloadRow selected = table.getSelectionModel().getSelectedItem();
    if (selected == null) {
      return;
    }
    File directory = selected.getOutputDirectory();
    if (!directory.isDirectory()) {
      Dialogs.warning(stage, "Open Folder", directory.getAbsolutePath() + " no longer exists.");
      return;
    }
    hostServices.showDocument(directory.toURI().toString());
  }

  /**
   * Torrent files and magnet links (e.g. dragged out of a browser) can be
   * dropped anywhere on the window; this opens the matching dialog
   * prefilled, rather than starting anything straight away.
   */
  private void enableDropToAdd(Scene scene) {
    scene.setOnDragOver(event -> {
      if (droppedTorrentFile(event.getDragboard()) != null || MagnetDrop.find(event.getDragboard()) != null) {
        event.acceptTransferModes(TransferMode.COPY);
      }
      event.consume();
    });
    scene.setOnDragDropped(event -> {
      File torrentFile = droppedTorrentFile(event.getDragboard());
      String magnetUri = MagnetDrop.find(event.getDragboard());
      event.setDropCompleted(torrentFile != null || magnetUri != null);
      event.consume();
      // open the dialog after the drop has finished, not inside it
      Platform.runLater(() -> {
        if (torrentFile != null) {
          showAddTorrent(torrentFile);
        } else if (magnetUri != null) {
          showAddMagnet(magnetUri);
        }
      });
    });
  }

  private static File droppedTorrentFile(Dragboard dragboard) {
    if (dragboard.hasFiles()) {
      for (File file : dragboard.getFiles()) {
        if (file.getName().toLowerCase().endsWith(".torrent")) {
          return file;
        }
      }
    }
    return null;
  }

  /**
   * Kicks off the tracker/DHT/metadata lookup for a magnet link (this is
   * what can take a while) and shows its progress as a status line at
   * the bottom of the window, rather than in the already-closed dialog.
   * The status line disappears once the download actually starts (at
   * which point it shows up in the table) or on failure (an error dialog
   * is shown instead).
   */
  private void startMagnetDownload(MagnetUri magnetUri, File outputDirectory) {
    String description = magnetUri.getDisplayName() != null
        ? magnetUri.getDisplayName() : magnetUri.getInfoHash().toString();
    final Label statusText = new Label("Resolving \"" + description + "\"...");
    ProgressIndicator spinner = new ProgressIndicator();
    spinner.setPrefSize(16, 16);
    final HBox statusLine = new HBox(8, spinner, statusText);
    statusLine.setAlignment(Pos.CENTER_LEFT);
    statusLine.setPadding(new Insets(4, 0, 0, 0));
    magnetLookups.getChildren().add(statusLine);

    MagnetDownloadStarter starter = new MagnetDownloadStarter(environment);
    starter.start(magnetUri, outputDirectory, new MagnetDownloadStarter.StatusListener() {
      public void onStatus(final String message) {
        Platform.runLater(() -> statusText.setText(message));
      }

      public void onSuccess(TorrentDownload download) {
        Platform.runLater(() -> {
          magnetLookups.getChildren().remove(statusLine);
          refresh();
        });
      }

      public void onFailure(final String message) {
        Platform.runLater(() -> {
          magnetLookups.getChildren().remove(statusLine);
          Dialogs.error(stage, "Magnet Download Failed", message);
        });
      }
    });
  }

  /**
   * Exits without stopping the downloads one by one first: nothing needs
   * saving (resume state is written when a download is added) and the OS
   * closes the sockets anyway. Stopping them here used to freeze the app
   * on exit -- it ran on the UI thread and could deadlock against network
   * threads busy handling incoming data.
   */
  private void quit() {
    stage.hide();
    Thread exit = new Thread(() -> System.exit(0), "exit");
    exit.start();
    // if anything still holds the exit up (a stuck shutdown hook), don't
    // leave an invisible process running
    Thread watchdog = new Thread(() -> {
      try {
        Thread.sleep(EXIT_TIMEOUT_MS);
      } catch (InterruptedException e) {
        // exiting anyway
      }
      Runtime.getRuntime().halt(0);
    }, "exit-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
  }

  /**
   * Restarts whatever downloads were still active the last time the app
   * ran (see ResumeStateStore) -- both file-data resume (already-
   * downloaded pieces are re-validated automatically when a
   * TorrentDownload is constructed) and simply not losing track of what
   * was being downloaded in the first place. Runs on a background thread
   * since re-validating existing file data against piece hashes can take
   * a while for larger torrents.
   */
  private void resumePreviousDownloads() {
    final List<ResumeStateStore.Entry> entries = ResumeStateStore.loadAll();
    if (entries.isEmpty()) {
      return;
    }
    for (ResumeStateStore.Entry entry : entries) {
      starting.add(entry.infoHash.toString());
    }
    environment.getExecutor().execute(() -> {
      List<String> failed = new ArrayList<String>();
      for (ResumeStateStore.Entry entry : entries) {
        try {
          resumeOne(entry);
        } catch (Exception e) {
          logger.warn("could not resume previous download " + entry.infoHash, e);
          failed.add(entry.outputDirectory + " (" + entry.infoHash + ")");
        } finally {
          starting.remove(entry.infoHash.toString());
          Platform.runLater(this::refresh);
        }
      }
      if (!failed.isEmpty()) {
        final String message =
            "Could not resume " + failed.size() + " previous download(s):\n" + String.join("\n", failed);
        Platform.runLater(() -> Dialogs.warning(stage, "Resume Failed", message));
      }
    });
  }

  private void resumeOne(ResumeStateStore.Entry entry) throws Exception {
    MetaInfo metaInfo = MetaInfoBuilder.fromFile(entry.metaFile);
    TorrentDownloadConfiguration configuration =
        new TorrentDownloadConfiguration(metaInfo, entry.outputDirectory);
    configuration.setListeningPort(entry.listeningPort);
    configuration.setShareRatio(entry.shareRatio);
    TorrentDownload download = new TorrentDownload(environment, configuration);
    download.start();
  }

  // ----------------------------------------------------------------- cells

  private static TableCell<DownloadRow, Number> textCell(final Function<Number, String> format) {
    return new TableCell<DownloadRow, Number>() {
      protected void updateItem(Number value, boolean empty) {
        super.updateItem(value, empty);
        setText(empty || value == null ? null : format.apply(value));
      }
    };
  }

  /** Blank for rows that aren't running, whose speed is NaN. */
  private static class SpeedCell extends TableCell<DownloadRow, Number> {
    protected void updateItem(Number value, boolean empty) {
      super.updateItem(value, empty);
      setText(empty || value == null || Double.isNaN(value.doubleValue()) ? null : Format.rate(value.doubleValue()));
    }
  }

  private static class ProgressCell extends TableCell<DownloadRow, Number> {
    private final ProgressBar bar = new ProgressBar();
    private final Label percent = new Label();
    private final HBox box = new HBox(8, bar, percent);

    ProgressCell() {
      bar.setMaxWidth(Double.MAX_VALUE);
      HBox.setHgrow(bar, Priority.ALWAYS);
      percent.setMinWidth(48);
      box.setAlignment(Pos.CENTER_LEFT);
    }

    protected void updateItem(Number value, boolean empty) {
      super.updateItem(value, empty);
      if (empty || value == null || value.doubleValue() == DownloadRow.UNKNOWN_PROGRESS) {
        setGraphic(null);
      } else {
        bar.setProgress(value.doubleValue());
        percent.setText(Format.percent(value.doubleValue()));
        setGraphic(box);
      }
    }
  }
}
