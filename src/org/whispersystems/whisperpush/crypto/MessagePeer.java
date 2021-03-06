/**
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.whispersystems.whisperpush.crypto;

import android.content.Context;

import org.whispersystems.whisperpush.database.DatabaseFactory;

public class MessagePeer {

    private final long canonicalAddress;

    public MessagePeer(Context context, String canonicalPeerNumber) {
        this.canonicalAddress = DatabaseFactory.getAddressDatabase(context)
                .getCanonicalAddressFromNumber(canonicalPeerNumber);
    }

    public long getCanonicalAddress(Context context) {
        return canonicalAddress;
    }
}