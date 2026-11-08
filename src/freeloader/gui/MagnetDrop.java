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

import javafx.scene.control.TextField;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds a magnet: link in whatever a drag-and-drop carries. Browsers
 * offer a dragged link in several formats at once (URL, plain text,
 * HTML, Windows-specific UniformResourceLocator blobs, ...) and which of
 * them JavaFX decodes, and how, varies by browser -- so rather than
 * trusting any single one, every format is searched for the link.
 */
final class MagnetDrop {

  private static final Pattern MAGNET = Pattern.compile("magnet:\\?[^\\s\"'<>\\x00]+", Pattern.CASE_INSENSITIVE);

  private MagnetDrop() {
  }

  /** The first magnet link found in the drag, or null. */
  static String find(Dragboard dragboard) {
    String found = search(dragboard.hasUrl() ? dragboard.getUrl() : null);
    if (found == null) {
      found = search(dragboard.hasString() ? dragboard.getString() : null);
    }
    for (DataFormat format : dragboard.getContentTypes()) {
      if (found != null) {
        break;
      }
      found = search(decode(dragboard.getContent(format)));
    }
    return found;
  }

  /**
   * Lets a text field take a dropped magnet link (replacing its text),
   * like the old Swing dialog's field did.
   */
  static void acceptDrops(final TextField field) {
    field.setOnDragOver(event -> {
      if (find(event.getDragboard()) != null) {
        event.acceptTransferModes(TransferMode.COPY);
        event.consume();
      }
    });
    field.setOnDragDropped(event -> {
      String magnetUri = find(event.getDragboard());
      if (magnetUri != null) {
        field.setText(magnetUri);
      }
      event.setDropCompleted(magnetUri != null);
      event.consume();
    });
  }

  private static String decode(Object content) {
    if (content instanceof String) {
      return (String) content;
    }
    if (content instanceof ByteBuffer) {
      ByteBuffer buffer = ((ByteBuffer) content).duplicate();
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      // Windows hands some formats over as raw UTF-16 (the "...W"
      // variants) and others as 8-bit text; try both
      return new String(bytes, StandardCharsets.UTF_16LE) + "\n" + new String(bytes, StandardCharsets.ISO_8859_1);
    }
    return null;
  }

  private static String search(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = MAGNET.matcher(text);
    if (!matcher.find()) {
      return null;
    }
    // links taken out of HTML have their & escaped
    return matcher.group().replace("&amp;", "&");
  }
}
