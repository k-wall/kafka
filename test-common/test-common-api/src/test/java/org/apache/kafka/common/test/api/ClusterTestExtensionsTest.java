/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.test.api;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.DescribeLogDirsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.GroupProtocol;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.server.common.MetadataVersion;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.apache.kafka.clients.consumer.GroupProtocol.CLASSIC;
import static org.apache.kafka.clients.consumer.GroupProtocol.CONSUMER;
import static org.apache.kafka.coordinator.group.GroupCoordinatorConfig.GROUP_COORDINATOR_REBALANCE_PROTOCOLS_CONFIG;
import static org.apache.kafka.coordinator.group.GroupCoordinatorConfig.NEW_GROUP_COORDINATOR_ENABLE_CONFIG;

@ClusterTestDefaults(types = {Type.KRAFT}, serverProperties = {
    @ClusterConfigProperty(key = "default.key", value = "default.value"),
    @ClusterConfigProperty(id = 0, key = "queued.max.requests", value = "100"),
})  // Set defaults for a few params in @ClusterTest(s)
@ExtendWith(ClusterTestExtensions.class)
public class ClusterTestExtensionsTest {

    private final ClusterInstance clusterInstance;

    ClusterTestExtensionsTest(ClusterInstance clusterInstance) {     // Constructor injections
        this.clusterInstance = clusterInstance;
    }

    // Static methods can generate cluster configurations
    static List<ClusterConfig> generate1() {
        Map<String, String> serverProperties = new HashMap<>();
        serverProperties.put("foo", "bar");
        return Collections.singletonList(ClusterConfig.defaultBuilder()
                .setTypes(Collections.singleton(Type.KRAFT))
                .setServerProperties(serverProperties)
                .setTags(Collections.singletonList("Generated Test"))
                .build());
    }

    // With no params, configuration comes from the annotation defaults as well as @ClusterTestDefaults (if present)
    @ClusterTest
    public void testClusterTest(ClusterInstance clusterInstance) {
        Assertions.assertSame(this.clusterInstance, clusterInstance, "Injected objects should be the same");
        Assertions.assertEquals(Type.KRAFT, clusterInstance.type()); // From the class level default
        Assertions.assertEquals("default.value", clusterInstance.config().serverProperties().get("default.key"));
    }

    // generate1 is a template method which generates any number of cluster configs
    @ClusterTemplate("generate1")
    public void testClusterTemplate() {
        Assertions.assertEquals(Type.KRAFT, clusterInstance.type(),
            "generate1 provided a KRAFT cluster, so we should see that here");
        Assertions.assertEquals("bar", clusterInstance.config().serverProperties().get("foo"));
        Assertions.assertEquals(Collections.singletonList("Generated Test"), clusterInstance.config().tags());
    }

    // Multiple @ClusterTest can be used with @ClusterTests
    @ClusterTests({
        @ClusterTest(types = {Type.KRAFT}, serverProperties = {
            @ClusterConfigProperty(key = "foo", value = "baz"),
            @ClusterConfigProperty(key = "spam", value = "eggz"),
            @ClusterConfigProperty(key = "default.key", value = "overwrite.value"),
            @ClusterConfigProperty(id = 0, key = "queued.max.requests", value = "200"),
            @ClusterConfigProperty(id = 3000, key = "queued.max.requests", value = "300"),
            @ClusterConfigProperty(key = "spam", value = "eggs"),
            @ClusterConfigProperty(key = "default.key", value = "overwrite.value")
        }, tags = {
                "default.display.key1", "default.display.key2"
        }),
        @ClusterTest(types = {Type.CO_KRAFT}, serverProperties = {
            @ClusterConfigProperty(key = "foo", value = "baz"),
            @ClusterConfigProperty(key = "spam", value = "eggz"),
            @ClusterConfigProperty(key = "default.key", value = "overwrite.value"),
            @ClusterConfigProperty(id = 0, key = "queued.max.requests", value = "200"),
            @ClusterConfigProperty(key = "spam", value = "eggs"),
            @ClusterConfigProperty(key = "default.key", value = "overwrite.value")
        }, tags = {
                "default.display.key1", "default.display.key2"
        })
    })
    public void testClusterTests() throws ExecutionException, InterruptedException {
        Assertions.assertEquals("baz", clusterInstance.config().serverProperties().get("foo"));
        Assertions.assertEquals("eggs", clusterInstance.config().serverProperties().get("spam"));
        Assertions.assertEquals("overwrite.value", clusterInstance.config().serverProperties().get("default.key"));
        Assertions.assertEquals(Arrays.asList("default.display.key1", "default.display.key2"), clusterInstance.config().tags());

        // assert broker server 0 contains property queued.max.requests 200 from ClusterTest which overrides
        // the value 100 in server property in ClusterTestDefaults
        try (Admin admin = clusterInstance.createAdminClient()) {
            ConfigResource configResource = new ConfigResource(ConfigResource.Type.BROKER, "0");
            Map<ConfigResource, Config> configs = admin.describeConfigs(Collections.singletonList(configResource)).all().get();
            Assertions.assertEquals(1, configs.size());
            Assertions.assertEquals("200", configs.get(configResource).get("queued.max.requests").value());
        }
        // In KRaft cluster non-combined mode, assert the controller server 3000 contains the property queued.max.requests 300
        if (clusterInstance.type() == Type.KRAFT) {
            try (Admin admin = Admin.create(Collections.singletonMap(
                    AdminClientConfig.BOOTSTRAP_CONTROLLERS_CONFIG, clusterInstance.bootstrapControllers()))) {
                ConfigResource configResource = new ConfigResource(ConfigResource.Type.BROKER, "3000");
                Map<ConfigResource, Config> configs = admin.describeConfigs(Collections.singletonList(configResource)).all().get();
                Assertions.assertEquals(1, configs.size());
                Assertions.assertEquals("300", configs.get(configResource).get("queued.max.requests").value());
            }
        }
    }

    @ClusterTests({
        @ClusterTest(types = {Type.KRAFT, Type.CO_KRAFT}),
        @ClusterTest(types = {Type.KRAFT, Type.CO_KRAFT}, disksPerBroker = 2),
    })
    public void testClusterTestWithDisksPerBroker() throws ExecutionException, InterruptedException {
        Admin admin = clusterInstance.createAdminClient();

        DescribeLogDirsResult result = admin.describeLogDirs(clusterInstance.brokerIds());
        result.allDescriptions().get().forEach((brokerId, logDirDescriptionMap) -> {
            Assertions.assertEquals(clusterInstance.config().numDisksPerBroker(), logDirDescriptionMap.size());
        });
    }

    @ClusterTest(autoStart = AutoStart.NO)
    public void testNoAutoStart() {
        Assertions.assertThrows(RuntimeException.class, clusterInstance::anyBrokerSocketServer);
        clusterInstance.start();
        Assertions.assertNotNull(clusterInstance.anyBrokerSocketServer());
    }

    @ClusterTest
    public void testDefaults(ClusterInstance clusterInstance) {
        Assertions.assertEquals(MetadataVersion.latestTesting(), clusterInstance.config().metadataVersion());
    }

    @ClusterTest(types = {Type.KRAFT, Type.CO_KRAFT})
    public void testSupportedNewGroupProtocols(ClusterInstance clusterInstance) {
        Set<GroupProtocol> supportedGroupProtocols = new HashSet<>();
        supportedGroupProtocols.add(CLASSIC);
        supportedGroupProtocols.add(CONSUMER);
        Assertions.assertEquals(supportedGroupProtocols, clusterInstance.supportedGroupProtocols());
    }

    @ClusterTests({
        @ClusterTest(types = {Type.KRAFT, Type.CO_KRAFT}, serverProperties = {
            @ClusterConfigProperty(key = GROUP_COORDINATOR_REBALANCE_PROTOCOLS_CONFIG, value = "classic"),
        }),
        @ClusterTest(types = {Type.KRAFT, Type.CO_KRAFT}, serverProperties = {
            @ClusterConfigProperty(key = NEW_GROUP_COORDINATOR_ENABLE_CONFIG, value = "false"),
        })
    })
    public void testNotSupportedNewGroupProtocols(ClusterInstance clusterInstance) {
        Assertions.assertEquals(Collections.singleton(CLASSIC), clusterInstance.supportedGroupProtocols());
    }



    @ClusterTest(types = {Type.CO_KRAFT, Type.KRAFT}, brokers = 3)
    public void testCreateTopic(ClusterInstance clusterInstance) throws Exception {
        String topicName = "test";
        int numPartition = 3;
        short numReplicas = 3;
        clusterInstance.createTopic(topicName, numPartition, numReplicas);

        try (Admin admin = clusterInstance.createAdminClient()) {
            Assertions.assertTrue(admin.listTopics().listings().get().stream().anyMatch(s -> s.name().equals(topicName)));
            List<TopicPartitionInfo> partitions = admin.describeTopics(Collections.singleton(topicName)).allTopicNames().get()
                    .get(topicName).partitions();
            Assertions.assertEquals(numPartition, partitions.size());
            Assertions.assertTrue(partitions.stream().allMatch(partition -> partition.replicas().size() == numReplicas));
        }
    }

    @ClusterTest(types = {Type.CO_KRAFT, Type.KRAFT}, brokers = 4)
    public void testShutdownAndSyncMetadata(ClusterInstance clusterInstance) throws Exception {
        String topicName = "test";
        int numPartition = 3;
        short numReplicas = 3;
        clusterInstance.createTopic(topicName, numPartition, numReplicas);
        clusterInstance.shutdownBroker(0);
        clusterInstance.waitForTopic(topicName, numPartition);
    }

    @ClusterTest(types = {Type.CO_KRAFT, Type.KRAFT}, brokers = 4)
    public void testClusterAliveBrokers(ClusterInstance clusterInstance) throws Exception {
        clusterInstance.waitForReadyBrokers();

        // Remove broker id 0
        clusterInstance.shutdownBroker(0);
        Assertions.assertFalse(clusterInstance.aliveBrokers().containsKey(0));
        Assertions.assertTrue(clusterInstance.brokers().containsKey(0));

        // add broker id 0 back
        clusterInstance.startBroker(0);
        Assertions.assertTrue(clusterInstance.aliveBrokers().containsKey(0));
        Assertions.assertTrue(clusterInstance.brokers().containsKey(0));
    }


    @ClusterTest(
        types = {Type.CO_KRAFT, Type.KRAFT},
        brokers = 4,
        serverProperties = {
            @ClusterConfigProperty(key = "log.initial.task.delay.ms", value = "100"),
            @ClusterConfigProperty(key = "log.segment.delete.delay.ms", value = "1000")
        }
    )
    public void testVerifyTopicDeletion(ClusterInstance clusterInstance) throws Exception {
        try (Admin admin = clusterInstance.createAdminClient()) {
            String testTopic = "testTopic";
            admin.createTopics(Collections.singletonList(new NewTopic(testTopic, 1, (short) 1)));
            clusterInstance.waitForTopic(testTopic, 1);
            admin.deleteTopics(Collections.singletonList(testTopic));
            clusterInstance.waitTopicDeletion(testTopic);
            Assertions.assertTrue(admin.listTopics().listings().get().stream().noneMatch(
                    topic -> topic.name().equals(testTopic)
            ));
        }
    }
}
