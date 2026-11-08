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
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * build-exe.bat downloads its own copies of the app's libraries, so their
 * versions are written there as well as in pom.xml. This keeps the two in
 * step: an update to one (by hand or by Dependabot) without the other
 * fails here.
 */
public class BuildScriptVersionsTest extends TestCase {

  private static final Pattern DEPENDENCY = Pattern.compile(
      "<dependency>[^<]*<groupId>([^<]+)</groupId>[^<]*<artifactId>([^<]+)</artifactId>[^<]*<version>([^<]+)</version>"
          + "([^<]*<scope>([^<]+)</scope>)?");
  private static final Pattern FETCH = Pattern.compile(
      "^call :fetch [^ ]+ ([^ ]+)/([^/ ]+)/([^/ ]+) ", Pattern.MULTILINE);

  public void testBuildScriptUsesThePomVersions() throws Exception {
    Map<String, String> pom = new HashMap<String, String>();
    Matcher dependency = DEPENDENCY.matcher(read("pom.xml"));
    while (dependency.find()) {
      if (!"test".equals(dependency.group(5))) {
        pom.put(dependency.group(2), dependency.group(3));
      }
    }
    assertFalse("no dependencies found in pom.xml", pom.isEmpty());

    Map<String, String> script = new HashMap<String, String>();
    Matcher fetch = FETCH.matcher(read("build-exe.bat"));
    while (fetch.find()) {
      script.put(fetch.group(2), fetch.group(3));
    }
    assertFalse("no downloads found in build-exe.bat", script.isEmpty());

    for (Map.Entry<String, String> library : pom.entrySet()) {
      assertEquals(library.getKey() + " in build-exe.bat", library.getValue(), script.get(library.getKey()));
    }
    // the JavaFX jars javafx-controls brings along come in the same version
    for (Map.Entry<String, String> download : script.entrySet()) {
      String expected = download.getKey().startsWith("javafx-")
          ? pom.get("javafx-controls") : pom.get(download.getKey());
      assertEquals(download.getKey() + " in build-exe.bat (pom.xml has no other version)",
          expected, download.getValue());
    }
  }

  private static String read(String file) throws Exception {
    return new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
  }
}
