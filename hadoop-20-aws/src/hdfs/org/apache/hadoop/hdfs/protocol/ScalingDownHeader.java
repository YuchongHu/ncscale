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
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;

/**
 * Scale-down
 * Header for RaidNode to tell the each node some info
 */

public class ScalingDownHeader extends DataTransferHeader implements Writable {
 
  private int namespaceId;
  private int scalingID;
  private int idx; //Node id of a scaling group. Used in ScaleRS. 
  private String code = null;
  private String jsonStr = null;
  DistributedFileSystem fs;
  Map<Integer,Map<Integer, Map<Integer, LocatedBlockWithFileName>>> blocksInfo;
  
  Configuration conf;
  private ReadOptions options = new ReadOptions();

  public ScalingDownHeader(final VersionAndOpcode versionAndOp, Configuration conf) {
    super(versionAndOp);
    this.conf = conf;
  }

  public ScalingDownHeader(int dataTransferVersion, final int namespaceId, int scalingID, int idx,
    String jsonStr, String code, DistributedFileSystem fs, Map<Integer,Map<Integer, Map<Integer, LocatedBlockWithFileName>>> blocksInfo, byte operation) {
	
  //  super(dataTransferVersion, DataTransferProtocol.OP_SCALING);
    super(dataTransferVersion, operation);
    set(namespaceId, scalingID, idx, jsonStr, code, fs, blocksInfo);
  }

  public void set(int namespaceId, int scalingID, int idx, String jsonStr, String code,  DistributedFileSystem fs,
		  Map<Integer,Map<Integer, Map<Integer, LocatedBlockWithFileName>>> blocksInfo) {

    this.namespaceId = namespaceId;
    this.scalingID = scalingID;
    this.idx = idx;
    this.jsonStr = jsonStr;
    this.code = code;
    this.fs = fs;
    this.blocksInfo = blocksInfo;
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

  public Map<Integer,Map<Integer, Map<Integer, LocatedBlockWithFileName>>> getBlocksInfo() {
    return this.blocksInfo;
  }

  public String getCode() {
    return this.code;
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
    
    out.writeInt(blocksInfo.size());
    for(int i=0; i<blocksInfo.size(); i++) 
    {
    	out.writeInt(blocksInfo.get(i).size());
        for(int j = 0; j < blocksInfo.get(i).size(); j++)
        {
        	out.writeInt(blocksInfo.get(i).get(j).size());
        	for(int k = 0; k < blocksInfo.get(i).get(j).size(); k++)
        	{
        		LocatedBlockWithFileName lb = blocksInfo.get(i).get(j).get(k);
        	    lb.write(out);
        	}
        }
    }

    Text.writeString(out, code);
    Text.writeString(out, jsonStr);
  }

  public void readFields(DataInput in) throws IOException {
    this.namespaceId = in.readInt();
    this.scalingID = in.readInt();
    this.idx = in.readInt();
    
    int blocksInfoSize = in.readInt();
    this.blocksInfo = new HashMap<Integer,Map<Integer, Map<Integer, LocatedBlockWithFileName>>>();
	for(int i = 0; i < blocksInfoSize; i++)
	{
		int setInfoSize = in.readInt();
		Map<Integer, Map<Integer, LocatedBlockWithFileName>> blocksInSet = 
				  new HashMap<Integer, Map<Integer, LocatedBlockWithFileName>>();
		for(int j = 0; j < setInfoSize; j++)
		{
			int stripeInfoSize = in.readInt();
			Map<Integer, LocatedBlockWithFileName> blocksInNode = 
					  new HashMap<Integer, LocatedBlockWithFileName>();
			for(int k = 0; k < stripeInfoSize; k++)
			{
		          LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
		          lb.readFields(in);
		          blocksInNode.put(k, lb);
			}
			blocksInSet.put(j, blocksInNode);
		}
		blocksInfo.put(i, blocksInSet);
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

