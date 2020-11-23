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

import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;

/**
 * Header for RaidNode to tell the targetnode some info
 */

public class TargetHeader extends DataTransferHeader implements Writable {
 
  private int namespaceId;
  private Map<Integer, LocatedBlockWithFileName> stripeBlocks;
  int[] corruptArray;
  private String code = null;
  private String jsonStr = null;

  private ReadOptions options = new ReadOptions();

  public TargetHeader(final VersionAndOpcode versionAndOp) {
    super(versionAndOp);
  }

  public TargetHeader(int dataTransferVersion, final int namespaceId, 
    Map<Integer, LocatedBlockWithFileName> stripeBlocks, int[] corruptArray,
    String jsonStr, String code) {
    super(dataTransferVersion, DataTransferProtocol.OP_DRC_TARGET_LEVEL);
    set(namespaceId, stripeBlocks, corruptArray, jsonStr, code);
  }

  public void set(int namespaceId, Map<Integer, LocatedBlockWithFileName> stripeBlocks, 
      int[] corruptArray, String jsonStr, String code) {

    this.namespaceId = namespaceId;
    this.stripeBlocks = stripeBlocks;
    this.corruptArray = corruptArray;
    this.jsonStr = jsonStr;
    this.code = code;
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

  public Map<Integer, LocatedBlockWithFileName> getStripeBlocks() {
    return this.stripeBlocks;
  }

  public int[] getCorruptArray() {
    return this.corruptArray;
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
    
    out.writeInt(stripeBlocks.size());
    for(int i=0; i<stripeBlocks.size(); i++) {
      LocatedBlockWithFileName lb = stripeBlocks.get(i);
      lb.write(out);
    }

    out.writeInt(corruptArray.length);
    for(int i=0; i<corruptArray.length; i++) {
      out.writeInt(corruptArray[i]);
    }

    Text.writeString(out, code);
    Text.writeString(out, jsonStr);
  }

  public void readFields(DataInput in) throws IOException {
    this.namespaceId = in.readInt();

    int stripeBlocksSize = in.readInt();
    this.stripeBlocks = new HashMap<Integer, LocatedBlockWithFileName>();
    for(int i=0; i<stripeBlocksSize; i++) {
      LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
      lb.readFields(in);
      this.stripeBlocks.put(i, lb);
    }

    int corruptArrayLength = in.readInt();
    this.corruptArray = new int[corruptArrayLength];
    for(int i=0; i<corruptArrayLength; i++) {
      corruptArray[i] = in.readInt();
    }

    this.code = Text.readString(in);
    this.jsonStr = Text.readString(in);
  }
}

