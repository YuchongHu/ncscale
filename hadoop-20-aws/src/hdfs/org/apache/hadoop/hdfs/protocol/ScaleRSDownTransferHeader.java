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

public class ScaleRSDownTransferHeader extends DataTransferHeader implements Writable {
 
  private int namespaceId;
  private int transferID;
  DistributedFileSystem fs;
  LocatedBlockWithFileName[] transferInfo;
  byte[][] transfer;
  Configuration conf;
  private ReadOptions options = new ReadOptions();
  Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks;
  Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks;
  int transferType = 0;
  private String[] nodesName;
  
  public ScaleRSDownTransferHeader(final VersionAndOpcode versionAndOp, Configuration conf) {
    super(versionAndOp);
    this.conf = conf;
  }

  public ScaleRSDownTransferHeader(int dataTransferVersion, final int namespaceId, int transferID,
    DistributedFileSystem fs, LocatedBlockWithFileName[] transferInfo, byte[][] transfer, 
	  Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks, 
	  Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks, int transferType, String[] nodesName) {
    super(dataTransferVersion, DataTransferProtocol.OP_SCALERS_DOWN_TRANSFER);
    set(namespaceId, transferID, fs,  transferInfo, transfer, dataBlocks, parityBlocks, transferType, nodesName);
  }

  public void set(final int namespaceId, int transferID,
		    DistributedFileSystem fs,LocatedBlockWithFileName[] transferInfo, byte[][] transfer,
			  Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks, 
			  Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks, int transferType, String[] nodesName) {
    this.namespaceId = namespaceId;
    this.transferID = transferID;
    this.fs = fs;
    this.transferInfo = transferInfo;
    this.transfer = transfer;
    this.dataBlocks = dataBlocks;
    this.parityBlocks = parityBlocks;
    this.transferType = transferType;
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
  
  public int getTransferType() {
	    return this.transferType;
	  }

  public LocatedBlockWithFileName[] getTransferInfo() {
    return this.transferInfo;
  }

  public byte[][] getTransfer() {
	    return this.transfer;
	  }

  public Map<String, Map<Integer, LocatedBlockWithFileName>> getDataBlocks() {
	    return this.dataBlocks;
	  }

  public Map<String, Map<Integer, LocatedBlockWithFileName>> getParityBlocks() {
		return this.parityBlocks;
  	  }
  public String[] getNodesName() {
	    return this.nodesName;
	  }

  public void write(DataOutput out) throws IOException {
    if(getDataTransferVersion() >= DataTransferProtocol.FEDERATION_VERSION) {
    	out.writeInt(namespaceId);
    }
    out.writeInt(transferID);
    out.writeInt(transferType);
    
    out.writeInt(nodesName.length);
    for(int i=0; i<nodesName.length; i++) {
    	Text.writeString(out, nodesName[i]);
    }
    
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
    
    out.writeInt(dataBlocks.size());
    for(int i=0; i<dataBlocks.size(); i++) {
    	Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
    	dataBlocksInNode = dataBlocks.get(nodesName[i]); 
    	
    	out.writeInt(dataBlocksInNode.size());
    	for(int j=0; j<dataBlocksInNode.size(); j++) {
    		LocatedBlockWithFileName lb = dataBlocksInNode.get(j);
    	    lb.write(out);
    	}		
    }
    
    out.writeInt(parityBlocks.size());
    for(int i=0; i<parityBlocks.size(); i++) {
    	Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
    	parityBlocksInNode = parityBlocks.get(nodesName[i]); 
    	
    	out.writeInt(parityBlocksInNode.size());
    	for(int j=0; j<parityBlocksInNode.size(); j++) {
    		LocatedBlockWithFileName lb = parityBlocksInNode.get(j);
    	    lb.write(out);
    	}		
    }
  }

  public void readFields(DataInput in) throws IOException {
    this.namespaceId = in.readInt();
    this.transferID = in.readInt();
    this.transferType = in.readInt();
    
    int nodesNameLength = in.readInt();
    this.nodesName = new String[nodesNameLength];
    for(int i=0; i<nodesNameLength; i++) {
    	this.nodesName[i] = Text.readString(in);
    }
    
    int transferBlocksSize = in.readInt();
    this.transferInfo = new LocatedBlockWithFileName[transferBlocksSize];
    for(int i=0; i<transferBlocksSize; i++) {
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
    
    int dataBlocksSize = in.readInt();
    this.dataBlocks = new HashMap<String, Map<Integer, LocatedBlockWithFileName>>();
    for(int i=0; i<dataBlocksSize; i++) {
    	int dataBlocksInNodeSize = in.readInt();
    	Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
        for(int j=0; j<dataBlocksInNodeSize; j++) {
          LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
          lb.readFields(in);
          dataBlocksInNode.put(j, lb);
        }
    	this.dataBlocks.put(nodesName[i], dataBlocksInNode);
    }
    
    int parityBlocksSize = in.readInt();
    this.parityBlocks = new HashMap<String, Map<Integer, LocatedBlockWithFileName>>();
    for(int i=0; i<parityBlocksSize; i++) {
    	int parityBlocksInNodeSize = in.readInt();
    	Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
        for(int j=0; j<parityBlocksInNodeSize; j++) {
          LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
          lb.readFields(in);
          parityBlocksInNode.put(j, lb);
        }
    	this.parityBlocks.put(nodesName[i], parityBlocksInNode);
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

