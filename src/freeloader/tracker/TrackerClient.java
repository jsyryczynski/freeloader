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

package freeloader.tracker;

import org.apache.log4j.Logger;
import freeloader.util.Urls;
import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.ParseException;
import freeloader.downloader.TorrentDownload;
import freeloader.downloader.peermanager.PeerManager;
import freeloader.util.ByteUtil;
import freeloader.util.SystemTimeSource;
import freeloader.util.TimeSource;
import freeloader.util.URLFetcher;

import java.io.IOException;
import java.net.URL;
import java.util.function.Function;

public class TrackerClient {

  private final URL baseUrl;
  private final PeerManager peerManager;
  private final TorrentDownload download;
  private byte[] key = ByteUtil.randomByteArray(8);
  private TrackerRequest.Event nextEvent;
  private long lastSuccessfullAnnounce = 0;
  private BDecoder decoder = new BDecoder();
  private Function<URL, URLFetcher> factory = URLFetcher::new;
  private TimeSource time = SystemTimeSource.INSTANCE;
  private long announceInterval;

  private static final Logger logger =
      Logger.getLogger(TrackerClient.class);

  public TrackerClient(URL announceUrl, PeerManager peerManager, TorrentDownload download) {
    baseUrl = announceUrl;
    this.peerManager = peerManager;
    this.download = download;
    nextEvent = TrackerRequest.Event.STARTED;
    announceInterval = download.getConfiguration().getInitialAnnounceInterval();
  }

  private TrackerResponse getTrackerResponse(TrackerRequest request) {
    try {
      URL url = Urls.lenient(baseUrl + "?" + request.getHttpEncodedExtendedParameters());
      logger.debug("sending tracker announce: " + url);
      URLFetcher fetcher = factory.apply(url);
      byte[] data = fetcher.fetch();
      if (data == null) {
        return null;
      }
      BEntity entity = decoder.decode(data);
      if (entity == null || !(entity instanceof BDictionary)) {
        logger.warn("error in tracker response. entity is " + entity);
        return null;
      }
      return new TrackerResponse((BDictionary) entity);
    } catch (IOException e) {
      logger.warn("exception during tracker query", e);
    } catch (ParseException e) {
      logger.warn("exception during tracker query", e);
    }
    return null;
  }

  public void setURLFetcherFactory(Function<URL, URLFetcher> factory) {
    this.factory = factory;
  }

  public void announce() {
    if (baseUrl == null) {
      // trackerless (DHT-only) torrent, there's no tracker to announce to
      return;
    }
    if (!timeForAnAnnounce()) {
      return;
    }
    logger.info("announcing to tracker");
    TrackerRequest request = new TrackerRequestBuilder().createTrackerRequest(download, nextEvent);
    request.setKey(key);
    TrackerResponse response = getTrackerResponse(request);
    logger.info("tracker response: " + response);
    if (response != null) {
      peerManager.addPeersFromTrackerResponse(response.getPeerInformationCollection());
      lastSuccessfullAnnounce = time.getTime();
      recalculateAnnounceInterval(response.getAnnounceInterval(), response.getMinimalAnnounceInterval());
      if (nextEvent == TrackerRequest.Event.STARTED) {
        nextEvent = null;
      }
      updateStatistics(response);
    }
  }

  private void updateStatistics(TrackerResponse response) {
    download.getCounterRepository().getCounter("tracker.announces").increase(1);
    download.getCounterRepository().getCounter("tracker.seeds").set(response.getCompletePeerCount());
    download.getCounterRepository().getCounter("tracker.leechers").set(response.getIncompletePeerCount());
  }

  private void recalculateAnnounceInterval(long regularInterval, long minimalInterval) {
    minimalInterval = Math.max(minimalInterval * 1000, 300 * 1000);
    announceInterval = (long) (announceInterval * 1.25);
    announceInterval = Math.max(10 * 1000, announceInterval);
    announceInterval = Math.min(minimalInterval, announceInterval);
    logger.debug("using announce interval of " + announceInterval / 1000 + " seconds");
  }

  private boolean timeForAnAnnounce() {
    return time.getTime() > lastSuccessfullAnnounce + announceInterval;
  }

  public void setKey(byte[] key) {
    this.key = key;
  }
}
