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

package freeloader.torrent;

import org.apache.log4j.Logger;
import freeloader.util.Urls;
import freeloader.bencoding.BDecoder;
import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.BInteger;
import freeloader.bencoding.BList;
import freeloader.bencoding.BString;
import freeloader.bencoding.ParseException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MetaInfoBuilder {

  private static Logger logger = Logger.getLogger(MetaInfoBuilder.class);

  public static final String COMMENT_FIELD       = "comment";
  public static final String ANNOUNCE_FIELD      = "announce";
  public static final String ANNOUNCE_LIST_FIELD = "announce-list";
  public static final String CREATION_DATE_FIELD = "creation date";
  public static final String PIECE_LENGTH_FIELD  = "piece length";
  public static final String NAME_FIELD          = "name";
  public static final String PIECES_FIELD        = "pieces";
  public static final String LENGTH_FIELD        = "length";
  public static final String PATH_FIELD          = "path";
  public static final String FILES_FIELD         = "files";
  public static final String INFO_FIELD          = "info";

  public static MetaInfo fromStream(InputStream inputStream) throws IOException, ParseException {
    BEntity entity = new BDecoder().decode(inputStream);
    if (!(entity instanceof BDictionary)) {
      throw new ParseException("root is not a dictionary entity");
    }
    return fromDictionary((BDictionary) entity);
  }

  public static MetaInfo fromFile(File file) throws IOException, ParseException {
    FileInputStream inputStream = new FileInputStream(file);
    MetaInfo metaInfo = fromStream(inputStream);
    inputStream.close();
    return metaInfo;
  }

  public static MetaInfo fromDictionary(BDictionary dictionary) throws ParseException {
    URL announceUrl = extractAnnounceUrl(dictionary);
    String comment = extractComment(dictionary);
    Date creationDate = extractCreationDate(dictionary);

    BEntity infoEntity = dictionary.getByString(INFO_FIELD);
    assertNotNull(infoEntity, INFO_FIELD);
    assertInstanceof(infoEntity, INFO_FIELD, BDictionary.class);
    BDictionary info = (BDictionary) infoEntity;

    Hash infoHash = calculateInfoHash(info);
    int pieceLength = extractPieceLength(info);
    String name = extractName(info);
    Hash[] pieceHashes = extractPieceHashes(info);

    MetaInfo metaInfo;
    if (isSingleFileTorrent(info)) {
      long length = extractLength(info);
      metaInfo = new SingleFileMetaInfo(infoHash, announceUrl, comment, creationDate, pieceLength, name, pieceHashes, length);
    } else {
      MetaInfo.FileInfo[] fileInfos = extractFileInformations(info);
      metaInfo = new MultiFileMetaInfo(infoHash, announceUrl, comment, creationDate, pieceLength, name, pieceHashes, fileInfos);
    }
    metaInfo.setTrackers(extractTrackers(dictionary));
    return metaInfo;
  }

  /**
   * "announce" plus every entry of "announce-list" (BEP 12: a list of
   * tiers, each a list of tracker URLs), deduplicated, in order. Entries
   * that aren't strings or valid URIs are skipped rather than failing the
   * whole torrent -- plenty of real torrents carry the odd broken one.
   */
  private static List<URI> extractTrackers(BDictionary dictionary) {
    Set<URI> trackers = new LinkedHashSet<URI>();
    addTracker(trackers, dictionary.getByString(ANNOUNCE_FIELD));
    BEntity announceList = dictionary.getByString(ANNOUNCE_LIST_FIELD);
    if (announceList instanceof BList) {
      for (Object tier : (BList<?>) announceList) {
        if (tier instanceof BList) {
          for (Object tracker : (BList<?>) tier) {
            addTracker(trackers, (BEntity) tracker);
          }
        }
      }
    }
    return new ArrayList<URI>(trackers);
  }

  private static void addTracker(Set<URI> trackers, BEntity entity) {
    if (!(entity instanceof BString)) {
      return;
    }
    try {
      URI uri = new URI(entity.toString().trim());
      if (uri.getScheme() != null) {
        trackers.add(uri);
      }
    } catch (URISyntaxException e) {
      logger.info("skipping unparseable tracker url: " + entity);
    }
  }

  private static MetaInfo.FileInfo[] extractFileInformations(BDictionary info) throws ParseException {
    BEntity filesEntity = info.getByString(FILES_FIELD);
    assertNotNull(filesEntity, FILES_FIELD);
    assertInstanceof(filesEntity, FILES_FIELD, BList.class);
    BList<BDictionary> files = (BList<BDictionary>) filesEntity;
    return extractFileInfos(files);
  }

  private static MetaInfo.FileInfo[] extractFileInfos(BList<BDictionary> files) throws ParseException {
    MetaInfo.FileInfo[] result = new MetaInfo.FileInfo[files.size()];
    int i = 0;
    for (BDictionary dictionary : files) {
      result[i++] = extractFileInfo(dictionary);
    }
    return result;
  }

  private static MetaInfo.FileInfo extractFileInfo(BDictionary dictionary) throws ParseException {
    String[] path = extractPath(dictionary);
    long length = extractLength(dictionary);
    return new MetaInfo.FileInfo(path, length);
  }

  private static boolean isSingleFileTorrent(BDictionary infoDictionary) throws ParseException {
    boolean hasLength = infoDictionary.getByString(LENGTH_FIELD) != null;
    boolean hasFiles = infoDictionary.getByString(FILES_FIELD) != null;
    if (hasLength && !hasFiles) {
      return true;
    } else if (!hasLength && hasFiles) {
      return false;
    } else {
      throw new ParseException("has either length and files field or none of them");
    }
  }

  private static String[] extractPath(BDictionary dictionary) throws ParseException {
    BEntity pathEntity = dictionary.getByString(PATH_FIELD);
    assertNotNull(pathEntity, PATH_FIELD);
    assertInstanceof(pathEntity, PATH_FIELD, BList.class);
    BList<BString> paths = (BList<BString>) pathEntity;
    String[] pathArray = new String[paths.size()];
    int i = 0;
    for (BString entry : paths) {
      pathArray[i++] = entry.toString();
    }
    return pathArray;
  }

  private static long extractLength(BDictionary info) throws ParseException {
    BEntity lengthEntity = info.getByString(LENGTH_FIELD);
    assertNotNull(lengthEntity, LENGTH_FIELD);
    assertInstanceof(lengthEntity, LENGTH_FIELD, BInteger.class);
    return ((BInteger) lengthEntity).longValue();
  }

  private static Hash[] extractPieceHashes(BDictionary info) throws ParseException {
    BEntity piecesEntity = info.getByString(PIECES_FIELD);
    assertNotNull(piecesEntity, PIECES_FIELD);
    assertInstanceof(piecesEntity, PIECES_FIELD, BString.class);
    return Hash.fromConcatenatedByteArrays(((BString) piecesEntity).getBytes());
  }

  private static String extractName(BDictionary info) throws ParseException {
    BEntity nameEntity = info.getByString(NAME_FIELD);
    assertNotNull(nameEntity, NAME_FIELD);
    assertInstanceof(nameEntity, NAME_FIELD, BString.class);
    return nameEntity.toString();
  }

  private static int extractPieceLength(BDictionary info) throws ParseException {
    BEntity lengthEntity = info.getByString(PIECE_LENGTH_FIELD);
    assertNotNull(lengthEntity, PIECE_LENGTH_FIELD);
    assertInstanceof(lengthEntity, PIECE_LENGTH_FIELD, BInteger.class);
    return ((BInteger) lengthEntity).intValue();
  }

  private static Hash calculateInfoHash(BDictionary info) throws ParseException {
    try {
      return Hash.forByteArray(info.encode());
    } catch (IOException e) {
      throw new ParseException("could not calculate info hash", e);
    }
  }

  private static Date extractCreationDate(BDictionary dictionary) throws ParseException {
    BEntity dateEntity = dictionary.getByString(CREATION_DATE_FIELD);
    if (dateEntity == null) {
      logger.warn("missing Creation Date in torrent info, continuing anyway");
      return null;
    }
    assertInstanceof(dateEntity, CREATION_DATE_FIELD, BInteger.class);
    long timestamp = ((BInteger) dateEntity).longValue();
    return new Date(1000 * timestamp);
  }

  private static String extractComment(BDictionary dictionary) throws ParseException {
    BEntity commentEntity = dictionary.getByString(COMMENT_FIELD);
    if (commentEntity == null) {
      // comment is optional
      return null;
    }
    assertInstanceof(commentEntity, COMMENT_FIELD, BString.class);
    return commentEntity.toString();
  }

  private static URL extractAnnounceUrl(BDictionary dictionary) throws ParseException {
    BEntity announceEntity = dictionary.getByString(ANNOUNCE_FIELD);
    if (announceEntity == null) {
      // announce is optional: a trackerless (DHT-only) torrent has no
      // tracker URL at all, peers are found via the DHT instead
      return null;
    }
    assertInstanceof(announceEntity, ANNOUNCE_FIELD, BString.class);
    String announce = announceEntity.toString().trim();
    String lower = announce.toLowerCase();
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
      // a udp:// (or other non-HTTP) tracker: java.net.URL can't even
      // represent it, and the TrackerClient this URL is for only speaks
      // HTTP. It's still in getTrackers(), which PeerFinder handles.
      return null;
    }
    try {
      return Urls.lenient(announce);
    } catch (MalformedURLException e) {
      throw new ParseException("could not parse announce url: " + announce, e);
    }
  }

  private static void assertInstanceof(BEntity entity, String fieldName, Class<? extends BEntity> clazz)
      throws ParseException {
    if (!(clazz.isAssignableFrom(entity.getClass()))) {
      throw new ParseException("\"" + fieldName + "\" field is of wrong type");
    }
  }

  private static void assertNotNull(BEntity nameEntity, String fieldname) throws ParseException {
    if (nameEntity == null) {
      throw new ParseException("missing \"" + fieldname + "\" field");
    }
  }
}
