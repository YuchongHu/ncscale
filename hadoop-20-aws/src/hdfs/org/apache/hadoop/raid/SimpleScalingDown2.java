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
import com.sun.jndi.url.iiopname.iiopnameURLContextFactory;
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
import org.apache.hadoop.hdfs.protocol.ScalingDownTransferHeader;
import org.apache.hadoop.hdfs.protocol.ScalingDownTransferHeader2;
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
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.NativeDRC;
import org.apache.hadoop.util.NativeNCScale;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;


public class SimpleScalingDown2 {
    public static final Log LOG = LogFactory.getLog(
            "SimpleScalingDown2.class");
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
    private String[] racks;
    private String[] nodesName;
    private int startBlockIDInDG;
    private int endBlockID;
    Map<Integer, byte[][]> newParity; // new parity blocks in the new stripes after scaling
    
    private long calParityTime1=0, calParityTime2=0, calParityTime=0;
    private long transToRemoteTime1=0, transToRemoteTime2=0, transToRemoteTime=0;
    private long calDeltaTime1=0, calDeltaTime2=0, calDeltaTime=0;
    private long remoteUpdateTime1=0, remoteUpdateTime2=0, remoteUpdateTime=0;
    private long calStage2Time1=0, calStage2Time2=0, calStage2Time=0;
    private long transStage2Time1=0, transStage2Time2=0, transStage2Time=0;
    
    public SimpleScalingDown2(Configuration conf, String localNode,
    		Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks,
    		Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks,int localNodeID, String[] nodesName) 
        throws IOException {       
    	long init1=System.nanoTime();
        this.conf = conf;
        this.localNode = localNode;
        this.dataBlocks = dataBlocks;
        this.parityBlocks = parityBlocks;
        this.localNodeID = localNodeID;
        this.nodesName = nodesName;
   	  	init();	  	
   	  	long init2=System.nanoTime();
   	  	LOG.info("SimpleScalingDown2.constructor time = "+(init2-init1));
    }
    
    public SimpleScalingDown2(Configuration conf, String localNode, int localNodeID, String[] nodesName) 
        throws IOException {       
        this.conf = conf;
        this.localNode = localNode;
        this.localNodeID = localNodeID;
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
   	  	this.racks = fs.getRacks();
   	  	this.newParity = new HashMap<Integer, byte[][]>();
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

    /*
     * compute the new parity blocks for new stripes after scaling.
     */
    public void computeNewParity() throws IOException {
    	calParityTime1 = System.nanoTime();
    	LOG.info("1. start computeParity");
//    	System.out.println("111. start compute parity");
    	Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
    	dataBlocksInNode = dataBlocks.get(localNode);
    	Map<Integer, byte[][]> blocks = new HashMap<Integer, byte[][]>();
    	
    	//Get data blocks for each compute operation.
    	for(int i = 0; i < (drcN-drcS)*drcK; i++) //Each scaling operates on n-1 times, and each time compute n-1 times parity blocks    
    	{
    		byte[][] dataBuffer = new byte[drcK-drcS][];
	       	for(int j = 0; j < (drcK-drcS); j++)
	       		dataBuffer[j] = new byte[blockSize];

    		for(int j = 0; j < (drcK-drcS); j++)
    		{
	     		LocatedBlockWithFileName lb = dataBlocksInNode.get(i*(drcK-drcS)+j);
	            long offset = lb.getStartOffset();
	            FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
	            input.read(offset, dataBuffer[j], 0, blockSize);
	            input.close();
    		}
    		blocks.put(i, dataBuffer);
    	}
    	
	    ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-computeDelta-pool-%d").build();
	    ExecutorService computePool = Executors.newFixedThreadPool((drcN-drcS)*drcK, computeFactory);
	    Semaphore slots = new Semaphore((drcN-drcS)*drcK);
	 	for(int i = 0; i < (drcN-drcS)*drcK;)
	   	{
	 		try {
	 			boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
	            if(acquired) {
	            	computePool.execute(new computeOperation(i, slots, blocks.get(i)));
	                i++;
	            }
	        } catch(Exception e) {
	        	LOG.info("SimpleScalingDown2.computeNewParity.run stream " + i + " for Exception");
	        }
	    }
	 	
	    while(true) {
	    	try {
	    		boolean acquired = slots.tryAcquire((drcN-drcS)*drcK, 100, TimeUnit.MINUTES);
	        	if(acquired) {
	            	slots.release((drcN-drcS)*drcK);
	            	break;
	            }
	        } catch(Exception e) {
	        	LOG.info("SimpleScalingDown2.computeNewParity.run.while Exception");
	        }
	    }
	    computePool.shutdown();  
//	    System.out.println("111. compute finish, computeNum is "+((drcN-drcS)*drcK)+" each time use "+(drcK-drcS)+" blocks to compute");
	    calParityTime2=System.nanoTime();
	    calParityTime=calParityTime2-calParityTime1;
    	LOG.info("SimpleScalingDown2.computeNewParity time is = " + calParityTime + " ns");
    }

    /*
     * Transfer blocks to remote nodes
     */
    public void transferToRemoteNode() throws IOException {
    	transToRemoteTime1 = System.nanoTime();
    	LOG.info("2. start trasnfer to remote node.");
//    	System.out.println("222. start transfer to remote");
    	Map<Integer, byte[][]> transfer = 
				 new HashMap<Integer, byte[][]>();
		Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
		dataBlocksInNode = dataBlocks.get(localNode);     
		Map<Integer, LocatedBlockWithFileName[]> transferInfo = 
				new HashMap<Integer, LocatedBlockWithFileName[]>();
		
		int transferNumForANode = (drcN*(drcK-drcS)+drcK*(drcN-drcK));          
    	for(int i = 0; i < drcN-drcS; i++)
    	{
    		byte[][] transferANode = new byte[transferNumForANode][];
    		LocatedBlockWithFileName[] transferInfoForANode = 
   				 new LocatedBlockWithFileName[transferNumForANode];
    		for(int j = 0; j < transferNumForANode; j++)
        	{
        		transferANode[j] = new byte[blockSize];
        	}

     		for(int j = 0; j < transferNumForANode; j++)
    		{
         		LocatedBlockWithFileName lb = dataBlocksInNode.get(j);
                long offset = lb.getStartOffset();
                    
                FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
                input.read(offset, transferANode[j], 0, blockSize);
                transferInfoForANode[j] = lb;
	            input.close();
    		}  
     		transfer.put(i, transferANode);
     		transferInfo.put(i, transferInfoForANode);
    	}
    	
		if(localNodeID == drcN-1)
     	{
			ThreadFactory TransferFactory = new ThreadFactoryBuilder().setNameFormat("parallel-transfer-pool-%d").build();
			ExecutorService TransferPool = Executors.newFixedThreadPool(drcN-drcS, TransferFactory);
	        Semaphore slots = new Semaphore(drcN-drcS);
	    	for(int i = 0; i < drcN-drcS;)
	    	{
	            try {
	                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
	                if(acquired) {
	                	TransferPool.execute(new TransferOperation(i, slots, transfer.get(i), 
	                			transferInfo.get(i),0));
	                    i++;
	                }
	            } catch(Exception e) {
	          	  LOG.info("SimpleScalingDown2.transferToRemoteNode.run stream " + i + " for Exception");
	            }
	        }
	        // should wait for all the stream read ready
	        while(true) {
	            try {
	                boolean acquired = slots.tryAcquire(drcN-drcS, 100, TimeUnit.MINUTES);
	                if(acquired) {
	                    slots.release(drcN-drcS);
	                    break;
	                }
	            } catch(Exception e) {
	          	  LOG.info("SimpleScalingDown2.transferToRemoteNode.run.while Exception");
	            }
	        }
	        TransferPool.shutdown();  
     	}
		else
		{
	     	ThreadFactory transferFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
	        ExecutorService transferPool = Executors.newFixedThreadPool(drcN-drcS, transferFactory);
	        Semaphore slots = new Semaphore(drcN-drcS);
	    	for(int i = 0; i < drcN-drcS;)
	    	{
		        try {
		            boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
		            if(acquired) {
		                transferPool.execute(new TransferAndWriteOperation(i, slots, nodesName[i], transfer.get(i), 
		                		transferInfo.get(i)));
		                i++;
		            }
		        } catch(Exception e) {
		        	LOG.info("SimpleScalingDown2.transferStage2.run stream " + i + " for Exception");
		        }
	        }
	        // should wait for all the stream read ready
	        while(true) {
	            try {
	                boolean acquired = slots.tryAcquire(drcN-drcS, 100, TimeUnit.MINUTES);
	                if(acquired) {
	                    slots.release(drcN-drcS);
	                    break;
	                }
	            } catch(Exception e) {
	          	  LOG.info("SimpleScalingDown2.transferStage2.run.while Exception");
	            }
	        }
	        transferPool.shutdown();  
		}
//		System.out.println("222. transfer finish, transfer to "+(drcN-drcS)+" nodes, and each node transfer "+transferNumForANode); 
		transToRemoteTime2=System.nanoTime();
		transToRemoteTime=transToRemoteTime2-transToRemoteTime1;
    	LOG.info("SimpleScalingDown2.transferToRemoteNode time is = " + transToRemoteTime + " ns");
    }
   
    /*
     * Compute Delta in the remote node
     */
    public void remoteComputeDelta(int transferID, LocatedBlockWithFileName[] transferInfo, byte[][] transfer) throws IOException {
    	calDeltaTime1 = System.nanoTime();
    	LOG.info("start Compute delta.");
//    	System.out.println("333. start compute delta");
    	int computeDeltaNum = drcN*(drcK-drcS);
    	int updateNum = drcN*(drcK-drcS);
    	int writeNumInLocal = drcN*(drcK-drcS);
    	byte[][] updateBlock = new byte[updateNum][];
    	byte[][] newParity = new byte[updateNum][];//these are updated parity blocks
    	LocatedBlockWithFileName[] updateBlockInfo = new LocatedBlockWithFileName[updateNum];
    	for(int i = 0; i < updateNum; i++)
    	{
    		updateBlock[i] = new byte[blockSize];
    		newParity[i] = new byte[blockSize];
    	}
    	
    	//read part
		int transferNum = transfer.length;
    	byte[][] transferBuf = new byte[transferNum][];
    	LocatedBlockWithFileName[] transferBufInfo = new LocatedBlockWithFileName[transferNum];
    	for(int i = 0; i < transferNum; i++)
    	{
    		transferBuf[i] = new byte[blockSize];
    	}
		for(int i = 0; i < updateNum; i++)
		{
     		LocatedBlockWithFileName lb = dataBlocks.get(localNode).get(i);
            long offset = lb.getStartOffset();
            FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
            input.read(offset, transferBuf[i], 0, blockSize);
            transferBufInfo[i] = lb;
            updateBlockInfo[i] = lb;
            updateBlock[i] = transferBuf[i];
            input.close();
		}
		for(int i = updateNum; i < transferNum; i++)
		{
     		LocatedBlockWithFileName lb = dataBlocks.get(localNode).get(i);
            long offset = lb.getStartOffset();
            FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
            input.read(offset, transferBuf[i], 0, blockSize);
            transferBufInfo[i] = lb;
            input.close();
		}
		
		//computeDelta part
		byte[][] dataBuffer = new byte[drcS][];
		for(int i = 0; i < drcS; i++)
			dataBuffer[i] = updateBlock[i]; 
	    ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-computeDelta-pool-%d").build();
	    ExecutorService computePool = Executors.newFixedThreadPool(computeDeltaNum, computeFactory);
	    Semaphore slots = new Semaphore(computeDeltaNum);
	 	for(int i = 0; i < computeDeltaNum;)
	   	{
	 		try {
	 			boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
	            if(acquired) {
	            	computePool.execute(new computeOperation(i, slots, dataBuffer));
	                i++;
	            }
	        } catch(Exception e) {
	        	LOG.info("SimpleScalingDown2.remoteComputeDelta.computeNewParity.run stream " + i + " for Exception");
	        }
	    }
	    while(true) {
	    	try {
	    		boolean acquired = slots.tryAcquire(computeDeltaNum, 100, TimeUnit.MINUTES);
	        	if(acquired) {
	            	slots.release(computeDeltaNum);
	            	break;
	            }
	        } catch(Exception e) {
	        	LOG.info("SimpleScalingDown2.remoteComputeDelta.computeNewParity.run.while Exception");
	        }
	    }
	    computePool.shutdown();  
//	    System.out.println("333. finish compute delta, num is "+computeDeltaNum);
		//2nd transfer and remote update
     	ThreadFactory TransferFactory = new ThreadFactoryBuilder().setNameFormat("parallel-transfer-pool-%d").build();
        ExecutorService TransferPool = Executors.newFixedThreadPool(drcN-drcK-1, TransferFactory);
        Semaphore slots1 = new Semaphore(drcN-drcK-1);
    	for(int i = 0; i < drcN-drcK-1;)
    	{
            try {
                boolean acquired = slots1.tryAcquire(1, 100, TimeUnit.MINUTES);
                int transferTo = (localNodeID+1+i)%(drcN-drcS);
//                System.out.println("333. transfer to node"+transferTo);
                if(acquired) {
//                	System.out.println("333. start transfer operation");
                	TransferPool.execute(new TransferOperation(transferTo, slots1, updateBlock, 
                			updateBlockInfo,1));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("SimpleScalingDown2.remoteComputeDelta.transferToRemoteNode.run stream " + i + " for Exception");
            }
        }
        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots1.tryAcquire(drcN-drcK-1, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots1.release(drcN-drcK-1);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("SimpleScalingDown2.remoteComputeDelta.transferToRemoteNode.run.while Exception");
            }
        }
        TransferPool.shutdown(); 
//        System.out.println("333. finish transfer delta, transfer to "+(drcN-drcK-1)+" nodes, and each nodes transfer "+updateNum);
		//update part
		ThreadFactory updateFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
        ExecutorService updatePool = Executors.newFixedThreadPool(updateNum, updateFactory);
        Semaphore slots2 = new Semaphore(updateNum);
    	for(int i = 0; i < updateNum;)
    	{
            try {
                boolean acquired = slots2.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	updatePool.execute(new updateOperation(i, slots2, updateBlock[i], transfer[i], newParity[i]));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("SimpleScalingDown2.remoteComputeDelta.remoteUpdate.run stream " + i + " for Exception");
            }
        }
        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots2.tryAcquire(updateNum, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots2.release(updateNum);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("SimpleScalingDown2.remoteComputeDelta.remoteUpdate.run.while Exception");
            }
        }
        updatePool.shutdown();  
//        System.out.println("333. finish update, num is "+updateNum);
        writeHDFS(localNode, transferBuf, transferBufInfo, transferNum, true);
        writeHDFS(localNode, updateBlock, updateBlockInfo, writeNumInLocal, true);
//        System.out.println("333. finish write HDFS, num is "+(transferNum+writeNumInLocal));
        calDeltaTime2=System.nanoTime();
        calDeltaTime=calDeltaTime2-calDeltaTime1;
    	LOG.info("SimpleScalingDown2.remoteComputeDelta time is = " + calDeltaTime + " ns");
    } 
    
    /*
     * The update operation in the remote node
     */
    public void remoteUpdate(int transferID, LocatedBlockWithFileName[] transferInfo, byte[][] transfer) throws IOException {
    	remoteUpdateTime1 = System.nanoTime();
    	LOG.info("start Remote update.");
//    	System.out.println("444. start remote update");
    	int updateNum = drcN*(drcK-drcS);
    	int writeNumInLocal = drcN*(drcK-drcS);
    	byte[][] updateBlock = new byte[updateNum][];
    	byte[][] newParity = new byte[updateNum][];//these are updated parity blocks
    	LocatedBlockWithFileName[] updateBlockInfo = new LocatedBlockWithFileName[updateNum];
    	for(int i = 0; i < updateNum; i++)
    	{
    		updateBlock[i] = new byte[blockSize];
    		newParity[i] = new byte[blockSize];
    	}
    	
    	//read part
		for(int i = 0; i < updateNum; i++)
		{
     		LocatedBlockWithFileName lb = dataBlocks.get(localNode).get(i);
            long offset = lb.getStartOffset();
            FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
            input.read(offset, updateBlock[i], 0, blockSize);
            updateBlockInfo[i] = lb;
            input.close();
		}
		//update part
		ThreadFactory updateFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
        ExecutorService updatePool = Executors.newFixedThreadPool(updateNum, updateFactory);
        Semaphore slots2 = new Semaphore(updateNum);
    	for(int i = 0; i < updateNum;)
    	{
            try {
                boolean acquired = slots2.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	updatePool.execute(new updateOperation(i, slots2, updateBlock[i], transfer[i], newParity[i]));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("SimpleScalingDown2.remoteUpdate.remoteUpdate.run stream " + i + " for Exception");
            }
        }
        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots2.tryAcquire(updateNum, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots2.release(updateNum);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("SimpleScalingDown2.remoteUpdate.remoteUpdate.run.while Exception");
            }
        }
        updatePool.shutdown();  
//        System.out.println("444. finish update, num is "+updateNum);
        writeHDFS(localNode, updateBlock, updateBlockInfo, writeNumInLocal, true);
//        System.out.println("444. finish write HDFS, num is "+writeNumInLocal);
        remoteUpdateTime2=System.nanoTime();
        remoteUpdateTime=remoteUpdateTime2-remoteUpdateTime1;
    	LOG.info("SimpleScalingDown2.remoteUpdate time is = " + remoteUpdateTime + " ns");
    } 

    /*
     * compute new parity blocks on the surviving nodes in stage 2.
     */
    public void computeStage2() throws IOException {
    	calStage2Time1 = System.nanoTime();
    	LOG.info("start compute Stage 2");
//    	System.out.println("AAA. start compute Stage 2");
    	Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
    	dataBlocksInNode = dataBlocks.get(localNode);
    	Map<Integer, byte[][]> blocks = new HashMap<Integer, byte[][]>();
    	int computeNum = (drcN-drcK)*drcS;
    	
    	//Get data blocks for each compute operation.
    	for(int i = 0; i < computeNum; i++) //Each scaling operates on n-1 times, and each time compute n-1 times parity blocks    
    	{
    		byte[][] dataBuffer = new byte[drcK-drcS][];
	       	for(int j = 0; j < (drcK-drcS); j++)
	       		dataBuffer[j] = new byte[blockSize];

    		for(int j = 0; j < (drcK-drcS); j++)
    		{
	     		LocatedBlockWithFileName lb = dataBlocksInNode.get(i*(drcK-drcS)+j);
	            long offset = lb.getStartOffset();
	            FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
	            input.read(offset, dataBuffer[j], 0, blockSize);
	            input.close();
    		}
    		blocks.put(i, dataBuffer);
    	}
    	
	    ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-computeDelta-pool-%d").build();
	    ExecutorService computePool = Executors.newFixedThreadPool(computeNum, computeFactory);
	    Semaphore slots = new Semaphore(computeNum);
	 	for(int i = 0; i < computeNum;)
	   	{
	 		try {
	 			boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
	            if(acquired) {
	            	computePool.execute(new computeOperation(i, slots, blocks.get(i)));
	                i++;
	            }
	        } catch(Exception e) {
	        	LOG.info("SimpleScalingDown2.computeStage2.run stream " + i + " for Exception");
	        }
	    }
	    while(true) {
	    	try {
	    		boolean acquired = slots.tryAcquire(computeNum, 100, TimeUnit.MINUTES);
	        	if(acquired) {
	            	slots.release(computeNum);
	            	break;
	            }
	        } catch(Exception e) {
	        	LOG.info("SimpleScalingDown2.computeStage2.run.while Exception");
	        }
	    }
	    computePool.shutdown();  
//	    System.out.println("AAA. finish compute Stage 2, compute num is "+computeNum);
	    calStage2Time2=System.nanoTime();
	    calStage2Time=calStage2Time2-calStage2Time1;
    	LOG.info("SimpleScalingDown2.computeStage2 time is = " + calStage2Time + " ns");
    }
    
    /*
     * Surviving nodes transfer blocks to new stripes.
     */
    public void transferStage2() throws IOException {
    	transStage2Time1 = System.nanoTime();
    	LOG.info("start transfer stage 2.");
//    	System.out.println("BBB. start transfer stage 2");
    	//prepare
		Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
		dataBlocksInNode = dataBlocks.get(localNode);   
    	Map<Integer, byte[][]> transfer = 
				 new HashMap<Integer, byte[][]>();
		Map<Integer, LocatedBlockWithFileName[]> transferInfo = 
				new HashMap<Integer, LocatedBlockWithFileName[]>();
		
		int transferNumForANode = (drcN-drcK)*drcS;    
    	for(int i = 0; i < drcN-drcS; i++)
    	{
	    	byte[][] transferANode = new byte[transferNumForANode][];
	    	for(int j = 0; j < transferNumForANode; j++ )
	    	{
	    		transferANode[j] = new byte[blockSize];
	    	}
	    	LocatedBlockWithFileName[] transferInfoForANode = 
	   			 new LocatedBlockWithFileName[transferNumForANode];
	        for(int j = 0; j < transferNumForANode; j++ )
	    	{
	        	LocatedBlockWithFileName lb = dataBlocksInNode.get(j);
	        	long offset = lb.getStartOffset();
                
		        FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
		        input.read(offset, transferANode[j], 0, blockSize);
		        transferInfoForANode[j] = lb;
	            input.close();
	    	}
	     	transfer.put(i, transferANode);
	     	transferInfo.put(i, transferInfoForANode);
    	}
    	//transfer
     	ThreadFactory transferFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
        ExecutorService transferPool = Executors.newFixedThreadPool(drcN-drcS-1, transferFactory);
        Semaphore slots = new Semaphore(drcN-drcS-1);
    	for(int i = 0; i < drcN-drcS; i++)
    	{
    		if(i != localNodeID)
    		{  
    			try {
	                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
	                if(acquired) {
	                	transferPool.execute(new TransferAndWriteOperation(i, slots, nodesName[i], transfer.get(i), 
	                			transferInfo.get(i)));
	                }
	            } catch(Exception e) {
	          	  LOG.info("SimpleScalingDown2.transferStage2.run stream " + i + " for Exception");
	            }
            }
        }
        // should wait for all the stream read ready
        while(true) {
            try {
                boolean acquired = slots.tryAcquire(drcN-drcS-1, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(drcN-drcS-1);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("SimpleScalingDown2.transferStage2.run.while Exception");
            }
        }
        transferPool.shutdown();
//        System.out.println("BBB. finish transfer stage 2, transfer to "+(drcN-drcS)+" nodes, and each transfer "+transferNumForANode);
        writeHDFS(localNode, transfer.get(0), transferInfo.get(0), transfer.get(0).length, true);
//    	System.out.println("BBB. finish transfer stage 2 write HDFS, num is "+transfer.get(0).length);
    	transStage2Time2=System.nanoTime();
    	transStage2Time=transStage2Time2-transStage2Time1;
    	LOG.info("SimpleScalingDown2.transferStage2 time is = " + transStage2Time + " ns");
    }    
////////////////////////////////////////////////////////////////////////////////////////////////  
    
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
        	byte[][] parityBuffer = new byte[drcN-drcK][];
        	for(int i = 0; i < (drcN-drcK); i++)
        	{
        		parityBuffer[i] = new byte[blockSize];
        	}
        	NativeNCScale.compute(dataBuffer, parityBuffer);
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
    
    public class TransferOperation implements Runnable {
        Semaphore slot;
        int transferID; //index of update operations.
        byte[][] transfer;
        LocatedBlockWithFileName[] transferInfo;
    	int transferType;
    	
        TransferOperation(int transferID, Semaphore s, byte[][] transfer, LocatedBlockWithFileName[] transferInfo, 
        		int transferType) {
            slot = s;
            this.transferID = transferID;
            this.transfer = transfer;
            this.transferInfo = transferInfo;
            this.transferType = transferType;
        }

        public void run() {
            try {
//            	System.out.println("    "+transferType+". start transfer operation "+transferID+" th");
        	  	String nodeName = nodesName[transferID];
	  	    	InetSocketAddress nodeAddr = NetUtils.createSocketAddr(nodeName);
	  	    	LOG.info("SimpleScalingDown2.TransferOperation.` = " + nodeAddr);
	  	    	InetAddress nodeaddress = nodeAddr.getAddress();
	  	    	DFSClient nodeClient = fs.getClient();
	  	
	  	    	Socket sock = nodeClient.getsocketFactory().createSocket();
	  	    	sock.setTcpNoDelay(true);
	  	    	NetUtils.connect(sock, nodeAddr, nodeClient.getsocketTimeout());
	  	    	sock.setSoTimeout(nodeClient.getsocketTimeout());
	  	
	  	    	DataOutputStream out = null;
	  	    	out = new DataOutputStream(
	  	    	    new BufferedOutputStream(NetUtils.getOutputStream(sock,HdfsConstants.WRITE_TIMEOUT)));
	  	    	ScalingDownTransferHeader2 ScalingDownTransferHeader = new ScalingDownTransferHeader2(nodeClient.getDataTransferProtocolVersion(),
	  	    	    nodeClient.getnamespaceId(), transferID, fs, transferInfo, transfer, dataBlocks, parityBlocks, transferType,nodesName);
	  	    	ScalingDownTransferHeader.setReadOptions(new ReadOptions());
	  	    	ScalingDownTransferHeader.writeVersionAndOpCode(out);
	  	    	ScalingDownTransferHeader.write(out);
	  	
	  	    	out.flush();
	  	
	  	    	DataInputStream in = new DataInputStream(
	  	    	    new BufferedInputStream(NetUtils.getInputStream(sock)));
	  	
	  	    	if(in.readShort() == DataTransferProtocol.OP_STATUS_SUCCESS) {
	  	    		LOG.info("SimpleScalingDown2.TransferOperation.have receive the response from datanode");
	  	    	}
          } catch(IOException e) {
              LOG.info("SimpleScalingDown2.TransferOperation.run IOException idx = " + transferID);
          } finally {
        	  slot.release();
          }
//          System.out.println("    "+transferType+". finish transfer operation "+transferID+" th");
      }
    }
    
    
    public class TransferAndWriteOperation implements Runnable {
        Semaphore slot;
        int transferID; //index of transfer operations.
        String newNode;
        byte[][] transfer;
        LocatedBlockWithFileName[] transferInfo;
        
        TransferAndWriteOperation(int transferID, Semaphore slot, String newNode, 
        		byte[][] transfer, LocatedBlockWithFileName[] transferInfo) {
            this.slot = slot;
            this.transferID = transferID;
            this.newNode = newNode;
            this.transfer = transfer;
            this.transferInfo = transferInfo;
        }

        public void run() {
            try {
            	writeHDFS(newNode, transfer, transferInfo, transfer.length, false);
            } catch (IOException e) {
            	 LOG.info("SimpleScalingDown2.TransferAndWriteOperation.run.writeHDFS Exception");
            }
        	
        	slot.release();
        }
    }
    
    public void writeHDFS(String nodename, byte[][] blocks, 
    		LocatedBlockWithFileName[] blocksInfo, int Length, boolean isWriteLocal) throws IOException{
    	LOG.info("start writeHDFS. write to: "+ nodename);
    	for(int i = 0; i < Length; i++)
    	{
    		LocatedBlockWithFileName lb = blocksInfo[i];
            
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
            LOG.info("SimpleScalingDown2.connectNode: Sending block " + block +
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
            DataNode.LOG.info("SimpleScalingDown2.connectNode: Sent block " + block + " to " + localName);
        } finally {
            sock.close();
            out.close();
        }
    }

}       
