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
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.util.InjectionHandler;

/**
 */
public class ReadGroupHeader extends DataTransferHeader implements Writable {
  
  public static final int DRC_PLACEMENT_A = 0;

  private int namespaceId;
  private int groupId;
  private int groupSize;
  private Map<Integer, LocatedBlockWithFileName> groupBlocks;
  private int[] idxArray;
  private int[] corruptArray;
  private String jsonStr;
  private int toReadInGroup;
  private int bufferSize;
  private boolean verifyChecksum;
  private String clientName;
  private long bytesToCheckReadSpeed;
  private long minSpeedBps;
  private boolean reuseConnection = false;
  private boolean shouldProfile = false;
  private ReadOptions options = new ReadOptions();
  private String code;
  private int outputUnitNum;
    
  public ReadGroupHeader(final VersionAndOpcode versionAndOp) {
    super(versionAndOp);
  }

  public ReadGroupHeader(int dataTransferVersion, final int namespaceId, int groupId,
      int groupSize, Map<Integer, LocatedBlockWithFileName> groupBlocks, int[] idxArray,
      int[] corruptArray, String jsonStr, int toReadInGroup, int bufferSize, 
      boolean verifyChecksum, String clientName, long bytesToCheckReadSpeed,
      long minSpeedBps, boolean reuseConnection, boolean shouldProfile, String code,
      int outputUnitNum) {
    
    super(dataTransferVersion, DataTransferProtocol.OP_DRC_RACK_LEVEL);
    set(namespaceId, groupId, groupSize, groupBlocks, idxArray, corruptArray, jsonStr,
        toReadInGroup, bufferSize, verifyChecksum, clientName, bytesToCheckReadSpeed,
        minSpeedBps, reuseConnection, shouldProfile, code, outputUnitNum);
  }

  public void set(int namespaceId, int groupId, int groupSize, 
      Map<Integer, LocatedBlockWithFileName> groupBlocks, int[] idxArray,
      int[] corruptArray, String jsonStr, int toReadInGroup, int bufferSize,
      boolean verifyChecksum, String clientName, long bytesToCheckReadSpeed,
      long minSpeedBps, boolean reuseConnection, boolean shouldProfile, String code,
      int outputUnitNum) {
    this.namespaceId = namespaceId;
    this.groupId = groupId;
    this.groupSize = groupSize;
    this.groupBlocks = groupBlocks;
    this.idxArray = idxArray;
    this.corruptArray = corruptArray;
    this.jsonStr = jsonStr;
    this.toReadInGroup = toReadInGroup;
    this.bufferSize = bufferSize;
    this.verifyChecksum = verifyChecksum;
    this.clientName = clientName;
    this.bytesToCheckReadSpeed = bytesToCheckReadSpeed;
    this.minSpeedBps = minSpeedBps;
    this.reuseConnection = reuseConnection;
    this.shouldProfile = shouldProfile;
    this.code = code;
    this.outputUnitNum = outputUnitNum;
  }

  public void setReadOptions(ReadOptions opt) {
    if(opt == null) {
      throw new IllegalArgumentException("options cannot be null");
    }

    this.options = opt;
  }
    
  public int getNamespaceId() {
    return this.namespaceId;
  }

  public int getGroupId() {
    return this.groupId;
  }

  public int getGroupSize() {
    return this.groupSize;
  }

  public Map<Integer, LocatedBlockWithFileName> getGroupBlocks() {
    return this.groupBlocks;
  }

  public int[] getIdxArray() {
    return this.idxArray;
  }

  public int[] getCorruptArray() {
    return this.corruptArray;
  }

  public String getJsonStr() {
    return this.jsonStr;
  }

  public int getToReadInGroup() {
    return this.toReadInGroup;
  }

  public int getBufferSize() {
    return this.bufferSize;
  }

  public boolean getVerifyChecksum() {
    return this.verifyChecksum;
  }

  public String getClientName() {
    return this.clientName;
  }

  public long getBytesToCheckReadSpeed() {
    return this.bytesToCheckReadSpeed;
  }

  public long getMinSpeedBps() {
    return this.minSpeedBps;
  }

  public boolean getReuseConnection() {
    return this.reuseConnection;
  }

  public boolean getShouldProfile() {
    return this.shouldProfile;
  }

  public String getCode() {
    return this.code;
  }

  public ReadOptions getReadOptions() {
    return this.options;
  }

  public int getOutputUnitNum() {
    return this.outputUnitNum;
  }

  public void write(DataOutput out) throws IOException {
    InjectionHandler.processEvent(InjectionEvent.READ_BLOCK_HEAD_BEFORE_WRITE);

    if(getDataTransferVersion() >= DataTransferProtocol.FEDERATION_VERSION) {
      out.writeInt(namespaceId);
    }

    out.writeInt(groupId);
    out.writeInt(groupSize);

    out.writeInt(idxArray.length);
    for(int i=0; i<idxArray.length; i++) {
      int idx = idxArray[i];
      out.writeInt(idx);
      LocatedBlockWithFileName lb = groupBlocks.get(idx);
      lb.write(out);
    }

    out.writeInt(corruptArray.length);
    for(int i=0; i<corruptArray.length; i++) {
      out.writeInt(corruptArray[i]);
    }

    Text.writeString(out, jsonStr);
    out.writeInt(toReadInGroup);
    out.writeInt(bufferSize);
    out.writeBoolean(verifyChecksum);
    Text.writeString(out, clientName);
    out.writeLong(bytesToCheckReadSpeed);
    out.writeLong(minSpeedBps);
    out.writeBoolean(reuseConnection);
    out.writeBoolean(shouldProfile);
    Text.writeString(out, code);
    out.writeInt(outputUnitNum);
  }
  
  public void readFields(DataInput in) throws IOException {
    namespaceId = in.readInt();
    groupId = in.readInt();
    groupSize = in.readInt();

    int idxLen = in.readInt();
    idxArray = new int[idxLen];
    groupBlocks = new HashMap<Integer, LocatedBlockWithFileName>();
    for(int i=0; i<idxLen; i++) {
      int idx = in.readInt();
      idxArray[i] = idx;
      LocatedBlockWithFileName lb = new LocatedBlockWithFileName();
      lb.readFields(in);
      groupBlocks.put(idx, lb);
    }

    int corruptLen = in.readInt();
    corruptArray = new int[corruptLen];
    for(int i=0; i<corruptLen; i++) {
      corruptArray[i] = in.readInt();
    }

    jsonStr = Text.readString(in);
    toReadInGroup = in.readInt();
    bufferSize = in.readInt();
    verifyChecksum = in.readBoolean();
    clientName = Text.readString(in);
    bytesToCheckReadSpeed = in.readLong();
    minSpeedBps = in.readLong();
    reuseConnection = in.readBoolean();
    shouldProfile = in.readBoolean();
    code = Text.readString(in);
    outputUnitNum = in.readInt();
  }
    
}
