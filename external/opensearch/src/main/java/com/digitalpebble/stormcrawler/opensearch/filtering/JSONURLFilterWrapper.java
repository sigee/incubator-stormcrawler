/**
 * Licensed to DigitalPebble Ltd under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership.
 * DigitalPebble licenses this file to You under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy of the
 * License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.digitalpebble.stormcrawler.opensearch.filtering;

import com.digitalpebble.stormcrawler.JSONResource;
import com.digitalpebble.stormcrawler.Metadata;
import com.digitalpebble.stormcrawler.filtering.URLFilter;
import com.digitalpebble.stormcrawler.opensearch.OpensearchConnection;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.opensearch.action.get.GetRequest;
import org.opensearch.action.get.GetResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps a URLFilter whose resources are in a JSON file that can be stored in ES. The benefit of
 * doing this is that the resources can be refreshed automatically and modified without having to
 * recompile the jar and restart the topology. The connection to ES is done via the config and uses
 * a new bolt type 'config'.
 *
 * <p>The configuration of the delegate is done in the urlfilters.json as usual.
 *
 * <pre>
 *  {
 *     "class": "com.digitalpebble.stormcrawler.elasticsearch.filtering.JSONURLFilterWrapper",
 *     "name": "ESFastURLFilter",
 *     "params": {
 *         "refresh": "60",
 *         "delegate": {
 *             "class": "com.digitalpebble.stormcrawler.filtering.regex.FastURLFilter",
 *             "params": {
 *                 "file": "fast.urlfilter.json"
 *             }
 *         }
 *     }
 *  }
 * </pre>
 *
 * The resource file can be pushed to ES with
 *
 * <pre>
 *  curl -XPUT 'localhost:9200/config/config/fast.urlfilter.json?pretty' -H 'Content-Type: application/json' -d @fast.urlfilter.json
 * </pre>
 */
public class JSONURLFilterWrapper extends URLFilter {

    private static final Logger LOG = LoggerFactory.getLogger(JSONURLFilterWrapper.class);

    private URLFilter delegatedURLFilter;

    public void configure(@NotNull Map<String, Object> stormConf, @NotNull JsonNode filterParams) {

        String urlfilterclass = null;

        JsonNode delegateNode = filterParams.get("delegate");
        if (delegateNode == null) {
            throw new RuntimeException("delegateNode undefined!");
        }

        JsonNode node = delegateNode.get("class");
        if (node != null && node.isTextual()) {
            urlfilterclass = node.asText();
        }

        if (urlfilterclass == null) {
            throw new RuntimeException("urlfilter.class undefined!");
        }

        // load an instance of the delegated parsefilter
        try {
            Class<?> filterClass = Class.forName(urlfilterclass);

            boolean subClassOK = URLFilter.class.isAssignableFrom(filterClass);
            if (!subClassOK) {
                throw new RuntimeException(
                        "Filter " + urlfilterclass + " does not extend URLFilter");
            }

            delegatedURLFilter = (URLFilter) filterClass.getDeclaredConstructor().newInstance();

            // check that it implements JSONResource
            if (!JSONResource.class.isInstance(delegatedURLFilter)) {
                throw new RuntimeException(
                        "Filter " + urlfilterclass + " does not implement JSONResource");
            }

        } catch (Exception e) {
            LOG.error("Can't setup {}: {}", urlfilterclass, e);
            throw new RuntimeException("Can't setup " + urlfilterclass, e);
        }

        // configure it
        node = delegateNode.get("params");

        delegatedURLFilter.configure(stormConf, node);

        int refreshRate = 600;

        node = filterParams.get("refresh");
        if (node != null && node.isInt()) {
            refreshRate = node.asInt(refreshRate);
        }

        final JSONResource resource = (JSONResource) delegatedURLFilter;

        new Timer()
                .schedule(
                        new TimerTask() {
                            private RestHighLevelClient esClient;

                            public void run() {
                                if (esClient == null) {
                                    try {
                                        esClient =
                                                OpensearchConnection.getClient(stormConf, "config");
                                    } catch (Exception e) {
                                        LOG.error("Exception while creating ES connection", e);
                                    }
                                }
                                if (esClient != null) {
                                    LOG.info("Reloading json resources from ES");
                                    try {
                                        GetResponse response =
                                                esClient.get(
                                                        new GetRequest(
                                                                "config",
                                                                resource.getResourceFile()),
                                                        RequestOptions.DEFAULT);
                                        resource.loadJSONResources(
                                                new ByteArrayInputStream(
                                                        response.getSourceAsBytes()));
                                    } catch (Exception e) {
                                        LOG.error("Can't load config from ES", e);
                                    }
                                }
                            }
                        },
                        0,
                        refreshRate * 1000);
    }

    @Override
    public @Nullable String filter(
            @Nullable URL sourceUrl,
            @Nullable Metadata sourceMetadata,
            @NotNull String urlToFilter) {
        return delegatedURLFilter.filter(sourceUrl, sourceMetadata, urlToFilter);
    }
}
