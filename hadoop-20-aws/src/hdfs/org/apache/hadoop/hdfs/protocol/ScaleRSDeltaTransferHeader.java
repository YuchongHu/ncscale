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
 * Header for data node transfer delta parity blocks to remote node.
 */

public class ScaleRSDeltaTransferHeader extends DataTransferHeader implements Writable {
 
  private int namespaceId;
  private int deltaTransferID;
  DistributedFileSystem fs;
  Map<Integer, LocatedBlockWithFileName> parityBlocks;
  byte[][] delta;
  Configuration conf;
  private ReadOptions options = new ReadOptions();
  private String[] nodesName;
  
  public ScaleRSDeltaTransferHeader(final VersionAndOpcode versionAndOp, Configuration conf) {
    super(versionAndOp);
    this.conf = conf;
  }

  public ScaleRSDeltaTransferHeader(int dataTransferVersion, final int namespaceId, int deltaTransferID, 
    DistributedFileSystem fs, Map<Integer, LocatedBlockWithFileName> parityBlocks, byte[][] delta, String[] nodesName) {
    super(dataTransferVersion, DataTransferProtocol.OP_SCALERS_TRANSFER);
    set(namespaceId, deltaTransferID, fs, parityBlocks, delta, nodesName);
  }

  public void set(final int namespaceId, int deltaTransferID,
		    DistributedFileSystem fs, Map<Integer, LocatedBlockWithFileName> parityBlocks, byte[][] delta, String[] nodesName) {

    this.namespaceId = namespaceId;
    this.deltaTransferID = deltaTransferID;
    this.fs = fs;
    this.parityBlocks = parityBlocks;
    this.delta = delta;
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
	  
	  public int getDeltaTransferID() {
		    return this.deltaTransferID;
		  }

	  public Map<Integer, LocatedBlockWithFileName> getParityBlocks() {
	    return this.parityBlocks;
	  }

	  public byte[][] getDeltaBlocks() {
		    return this.delta;
		  }
	  public String[] getNodesName() {
		    return this.nodesName;
		  }

  public void write(DataOutput out) throws IOException {
	    if(getDataTransferVersion() >= DataTransferProtocol.FEDERATION_VERSION) {
	    	out.writeInt(namespaceId);
	    }
	    out.writeInt(deltaTransferID);
	    
	    out.writeInt(parityBlocks.size());
	    for(int i=0; i<parityBlocks.size(); i++) {
	    	LocatedBlockWithFileName lb = parityBlocks.get(i);
	    	lb.write(out);	
	    }
	   
	    //byte[][] delta
	    out.writeInt(delta.length);
	    for(int i=0; i<delta.length; i++) {
	    	 out.write(delta[i]);
	    }
	    out.writeInt(nodesName.length);
	    for(int i=0; i<nodesName.length; i++) {
	    	Text.writeString(out, nodesName[i]);
	    }
	    
	  }

  public void readFields(DataInput in) throws IOException {
	    this.namespaceId = in.readInt();
	    this.deltaTransferID = in.readInt();
	    
	    int parityBlocksSize = in.readInt();
	    this.parityBlocks = new HashMap<Integer, LocatedBlockWithFileName>();
	    for(int i=0; i<parityBlocksSize; i++) {
	    	LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
	    	lb.readFields(in);
	    	parityBlocks.put(i, lb);
	    }
	    
	    int deltaSize = in.readInt();
	    this.delta = new byte[deltaSize][];
	    int blockSize = conf.getInt("dfs.block.size", 1048576);
	    for(int i=0; i<deltaSize; i++) {
	    	delta[i] = new byte[blockSize];
	    }  
	    for(int i=0; i<deltaSize; i++) {
	    	in.readFully(delta[i]);
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

