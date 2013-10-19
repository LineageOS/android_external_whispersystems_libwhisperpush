/*
 * Copyright (C) 2013 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.whispersystems.whisperpush.service;


import android.app.Activity;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver.PendingResult;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;
import android.util.Pair;

import org.whispersystems.textsecure.crypto.AttachmentCipherInputStream;
import org.whispersystems.textsecure.crypto.InvalidMessageException;
import org.whispersystems.textsecure.crypto.MasterSecret;
import org.whispersystems.textsecure.directory.Directory;
import org.whispersystems.textsecure.directory.NotInDirectoryException;
import org.whispersystems.textsecure.push.ContactTokenDetails;
import org.whispersystems.textsecure.push.IncomingPushMessage;
import org.whispersystems.textsecure.push.PushMessageProtos.PushMessageContent;
import org.whispersystems.textsecure.push.PushMessageProtos.PushMessageContent.AttachmentPointer;
import org.whispersystems.textsecure.push.PushServiceSocket;
import org.whispersystems.textsecure.util.PhoneNumberFormatter;
import org.whispersystems.textsecure.util.Util;
import org.whispersystems.whisperpush.attachments.AttachmentManager;
import org.whispersystems.whisperpush.crypto.IdentityMismatchException;
import org.whispersystems.whisperpush.crypto.MasterSecretUtil;
import org.whispersystems.whisperpush.crypto.WhisperCipher;
import org.whispersystems.whisperpush.database.DatabaseFactory;
import org.whispersystems.whisperpush.sms.OutgoingSmsQueue;
import org.whispersystems.whisperpush.sms.OutgoingSmsQueue.OutgoingMessageCandidate;
import org.whispersystems.whisperpush.util.SmsServiceBridge;
import org.whispersystems.whisperpush.util.WhisperPreferences;
import org.whispersystems.whisperpush.util.WhisperPushCredentials;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The service that does the work of delivering outgoing messages and processing
 * incoming messages.
 *
 * @author Moxie Marlinspike
 */
public class SendReceiveService extends Service {

  public static final String RECEIVE_SMS = "org.whispersystems.SendReceiveService.RECEIVE_SMS";
  public static final String SEND_SMS    = "org.whispersystems.SendReceiveService.SEND_SMS";

  public  static final String DESTINATION  = "destAddr";
  private static final String PARTS        = "parts";
  private static final String SENT_INTENTS = "sentIntents";

  private final ExecutorService executor = Executors.newCachedThreadPool();

  @Override
  public int onStartCommand(final Intent intent, int flags, int startId) {
    if (intent != null) {
      executor.execute(new Runnable() {
        @Override
        public void run() {
          if      (RECEIVE_SMS.equals(intent.getAction())) handleReceiveSms(intent);
          else if (SEND_SMS.equals(intent.getAction()))    handleSendSms();
        }
      });
    }

    return START_NOT_STICKY;
  }

  private void handleReceiveSms(Intent intent) {
    MasterSecret        masterSecret = MasterSecretUtil.getMasterSecret(this);
    IncomingPushMessage message      = intent.getParcelableExtra("message");

    if (message == null)
      return;

    if (!isActiveNumber(message.getSource())) {
      Directory           directory           = Directory.getInstance(this);
      String              contactToken        = directory.getToken(message.getSource());
      String              relay               = message.getRelay();
      ContactTokenDetails contactTokenDetails = new ContactTokenDetails(contactToken, relay);

      directory.setToken(contactTokenDetails, true);
    }

    try {
      WhisperCipher              whisperCipher = new WhisperCipher(this, masterSecret, message.getSource());
      PushMessageContent         content       = whisperCipher.getDecryptedMessage(message);
      List<Pair<String, String>> attachments   = new LinkedList<Pair<String, String>>();

      if (content.getAttachmentsCount() > 0) {
        attachments = retrieveAttachments(content.getAttachmentsList());
      }

      SmsServiceBridge.receivedPushMessage(message.getSource(), message.getDestinations(),
                                           content.getBody(), attachments,
                                           message.getTimestampMillis());
    } catch (IdentityMismatchException e) {
      Log.w("SendReceiveService", e);
      DatabaseFactory.getPendingApprovalDatabase(this).insert(message);
      MessageNotifier.updateNotifications(this);
    } catch (InvalidMessageException e) {
      Log.w("SendReceiveService", e);
      // XXX
    } catch (IOException e) {
      Log.w("SendReceiveService", e);
      // XXX
    }
  }

  private void handleSendSms() {
    OutgoingMessageCandidate candidate = OutgoingSmsQueue.getInstance().get();

    Log.w("SendReceiveService", "Got outgoing message candidate: " + candidate);

    if (candidate == null)
      return;

    Intent        sendIntent    = candidate.getIntent();
    PendingResult pendingResult = candidate.getPendingResult();

    try {
      List<String>        messageParts         = sendIntent.getStringArrayListExtra(PARTS);
      String              destination          = sendIntent.getStringExtra(DESTINATION);
      List<PendingIntent> sentIntents          = sendIntent.getParcelableArrayListExtra(SENT_INTENTS);
      String              localNumber          = WhisperPreferences.getLocalNumber(this);
      String              formattedDestination = PhoneNumberFormatter.formatNumber(destination, localNumber);
      MasterSecret        masterSecret         = MasterSecretUtil.getMasterSecret(this);

      if (!isRegisteredUser(formattedDestination)) {
        Log.w("SendReceiveService", "Not a registered user...");
        pendingResult.finish();
        return;
      }

      PushServiceSocket socket        = new PushServiceSocket(this, WhisperPushCredentials.getInstance());
      String            message       = Util.join(messageParts, "");
      String            relay         = Directory.getInstance(this).getRelay(formattedDestination);
      byte[]            plaintext     = PushMessageContent.newBuilder().setBody(message).build().toByteArray();
      WhisperCipher     whisperCipher = new WhisperCipher(this, masterSecret, formattedDestination);

      Pair<Integer, byte[]> typeAndEncryptedMessage = whisperCipher.getEncryptedMessage(socket,
                                                                                        formattedDestination,
                                                                                        plaintext);

      socket.sendMessage(relay, formattedDestination,
                         typeAndEncryptedMessage.second,
                         typeAndEncryptedMessage.first);

      for (PendingIntent sentIntent : sentIntents) {
        try {
          sentIntent.send(Activity.RESULT_OK);
        } catch (PendingIntent.CanceledException e) {
          Log.w("SendReceiveService", e);
        }
      }

      pendingResult.abortBroadcast();
      pendingResult.setResultCode(Activity.RESULT_CANCELED);
      pendingResult.finish();
    } catch (IOException e) {
      Log.w("SendReceiveService", e);
      pendingResult.finish();
    }
  }

  private boolean isRegisteredUser(String e164number) {
    Directory directory = Directory.getInstance(this);

    try {
      return directory.isActiveNumber(e164number);
    } catch (NotInDirectoryException e) {
      try {
        PushServiceSocket   socket              = new PushServiceSocket(this, WhisperPushCredentials.getInstance());
        String              contactToken        = directory.getToken(e164number);
        ContactTokenDetails contactTokenDetails = socket.getContactTokenDetails(contactToken);

        if (contactTokenDetails != null) {
          directory.setToken(contactTokenDetails, true);
          return true;
        } else {
          contactTokenDetails = new ContactTokenDetails(contactToken);
          directory.setToken(contactTokenDetails, false);
          return false;
        }
      } catch (IOException e1) {
        Log.w("SendReceiveService", e1);
        return false;
      }
    }
  }

  private boolean isActiveNumber(String e164number) {
    try {
      return Directory.getInstance(this).isActiveNumber(e164number);
    } catch (NotInDirectoryException e) {
      return false;
    }
  }

  private List<Pair<String, String>> retrieveAttachments(List<AttachmentPointer> attachments)
      throws IOException, InvalidMessageException
  {
    AttachmentManager          attachmentManager = AttachmentManager.getInstance(this);
    PushServiceSocket          socket            = new PushServiceSocket(this, WhisperPushCredentials.getInstance());
    List<Pair<String, String>> results           = new LinkedList<Pair<String, String>>();

    for (AttachmentPointer attachment : attachments) {
      byte[]                      key              = attachment.getKey().toByteArray();
      File                        file             = socket.retrieveAttachment(attachment.getId());
      AttachmentCipherInputStream attachmentStream = new AttachmentCipherInputStream(file, key);
      String                      storedToken      = attachmentManager.store(attachmentStream);

      file.delete();
      results.add(new Pair<String, String>(storedToken, attachment.getContentType()));
    }

    return results;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

}
