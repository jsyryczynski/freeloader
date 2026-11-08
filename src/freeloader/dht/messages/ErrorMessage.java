/*
 * Freeloader - No upload BitTorrent Client
 * Copyright (C) 2006 Patrick Moor <patrick@moor.ws>
 * Modified by Jan Syryczyński, 2022-2026.
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

package freeloader.dht.messages;

import freeloader.bencoding.BDictionary;
import freeloader.bencoding.BEntity;
import freeloader.bencoding.BInteger;
import freeloader.bencoding.BList;
import freeloader.bencoding.BString;
import freeloader.dht.TransactionId;

public class ErrorMessage extends DHTMessage {

  private final int errorCode;
  private final String errorMessage;

  public static final int GENERIC_ERROR = 201;
  public static final int SERVER_ERROR = 202;
  public static final int PROTOTOL_ERROR = 203;
  public static final int METHOD_UNKNOWN = 204;

  public ErrorMessage(TransactionId transactionId, int errorCode, String errorMessage) {
    super(ERROR_MESSAGE_TYPE, transactionId);
    this.errorCode = errorCode;
    this.errorMessage = errorMessage;
  }

  public void populateDictionary(BDictionary<BEntity> dictionary) {
    BList<BEntity> list = new BList<BEntity>();
    list.add(new BInteger(errorCode));
    list.add(new BString(errorMessage));
    dictionary.put(new BString("e"), list);
    super.populateDictionary(dictionary);
  }
}
