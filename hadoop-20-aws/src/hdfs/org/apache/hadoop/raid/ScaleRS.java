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

import jdk.internal.org.objectweb.asm.tree.IntInsnNode;
import sun.util.resources.cldr.bs.LocaleNames_bs;

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
import org.apache.hadoop.hdfs.protocol.ScaleRSDeltaTransferHeader;
import org.apache.hadoop.hdfs.protocol.ScalingDeltaTransferHeader;
import org.apache.hadoop.hdfs.protocol.VersionAndOpcode;
import org.apache.hadoop.hdfs.protocol.WriteBlockHeader;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.hdfs.server.datanode.BlockDataFile;
import org.apache.hadoop.hdfs.server.datanode.BlockSender;
import org.apache.hadoop.hdfs.server.datanode.BlockWithChecksumFileReader;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.FSDataset;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.io.serializer.DeserializerComparator;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.raid.RaidUtils;
import org.apache.hadoop.raid.ScalingAlgorithmic.updateOperation;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.NativeDRC;
import org.apache.hadoop.util.NativeNCScale;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;


public class ScaleRS {
    public static final Log LOG = LogFactory.getLog(
            "ScaleRS.class");
    protected Configuration conf;
    Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks = null;
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks = null;
    private int blockSize;
    private DFSClient dfsClient;
    DistributedFileSystem fs;
    Codec codec;
    String localNode;
    private int scalingID;
    private int idx;
    private int localNodeID;
    Path path;
    private int drcK;
    private int drcS;
    private int drcN;
    private String[] racks;
    private String[] nodesName;
    private String[] parityNodes; //ip of parity nodes
    private int operateNum;
    private byte[][] deltaComputed;
    
    private long calTime1=0, calTime2=0, calTime=0;
    private long transToNewTime1=0, transToNewTime2=0, transToNewTime=0;
    private long transDeltaTime1=0, transDeltaTime2=0, transDeltaTime=0;
    private long remoteUpdateTime1=0, remoteUpdateTime2=0, remoteUpdateTime=0;
    
    public ScaleRS(Configuration conf, String localNode,
    		Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks,
    		Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks,
    		int scalingID, int idx, String[] nodesName) 
        throws IOException { 
    	long init1=System.nanoTime();
        this.conf = conf;
        this.localNode = localNode;
        this.dataBlocks = dataBlocks;
        this.parityBlocks = parityBlocks;
        this.scalingID = scalingID;
        this.idx = idx;
        this.nodesName = nodesName;
   	  	init();	  	
   	  	long init2=System.nanoTime();
   	  	LOG.info("ScaleRS.constructor time = "+(init2-init1));
    }
    
    public ScaleRS(Configuration conf, String localNode, String[] nodesName) 
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
   	  	this.racks = fs.getRacks();
   	  	this.operateNum = drcN*(drcN+drcS); 
   	  	this.deltaComputed = new byte[operateNum][];	
    	this.localNodeID = idx;
   	  	this.parityNodes = new String[drcN-drcK];
   	  	int tmpIndex = scalingID%drcN;
   	  	for (int i = 0; i < (drcN-drcK); i++)
   	  		this.parityNodes[i] = nodesName[(i+tmpIndex)%drcN];
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
    	calTime1=System.nanoTime();
    	LOG.info("start computeDelta");
    	Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
    	dataBlocksInNode = dataBlocks.get(localNode);
    	Map<Integer, byte[][]> blocks = new HashMap<Integer, byte[][]>();
    	
    	for(int i = 0; i < operateNum; i++) //Each scaling operates on n-1 times, and each time compute n-1 times parity blocks    
    	{
    		byte[][] dataBuffer = new byte[drcS][];
	       	for(int j = 0; j < drcS; j++)
	       		dataBuffer[j] = new byte[blockSize];

    		for(int j = 0; j < drcS; j++)
    		{
	     		LocatedBlockWithFileName lb = dataBlocksInNode.get(drcS*i+j);
	            long offset = lb.getStartOffset();
	            FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
	            input.read(offset, dataBuffer[j], 0, blockSize);
	            input.close();
    		}
    		blocks.put(i, dataBuffer);
    		deltaComputed[i] = dataBuffer[0];
    	}
    	
    	if(drcS != 1)
    	{
		    ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-computeDelta-pool-%d").build();
		    ExecutorService computePool = Executors.newFixedThreadPool(operateNum, computeFactory);
		    Semaphore slots = new Semaphore(operateNum);
		 	for(int i = 0; i < operateNum;)
		   	{
		 		try {
		 			boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
		            if(acquired) {
		            	computePool.execute(new computeOperation(i, slots, blocks.get(i)));
		                i++;
		            }
		        } catch(Exception e) {
		        	LOG.info("ScaleRS.computeDelta.run stream " + i + " for Exception");
		        }
		    }
		    while(true) {
		    	try {
		    		boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
		        	if(acquired) {
		            	slots.release(1);
		            	break;
		            }
		        } catch(Exception e) {
		        	LOG.info("ScaleRS.computeDelta.run.while Exception");
		        }
		    }
		    computePool.shutdown();  		
    	}
    	calTime2=System.nanoTime();
    	calTime=calTime2-calTime1;
    	LOG.info("ScaleRS.computeDelta time is = " + calTime + " ns");
    }
    
    public void transferToNewNodes() throws IOException {
    	transToNewTime1=System.nanoTime(); 
    	LOG.info("start transfer to new nodes.");
		Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				 new HashMap<Integer, LocatedBlockWithFileName>();
		dataBlocksInNode = dataBlocks.get(localNode);   
    	Map<Integer, byte[][]> transfer = 
				 new HashMap<Integer, byte[][]>();
		LocatedBlockWithFileName[][] transferInfo = new LocatedBlockWithFileName[drcS][];
		
    	for(int i = 0; i < drcS; i++)
    	{
    		byte[][] dataBuffer = new byte[operateNum][];
	       	for(int j = 0; j < operateNum; j++)
	       		dataBuffer[j] = new byte[blockSize];
	       	transferInfo[i] = new LocatedBlockWithFileName[operateNum];
	       	
    		for(int j = 0; j < operateNum; j++)
    		{
	     		LocatedBlockWithFileName lb = dataBlocksInNode.get(i*operateNum+j);
	            long offset = lb.getStartOffset();
	            FSDataInputStream input = fs.open(path, conf.getInt("io.file.buffer.size", 64 * 1024));
	            input.read(offset, dataBuffer[j], 0, blockSize);
	            transferInfo[i][j] = lb;
	            input.close();
    		}
    		transfer.put(i, dataBuffer);
    	}
		
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
                			transfer.get(i), transferInfo[i]));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("ScaleRS.localUpdate.run stream " + i + " for Exception");
            }
        }
        while(true) {
            try {
                boolean acquired = slots.tryAcquire(drcS, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(drcS);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("ScaleRS.transferToNewNodes.run.while Exception");
            }
        }
        transferPool.shutdown();  
        transToNewTime2=System.nanoTime();
        transToNewTime=transToNewTime2-transToNewTime1;
    	LOG.info("ScaleRS.transferToNewNodes time is = " + transToNewTime + " ns");
    }
    
    public void transferDeltaToRemoteNode() throws IOException {
    	transDeltaTime1=System.nanoTime();
    	LOG.info("start trasnfer to remote node.");
    	
    	ThreadFactory deltaTransferFactory = new ThreadFactoryBuilder().setNameFormat("parallel-transferDelta-pool-%d").build();
        ExecutorService deltaTransferPool = Executors.newFixedThreadPool(drcN-drcK, deltaTransferFactory);
        Semaphore slots = new Semaphore(drcN-drcK);
    	for(int i = 0; i < drcN-drcK;)
    	{
            try {
                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	deltaTransferPool.execute(new deltaTransferOperation(i, slots, deltaComputed));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("ScaleRS.transferDeltaToRemoteNode.run stream " + i + " for Exception");
            }
        }
        while(true) {
            try {
                boolean acquired = slots.tryAcquire(drcN-drcK, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(drcN-drcK);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("ScaleRS.transferDeltaToRemoteNode.run.while Exception");
            }
        }
        deltaTransferPool.shutdown();  
        transDeltaTime2=System.nanoTime();
        transDeltaTime=transDeltaTime2-transDeltaTime1;
    	LOG.info("ScaleRS.transferDeltaToRemoteNode time is = " + transDeltaTime + " ns");
    }
    
    // Running on the parity nodes. 
    public void remoteUpdate(int deltaTransferID, Map<Integer, LocatedBlockWithFileName> localParityBlocks, byte[][] delta,String remoteNode) throws IOException {
    	remoteUpdateTime1=System.nanoTime();
    	LOG.info("start Remote update.");
    	
    	int updateID = 0;
    	for(int i = 0; i < nodesName.length; i++)
    	{
    		if(remoteNode.equals(nodesName[i]))
    		{
    			updateID = i;
    			break;
    		}
    	}
    	byte[][] parity = new byte[operateNum][];
    	byte[][] newParity = new byte[operateNum][];//these are updated parity blocks
    	for(int i = 0; i < operateNum; i++)
    	{
    		parity[i] = new byte[blockSize];
    		newParity[i] = new byte[blockSize];
    	}
        LocatedBlockWithFileName[] ParityInfo =  new LocatedBlockWithFileName[operateNum];
  	  	FileStatus srcStat = fs.getFileStatus(path);
  	  	ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
  	  	Path parityPath = ppair.getPath();
    	DistributedFileSystem parityFs= getDFS(parityPath, conf);

    	for(int i = 0; i < operateNum; i++)
        {
     		LocatedBlockWithFileName lb = localParityBlocks.get(i+updateID*operateNum);
            long offset = lb.getStartOffset();
            
            FSDataInputStream input = parityFs.open(parityPath, conf.getInt("io.file.buffer.size", 64 * 1024));
            input.read(offset, parity[i], 0, blockSize);
            ParityInfo[i] =  lb;
            input.close();
        }
    	
//    	System.out.println("remoteUpdate.finish read parity, begin parity update");
     	ThreadFactory computeFactory = new ThreadFactoryBuilder().setNameFormat("parallel-node-parityUpdate-pool-%d").build();
        ExecutorService updatePool = Executors.newFixedThreadPool(operateNum, computeFactory);
        Semaphore slots = new Semaphore(operateNum);
    	for(int i = 0; i < operateNum;)
    	{
            try {
                boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
                if(acquired) {
                	updatePool.execute(new updateOperation(i, slots, parity[i], delta[i], newParity[i]));
                    i++;
                }
            } catch(Exception e) {
          	  LOG.info("ScaleRS.remoteUpdate.run stream " + i + " for Exception");
            }
        }
    	while(true) {
            try {
                boolean acquired = slots.tryAcquire(operateNum, 100, TimeUnit.MINUTES);
                if(acquired) {
                    slots.release(operateNum);
                    break;
                }
            } catch(Exception e) {
          	  LOG.info("ScaleRS.remoteUpdate.run.while Exception");
            }
        }
        updatePool.shutdown();  
        
        writeHDFS(localNode, parity, ParityInfo, true);
        remoteUpdateTime2=System.nanoTime();
        remoteUpdateTime= remoteUpdateTime2-remoteUpdateTime1;
    	LOG.info("ScaleRS.remoteUpdate time is = " + remoteUpdateTime + " ns");
    } 
  /////////////////////////////////////////////////////////////////////////////////////  
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
        	  	int remoteNodeID = deltaTransferID;
//        	  	System.out.println("localNodeID: "+localNodeID+" remoteNodeID: "+remoteNodeID);
        	  	String nodeName = nodesName[remoteNodeID];
        	  	
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
	  	
	  	    	ScaleRSDeltaTransferHeader scalingDeltaTransferHeader = new ScaleRSDeltaTransferHeader(nodeClient.getDataTransferProtocolVersion(),
	  	    	    nodeClient.getnamespaceId(), remoteNodeID, fs, parityBlocks.get(nodeName), delta,nodesName);
	  	    	scalingDeltaTransferHeader.setReadOptions(new ReadOptions());
	  	    	scalingDeltaTransferHeader.writeVersionAndOpCode(out);
	  	    	scalingDeltaTransferHeader.write(out);
	  	
	  	    	out.flush();
	  	
	  	    	DataInputStream in = new DataInputStream(
	  	    	    new BufferedInputStream(NetUtils.getInputStream(sock)));
	  	    	if(in.readShort() == DataTransferProtocol.OP_STATUS_SUCCESS) {
	  	    		LOG.info("ScaleRS.deltaTransferOperation.have receive the response from datanode");
	  	    	}
          } catch(IOException e) {
              LOG.info("ScaleRS.deltaTransferOperation.run IOException idx = " + deltaTransferID);
          } finally {
        	  slot.release();
          }
      }
    }
    
    
    public class transferOperation implements Runnable {
        Semaphore slot;
        int transferID; //index of transfer operations.
        String newNode;
        byte[][] block;
        LocatedBlockWithFileName[] lb;
        
        transferOperation(int transferID, Semaphore slot, String newNode, 
        		byte[][] block, LocatedBlockWithFileName[] lb) {
            this.slot = slot;
            this.transferID = transferID;
            this.newNode = newNode;
            this.block = block;
            this.lb = lb;
        }

        public void run() {
            try {
            	writeHDFS(newNode, block, lb, false);
            } catch (IOException e) {
            	 LOG.info("ScaleRS.transferOperation.run.writeHDFS Exception");
            }
        	
        	slot.release();
        }
    }
    
    public void writeHDFS(String nodename, byte[][] blocks, 
    		LocatedBlockWithFileName[] blocksInfo, boolean isWriteLocal) throws IOException{
    	LOG.info("start writeHDFS. write to: "+ nodename);
    	for(int i = 0; i < blocks.length; i++)
    	{
    		LocatedBlockWithFileName lb = blocksInfo[i];
            
    		File writeFile = File.createTempFile(lb.getBlock().getBlockName(), ".tmp");
    		writeFile.deleteOnExit();
    		
    		OutputStream out = new FileOutputStream(writeFile);
    		out.write(blocks[i], 0, blockSize);
    		out.close();
         
    		FileInputStream blockContents = new FileInputStream(writeFile);
            DataInputStream blockMetaData = computeMetaData(this.conf, blockContents);
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
            LOG.info("ScaleRS.connectNode: Sending block " + block +
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
            DataNode.LOG.info("ScaleRS.connectNode: Sent block " + block + " to " + localName);
        } finally {
            sock.close();
            out.close();
        }
    }

}       
