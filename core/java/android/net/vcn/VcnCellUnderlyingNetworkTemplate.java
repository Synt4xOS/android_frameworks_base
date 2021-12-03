/*
 * Copyright (C) 2021 The Android Open Source Project
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
package android.net.vcn;

import static android.net.vcn.VcnUnderlyingNetworkTemplate.MATCH_ANY;
import static android.net.vcn.VcnUnderlyingNetworkTemplate.getMatchCriteriaString;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.INTEGER_SERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_DESERIALIZER;
import static com.android.server.vcn.util.PersistableBundleUtils.STRING_SERIALIZER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.net.NetworkCapabilities;
import android.net.vcn.VcnUnderlyingNetworkTemplate.MatchCriteria;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.vcn.util.PersistableBundleUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * This class represents a configuration for a network template class of underlying cellular
 * networks.
 *
 * <p>See {@link VcnUnderlyingNetworkTemplate}
 *
 * @hide
 */
public final class VcnCellUnderlyingNetworkTemplate extends VcnUnderlyingNetworkTemplate {
    private static final String ALLOWED_NETWORK_PLMN_IDS_KEY = "mAllowedNetworkPlmnIds";
    @NonNull private final Set<String> mAllowedNetworkPlmnIds;
    private static final String ALLOWED_SPECIFIC_CARRIER_IDS_KEY = "mAllowedSpecificCarrierIds";
    @NonNull private final Set<Integer> mAllowedSpecificCarrierIds;

    private static final String ROAMING_MATCH_KEY = "mRoamingMatchCriteria";
    private final int mRoamingMatchCriteria;

    private static final String OPPORTUNISTIC_MATCH_KEY = "mOpportunisticMatchCriteria";
    private final int mOpportunisticMatchCriteria;

    private VcnCellUnderlyingNetworkTemplate(
            int networkQuality,
            int meteredMatchCriteria,
            Set<String> allowedNetworkPlmnIds,
            Set<Integer> allowedSpecificCarrierIds,
            int roamingMatchCriteria,
            int opportunisticMatchCriteria) {
        super(NETWORK_PRIORITY_TYPE_CELL, networkQuality, meteredMatchCriteria);
        mAllowedNetworkPlmnIds = new ArraySet<>(allowedNetworkPlmnIds);
        mAllowedSpecificCarrierIds = new ArraySet<>(allowedSpecificCarrierIds);
        mRoamingMatchCriteria = roamingMatchCriteria;
        mOpportunisticMatchCriteria = opportunisticMatchCriteria;

        validate();
    }

    /** @hide */
    @Override
    protected void validate() {
        super.validate();
        validatePlmnIds(mAllowedNetworkPlmnIds);
        Objects.requireNonNull(mAllowedSpecificCarrierIds, "matchingCarrierIds is null");
        validateMatchCriteria(mRoamingMatchCriteria, "mRoamingMatchCriteria");
        validateMatchCriteria(mOpportunisticMatchCriteria, "mOpportunisticMatchCriteria");
    }

    private static void validatePlmnIds(Set<String> matchingOperatorPlmnIds) {
        Objects.requireNonNull(matchingOperatorPlmnIds, "matchingOperatorPlmnIds is null");

        // A valid PLMN is a concatenation of MNC and MCC, and thus consists of 5 or 6 decimal
        // digits.
        for (String id : matchingOperatorPlmnIds) {
            if ((id.length() == 5 || id.length() == 6) && id.matches("[0-9]+")) {
                continue;
            } else {
                throw new IllegalArgumentException("Found invalid PLMN ID: " + id);
            }
        }
    }

    /** @hide */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static VcnCellUnderlyingNetworkTemplate fromPersistableBundle(
            @NonNull PersistableBundle in) {
        Objects.requireNonNull(in, "PersistableBundle is null");

        final int networkQuality = in.getInt(NETWORK_QUALITY_KEY);
        final int meteredMatchCriteria = in.getInt(METERED_MATCH_KEY);

        final PersistableBundle plmnIdsBundle =
                in.getPersistableBundle(ALLOWED_NETWORK_PLMN_IDS_KEY);
        Objects.requireNonNull(plmnIdsBundle, "plmnIdsBundle is null");
        final Set<String> allowedNetworkPlmnIds =
                new ArraySet<String>(
                        PersistableBundleUtils.toList(plmnIdsBundle, STRING_DESERIALIZER));

        final PersistableBundle specificCarrierIdsBundle =
                in.getPersistableBundle(ALLOWED_SPECIFIC_CARRIER_IDS_KEY);
        Objects.requireNonNull(specificCarrierIdsBundle, "specificCarrierIdsBundle is null");
        final Set<Integer> allowedSpecificCarrierIds =
                new ArraySet<Integer>(
                        PersistableBundleUtils.toList(
                                specificCarrierIdsBundle, INTEGER_DESERIALIZER));

        final int roamingMatchCriteria = in.getInt(ROAMING_MATCH_KEY);
        final int opportunisticMatchCriteria = in.getInt(OPPORTUNISTIC_MATCH_KEY);

        return new VcnCellUnderlyingNetworkTemplate(
                networkQuality,
                meteredMatchCriteria,
                allowedNetworkPlmnIds,
                allowedSpecificCarrierIds,
                roamingMatchCriteria,
                opportunisticMatchCriteria);
    }

    /** @hide */
    @Override
    @NonNull
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public PersistableBundle toPersistableBundle() {
        final PersistableBundle result = super.toPersistableBundle();

        final PersistableBundle plmnIdsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mAllowedNetworkPlmnIds), STRING_SERIALIZER);
        result.putPersistableBundle(ALLOWED_NETWORK_PLMN_IDS_KEY, plmnIdsBundle);

        final PersistableBundle specificCarrierIdsBundle =
                PersistableBundleUtils.fromList(
                        new ArrayList<>(mAllowedSpecificCarrierIds), INTEGER_SERIALIZER);
        result.putPersistableBundle(ALLOWED_SPECIFIC_CARRIER_IDS_KEY, specificCarrierIdsBundle);

        result.putInt(ROAMING_MATCH_KEY, mRoamingMatchCriteria);
        result.putInt(OPPORTUNISTIC_MATCH_KEY, mOpportunisticMatchCriteria);

        return result;
    }

    /**
     * Retrieve the matching operator PLMN IDs, or an empty set if any PLMN ID is acceptable.
     *
     * @see Builder#setOperatorPlmnIds(Set)
     */
    @NonNull
    public Set<String> getOperatorPlmnIds() {
        return Collections.unmodifiableSet(mAllowedNetworkPlmnIds);
    }

    /**
     * Retrieve the matching sim specific carrier IDs, or an empty set if any sim specific carrier
     * ID is acceptable.
     *
     * @see Builder#setSimSpecificCarrierIds(Set)
     */
    @NonNull
    public Set<Integer> getSimSpecificCarrierIds() {
        return Collections.unmodifiableSet(mAllowedSpecificCarrierIds);
    }

    /**
     * Return the matching criteria for roaming networks.
     *
     * @see Builder#setRoaming(int)
     */
    @MatchCriteria
    public int getRoaming() {
        return mRoamingMatchCriteria;
    }

    /**
     * Return the matching criteria for opportunistic cellular subscriptions.
     *
     * @see Builder#setOpportunistic(int)
     */
    @MatchCriteria
    public int getOpportunistic() {
        return mOpportunisticMatchCriteria;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                super.hashCode(),
                mAllowedNetworkPlmnIds,
                mAllowedSpecificCarrierIds,
                mRoamingMatchCriteria,
                mOpportunisticMatchCriteria);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        if (!super.equals(other)) {
            return false;
        }

        if (!(other instanceof VcnCellUnderlyingNetworkTemplate)) {
            return false;
        }

        final VcnCellUnderlyingNetworkTemplate rhs = (VcnCellUnderlyingNetworkTemplate) other;
        return Objects.equals(mAllowedNetworkPlmnIds, rhs.mAllowedNetworkPlmnIds)
                && Objects.equals(mAllowedSpecificCarrierIds, rhs.mAllowedSpecificCarrierIds)
                && mRoamingMatchCriteria == rhs.mRoamingMatchCriteria
                && mOpportunisticMatchCriteria == rhs.mOpportunisticMatchCriteria;
    }

    /** @hide */
    @Override
    void dumpTransportSpecificFields(IndentingPrintWriter pw) {
        pw.println("mAllowedNetworkPlmnIds: " + mAllowedNetworkPlmnIds.toString());
        pw.println("mAllowedSpecificCarrierIds: " + mAllowedSpecificCarrierIds.toString());
        pw.println("mRoamingMatchCriteria: " + getMatchCriteriaString(mRoamingMatchCriteria));
        pw.println(
                "mOpportunisticMatchCriteria: "
                        + getMatchCriteriaString(mOpportunisticMatchCriteria));
    }

    /** This class is used to incrementally build VcnCellUnderlyingNetworkTemplate objects. */
    public static final class Builder {
        private int mNetworkQuality = NETWORK_QUALITY_ANY;
        private int mMeteredMatchCriteria = MATCH_ANY;

        @NonNull private final Set<String> mAllowedNetworkPlmnIds = new ArraySet<>();
        @NonNull private final Set<Integer> mAllowedSpecificCarrierIds = new ArraySet<>();

        private int mRoamingMatchCriteria = MATCH_ANY;
        private int mOpportunisticMatchCriteria = MATCH_ANY;

        /** Construct a Builder object. */
        public Builder() {}

        /**
         * Set the required network quality to match this template.
         *
         * <p>Network quality is a aggregation of multiple signals that reflect the network link
         * metrics. For example, the network validation bit (see {@link
         * NetworkCapabilities#NET_CAPABILITY_VALIDATED}), estimated first hop transport bandwidth
         * and signal strength.
         *
         * @param networkQuality the required network quality. Defaults to NETWORK_QUALITY_ANY
         * @hide
         */
        @NonNull
        public Builder setNetworkQuality(@NetworkQuality int networkQuality) {
            validateNetworkQuality(networkQuality);

            mNetworkQuality = networkQuality;
            return this;
        }

        /**
         * Set the matching criteria for metered networks.
         *
         * @param matchCriteria the matching criteria for metered networks. Defaults to {@link
         *     #MATCH_ANY}. See {@link NetworkCapabilities#NET_CAPABILITY_NOT_METERED}
         */
        // The matching getter is defined in the super class. Please see {@link
        // VcnUnderlyingNetworkTemplate#getMetered()}
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setMetered(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setMetered");

            mMeteredMatchCriteria = matchCriteria;
            return this;
        }

        /**
         * Set operator PLMN IDs with which a network can match this template.
         *
         * <p>This is used to distinguish cases where roaming agreements may dictate a different
         * priority from a partner's networks.
         *
         * @param operatorPlmnIds the matching operator PLMN IDs in String. Network with one of the
         *     matching PLMN IDs can match this template. Defaults to an empty set, allowing ANY
         *     PLMN ID. A valid PLMN is a concatenation of MNC and MCC, and thus consists of 5 or 6
         *     decimal digits. See {@link SubscriptionInfo#getMccString()} and {@link
         *     SubscriptionInfo#getMncString()}.
         */
        @NonNull
        public Builder setOperatorPlmnIds(@NonNull Set<String> operatorPlmnIds) {
            validatePlmnIds(operatorPlmnIds);

            mAllowedNetworkPlmnIds.clear();
            mAllowedNetworkPlmnIds.addAll(operatorPlmnIds);
            return this;
        }

        /**
         * Set sim specific carrier IDs with which a network can match this template.
         *
         * @param simSpecificCarrierIds the matching sim specific carrier IDs. Network with one of
         *     the sim specific carrier IDs can match this template. Defaults to an empty set,
         *     allowing ANY carrier ID. See {@link TelephonyManager#getSimSpecificCarrierId()}.
         */
        @NonNull
        public Builder setSimSpecificCarrierIds(@NonNull Set<Integer> simSpecificCarrierIds) {
            Objects.requireNonNull(simSpecificCarrierIds, "simSpecificCarrierIds is null");

            mAllowedSpecificCarrierIds.clear();
            mAllowedSpecificCarrierIds.addAll(simSpecificCarrierIds);
            return this;
        }

        /**
         * Set the matching criteria for roaming networks.
         *
         * @param matchCriteria the matching criteria for roaming networks. Defaults to {@link
         *     #MATCH_ANY}. See {@link NetworkCapabilities#NET_CAPABILITY_NOT_ROAMING}.
         */
        @NonNull
        public Builder setRoaming(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setRoaming");

            mRoamingMatchCriteria = matchCriteria;
            return this;
        }

        /**
         * Set the matching criteria for opportunistic cellular subscriptions.
         *
         * @param matchCriteria the matching criteria for opportunistic cellular subscriptions.
         *     Defaults to {@link #MATCH_ANY}. See {@link
         *     SubscriptionManager#setOpportunistic(boolean, int)}
         */
        @NonNull
        public Builder setOpportunistic(@MatchCriteria int matchCriteria) {
            validateMatchCriteria(matchCriteria, "setOpportunistic");

            mOpportunisticMatchCriteria = matchCriteria;
            return this;
        }

        /** Build the VcnCellUnderlyingNetworkTemplate. */
        @NonNull
        public VcnCellUnderlyingNetworkTemplate build() {
            return new VcnCellUnderlyingNetworkTemplate(
                    mNetworkQuality,
                    mMeteredMatchCriteria,
                    mAllowedNetworkPlmnIds,
                    mAllowedSpecificCarrierIds,
                    mRoamingMatchCriteria,
                    mOpportunisticMatchCriteria);
        }
    }
}
