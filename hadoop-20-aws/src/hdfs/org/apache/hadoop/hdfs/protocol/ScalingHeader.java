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
import org.apache.hadoop.raid.ScaleRSDown.computeOperation;

/**
 * Header for RaidNode to tell the each node some info
 */

public class ScalingHeader extends DataTransferHeader implements Writable {
 
  private int namespaceId;
  private int scalingID;
  private int idx; //Node id of a scaling group. Used in ScaleRS. 
  private String code = null;
  private String jsonStr = null;
  DistributedFileSystem fs;
  Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks;
  Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks;
  private String[] nodesName;

  Configuration conf;
  private ReadOptions options = new ReadOptions();

  public ScalingHeader(final VersionAndOpcode versionAndOp, Configuration conf) {
    super(versionAndOp);
    this.conf = conf;
  }

  public ScalingHeader(int dataTransferVersion, final int namespaceId, int scalingID, int idx,
    String jsonStr, String code, DistributedFileSystem fs, Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks, 
    Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks, byte operation, String[] nodesName) {
	
  //  super(dataTransferVersion, DataTransferProtocol.OP_SCALING);
    super(dataTransferVersion, operation);
    set(namespaceId, scalingID, idx, jsonStr, code, fs, dataBlocks, parityBlocks, nodesName);
  }

  public void set(int namespaceId, int scalingID, int idx, String jsonStr, String code,  DistributedFileSystem fs,
		  Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks, 
		  Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks, String[] nodesName) {

    this.namespaceId = namespaceId;
    this.scalingID = scalingID;
    this.idx = idx;
    this.jsonStr = jsonStr;
    this.code = code;
    this.fs = fs;
    this.dataBlocks = dataBlocks;
    this.parityBlocks = parityBlocks;
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
  
  public int getscalingID() {
	    return this.scalingID;
	  }
  public int getidx() {
	    return this.idx;
	  }

  public Map<String, Map<Integer, LocatedBlockWithFileName>> getDataBlocks() {
    return this.dataBlocks;
  }

  public Map<String, Map<Integer, LocatedBlockWithFileName>> getParityBlocks() {
	    return this.parityBlocks;
	  }

  public String getCode() {
    return this.code;
  }
  
  public String[] getNodesName() {
	    return this.nodesName;
	  }

  public String getJsonStr() {
    return this.jsonStr;
  }

  public void write(DataOutput out) throws IOException {
    if(getDataTransferVersion() >= DataTransferProtocol.FEDERATION_VERSION) {
    	out.writeInt(namespaceId);
    }
    out.writeInt(scalingID);
    out.writeInt(idx);
    out.writeInt(nodesName.length);
    for(int i=0; i<nodesName.length; i++) {
    	Text.writeString(out, nodesName[i]);
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

    Text.writeString(out, code);
    Text.writeString(out, jsonStr);
  }

  public void readFields(DataInput in) throws IOException {
    this.namespaceId = in.readInt();
    this.scalingID = in.readInt();
    this.idx = in.readInt();
    
    int nodesNameLength = in.readInt();
    this.nodesName = new String[nodesNameLength];
    for(int i=0; i<nodesNameLength; i++) {
    	this.nodesName[i] = Text.readString(in);
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

    this.code = Text.readString(in);
    this.jsonStr = Text.readString(in);
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

