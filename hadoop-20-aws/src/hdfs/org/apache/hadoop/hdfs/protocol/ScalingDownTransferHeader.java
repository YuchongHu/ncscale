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

package org.apache.hadoop.hdfs.protocol;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
//import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;

/**
 * Header for data node transfer blocks to remote node for scaling down.
 */

public class ScalingDownTransferHeader extends DataTransferHeader implements Writable {
 
  private int namespaceId;
  private int transferID;
  DistributedFileSystem fs;
  LocatedBlockWithFileName[] transferInfo;
  byte[][] transfer;
  Configuration conf;
  private ReadOptions options = new ReadOptions();
  Map<Integer, LocatedBlockWithFileName> dataBlocksInRemoteNode;
  Map<Integer, LocatedBlockWithFileName> parityBlocksInRemoteNode;
  private String[] nodesName;
  
  public ScalingDownTransferHeader(final VersionAndOpcode versionAndOp, Configuration conf) {
    super(versionAndOp);
    this.conf = conf;
  }

  public ScalingDownTransferHeader(int dataTransferVersion, final int namespaceId, int transferID,
    DistributedFileSystem fs, LocatedBlockWithFileName[] transferInfo, byte[][] transfer, 
    Map<Integer, LocatedBlockWithFileName> dataBlocksInRemoteNode, 
	Map<Integer, LocatedBlockWithFileName> parityBlocksInRemoteNode, String[] nodesName) {
    super(dataTransferVersion, DataTransferProtocol.OP_SCALING_DOWN_1_TRANSFER);
    set(namespaceId, transferID, fs,  transferInfo, transfer, dataBlocksInRemoteNode, parityBlocksInRemoteNode, nodesName);
  }

  public void set(final int namespaceId, int transferID,
		    DistributedFileSystem fs,LocatedBlockWithFileName[] transferInfo, byte[][] transfer,
		    Map<Integer, LocatedBlockWithFileName> dataBlocksInRemoteNode, 
			Map<Integer, LocatedBlockWithFileName> parityBlocksInRemoteNode, String[] nodesName) {
    this.namespaceId = namespaceId;
    this.transferID = transferID;
    this.fs = fs;
    this.transferInfo = transferInfo;
    this.transfer = transfer;
    this.dataBlocksInRemoteNode = dataBlocksInRemoteNode;
    this.parityBlocksInRemoteNode = parityBlocksInRemoteNode;
    this.nodesName = nodesName;
  }

  public void setReadOptions(ReadOptions options) {
    if(options == null) {
      throw new IllegalArgumentException("options cannot be null");
    }
    this.options = options;
  }

  public int getNameSpaceId() {
    return this.namespaceId;
  }
  
  public int getTransferID() {
	    return this.transferID;
	  }

  public LocatedBlockWithFileName[] getTransferInfo() {
    return this.transferInfo;
  }

  public byte[][] getTransfer() {
	    return this.transfer;
	  }
  public String[] getNodesName() {
	    return this.nodesName;
	  }
  
  public Map<Integer, LocatedBlockWithFileName> getDataBlocksInNode() {
	    return this.dataBlocksInRemoteNode;
	  }  
 
  public Map<Integer, LocatedBlockWithFileName> getParityBlocksInNode() {
	    return this.parityBlocksInRemoteNode;
	  }


  public void write(DataOutput out) throws IOException {
    if(getDataTransferVersion() >= DataTransferProtocol.FEDERATION_VERSION) {
    	out.writeInt(namespaceId);
    }
    out.writeInt(transferID);
    
    out.writeInt(transferInfo.length);
    for(int i=0; i<transferInfo.length; i++) {
    	LocatedBlockWithFileName lb = transferInfo[i];
    	lb.write(out);	
    }
   
    //byte[][] delta
    out.writeInt(transfer.length);
    for(int i=0; i<transfer.length; i++) {
    	 out.write(transfer[i]);
    }
    
    out.writeInt(dataBlocksInRemoteNode.size());
    for(int i=0; i<dataBlocksInRemoteNode.size(); i++) {
    	LocatedBlockWithFileName lb = dataBlocksInRemoteNode.get(i);
    	lb.write(out);	
    }
    out.writeInt(parityBlocksInRemoteNode.size());
    for(int i=0; i<parityBlocksInRemoteNode.size(); i++) {
    	LocatedBlockWithFileName lb = parityBlocksInRemoteNode.get(i);
    	lb.write(out);	
    }
    out.writeInt(nodesName.length);
    for(int i=0; i<nodesName.length; i++) {
    	Text.writeString(out, nodesName[i]);
    }
  }

  public void readFields(DataInput in) throws IOException {
    this.namespaceId = in.readInt();
    this.transferID = in.readInt();
    
    int parityBlocksSize = in.readInt();
    this.transferInfo = new LocatedBlockWithFileName[parityBlocksSize];
    for(int i=0; i<parityBlocksSize; i++) {
    	LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
    	lb.readFields(in);
    	transferInfo[i] = lb;
    }
    
    int deltaSize = in.readInt();
    this.transfer = new byte[deltaSize][];
    int blockSize = conf.getInt("dfs.block.size", 1048576);
    for(int i=0; i<deltaSize; i++) {
    	transfer[i] = new byte[blockSize];
    }  
    for(int i=0; i<deltaSize; i++) {
    	in.readFully(transfer[i]);
    }
    
    int dataBlocksInRemoteNodeSize = in.readInt();
    this.dataBlocksInRemoteNode = new HashMap<Integer, LocatedBlockWithFileName>();
    for(int i=0; i<dataBlocksInRemoteNodeSize; i++) {
    	LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
    	lb.readFields(in);
    	dataBlocksInRemoteNode.put(i, lb);
    }
    int parityBlocksInRemoteNodeSize = in.readInt();
    this.parityBlocksInRemoteNode = new HashMap<Integer, LocatedBlockWithFileName>();
    for(int i=0; i<parityBlocksInRemoteNodeSize; i++) {
    	LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
    	lb.readFields(in);
    	parityBlocksInRemoteNode.put(i, lb);
    }
    int nodesNameLength = in.readInt();
    this.nodesName = new String[nodesNameLength];
    for(int i=0; i<nodesNameLength; i++) {
    	this.nodesName[i] = Text.readString(in);
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
}

