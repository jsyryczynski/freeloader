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

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import freeloader.downloader.TorrentDownload;
import freeloader.downloader.TorrentDownloadConfiguration;
import freeloader.gui.FreeloaderApp;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class Freeloader {

  private static final String METAFILE_OPTION = "m";
  private static final String OUTPUTDIR_OPTION = "o";
  private static final String PORT_OPTION = "p";
  private static final String GUI_OPTION = "g";
  private static final String QUITONFINISH_OPTION = "q";
  private static final String LIMIT_OPTION = "l";
  private static final String HELP_OPTION = "h";

  private static final Logger logger = Logger.getLogger(Freeloader.class);

  public static void main(String[] arguments) throws Exception {
    FreeloaderConfiguration freeloaderConfiguration = loadFreeloaderConfiguration();
    configureStaticStuff(freeloaderConfiguration);

    CommandLine line = parseCommandLine(arguments);
    // checked before anything starts up
    int port = parsePort(line);
    double limitMBps = line.hasOption(LIMIT_OPTION) ? parseLimit(line.getOptionValue(LIMIT_OPTION)) : 0;

    logger.info("Freeloader " + Version.getLongVersionString());
    logArguments(arguments);

    logger.info("setting up environment");
    Environment environment = new Environment(freeloaderConfiguration);

    environment.getNetworkManager().getDownloadLimiter().setBytesPerSecond(Math.round(limitMBps * 1000000));

    boolean showGui = line.hasOption(GUI_OPTION) || !line.hasOption(METAFILE_OPTION);
    if (line.hasOption(METAFILE_OPTION)) {
      CommandLineDownload cli = new CommandLineDownload(environment);
      TorrentDownload download = cli.start(
          line.getOptionValue(METAFILE_OPTION), new File(line.getOptionValue(OUTPUTDIR_OPTION)), port);
      if (download == null) {
        System.exit(1);
      }
      if (!showGui) {
        cli.showProgressUntilFinished(download);
        System.exit(0);
      }
      download.getConfiguration().setQuitOnFinish(line.hasOption(QUITONFINISH_OPTION));
    }

    if (showGui) {
      logger.info("starting GUI");
      FreeloaderApp.run(environment);
    }
  }

  private static void configureStaticStuff(FreeloaderConfiguration configuration) throws IOException {
    InputStream stream = ClassLoader.getSystemResourceAsStream(configuration.getLoggingPropertyFile());
    if (stream != null) {
      Properties properties = new Properties();
      properties.load(stream);
      PropertyConfigurator.configure(properties);
    } else {
      System.err.println("unable to load logging property file");
      System.exit(1);
    }
  }

  private static FreeloaderConfiguration loadFreeloaderConfiguration() {
    FreeloaderConfiguration configuration = FreeloaderConfiguration.fromPropertiesFile();
    if (configuration == null) {
      System.err.println("Error loading property file.");
      System.exit(1);
    }
    return configuration;
  }

  private static void logArguments(String[] arguments) {
    StringBuilder commandLine = new StringBuilder();
    commandLine.append("Arguments: ");
    commandLine.append(String.join(" ", arguments));
    logger.info(commandLine.toString());
  }

  private static int parsePort(CommandLine line) {
    if (!line.hasOption(PORT_OPTION)) {
      return TorrentDownloadConfiguration.DEFAULT_LISTENING_PORT;
    }
    try {
      return Integer.parseInt(line.getOptionValue(PORT_OPTION));
    } catch (NumberFormatException e) {
      System.err.println("Not a port number: " + line.getOptionValue(PORT_OPTION));
      System.exit(1);
      return 0;
    }
  }

  static double parseLimit(String value) {
    try {
      double megabytesPerSecond = Double.parseDouble(value.trim().replace(',', '.'));
      if (megabytesPerSecond > 0 && !Double.isInfinite(megabytesPerSecond)) {
        return megabytesPerSecond;
      }
    } catch (NumberFormatException e) {
      // reported below
    }
    System.err.println("The speed limit is in MB/s, e.g. --limit 1.5");
    System.exit(1);
    return 0;
  }

  private static CommandLine parseCommandLine(String[] args) {
    CommandLineParser parser = new DefaultParser();
    Options options = createOptions();

    try {
      // reject unknown options (e.g. ones that no longer exist) rather than
      // quietly ignoring them and carrying on
      CommandLine commandLine = parser.parse(options, args, false);
      if (commandLine.hasOption(HELP_OPTION)) {
        printHelpTextAndExit(options);
      }
      if (commandLine.getArgs().length > 0) {
        System.err.println("Unexpected argument: " + commandLine.getArgs()[0]);
        printHelpTextAndExit(options);
      }
      if (commandLine.hasOption(METAFILE_OPTION) != commandLine.hasOption(OUTPUTDIR_OPTION)) {
        System.err.println("Downloading needs both -m (the torrent) and -o (where to save it).");
        printHelpTextAndExit(options);
      }
      return commandLine;
    } catch (ParseException e) {
      System.err.println(e.getMessage());
      printHelpTextAndExit(options);
    }
    return null;
  }

  private static void printHelpTextAndExit(Options options) {
    try {
      HelpFormatter.builder().setShowSince(false).get().printHelp("Freeloader", null, options, null, true);
    } catch (IOException e) {
      // printing to the console; nothing more to do
    }
    System.exit(1);
  }

  private static Options createOptions() {
    Options options = new Options();
    options.addOption(Option.builder(METAFILE_OPTION)
        .longOpt("metafile")
        .desc("torrent file or magnet link to download")
        .argName("torrent or magnet")
        .hasArg()
        .get());
    options.addOption(Option.builder(OUTPUTDIR_OPTION)
        .longOpt("output")
        .desc("folder to save the download in")
        .argName("directory")
        .hasArg()
        .get());
    options.addOption(Option.builder(PORT_OPTION)
        .longOpt("port")
        .desc("listening port (default: " + TorrentDownloadConfiguration.DEFAULT_LISTENING_PORT + ")")
        .argName("port")
        .hasArg()
        .get());
    options.addOption(Option.builder(GUI_OPTION)
        .longOpt("gui")
        .desc("also open the window (without -m it always opens)")
        .get());
    options.addOption(Option.builder(QUITONFINISH_OPTION)
        .longOpt("quitonfinish")
        .desc("with --gui: quit once the download finishes (without --gui it always does)")
        .get());
    options.addOption(Option.builder(LIMIT_OPTION)
        .longOpt("limit")
        .desc("limit the download speed, in MB/s (the window has its own setting)")
        .argName("MB/s")
        .hasArg()
        .get());
    options.addOption(Option.builder(HELP_OPTION)
        .longOpt("help")
        .desc("display this help text")
        .get());
    return options;
  }
}
