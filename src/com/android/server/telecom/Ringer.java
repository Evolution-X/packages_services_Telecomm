/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.telecom;

import static android.provider.CallLog.Calls.USER_MISSED_DND_MODE;
import static android.provider.CallLog.Calls.USER_MISSED_LOW_RING_VOLUME;
import static android.provider.CallLog.Calls.USER_MISSED_NO_VIBRATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Context;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.RingtoneVibrationUtils;
import android.media.VolumeShaper;
import android.media.audio.Flags;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecom.Log;
import android.telecom.TelecomManager;
import android.util.Pair;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.LogUtils.EventTimer;
import com.android.server.telecom.flags.FeatureFlags;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Controls the ringtone player.
 */
@VisibleForTesting
public class Ringer {
    private static final String TAG = "TelecomRinger";

    /**
     * Abstraction of vibration.  We used to leverage SystemVibrator which implements the abstract
     * Vibrator class.  However, the Vibrator class has a bunch of abstract @hide methods we can
     * not implement, so we just abstract out a base interface here with only what we need.
     */
    public interface VibratorAdapter {
        boolean hasVibrator();
        void vibrate(VibrationEffect vibe, VibrationAttributes attributes);
        void cancel();
        Vibrator getVibrator();
    }

    public interface AccessibilityManagerAdapter {
        boolean startFlashNotificationSequence(@NonNull Context context,
                /* @AccessibilityManager.FlashNotificationReason */ int reason);
        boolean stopFlashNotificationSequence(@NonNull Context context);
    }
    /**
     * Flag only for local debugging. Do not submit enabled.
     */
    private static final boolean DEBUG_RINGER = false;

    public static class VibrationEffectProxy {
        public VibrationEffect createWaveform(long[] timings, int[] amplitudes, int repeat) {
            return VibrationEffect.createWaveform(timings, amplitudes, repeat);
        }
    }
    @VisibleForTesting
    public VibrationEffect mDefaultVibrationEffect;

    // Used for test to notify the completion of RingerAttributes
    private CountDownLatch mAttributesLatch;

    /**
     * Delay to be used between consecutive vibrations when a non-repeating vibration effect is
     * provided by the device.
     *
     * <p>If looking to customize the loop delay for a device's ring vibration, the desired repeat
     * behavior should be encoded directly in the effect specification in the device configuration
     * rather than changing the here (i.e. in `R.raw.default_ringtone_vibration_effect` resource).
     */
    private static int DEFAULT_RING_VIBRATION_LOOP_DELAY_MS = 1000;

    private static final long[] PULSE_PRIMING_PATTERN = {0,12,250,12,500}; // priming  + interval

    private static final int[] PULSE_PRIMING_AMPLITUDE = {0,255,0,255,0};  // priming  + interval

    // ease-in + peak + pause
    private static final long[] PULSE_RAMPING_PATTERN = {
        50,50,50,50,50,50,50,50,50,50,50,50,50,50,300,1000};

    // ease-in (min amplitude = 30%) + peak + pause
    private static final int[] PULSE_RAMPING_AMPLITUDE = {
        77,77,78,79,81,84,87,93,101,114,133,162,205,255,255,0};

    @VisibleForTesting
    public static final long[] PULSE_PATTERN;

    @VisibleForTesting
    public static final int[] PULSE_AMPLITUDE;

    private static final int RAMPING_RINGER_VIBRATION_DURATION = 5000;
    private static final int RAMPING_RINGER_DURATION = 10000;
    private static final int OUTGOING_CALL_VIBRATING_DURATION = 100;

    static {
        // construct complete pulse pattern
        PULSE_PATTERN = new long[PULSE_PRIMING_PATTERN.length + PULSE_RAMPING_PATTERN.length];
        System.arraycopy(
            PULSE_PRIMING_PATTERN, 0, PULSE_PATTERN, 0, PULSE_PRIMING_PATTERN.length);
        System.arraycopy(PULSE_RAMPING_PATTERN, 0, PULSE_PATTERN,
            PULSE_PRIMING_PATTERN.length, PULSE_RAMPING_PATTERN.length);

        // construct complete pulse amplitude
        PULSE_AMPLITUDE = new int[PULSE_PRIMING_AMPLITUDE.length + PULSE_RAMPING_AMPLITUDE.length];
        System.arraycopy(
            PULSE_PRIMING_AMPLITUDE, 0, PULSE_AMPLITUDE, 0, PULSE_PRIMING_AMPLITUDE.length);
        System.arraycopy(PULSE_RAMPING_AMPLITUDE, 0, PULSE_AMPLITUDE,
            PULSE_PRIMING_AMPLITUDE.length, PULSE_RAMPING_AMPLITUDE.length);
    }

    private static final long[] SIMPLE_VIBRATION_PATTERN = {
            0, // No delay before starting
            1000, // How long to vibrate
            1000, // How long to wait before vibrating again
    };

    private static final int[] SIMPLE_VIBRATION_AMPLITUDE = {
            0, // No delay before starting
            255, // Vibrate full amplitude
            0, // No amplitude while waiting
    };

    private static final long[] CALL_CONNECTED_VIBRATION_PATTERN = {
            0, // No delay before starting
            1000, // How long to vibrate
    };

    private static final int[] CALL_CONNECTED_VIBRATION_AMPLITUDE = {
            0, // No delay before starting
            255, // Vibrate full amplitude
    };

    /**
     * Indicates that vibration should be repeated at element 5 in the {@link #PULSE_AMPLITUDE} and
     * {@link #PULSE_PATTERN} arrays.  This means repetition will happen for the main ease-in/peak
     * pattern, but the priming + interval part will not be repeated.
     */
    private static final int REPEAT_VIBRATION_AT = 5;

    private static final int REPEAT_SIMPLE_VIBRATION_AT = 1;

    private static final long RINGER_ATTRIBUTES_TIMEOUT = 5000; // 5 seconds

    private static final float EPSILON = 1e-6f;

    private static final VibrationAttributes VIBRATION_ATTRIBUTES =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_RINGTONE).build();
    private static final VibrationAttributes VIBRATION_INCALL_ATTRIBUTES =
            new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ACCESSIBILITY).build();

    private static VolumeShaper.Configuration mVolumeShaperConfig;

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */

    private final SystemSettingsUtil mSystemSettingsUtil;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final AsyncRingtonePlayer mRingtonePlayer;
    private final Context mContext;
    private final VibratorAdapter mVibrator;
    private final InCallController mInCallController;
    private final VibrationEffectProxy mVibrationEffectProxy;
    private final boolean mIsHapticPlaybackSupportedByDevice;
    private final FeatureFlags mFlags;
    private final boolean mRingtoneVibrationSupported;
    private final AnomalyReporterAdapter mAnomalyReporter;
    private RingerAttributes mRingerAttributes;
    private final CrsAudioController mCrsAudioController;

    /**
     * For unit testing purposes only; when set, {@link #startRinging(Call, boolean)} will complete
     * the future provided by the test using {@link #setBlockOnRingingFuture(CompletableFuture)}.
     */
    private CompletableFuture<Void> mBlockOnRingingFuture = null;

    private InCallTonePlayer mCallWaitingPlayer;
    private RingtoneFactory mRingtoneFactory;
    private AudioManager mAudioManager;
    private NotificationManager mNotificationManager;
    private AccessibilityManagerAdapter mAccessibilityManagerAdapter;

    /**
     * Call objects that are ringing, vibrating or call-waiting. These are used only for logging
     * purposes (except mVibratingCall is also used to ensure consistency).
     */
    private Call mRingingCall;
    private Call mVibratingCall;
    private Call mCallWaitingCall;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private volatile boolean mIsVibrating = false;

    private Handler mHandler = null;

    /**
     * Use lock different from the Telecom sync because ringing process is asynchronous outside that
     * lock
     */
    private final Object mLock;
    /**
     * Used to track the status of call connected inidicator preference.
     */
    private final CallConnectedIndicatorSettings mCallConnectedIndicatorSettings;
    private final Executor mAsyncTaskExecutor;

    /**
     * Manages a dedicated single background thread for executing Ringer-specific tasks
     * asynchronously, such as calculating ringer attributes and posting accessibility updates.
     * <p>
     * This ExecutorService replaces the previous Handler/HandlerThread mechanism to align with
     * Mainline module guidelines, which restrict direct Handler usage to reduce platform coupling
     * and enhance stability.
     */
    private ExecutorService mRingerExecutor = null;
    /**
     * Guards mRingerExecutor's lifecycle (lazy initialization, shutdown). This prevents
     * race conditions during init that could lead to multiple executor instances & resource leaks.
     * Kept separate from mLock (ringer operational state) to minimize unrelated contention.
     */
    private final Object mExecutorLock = new Object();

    /** Initializes the Ringer. */
    @VisibleForTesting
    public Ringer(
            InCallTonePlayer.Factory playerFactory,
            Context context,
            SystemSettingsUtil systemSettingsUtil,
            AsyncRingtonePlayer asyncRingtonePlayer,
            RingtoneFactory ringtoneFactory,
            VibratorAdapter vibrator,
            VibrationEffectProxy vibrationEffectProxy,
            InCallController inCallController,
            NotificationManager notificationManager,
            AccessibilityManagerAdapter accessibilityManagerAdapter,
            FeatureFlags featureFlags,
            AnomalyReporterAdapter anomalyReporter,
            CallConnectedIndicatorSettings callConnectedIndicator,
            Executor asyncTaskExecutor,
            CrsAudioController crsAudioController) {

        mLock = new Object();
        mSystemSettingsUtil = systemSettingsUtil;
        mPlayerFactory = playerFactory;
        mContext = context;
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = vibrator;
        mRingtonePlayer = asyncRingtonePlayer;
        mRingtoneFactory = ringtoneFactory;
        mInCallController = inCallController;
        mVibrationEffectProxy = vibrationEffectProxy;
        mNotificationManager = notificationManager;
        mAccessibilityManagerAdapter = accessibilityManagerAdapter;
        mAnomalyReporter = anomalyReporter;

        mDefaultVibrationEffect =
                loadDefaultRingVibrationEffect(mContext, mVibrationEffectProxy, featureFlags);

        mIsHapticPlaybackSupportedByDevice =
                mSystemSettingsUtil.isHapticPlaybackSupported(mContext);

        mAudioManager = mContext.getSystemService(AudioManager.class);
        mFlags = featureFlags;
        Resources res = mContext.getResources();
        int resourceId = Resources.getSystem().getIdentifier(
                "config_ringtoneVibrationSettingsSupported", "bool", "android");
        mRingtoneVibrationSupported = res.getBoolean(resourceId);
        mCallConnectedIndicatorSettings = callConnectedIndicator;
        mAsyncTaskExecutor = asyncTaskExecutor;
        mCrsAudioController = crsAudioController;
    }

    public void shutdownExecutor() {
        Log.i(this, "Shutting down Ringer executor.");
        synchronized (mExecutorLock) {
            if (mRingerExecutor != null) {
                mRingerExecutor.shutdown();
                try {
                    if (!mRingerExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                        mRingerExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    mRingerExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
                mRingerExecutor = null;
            }
        }
    }

    @VisibleForTesting
    public void setBlockOnRingingFuture(CompletableFuture<Void> future) {
        mBlockOnRingingFuture = future;
    }

    @VisibleForTesting
    public void setNotificationManager(NotificationManager notificationManager) {
        mNotificationManager = notificationManager;
    }

    public boolean startRinging(Call foregroundCall, boolean isHfpDeviceAttached) {
        boolean deferBlockOnRingingFuture = false;
        // try-finally to ensure that the block on ringing future is always called.
        try {
            if (foregroundCall == null) {
                Log.wtf(this, "startRinging called with null foreground call.");
                return false;
            }

            if (foregroundCall.getState() != CallState.RINGING
                    && foregroundCall.getState() != CallState.SIMULATED_RINGING) {
                // It's possible for bluetooth to connect JUST as a call goes active, which would
                // mean the call would start ringing again.
                Log.i(this, "startRinging called for non-ringing foreground callid=%s",
                        foregroundCall.getId());
                return false;
            }

            mAttributesLatch = new CountDownLatch(1);

            // Use completable future to establish a timeout, not intent to make these work outside
            // the main thread asynchronously
            // TODO: moving these RingerAttributes calculation out of Telecom lock to avoid blocking
            CompletableFuture<RingerAttributes> ringerAttributesFuture = CompletableFuture
                    .supplyAsync(() -> getRingerAttributes(foregroundCall, isHfpDeviceAttached),
                            getLoggedExecutor("R.sR"));

            try {
                mRingerAttributes = ringerAttributesFuture.get(
                        RINGER_ATTRIBUTES_TIMEOUT, TimeUnit.MILLISECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                // Keep attributes as null
                Log.i(this, "getAttributes error: " + e);
            }

            if (mRingerAttributes == null) {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING,
                        "RingerAttributes error");
                return false;
            }

            Log.i(this, "startRinging: attributes=%s", mRingerAttributes);

            if (mRingerAttributes.isEndEarly()) {
                boolean acquireAudioFocus = mRingerAttributes.shouldAcquireAudioFocus();
                if (mRingerAttributes.letDialerHandleRinging()) {
                    Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Dialer handles");
                    // Dialer will setup a ringtone, provide the audio focus if its audible.
                    acquireAudioFocus |= mRingerAttributes.isRingerAudible();
                }

                if (mRingerAttributes.isSilentRingingRequested()) {
                    Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING, "Silent ringing "
                            + "requested");
                }
                if (mRingerAttributes.isWorkProfileInQuietMode()) {
                    Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING,
                            "Work profile in quiet mode");
                }
                return acquireAudioFocus;
            }

            stopCallWaiting();

            final boolean shouldFlash = mRingerAttributes.shouldRingForContact();
            if (mAccessibilityManagerAdapter != null && shouldFlash) {
                Log.addEvent(foregroundCall, LogUtils.Events.FLASH_NOTIFICATION_START);
                getExecutor().execute(() ->
                        mAccessibilityManagerAdapter.startFlashNotificationSequence(mContext,
                                1 /* FLASH_REASON_CALL = 1 */));
            }

            Context userContext = null;
            try {
                userContext = mContext.createContextAsUser(UserHandle.CURRENT, 0 /* flags */);
            } catch (Exception e) {
                Log.i(this, "createContextAsUser fail exception=[%s]", e.toString());
            } finally {
                if (userContext == null) {
                    userContext = mContext;
                }
            }
            // Determine if the settings and DND mode indicate that the vibrator can be used right
            // now.
            final boolean isVibratorEnabled =
                    isVibratorEnabled(userContext, mRingerAttributes.shouldRingForContact());
            boolean shouldApplyRampingRinger =
                    isVibratorEnabled && mSystemSettingsUtil.isRampingRingerEnabled(userContext);

            boolean isHapticOnly = false;
            boolean useCustomVibrationEffect = false;

            mVolumeShaperConfig = null;

            String vibratorAttrs = String.format("hasVibrator=%b, userRequestsVibrate=%b, "
                            + "ringerMode=%d, isVibratorEnabled=%b",
                    mVibrator.hasVibrator(),
                    mSystemSettingsUtil.isRingVibrationEnabled(userContext),
                    mAudioManager.getRingerMode(), isVibratorEnabled);

            if (mRingerAttributes.isRingerAudible()) {
                mRingingCall = foregroundCall;
                if (mRingerAttributes.getRingtoneType() == Call.RINGTONE_SOURCE_LOCAL) {
                    Log.addEvent(foregroundCall, LogUtils.Events.START_RINGER);
                } else if (mRingerAttributes.getRingtoneType()
                        == Call.RINGTONE_SOURCE_NETWORK_RING_MODE) {
                    Log.addEvent(foregroundCall, LogUtils.Events.START_CRS_RINGER_IN_MODE_RINGTONE);
                } else if (mRingerAttributes.getRingtoneType()
                        == Call.RINGTONE_SOURCE_NETWORK_IN_CALL_MODE) {
                    Log.addEvent(foregroundCall, LogUtils.Events.START_CRS_RINGER_IN_MODE_IN_CALL);
                }
                // Because we wait until a contact info query to complete before processing a
                // call (for the purposes of direct-to-voicemail), the information about custom
                // ringtones should be available by the time this code executes. We can safely
                // request the custom ringtone from the call and expect it to be current.
                if (shouldApplyRampingRinger) {
                    Log.i(this, "create ramping ringer.");
                    float silencePoint = (float) (RAMPING_RINGER_VIBRATION_DURATION)
                            / (float) (RAMPING_RINGER_VIBRATION_DURATION + RAMPING_RINGER_DURATION);
                    mVolumeShaperConfig =
                            new VolumeShaper.Configuration.Builder()
                                    .setDuration(RAMPING_RINGER_VIBRATION_DURATION
                                            + RAMPING_RINGER_DURATION)
                                    .setCurve(
                                            new float[]{0.f, silencePoint + EPSILON
                                                    /*keep monotonicity*/, 1.f},
                                            new float[]{0.f, 0.f, 1.f})
                                    .setInterpolatorType(
                                            VolumeShaper.Configuration.INTERPOLATOR_TYPE_LINEAR)
                                    .build();
                    if (mSystemSettingsUtil.isAudioCoupledVibrationForRampingRingerEnabled()) {
                        useCustomVibrationEffect = true;
                    }
                } else {
                    if (DEBUG_RINGER) {
                        Log.i(this, "Create ringer with custom vibration effect");
                    }
                    // Ramping ringtone is not enabled.
                    useCustomVibrationEffect = true;
                }
            } else {
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_RINGING,
                        "Inaudible: " + mRingerAttributes.getInaudibleReason()
                                + " isVibratorEnabled=" + isVibratorEnabled);

                if (isVibratorEnabled) {
                    // If ringer is not audible for this call, then the phone is in "Vibrate" mode.
                    // Use haptic-only ringtone or do not play anything.
                    isHapticOnly = true;
                    Log.i(this, "Set ringtone as haptic only: " + isHapticOnly);
                } else {
                    Log.i(this, "ringer & haptics are off, user missed alerts for call");
                    foregroundCall.setUserMissed(USER_MISSED_NO_VIBRATE);
                    Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION,
                            vibratorAttrs);
                    return mRingerAttributes.shouldAcquireAudioFocus(); // ringer not audible
                }
            }

            boolean hapticChannelsMuted = !isVibratorEnabled || !mIsHapticPlaybackSupportedByDevice;
            if (mRingerAttributes.isRingerAudible() && shouldApplyRampingRinger
                    && !mSystemSettingsUtil.isAudioCoupledVibrationForRampingRingerEnabled()
                    && isVibratorEnabled) {
                Log.i(this, "Muted haptic channels since audio coupled ramping ringer is disabled");
                hapticChannelsMuted = true;
            } else if (hapticChannelsMuted) {
                Log.i(this,
                        "Muted haptic channels isVibratorEnabled=%s, hapticPlaybackSupported=%s",
                        isVibratorEnabled, mIsHapticPlaybackSupportedByDevice);
            }
            // Defer ringtone creation to the async player thread.
            Supplier<Pair<Uri, Ringtone>> ringtoneInfoSupplier = null;
            final boolean finalHapticChannelsMuted = hapticChannelsMuted;
            if (!isHapticOnly
                    && mRingerAttributes.getRingtoneType() == Call.RINGTONE_SOURCE_LOCAL) {
                ringtoneInfoSupplier = () -> mRingtoneFactory.getRingtone(
                        foregroundCall, mVolumeShaperConfig, finalHapticChannelsMuted);
            } else if (useCustomVibration(foregroundCall)) {
                ringtoneInfoSupplier = () -> mRingtoneFactory.getRingtone(
                        foregroundCall, null, false);
            }
            Log.i(this, "isRingtoneInfoSupplierNull=[%b]", ringtoneInfoSupplier == null);
            // If vibration will be done, reserve the vibrator.
            boolean vibratorReserved = isVibratorEnabled && mRingerAttributes.shouldRingForContact()
                && tryReserveVibration(foregroundCall);
            if (!vibratorReserved) {
                foregroundCall.setUserMissed(USER_MISSED_NO_VIBRATE);
                Log.addEvent(foregroundCall, LogUtils.Events.SKIP_VIBRATION,
                        vibratorAttrs);
            }

            // The vibration logic depends on the loaded ringtone, but we need to defer the ringtone
            // load to the async ringtone thread. Hence, we bundle up the final part of this method
            // for that thread to run after loading the ringtone. This logic is intended to run even
            // if the loaded ringtone is null. However if a stop event arrives before the ringtone
            // creation finishes, then this consumer can be skipped.
            final boolean finalUseCustomVibrationEffect = useCustomVibrationEffect;
            BiConsumer<Pair<Uri, Ringtone>, Boolean> afterRingtoneLogic =
                    (Pair<Uri, Ringtone> ringtoneInfo, Boolean stopped) -> {
                try {
                    Uri ringtoneUri = null;
                    Ringtone ringtone = null;
                    if (ringtoneInfo != null) {
                        ringtoneUri = ringtoneInfo.first;
                        ringtone = ringtoneInfo.second;
                    } else {
                        Log.w(this, "The ringtone could not be loaded.");
                    }

                    if (stopped.booleanValue() || !vibratorReserved) {
                        // don't start vibration if the ringing is already abandoned, or the
                        // vibrator wasn't reserved. This still triggers the mBlockOnRingingFuture.
                        return;
                    }
                    final VibrationEffect vibrationEffect = mDefaultVibrationEffect;

                    boolean isUsingAudioCoupledHaptics =
                            !finalHapticChannelsMuted && ringtone != null
                                    && ringtone.hasHapticChannels();
                    vibrateIfNeeded(isUsingAudioCoupledHaptics, foregroundCall, vibrationEffect,
                            ringtoneUri);
                } finally {
                    // This is used to signal to tests that the async play() call has completed.
                    if (mBlockOnRingingFuture != null) {
                        mBlockOnRingingFuture.complete(null);
                    }
                }
            };
            deferBlockOnRingingFuture = true;  // Run in vibrationLogic.
            if (foregroundCall.isCrsCall()) {
                if (mCrsAudioController != null) {
                    mCrsAudioController.configureCrsRingVolume(mRingerAttributes);
                }
                afterRingtoneLogic.accept(/* ringtoneUri, ringtone = */ null, /* stopped= */ false);
            } else if (ringtoneInfoSupplier != null) {
                mRingtonePlayer.play(ringtoneInfoSupplier, afterRingtoneLogic, isHfpDeviceAttached);
            } else {
                afterRingtoneLogic.accept(/* ringtoneUri, ringtone = */ null, /* stopped= */ false);
            }

            // shouldAcquireAudioFocus is meant to be true, but that check is deferred to here
            // because until now is when we actually know if the ringtone loading worked.
            return mRingerAttributes.shouldAcquireAudioFocus()
                    || (!isHapticOnly && mRingerAttributes.isRingerAudible());
        } finally {
            // This is used to signal to tests that the async play() call has completed. It can
            // be deferred into AsyncRingtonePlayer
            if (mBlockOnRingingFuture != null && !deferBlockOnRingingFuture) {
                mBlockOnRingingFuture.complete(null);
            }
        }
    }

    private boolean useCustomVibration(@NonNull Call foregroundCall) {
        return mRingtoneVibrationSupported && hasExplicitVibration(foregroundCall);
    }

    private boolean hasExplicitVibration(@NonNull Call foregroundCall) {
        final Uri ringtoneUri = foregroundCall.getRingtone();
        if (ringtoneUri != null) {
            return RingtoneVibrationUtils.hasVibrationParameter(ringtoneUri);
        }
        if (Flags.supportPerPhoneAccountRingtone()) {
            return RingtoneVibrationUtils.hasVibrationParameter(
                    RingtoneManager.getRingtoneUriForPhoneAccountHandle(
                            mContext, foregroundCall.getTargetPhoneAccount()));
        } else {
            return RingtoneVibrationUtils.hasVibrationParameter(
                    RingtoneManager.getActualDefaultRingtoneUri(
                            mContext, RingtoneManager.TYPE_RINGTONE));
        }
    }

    /**
     * Try to reserve the vibrator for this call, returning false if it's already committed.
     * The vibration will be started by AsyncRingtonePlayer to ensure timing is aligned with the
     * audio. The logic uses mVibratingCall to say which call is currently getting ready to vibrate,
     * or actually vibrating (indicated by mIsVibrating).
     *
     * Once reserved, the vibrateIfNeeded method is expected to be called. Note that if
     * audio-coupled haptics were used instead of vibrator, the reservation still stays until
     * ringing is stopped, because the vibrator is exclusive to a single vibration source.
     *
     * Note that this "reservation" is only local to the Ringer - it's not locking the vibrator, so
     * if it's busy with some other important vibration, this ringer's one may not displace it.
     */
    private boolean tryReserveVibration(Call foregroundCall) {
        synchronized (mLock) {
            if (mVibratingCall != null || mIsVibrating) {
                return false;
            }
            mVibratingCall = foregroundCall;
            return true;
        }
   }

    private void vibrateIfNeeded(boolean isUsingAudioCoupledHaptics, Call foregroundCall,
            VibrationEffect effect, Uri ringtoneUri) {
        if (isUsingAudioCoupledHaptics) {
            Log.addEvent(
                foregroundCall, LogUtils.Events.SKIP_VIBRATION, "using audio-coupled haptics");
            return;
        }

        if (mRingtoneVibrationSupported
                && RingtoneVibrationUtils.hasVibrationParameter(ringtoneUri)) {
            Log.addEvent(
                    foregroundCall, LogUtils.Events.SKIP_VIBRATION, "using custom haptics");
            return;
        }

        synchronized (mLock) {
            // Ensure the reservation is live. The mIsVibrating check should be redundant.
            if (foregroundCall == mVibratingCall && !mIsVibrating) {
                Log.addEvent(foregroundCall, LogUtils.Events.START_VIBRATOR,
                    "hasVibrator=%b, userRequestsVibrate=%b, ringerMode=%d, isVibrating=%b",
                        mVibrator.hasVibrator(),
                        mSystemSettingsUtil.isRingVibrationEnabled(mContext),
                    mAudioManager.getRingerMode(), mIsVibrating);
                mIsVibrating = true;
                mVibrator.vibrate(effect, VIBRATION_ATTRIBUTES);
                Log.i(this, "start vibration.");
            } else {
                Log.i(this, "vibrateIfNeeded: skip; isVibrating=%b, fgCallId=%s, vibratingCall=%s",
                        mIsVibrating,
                        (foregroundCall == null ? "null" : foregroundCall.getId()),
                        (mVibratingCall == null ? "null" : mVibratingCall.getId()));
            }
            // else stopped already: this isn't started unless a reservation was made.
        }
    }

    public void startCallWaiting(Call call) {
        startCallWaiting(call, null);
    }

    public void startCallWaiting(Call call, String reason) {
        if (mInCallController.doesConnectedDialerSupportRinging(
                call.getAssociatedUser())) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Dialer handles");
            return;
        }

        if (call.isSelfManaged()) {
            Log.addEvent(call, LogUtils.Events.SKIP_RINGING, "Self-managed");
            return;
        }

        Log.v(this, "Playing call-waiting tone.");

        stopRinging();

        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.VIBRATE_ON_CALLWAITING, 0, UserHandle.USER_CURRENT) == 1) {
            vibrate(200, 300, 500);
        }

        if (mCallWaitingPlayer == null) {
            Log.addEvent(call, LogUtils.Events.START_CALL_WAITING_TONE, reason);
            mCallWaitingCall = call;
            mCallWaitingPlayer =
                    mPlayerFactory.createPlayer(call, InCallTonePlayer.TONE_CALL_WAITING);
            mCallWaitingPlayer.startTone();
        }
    }

    public void stopRinging() {
        final Call foregroundCall = mRingingCall != null ? mRingingCall : mVibratingCall;
        if (mAccessibilityManagerAdapter != null) {
            Log.addEvent(foregroundCall, LogUtils.Events.FLASH_NOTIFICATION_STOP);
            getExecutor().execute(() ->
                    mAccessibilityManagerAdapter.stopFlashNotificationSequence(mContext));
        }

        synchronized (mLock) {
            if (mRingerAttributes != null
                    && mRingerAttributes.getRingtoneType() == Call.RINGTONE_SOURCE_LOCAL) {
                if (mRingingCall != null) {
                    Log.addEvent(mRingingCall, LogUtils.Events.STOP_RINGER);
                    mRingingCall = null;
                }
                mRingtonePlayer.stop();
            }
            if (foregroundCall != null && mCrsAudioController != null) {
                mCrsAudioController.resetCrsAudioVolume(foregroundCall, mRingerAttributes);
            }
            mRingerAttributes = null;

            if (mIsVibrating) {
                Log.addEvent(mVibratingCall, LogUtils.Events.STOP_VIBRATOR);
                mVibrator.cancel();
                mIsVibrating = false;
            }
            mVibratingCall = null;  // Prevents vibrations from starting via AsyncRingtonePlayer.
        }
    }

    public void stopCallWaiting() {
        Log.v(this, "stop call waiting.");
        if (mCallWaitingPlayer != null) {
            if (mCallWaitingCall != null) {
                Log.addEvent(mCallWaitingCall, LogUtils.Events.STOP_CALL_WAITING_TONE);
                mCallWaitingCall = null;
            }

            mCallWaitingPlayer.stopTone();
            mCallWaitingPlayer = null;
        }
    }

    public boolean isRinging() {
        return mRingtonePlayer.isPlaying()
                || (mRingerAttributes != null
                && (mRingerAttributes.getRingtoneType() == Call.RINGTONE_SOURCE_NETWORK_RING_MODE
                || mRingerAttributes.getRingtoneType() == Call.RINGTONE_SOURCE_NETWORK_IN_CALL_MODE)
        );
    }

    /**
     * shouldRingForContact checks if the caller matches one of the Do Not Disturb bypass
     * settings (ex. A contact or repeat caller might be able to bypass DND settings). If
     * matchesCallFilter returns true, this means the caller can bypass the Do Not Disturb settings
     * and interrupt the user; otherwise call is suppressed.
     */
    public boolean shouldRingForContact(Call call) {
        // avoid re-computing manager.matcherCallFilter(Bundle)
        if (call.wasDndCheckComputedForCall()) {
            Log.i(this, "shouldRingForContact: returning computation from DndCallFilter.");
            return !call.isCallSuppressedByDoNotDisturb();
        }
        Uri contactUri = call.getHandle();
        if (contactUri == null) {
            contactUri = Uri.EMPTY;
        }
        boolean matchesCallFilter = mNotificationManager.matchesCallFilter(contactUri);
        return matchesCallFilter;
    }

    private boolean hasExternalRinger(Call foregroundCall) {
        Bundle intentExtras = foregroundCall.getIntentExtras();
        if (intentExtras != null) {
            return intentExtras.getBoolean(TelecomManager.EXTRA_CALL_HAS_IN_BAND_RINGTONE, false);
        } else {
            return false;
        }
    }

    private boolean isVibratorEnabled(Context context, boolean shouldRingForContact) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        // Use AudioManager#getRingerMode for more accurate result, instead of
        // AudioManager#getRingerModeInternal which only useful for volume controllers
        // See NotificationManager#zenModeToInterruptionFilter; INTERRUPTION_FILTER_ALL is
        // equivalent to the former ZEN_MODE_OFF.
        boolean zenModeOn = mNotificationManager != null
                && mNotificationManager.getCurrentInterruptionFilter()
                != NotificationManager.INTERRUPTION_FILTER_ALL;

        boolean hasVibrator = mVibrator.hasVibrator();
        int ringerMode = audioManager.getRingerMode();
        // Check if ring vibration is effectively enabled.
        //   This verifies two layers of settings:
        //   1. The specific 'Vibrate for calls' toggle (VIBRATE_WHEN_RINGING).
        //   2. The global master 'Use vibration & haptics' toggle (VIBRATE_ON),
        //      which overrides all others.
        boolean isRingVibrationEnabled = mSystemSettingsUtil.isRingVibrationEnabled(context);
        // Determine if the call should ring/vibrate even when Zen Mode (Do Not Disturb) is on,
        // based on whether the contact is allowed to bypass DND.
        boolean shouldRingForContactInZen = zenModeOn && shouldRingForContact;

        boolean shouldVibrate;

        if (!hasVibrator) {
            shouldVibrate = false;
        } else if (isRingVibrationEnabled) {
            if (ringerMode != AudioManager.RINGER_MODE_SILENT) {
                shouldVibrate = true;
            } else {
                shouldVibrate = shouldRingForContactInZen;
            }
        } else {
            shouldVibrate = false;
        }

        String ringerModeString;
        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            ringerModeString = "SILENT";
        } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            ringerModeString = "VIBRATE";
        } else if (ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            ringerModeString = "NORMAL";
        } else {
            ringerModeString = "UNKNOWN (" + ringerMode + ")";
        }

        Log.i(this, "isVibratorEnabled: hasVibrator=%b, ringerMode=%s, isRingVibrationEnabled=%b, "
                        + "zenModeOn=%b, shouldRingForContact=%b, shouldRingForContactInZen=%b"
                        + " -> result=%b",
                hasVibrator, ringerModeString, isRingVibrationEnabled, zenModeOn,
                shouldRingForContact, shouldRingForContactInZen, shouldVibrate);

        return shouldVibrate;
    }

    private RingerAttributes getRingerAttributes(Call call, boolean isHfpDeviceAttached) {
        mAudioManager = mContext.getSystemService(AudioManager.class);
        RingerAttributes.Builder builder = new RingerAttributes.Builder();

        EventTimer timer = new EventTimer();

        boolean isVolumeOverZero;

        isVolumeOverZero = mAudioManager.getStreamVolume(AudioManager.STREAM_RING) > 0;

        timer.record("isVolumeOverZero");
        boolean shouldRingForContact = shouldRingForContact(call);
        timer.record("shouldRingForContact");
        boolean isSelfManaged = call.isSelfManaged();
        timer.record("isSelfManaged");
        boolean isSilentRingingRequested = call.isSilentRingingRequested();
        timer.record("isSilentRingRequested");

        boolean isRingerAudible = isVolumeOverZero && shouldRingForContact;
        timer.record("isRingerAudible");
        String inaudibleReason = "";
        if (!isRingerAudible) {
            inaudibleReason = String.format("isVolumeOverZero=%s, shouldRingForContact=%s",
                isVolumeOverZero, shouldRingForContact);
        }

        boolean hasExternalRinger = hasExternalRinger(call);
        timer.record("hasExternalRinger");
        // Don't do call waiting operations or vibration unless these are false.
        boolean letDialerHandleRinging = mInCallController.doesConnectedDialerSupportRinging(
                call.getAssociatedUser());
        timer.record("letDialerHandleRinging");
        boolean isWorkProfileInQuietMode =
                isProfileInQuietMode(call.getAssociatedUser());
        timer.record("isWorkProfileInQuietMode");

        Log.i(this, "startRinging timings: " + timer);
        boolean endEarly =
                letDialerHandleRinging
                        || isSelfManaged
                        || hasExternalRinger
                        || isSilentRingingRequested
                        || isWorkProfileInQuietMode;

        if (endEarly) {
            Log.i(
                    this,
                    "getRingerAttributtes: ending -- letDialerHandleRinging=%s, isSelfManaged=%s, "
                            + "hasExternalRinger=%s, silentRingingRequested=%s, "
                            + "isWorkProfileInQuietMode=%s, shouldRingForContact=%s, "
                            + "isVolumeOverZero=%s",
                    letDialerHandleRinging,
                    isSelfManaged,
                    hasExternalRinger,
                    isSilentRingingRequested,
                    isWorkProfileInQuietMode, shouldRingForContact, isVolumeOverZero);
        }

        // Acquire audio focus under any of the following conditions:
        // 1. Should ring for contact and there's an HFP device attached
        // 2. Volume is over zero, we should ring for the contact, and there's a audible ringtone
        //    present. (This check is deferred until ringer knows the ringtone)
        boolean shouldAcquireAudioFocus;
        if (com.android.internal.telecom.flags.Flags.voipDndFocus()) {
            shouldAcquireAudioFocus = !isWorkProfileInQuietMode &&
                    // The previous logic for determining if audio focus should be acquired
                    // assumed we should ALWAYS acquire audio focus for a voip call.  For non-voip
                    // calls, the value of shouldAcquireAudioFocus we calculate here is combined
                    // with other factors later such as whether the ringer volume is zero or if
                    // there is a ringtone present.
                    // For voip we should ideally only acquire ringing focus if DND didn't block the
                    // contact and the ringer volume is over zero.
                    ((!isSelfManaged && isHfpDeviceAttached && shouldRingForContact)
                            || isSelfManaged && shouldRingForContact && isVolumeOverZero);
        } else {
            shouldAcquireAudioFocus = !isWorkProfileInQuietMode &&
                    ((isHfpDeviceAttached && shouldRingForContact) || isSelfManaged);
        }

        int ringToneType = Call.RINGTONE_SOURCE_LOCAL;
        if (call.isCrsCall() && mCrsAudioController!= null) {
            ringToneType = mCrsAudioController.getCrsRingToneType(call);
            Log.i(TAG, "CRS mode : Set the ringToneType : ", ringToneType);
        }

        // Set missed reason according to attributes
        if (!isVolumeOverZero) {
            call.setUserMissed(USER_MISSED_LOW_RING_VOLUME);
        }
        if (!shouldRingForContact) {
            call.setUserMissed(USER_MISSED_DND_MODE);
        }

        if (mAttributesLatch != null) {
            mAttributesLatch.countDown();
        }
        return builder.setEndEarly(endEarly)
                .setLetDialerHandleRinging(letDialerHandleRinging)
                .setAcquireAudioFocus(shouldAcquireAudioFocus)
                .setRingerAudible(isRingerAudible)
                .setInaudibleReason(inaudibleReason)
                .setShouldRingForContact(shouldRingForContact)
                .setSilentRingingRequested(isSilentRingingRequested)
                .setWorkProfileQuietMode(isWorkProfileInQuietMode)
                .setRingToneType(ringToneType)
                .build();
    }

    private boolean isProfileInQuietMode(UserHandle user) {
        UserManager um = mContext.getSystemService(UserManager.class);
        return um.isManagedProfile(user.getIdentifier()) && um.isQuietModeEnabled(user);
    }

    private Executor getLoggedExecutor(String functionName) {
        return new LoggedExecutor(getExecutor(), functionName, null);
    }

    public ExecutorService getExecutor() {
        synchronized (mExecutorLock) {
            if (mRingerExecutor == null) {
                ThreadFactory ringerThread = new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "Ringer-Executor");
                        return t;
                    }
                };
                // A single-thread executor is chosen here to ensure that Ringer-internal
                // background tasks (e.g., ringer attribute calculation, accessibility updates)
                // are processed sequentially. This mirrors the execution model of the previous
                // Handler/HandlerThread, preserving task order and predictable behavior.
                mRingerExecutor = Executors.newSingleThreadExecutor(ringerThread);
            }
        }
        return mRingerExecutor;
    }

    @VisibleForTesting
    public boolean waitForAttributesCompletion() throws InterruptedException {
        if (mAttributesLatch != null) {
            return mAttributesLatch.await(RINGER_ATTRIBUTES_TIMEOUT, TimeUnit.MILLISECONDS);
        } else {
            return false;
        }
    }

    private static VibrationEffect loadDefaultRingVibrationEffect(
            Context context,
            VibrationEffectProxy vibrationEffectProxy,
            FeatureFlags featureFlags) {
        Resources resources = TelecomResourceId.getResources(context);

        if (TelecomResourceId.getBoolean(context, "use_simple_vibration_pattern")) {
            Log.i(TAG, "Using simple default ring vibration.");
            return createSimpleRingVibration(vibrationEffectProxy);
        }

        if (featureFlags.useDeviceProvidedSerializedRingerVibration()) {
            Log.i(TAG, "Device provided serialized ringer vibration is no longer supported; "
                    + "falling back to simple default ring vibration.");
            return createSimpleRingVibration(vibrationEffectProxy);
        }

        Log.i(TAG, "Using pulse default ring vibration.");
        return vibrationEffectProxy.createWaveform(
                PULSE_PATTERN, PULSE_AMPLITUDE, REPEAT_VIBRATION_AT);
    }

    private static VibrationEffect createSimpleRingVibration(
            VibrationEffectProxy vibrationEffectProxy) {
        return vibrationEffectProxy.createWaveform(SIMPLE_VIBRATION_PATTERN,
                SIMPLE_VIBRATION_AMPLITUDE, REPEAT_SIMPLE_VIBRATION_AT);
    }

    public void startVibratingForOutgoingCallActive() {
        if (!android.telecom.flags.Flags.callConnectedIndicatorPreference()) {
            Log.i(TAG, "Call connected indicator of vibration is disabled.");
            return;
        }
        if (!mIsVibrating && mCallConnectedIndicatorSettings.isCallConnectedVibrationEnabled()) {
            mIsVibrating = true;
            mAsyncTaskExecutor.execute(() -> {
                final VibrationEffect vibrationEffect =
                        mVibrationEffectProxy.createWaveform(CALL_CONNECTED_VIBRATION_PATTERN,
                        CALL_CONNECTED_VIBRATION_AMPLITUDE, -1);
                final VibrationAttributes vibrationAttributes = new VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_NOTIFICATION)
                        .build();
                mVibrator.vibrate(vibrationEffect, vibrationAttributes);
                try {
                    Thread.sleep(OUTGOING_CALL_VIBRATING_DURATION);
                } catch (InterruptedException e) {
                    // Womp
                }
                mVibrator.cancel();
                mIsVibrating = false;
            });
        }
    }

    public void vibrate(int v1, int p1, int v2) {
        if (mVibrator != null && mVibrator.hasVibrator()) {
            long[] pattern = new long[] {
                0, v1, p1, v2
            };
            mVibrator.vibrate(
                VibrationEffect.createWaveform(pattern, -1), VIBRATION_INCALL_ATTRIBUTES);
        }
    }
}
