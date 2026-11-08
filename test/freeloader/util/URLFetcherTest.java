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

package freeloader.util;

import freeloader.util.Urls;
import freeloader.http.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;

public class URLFetcherTest extends ExtendedTestCase {

  public void testSuccessfulFetch() throws Exception {
    final byte[] originalData = new byte[128];
    final String[] errorMessage = new String[1];
    rnd.nextBytes(originalData);

    HttpServer.RequestHandler handler = new HttpServer.RequestHandler() {
      public void handleRequest(String[] request, OutputStream result) throws IOException {
        // java.net.HttpURLConnection on modern JDKs strictly requires a
        // well-formed HTTP status line and headers before the body; older
        // JDKs tolerated the raw body-only response this test used to send.
        String header = "HTTP/1.1 200 OK\r\n"
            + "Content-Type: application/octet-stream\r\n"
            + "Content-Length: " + originalData.length + "\r\n"
            + "Connection: close\r\n"
            + "\r\n";
        result.write(header.getBytes("US-ASCII"));
        result.write(originalData);
        if (!request[0].contains("GET /theFile HTTP")) {
          errorMessage[0] = "GET request was not valid";
        }
      }
    };

    HttpServer server = new HttpServer(handler);

    int waited = 0;
    while (!server.isReady() && waited < 1000) {
      Thread.sleep(10);
      waited += 10;
    }
    
    URL url = Urls.lenient("http://localhost:" + server.getListeningPort() + "/theFile");
    URLFetcher fetcher = new URLFetcher(url);

    byte[] data = fetcher.fetch();
    assertArrayEquals(originalData, data);

    server.stop();

    if (errorMessage[0] != null) {
      fail(errorMessage[0]);
    }

    waited = 0;
    while (!server.isStopped() && waited < 1000) {
      Thread.sleep(10);
      waited += 10;
    }
  }

  public void testUnsuccessfulFetch() throws IOException {
    URL url = Urls.lenient("http://localhost:13000/theFile");
    URLFetcher fetcher = new URLFetcher(url);
    byte[] data = fetcher.fetch();

    assertNull(data);
  }

  public void testSimpleConsistency() throws MalformedURLException {
    URL url = Urls.lenient("http://tracker.example.com/");
    URLFetcher fetcher = new URLFetcher(url);
    assertEquals(url, fetcher.getUrl());
  }
}
