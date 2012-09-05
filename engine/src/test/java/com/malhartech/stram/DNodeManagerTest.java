/**
 * Copyright (c) 2012-2012 Malhar, Inc.
 * All rights reserved.
 */
package com.malhartech.stram;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import junit.framework.Assert;

import org.apache.hadoop.io.DataInputByteBuffer;
import org.apache.hadoop.io.DataOutputByteBuffer;
import org.junit.Test;

import com.malhartech.dag.DefaultSerDe;
import com.malhartech.dag.Tuple;
import com.malhartech.stram.NodeDeployInfo.NodeInputDeployInfo;
import com.malhartech.stram.NodeDeployInfo.NodeOutputDeployInfo;
import com.malhartech.stram.StreamingNodeUmbilicalProtocol.StreamingContainerContext;
import com.malhartech.stram.TopologyDeployer.PTNode;
import com.malhartech.stram.conf.NewTopologyBuilder;
import com.malhartech.stram.conf.NewTopologyBuilder.StreamBuilder;
import com.malhartech.stram.conf.Topology;
import com.malhartech.stram.conf.Topology.NodeDecl;

public class DNodeManagerTest {

  @Test
  public void testNodeDeployInfoSerialization() throws Exception {
    NodeDeployInfo ndi = new NodeDeployInfo();
    ndi.declaredId = "node1";
    ndi.id ="1";

    NodeDeployInfo.NodeInputDeployInfo input = new NodeDeployInfo.NodeInputDeployInfo();
    input.declaredStreamId = "streamToNode";
    input.portName = "inputPortNameOnNode";
    input.sourceNodeId = "sourceNodeId";

    ndi.inputs = new ArrayList<NodeDeployInfo.NodeInputDeployInfo>();
    ndi.inputs.add(input);

    NodeDeployInfo.NodeOutputDeployInfo output = new NodeDeployInfo.NodeOutputDeployInfo();
    output.declaredStreamId = "streamFromNode";
    output.portName = "outputPortNameOnNode";

    ndi.outputs = new ArrayList<NodeDeployInfo.NodeOutputDeployInfo>();
    ndi.outputs.add(output);

    StreamingContainerContext scc = new StreamingContainerContext();
    scc.nodeList = Collections.singletonList(ndi);

    DataOutputByteBuffer out = new DataOutputByteBuffer();
    scc.write(out);

    DataInputByteBuffer in = new DataInputByteBuffer();
    in.reset(out.getData());

    StreamingContainerContext clone = new StreamingContainerContext();
    clone.readFields(in);

    Assert.assertNotNull(clone.nodeList);
    Assert.assertEquals(1, clone.nodeList.size());
    Assert.assertEquals("node1", clone.nodeList.get(0).declaredId);

    String nodeToString = ndi.toString();
    Assert.assertTrue(nodeToString.contains(input.portName));
    Assert.assertTrue(nodeToString.contains(output.portName));
  }

  @Test
  public void testAssignContainer() {

    NewTopologyBuilder b = new NewTopologyBuilder();

    NodeDecl node1 = b.addNode("node1", GenericTestNode.class);
    NodeDecl node2 = b.addNode("node2", GenericTestNode.class);
    NodeDecl node3 = b.addNode("node3", GenericTestNode.class);

    b.addStream("n1n2")
      .setSource(node1.getOutput(GenericTestNode.OUTPUT1))
      .addSink(node2.getInput(GenericTestNode.INPUT1));

    b.addStream("n2n3")
      .setInline(true)
      .setSource(node2.getOutput(GenericTestNode.OUTPUT1))
      .addSink(node3.getInput(GenericTestNode.INPUT1));

    Topology tplg = b.getTopology();
    tplg.setMaxContainerCount(2);

    Assert.assertEquals("number nodes", 3, tplg.getAllNodes().size());
    Assert.assertEquals("number root nodes", 1, tplg.getRootNodes().size());

    DNodeManager dnm = new DNodeManager(tplg);
    Assert.assertEquals("number required containers", 2, dnm.getNumRequiredContainers());

    String container1Id = "container1";
    String container2Id = "container2";

    // node1 needs to be deployed first, regardless in which order they were given
    StreamingContainerContext c1 = dnm.assignContainerForTest(container1Id, InetSocketAddress.createUnresolved(container1Id+"Host", 9001));
    Assert.assertEquals("number nodes assigned to c1", 1, c1.nodeList.size());
    NodeDeployInfo node1DI = getNodeDeployInfo(c1, node1);
    Assert.assertNotNull(node1.getId() + " assigned to " + container1Id, node1DI);
    Assert.assertEquals("inputs " + node1DI.declaredId, 0, node1DI.inputs.size());
    Assert.assertEquals("outputs " + node1DI.declaredId, 1, node1DI.outputs.size());
    Assert.assertNotNull("serializedNode " + node1DI.declaredId, node1DI.serializedNode);

    NodeOutputDeployInfo c1n1n2 = node1DI.outputs.get(0);
    Assert.assertNotNull("stream connection for container1", c1n1n2);
    Assert.assertEquals("stream connection for container1", "n1n2", c1n1n2.declaredStreamId);
    Assert.assertEquals("stream connects to upstream host", container1Id + "Host", c1n1n2.bufferServerHost);
    Assert.assertEquals("stream connects to upstream port", 9001, c1n1n2.bufferServerPort);
    Assert.assertFalse("stream inline", c1n1n2.isInline());

    StreamingContainerContext c2 = dnm.assignContainerForTest(container2Id, InetSocketAddress.createUnresolved(container2Id+"Host", 9002));
    Assert.assertEquals("number nodes assigned to container", 2, c2.nodeList.size());
    NodeDeployInfo node2DI = getNodeDeployInfo(c2, node2);
    NodeDeployInfo node3DI = getNodeDeployInfo(c2, node3);
    Assert.assertNotNull(node2.getId() + " assigned to " + container2Id, node2DI);
    Assert.assertNotNull(node3.getId() + " assigned to " + container2Id, node3DI);

    // buffer server input node2 from node1
    NodeInputDeployInfo c2n1n2 = getInputDeployInfo(node2DI, "n1n2");
    Assert.assertNotNull("stream connection for container2", c2n1n2);
    Assert.assertEquals("stream connects to upstream host", container1Id + "Host", c2n1n2.bufferServerHost);
    Assert.assertEquals("stream connects to upstream port", 9001, c2n1n2.bufferServerPort);
    Assert.assertEquals("portName " + c2n1n2, GenericTestNode.INPUT1, c2n1n2.portName);
    Assert.assertNull("partitionKeys " + c2n1n2, c2n1n2.partitionKeys);
    Assert.assertEquals("sourceNodeId " + c2n1n2, node1DI.id, c2n1n2.sourceNodeId);
    Assert.assertEquals("sourcePortName " + c2n1n2, GenericTestNode.OUTPUT1, c2n1n2.sourcePortName);

    // inline input node3 from node2
    NodeInputDeployInfo c2n3In = getInputDeployInfo(node3DI, "n2n3");
    Assert.assertNotNull("input " + c2n3In, node2DI);
    Assert.assertEquals("portName " + c2n3In, GenericTestNode.INPUT1, c2n3In.portName);
    Assert.assertNotNull("stream connection for container2", c2n3In);
    Assert.assertNull("bufferServerHost " + c2n3In, c2n3In.bufferServerHost);
    Assert.assertEquals("bufferServerPort " + c2n3In, 0, c2n3In.bufferServerPort);
    Assert.assertNull("partitionKeys " + c2n3In, c2n3In.partitionKeys);
    Assert.assertEquals("sourceNodeId " + c2n3In, node2DI.id, c2n3In.sourceNodeId);
    Assert.assertEquals("sourcePortName " + c2n3In, GenericTestNode.OUTPUT1, c2n3In.sourcePortName);
  }

  @Test
  public void testStaticPartitioning() {
    NewTopologyBuilder b = new NewTopologyBuilder();

    NodeDecl node1 = b.addNode("node1", GenericTestNode.class);
    NodeDecl node2 = b.addNode("node2", GenericTestNode.class);
    NodeDecl mergeNode = b.addNode("mergeNode", GenericTestNode.class);

    StreamBuilder n1n2 = b.addStream("n1n2")
      .setSerDeClass(TestStaticPartitioningSerDe.class)
      .setSource(node1.getOutput(GenericTestNode.OUTPUT1))
      .addSink(node2.getInput(GenericTestNode.INPUT1));

    StreamBuilder mergeStream = b.addStream("mergeStream")
        .setSource(node2.getOutput(GenericTestNode.OUTPUT1))
        .addSink(mergeNode.getInput(GenericTestNode.INPUT1));

    Topology tplg = b.getTopology();
    tplg.setMaxContainerCount(5);

    DNodeManager dnm = new DNodeManager(tplg);
    Assert.assertEquals("number required containers", 5, dnm.getNumRequiredContainers());

    String container1Id = "container1";
    StreamingContainerContext c1 = dnm.assignContainerForTest(container1Id, InetSocketAddress.createUnresolved(container1Id+"Host", 9001));
    Assert.assertEquals("number nodes assigned to container", 1, c1.nodeList.size());
    Assert.assertTrue(node2.getId() + " assigned to " + container1Id, containsNodeContext(c1, node1));

    for (int i=0; i<TestStaticPartitioningSerDe.partitions.length; i++) {
      String containerId = "container"+(i+1);
      StreamingContainerContext cc = dnm.assignContainerForTest(containerId, InetSocketAddress.createUnresolved(containerId+"Host", 9001));
      Assert.assertEquals("number nodes assigned to container", 1, cc.nodeList.size());
      Assert.assertTrue(node2.getId() + " assigned to " + containerId, containsNodeContext(cc, node2));

      // n1n2 in, mergeStream out
      NodeDeployInfo ndi = cc.nodeList.get(0);
      Assert.assertEquals("inputs " + ndi, 1, ndi.inputs.size());
      Assert.assertEquals("outputs " + ndi, 1, ndi.outputs.size());

      NodeInputDeployInfo nidi = ndi.inputs.get(0);
      Assert.assertEquals("stream " + nidi, n1n2.getDecl().getId(), nidi.declaredStreamId);
      Assert.assertTrue("partition for " + containerId, Arrays.equals(TestStaticPartitioningSerDe.partitions[i], nidi.partitionKeys.get(0)));
      Assert.assertEquals("serde " + nidi, TestStaticPartitioningSerDe.class.getName(), nidi.serDeClassName);
    }

    // mergeNode container
    String mergeContainerId = "mergeNodeContainer";
    StreamingContainerContext cmerge = dnm.assignContainerForTest(mergeContainerId, InetSocketAddress.createUnresolved(mergeContainerId+"Host", 9001));
    Assert.assertEquals("number nodes assigned to " + mergeContainerId, 1, cmerge.nodeList.size());

    NodeDeployInfo mergeNodeDI = getNodeDeployInfo(cmerge,  mergeNode);
    Assert.assertNotNull(mergeNode.getId() + " assigned to " + container1Id, mergeNodeDI);
    Assert.assertEquals("inputs " + mergeNodeDI, 3, mergeNodeDI.inputs.size());
    List<String> sourceNodeIds = new ArrayList<String>();
    for (NodeInputDeployInfo nidi : mergeNodeDI.inputs) {
      Assert.assertEquals("streamName " + nidi, mergeStream.getDecl().getId(), nidi.declaredStreamId);
      Assert.assertEquals("streamName " + nidi, GenericTestNode.INPUT1, nidi.portName);
      Assert.assertNotNull("sourceNodeId " + nidi, nidi.sourceNodeId);
      sourceNodeIds.add(nidi.sourceNodeId);
    }

    for (PTNode node : dnm.getTopologyDeployer().getNodes(tplg.getNode(node2.getId()))) {
      Assert.assertTrue(sourceNodeIds + " contains " + node.id, sourceNodeIds.contains(node.id));
    }
    Assert.assertEquals("outputs " + mergeNodeDI, 0, mergeNodeDI.outputs.size());
  }

  /**
   * Verify buffer server address when downstream node is assigned before upstream.
   */
  @Test
  public void testBufferServerAssignment() {
    NewTopologyBuilder b = new NewTopologyBuilder();

    NodeDecl node1 = b.addNode("node1", GenericTestNode.class);
    NodeDecl node2 = b.addNode("node2", GenericTestNode.class);
    NodeDecl node3 = b.addNode("node3", GenericTestNode.class);

    b.addStream("n1n2")
      .setSerDeClass(TestStaticPartitioningSerDe.class)
      .setSource(node1.getOutput(GenericTestNode.OUTPUT1))
      .addSink(node2.getInput(GenericTestNode.INPUT1));

    b.addStream("n2n3")
        .setSource(node2.getOutput(GenericTestNode.OUTPUT1))
        .addSink(node3.getInput(GenericTestNode.INPUT1));

    Topology tplg = b.getTopology();
    tplg.setMaxContainerCount(2);

    // node1 and node3 are assigned, node2 unassigned
    DNodeManager dnmgr = new DNodeManager(tplg);
    dnmgr.assignContainerForTest("container1", InetSocketAddress.createUnresolved("localhost", 9001));

  }

  public static class TestStaticPartitioningSerDe extends DefaultSerDe {

    public final static byte[][] partitions = new byte[][]{
        {'1'}, {'2'}, {'3'}
    };

    @Override
    public byte[][] getPartitions() {
      return partitions;
    }

    @Override
    public byte[] getPartition(Object o)
    {
      if (o instanceof Tuple) {
        throw new UnsupportedOperationException("should not be called with control tuple");
      }
      return partitions[0];
    }

  }

  private boolean containsNodeContext(StreamingContainerContext scc, NodeDecl nodeConf) {
    return getNodeDeployInfo(scc, nodeConf) != null;
  }

  private static NodeDeployInfo getNodeDeployInfo(StreamingContainerContext scc, NodeDecl nodeConf) {
    for (NodeDeployInfo ndi : scc.nodeList) {
      if (nodeConf.getId().equals(ndi.declaredId)) {
        return ndi;
      }
    }
    return null;
  }

  private static NodeInputDeployInfo getInputDeployInfo(NodeDeployInfo ndi, String streamId) {
    for (NodeInputDeployInfo in : ndi.inputs) {
      if (streamId.equals(in.declaredStreamId)) {
        return in;
      }
    }
    return null;
  }

}
