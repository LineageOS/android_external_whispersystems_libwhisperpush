package org.whispersystems.whisperpush.util;

import org.whispersystems.libaxolotl.util.guava.Optional;
import org.whispersystems.textsecure.api.TextSecureAccountManager;
import org.whispersystems.textsecure.api.TextSecureMessageReceiver;
import org.whispersystems.textsecure.api.TextSecureMessageSender;
import org.whispersystems.textsecure.api.TextSecureMessageSender.EventListener;
import org.whispersystems.whisperpush.Release;
import org.whispersystems.whisperpush.database.WPAxolotlStore;

import android.content.Context;

public class WhisperServiceFactory {
    public static TextSecureMessageSender createMessageSender(Context context) {
        WhisperPreferences preferences = WhisperPreferences.getInstance(context);
        return new TextSecureMessageSender(Release.PUSH_URL,
                                           new WhisperPushTrustStore(context),
                                           preferences.getLocalNumber(),
                                           WhisperPreferences.getPushServerPassword(context),
                                           WPAxolotlStore.getInstance(context),
                                           null,
                                           Optional.<EventListener>absent());
    }

    public static TextSecureMessageReceiver createMessageReceiver(Context context) {
        return new TextSecureMessageReceiver(Release.PUSH_URL,
                                             new WhisperPushTrustStore(context),
                                             WhisperPreferences.getLocalNumber(context),
                                             WhisperPreferences.getPushServerPassword(context),
                                             WhisperPreferences.getSignalingKey(context), null);
    }

    public static TextSecureAccountManager createAccountManager(Context context) {
        WhisperPreferences preferences = WhisperPreferences.getInstance(context);
        return new TextSecureAccountManager(Release.PUSH_URL,
                                            new WhisperPushTrustStore(context),
                                            preferences.getLocalNumber(),
                                            WhisperPreferences.getPushServerPassword(context), null);
    }

    public static TextSecureAccountManager initAccountManager(Context context, String number, String password) {
        WhisperPreferences.setLocalNumber(context, number);
        WhisperPreferences.setPushServerPassword(context, password);
        return createAccountManager(context);
    }
}