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

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class FreeloaderConfiguration {

  private Properties properties;

  private static final String DEFAULT_PROPERTY_FILE = "freeloader.properties";
  private static final String SYSTEM_PROPERTY_KEY = "freeloader.mainproperty";

  public static FreeloaderConfiguration fromPropertiesFile() {
    try {
      return new FreeloaderConfiguration(getPropertyFileName());
    } catch (IOException e) {
      return null;
    }
  }

  private static String getPropertyFileName() {
    String systemProperty = System.getProperty(SYSTEM_PROPERTY_KEY);
    if (systemProperty != null) {
      return systemProperty;
    } else {
      return DEFAULT_PROPERTY_FILE;
    }
  }

  private FreeloaderConfiguration(String name) throws IOException {
    InputStream stream = ClassLoader.getSystemResourceAsStream(name);
    if (stream != null) {
      properties = new Properties();
      properties.load(stream);
    } else {
      throw new IOException("stream is not valid");
    }
  }

  public boolean doLogStats() {
    return readBooleanProperty("stats.enable");
  }

  /** The statistics file; ${java.io.tmpdir} in it means the temp folder. */
  public String getStatsLogFile() {
    String file = properties.getProperty("stats.file");
    return file == null ? null : file.replace("${java.io.tmpdir}", System.getProperty("java.io.tmpdir"));
  }

  private boolean readBooleanProperty(String key) {
    return Boolean.parseBoolean(properties.getProperty(key));
  }

  public String getLoggingPropertyFile() {
    return properties.getProperty("logging.config");
  }
}
