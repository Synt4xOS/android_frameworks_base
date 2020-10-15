/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.server.alarm;

import static android.app.AlarmManager.ELAPSED_REALTIME;
import static android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP;
import static android.app.AlarmManager.RTC;
import static android.app.AlarmManager.RTC_WAKEUP;

import static com.android.server.alarm.AlarmManagerService.clampPositive;

import android.app.AlarmManager;
import android.app.IAlarmListener;
import android.app.PendingIntent;
import android.os.WorkSource;
import android.util.IndentingPrintWriter;
import android.util.TimeUtils;
import android.util.proto.ProtoOutputStream;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Class to describe an alarm that is used to the set the kernel timer that returns when the timer
 * expires. The timer will wake up the device if the alarm is a "wakeup" alarm.
 */
class Alarm {
    private static final int NUM_POLICIES = 2;
    /**
     * Index used to store the time the alarm was requested to expire. To be used with
     * {@link #setPolicyElapsed(int, long)}
     */
    public static final int REQUESTER_POLICY_INDEX = 0;
    /**
     * Index used to store the earliest time the alarm can expire based on app-standby policy.
     * To be used with {@link #setPolicyElapsed(int, long)}
     */
    public static final int APP_STANDBY_POLICY_INDEX = 1;

    public final int type;
    /**
     * The original trigger time supplied by the caller. This can be in the elapsed or rtc time base
     * depending on the type of this alarm
     */
    public final long origWhen;
    public final boolean wakeup;
    public final PendingIntent operation;
    public final IAlarmListener listener;
    public final String listenerTag;
    public final String statsTag;
    public final WorkSource workSource;
    public final int flags;
    public final AlarmManager.AlarmClockInfo alarmClock;
    public final int uid;
    public final int creatorUid;
    public final String packageName;
    public final String sourcePackage;
    public final long windowLength;
    public final long repeatInterval;
    public int count;
    /** The earliest time this alarm is eligible to fire according to each policy */
    private long[] mPolicyWhenElapsed;
    /** The ultimate delivery time to be used for this alarm */
    private long mWhenElapsed;
    private long mMaxWhenElapsed;
    public AlarmManagerService.PriorityClass priorityClass;

    Alarm(int type, long when, long requestedWhenElapsed, long windowLength, long interval,
            PendingIntent op, IAlarmListener rec, String listenerTag, WorkSource ws, int flags,
            AlarmManager.AlarmClockInfo info, int uid, String pkgName) {
        this.type = type;
        origWhen = when;
        wakeup = type == AlarmManager.ELAPSED_REALTIME_WAKEUP
                || type == AlarmManager.RTC_WAKEUP;
        mPolicyWhenElapsed = new long[NUM_POLICIES];
        mPolicyWhenElapsed[REQUESTER_POLICY_INDEX] = requestedWhenElapsed;
        mWhenElapsed = requestedWhenElapsed;
        this.windowLength = windowLength;
        mMaxWhenElapsed = clampPositive(requestedWhenElapsed + windowLength);
        repeatInterval = interval;
        operation = op;
        listener = rec;
        this.listenerTag = listenerTag;
        statsTag = makeTag(op, listenerTag, type);
        workSource = ws;
        this.flags = flags;
        alarmClock = info;
        this.uid = uid;
        packageName = pkgName;
        sourcePackage = (operation != null) ? operation.getCreatorPackage() : packageName;
        creatorUid = (operation != null) ? operation.getCreatorUid() : this.uid;
    }

    public static String makeTag(PendingIntent pi, String tag, int type) {
        final String alarmString = type == ELAPSED_REALTIME_WAKEUP || type == RTC_WAKEUP
                ? "*walarm*:" : "*alarm*:";
        return (pi != null) ? pi.getTag(alarmString) : (alarmString + tag);
    }

    // Returns true if either matches
    public boolean matches(PendingIntent pi, IAlarmListener rec) {
        return (operation != null)
                ? operation.equals(pi)
                : rec != null && listener.asBinder().equals(rec.asBinder());
    }

    public boolean matches(String packageName) {
        return packageName.equals(sourcePackage);
    }

    /**
     * Get the earliest time this alarm is allowed to expire based on the given policy.
     *
     * @param policyIndex The index of the policy. One of [{@link #REQUESTER_POLICY_INDEX},
     *                    {@link #APP_STANDBY_POLICY_INDEX}].
     */
    public long getPolicyElapsed(int policyIndex) {
        return mPolicyWhenElapsed[policyIndex];
    }

    /**
     * Get the earliest time that this alarm should be delivered to the requesting app.
     */
    public long getWhenElapsed() {
        return mWhenElapsed;
    }

    /**
     * Get the latest time that this alarm should be delivered to the requesting app. Will be equal
     * to {@link #getWhenElapsed()} in case this is an exact alarm.
     */
    public long getMaxWhenElapsed() {
        return mMaxWhenElapsed;
    }

    /**
     * Set the earliest time this alarm can expire based on the passed policy index.
     *
     * @return {@code true} if this change resulted in a change in the ultimate delivery time (or
     * time window in the case of inexact alarms) of this alarm.
     * @see #getWhenElapsed()
     * @see #getMaxWhenElapsed()
     * @see #getPolicyElapsed(int)
     */
    public boolean setPolicyElapsed(int policyIndex, long policyElapsed) {
        mPolicyWhenElapsed[policyIndex] = policyElapsed;
        return updateWhenElapsed();
    }

    /**
     * @return {@code true} if either {@link #mWhenElapsed} or {@link #mMaxWhenElapsed} changes
     * due to this call.
     */
    private boolean updateWhenElapsed() {
        final long oldWhenElapsed = mWhenElapsed;
        mWhenElapsed = 0;
        for (int i = 0; i < NUM_POLICIES; i++) {
            mWhenElapsed = Math.max(mWhenElapsed, mPolicyWhenElapsed[i]);
        }

        final long oldMaxWhenElapsed = mMaxWhenElapsed;
        // windowLength should always be >= 0 here.
        final long maxRequestedElapsed = clampPositive(
                mPolicyWhenElapsed[REQUESTER_POLICY_INDEX] + windowLength);
        mMaxWhenElapsed = Math.max(maxRequestedElapsed, mWhenElapsed);

        return (oldWhenElapsed != mWhenElapsed) || (oldMaxWhenElapsed != mMaxWhenElapsed);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Alarm{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" type ");
        sb.append(type);
        sb.append(" origWhen ");
        sb.append(origWhen);
        sb.append(" ");
        sb.append(" whenElapsed ");
        sb.append(getWhenElapsed());
        sb.append(" ");
        sb.append(sourcePackage);
        sb.append('}');
        return sb.toString();
    }

    /**
     * @deprecated Use {{@link #dump(IndentingPrintWriter, long, SimpleDateFormat)}} instead.
     */
    @Deprecated
    public void dump(PrintWriter pw, String prefix, long nowELAPSED, SimpleDateFormat sdf) {
        final IndentingPrintWriter ipw = new IndentingPrintWriter(pw, prefix, prefix);
        dump(ipw, nowELAPSED, sdf);
    }

    private static String policyIndexToString(int index) {
        switch (index) {
            case REQUESTER_POLICY_INDEX:
                return "requester";
            case APP_STANDBY_POLICY_INDEX:
                return "app_standby";
            default:
                return "unknown";
        }
    }

    public static String typeToString(int type) {
        switch (type) {
            case RTC:
                return "RTC";
            case RTC_WAKEUP:
                return "RTC_WAKEUP";
            case ELAPSED_REALTIME:
                return "ELAPSED";
            case ELAPSED_REALTIME_WAKEUP:
                return "ELAPSED_WAKEUP";
            default:
                return "--unknown--";
        }
    }

    public void dump(IndentingPrintWriter ipw, long nowELAPSED, SimpleDateFormat sdf) {
        final boolean isRtc = (type == RTC || type == RTC_WAKEUP);
        ipw.print("tag=");
        ipw.println(statsTag);

        ipw.print("type=");
        ipw.print(typeToString(type));
        ipw.print(" origWhen=");
        if (isRtc) {
            ipw.print(sdf.format(new Date(origWhen)));
        } else {
            TimeUtils.formatDuration(origWhen, nowELAPSED, ipw);
        }
        ipw.print(" window=");
        TimeUtils.formatDuration(windowLength, ipw);
        ipw.print(" repeatInterval=");
        ipw.print(repeatInterval);
        ipw.print(" count=");
        ipw.print(count);
        ipw.print(" flags=0x");
        ipw.println(Integer.toHexString(flags));

        ipw.print("policyWhenElapsed:");
        for (int i = 0; i < NUM_POLICIES; i++) {
            ipw.print(" " + policyIndexToString(i) + "=");
            TimeUtils.formatDuration(mPolicyWhenElapsed[i], nowELAPSED, ipw);
        }
        ipw.println();

        ipw.print("whenElapsed=");
        TimeUtils.formatDuration(getWhenElapsed(), nowELAPSED, ipw);
        ipw.print(" maxWhenElapsed=");
        TimeUtils.formatDuration(mMaxWhenElapsed, nowELAPSED, ipw);
        ipw.println();

        if (alarmClock != null) {
            ipw.println("Alarm clock:");

            ipw.print("  triggerTime=");
            ipw.println(sdf.format(new Date(alarmClock.getTriggerTime())));

            ipw.print("  showIntent=");
            ipw.println(alarmClock.getShowIntent());
        }
        if (operation != null) {
            ipw.print("operation=");
            ipw.println(operation);
        }
        if (listener != null) {
            ipw.print("listener=");
            ipw.println(listener.asBinder());
        }
    }

    public void dumpDebug(ProtoOutputStream proto, long fieldId, long nowElapsed) {
        final long token = proto.start(fieldId);

        proto.write(AlarmProto.TAG, statsTag);
        proto.write(AlarmProto.TYPE, type);
        proto.write(AlarmProto.TIME_UNTIL_WHEN_ELAPSED_MS, getWhenElapsed() - nowElapsed);
        proto.write(AlarmProto.WINDOW_LENGTH_MS, windowLength);
        proto.write(AlarmProto.REPEAT_INTERVAL_MS, repeatInterval);
        proto.write(AlarmProto.COUNT, count);
        proto.write(AlarmProto.FLAGS, flags);
        if (alarmClock != null) {
            alarmClock.dumpDebug(proto, AlarmProto.ALARM_CLOCK);
        }
        if (operation != null) {
            operation.dumpDebug(proto, AlarmProto.OPERATION);
        }
        if (listener != null) {
            proto.write(AlarmProto.LISTENER, listener.asBinder().toString());
        }

        proto.end(token);
    }
}
