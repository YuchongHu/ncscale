/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.datanode;

import static org.apache.hadoop.hdfs.server.datanode.DataNode.DN_CLIENTTRACE_FORMAT;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.security.MessageDigest;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataNodeReadProfilingData;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.SocketCache;
import org.apache.hadoop.hdfs.protocol.AppendBlockHeader;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockChecksumHeader;
import org.apache.hadoop.hdfs.protocol.CopyBlockHeader;
import org.apache.hadoop.hdfs.protocol.TargetHeader;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithMetaInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlocksWithMetaInfo;
import org.apache.hadoop.hdfs.protocol.ReadBlockHeader;
import org.apache.hadoop.hdfs.protocol.ReadDRCBlockHeader;
import org.apache.hadoop.hdfs.protocol.ReadGroupHeader;
import org.apache.hadoop.hdfs.protocol.ReadMetadataHeader;
import org.apache.hadoop.hdfs.protocol.ReplaceBlockHeader;
import org.apache.hadoop.hdfs.protocol.ScaleRSDeltaTransferHeader;
import org.apache.hadoop.hdfs.protocol.ScaleRSDownTransferHeader;
import org.apache.hadoop.hdfs.protocol.ScalingDeltaTransferHeader;
import org.apache.hadoop.hdfs.protocol.ScalingDownTransferHeader;
import org.apache.hadoop.hdfs.protocol.ScalingDownTransferHeader2;
import org.apache.hadoop.hdfs.protocol.ScalingHeader;
import org.apache.hadoop.hdfs.protocol.VersionAndOpcode;
import org.apache.hadoop.hdfs.protocol.VersionedLocatedBlocks;
import org.apache.hadoop.hdfs.protocol.WriteBlockHeader;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.MD5Hash;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WriteOptions;
import org.apache.hadoop.io.nativeio.NativeIO;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.raid.Codec;
import org.apache.hadoop.raid.RaidCodec;
import org.apache.hadoop.raid.DRCDecoder;
import org.apache.hadoop.raid.RaidUtils;
import org.apache.hadoop.raid.ScalingAlgorithmic;
import org.apache.hadoop.raid.ScaleRS;
import org.apache.hadoop.raid.ScaleRSDown;
import org.apache.hadoop.raid.ScalingMBR;
import org.apache.hadoop.raid.SimpleScalingDown1;
import org.apache.hadoop.raid.SimpleScalingDown2;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.DataTransferThrottler;
import org.apache.hadoop.util.InjectionHandler;
import org.apache.hadoop.util.StringUtils;

/**
 * Thread for processing incoming/outgoing data stream.
 */
class DataXceiver implements Runnable, FSConstants {
  public static final Log LOG = DataNode.LOG;
  static final Log ClientTraceLog = DataNode.ClientTraceLog;
  
  Socket s;
  String remoteAddress = null; // address of remote side
  String localAddress = null;  // local address of this daemon
  DataNode datanode;
  DataXceiverServer dataXceiverServer;
  
  private int socketKeepaliveTimeout;
  private boolean reuseConnection = false;
  
  public DataXceiver(Socket s, DataNode datanode, 
      DataXceiverServer dataXceiverServer) {
    this.s = s;
    this.datanode = datanode;
    this.dataXceiverServer = dataXceiverServer;

    if (ClientTraceLog.isInfoEnabled()) {
      getAddresses();
      ClientTraceLog.info("Accepted DataXceiver connection: src "
          + remoteAddress + " dest " + localAddress + " XceiverCount: "
          + datanode.getXceiverCount());
    }
    
    dataXceiverServer.childSockets.put(s, s);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Number of active connections is: " + datanode.getXceiverCount());
    }

    datanode.myMetrics.xceiverCount.inc();
    
    socketKeepaliveTimeout = datanode.getConf().getInt(
        DataNode.DFS_DATANODE_SOCKET_REUSE_KEEPALIVE_KEY, 
        DataNode.DFS_DATANODE_SOCKET_REUSE_KEEPALIVE_DEFAULT);
  }
  
  private void getAddresses() {
    if (s != null) {
      remoteAddress = s.getRemoteSocketAddress().toString();
      localAddress = s.getLocalSocketAddress().toString();    
    }
  }

  /**
   * Update the thread name to contain the current status.
   * Use this only after this receiver has started on its thread, i.e.,
   * outside the constructor.
   */
  private void updateCurrentThreadName(String status) {
    StringBuilder sb = new StringBuilder();
    sb.append("DataXceiver for client ");
    InetAddress ia;
    if (s != null && (ia = s.getInetAddress()) != null) {
      sb.append(ia.toString());
    } else {
      sb.append("unknown");
    }
      
    if (status != null) {
      sb.append(" [").append(status).append("]");
    }
    Thread.currentThread().setName(sb.toString());
  }

  /**
   * Read/write data from/to the DataXceiveServer.
   */
  public void run() {
    DataInputStream in=null; 
    byte op = -1;
    int opsProcessed = 0;
    try {
      s.setTcpNoDelay(true);
      s.setSoTimeout(datanode.socketTimeout*5);

      in = new DataInputStream(
          new BufferedInputStream(NetUtils.getInputStream(s), 
                                  SMALL_BUFFER_SIZE));
      int stdTimeout = s.getSoTimeout();
      
      // We process requests in a loop, and stay around for a short timeout.
      // This optimistic behavior allows the other end to reuse connections.
      // Setting keepalive timeout to 0 to disable this behavior.
      
      do {
        long startNanoTime = System.nanoTime();
        long startTime = DataNode.now(); 
        
        VersionAndOpcode versionAndOpcode = new VersionAndOpcode();
        
        try {
          if (opsProcessed != 0) {
            assert socketKeepaliveTimeout > 0;
            s.setSoTimeout(socketKeepaliveTimeout);
          }
          versionAndOpcode.readFields(in);
          op = versionAndOpcode.getOpCode();
        } catch (InterruptedIOException ignored) {
          // Time out while we wait for client rpc
          break;
        } catch (IOException err) {
          // Since we optimistically expect the next op, it's quite normal to get EOF here.
          if (opsProcessed > 0 && 
              (err instanceof EOFException || err instanceof ClosedChannelException)) {
            if (LOG.isDebugEnabled()) {
              LOG.debug("Cached " + s.toString() + " closing after " + opsProcessed + " ops");
            }
          } else {
            throw err;
          }
          break;
        }
        
        // restore normal timeout.
        if (opsProcessed != 0) {
          s.setSoTimeout(stdTimeout);
        }

        boolean local = s.getInetAddress().equals(s.getLocalAddress());
        updateCurrentThreadName("waiting for operation");

        // Make sure the xciver count is not exceeded
        int curXceiverCount = datanode.getXceiverCount();
        if (curXceiverCount > dataXceiverServer.maxXceiverCount) {
          datanode.myMetrics.xceiverCountExceeded.inc();
          throw new IOException("xceiverCount " + curXceiverCount
              + " exceeds the limit of concurrent xcievers "
              + dataXceiverServer.maxXceiverCount);
        }
        
        switch ( op ) {
        case DataTransferProtocol.OP_READ_BLOCK:
          readBlock( in, versionAndOpcode , startNanoTime);
          datanode.myMetrics.readBlockOp.inc(DataNode.now() - startTime);
          if (local)
            datanode.myMetrics.readsFromLocalClient.inc();
          else
            datanode.myMetrics.readsFromRemoteClient.inc();
          break;
        case DataTransferProtocol.OP_READ_BLOCK_ACCELERATOR:
          readBlockAccelerator(in, versionAndOpcode);
          datanode.myMetrics.readBlockOp.inc(DataNode.now() - startTime);
          if (local)
            datanode.myMetrics.readsFromLocalClient.inc();
          else
            datanode.myMetrics.readsFromRemoteClient.inc();
          break;
        case DataTransferProtocol.OP_WRITE_BLOCK:
          writeBlock( in, versionAndOpcode );
          datanode.myMetrics.writeBlockOp.inc(DataNode.now() - startTime);
          if (local)
            datanode.myMetrics.writesFromLocalClient.inc();
          else
            datanode.myMetrics.writesFromRemoteClient.inc();
          break;
        case DataTransferProtocol.OP_READ_METADATA:
          readMetadata(in, versionAndOpcode);
          datanode.myMetrics.readMetadataOp.inc(DataNode.now() - startTime);
          break;
        case DataTransferProtocol.OP_REPLACE_BLOCK: // for balancing purpose; send to a destination
          replaceBlock(in, versionAndOpcode);
          datanode.myMetrics.replaceBlockOp.inc(DataNode.now() - startTime);
          break;
        case DataTransferProtocol.OP_COPY_BLOCK:
          // for balancing purpose; send to a proxy source
          copyBlock(in, versionAndOpcode);
          datanode.myMetrics.copyBlockOp.inc(DataNode.now() - startTime);
          break;
        case DataTransferProtocol.OP_BLOCK_CHECKSUM: //get the checksum of a block
          getBlockChecksum(in, versionAndOpcode);
          datanode.myMetrics.blockChecksumOp.inc(DataNode.now() - startTime);
          break;
        case DataTransferProtocol.OP_BLOCK_CRC: //get the checksum of a block
          getBlockCrc(in, versionAndOpcode);
          datanode.myMetrics.blockChecksumOp.inc(DataNode.now() - startTime);
          break;
        case DataTransferProtocol.OP_APPEND_BLOCK:
          appendBlock(in, versionAndOpcode);
          datanode.myMetrics.appendBlockOp.inc(DataNode.now() - startTime);
          if (local)
            datanode.myMetrics.writesFromLocalClient.inc();
          else
            datanode.myMetrics.writesFromRemoteClient.inc();
          break;
        case DataTransferProtocol.OP_DRC_TARGET_LEVEL:
          drcfinal(in,versionAndOpcode);
          datanode.myMetrics.readsFromRemoteClient.inc();
          break;
        case DataTransferProtocol.OP_DRC_RACK_LEVEL:
          drcrack(in,versionAndOpcode);
          datanode.myMetrics.readsFromRemoteClient.inc();
          break;
        case DataTransferProtocol.OP_DRC_NODE_LEVEL:
          drcnode(in,versionAndOpcode,startNanoTime);
          datanode.myMetrics.readsFromRemoteClient.inc();
          break;
        case DataTransferProtocol.OP_SCALING:
            NCScale(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;
        case DataTransferProtocol.OP_DELTA_TRANSFER_IN_SCALING:
            remoteUpdate(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;
        case DataTransferProtocol.OP_SCALERS:
            ScaleRS(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;
        case DataTransferProtocol.OP_MBR_SCALING:
            MBRScale(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;
        case DataTransferProtocol.OP_SCALING_DOWN_1:
            NCScaleDown1(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;    
        case DataTransferProtocol.OP_SCALING_DOWN_2:
            NCScaleDown2(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;  
        case DataTransferProtocol.OP_SCALING_DOWN_1_TRANSFER:
        	NCScaleDown1RemoteUpdate(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;  
        case DataTransferProtocol.OP_SCALING_DOWN_2_TRANSFER:
        	NCScaleDown2ComputeDelta(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;  
        case DataTransferProtocol.OP_SCALERS_DOWN:
        	ScaleRSDown(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;  
        case DataTransferProtocol.OP_SCALERS_DOWN_TRANSFER:
        	ScaleRSDownComputeDelta(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break;  
        case DataTransferProtocol.OP_SCALERS_TRANSFER:
        	remoteUpdateScaleRS(in,versionAndOpcode);
            datanode.myMetrics.readsFromRemoteClient.inc();
            break; 
        default:
          throw new IOException("Unknown opcode " + op + " in data stream");
        }
        
        ++ opsProcessed;
        
        if (versionAndOpcode.getDataTransferVersion() < 
            DataTransferProtocol.READ_REUSE_CONNECTION_VERSION ||
            !reuseConnection) {
          break;
        }
      } while (s.isConnected() && socketKeepaliveTimeout > 0);
    } catch (Throwable t) {
      if (op == DataTransferProtocol.OP_READ_BLOCK && t instanceof SocketTimeoutException) {
        // Ignore SocketTimeoutException for reading.
        // This usually means that the client who's reading data from the DataNode has exited.
        if (ClientTraceLog.isInfoEnabled()) {
          ClientTraceLog.info(datanode.getDatanodeInfo() + ":DataXceiver" + " (IGNORED) "
              + StringUtils.stringifyException(t));
        }
      } else {
        if (op == DataTransferProtocol.OP_READ_BLOCK
            || op == DataTransferProtocol.OP_READ_BLOCK_ACCELERATOR) {
          datanode.myMetrics.blockReadFailures.inc();
        }
        datanode.myMetrics.opFailures.inc();
        LOG.error(datanode.getDatanodeInfo() + ":DataXceiver, at " + 
                  s.toString(),t);
      }
    } finally {
      LOG.debug(datanode.getDatanodeInfo() + ":Number of active connections is: "
                               + datanode.getXceiverCount());
      updateCurrentThreadName("Cleaning up");
      IOUtils.closeStream(in);
      IOUtils.closeSocket(s);
      dataXceiverServer.childSockets.remove(s);
    }
  }

  /** 
   * This is the main scaling operation of ScaleRS-scaleDown. 
   * modify at Nov.20 2018
   */ 
  private void ScaleRSDown(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
    LOG.info("DataXceiver.ScaleRSDown.receive the request success!");
    
    ScalingHeader header = new ScalingHeader(versionAndOpcode, datanode.getConf());
    header.readFields(in);
    
    String[] nodesName = header.getNodesName();
    int namespaceId = header.getNameSpaceId();
    int scalingID = header.getscalingID();
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
    String codeid = header.getCode();
    String jsonStr = header.getJsonStr();
    String localNode = s.getLocalSocketAddress().toString().substring(1);
    int nodeId = header.getidx();
//    System.out.println("nodeID is "+nodeId);
    //System.out.println("scalingID : "+scalingID); 
    //load lib
    try {
      System.loadLibrary("hadoop");
    } catch (Throwable t) {
      DataNode.LOG.info("DataXceiver.ScaleRSDown.loadlibrary with error: " + t);
      DataNode.LOG.info("DataXceiver.ScaleRSDown.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
    }
    
    Codec codec = Codec.getCodec(codeid);
    int drcN = codec.stripeLength + codec.parityLength;
    ScaleRSDown sd = new ScaleRSDown(datanode.getConf(), localNode, dataBlocks, parityBlocks, nodeId, nodesName);
	sd.computeNewParity();	
	sd.transferToRemoteNode();
    
    //write back
    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
    out.flush();
    DataNode.LOG.info("DataXceiver.ScaleRSDown.write success to back stream");
  }
  
  private void ScaleRSDownComputeDelta(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
	  	LOG.info("DataXceiver.ScaleRSDownComputeDelta.receive the request success!");
	    
	  	ScaleRSDownTransferHeader header = new ScaleRSDownTransferHeader(versionAndOpcode, datanode.getConf());
	    header.readFields(in);

	    String[] nodesName = header.getNodesName();
	    int namespaceId = header.getNameSpaceId();
	    int transferID = header.getTransferID();
	    LocatedBlockWithFileName[] transferInfo = header.getTransferInfo();
	    byte[][] transfer = header.getTransfer();
	    String localNode = s.getLocalSocketAddress().toString().substring(1);
	    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
	    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
	    //type=0, first transfer data and remote update; type=1, 2nd transfer delta and update.
	    int transferType = header.getTransferType();
	    //load lib
	    try {
	      System.loadLibrary("hadoop");
	    } catch (Throwable t) {
	      DataNode.LOG.info("DataXceiver.ScaleRSDownComputeDelta.loadlibrary with error: " + t);
	      DataNode.LOG.info("DataXceiver.ScaleRSDownComputeDelta.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
	    }

	    ScaleRSDown sd2 = new ScaleRSDown(datanode.getConf(), localNode, dataBlocks, parityBlocks, transferID, nodesName);
		if(transferType == 0)
			sd2.remoteComputeDelta(transferID, transferInfo, transfer);
		else
			sd2.remoteUpdate(transferID, transferInfo, transfer);
	    //write back
	    OutputStream baseStream = NetUtils.getOutputStream(s, 
	        datanode.socketWriteTimeout);
	    DataOutputStream out = new DataOutputStream(
	    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

	    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
	    out.flush();
	    DataNode.LOG.info("DataXceiver.ScaleRSDownComputeDelta.write success to back stream");
	  } 
  /** 
   * This is the main scaling operation of NCScale-scaleDown for n-k=1. 
   * modify at Oct.18 2018
   */ 
  private void NCScaleDown1(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
    LOG.info("DataXceiver.NCScaleDown1.receive the request success!");
    
    ScalingHeader header = new ScalingHeader(versionAndOpcode, datanode.getConf());
    header.readFields(in);

    String[] nodesName = header.getNodesName();
    int namespaceId = header.getNameSpaceId();
    int scalingID = header.getscalingID();
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
    String codeid = header.getCode();
    String jsonStr = header.getJsonStr();
    String localNode = s.getLocalSocketAddress().toString().substring(1);
    int nodeId = header.getidx();
//    System.out.println("nodeID is "+nodeId);
    //System.out.println("scalingID : "+scalingID); 
    //load lib
    try {
      System.loadLibrary("hadoop");
    } catch (Throwable t) {
      DataNode.LOG.info("DataXceiver.NCScaleDown1.loadlibrary with error: " + t);
      DataNode.LOG.info("DataXceiver.NCScaleDown1.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
    }
    
    Codec codec = Codec.getCodec(codeid);
    int drcN = codec.stripeLength + codec.parityLength;
    SimpleScalingDown1 sd1 = new SimpleScalingDown1(datanode.getConf(), localNode, dataBlocks, parityBlocks, nodeId, nodesName);
    if(nodeId == (drcN-1))//For the last new node
	{ 
	    sd1.computeNewParity();	
	    sd1.transferToRemoteNode();
	}
    else
    {
    	sd1.transferStage2();
    	sd1.computeStage2();
    }
    
    //write back
    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
    out.flush();
    DataNode.LOG.info("DataXceiver.NCScaleDown1.write success to back stream");
  }
  
  private void NCScaleDown1RemoteUpdate(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
	  	LOG.info("DataXceiver.NCScaleDown1RemoteUpdate.receive the request success!");
	    
	  	ScalingDownTransferHeader header = new ScalingDownTransferHeader(versionAndOpcode, datanode.getConf());
	    header.readFields(in);

	    String[] nodesName = header.getNodesName();
	    int namespaceId = header.getNameSpaceId();
	    int transferID = header.getTransferID();
	    LocatedBlockWithFileName[] transferInfo = header.getTransferInfo();
	    byte[][] transfer = header.getTransfer();
	    String localNode = s.getLocalSocketAddress().toString().substring(1);
	    Map<Integer, LocatedBlockWithFileName> dataBlocksInNode= header.getDataBlocksInNode();
	    Map<Integer, LocatedBlockWithFileName> parityBlocksInNode= header.getParityBlocksInNode();
	    String remoteNode = s.getRemoteSocketAddress().toString().substring(1);
//	    System.out.println("receive Remote Update Request from: "+ remoteNode);
	    //load lib
	    try {
	      System.loadLibrary("hadoop");
	    } catch (Throwable t) {
	      DataNode.LOG.info("DataXceiver.NCScaleDown1RemoteUpdate.loadlibrary with error: " + t);
	      DataNode.LOG.info("DataXceiver.NCScaleDown1RemoteUpdate.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
	    }

	    SimpleScalingDown1 sd1 = new SimpleScalingDown1(datanode.getConf(), localNode,transferID, nodesName);
		sd1.remoteUpdate(transferID, transferInfo, transfer, dataBlocksInNode, parityBlocksInNode);
	    //write back
	    OutputStream baseStream = NetUtils.getOutputStream(s, 
	        datanode.socketWriteTimeout);
	    DataOutputStream out = new DataOutputStream(
	    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

	    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
	    out.flush();
	    DataNode.LOG.info("DataXceiver.NCScaleDown1RemoteUpdate.write success to back stream");
	  } 

  /** 
   * This is the main scaling operation of NCScale-scaleDown for n-k>1. 
   * modify at Nov.18 2018
   */ 
  private void NCScaleDown2(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
    LOG.info("DataXceiver.NCScaleDown2.receive the request success!");
    
    ScalingHeader header = new ScalingHeader(versionAndOpcode, datanode.getConf());
    header.readFields(in);

    String[] nodesName = header.getNodesName();
    int namespaceId = header.getNameSpaceId();
    int scalingID = header.getscalingID();
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
    String codeid = header.getCode();
    String jsonStr = header.getJsonStr();
    String localNode = s.getLocalSocketAddress().toString().substring(1);
    int nodeId = header.getidx();
//    System.out.println("nodeID is "+nodeId);
    //System.out.println("scalingID : "+scalingID); 
    //load lib
    try {
      System.loadLibrary("hadoop");
    } catch (Throwable t) {
      DataNode.LOG.info("DataXceiver.NCScaleDown2.loadlibrary with error: " + t);
      DataNode.LOG.info("DataXceiver.NCScaleDown2.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
    }
    
    Codec codec = Codec.getCodec(codeid);
    int drcN = codec.stripeLength + codec.parityLength;
    int drcS = codec.newNode;
    SimpleScalingDown2 sd2 = new SimpleScalingDown2(datanode.getConf(), localNode, dataBlocks, parityBlocks, nodeId, nodesName);
    if(nodeId >= (drcN-drcS))//For the last new node
	{ 
	    sd2.computeNewParity();	
	    sd2.transferToRemoteNode();
	}
    else
    {
    	sd2.computeStage2();
    	sd2.transferStage2();
    }
    
    //write back
    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
    out.flush();
    DataNode.LOG.info("DataXceiver.NCScaleDown2.write success to back stream");
  }
  
  private void NCScaleDown2ComputeDelta(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
	  	LOG.info("DataXceiver.NCScaleDown2ComputeDelta.receive the request success!");
	    
	  	ScalingDownTransferHeader2 header = new ScalingDownTransferHeader2(versionAndOpcode, datanode.getConf());
	    header.readFields(in);

	    String[] nodesName = header.getNodesName();
	    int namespaceId = header.getNameSpaceId();
	    int transferID = header.getTransferID();
	    LocatedBlockWithFileName[] transferInfo = header.getTransferInfo();
	    byte[][] transfer = header.getTransfer();
	    String localNode = s.getLocalSocketAddress().toString().substring(1);
	    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
	    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
	    //type=0, first transfer data and remote update; type=1, 2nd transfer delta and update.
	    int transferType = header.getTransferType();
	    String remoteNode = s.getRemoteSocketAddress().toString().substring(1);
//	    System.out.println("receive Remote Update Request from: "+ remoteNode);
	    //load lib
	    try {
	      System.loadLibrary("hadoop");
	    } catch (Throwable t) {
	      DataNode.LOG.info("DataXceiver.NCScaleDown2ComputeDelta.loadlibrary with error: " + t);
	      DataNode.LOG.info("DataXceiver.NCScaleDown2ComputeDelta.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
	    }

	    SimpleScalingDown2 sd2 = new SimpleScalingDown2(datanode.getConf(), localNode, dataBlocks, parityBlocks, transferID, nodesName);
		if(transferType == 0)
			sd2.remoteComputeDelta(transferID, transferInfo, transfer);
		else
			sd2.remoteUpdate(transferID, transferInfo, transfer);
	    //write back
	    OutputStream baseStream = NetUtils.getOutputStream(s, 
	        datanode.socketWriteTimeout);
	    DataOutputStream out = new DataOutputStream(
	    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

	    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
	    out.flush();
	    DataNode.LOG.info("DataXceiver.NCScaleDown2ComputeDelta.write success to back stream");
	  } 
  
  /** 
   * This is the main scaling operation of NCScale. 
   * 1. Compute the delta parity blocks
   * 2. Send delta blocks to corresponding nodes.
   * 3. Send data blocks to new nodes.
   * 4. Delete all obsolete blocks.
   * modify at Nov.30 2017
   */ 
  private void NCScale(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
	long startNCScaleTime=System.nanoTime();
	LOG.info("DataXceiver.NCScale.receive the request success!");
    
    ScalingHeader header = new ScalingHeader(versionAndOpcode, datanode.getConf());
    header.readFields(in);

    String[] nodesName = header.getNodesName();
    int namespaceId = header.getNameSpaceId();
    int localnodeID = header.getidx();
    int scalingID = header.getscalingID();
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
    String codeid = header.getCode();
    String jsonStr = header.getJsonStr();
    String localNode = s.getLocalSocketAddress().toString().substring(1);
//    System.out.println("scalingID : "+scalingID); 

    //load lib
    try {
      System.loadLibrary("hadoop");
    } catch (Throwable t) {
      DataNode.LOG.info("DataXceiver.NCScale.loadlibrary with error: " + t);
      DataNode.LOG.info("DataXceiver.NCScale.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
    }

    ScalingAlgorithmic sa = new ScalingAlgorithmic(datanode.getConf(), localNode, dataBlocks, parityBlocks, nodesName,localnodeID);
    sa.computeDelta();	//Compute delta parity, and delta is stored in Map<Integer, byte[][]> deltaParity.
    sa.localUpdate(null);
    sa.transferToNewNodes();
    sa.transferDeltaToRemoteNode();

    
    //write back
    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
    out.flush();
    DataNode.LOG.info("DataXceiver.NCScale.write success to back stream");
    long finishNCScaleTime=System.nanoTime();
	long NCScaleTime = finishNCScaleTime-startNCScaleTime;
	LOG.info("DataXceiver.NCScale.scaling in node time is = "+NCScaleTime);
  }
  
  /**
   * This is the remote update operation in NCScale. 
   * Xiaoyang modify at Dec.29 2017 
   */
  private void remoteUpdate(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
	  	long startRemoteUpdateTime=System.nanoTime();  
	  	LOG.info("DataXceiver.remoteUpdate.receive the request success!");
	    
	    ScalingDeltaTransferHeader header = new ScalingDeltaTransferHeader(versionAndOpcode, datanode.getConf());
	    header.readFields(in);

	    String[] nodesName = header.getNodesName();
	    int namespaceId = header.getNameSpaceId();
	    int deltaTransferID = header.getDeltaTransferID();
	    Map<Integer, LocatedBlockWithFileName> parityBlocks= header.getParityBlocks();
	    byte[][] delta = header.getDeltaBlocks();
	    String localNode = s.getLocalSocketAddress().toString().substring(1);

	    //load lib
	    try {
	      System.loadLibrary("hadoop");
	    } catch (Throwable t) {
	      DataNode.LOG.info("DataXceiver.remoteUpdate.loadlibrary with error: " + t);
	      DataNode.LOG.info("DataXceiver.remoteUpdate.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
	    }

	    ScalingAlgorithmic sa = new ScalingAlgorithmic(datanode.getConf(), localNode, nodesName,deltaTransferID);
	    sa.remoteUpdate(deltaTransferID, parityBlocks, delta);
	    
	    //write back
	    OutputStream baseStream = NetUtils.getOutputStream(s, 
	        datanode.socketWriteTimeout);
	    DataOutputStream out = new DataOutputStream(
	    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

	    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
	    out.flush();
	    DataNode.LOG.info("DataXceiver.remoteUpdate.write success to back stream");
	    long finishRemoteUpdateTime=System.nanoTime();
		long RemoteUpdateTime = finishRemoteUpdateTime-startRemoteUpdateTime;
		LOG.info("DataXceiver.remoteUpdate.remoteUpdate in node time is = "+RemoteUpdateTime);
	  }
  
  /**
   * This is the main scaling operation of ScaleRS. 
   * 1. Compute the delta parity blocks
   * 2. Send delta blocks to corresponding nodes and update parity blocks.
   * 3. Send data blocks to new nodes.
   * 4. Delete all obsolete blocks.
   * modify at Apr.09 2018
   */ 
  private void ScaleRS(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
	  long startScaleRSTime=System.nanoTime();
    LOG.info("DataXceiver.ScaleRS.receive the request success!");
    
    ScalingHeader header = new ScalingHeader(versionAndOpcode, datanode.getConf());
    header.readFields(in);

    String[] nodesName = header.getNodesName();
    int namespaceId = header.getNameSpaceId();
    int scalingID = header.getscalingID();
    int idx = header.getidx(); //Node id of a scaling group. Used in ScaleRS. idx_th data node while scaling.
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
    String codeid = header.getCode();
    String jsonStr = header.getJsonStr();
    String localNode = s.getLocalSocketAddress().toString().substring(1);
//    System.out.println("scalingID : "+scalingID); 

    //load lib
    try {
      System.loadLibrary("hadoop");
    } catch (Throwable t) {
      DataNode.LOG.info("DataXceiver.ScaleRS.loadlibrary with error: " + t);
      DataNode.LOG.info("DataXceiver.ScaleRS.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
    }

    ScaleRS sa = new ScaleRS(datanode.getConf(), localNode, dataBlocks, parityBlocks, scalingID, idx, nodesName);
    sa.computeDelta();	//Compute delta parity, and delta is stored in byte[][] deltaParity.
    sa.transferToNewNodes();
    sa.transferDeltaToRemoteNode();

    //write back
    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
    out.flush();
    DataNode.LOG.info("DataXceiver.ScaleRS.write success to back stream");
    long finishScaleRSTime=System.nanoTime();
	long ScaleRSTime = finishScaleRSTime-startScaleRSTime;
	LOG.info("DataXceiver.ScaleRS.scaling in node time is = "+ScaleRSTime);
  }
  
  /** 
   * This is the remote update operation in ScaleRS. 
   * Xiaoyang modify at Apr.10 2018
   */
  private void remoteUpdateScaleRS(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
	  long startRemoteUpdateScaleRSTime=System.nanoTime();  
	    LOG.info("DataXceiver.remoteUpdateScaleRS.receive the request success!");
	    
	    ScaleRSDeltaTransferHeader header = new ScaleRSDeltaTransferHeader(versionAndOpcode, datanode.getConf());
	    header.readFields(in);

	    String[] nodesName = header.getNodesName();
	    int namespaceId = header.getNameSpaceId();
	    int deltaTransferID = header.getDeltaTransferID();
	    Map<Integer, LocatedBlockWithFileName> parityBlocks= header.getParityBlocks();
	    byte[][] delta = header.getDeltaBlocks();
	    String localNode = s.getLocalSocketAddress().toString().substring(1);
	    String remoteNode = s.getRemoteSocketAddress().toString().substring(1);
	    
	    //load lib
	    try {
	      System.loadLibrary("hadoop");
	    } catch (Throwable t) {
	      DataNode.LOG.info("DataXceiver.remoteUpdateScaleRS.loadlibrary with error: " + t);
	      DataNode.LOG.info("DataXceiver.remoteUpdateScaleRS.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
	    }

	    ScaleRS sa = new ScaleRS(datanode.getConf(), localNode, nodesName);
	    sa.remoteUpdate(deltaTransferID, parityBlocks, delta,remoteNode);
	    
	    //write back
	    OutputStream baseStream = NetUtils.getOutputStream(s, 
	        datanode.socketWriteTimeout);
	    DataOutputStream out = new DataOutputStream(
	    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

	    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
	    out.flush();
	    DataNode.LOG.info("DataXceiver.remoteUpdateScaleRS.write success to back stream");
	    long finishRemoteUpdateScaleRSTime=System.nanoTime();
		long RemoteUpdateScaleRSTime = finishRemoteUpdateScaleRSTime-startRemoteUpdateScaleRSTime;
		LOG.info("DataXceiver.remoteUpdateScaleRS.remoteUpdate in node time is = "+RemoteUpdateScaleRSTime);
	  }
  
  /** 
   * This is the main scaling operation of MBRScale. 
   * 1. Send data blocks to new nodes.
   * 2. update parity blocks
   * modify at Apr.04 2017
   */ 
  private void MBRScale(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
    LOG.info("DataXceiver.scaling.receive the request success!");
    
    ScalingHeader header = new ScalingHeader(versionAndOpcode, datanode.getConf());
    header.readFields(in);

    String[] nodesName = header.getNodesName();
    int namespaceId = header.getNameSpaceId();
    int scalingID = header.getscalingID();
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks= header.getDataBlocks();
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks= header.getParityBlocks();
    String codeid = header.getCode();
    String jsonStr = header.getJsonStr();
    String localNode = s.getLocalSocketAddress().toString().substring(1); //Get the local node ip  

    //load lib
    try {
      System.loadLibrary("hadoop");
    } catch (Throwable t) {
      DataNode.LOG.info("DataXceiver.scaling.loadlibrary with error: " + t);
      DataNode.LOG.info("DataXceiver.scaling.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
    }

    //ScalingMBR init
    ScalingMBR smbr = new ScalingMBR(datanode.getConf(), localNode, dataBlocks, parityBlocks, nodesName);
   
    //TODO: 把以前的东西改为现在的MBR
    

	
    //write back
    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
    	new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
    out.flush();
    DataNode.LOG.info("DataXceiver.scaling.write success to back stream");
    
  }
  
  /**
   * deal with the draft connection
   * this is the node level of the double-regenerating-code
   * receive request from relayer node
   * this node is responsible for networking codeing on this node and return the encoded data to relayer
   */
  private void drcnode(DataInputStream in, VersionAndOpcode versionAndOpcode, long receiverStartTime) 
      throws IOException {
    LOG.info("DataXceiver.drcnode.receive the request success!");

    // read in header

    long startNanoTime = System.nanoTime();
    long startTime = System.currentTimeMillis();

    ReadDRCBlockHeader header = new ReadDRCBlockHeader(versionAndOpcode);
    header.readFields(in);

    ReadOptions options = header.getReadOptions();
    boolean ioprioEnabled = !options.isIoprioDisabled();
    if(ioprioEnabled) {
      NativeIO.ioprioSetIfPossible(options.getIoprioClass(),
          options.getIoprioData());
    }

    int namespaceId = header.getNamespaceId();
	LOG.info("DataXceiver.drcnode.namespaceId = " + namespaceId);
    long blockId = header.getBlockId();
//    LOG.info("DataXceiver.drcnode.receive blockId = " + blockId);
    Block block = new Block(blockId, 0, header.getGenStamp());
    long startOffset = header.getStartOffset();
//    LOG.info("DataXceiver.drcnode.receive encoded startOffset = " + startOffset);
    long length = header.getLen();   //  the encoded length
//    LOG.info("DataXceiver.drcnode.receive encoded length = " + length);
    String clientName = header.getClientName();
	String jsonstr = header.getJsonStr();
	int nodeId = header.getNodeId();
	int[] corruptArray = header.getCorruptArray();
//	DataNode.LOG.info("DataXceiver.drcnode.nodeId = " + nodeId);
//	for(int i=0; i<corruptArray.length; i++) {
//	  DataNode.LOG.info("DataXceiver.corruptArray[" + i + "] = " + corruptArray[i]);
//	}
	reuseConnection = header.getReuseConnection();
    boolean shouldProfile = header.getShouldProfile();
    FSDataNodeReadProfilingData dnData = shouldProfile ?
        new FSDataNodeReadProfilingData() : null;

    if(shouldProfile) {
      dnData.readVersionAndOpCodeTime = (startNanoTime - receiverStartTime);
      dnData.readBlockHeaderTime = (System.nanoTime() - startNanoTime);
      dnData.startProfiling();
    }
    
    // send the block
    OutputStream baseStream = NetUtils.getOutputStream(s,
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
        new BufferedOutputStream(baseStream, 
            SMALL_BUFFER_SIZE));

    DRCBlockSender blockSender = null;
    String clientTraceFmt = null;

    if (ClientTraceLog.isInfoEnabled()) {
      if (remoteAddress == null) {
        getAddresses();
      }
      clientTraceFmt = clientName.length() > 0 ? String.format(
          DN_CLIENTTRACE_FORMAT, localAddress, remoteAddress, "%d",
          "HDFS_READ", clientName, "%d",
          datanode.getDNRegistrationForNS(namespaceId).getStorageID(), block,
          "%d")
          :
          datanode.getDNRegistrationForNS(namespaceId)
          + " Served block " + block + " to " + s.getInetAddress();
    }
    updateCurrentThreadName("sending block " + block);
    InjectionHandler.processEvent(InjectionEvent.DATANODE_READ_BLOCK);

    try {
      try {
        // here length is encoded length
//        LOG.info("DataXceiver.drcnode.create DRCBlockSender");
          blockSender = new DRCBlockSender(namespaceId, block, startOffset,
                  length, datanode.ignoreChecksumWhenRead, true, true, false,
                  versionAndOpcode.getDataTransferVersion() >=
                      DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
                  false, datanode, clientTraceFmt, jsonstr,  
      			new	Configuration(), nodeId, corruptArray);
              // what's the meaning of reserve???
            } catch (IOException e) {
              throw e;
            }

      // success response
      out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
      if(shouldProfile) {
        dnData.startSendBlock();
      }
      
      LOG.info("DataXceiver.drcnode.write SUCCESS into backstream");
  
      DataTransferThrottler throttler = null;
//      LOG.info("DataXceiver.drcnode.start blockSender.sendBlock");
      long read = blockSender.sendBlock(out, baseStream, throttler); // TODO: here read contain checksum?

      if(shouldProfile) {
        dnData.endSendBlock();
      }

      //report finalization information and block length.
      ReplicaToRead replicaRead = blockSender.getReplicaToRead();

      if(replicaRead == null) {
        replicaRead = datanode.data.getReplicaToRead(namespaceId, block);
      }
      if(replicaRead == null) {
        throw new IOException("can't find block " + block + " in volumeMap");
      }

      int fadvise = header.getReadOptions().getFadvise();
      if(fadvise != 0) {
        blockSender.fadviseStream(fadvise, startOffset, length);
      }

      boolean isBlockFinalized = replicaRead.isFinalized();
      long blockLength = replicaRead.getBytesVisible();
      out.writeBoolean(isBlockFinalized);
      out.writeLong(blockLength);

      if(shouldProfile) {
        dnData.write(out);
      }
      
      out.flush();

      if(blockSender.didSendEntireByteRange()) {
        try {
          short status = in.readShort();
          if((status == DataTransferProtocol.OP_STATUS_CHECKSUM_OK ||
              status == DataTransferProtocol.OP_STATUS_SUCCESS) &&
              datanode.blockScanner != null) {
            datanode.blockScanner.verifiedByClient(namespaceId, block);
          }
        } catch (IOException ignored) {
        }         
      }

      long readDuration = System.currentTimeMillis() - startTime;

      if(readDuration > datanode.thresholdLogReadDuration
          && !ClientTraceLog.isInfoEnabled()) {
        if(remoteAddress == null) {
          getAddresses();
        }
        LOG.info("sent block " + block + " to " + remoteAddress + " offset "
            + startOffset + " len " + length + " duration " + readDuration
            + " (longer than " + datanode.thresholdLogReadDuration + ")");
      }

      datanode.myMetrics.bytesReadLatency.inc(readDuration);
      datanode.myMetrics.bytesRead.inc((int) read);
      if (read > KB_RIGHT_SHIFT_MIN) {
        datanode.myMetrics.bytesReadRate.inc((int) (read >> KB_RIGHT_SHIFT_BITS),
            readDuration);
      }
      datanode.myMetrics.blocksRead.inc();
    } catch (SocketException ignored) {
      datanode.myMetrics.blocksRead.inc();
      IOUtils.closeStream(out);
      if(LOG.isDebugEnabled()) {
        LOG.debug("Ignore exception while sending blocks. namespaceId: "
            + namespaceId + " block: " + block + " to " + remoteAddress + ": "
            + ignored.getMessage());
      }
    } catch (IOException ioe) {
      LOG.warn(datanode.getDatanodeInfo() + ": Got exception while serving " +
          "namespaceId: " + namespaceId + " block: " + block + " to " +
          s.getInetAddress() + ", client: " + clientName + ":\n" +
          StringUtils.stringifyException(ioe));
      throw ioe;
    } finally {
      IOUtils.closeStream(blockSender);
    }
  }


  /** 
   * deal with the draft connection
   * this is the rack level of the double-regenerating-code
   * receive request from the datanode who will hold the recover data
   * this node is the relayer in each rack
   * this node is responsible for reading (encoded)data from other datanode in the same rack
   *
   * modify at Mar.4 2016
   * modify at Mar.7 2016
   */ 
  private void drcrack(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
    LOG.info("DataXceiver.drcrack.receive the request success!");
 
    // read in header
    ReadGroupHeader header = new ReadGroupHeader(versionAndOpcode);
    header.readFields(in);

    ReadOptions options = header.getReadOptions();
    boolean ioprioEnabled = !options.isIoprioDisabled();
    if(ioprioEnabled) {
      NativeIO.ioprioSetIfPossible(options.getIoprioClass(),
          options.getIoprioData());
    }

    int namespaceId = header.getNamespaceId();
	LOG.info("DataXceiver.drcrack.namespaceId = " + namespaceId);
    int groupId = header.getGroupId();
    int groupSize = header.getGroupSize();
    Map<Integer, LocatedBlockWithFileName> groupBlocks = header.getGroupBlocks();
    int[] idxArray = header.getIdxArray();
    int[] corruptArray = header.getCorruptArray();
    String jsonStr = header.getJsonStr();
    int toReadInGroup = header.getToReadInGroup();
    int bufferSize = header.getBufferSize();
    boolean verifyChecksum = header.getVerifyChecksum();
    String clientName = header.getClientName();
    long bytesToCheckSpeed = header.getBytesToCheckReadSpeed();
    long minSpeedBps = header.getMinSpeedBps();
    boolean reuseConnection = header.getReuseConnection();
    boolean shouldProfile = header.getShouldProfile();
    String code = header.getCode();
    int outputUnitNum = header.getOutputUnitNum();
    
    FSDataNodeReadProfilingData dnData = shouldProfile ?
        new FSDataNodeReadProfilingData() : null;

    /**
     *TODO: if should profile???
     */

    // send encoded data from in a rack back
    OutputStream baseStream = NetUtils.getOutputStream(s,
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
        new BufferedOutputStream(baseStream, 
            SMALL_BUFFER_SIZE));

    GroupSender groupSender = null;

    String clientTraceFmt = null;

    /**
     * TODO: InjectionHandler.processEvent
     */
    
//    try {

      try {
        LOG.info("DataXceiver.drcrack.newGroupSender");
        groupSender = new GroupSender(groupId, groupSize, groupBlocks, idxArray, 
            outputUnitNum, code, toReadInGroup, clientTraceFmt, datanode, true, false, 
            versionAndOpcode.getDataTransferVersion() >=
                DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
            true, jsonStr, corruptArray);
      } catch (IOException e) {
        throw e;
      }

      // success response
      out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);

      /**
       * TODO: if should profile???
       */
      
      LOG.info("DataXceiver.drcrack.write SUCCESS to backstream");
      DataTransferThrottler throttler = null;
      long read = groupSender.sendGroup(out, baseStream, throttler);
//      LOG.info("DataXceiver.drcrack.sendGroup read = " + read);


      /**
       * TODO: should proifle?
       */

       


      out.flush(); 

//    } catch() {
//    }

  }


  /**
   * deal with the draft connection
   * this is the final step of the double-regenerating-code
   * to read from all the relayers and recover the corrupted block
   * the datanode which call this function is the destination datanode 
   * to contain the new data
   * TODO: 1 to read from the relayers
   *       2 recover the data
   *       3 write to local disk  
   */

  private void drcfinal(DataInputStream in, VersionAndOpcode versionAndOpcode) throws IOException {
    DataNode.LOG.info("DataXceiver.drcfinal.receive the request success!");
    long  t1 = System.nanoTime();
    long startNanoTime = System.nanoTime();
    long startTime = System.currentTimeMillis();
    
	long h1= System.nanoTime();
    TargetHeader header = new TargetHeader(versionAndOpcode);
    header.readFields(in);

    int namespaceId = header.getNameSpaceId();
//	DataNode.LOG.info("DataXceiver.drcfinal.namespaceid = " + namespaceId);
    Map<Integer, LocatedBlockWithFileName> stripeBlocks= header.getStripeBlocks();
    int[] corruptArray= header.getCorruptArray();
    String codeid = header.getCode();
    String jsonStr = header.getJsonStr();
    long h2 = System.nanoTime();
	DataNode.LOG.info("DataXceiver.drcfinal.header time = " + (h2-h1));
    // create DRCDecoder

    long l1=System.nanoTime();
    try {
      System.loadLibrary("hadoop");
    } catch (Throwable t) {
      DataNode.LOG.info("DataXceiver.drcfinal.loadlibrary with error: " + t);
      DataNode.LOG.info("DataXceiver.drcfinal.loadlibrary with java.library.path = " + System.getProperty("java.library.path"));
    }
    long l2=System.nanoTime();
	DataNode.LOG.info("DataXceiver.drcfinal.load time = " + (l2-l1));

	long c1=System.nanoTime();
    DRCDecoder decoder = new DRCDecoder(datanode.getConf(), jsonStr, stripeBlocks, corruptArray);
//    decoder.tryReadGroup();
    long c2=System.nanoTime();
	DataNode.LOG.info("DataXceiver.drcfinal.create time = " + (c2-c1));

    boolean recoverable = decoder.getRecoverable();
    long start=0, end=0;
    if(recoverable) {
      DataNode.LOG.info("start to recover");
      decoder.recoverBlockToFile();
      DataNode.LOG.info("recover ends!");

    } else {
      throw new IOException("corruption can't recover by double regenerating code");
    }

    DataNode.LOG.info("DataXceiver.drcfinal.end recover"); 

    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
        new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

    out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
    out.flush();
    DataNode.LOG.info("DataXceiver.drcfinal.write success to back stream");
    long t2=System.nanoTime();
	DataNode.LOG.info("Dataceiver.drcfinal.time = " + (t2-t1));
    DataNode.LOG.info("DataXceiver.drcfinal.start write into HDFS");
	decoder.writeHdfs();

  }

  /** 
   * get the validate blocknum list in this rack
   */
  public List<Integer> getBlocksInRack(Map<DatanodeInfo, List<Integer>> thisrack) {
    List<Integer> blocklist = new LinkedList<Integer>();
    for (Iterator it = thisrack.keySet().iterator(); it.hasNext();) {
      DatanodeInfo tempdn = (DatanodeInfo)it.next();
      List<Integer> blocksInNode = thisrack.get(tempdn);
      for (int i=0; i<blocksInNode.size(); i++) {
        blocklist.add(blocksInNode.get(i));
      }
    }
    return blocklist;
  }  

  /**
   * find a relayernode randomly
   */
  public DatanodeInfo getRelayerNode(Map<DatanodeInfo, List<Integer>> thisrack) 
         throws IOException {
    int datanodeNum = thisrack.size();
    Random random = new Random();
    int r = random.nextInt(datanodeNum);
    Object[] datanodeset = thisrack.keySet().toArray();
    DatanodeInfo relayernode = (DatanodeInfo) datanodeset[r];
    return relayernode;
  }
  /**
   * map the rack number to the datanode to the validate blocks in this datanode
   */

  public Map<String, Map<DatanodeInfo, List<Integer>>> mapRackToDNandBlock(
                     Map<Integer, LocatedBlockWithMetaInfo> blocksinfile,
                     int stripenum,
                     int stripesize)
         throws IOException {
    Map<String, Map<DatanodeInfo, List<Integer>>> ret =
      new HashMap<String, Map<DatanodeInfo, List<Integer>>>();
    for (int blocknum = stripenum * stripesize; (blocknum < blocksinfile.size())
         && (blocknum < (stripenum + 1) * stripesize); blocknum++) {
      if ((blocksinfile.get(blocknum)).getCorrupt())
        continue;
      DatanodeInfo[] blockDNInfo = (blocksinfile.get(blocknum)).getLocations();
      String rack = blockDNInfo[0].getNetworkLocation();  //TODO: how to choose the datanode of the block
      LOG.info("blocknum = " + blocknum + " datanodenum " + blockDNInfo[0].getName()
        + " racknum = " + rack);
      Map<DatanodeInfo, List<Integer>> dntoblock = ret.get(rack);
      List<Integer> blocklist;
      if (dntoblock == null) {
        dntoblock = new HashMap<DatanodeInfo, List<Integer>>();
        LOG.info("datanode null, new datanode and blocklist");
        blocklist = new LinkedList<Integer>();
        blocklist.add(blocknum);
        dntoblock.put(blockDNInfo[0], blocklist);
        ret.put(rack, dntoblock);
      } else {
        blocklist = dntoblock.get(blockDNInfo[0]);
        if (blocklist == null) {
          LOG.info("datanode not null, list null, new blocklist");
          blocklist = new LinkedList<Integer>();
          blocklist.add(blocknum);
        } else {
          blocklist.add(blocknum);
        }
        dntoblock.put(blockDNInfo[0],blocklist);
        ret.put(rack, dntoblock);
      }
      LOG.info("this datanode blocks size = " + blocklist.size());
    }
    return ret;
  }
      

  /**
   * read through a source file and get all the blocks info in this file
   */
  public Map<Integer, LocatedBlockWithMetaInfo> getAllBlocksInFile(
                                              DistributedFileSystem fs,
                                              String uriPath,
                                              FileStatus stat)
         throws IOException {
    VersionedLocatedBlocks locatedBlocks;
    Map<Integer,LocatedBlockWithMetaInfo> locatedBlockstoret =
             new HashMap<Integer, LocatedBlockWithMetaInfo>();
    long blocksize = stat.getBlockSize();
    int namespaceId = 0;
    int methodFingerprint = 0;
    if(DFSClient.isMetaInfoSuppoted(fs.getClient().namenodeProtocolProxy)) {
      LocatedBlocksWithMetaInfo lbwmi = fs.getClient().namenode.
                                        openAndFetchMetaInfo(uriPath, 0, stat.getLen());
      locatedBlocks = lbwmi;
      namespaceId = lbwmi.getNamespaceID();
      methodFingerprint = lbwmi.getMethodFingerPrint();
    } else {
      locatedBlocks = fs.getClient().namenode.open(uriPath, 0, stat.getLen());
    }
    final int dataTransferVersion = locatedBlocks.getDataProtocolVersion();
    for(LocatedBlock b: locatedBlocks.getLocatedBlocks()) {
      long boffset = b.getStartOffset();
      int blockIdx = (int)(boffset/blocksize);
      
      locatedBlockstoret.put(blockIdx, new LocatedBlockWithMetaInfo(b.getBlock(),
                             b.getLocations(),b.getStartOffset(),
                             dataTransferVersion, namespaceId, methodFingerprint));
    }
 
    return locatedBlockstoret;
  }

  /** 
   */  
  protected DistributedFileSystem getDFS(Path p) throws IOException {
    FileSystem fs = p.getFileSystem(new Configuration());   // TODO: figure out this configuration?
    DistributedFileSystem dfs = null;
    if (fs instanceof DistributedFileSystem) {
      dfs = (DistributedFileSystem) fs;
    } else if(fs instanceof FilterFileSystem) {
      FilterFileSystem ffs = (FilterFileSystem) fs;
      if (ffs.getRawFileSystem() instanceof DistributedFileSystem) {
        dfs = (DistributedFileSystem) ffs.getRawFileSystem();
      }
    }
    return dfs;
  }
      

  /**
   * Read a block from the disk.
   * @param in The stream to read from
   * @throws IOException
   */
  private void readBlock(DataInputStream in,
      VersionAndOpcode versionAndOpcode, long receiverStartTime) throws IOException {
    //
    // Read in the header
    //
    long startNanoTime = System.nanoTime();
    long startTime = System.currentTimeMillis();
    
    ReadBlockHeader header = new ReadBlockHeader(versionAndOpcode);
    header.readFields(in);
    
    ReadOptions options = header.getReadOptions();
    boolean ioprioEnabled = !options.isIoprioDisabled();
    if (ioprioEnabled) {
      NativeIO.ioprioSetIfPossible(options.getIoprioClass(),
          options.getIoprioData());
    }

    int namespaceId = header.getNamespaceId();
    long blockId = header.getBlockId();
    Block block = new Block( blockId, 0 , header.getGenStamp());
    long startOffset = header.getStartOffset();
    long length = header.getLen();
    String clientName = header.getClientName();
    reuseConnection = header.getReuseConnection();
    boolean shouldProfile = header.getShouldProfile();
    FSDataNodeReadProfilingData dnData = shouldProfile ? 
        new FSDataNodeReadProfilingData() : null;

    if (shouldProfile) {
      dnData.readVersionAndOpCodeTime = (startNanoTime - receiverStartTime) ;
      dnData.readBlockHeaderTime = (System.nanoTime() - startNanoTime);
      dnData.startProfiling();
    }
    
    // send the block
    OutputStream baseStream = NetUtils.getOutputStream(s, 
        datanode.socketWriteTimeout);
    DataOutputStream out = new DataOutputStream(
                 new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));
    
    BlockSender blockSender = null;
    String clientTraceFmt = null;
    if (ClientTraceLog.isInfoEnabled()) {    
      if (remoteAddress == null) {
        getAddresses();
      }
      clientTraceFmt = clientName.length() > 0 ? String.format(
          DN_CLIENTTRACE_FORMAT, localAddress, remoteAddress, "%d",
          "HDFS_READ", clientName, "%d",
          datanode.getDNRegistrationForNS(namespaceId).getStorageID(), block,
          "%d")
          :
          datanode.getDNRegistrationForNS(namespaceId)
          + " Served block " + block + " to " + s.getInetAddress();
    }
    updateCurrentThreadName("sending block " + block);
    InjectionHandler.processEvent(InjectionEvent.DATANODE_READ_BLOCK);
        
    try {
      try {
        blockSender = new BlockSender(namespaceId, block, startOffset, length,
            datanode.ignoreChecksumWhenRead, true, true, false,
            versionAndOpcode.getDataTransferVersion() >=
              DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
            false, datanode, clientTraceFmt);
        if (shouldProfile) {
          blockSender.enableReadProfiling(dnData);
        }
     } catch(IOException e) {
        sendResponse(s, (short) DataTransferProtocol.OP_STATUS_ERROR, 
            datanode.socketWriteTimeout);
        throw e;
      }
      if (ClientTraceLog.isInfoEnabled()) {
        ClientTraceLog.info("Sending blocks. namespaceId: "
            + namespaceId + " block: " + block + " to " + remoteAddress);
      }

      out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS); // send op status
      if (shouldProfile) {
        dnData.startSendBlock();
      }
      
      long read = blockSender.sendBlock(out, baseStream, null); // send data
      if (shouldProfile) {
        dnData.endSendBlock();
      }
      
      // report finalization information and block length.
      ReplicaToRead replicaRead = blockSender.getReplicaToRead();
      if (replicaRead == null) {
        replicaRead = datanode.data.getReplicaToRead(namespaceId, block);
      }
      if (replicaRead == null) {
        throw new IOException("Can't find block " + block + " in volumeMap");
      }
      
      int fadvise = header.getReadOptions().getFadvise();
      if (fadvise != 0) {
        blockSender.fadviseStream(fadvise, startOffset, length);
      }

      boolean isBlockFinalized = replicaRead.isFinalized();
      long blockLength = replicaRead.getBytesVisible();
      out.writeBoolean(isBlockFinalized);
      out.writeLong(blockLength);
      if (shouldProfile) {
        dnData.write(out);
      }
      out.flush();

      if (blockSender.didSendEntireByteRange()) {
        // See if client verification succeeded. 
        // This is an optional response from client.
        try {
          short status = in.readShort();
          if ((status == DataTransferProtocol.OP_STATUS_CHECKSUM_OK ||
               status == DataTransferProtocol.OP_STATUS_SUCCESS) && 
              datanode.blockScanner != null) {
            datanode.blockScanner.verifiedByClient(namespaceId, block);
          }
        } catch (IOException ignored) {}
      }
      
      long readDuration = System.currentTimeMillis() - startTime;
      
      if (readDuration > datanode.thresholdLogReadDuration
          && !ClientTraceLog.isInfoEnabled()) {
        // No need to double logging if we've logged duration of every read
        
        if (remoteAddress == null) {
            getAddresses();
        }
        LOG.info("Sent block " + block + " to " + remoteAddress + " offset "
            + startOffset + " len " + length + " duration " + readDuration
            + " (longer than " + datanode.thresholdLogReadDuration + ")");
      }
      
      datanode.myMetrics.bytesReadLatency.inc(readDuration);
      datanode.myMetrics.bytesRead.inc((int) read);
      if (read > KB_RIGHT_SHIFT_MIN) {
        datanode.myMetrics.bytesReadRate.inc((int) (read >> KB_RIGHT_SHIFT_BITS),
                              readDuration);
      }
      datanode.myMetrics.blocksRead.inc();
    } catch ( SocketException ignored ) {
      // Its ok for remote side to close the connection anytime.
      datanode.myMetrics.blocksRead.inc();
      IOUtils.closeStream(out);
      
      // log exception to debug exceptions like socket timeout exceptions
      if (LOG.isDebugEnabled()) {
        LOG.debug("Ignore exception while sending blocks. namespaceId: "
          + namespaceId + " block: " + block + " to " + remoteAddress + ": "
          + ignored.getMessage());
      }
    } catch ( IOException ioe ) {
      /* What exactly should we do here?
       * Earlier version shutdown() datanode if there is disk error.
       */
      LOG.warn(datanode.getDatanodeInfo() +  ":Got exception while serving " + 
          "namespaceId: " + namespaceId + " block: " + block + " to " +
                s.getInetAddress() + ", client: " + clientName + ":\n" + 
                StringUtils.stringifyException(ioe) );
      throw ioe;
    } finally {
      IOUtils.closeStream(blockSender);
    }
  }

  /**
   * Append a block on disk.
   * 
   * @param in The stream to read from
   * @param versionAndOpcode
   * @throws IOException
   */
  private void appendBlock(DataInputStream in,
      VersionAndOpcode versionAndOpcode) throws IOException {
    DatanodeInfo srcDataNode = null;
    if (LOG.isDebugEnabled()) {
      LOG.debug("appendBlock receive buf size " + s.getReceiveBufferSize() + 
            " tcp no delay " + s.getTcpNoDelay());
    }
    
    InjectionHandler.processEventIO(InjectionEvent.DATANODE_APPEND_BLOCK);
    
    // Read in the header
    long startTime = System.currentTimeMillis();
    AppendBlockHeader headerToReceive = new AppendBlockHeader(versionAndOpcode);
    headerToReceive.readFields(in);
    int namespaceid = headerToReceive.getNamespaceId();
    Block block = new Block(headerToReceive.getBlockId(), 
        headerToReceive.getNumBytes(), headerToReceive.getGenStamp());
    if (LOG.isInfoEnabled()) {
      if (remoteAddress == null) {
        getAddresses();
      }
      LOG.info("Receiving block " + block + 
          " src: " + remoteAddress + 
          " dest: " + localAddress);
    }
    
    int pipelineSize = headerToReceive.getPipelineDepth();
    String client = headerToReceive.getWritePipelineInfo().getClientName();
    boolean hasSrcDataNode = headerToReceive.getWritePipelineInfo()
        .hasSrcDataNode();
    if (hasSrcDataNode) {
      srcDataNode = headerToReceive.getWritePipelineInfo().getSrcDataNode();
    }
    int numTargets = headerToReceive.getWritePipelineInfo().getNumTargets();
    DatanodeInfo targets[] = headerToReceive.getWritePipelineInfo().getNodes();

    DataOutputStream mirrorOut = null;  // stream to next target
    DataInputStream mirrorIn = null;    // reply from next target
    DataOutputStream replyOut = null;   // stream to prev target
    Socket mirrorSock = null;           // socket to next target
    BlockReceiver blockReceiver = null; // responsible for data handling
    String mirrorNode = null;           // the name:port of next target
    String firstBadLink = "";           // first datanode that failed in connection setup

    updateCurrentThreadName("receiving append block " + block + " client=" + client);

    try {

      // update the block 
      Block oldBlock = new Block(headerToReceive.getBlockId());
      oldBlock = datanode.getBlockInfo(namespaceid, oldBlock);

      boolean isSecondary = (targets.length + 1 != pipelineSize);
      // open a block receiver and check if the block does not exist
      // append doesn't support per block fadvise
      blockReceiver = new BlockReceiver(namespaceid, oldBlock, block, in, 
          s.getRemoteSocketAddress().toString(),
          s.getLocalSocketAddress().toString(),
          true, client, srcDataNode, datanode, isSecondary, 0, false,
          versionAndOpcode.getDataTransferVersion() >=
            DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
            headerToReceive.getWritePipelineInfo().getWriteOptions().getSyncFileRange());

      // get a connection back to the previous target
      replyOut = new DataOutputStream(new BufferedOutputStream(
          NetUtils.getOutputStream(s, datanode.socketWriteTimeout),
          SMALL_BUFFER_SIZE));

      //
      // Open network conn to backup machine, if 
      // appropriate
      //
      if (targets.length > 0) {
        InetSocketAddress mirrorTarget = null;
        // Connect to backup machine
        mirrorNode = targets[0].getName();
        mirrorTarget = NetUtils.createSocketAddr(mirrorNode);
        mirrorSock = datanode.newSocket();
        try {
          int timeoutValue = datanode.socketTimeout +
              (datanode.socketReadExtentionTimeout * numTargets);
          int writeTimeout = datanode.socketWriteTimeout + 
              (datanode.socketWriteExtentionTimeout * numTargets);
          NetUtils.connect(mirrorSock, mirrorTarget, timeoutValue);
          mirrorSock.setSoTimeout(timeoutValue);
          mirrorSock.setSendBufferSize(DEFAULT_DATA_SOCKET_SIZE);
          mirrorOut = new DataOutputStream(
              new BufferedOutputStream(
                  NetUtils.getOutputStream(mirrorSock, writeTimeout),
                  SMALL_BUFFER_SIZE));
          mirrorIn = new DataInputStream(NetUtils.getInputStream(mirrorSock));

          // Write header: Copied from DFSClient.java!
          AppendBlockHeader headerToSend = new AppendBlockHeader(
              versionAndOpcode.getDataTransferVersion(), namespaceid,
              block.getBlockId(), block.getNumBytes(), block.getGenerationStamp(), pipelineSize,
              hasSrcDataNode, srcDataNode, targets.length - 1, targets,
              client);
          headerToSend.writeVersionAndOpCode(mirrorOut);
          headerToSend.write(mirrorOut);
          blockReceiver.writeChecksumHeader(mirrorOut);
          mirrorOut.flush();

          // read connect ack (only for clients, not for replication req)
          if (client.length() != 0) {
            firstBadLink = Text.readString(mirrorIn);
            if (LOG.isDebugEnabled() || firstBadLink.length() > 0) {
              LOG.info("Datanode " + targets.length +
                  " got response for connect ack " +
                  " from downstream datanode with firstbadlink as " +
                  firstBadLink);
            }
          }

        } catch (IOException e) {
          if (client.length() != 0) {
            Text.writeString(replyOut, mirrorNode);
            replyOut.flush();
          }
          IOUtils.closeStream(mirrorOut);
          mirrorOut = null;
          IOUtils.closeStream(mirrorIn);
          mirrorIn = null;
          IOUtils.closeSocket(mirrorSock);
          mirrorSock = null;
          if (client.length() > 0) {
            throw e;
          } else {
            LOG.info(datanode.getDatanodeInfo() + ":Exception transfering block " +
                block + " to mirror " + mirrorNode +
                ". continuing without the mirror.\n" +
                StringUtils.stringifyException(e));
          }
        }
      }

      // send connect ack back to source (only for clients)
      if (client.length() != 0) {
        if (LOG.isDebugEnabled() || firstBadLink.length() > 0) {
          LOG.info("Datanode " + targets.length +
              " forwarding connect ack to upstream firstbadlink is " +
              firstBadLink);
        }
        Text.writeString(replyOut, firstBadLink);
        replyOut.flush();
      }

      // receive the block and mirror to the next target
      String mirrorAddr = (mirrorSock == null) ? null : mirrorNode;
      long totalReceiveSize = blockReceiver.receiveBlock(mirrorOut, mirrorIn, replyOut,
          mirrorAddr, null, targets.length);

      // if this write is for a replication request (and not
      // from a client), then confirm block. For client-writes,
      // the block is finalized in the PacketResponder.
      if (client.length() == 0) {
        datanode.notifyNamenodeReceivedBlock(namespaceid, block, null);
        LOG.info("Received block " + block + 
            " src: " + remoteAddress +
            " dest: " + localAddress +
            " of size " + block.getNumBytes());
      } else {
        // Log the fact that the block has been received by this datanode and
        // has been written to the local disk on this datanode.
        LOG.info("Received Block " + block +
            " src: " + remoteAddress +
            " dest: " + localAddress +
            " of size " + block.getNumBytes() +
            " and written to local disk");
      }

      if (datanode.blockScanner != null) {
        datanode.blockScanner.deleteBlock(namespaceid, oldBlock);
        datanode.blockScanner.addBlock(namespaceid, block);
      }

      long writeDuration = System.currentTimeMillis() - startTime;
      datanode.myMetrics.bytesWrittenLatency.inc(writeDuration);
      if (totalReceiveSize > KB_RIGHT_SHIFT_MIN) {
        datanode.myMetrics.bytesWrittenRate.inc((int) (totalReceiveSize >> KB_RIGHT_SHIFT_BITS),
            writeDuration);
      }

    } catch (IOException ioe) {
      LOG.info("appendBlock " + block + " received exception " + ioe);
      throw ioe;
    } finally {
      // close all opened streams
      IOUtils.closeStream(mirrorOut);
      IOUtils.closeStream(mirrorIn);
      IOUtils.closeStream(replyOut);
      IOUtils.closeSocket(mirrorSock);
      IOUtils.closeStream(blockReceiver);
    }
  }
  
  
  /**
   * Write a block to disk.
   * 
   * @param in The stream to read from
   * @throws IOException
   */
  private void writeBlock(DataInputStream in,
      VersionAndOpcode versionAndOpcode) throws IOException {
    DatanodeInfo srcDataNode = null;
    if (LOG.isDebugEnabled()) {
      LOG.debug("writeBlock receive buf size " + s.getReceiveBufferSize() +
                " tcp no delay " + s.getTcpNoDelay());
    }
    //
    // Read in the header
    //
    long startTime = System.currentTimeMillis();
    
    WriteBlockHeader headerToReceive = new WriteBlockHeader(
        versionAndOpcode);
    headerToReceive.readFields(in);

    WriteOptions options = headerToReceive.getWritePipelineInfo()
        .getWriteOptions();
    boolean ioprioEnabled = !options.isIoprioDisabled();
    if (ioprioEnabled) {
      NativeIO.ioprioSetIfPossible(options.getIoprioClass(),
          options.getIoprioData());
    }

    int namespaceid = headerToReceive.getNamespaceId();
	DataNode.LOG.info("DataXceiver.writeBlock.namespaceid = " + namespaceid);
    Block block = new Block(headerToReceive.getBlockId(), 
        dataXceiverServer.estimateBlockSize, headerToReceive.getGenStamp());
    if (LOG.isInfoEnabled()) {
      if (remoteAddress == null) {
        getAddresses();
      }
      LOG.info("Receiving block " + block + 
               " src: " + remoteAddress +
               " dest: " + localAddress);
    }
    int pipelineSize = headerToReceive.getPipelineDepth(); // num of datanodes in entire pipeline
    boolean isRecovery = headerToReceive.isRecoveryFlag(); // is this part of recovery?
    String client = headerToReceive.getWritePipelineInfo().getClientName(); // working on behalf of this client
    boolean hasSrcDataNode = headerToReceive.getWritePipelineInfo()
        .hasSrcDataNode(); // is src node info present
    if (hasSrcDataNode) {
      srcDataNode = headerToReceive.getWritePipelineInfo().getSrcDataNode();
    }
    int numTargets = headerToReceive.getWritePipelineInfo().getNumTargets();
    DatanodeInfo targets[] = headerToReceive.getWritePipelineInfo().getNodes();
    int fadvise = headerToReceive.getWritePipelineInfo().getWriteOptions()
        .getFadvise();

    DataOutputStream mirrorOut = null;  // stream to next target
    DataInputStream mirrorIn = null;    // reply from next target
    DataOutputStream replyOut = null;   // stream to prev target
    Socket mirrorSock = null;           // socket to next target
    BlockReceiver blockReceiver = null; // responsible for data handling
    String mirrorNode = null;           // the name:port of next target
    String firstBadLink = "";           // first datanode that failed in connection setup

    updateCurrentThreadName("receiving block " + block + " client=" + client);
    InjectionHandler.processEvent(InjectionEvent.DATANODE_WRITE_BLOCK);
    try {
      boolean ifProfileEnabled = headerToReceive.getWritePipelineInfo()
          .getWriteOptions().ifProfileEnabled();
      boolean isSecondary = (targets.length + 1 != pipelineSize);
      // open a block receiver and check if the block does not exist
      blockReceiver = new BlockReceiver(namespaceid, block, block, in, 
          s.getRemoteSocketAddress().toString(),
          s.getLocalSocketAddress().toString(),
          isRecovery, client, srcDataNode, datanode, isSecondary, fadvise,
          ifProfileEnabled, versionAndOpcode.getDataTransferVersion() >=
            DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
            options.getSyncFileRange());

      // get a connection back to the previous target
      replyOut = new DataOutputStream(new BufferedOutputStream(
                     NetUtils.getOutputStream(s, datanode.socketWriteTimeout),
                     SMALL_BUFFER_SIZE));

      //
      // Open network conn to backup machine, if 
      // appropriate
      //
      if (targets.length > 0) {
        InetSocketAddress mirrorTarget = null;
        // Connect to backup machine
        mirrorNode = targets[0].getName();
        mirrorTarget = NetUtils.createSocketAddr(mirrorNode);
        mirrorSock = datanode.newSocket();
        try {
          int timeoutValue = datanode.socketTimeout +
                             (datanode.socketReadExtentionTimeout * numTargets);
          int writeTimeout = datanode.socketWriteTimeout + 
                             (datanode.socketWriteExtentionTimeout * numTargets);
          NetUtils.connect(mirrorSock, mirrorTarget, timeoutValue);
          mirrorSock.setSoTimeout(timeoutValue);
          mirrorSock.setSendBufferSize(DEFAULT_DATA_SOCKET_SIZE);
          mirrorOut = new DataOutputStream(
             new BufferedOutputStream(
                         NetUtils.getOutputStream(mirrorSock, writeTimeout),
                         SMALL_BUFFER_SIZE));
          mirrorIn = new DataInputStream(NetUtils.getInputStream(mirrorSock));

          // Write header: Copied from DFSClient.java!
          WriteBlockHeader headerToSend = new WriteBlockHeader(
              versionAndOpcode.getDataTransferVersion(), namespaceid,
              block.getBlockId(), block.getGenerationStamp(), pipelineSize,
              isRecovery, hasSrcDataNode, srcDataNode, targets.length - 1, targets,
              client);
          headerToSend.getWritePipelineInfo().setWriteOptions(options);
          headerToSend.writeVersionAndOpCode(mirrorOut);
          headerToSend.write(mirrorOut);
          blockReceiver.writeChecksumHeader(mirrorOut);
          mirrorOut.flush();

          // read connect ack (only for clients, not for replication req)
          if (client.length() != 0) {
            firstBadLink = Text.readString(mirrorIn);
            if (LOG.isDebugEnabled() || firstBadLink.length() > 0) {
              LOG.info("Datanode " + targets.length +
                       " got response for connect ack " +
                       " from downstream datanode with firstbadlink as " +
                       firstBadLink);
            }
          }

        } catch (IOException e) {
          if (client.length() != 0) {
            Text.writeString(replyOut, mirrorNode);
            replyOut.flush();
          }
          IOUtils.closeStream(mirrorOut);
          mirrorOut = null;
          IOUtils.closeStream(mirrorIn);
          mirrorIn = null;
          IOUtils.closeSocket(mirrorSock);
          mirrorSock = null;
          if (client.length() > 0) {
            throw e;
          } else {
            LOG.info(datanode.getDatanodeInfo() + ":Exception transfering block " +
                     block + " to mirror " + mirrorNode +
                     ". continuing without the mirror.\n" +
                     StringUtils.stringifyException(e));
          }
        }
      }

      // send connect ack back to source (only for clients)
      if (client.length() != 0) {
        if (LOG.isDebugEnabled() || firstBadLink.length() > 0) {
          LOG.info("Datanode " + targets.length +
                   " forwarding connect ack to upstream firstbadlink is " +
                   firstBadLink);
        }
        Text.writeString(replyOut, firstBadLink);
        replyOut.flush();
      }

      // receive the block and mirror to the next target
      String mirrorAddr = (mirrorSock == null) ? null : mirrorNode;
      long totalReceiveSize = blockReceiver.receiveBlock(mirrorOut, mirrorIn, replyOut,
                                 mirrorAddr, null, targets.length);

      // if this write is for a replication request (and not
      // from a client), then confirm block. For client-writes,
      // the block is finalized in the PacketResponder.
      if (client.length() == 0) {
        datanode.notifyNamenodeReceivedBlock(namespaceid, block, null);
        LOG.info("Received block " + block + 
                 " src: " + remoteAddress +
                 " dest: " + localAddress +
                 " of size " + block.getNumBytes());
      } else {
        // Log the fact that the block has been received by this datanode and
        // has been written to the local disk on this datanode.
        LOG.info("Received Block " + block +
            " src: " + remoteAddress +
            " dest: " + localAddress +
            " of size " + block.getNumBytes() +
            " and written to local disk");
      }

      if (datanode.blockScanner != null) {
        datanode.blockScanner.addBlock(namespaceid, block);
      }
      
      long writeDuration = System.currentTimeMillis() - startTime;
      datanode.myMetrics.bytesWrittenLatency.inc(writeDuration);
      if (totalReceiveSize > KB_RIGHT_SHIFT_MIN) {
        datanode.myMetrics.bytesWrittenRate.inc((int) (totalReceiveSize >> KB_RIGHT_SHIFT_BITS),
                              writeDuration);
      }

    } catch (IOException ioe) {
      LOG.info("WriteBlock: Got exception while receiving block: " + block + 
                " from " + s.getInetAddress() + ", client: " + client + ":\n" + 
                StringUtils.stringifyException(ioe) );
      throw ioe;
    } finally {
      // close all opened streams
      IOUtils.closeStream(mirrorOut);
      IOUtils.closeStream(mirrorIn);
      IOUtils.closeStream(replyOut);
      IOUtils.closeSocket(mirrorSock);
      IOUtils.closeStream(blockReceiver);
    }
  }

  /**
   * Reads the metadata and sends the data in one 'DATA_CHUNK'.
   * @param in
   */
  void readMetadata(DataInputStream in, VersionAndOpcode versionAndOpcode)
      throws IOException {
    ReadMetadataHeader readMetadataHeader = 
        new ReadMetadataHeader(versionAndOpcode);
    readMetadataHeader.readFields(in);
    final int namespaceId = readMetadataHeader.getNamespaceId();
    Block block = new Block(readMetadataHeader.getBlockId(), 0,
        readMetadataHeader.getGenStamp());
    
    ReplicaToRead rtr;
    if ((rtr = datanode.data.getReplicaToRead(namespaceId, block)) == null
        || rtr.isInlineChecksum()) {
      throw new IOException(
          "Read metadata from inline checksum file is not supported");
    }
    DataOutputStream out = null;
    try {
      updateCurrentThreadName("reading metadata for block " + block);
      
      out = new DataOutputStream(
                NetUtils.getOutputStream(s, datanode.socketWriteTimeout));
      
      byte[] buf = BlockWithChecksumFileReader.getMetaData(datanode.data,
          namespaceId, block);
      out.writeByte(DataTransferProtocol.OP_STATUS_SUCCESS);
      out.writeInt(buf.length);
      out.write(buf);
      
      //last DATA_CHUNK
      out.writeInt(0);
    } finally {
      IOUtils.closeStream(out);
    }
  }
  
  /**
   * Get block data's CRC32 checksum.
   * @param in
   * @param versionAndOpcode
   */
  void getBlockCrc(DataInputStream in, VersionAndOpcode versionAndOpcode)
      throws IOException {
    // header
    BlockChecksumHeader blockChecksumHeader =
        new BlockChecksumHeader(versionAndOpcode);
    blockChecksumHeader.readFields(in);
    final int namespaceId = blockChecksumHeader.getNamespaceId();
    final Block block = new Block(blockChecksumHeader.getBlockId(), 0,
            blockChecksumHeader.getGenStamp());

    DataOutputStream out = null;

    ReplicaToRead ri = datanode.data.getReplicaToRead(namespaceId, block);
    if (ri == null) {
      throw new IOException("Unknown block");
    }

    updateCurrentThreadName("getting CRC checksum for block " + block);

    try {
      //write reply
      out = new DataOutputStream(
          NetUtils.getOutputStream(s, datanode.socketWriteTimeout));
      int blockCrc;
      if (ri.hasBlockCrcInfo()) {
        // There is actually a short window that the block is reopened
        // and we got exception when call getBlockCrc but it's OK. It's
        // only happens for append(). So far we don't optimize for this
        // use case. We can do it later when necessary.
        //
        blockCrc = ri.getBlockCrc();
      } else {
        try {
          if (ri.isInlineChecksum()) {
            blockCrc = BlockInlineChecksumReader.getBlockCrc(datanode, ri,
                namespaceId, block);
          } else {
            blockCrc = BlockWithChecksumFileReader.getBlockCrc(datanode, ri,
                namespaceId, block);
          }
        } catch (IOException ioe) {
          LOG.warn("Exception when getting Block CRC", ioe);
          out.writeShort(DataTransferProtocol.OP_STATUS_ERROR);
          out.flush();
          throw ioe;
        }
      }
      
      out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
      out.writeLong(blockCrc);
      out.flush();
    } finally {
      IOUtils.closeStream(out);
    }
  }

  
  /**
   * Get block checksum (MD5 of CRC32).
   * @param in
   */
  void getBlockChecksum(DataInputStream in,
      VersionAndOpcode versionAndOpcode) throws IOException {
    // header
    BlockChecksumHeader blockChecksumHeader =
        new BlockChecksumHeader(versionAndOpcode);
    blockChecksumHeader.readFields(in);
    final int namespaceId = blockChecksumHeader.getNamespaceId();
    final Block block = new Block(blockChecksumHeader.getBlockId(), 0,
            blockChecksumHeader.getGenStamp());

    DataOutputStream out = null;
    InputStream rawStreamIn = null;
    DataInputStream streamIn = null;

    ReplicaToRead ri = datanode.data.getReplicaToRead(namespaceId, block);
    if (ri == null) {
      throw new IOException("Unknown block");
    }

    updateCurrentThreadName("getting checksum for block " + block);
    try {
      int bytesPerCRC;
      int checksumSize;

      long crcPerBlock;
      MD5Hash md5;
      if (!ri.isInlineChecksum()) {
        rawStreamIn = BlockWithChecksumFileReader.getMetaDataInputStream(
            datanode.data, namespaceId, block);
        streamIn = new DataInputStream(new BufferedInputStream(rawStreamIn,
            BUFFER_SIZE));

        final BlockMetadataHeader header = BlockMetadataHeader
            .readHeader(streamIn);
        final DataChecksum checksum = header.getChecksum();
        bytesPerCRC = checksum.getBytesPerChecksum();
        checksumSize = checksum.getChecksumSize();
        crcPerBlock = (((BlockWithChecksumFileReader.MetaDataInputStream) rawStreamIn)
            .getLength() - BlockMetadataHeader.getHeaderSize()) / checksumSize;

       //compute block checksum
       md5 = MD5Hash.digest(streamIn);
      } else {
        bytesPerCRC = ri.getBytesPerChecksum();
        checksumSize = DataChecksum.getChecksumSizeByType(ri.getChecksumType());
        ReplicaToRead replica = datanode.data.getReplicaToRead(namespaceId, block);
        rawStreamIn = replica.getBlockInputStream(datanode, 0);
        streamIn = new DataInputStream(new BufferedInputStream(rawStreamIn,
            BUFFER_SIZE));

        long lengthLeft = ((FileInputStream) rawStreamIn).getChannel().size()
            - BlockInlineChecksumReader.getHeaderSize();
        if (lengthLeft == 0) {
          crcPerBlock = 0;
          md5 = MD5Hash.digest(new byte[0]);
        } else {
          crcPerBlock = (lengthLeft - 1) / (checksumSize + bytesPerCRC) + 1;
          MessageDigest digester = MD5Hash.getDigester();
          byte[] buffer = new byte[checksumSize];
          while (lengthLeft > 0) {
            if (lengthLeft >= bytesPerCRC + checksumSize) {
              streamIn.skip(bytesPerCRC);
              IOUtils.readFully(streamIn, buffer, 0, buffer.length);
              lengthLeft -= bytesPerCRC
                  + checksumSize;
            } else if (lengthLeft > checksumSize) {
              streamIn.skip(lengthLeft - checksumSize);
              IOUtils.readFully(streamIn, buffer, 0, buffer.length);
              lengthLeft = 0;
            } else {
              out = new DataOutputStream(
                  NetUtils.getOutputStream(s, datanode.socketWriteTimeout));
              out.writeShort(DataTransferProtocol.OP_STATUS_ERROR);
              out.flush();
              // report to name node the corruption.
              DataBlockScanner.reportBadBlocks(block, namespaceId, datanode);
              throw new IOException("File for namespace " + namespaceId
                  + " block " + block + " seems to be corrupted");
            }
            digester.update(buffer);
          }
          md5 = new MD5Hash(digester.digest(), checksumSize * crcPerBlock);
        }
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("block=" + block + ", bytesPerCRC=" + bytesPerCRC
            + ", crcPerBlock=" + crcPerBlock + ", md5=" + md5);
      }

      //write reply
      out = new DataOutputStream(
          NetUtils.getOutputStream(s, datanode.socketWriteTimeout));
      out.writeShort(DataTransferProtocol.OP_STATUS_SUCCESS);
      out.writeInt(bytesPerCRC);
      out.writeLong(crcPerBlock);
      md5.write(out);
      out.flush();
    } finally {
      IOUtils.closeStream(out);
      if (streamIn != null) {
        IOUtils.closeStream(streamIn);
      }
      if (rawStreamIn != null) {
        IOUtils.closeStream(rawStreamIn);
      }
    }
  }

  /**
   * Read a block from the disk and then sends it to a destination.
   * 
   * @param in The stream to read from
   * @throws IOException
   */
  private void copyBlock(DataInputStream in,
      VersionAndOpcode versionAndOpcode) throws IOException {
    
    // Read in the header
    CopyBlockHeader copyBlockHeader = new CopyBlockHeader(versionAndOpcode);
    copyBlockHeader.readFields(in);
    long startTime = System.currentTimeMillis();
    int namespaceId = copyBlockHeader.getNamespaceId();
    long blockId = copyBlockHeader.getBlockId();
    long genStamp = copyBlockHeader.getGenStamp();
    Block block = new Block(blockId, 0, genStamp);

    if (!dataXceiverServer.balanceThrottler.acquire()) { // not able to start
      LOG.info("Not able to copy block " + blockId + " to " 
          + s.getRemoteSocketAddress() + " because threads quota is exceeded.");
      return;
    }

    BlockSender blockSender = null;
    DataOutputStream reply = null;
    boolean isOpSuccess = true;
    updateCurrentThreadName("Copying block " + block);
    try {
      // check if the block exists or not
      blockSender = new BlockSender(namespaceId, block, 0, -1, false, false, false,
          false,
          versionAndOpcode.getDataTransferVersion() >=
              DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION, true,
          datanode, null);

      // set up response stream
      OutputStream baseStream = NetUtils.getOutputStream(
          s, datanode.socketWriteTimeout);
      reply = new DataOutputStream(new BufferedOutputStream(
          baseStream, SMALL_BUFFER_SIZE));

      // send block content to the target
      long read = blockSender.sendBlock(reply, baseStream, 
                                        dataXceiverServer.balanceThrottler);

      long readDuration = System.currentTimeMillis() - startTime;
      datanode.myMetrics.bytesReadLatency.inc(readDuration);
      datanode.myMetrics.bytesRead.inc((int) read);
      if (read > KB_RIGHT_SHIFT_MIN) {
        datanode.myMetrics.bytesReadRate.inc((int) (read >> KB_RIGHT_SHIFT_BITS),
                          readDuration);
      }
      datanode.myMetrics.blocksRead.inc();
      
      LOG.info("Copied block " + block + " to " + s.getRemoteSocketAddress());
    } catch (IOException ioe) {
      isOpSuccess = false;
      throw ioe;
    } finally {
      dataXceiverServer.balanceThrottler.release();
      if (isOpSuccess) {
        try {
          // send one last byte to indicate that the resource is cleaned.
          reply.writeChar('d');
        } catch (IOException ignored) {
        }
      }
      IOUtils.closeStream(reply);
      IOUtils.closeStream(blockSender);
    }
  }

  /**
   * Receive a block and write it to disk, it then notifies the namenode to
   * remove the copy from the source.
   * 
   * @param in The stream to read from
   * @throws IOException
   */
  private void replaceBlock(DataInputStream in,
      VersionAndOpcode versionAndOpcode) throws IOException {
    long startTime = System.currentTimeMillis();
    ReplaceBlockHeader replaceBlockHeader = 
        new ReplaceBlockHeader(versionAndOpcode);
    
    /* read header */
    replaceBlockHeader.readFields(in);
    int namespaceId = replaceBlockHeader.getNamespaceId();
    long blockId = replaceBlockHeader.getBlockId();
    long genStamp = replaceBlockHeader.getGenStamp();
    Block block = new Block(blockId, dataXceiverServer.estimateBlockSize,
        genStamp);
    String sourceID = replaceBlockHeader.getSourceID();
    DatanodeInfo proxySource = replaceBlockHeader.getProxySource();

    if (!dataXceiverServer.balanceThrottler.acquire()) { // not able to start
      LOG.warn("Not able to receive block " + blockId + " from " 
          + s.getRemoteSocketAddress() + " because threads quota is exceeded.");
      sendResponse(s, (short)DataTransferProtocol.OP_STATUS_ERROR, 
          datanode.socketWriteTimeout);
      return;
    }

    Socket proxySock = null;
    DataOutputStream proxyOut = null;
    short opStatus = DataTransferProtocol.OP_STATUS_SUCCESS;
    BlockReceiver blockReceiver = null;
    DataInputStream proxyReply = null;
    long totalReceiveSize = 0;
    long writeDuration;
    
    updateCurrentThreadName("replacing block " + block + " from " + sourceID);
    try {
      // get the output stream to the proxy
      InetSocketAddress proxyAddr = NetUtils.createSocketAddr(
          proxySource.getName());
      proxySock = datanode.newSocket();
      NetUtils.connect(proxySock, proxyAddr, datanode.socketTimeout);
      proxySock.setSoTimeout(datanode.socketTimeout);

      OutputStream baseStream = NetUtils.getOutputStream(proxySock, 
          datanode.socketWriteTimeout);
      proxyOut = new DataOutputStream(
                     new BufferedOutputStream(baseStream, SMALL_BUFFER_SIZE));

      /* send request to the proxy */
      CopyBlockHeader copyBlockHeader = new CopyBlockHeader(
          DataTransferProtocol.DATA_TRANSFER_VERSION, namespaceId,
          block.getBlockId(), block.getGenerationStamp());
      copyBlockHeader.writeVersionAndOpCode(proxyOut);
      copyBlockHeader.write(proxyOut);
      proxyOut.flush();

      // receive the response from the proxy
      proxyReply = new DataInputStream(new BufferedInputStream(
          NetUtils.getInputStream(proxySock), BUFFER_SIZE));

      // open a block receiver and check if the block does not exist
      blockReceiver = new BlockReceiver(namespaceId, block,
          block, proxyReply, proxySock.getRemoteSocketAddress().toString(),
          proxySock.getLocalSocketAddress().toString(),
          false, "", null, datanode, true, 0, false,
          versionAndOpcode.getDataTransferVersion() >=
            DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
            WriteOptions.SYNC_FILE_RANGE_DEFAULT);

      // receive a block
      totalReceiveSize = blockReceiver.receiveBlock(null, null, null, null,
          dataXceiverServer.balanceThrottler, -1);

      // notify name node
      datanode.notifyNamenodeReceivedBlock(namespaceId, block, sourceID);

      writeDuration = System.currentTimeMillis() - startTime;
      datanode.myMetrics.bytesWrittenLatency.inc(writeDuration);
      if (totalReceiveSize > KB_RIGHT_SHIFT_MIN) {
        datanode.myMetrics.bytesWrittenRate.inc((int) (totalReceiveSize >> KB_RIGHT_SHIFT_BITS),
                                  writeDuration);
      }

      LOG.info("Moved block " + block + 
          " from " + s.getRemoteSocketAddress());
      
    } catch (IOException ioe) {
      opStatus = DataTransferProtocol.OP_STATUS_ERROR;
      throw ioe;
    } finally {
      // receive the last byte that indicates the proxy released its thread resource
      if (opStatus == DataTransferProtocol.OP_STATUS_SUCCESS) {
        try {
          proxyReply.readChar();
        } catch (IOException ignored) {
        }
      }
      
      // now release the thread resource
      dataXceiverServer.balanceThrottler.release();
      
      // send response back
      try {
        sendResponse(s, opStatus, datanode.socketWriteTimeout);
      } catch (IOException ioe) {
        LOG.warn("Error writing reply back to " + s.getRemoteSocketAddress());
      }
      IOUtils.closeStream(proxyOut);
      IOUtils.closeStream(blockReceiver);
      IOUtils.closeStream(proxyReply);
    }
  }
  
  /**
   * Utility function for sending a response.
   * @param s socket to write to
   * @param opStatus status message to write
   * @param timeout send timeout
   **/
  private void sendResponse(Socket s, short opStatus, long timeout) 
                                                       throws IOException {
    DataOutputStream reply = 
      new DataOutputStream(NetUtils.getOutputStream(s, timeout));
    
    reply.writeShort(opStatus);
    reply.flush();
  }

  /**
   * Read a block from the disk, the emphasis in on speed baby, speed!
   * The focus is to decrease the number of system calls issued to satisfy
   * this read request.
   * @param in The stream to read from
   *
   * Input  4 bytes: namespace id
   *        8 bytes: block id
   *        8 bytes: genstamp
   *        8 bytes: startOffset
   *        8 bytes: length of data to read
   *        n bytes: clientName as a string
   * Output 1 bytes: checksum type
   *         4 bytes: bytes per checksum
   *        -stream of checksum values for all data
   *        -stream of data starting from the previous alignment of startOffset
   *         with bytesPerChecksum
   * @throws IOException
   */
  private void readBlockAccelerator(DataInputStream in, 
      VersionAndOpcode versionAndOpcode) throws IOException {
    //
    // Read in the header
    //
    int namespaceId = in.readInt();
    long blockId = in.readLong();          
    long generationStamp = in.readLong();          
    long startOffset = in.readLong();
    long length = in.readLong();
    String clientName = Text.readString(in);
    if (LOG.isDebugEnabled()) {
      LOG.debug("readBlockAccelerator blkid = " + blockId + 
                " offset " + startOffset + " length " + length);
    }

    long startTime = System.currentTimeMillis();
    Block block = new Block( blockId, 0 , generationStamp);
    // TODO: support inline checksum
    ReplicaToRead ri = datanode.data.getReplicaToRead(namespaceId, block);

    File dataFile = datanode.data.getBlockFile(namespaceId, block);

    long read = -1;
    try {
      if (ri.isInlineChecksum()) {
        read = BlockInlineChecksumReader.readBlockAccelerator(s, ri, dataFile,
            block, startOffset, length, datanode);
      } else {
        read =  BlockWithChecksumFileReader.readBlockAccelerator(s, dataFile,
            block, startOffset, length, datanode);
      }
    }
    finally {
      if (read != -1) {
        long readDuration = System.currentTimeMillis() - startTime;
        datanode.myMetrics.bytesReadLatency.inc(readDuration);
        datanode.myMetrics.bytesRead.inc((int) read);
        if (read > KB_RIGHT_SHIFT_MIN) {
          datanode.myMetrics.bytesReadRate.inc(
              (int) (read >> KB_RIGHT_SHIFT_BITS), readDuration);
        }
        datanode.myMetrics.blocksRead.inc();
      }
    }
  }
}
