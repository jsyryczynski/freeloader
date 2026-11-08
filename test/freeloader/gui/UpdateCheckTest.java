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

import junit.framework.TestCase;

/** UpdateCheck's reading of GitHub's answer; no network involved. */
public class UpdateCheckTest extends TestCase {

  public void testReadsTheTagFromGitHubsAnswer() {
    String json = "{\"url\": \"https://api.github.com/x\", \"tag_name\": \"v0.3.0\", \"name\": \"Freeloader 0.3.0\"}";
    assertEquals("v0.3.0", UpdateCheck.latestTag(json));
    assertEquals("v1.2.3", UpdateCheck.latestTag("{\"tag_name\":\"v1.2.3\"}"));
  }

  public void testNoTagInAnAnswerWithoutOne() {
    assertNull(UpdateCheck.latestTag("{\"message\": \"Not Found\"}"));
    assertNull(UpdateCheck.latestTag(""));
  }

  public void testLaterVersionsAreNewer() {
    assertTrue(UpdateCheck.isNewer("v0.2.2", 0, 2, 1));
    assertTrue(UpdateCheck.isNewer("v0.3.0", 0, 2, 9));
    assertTrue(UpdateCheck.isNewer("v1.0.0", 0, 9, 9));
    // numbers, not text: 10 comes after 9
    assertTrue(UpdateCheck.isNewer("v0.10.0", 0, 9, 0));
    assertTrue(UpdateCheck.isNewer("0.2.2", 0, 2, 1));
  }

  public void testTheSameOrAnEarlierVersionIsNotNewer() {
    assertFalse(UpdateCheck.isNewer("v0.2.1", 0, 2, 1));
    assertFalse(UpdateCheck.isNewer("v0.2.0", 0, 2, 1));
    assertFalse(UpdateCheck.isNewer("v0.9.9", 1, 0, 0));
  }

  public void testTagsThatAreNotAVersionAreNotNewer() {
    assertFalse(UpdateCheck.isNewer("nightly", 0, 2, 1));
    assertFalse(UpdateCheck.isNewer("v0.3", 0, 2, 1));
    assertFalse(UpdateCheck.isNewer("v0.3.0-beta", 0, 2, 1));
    assertFalse(UpdateCheck.isNewer("v99999999999.0.0", 0, 2, 1));
  }
}
