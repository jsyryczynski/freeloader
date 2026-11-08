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

package freeloader.network;

import junit.framework.TestSuite;
import freeloader.network.packets.PacketTests;

public class NetworkTests {

  private static final Class[] testClasses = {
      ConnectionRepositoryTest.class,
      DownloadRateLimiterTest.class,
      EncryptedHandshakeTest.class,
      NeverUploadTest.class,
      PacketizerTest.class,
      PeerExchangeTest.class,
      SerializerTest.class,
  };

  public static TestSuite suite() {
    TestSuite suite = new TestSuite("Network Tests");

    for (Class testClass : NetworkTests.testClasses) {
      suite.addTestSuite(testClass);
    }

    suite.addTest(PacketTests.suite());

    return suite;
  }
}
