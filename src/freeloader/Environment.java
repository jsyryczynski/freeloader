/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2006 Patrick Moor <patrick@moor.ws>
 * Modified by Jan Syryczyński, 2022-2026.
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

import org.apache.log4j.Logger;
import freeloader.dht.DHTracker;
import freeloader.dht.TrackerState;
import freeloader.downloader.TorrentDownloadRepository;
import freeloader.network.BitTorrentListener;
import freeloader.network.ConnectionRepository;
import freeloader.network.NetworkManager;
import freeloader.stats.CounterRepository;
import freeloader.stats.RealCounterRepository;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class Environment {

  private final FreeloaderConfiguration configuration;

  private TorrentDownloadRepository repository;
  private NetworkManager networkManager;
  private Map<Integer, BitTorrentListener> listeners;
  private ExecutorService executor;
  private ScheduledExecutorService scheduledExecutor;
  private ConnectionRepository connectionRepository;
  private CounterRepository counterRepository;
  private DHTracker dhTracker;

  private static final Logger logger = Logger.getLogger(Environment.class);

  private static final int EXECUTOR_THREADS = 6;
  private static final int SCHEDULED_EXECUTOR_THREADS = 4;

  public Environment(FreeloaderConfiguration configuration) throws IOException {
    this.configuration = configuration;
    repository = new TorrentDownloadRepository();
    networkManager = new NetworkManager();
    listeners = new HashMap<Integer, BitTorrentListener>();
    executor = Executors.newFixedThreadPool(EXECUTOR_THREADS);
    scheduledExecutor = Executors.newScheduledThreadPool(SCHEDULED_EXECUTOR_THREADS);
  }

  public TorrentDownloadRepository getDownloadRepository() {
    return repository;
  }

  public NetworkManager getNetworkManager() {
    return networkManager;
  }

  public Executor getExecutor() {
    return executor;
  }

  public ScheduledExecutorService getScheduledExecutor() {
    return scheduledExecutor;
  }

  public synchronized BitTorrentListener getListener(int port) throws IOException {
    logger.info("requesting a listener on port " + port);
    BitTorrentListener listener = listeners.get(port);
    if (listener == null) {
      return createNewListener(port);
    }
    return listener;
  }

  private BitTorrentListener createNewListener(int port) throws IOException {
    BitTorrentListener listener = new BitTorrentListener(this, port);
    listeners.put(port, listener);
    return listener;
  }

  public synchronized BitTorrentListener getAnyListener() throws IOException {
    logger.info("requesting any listener");
    Collection<BitTorrentListener> bitTorrentListeners = listeners.values();
    if (!bitTorrentListeners.isEmpty()) {
      return bitTorrentListeners.iterator().next();
    }
    return createNewListener(randomListeningPort());
  }

  private int randomListeningPort() {
    return new Random().nextInt(10000) + 10000;
  }

  public synchronized ConnectionRepository getConnectionRepository() {
    if (connectionRepository == null) {
      connectionRepository = new ConnectionRepository();
      connectionRepository.startMaintenanceThread(scheduledExecutor);
    }
    return connectionRepository;
  }

  public FreeloaderConfiguration getConfiguration() {
    return configuration;
  }

  public CounterRepository getCounterRepository() {
    if (counterRepository == null) {
      createCounterRepository();
    }
    return counterRepository;
  }

  private synchronized void createCounterRepository() {
    if (counterRepository == null) {
      RealCounterRepository repo =
        RealCounterRepository.fromResource("stats.properties");
      if (configuration.doLogStats()) {
        repo.setFileForWriting(configuration.getStatsLogFile());
      }
      counterRepository = repo;
    }
  }

  /**
   * A DHT node (BEP 5), created and bootstrapped into the public DHT
   * lazily on first use. Used to find peers for trackerless (DHT-only)
   * magnet links, where there's no tracker to ask for a peer list.
   */
  public synchronized DHTracker getDHTracker() throws IOException {
    if (dhTracker == null) {
      dhTracker = new DHTracker(this, TrackerState.createNew(randomListeningPort()));
    }
    return dhTracker;
  }
}
