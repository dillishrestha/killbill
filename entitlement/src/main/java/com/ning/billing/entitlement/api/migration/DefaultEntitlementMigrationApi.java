/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.entitlement.api.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import com.ning.billing.util.callcontext.CallContext;
import org.joda.time.DateTime;

import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.ning.billing.catalog.api.ProductCategory;
import com.ning.billing.entitlement.alignment.MigrationPlanAligner;
import com.ning.billing.entitlement.alignment.TimedMigration;
import com.ning.billing.entitlement.api.migration.AccountMigrationData.BundleMigrationData;
import com.ning.billing.entitlement.api.migration.AccountMigrationData.SubscriptionMigrationData;
import com.ning.billing.entitlement.api.user.SubscriptionBundleData;
import com.ning.billing.entitlement.api.user.SubscriptionData;
import com.ning.billing.entitlement.api.user.SubscriptionFactory;
import com.ning.billing.entitlement.api.user.SubscriptionFactory.SubscriptionBuilder;
import com.ning.billing.entitlement.engine.dao.EntitlementDao;
import com.ning.billing.entitlement.events.EntitlementEvent;
import com.ning.billing.entitlement.events.EntitlementEvent.EventType;
import com.ning.billing.entitlement.events.phase.PhaseEvent;
import com.ning.billing.entitlement.events.phase.PhaseEventData;
import com.ning.billing.entitlement.events.user.ApiEvent;
import com.ning.billing.entitlement.events.user.ApiEventBuilder;
import com.ning.billing.entitlement.events.user.ApiEventCancel;
import com.ning.billing.entitlement.events.user.ApiEventChange;
import com.ning.billing.entitlement.events.user.ApiEventMigrateBilling;
import com.ning.billing.entitlement.events.user.ApiEventMigrateEntitlement;
import com.ning.billing.entitlement.events.user.ApiEventType;
import com.ning.billing.entitlement.exceptions.EntitlementError;
import com.ning.billing.util.clock.Clock;

public class DefaultEntitlementMigrationApi implements EntitlementMigrationApi {
    private final EntitlementDao dao;
    private final MigrationPlanAligner migrationAligner;
    private final SubscriptionFactory factory;
    private final Clock clock;

    @Inject
    public DefaultEntitlementMigrationApi(MigrationPlanAligner migrationAligner,
            SubscriptionFactory factory,
            EntitlementDao dao,
            Clock clock) {
        this.dao = dao;
        this.migrationAligner = migrationAligner;
        this.factory = factory;
        this.clock = clock;
    }

    @Override
    public void migrate(EntitlementAccountMigration toBeMigrated, CallContext context)
    throws EntitlementMigrationApiException {
        AccountMigrationData accountMigrationData = createAccountMigrationData(toBeMigrated);
        dao.migrate(toBeMigrated.getAccountKey(), accountMigrationData, context);
    }

    private AccountMigrationData createAccountMigrationData(EntitlementAccountMigration toBeMigrated)
    throws EntitlementMigrationApiException  {

        final UUID accountId = toBeMigrated.getAccountKey();
        final DateTime now = clock.getUTCNow();

        List<BundleMigrationData> accountBundleData = new LinkedList<BundleMigrationData>();

        for (final EntitlementBundleMigration curBundle : toBeMigrated.getBundles()) {

            SubscriptionBundleData bundleData = new SubscriptionBundleData(curBundle.getBundleKey(), accountId);
            List<SubscriptionMigrationData> bundleSubscriptionData = new LinkedList<AccountMigrationData.SubscriptionMigrationData>();


            List<EntitlementSubscriptionMigration> sortedSubscriptions = Lists.newArrayList(curBundle.getSubscriptions());
            // Make sure we have first mpp or legacy, then addon and for each category order by CED
            Collections.sort(sortedSubscriptions, new Comparator<EntitlementSubscriptionMigration>() {
                @Override
                public int compare(EntitlementSubscriptionMigration o1,
                        EntitlementSubscriptionMigration o2) {
                    if (o1.getCategory().equals(o2.getCategory())) {
                        return o1.getSubscriptionCases()[0].getEffectiveDate().compareTo(o2.getSubscriptionCases()[0].getEffectiveDate());
                    } else {
                        if (o1.getCategory().equals("mpp")) {
                            return -1;
                        } else if (o2.getCategory().equals("mpp")) {
                            return 1;
                        } else if (o1.getCategory().equals("legacy")) {
                            return -1;
                        } else if (o2.getCategory().equals("legacy")) {
                            return 1;
                        } else {
                            return 0;
                        }
                    }
                }
            });

            DateTime bundleStartDate = null;
            for (EntitlementSubscriptionMigration curSub : sortedSubscriptions) {
                SubscriptionMigrationData data = null;
                if (bundleStartDate == null) {
                    data = createInitialSubscription(bundleData.getId(), curSub.getCategory(), curSub.getSubscriptionCases(), now, curSub.getChargedThroughDate());
                    bundleStartDate = data.getInitialEvents().get(0).getEffectiveDate();
                } else {
                    data = createSubscriptionMigrationDataWithBundleDate(bundleData.getId(), curSub.getCategory(), curSub.getSubscriptionCases(), now, bundleStartDate, curSub.getChargedThroughDate());
                }
                if (data != null) {
                    bundleSubscriptionData.add(data);
                }
            }
            BundleMigrationData bundleMigrationData = new BundleMigrationData(bundleData, bundleSubscriptionData);
            accountBundleData.add(bundleMigrationData);
        }
        AccountMigrationData accountMigrationData = new AccountMigrationData(accountBundleData);
        return accountMigrationData;
    }

    private SubscriptionMigrationData createInitialSubscription(UUID bundleId, ProductCategory productCategory,
            EntitlementSubscriptionMigrationCase [] input, DateTime now, DateTime ctd)
        throws EntitlementMigrationApiException {

        TimedMigration [] events = migrationAligner.getEventsMigration(input, now);
        DateTime migrationStartDate= events[0].getEventTime();
        List<EntitlementEvent> emptyEvents =  Collections.emptyList();
        SubscriptionData subscriptionData = factory.createSubscription(new SubscriptionBuilder()
            .setId(UUID.randomUUID())
            .setBundleId(bundleId)
            .setCategory(productCategory)
            .setBundleStartDate(migrationStartDate)
            .setStartDate(migrationStartDate),
            emptyEvents);
        return new SubscriptionMigrationData(subscriptionData, toEvents(subscriptionData, now, ctd, events));
    }

    private SubscriptionMigrationData createSubscriptionMigrationDataWithBundleDate(UUID bundleId, ProductCategory productCategory,
            EntitlementSubscriptionMigrationCase [] input, DateTime now, DateTime bundleStartDate, DateTime ctd)
    throws EntitlementMigrationApiException {
        TimedMigration [] events = migrationAligner.getEventsMigration(input, now);
        DateTime migrationStartDate= events[0].getEventTime();
        List<EntitlementEvent> emptyEvents =  Collections.emptyList();
        SubscriptionData subscriptionData = factory.createSubscription(new SubscriptionBuilder()
            .setId(UUID.randomUUID())
            .setBundleId(bundleId)
            .setCategory(productCategory)
            .setBundleStartDate(bundleStartDate)
            .setStartDate(migrationStartDate),
            emptyEvents);
        return new SubscriptionMigrationData(subscriptionData, toEvents(subscriptionData, now, ctd, events));
    }

    private List<EntitlementEvent> toEvents(SubscriptionData subscriptionData, DateTime now, DateTime ctd, TimedMigration [] migrationEvents) {


        ApiEventMigrateEntitlement creationEvent = null;
        List<EntitlementEvent> events = new ArrayList<EntitlementEvent>(migrationEvents.length);
        for (TimedMigration cur : migrationEvents) {

            if (cur.getEventType() == EventType.PHASE) {
                PhaseEvent nextPhaseEvent = PhaseEventData.createNextPhaseEvent(cur.getPhase().getName(), subscriptionData, now, cur.getEventTime());
                events.add(nextPhaseEvent);

            } else if (cur.getEventType() == EventType.API_USER) {

                ApiEventBuilder builder = new ApiEventBuilder()
                .setSubscriptionId(subscriptionData.getId())
                .setEventPlan((cur.getPlan() != null) ? cur.getPlan().getName() : null)
                .setEventPlanPhase((cur.getPhase() != null) ? cur.getPhase().getName() : null)
                .setEventPriceList(cur.getPriceList())
                .setActiveVersion(subscriptionData.getActiveVersion())
                .setEffectiveDate(cur.getEventTime())
                .setProcessedDate(now)
                .setRequestedDate(now)
                .setFromDisk(true);

                switch(cur.getApiEventType()) {
                case MIGRATE_ENTITLEMENT:
                    creationEvent = new ApiEventMigrateEntitlement(builder);
                    events.add(creationEvent);
                    break;

                case CHANGE:
                    events.add(new ApiEventChange(builder));
                    break;
                case CANCEL:
                    events.add(new ApiEventCancel(builder));
                    break;
                default:
                    throw new EntitlementError(String.format("Unexpected type of api migration event %s", cur.getApiEventType()));
                }
            } else {
                throw new EntitlementError(String.format("Unexpected type of migration event %s", cur.getEventType()));
            }
        }
        if (creationEvent == null || ctd == null) {
            throw new EntitlementError(String.format("Could not create migration billing event ctd = %s", ctd));
        }
        events.add(new ApiEventMigrateBilling(creationEvent, ctd));
        Collections.sort(events, new Comparator<EntitlementEvent>() {

            int compForApiType(EntitlementEvent o1, EntitlementEvent o2, ApiEventType type) {

                ApiEventType apiO1 = null;
                if (o1.getType() == EventType.API_USER) {
                    apiO1 = ((ApiEvent) o1).getEventType();
                }
                ApiEventType apiO2 = null;
                if (o2.getType() == EventType.API_USER) {
                    apiO2 = ((ApiEvent) o2).getEventType();
                }
                if (apiO1 != null && apiO1 == type) {
                    return -1;
                } else if (apiO2 != null && apiO2 == type) {
                    return 1;
                } else {
                    return 0;
                }
            }

            @Override
            public int compare(EntitlementEvent o1, EntitlementEvent o2) {

                int comp = o1.getEffectiveDate().compareTo(o2.getEffectiveDate());
                if (comp == 0) {
                    comp = compForApiType(o1, o2, ApiEventType.MIGRATE_ENTITLEMENT);
                }
                if (comp == 0) {
                    comp = compForApiType(o1, o2, ApiEventType.MIGRATE_BILLING);
                }
                return comp;
            }
        });
        return events;
    }
}
