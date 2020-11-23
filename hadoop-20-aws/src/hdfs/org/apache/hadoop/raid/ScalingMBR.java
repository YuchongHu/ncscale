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

package org.apache.hadoop.raid;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sun.org.apache.xml.internal.security.Init;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.ObjectUtils.Null;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.GroupInputStream;
import org.apache.hadoop.hdfs.ReadStreamInfo;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.protocol.ScalingDeltaTransferHeader;
import org.apache.hadoop.hdfs.protocol.ScalingHeader;
import org.apache.hadoop.hdfs.protocol.VersionAndOpcode;
import org.apache.hadoop.hdfs.protocol.WriteBlockHeader;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.hdfs.server.datanode.BlockDataFile;
import org.apache.hadoop.hdfs.server.datanode.BlockSender;
import org.apache.hadoop.hdfs.server.datanode.BlockWithChecksumFileReader;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.FSDataset;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.raid.RaidUtils;
import org.apache.hadoop.raid.RaidNode.ScalingOperation;
import org.apache.hadoop.raid.RaidNode.ScalingThread;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.NativeDRC;
import org.apache.hadoop.util.NativeNCScale;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;


public class ScalingMBR {
    public static final Log LOG = LogFactory.getLog(
            "ScalingAlgorithmic.class");
    protected Configuration conf;
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks = null;
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks = null;
    private int blockSize;
    private DFSClient dfsClient;
    DistributedFileSystem fs;
    Codec codec;
    String localNode;
    private int localNodeID;
    Path path;
    private int drcK;
    private int drcS;
    private int drcN;
    private int nodeNum;
    private String[] racks;
    private String[] nodesName;
    private int numCompute;//the num of update operation or the num of compute delta parity. 
    private int startBlockIDInDG;
    private int endBlockID;
    Map<Integer, byte[][]> deltaParity;  //The value: delta byte[computeID][blockSize] will transfer to the key: node. 
    									 //Here, 0 presents localNode. 1 is localNode+1. 
    Map<Integer, byte[][]> deltaParityBuffer;//this map restore the results of each compute operation, and will put in MAP: deltaParity.
    
    public ScalingMBR(Configuration conf, String localNode,
    		Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks,
    		Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks, String[] nodesName) 
        throws IOException {       
        this.conf = conf;
        this.localNode = localNode;
        this.dataBlocks = dataBlocks;
        this.parityBlocks = parityBlocks;
        this.nodesName = nodesName;
   	  	init();	  	
    }
    
    public ScalingMBR(Configuration conf, String localNode, String[] nodesName) 
        throws IOException {       
        this.conf = conf;
        this.localNode = localNode;
        this.nodesName = nodesName;
   	  	init();	  	
    }

    // init the native lib for scaling compute.
    public void init() throws IOException {
        this.blockSize = conf.getInt("dfs.block.size", 1048576);
        path = new Path("/user/hadoop/raidTest/input");
        this.fs = getDFS(path, conf);
        String codeid = new String("drc");
        this.dfsClient = fs.getClient();
    	this.codec = Codec.getCodec(codeid);
    	this.drcK = codec.stripeLength;
    	this.drcS = codec.newNode;
    	this.drcN = codec.stripeLength + codec.parityLength;
    	this.nodeNum = codec.nodeNum;
   	  	this.racks = fs.getRacks();
   	  	this.numCompute = drcK*(drcN+drcS);
   	  	this.startBlockIDInDG = drcK*drcK*(drcN+drcS);
   	  	this.endBlockID = drcK*(drcN+drcS)*(drcK+drcS);
   	  	this.deltaParity = new HashMap<Integer, byte[][]>();
   	  	this.deltaParityBuffer = new HashMap<Integer, byte[][]>();
//   	 System.out.println("ScalingAlgorithmic.init.Local nodename: "+localNode);
   	  	for(int i = 0; i < racks.length; i++)
   	  	{ 
//   	  		System.out.println("ScalingAlgorithmic.init.nodename: "+fs.getDataNodeByRackName(racks[i]));
   	  		if(fs.getDataNodeByRackName(racks[i]).equals(localNode))
   	  		{
   	  			localNodeID = i;
//   	  			System.out.println("ScalingAlgorithmic.init.localNodeId: "+localNodeID);
   	  			break;
   	  		}
   	  	}
        try {
          System.loadLibrary("ncscale");
          LOG.info("load NCScale scaling library");
        } catch(Throwable t) {
          LOG.info("fail to load scaling with error: " + t);
          LOG.info("java.library.path = " + System.getProperty("java.library.path"));
        }

        if(NativeNCScale.isAvailable()) {
          if(!NativeNCScale.nativeInit(this.drcN, this.drcK, this.drcS, this.blockSize)) {
            LOG.info("fail to init native scaling cumpute operation.");
          }
        } else {
          LOG.info("can not use native C implemention of scaling cumpute operation.");
        }
          
      }
    
    public DistributedFileSystem getDFS(Path p, Configuration conf) throws IOException {
        FileSystem fs = p.getFileSystem(conf);
        DistributedFileSystem dfs = null;
        if (fs instanceof DistributedFileSystem) {
            dfs = (DistributedFileSystem) fs;
        } else if (fs instanceof FilterFileSystem) {
            FilterFileSystem ffs = (FilterFileSystem) fs;
            if (ffs.getRawFileSystem() instanceof DistributedFileSystem) {
                dfs = (DistributedFileSystem) ffs.getRawFileSystem();
            }
        }
        return dfs;
    }
    
    public void computeDelta() throws IOException {
    	LOG.info("start computeDelta");
    	Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
    	dataBlocksInNode = dataBlocks.get(localNode);
    	Map<Integer, byte[][]> blocks = new HashMap<Integer, byte[][]>();
    	
    	if(drcS == 1)
    	{
    		byte[][] dataBuffer = new byte[numCompute][];
    		for(int i = 0; i < numCompute; i++)
        	{
        		dataBuffer[i] = new byte[blockSize];
        	}
    		int index = 0;
    		for(int i = startBlockIDInDG; i < endBlockID; i++)
    		{
     			LocatedBlockWithFileName lb = dataBlocksInNode.get(i);
//     			Path filePath = new Path(lb.getFileName());
                long offset = lb.getStartOffset();
                
                FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
                input.read(offset, dataBuffer[index], 0, blockSize);
                index++;
    		}
    		deltaParity.put(0, dataBuffer);
//    		System.out.println("End computeDelta, deltaParity.length is: "+deltaParity.get(0).length);
    	}
    	else{
    		int index = 0;
	    	for(int i = startBlockIDInDG; i < endBlockID; i++)
	        {
	        	byte[][] dataBuffer = new byte[drcS][];
	        	for(int j = 0; j < drcS; j++)
	        	{
	        		dataBuffer[j] = new byte[blockSize];
	        	}
	        	
	     		for(int j = 0; j < drcS; j++)
	    		{
	     			LocatedBlockWithFileName lb = dataBlocksInNode.get(i);
//	     			Path filePath = new Path(lb.getFileName());
	                long offset = lb.getStartOffset();
	                
	                FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
	                input.read(offset, dataBuffer[j], 0, blockSize);
	    		}
	    		blocks.put(index, dataBuffer);
	    		index++;
	        }
	    	 
	     	ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-computeDelta-pool-%d").build();
	        ExecutorService computePool = Executors.newFixedThreadPool(numCompute, computeFactory);
	        Semaphore slots = new Semaphore(numCompute);
	    	for(int i = 0; i < numCompute;)
	    	{
	            try {
	                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
	                if(acquired) {
	                	computePool.execute(new computeOperation(i, slots, blocks.get(i)));
	                    i++;
	                }
	            } catch(Exception e) {
	          	  LOG.info("ScalingAlgorithmic.computeDelta.run stream " + i + " for Exception");
	            }
	        }
	
	        // should wait for all the stream read ready
	        while(true) {
	            try {
	                boolean acquired = slots.tryAcquire(numCompute, 100, TimeUnit.MINUTES);
	                if(acquired) {
	                    slots.release(numCompute);
	                    break;
	                }
	            } catch(Exception e) {
	          	  LOG.info("ScalingAlgorithmic.computeDelta.run.while Exception");
	            }
	        }
	        computePool.shutdown();  
	       
	        //put MAP: deltaParityBuffer to MAP: deltaParity
	        for(int i = 0; i < (drcN-drcK); i++)
	        {
            	byte[][] deltaBuffer = new byte[numCompute][];
        		for(int j = 0; j < numCompute; j++)
        		{
        			deltaBuffer[j] = deltaParityBuffer.get(j)[i];
        		}
        		deltaParity.put(i, deltaBuffer);
	        }
    	}
    }
    	 
    public void localUpdate(byte[][] transferredDelta) throws IOException {
    	LOG.info("start local update.");
    	byte[][] delta = new byte[numCompute][];
  	  	
    	if(transferredDelta == null)//this is for local update.
    	{	
    		delta = deltaParity.get(0);
    	}
    	else//this is for remote update. And transferredDelta.length must be numCompute.
    	{
    		delta = transferredDelta;
    	}
    	
    	byte[][] parity = new byte[numCompute][];
    	byte[][] newParity = new byte[numCompute][];//these are updated parity blocks
    	for(int i = 0; i < numCompute; i++)
    	{
    		parity[i] = new byte[blockSize];
    		newParity[i] = new byte[blockSize];
    	}
        Map<Integer, LocatedBlockWithFileName> ParityInfo = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
        getUpdatedParityBlocksInPG(parity, ParityInfo);
    	
//    	System.out.println("finish read parity, begin parity update");
     	ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
        ExecutorService updatePool = Executors.newFixedThreadPool(numCompute, computeFactory);
        Semaphore slots = new Semaphore(numCompute);
    	for(int i = 0; i < numCompute;)
    	{
            try {
                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	updatePool.execute(new updateOperation(i, slots, parity[i], delta[i], newParity[i]));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.localUpdate.run stream " + i + " for Exception");
            }
        }

        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots.tryAcquire(numCompute, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(numCompute);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.localUpdate.run.while Exception");
            }
        }
        updatePool.shutdown();  
        
        //write these updated parity blocks into HDFS.
//        System.out.println("not changed? "+parity.equals(newParity));
//        System.out.println("finish parity update, start write hdfs");
        writeHDFS(localNode, newParity, ParityInfo, true);
    } 
    
    public void transferToNewNodes() throws IOException {
    	LOG.info("start transfer to new nodes.");
    	Map<String, byte[][]> transfer = 
				 new HashMap<String, byte[][]>();
		Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
		dataBlocksInNode = dataBlocks.get(localNode);   
		Map<String, Map<Integer, LocatedBlockWithFileName>> transferInfo = 
				new HashMap<String, Map<Integer, LocatedBlockWithFileName>>();
		Map<Integer, LocatedBlockWithFileName> parityInfo = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
		
		byte[][] parity = new byte[numCompute][];
    	for(int i = 0; i < numCompute; i++)
    	{
    		parity[i] = new byte[blockSize];
    	}
    	getUpdatedParityBlocksInPG(parity, parityInfo);
		
    	int line = drcK*(drcN-drcS*(drcN-drcK-1));
    	for(int i = 0; i < drcS; i++)
    	{
    		String newNode = fs.getDataNodeByRackName(racks[drcN+i]);
    		byte[][] transferANode = new byte[numCompute][];
    		Map<Integer, LocatedBlockWithFileName> transferInfoForANode = 
   				 new HashMap<Integer, LocatedBlockWithFileName>();
        	for(int j = 0; j < numCompute; j++)
        	{
        		transferANode[j] = new byte[blockSize];
        	}
        	
     		for(int j = 0; j < numCompute; j++)
    		{
     			if(i == 0 && j >= line)
     			{
     				transferANode[j] = parity[j];
     				transferInfoForANode.put(j, parityInfo.get(j));
//     				System.out.println("transferToNewNodes.ParityID: "+ j);
     			}
     			else
     			{
         			LocatedBlockWithFileName lb = dataBlocksInNode.get(startBlockIDInDG+drcS*j+i);
                    long offset = lb.getStartOffset();
                    
                    FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
                    input.read(offset, transferANode[j], 0, blockSize);
                    transferInfoForANode.put(j, lb);
     			}
    		}
     		transfer.put(newNode, transferANode);
     		transferInfo.put(newNode, transferInfoForANode);
    	}
//    	System.out.println("finish read transfer, begin exchange");
    	//Exchange the location of some blocks to balance the number of parity blocks in new Node.
    	String firstNewNode = fs.getDataNodeByRackName(racks[drcN]);
    	byte[][] transferToFirstNewNode = transfer.get(firstNewNode);
    	Map<Integer, LocatedBlockWithFileName> transferInfoForFirstNewNode = 
				transferInfo.get(firstNewNode);
    	byte[] tmpBuffer = new byte[blockSize];
    	LocatedBlockWithFileName lbBuffer = new LocatedBlockWithFileName();
    	for(int i = 1; i < drcS; i++)
    	{
    		String NewNode = fs.getDataNodeByRackName(racks[drcN+i]);
    		byte[][] transferBuffer = transfer.get(NewNode);
    		Map<Integer, LocatedBlockWithFileName> transferInfoBuffer = 
    				transferInfo.get(NewNode);
    		for(int j = 0; j < drcK*(drcN-drcK); j++)
    		{
    			int exchangeID = line + i*drcK*(drcN-drcK) + j;
    			//exchange content
    			tmpBuffer = transferToFirstNewNode[exchangeID];
    			transferToFirstNewNode[exchangeID] = transferBuffer[exchangeID];
    			transferBuffer[exchangeID] = tmpBuffer;
    			//exchange Info
    			lbBuffer = transferInfoForFirstNewNode.get(exchangeID);
    			transferInfoForFirstNewNode.replace(exchangeID, transferInfoBuffer.get(exchangeID));
    			transferInfoBuffer.replace(exchangeID, lbBuffer);
//    			System.out.println("transferToNewNodes.exchangeID: "+ exchangeID);
    		}
    	}
//    	System.out.println("finish exchange, begin transfer");
    	//Transfer to new node
     	ThreadFactory transferFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
        ExecutorService transferPool = Executors.newFixedThreadPool(drcS, transferFactory);
        Semaphore slots = new Semaphore(drcS);
    	for(int i = 0; i < drcS;)
    	{
            try {
                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	String newNode = fs.getDataNodeByRackName(racks[drcN+i]);
                	transferPool.execute(new transferOperation(i, slots, newNode, 
                			transfer.get(newNode), transferInfo.get(newNode)));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.localUpdate.run stream " + i + " for Exception");
            }
        }

        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots.tryAcquire(drcS, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(drcS);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.localUpdate.run.while Exception");
            }
        }
        transferPool.shutdown();  
    }
    
    public void transferDeltaToRemoteNode() throws IOException {
    	LOG.info("start trasnfer to remote node.");
    	
    	if(drcN-drcK ==1)
    	{
    		LOG.info("ScalingAlgorithmic.transferDeltaToRemoteNode: Do not need remote update.");
    		return;
    	}
    	
     	ThreadFactory deltaTransferFactory = new ThreadFactoryBuilder().setNameFormat("parallel-transferDelta-pool-%d").build();
        ExecutorService deltaTransferPool = Executors.newFixedThreadPool(drcN-drcK-1, deltaTransferFactory);
        Semaphore slots = new Semaphore(drcN-drcK-1);
    	for(int i = 1; i < drcN-drcK;)
    	{
            try {
                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	deltaTransferPool.execute(new deltaTransferOperation(i, slots, deltaParity.get(i)));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.transferDeltaToRemoteNode.run stream " + i + " for Exception");
            }
        }

        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots.tryAcquire(drcN-drcK-1, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(drcN-drcK-1);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.transferDeltaToRemoteNode.run.while Exception");
            }
        }
        deltaTransferPool.shutdown();  
    }
    
    public void remoteUpdate(int deltaTransferID, Map<Integer, LocatedBlockWithFileName> localParityBlocks, byte[][] delta) throws IOException {
    	LOG.info("start Remote update.");
    	
    	byte[][] parity = new byte[numCompute][];
    	byte[][] newParity = new byte[numCompute][];//these are updated parity blocks
    	for(int i = 0; i < numCompute; i++)
    	{
    		parity[i] = new byte[blockSize];
    		newParity[i] = new byte[blockSize];
    	}
        Map<Integer, LocatedBlockWithFileName> ParityInfo = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
  	  	FileStatus srcStat = fs.getFileStatus(path);
  	  	ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
  	  	Path parityPath = ppair.getPath();
    	DistributedFileSystem parityFs= getDFS(parityPath, conf);
    	//get parity blocks
    	int nodeID = 0;
    	int startID = 0;
    	for(int i = 0; i < racks.length; i++)
    	{
    		if(localNode == fs.getDataNodeByRackName(racks[i]))
    		{
    			nodeID = i;
    			break;
    		}
    	}
    	//choose corresponding parity blocks to update.
    	if(nodeID < (drcN-drcK))
    		startID = nodeID-deltaTransferID;
    	else
    		startID = drcN-drcK-1-deltaTransferID;
    	
    	if(startID < 0)
    		startID += drcN-drcK;
//    	System.out.println("remoteUpdate.startID: "+startID);
//    	System.out.println("parityblocks is null? "+localParityBlocks.equals(null));
    	for(int i = 0; i < parity.length; i++)
        {
     		LocatedBlockWithFileName lb = localParityBlocks.get(startID);
            long offset = lb.getStartOffset();
            
            FSDataInputStream input = parityFs.open(parityPath, conf.getInt("io.file.buffer.size", 64 * 1024));
            input.read(offset, parity[i], 0, blockSize);
            startID += (drcN-drcK);
            ParityInfo.put(i, lb);
        }
        
//    	System.out.println("remoteUpdate.finish read parity, begin parity update");
     	ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
        ExecutorService updatePool = Executors.newFixedThreadPool(numCompute, computeFactory);
        Semaphore slots = new Semaphore(numCompute);
    	for(int i = 0; i < numCompute;)
    	{
            try {
                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	updatePool.execute(new updateOperation(i, slots, parity[i], delta[i], newParity[i]));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.remoteUpdate.run stream " + i + " for Exception");
            }
        }

        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots.tryAcquire(numCompute, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(numCompute);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("ScalingAlgorithmic.remoteUpdate.run.while Exception");
            }
        }
        updatePool.shutdown();  
        
        //write these updated parity blocks into HDFS.
//        System.out.println("not changed? "+parity.equals(newParity));
//        System.out.println("finish parity update, start write hdfs");
        writeHDFS(localNode, newParity, ParityInfo, true);
    } 
    
    //Get parity blocks of PG in local node which are need to be updated. 
    public void getUpdatedParityBlocksInPG(byte[][] parity,  Map<Integer, LocatedBlockWithFileName> ParityInfo) throws IOException {
    	Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
    	parityBlocksInNode = parityBlocks.get(localNode);
  	  	FileStatus srcStat = fs.getFileStatus(path);
  	  	ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
  	  	Path parityPath = ppair.getPath();
    	DistributedFileSystem parityFs= getDFS(parityPath, conf);

    	int nodeID = 0;
    	int startID = 0;
    	for(int i = 0; i < racks.length; i++)
    	{
    		if(localNode == fs.getDataNodeByRackName(racks[i]))
    		{
    			nodeID = i;
    			break;
    		}
    	}
    	//choose corresponding parity blocks to update.
    	if(nodeID < (drcN-drcK))
    		startID = nodeID;
    	else
    		startID = (drcN-drcK)-1;
    	
    	for(int i = 0; i < parity.length; i++)
        {
     		LocatedBlockWithFileName lb = parityBlocksInNode.get(startID);
            long offset = lb.getStartOffset();
            
            FSDataInputStream input = parityFs.open(parityPath, conf.getInt("io.file.buffer.size", 64 * 1024));
            input.read(offset, parity[i], 0, blockSize);
            startID += (drcN-drcK);
            ParityInfo.put(i, lb);
        }
    }
    
    public class computeOperation implements Runnable {
        Semaphore slot;
        int computeID; //index of compute operations.
        byte[][] dataBuffer;

        computeOperation(int computeID, Semaphore s, byte[][] dataBuffer) {
            slot = s;
            this.computeID = computeID;
            this.dataBuffer = dataBuffer;
        }

        public void run() {
        	byte[][] deltaBuffer = new byte[drcN-drcK][];
        	for(int i = 0; i < (drcN-drcK); i++)
        	{
        		deltaBuffer[i] = new byte[blockSize];
        	}
        	NativeNCScale.compute(dataBuffer, deltaBuffer);
//        	System.out.println("computeOperation.deltaBuffer is null? "+deltaBuffer.equals(null));
        	deltaParityBuffer.put(computeID, deltaBuffer);
        	slot.release();
        }
    }
    
    public class updateOperation implements Runnable {
        Semaphore slot;
        int updateID; //index of update operations.
        byte[] parity;
        byte[] delta;
        byte[] newParity;
        
        updateOperation(int updateID, Semaphore s, byte[] parity, byte[] delta, byte[] newParity) {
            slot = s;
            this.updateID = updateID;
            this.parity = parity;
            this.delta = delta;
            this.newParity = newParity;
        }

        public void run() {
        	NativeNCScale.parityUpdate(parity, delta, newParity);
        	slot.release();
            
        }
    }
    
    public class deltaTransferOperation implements Runnable {
        Semaphore slot;
        int deltaTransferID; //index of update operations.
        byte[][] delta;

        
        deltaTransferOperation(int deltaTransferID, Semaphore s, byte[][] delta) {
            slot = s;
            this.deltaTransferID = deltaTransferID;
            this.delta = delta;
        }

        public void run() {
            try {
//        	  	System.out.println("This is delta transfer operation: "+deltaTransferID);
	    	    //增加 节点名称。首先是从rack中得到localnodeid，然后是计算出
        	  	int remoteNodeID = (localNodeID + deltaTransferID) % drcN;
//        	  	System.out.println("localNodeID: "+localNodeID+" remoteNodeID: "+remoteNodeID);
        	  	String nodeName = fs.getDataNodeByRackName(racks[remoteNodeID]);
        	  	
        	  	
	  	    	InetSocketAddress nodeAddr = NetUtils.createSocketAddr(nodeName);
	  	    	LOG.info("RaidNode.deltaTransferOperation.nodeAddr = " + nodeAddr);
	  	    	InetAddress nodeaddress = nodeAddr.getAddress();
	  	    	DFSClient nodeClient = fs.getClient();
	  	
	  	    	Socket sock = nodeClient.getsocketFactory().createSocket();
	  	    	sock.setTcpNoDelay(true);
	  	    	NetUtils.connect(sock, nodeAddr, nodeClient.getsocketTimeout());
	  	    	sock.setSoTimeout(nodeClient.getsocketTimeout());
	  	
	  	    	DataOutputStream out = null;
	  	    	out = new DataOutputStream(
	  	    	    new BufferedOutputStream(NetUtils.getOutputStream(sock,HdfsConstants.WRITE_TIMEOUT)));
	  	
	  	    	ScalingDeltaTransferHeader scalingDeltaTransferHeader = new ScalingDeltaTransferHeader(nodeClient.getDataTransferProtocolVersion(),
	  	    	    nodeClient.getnamespaceId(), deltaTransferID, fs, parityBlocks.get(nodeName), delta,nodesName);
	  	    	scalingDeltaTransferHeader.setReadOptions(new ReadOptions());
	  	    	scalingDeltaTransferHeader.writeVersionAndOpCode(out);
	  	    	scalingDeltaTransferHeader.write(out);
	  	
	  	    	out.flush();
	  	
	  	    	DataInputStream in = new DataInputStream(
	  	    	    new BufferedInputStream(NetUtils.getInputStream(sock)));
	  	
	  	    	if(in.readShort() == DataTransferProtocol.OP_STATUS_SUCCESS) {
	  	    		LOG.info("RaidNode.deltaTransferOperation.have receive the response from datanode");
	  	    	}
          } catch(IOException e) {
              LOG.info("RaidNode.deltaTransferOperation.run IOException idx = " + deltaTransferID);
          } finally {
        	  slot.release();
          }
      }
    }
    
    
    public class transferOperation implements Runnable {
        Semaphore slot;
        int transferID; //index of transfer operations.
        String newNode;
        byte[][] transfer;
        Map<Integer, LocatedBlockWithFileName> transferInfo;
        
        transferOperation(int transferID, Semaphore slot, String newNode, 
        		byte[][] transfer, Map<Integer, LocatedBlockWithFileName> transferInfo) {
            this.slot = slot;
            this.transferID = transferID;
            this.newNode = newNode;
            this.transfer = transfer;
            this.transferInfo = transferInfo;
        }

        public void run() {
            try {
            	writeHDFS(newNode, transfer, transferInfo, false);
            } catch (IOException e) {
            	 LOG.info("ScalingAlgorithmic.transferOperation.run.writeHDFS Exception");
            }
        	
        	slot.release();
        }
    }
    
    public void writeHDFS(String nodename, byte[][] blocks, 
    		Map<Integer, LocatedBlockWithFileName> blocksInfo, boolean isWriteLocal) throws IOException{
    	LOG.info("start writeHDFS. write to: "+ nodename);
    	for(int i = 0; i < blocks.length; i++)
    	{
    		LocatedBlockWithFileName lb = blocksInfo.get(i);
            
    		File writeFile = File.createTempFile(lb.getBlock().getBlockName(), ".tmp");
    		writeFile.deleteOnExit();
    		
    		OutputStream out = new FileOutputStream(writeFile);
    		out.write(blocks[i], 0, blockSize);
    		out.close();
         
    		FileInputStream blockContents = new FileInputStream(writeFile);
            DataInputStream blockMetaData = computeMetaData(this.conf, blockContents);
//            System.out.println(i+"th blockMetaData is null? "+blockMetaData.equals(null));
            blockContents.close();

            // connect to host
            blockContents = new FileInputStream(writeFile);
            Progressable progress = new Progressable() {
                @Override
                public void progress() {
                }
            };
               
            connectNode(nodename, blockContents, blockMetaData, lb.getBlock(),
                    blockSize, this.dfsClient.getDataTransferProtocolVersion(),
                    this.dfsClient.getnamespaceId(), progress, this.conf, isWriteLocal);
            blockContents.close();
            blockMetaData.close();
            writeFile.delete();
//            System.out.println("finish write "+i+"th parity.");
    	}
    }
    
    public DataInputStream computeMetaData(Configuration conf, InputStream dataStream) 
            throws IOException {
            ByteArrayOutputStream mdOutBase = new ByteArrayOutputStream(1024*1024);
            DataOutputStream mdOut = new DataOutputStream(mdOutBase);

            // First, write out the version.
            mdOut.writeShort(FSDataset.FORMAT_VERSION_NON_INLINECHECKSUM);

            // Create a summer and write out its header.
            int bytesPerChecksum = conf.getInt("io.bytes.per.checksum", 512);
            DataChecksum sum =
                DataChecksum.newDataChecksum(DataChecksum.CHECKSUM_CRC32,
                        bytesPerChecksum);
            sum.writeHeader(mdOut);

            // Buffer to read in a chunk of data.
            byte[] buf = new byte[bytesPerChecksum];
            // Buffer to store the checksum bytes.
            byte[] chk = new byte[sum.getChecksumSize()];

            // Read data till we reach the end of the input stream.
            int bytesSinceFlush = 0;
            while (true) {
                // Read some bytes.
                int bytesRead = dataStream.read(buf, bytesSinceFlush,
                        bytesPerChecksum - bytesSinceFlush);
                if (bytesRead == -1) {
                    if (bytesSinceFlush > 0) {
                        boolean reset = true;
                        sum.writeValue(chk, 0, reset); // This also resets the sum.
                        // Write the checksum to the stream.
                        mdOut.write(chk, 0, chk.length);
                        bytesSinceFlush = 0;
                    }
                    break;
                }
                // Update the checksum.
                sum.update(buf, bytesSinceFlush, bytesRead);
                bytesSinceFlush += bytesRead;

                // Flush the checksum if necessary.
                if (bytesSinceFlush == bytesPerChecksum) {
                    boolean reset = true;
                    sum.writeValue(chk, 0, reset); // This also resets the sum.
                    // Write the checksum to the stream.
                    mdOut.write(chk, 0, chk.length);
                    bytesSinceFlush = 0;
                }
            }
            byte[] mdBytes = mdOutBase.toByteArray();
            return new DataInputStream(new ByteArrayInputStream(mdBytes));
        }
    
    public void connectNode(String localName, final FileInputStream blockContents, 
            final DataInputStream metaData, Block block, long blockSize, int dataTransferVersion, 
            int namespaceId, Progressable progress, Configuration writeconf, boolean isWrithLocal) throws IOException {
        InetSocketAddress target = NetUtils.createSocketAddr(localName);
        Socket sock = SocketChannel.open().socket();
        
//        System.out.println("start connectNode");
        int readTimeout =
            writeconf.getInt(BlockIntegrityMonitor.BLOCKFIX_READ_TIMEOUT, 
                    HdfsConstants.READ_TIMEOUT);
        NetUtils.connect(sock, target, readTimeout);
        sock.setSoTimeout(readTimeout);

        int writeTimeout = 
             writeconf.getInt(BlockIntegrityMonitor.BLOCKFIX_WRITE_TIMEOUT,
                HdfsConstants.WRITE_TIMEOUT);

        OutputStream baseStream = NetUtils.getOutputStream(sock, writeTimeout);
        DataOutputStream out =
            new DataOutputStream(new BufferedOutputStream(baseStream, 
                        FSConstants.SMALL_BUFFER_SIZE));

        boolean corruptChecksumOk = false;
        boolean chunkOffsetOK = false;
        boolean verifyChecksum = true;
        boolean transferToAllowed = false;
        try {
            LOG.info("ScalingAlgorithmic.connectNode: Sending block " + block +
                    " from " + sock.getLocalSocketAddress().toString() +
                    " to " + sock.getRemoteSocketAddress().toString());
            BlockSender blockSender = 
                new BlockSender(namespaceId, block, blockSize, 0, blockSize,
                        corruptChecksumOk, chunkOffsetOK, verifyChecksum,
                        transferToAllowed, dataTransferVersion >= DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
                        new BlockWithChecksumFileReader.InputStreamWithChecksumFactory() {
                            @Override
                            public InputStream createStream(long offset) throws IOException {
                                // we are passing 0 as the offset above,
                                // so we can safely ignore
                                // the offset passed
                                return blockContents; 
                            }

                            @Override
                            public DataInputStream getChecksumStream() throws IOException {
                                return metaData;
                            }

                            @Override
                            public BlockDataFile.Reader getBlockDataFileReader()
                            throws IOException {
                            return BlockDataFile.getDummyDataFileFromFileChannel(
                                    blockContents.getChannel()).getReader(null);
                            }
                        });
            WriteBlockHeader header = new WriteBlockHeader(new VersionAndOpcode(
                        dataTransferVersion, DataTransferProtocol.OP_WRITE_BLOCK));
            header.set(namespaceId, block.getBlockId(), block.getGenerationStamp(),
                    0, isWrithLocal, true, new DatanodeInfo(), 0, null, "");
            header.writeVersionAndOpCode(out);
            header.write(out);
            blockSender.sendBlock(out, baseStream, null, progress);
            DataNode.LOG.info("ScalingAlgorithmic.connectNode: Sent block " + block + " to " + localName);
        } finally {
            sock.close();
            out.close();
        }
    }

}       
