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


package freeloader.network;

import freeloader.network.packets.ChokePacket;
import freeloader.network.packets.InterestedPacket;
import freeloader.network.packets.PacketFactory;
import freeloader.network.packets.PiecePacket;
import freeloader.network.packets.RequestPacket;
import freeloader.network.packets.UnchokePacket;
import junit.framework.TestCase;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Freeloader never uploads: it never sends file data (a piece message)
 * and never lets a peer ask for any (an unchoke message). See also
 * NeverUploadEndToEndTest, which checks this on a real connection.
 */
public class NeverUploadTest extends TestCase {

  private static final String PIECE = "freeloader/network/packets/PiecePacket";
  private static final String UNCHOKE = "freeloader/network/packets/UnchokePacket";

  public void testPiecesCannotBeSent() {
    assertRefused(new PiecePacket(0, 0, new byte[16384]));
  }

  public void testUnchokesCannotBeSent() {
    assertRefused(new UnchokePacket());
  }

  public void testOtherMessagesStillGoOut() {
    Serializer serializer = new Serializer(new PacketFactory());
    serializer.addPacket(new InterestedPacket());
    serializer.addPacket(new ChokePacket());
    assertTrue(serializer.dataAvailable() > 0);
  }

  /**
   * No code in the app creates a piece or an unchoke message: only those
   * classes themselves do, when they decode one that arrived.
   */
  public void testNoAppCodeCreatesPiecesOrUnchokes() throws IOException {
    Path classes = Paths.get("target", "classes");
    assertTrue("compiled classes not found in " + classes.toAbsolutePath(), Files.isDirectory(classes));
    List<String> offenders = new ArrayList<String>();
    int scanned = 0;
    try (Stream<Path> files = Files.walk(classes)) {
      for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
        ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
        String owner = model.thisClass().asInternalName();
        scanned++;
        for (MethodModel method : model.methods()) {
          if (method.code().isEmpty()) {
            continue;
          }
          for (CodeElement element : method.code().get()) {
            if (element instanceof NewObjectInstruction) {
              String created = ((NewObjectInstruction) element).className().asInternalName();
              if (isForbidden(created) && !owner.startsWith(created)) {
                offenders.add(owner + "." + method.methodName().stringValue() + " creates " + created);
              }
            }
          }
        }
      }
    }
    assertTrue("only " + scanned + " classes found", scanned > 100);
    assertTrue("code that could upload: " + offenders, offenders.isEmpty());
  }

  /** Requests from peers are counted and dropped, never answered. */
  public void testRequestsAreNeverAnswered() throws IOException {
    Path compiled = Paths.get("target", "classes", "freeloader", "network", "BitTorrentConnection.class");
    ClassModel model = ClassFile.of().parse(Files.readAllBytes(compiled));
    for (MethodModel method : model.methods()) {
      String name = method.methodName().stringValue();
      if (name.equals("handleRequestPacket") || name.equals("handleCancelPacket")) {
        for (CodeElement element : method.code().get()) {
          if (element instanceof InvokeInstruction) {
            String called = ((InvokeInstruction) element).name().stringValue();
            assertFalse(name + " sends something via " + called,
                called.equals("sendPacket") || called.equals("safeSend") || called.startsWith("send"));
          }
        }
      }
    }
    // the request type is still understood, so it can be dropped
    assertNotNull(new RequestPacket(0, 0, 16384).getBlock());
  }

  private static boolean isForbidden(String internalName) {
    return internalName.equals(PIECE) || internalName.equals(UNCHOKE);
  }

  private static void assertRefused(freeloader.network.packets.Packet packet) {
    Serializer serializer = new Serializer(new PacketFactory());
    try {
      serializer.addPacket(packet);
      fail("a " + packet.getClass().getSimpleName() + " was accepted for sending");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("never uploads"));
    }
    assertEquals("nothing may be queued", 0, serializer.dataAvailable());
  }
}
