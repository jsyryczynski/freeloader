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


package freeloader;

import junit.framework.TestCase;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionTest extends TestCase {

  public void testVersionComesFromVersionProperties() {
    assertTrue(Version.getShortVersionString().matches("[0-9]+[.][0-9]+[.][0-9]+"));
  }

  /** pom.xml repeats the version; it must not drift from version.properties. */
  public void testPomHasTheSameVersion() throws Exception {
    String pom = new String(Files.readAllBytes(Paths.get("pom.xml")), StandardCharsets.UTF_8);
    // the project's own version: the first <version> after its artifactId
    Matcher matcher = Pattern.compile("<artifactId>freeloader</artifactId>[^<]*<version>([^<]+)</version>")
        .matcher(pom);
    assertTrue("no project version in pom.xml", matcher.find());
    assertEquals(Version.getShortVersionString(), matcher.group(1));
  }
}
