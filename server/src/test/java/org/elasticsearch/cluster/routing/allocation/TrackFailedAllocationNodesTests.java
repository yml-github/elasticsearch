/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.command.AllocationCommands;
import org.elasticsearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider;
import org.elasticsearch.common.settings.Settings;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;

public class TrackFailedAllocationNodesTests extends ESAllocationTestCase {

    public void testTrackFailedNodes() {
        int maxRetries = MaxRetryAllocationDecider.SETTING_ALLOCATION_MAX_RETRY.get(Settings.EMPTY);
        AllocationService allocationService = createAllocationService();
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("idx").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0))
            .build();
        DiscoveryNodes.Builder discoNodes = DiscoveryNodes.builder();
        for (int i = 0; i < 5; i++) {
            discoNodes.add(newNode("node-" + i));
        }
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .nodes(discoNodes)
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsNew(metadata.index("idx")).build())
            .build();
        clusterState = allocationService.reroute(clusterState, "reroute");
        Set<String> failedNodeIds = new HashSet<>();

        // track the failed nodes if shard is not started
        for (int i = 0; i < maxRetries; i++) {
            failedNodeIds.add(clusterState.routingTable().index("idx").shard(0).shards().get(0).currentNodeId());
            clusterState = allocationService.applyFailedShard(
                clusterState,
                clusterState.routingTable().index("idx").shard(0).shards().get(0),
                randomBoolean()
            );
            assertThat(
                clusterState.routingTable().index("idx").shard(0).shards().get(0).unassignedInfo().getFailedNodeIds(),
                equalTo(failedNodeIds)
            );
        }

        // reroute with retryFailed=true should discard the failedNodes
        assertThat(clusterState.routingTable().index("idx").shard(0).shards().get(0).state(), equalTo(ShardRoutingState.UNASSIGNED));
        clusterState = allocationService.reroute(clusterState, new AllocationCommands(), false, true).getClusterState();
        assertThat(clusterState.routingTable().index("idx").shard(0).shards().get(0).unassignedInfo().getFailedNodeIds(), empty());

        // do not track the failed nodes while shard is started
        clusterState = startInitializingShardsAndReroute(allocationService, clusterState);
        assertThat(clusterState.routingTable().index("idx").shard(0).shards().get(0).state(), equalTo(ShardRoutingState.STARTED));
        clusterState = allocationService.applyFailedShard(
            clusterState,
            clusterState.routingTable().index("idx").shard(0).shards().get(0),
            false
        );
        assertThat(clusterState.routingTable().index("idx").shard(0).shards().get(0).unassignedInfo().getFailedNodeIds(), empty());
    }
}
