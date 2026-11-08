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
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FullLogTest extends TestCase {

  private static final Logger logger = Logger.getLogger(FullLogTest.class);

  private File file;
  private Level levelBefore;

  protected void setUp() throws Exception {
    file = File.createTempFile("full-log", ".log", new File("target"));
    file.delete();
    levelBefore = Logger.getRootLogger().getLevel();
    Logger.getRootLogger().setLevel(Level.ERROR);
  }

  protected void tearDown() {
    FullLog.stop();
    Logger.getRootLogger().setLevel(levelBefore);
    file.delete();
  }

  public void testWritesDebugMessagesWhileRunning() throws Exception {
    FullLog.start(file);
    assertTrue(FullLog.isRunning());
    logger.debug("a debug message");
    logger.info("an info message");
    FullLog.stop();

    String log = read();
    assertTrue(log, log.contains("a debug message"));
    assertTrue(log, log.contains("an info message"));
  }

  public void testStopsWritingAndRestoresTheLevel() throws Exception {
    FullLog.start(file);
    assertEquals(Level.DEBUG, Logger.getRootLogger().getLevel());
    FullLog.stop();
    assertFalse(FullLog.isRunning());
    assertEquals(Level.ERROR, Logger.getRootLogger().getLevel());

    logger.error("after stopping");
    assertFalse(read().contains("after stopping"));
  }

  public void testStartingTwiceWritesEachMessageOnce() throws Exception {
    FullLog.start(file);
    FullLog.start(file);
    logger.debug("only once");
    FullLog.stop();

    String log = read();
    assertEquals(log, log.indexOf("only once"), log.lastIndexOf("only once"));
  }

  public void testOffUnlessStarted() {
    assertFalse(FullLog.isRunning());
    logger.debug("nobody writes this");
    assertFalse(file.exists());
  }

  private String read() throws Exception {
    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
  }
}
