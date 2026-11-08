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

import junit.framework.TestSuite;
import junit.textui.TestRunner;
import freeloader.bencoding.BEncodingTests;
import freeloader.dht.DHTests;
import freeloader.downloader.DownloaderTests;
import freeloader.network.NetworkTests;
import freeloader.stats.StatsTests;
import freeloader.storage.StorageTests;
import freeloader.torrent.HashTest;
import freeloader.torrent.MagnetMetadataFetcherTest;
import freeloader.torrent.MagnetUriTest;
import freeloader.torrent.MetaInfoBuilderTest;
import freeloader.torrent.MetaInfoTest;
import freeloader.torrent.PeerIdTest;
import freeloader.tracker.TrackerClientTest;
import freeloader.tracker.TrackerRequestTest;
import freeloader.tracker.TrackerResponseTest;
import freeloader.tracker.UdpTrackerClientTest;
import freeloader.util.UtilTests;

public class UnitTests {

  private static final Class[] testClasses = {
      CommandLineDownloadTest.class,
      VersionTest.class,
      DeliberatelyFailingTest.class,
      BuildScriptVersionsTest.class,
      freeloader.gui.FormatTest.class,
      HashTest.class,
      PeerIdTest.class,
      MetaInfoBuilderTest.class,
      MetaInfoTest.class,
      TrackerClientTest.class,
      TrackerRequestTest.class,
      TrackerResponseTest.class,
      UdpTrackerClientTest.class,
      MagnetUriTest.class,
      MagnetMetadataFetcherTest.class,
  };

  public static TestSuite suite() {
    TestSuite suite = new TestSuite("Unit Tests");

    for (Class testClass : testClasses) {
      suite.addTestSuite(testClass);
    }

    suite.addTest(BEncodingTests.suite());
    suite.addTest(DHTests.suite());
    suite.addTest(DownloaderTests.suite());
    suite.addTest(NetworkTests.suite());
    suite.addTest(StatsTests.suite());
    suite.addTest(StorageTests.suite());
    suite.addTest(UtilTests.suite());

    return suite;
  }

  public static void main(String[] args) {
    TestRunner.run(suite());
  }
}
