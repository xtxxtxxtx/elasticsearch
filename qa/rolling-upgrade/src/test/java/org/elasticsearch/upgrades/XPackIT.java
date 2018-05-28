/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.upgrades;

import org.apache.http.util.EntityUtils;
import org.elasticsearch.common.xcontent.DeprecationHandler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.junit.Before;
import org.elasticsearch.Version;
import org.elasticsearch.client.Request;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assume.assumeThat;

/**
 * Basic tests for simple xpack functionality that are only run if the
 * cluster is the on the "zip" distribution.
 */
public class XPackIT extends AbstractRollingTestCase {
    @Before
    public void skipIfNotZip() {
        assumeThat("test is only supported if the distribution contains xpack",
                System.getProperty("tests.distribution"), equalTo("zip"));
        /*
         * *Mostly* we want this for when we're upgrading from pre-6.3's
         * zip distribution which doesn't contain xpack to post 6.3's zip
         * distribution which *does* contain xpack. But we'll also run it
         * on all upgrades for completeness's sake.
         */
    }

    /**
     * Tests that xpack is able to work itself into a sane state during the
     * upgrade by testing that it is able to create all of the templates that
     * it needs. This isn't a very strong assertion of sanity, but it is better
     * than nothing and should catch a few sad cases.
     * <p>
     * The trouble is that when xpack isn't able to create the templates that
     * it needs it retries over and over and over again. This can
     * <strong>really</strong> slow things down. This test asserts that xpack
     * was able to create the templates so it <strong>shouldn't</strong> be
     * spinning trying to create things and slowing down the rest of the
     * system.
     */
    public void testIndexTemplatesCreated() throws Exception {
        Version upgradeFromVersion =
                Version.fromString(System.getProperty("tests.upgrade_from_version"));
        boolean upgradeFromVersionHasXPack = upgradeFromVersion.onOrAfter(Version.V_6_3_0);
        assumeFalse("this test doesn't really prove anything if the starting version has xpack and it is *much* more complex to maintain",
                upgradeFromVersionHasXPack);
        assumeFalse("since we're upgrading from a version without x-pack it won't have any templates",
                CLUSTER_TYPE == ClusterType.OLD);

        List<String> expectedTemplates = new ArrayList<>();
        // Watcher creates its templates as soon as the first watcher node connects
        expectedTemplates.add(".triggered_watches");
        expectedTemplates.add(".watch-history-8");
        expectedTemplates.add(".watches");
        if (masterIsNewVersion()) {
            // Everything else waits until the master is upgraded to create its templates
            expectedTemplates.add(".ml-anomalies-");
            expectedTemplates.add(".ml-meta");
            expectedTemplates.add(".ml-notifications");
            expectedTemplates.add(".ml-state");
            expectedTemplates.add("logstash-index-template");
            expectedTemplates.add("security-index-template");
            expectedTemplates.add("security_audit_log");
        }
        Collections.sort(expectedTemplates);

        /*
         * The index templates are created asynchronously after startup and
         * while this is usually fast we use assertBusy here just in case
         * they aren't created by the time this test is run.
         */
        assertBusy(() -> {
            List<String> actualTemplates;
            try (XContentParser parser = JsonXContent.jsonXContent.createParser(
                    NamedXContentRegistry.EMPTY,
                    DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                    client().performRequest(new Request("GET", "/_template")).getEntity().getContent())) {
                actualTemplates = new ArrayList<>(parser.map().keySet());
            }
            Collections.sort(actualTemplates);
            /*
             * This test asserts that the templates match *exactly* to force
             * us to keep the list of templates up to date. Most templates
             * aren't likely to cause a problem on upgrade but it is better
             * to be safe and make sure they are all created than to be sorry
             * and miss a bug that causes one to be missed on upgrade.
             *
             * We sort the templates so the error message is easy to read.
             */
            assertEquals(expectedTemplates, actualTemplates);
        });
    }

    /**
     * Test a basic feature (SQL) after the upgrade which only requires the
     * "default" basic license. Note that the test methods on this class can
     * run in any order so we <strong>might</strong> have already installed a
     * trial license.
     */
    public void testBasicFeatureAfterUpgrade() throws IOException {
        assumeThat("running this on the unupgraded cluster would change its state and it wouldn't work prior to 6.3 anyway",
                CLUSTER_TYPE, equalTo(ClusterType.UPGRADED));

        Request bulk = new Request("POST", "/sql_test/doc/_bulk");
        bulk.setJsonEntity(
              "{\"index\":{}}\n"
            + "{\"f\": \"1\"}\n"
            + "{\"index\":{}}\n"
            + "{\"f\": \"2\"}\n");
        bulk.addParameter("refresh", "true");
        client().performRequest(bulk);

        Request sql = new Request("POST", "/_xpack/sql");
        sql.setJsonEntity("{\"query\": \"SELECT * FROM sql_test WHERE f > 1 ORDER BY f ASC\"}");
        String response = EntityUtils.toString(client().performRequest(sql).getEntity());
        assertEquals("{\"columns\":[{\"name\":\"f\",\"type\":\"text\"}],\"rows\":[[\"2\"]]}", response);
    }

    /**
     * Test creating a trial license after the upgrade and a feature (ML) that
     * requires the license. Our other tests test cover starting a new cluster
     * with the default distribution and enabling the trial license but this
     * test is the only one tests the rolling upgrade from the oss distribution
     * to the default distribution with xpack and then creating of a trial
     * license. We don't <strong>do</strong> a lot with the trial license
     * because for the most part those things are tested elsewhere, off in
     * xpack. But we do use the trial license a little bit to make sure that
     * creating it worked properly.
     */
    public void testTrialLicense() throws IOException {
        assumeThat("running this on the unupgraded cluster would change its state and it wouldn't work prior to 6.3 anyway",
                CLUSTER_TYPE, equalTo(ClusterType.UPGRADED));

        Request startTrial = new Request("POST", "/_xpack/license/start_trial");
        startTrial.addParameter("acknowledge", "true");
        client().performRequest(startTrial);

        String noJobs = EntityUtils.toString(
            client().performRequest(new Request("GET", "/_xpack/ml/anomaly_detectors")).getEntity());
        assertEquals("{\"count\":0,\"jobs\":[]}", noJobs);

        Request createJob = new Request("PUT", "/_xpack/ml/anomaly_detectors/test_job");
        createJob.setJsonEntity(
                  "{\n"
                + "  \"analysis_config\" : {\n"
                + "    \"bucket_span\": \"10m\",\n"
                + "    \"detectors\": [\n"
                + "      {\n"
                + "        \"function\": \"sum\",\n"
                + "        \"field_name\": \"total\"\n"
                + "      }\n"
                + "    ]\n"
                + "  },\n"
                + "  \"data_description\": {\n"
                + "    \"time_field\": \"timestamp\",\n"
                + "    \"time_format\": \"epoch_ms\"\n"
                + "  }\n"
                + "}\n");
        client().performRequest(createJob);
    }

    /**
     * Has the master been upgraded to the new version?
     */
    private boolean masterIsNewVersion() throws IOException {
        Map<?, ?> map;
        try (XContentParser parser = JsonXContent.jsonXContent.createParser(
                NamedXContentRegistry.EMPTY,
                DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
                client().performRequest(new Request("GET", "/_nodes/_master")).getEntity().getContent())) {
            map = parser.map();
        }
        map = (Map<?, ?>) map.get("nodes");
        assertThat(map.values(), hasSize(1));
        map = (Map<?, ?>) map.values().iterator().next();
        Version masterVersion = Version.fromString(map.get("version").toString());
        return Version.CURRENT.equals(masterVersion);
    }
}
