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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.BlockMissingException;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.TargetHeader;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocksWithMetaInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.raid.Codec;
import org.apache.hadoop.raid.DRCCode;
import org.apache.hadoop.raid.ErasureCode;
import org.apache.hadoop.raid.StripeReader;
import org.apache.hadoop.raid.StripeReader.LocationPair;
import org.apache.hadoop.raid.StripeStore.StripeInfo;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.Progressable;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONException;
import org.json.JSONObject;

public class Target {

  public static final Log LOG = LogFactory.getLog(Target.class);
  public static final String ERASURE_CODE_KEY_PREFIX = "hdfs.raid.erasure.code.";

  private Codec codec;
  private Map<Integer, LocatedBlockWithFileName> stripeBlocks;
  private int[] corruptArray;
  private Configuration conf;
  private Progressable progress;

  private int[] dataArray;
  private int[] groupArray;
  private JSONObject json;
  private String erasureCodeClass;
  private ErasureCode code;
  private int groupNum;
  private int groupSize;

  // for new interface
  private int stripSize;
  private int[] lenArray;
  private int[] nodeLenArray = null;
  private int[] groupLenArray = null;

  Target(Codec codec, Map<Integer, LocatedBlockWithFileName> stripeBlocks, int[] corruptArray,
      Configuration conf, Progressable progress) 
    throws IOException {
    this.codec = codec;
    this.stripeBlocks = stripeBlocks;
    this.corruptArray = corruptArray;
    this.conf = conf;
    this.progress = progress;

    init(conf);

	this.stripSize = conf.getInt("hdfs.raid.strip.size", 1024*1024);
	this.lenArray = new int[2];

    // TODO: modify this function;
    boolean res = verify(corruptArray, dataArray, groupArray,
	    stripSize, nodeLenArray, groupLenArray);
    LOG.info("Target.constructor.recoverable = " + res);	
//    dump("corruptArray", corruptArray);
//    dump("dataArray", dataArray);
//    dump("groupArray", groupArray);
  }

  private void init(Configuration conf) throws IOException {
    try{
      String jsonStr = codec.jsonStr;
      json = new JSONObject(jsonStr);
      erasureCodeClass = json.getString("erasure_code");
      groupNum = json.getInt("group_num");
      groupSize = json.getInt("group_size");
    } catch (JSONException e) {
      throw new IOException(e);
    }
    this.code = createErasureCode(conf);

    dataArray = new int[codec.stripeLength+codec.parityLength];
    groupArray = new int[groupNum];
    nodeLenArray = new int[codec.stripeLength+codec.parityLength];
    groupLenArray = new int[groupNum];
  }
 
  public ErasureCode createErasureCode(Configuration conf) {
    Class<?> erasureCode = null;

    try {
      erasureCode = conf.getClass(ERASURE_CODE_KEY_PREFIX + codec.id,
          conf.getClassByName(this.erasureCodeClass));
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }

    ErasureCode code = (ErasureCode)ReflectionUtils.newInstance(erasureCode,
        conf);
    code.init(codec.stripeLength, codec.parityLength, codec.jsonStr);
    return code;
  }

  public boolean verify(int[] corruptArray, int[] dataArray, int[] groupArray,
      int dataLen, int[] nodeLenArray, int[] groupLenArray) 
      throws IOException {
    boolean res = false;
    if(codec.id.equals("drc"))
      res = ((DRCCode)code).verify(corruptArray, dataArray, groupArray, dataLen,
	      nodeLenArray, groupLenArray);
    return res;
  }

  public void dump(String name, int[] array) {
    int len = array.length;
    for(int i=0; i<len ;i++) {
      LOG.info("Target.dump " + name + ",i = " + i + " value = " + array[i]);
    }
  }

  public String findARack(Map<Integer, List<Integer>> map, 
      int corruptGroup) 
      throws IOException {
    // this function is only used for find a rack for group corruption
    
    //1. get all racks from namenode
    Path p = new Path("/");
    DistributedFileSystem fs = getDFS(p, this.conf);
    String[] racks = fs.getRacks();
    for(Map.Entry<Integer, List<Integer>> entry: map.entrySet()) {
      if(entry.getKey() == corruptGroup) {
        continue;
      }
      List<Integer> list = entry.getValue();
      DatanodeInfo[] locs = stripeBlocks.get(list.get(0)).getLocations();
      String loc = locs[0].getNetworkLocation();
      for(int i=0; i<racks.length; i++) {
        if(loc.equals(racks[i])) {
          racks[i] = null;
          break;
        }
      }
    }
    for(int i=0; i<racks.length; i++) {
      if(racks[i]!=null) {
        LOG.info("Target.findARack.rack = " + racks[i]);
        return racks[i];
      }
    }

    return "/default-rack";

  }

  public boolean findTargetNode()
      throws IOException {
    long start1 = System.nanoTime();
    DatanodeInfo targetNode = null;
    
    //1.get the group information from code 
    Map<Integer, List<Integer>> map = new HashMap<Integer, List<Integer>>();
    
    if(codec.id.equals("drc")) {
      map = ((DRCCode)code).placement();
    }

    //2.find the idx for one corrupt node
    int corruptId = -1;
    int corruptRack = -1; 
    for(int i=0; i<corruptArray.length; i++) {
      if(corruptArray[i] == 1) {
        corruptId = i;
        break;
      }
    }

    //3.get other index in the same group with that corrupt node
    //  if only one node corrupt, i can get the rack holds these blocks, then choose one node in this rack
    //  if group corruption, then first get the whole racks and delete the other group of racks then choose
    //    one node in the ramain racks as new rack and new node
    int corruptKind = -1;    // 0 -> one node 1 -> group
    String rack = null;
    for(Map.Entry<Integer, List<Integer>> entry: map.entrySet()) {
//      LOG.info("Target.findTargetNode.groupnum = " + entry.getKey());
      List<Integer> list = entry.getValue();
      if(list.contains(corruptId)) {
        int len = list.size();
        int numCorrupt = 0;
        for(Integer i : list) {
          if(corruptArray[i]==1) {
            numCorrupt++;
          }
        }
        if(numCorrupt < len) {
//          LOG.info("Target.findTargetNode.find " + numCorrupt + " corruption");
          for(Integer i: list) {
            if(corruptArray[i]==0) {
              DatanodeInfo[] locs = stripeBlocks.get(i).getLocations();
              rack = locs[0].getNetworkLocation();
//              LOG.info("Target.findTargetNode.onenode corruption.rack = " + rack);
              break;
            }
          }
        } else if(numCorrupt == len) {
//          LOG.info("Target.findTargetNode.group corruption");
          rack = findARack(map, entry.getKey());
        } else {
          LOG.info("Target.findTargetNode.cant deal with this corruption");
        }
          
        break;
      }
    }

    assert rack != null;
//    LOG.info("Target.findTargetNode.targetRack = " + rack);

    //3.get all datanodes in this rack and randomly choose one as targetnode
    Path p = new Path("/");
    DistributedFileSystem fs = getDFS(p, this.conf);

// load balance
    String targetname = fs.getDataNodeByRackName(rack);

/*  not load balance */
/*    String[] datanodes = fs.getDatanodesByRack(rack);
    Random random = new Random();
    int datanodeid = random.nextInt(datanodes.length);
    String targetname = datanodes[datanodeid];
*/
    //
    InetSocketAddress targetAddr = NetUtils.createSocketAddr(targetname);
    LOG.info("Target.findTargetNode.targetaddr = " + targetAddr);
    InetAddress nodeaddress = targetAddr.getAddress();
    DFSClient targetClient = fs.getClient();

    Socket sock = targetClient.getsocketFactory().createSocket();
    sock.setTcpNoDelay(true);
    NetUtils.connect(sock, targetAddr, targetClient.getsocketTimeout());
    sock.setSoTimeout(targetClient.getsocketTimeout());
//    LOG.info("Target.findTargetNode.trying to connect to targetnode");

    DataOutputStream out = null;
    out = new DataOutputStream(
        new BufferedOutputStream(NetUtils.getOutputStream(sock,HdfsConstants.WRITE_TIMEOUT)));

    String codeid = null;

    if(codec.id.equals("drc")) {
//      LOG.info("Target.findTargetNode.create DRCTargetHeader");
      codeid = new String("drc");
    }
    
    TargetHeader targetHeader = new TargetHeader(targetClient.getDataTransferProtocolVersion(),
        targetClient.getnamespaceId(), stripeBlocks, corruptArray, codec.jsonStr, codeid);
    targetHeader.setReadOptions(new ReadOptions());
    targetHeader.writeVersionAndOpCode(out);
    targetHeader.write(out);
    
	long start2 = System.nanoTime();
	LOG.info("Target.findTargetNode.findtime = " + (start2-start1));

    out.flush();

    DataInputStream in = new DataInputStream(
        new BufferedInputStream(NetUtils.getInputStream(sock)));

    if(in.readShort() == DataTransferProtocol.OP_STATUS_SUCCESS) {
      LOG.info("DRCTarget.findTargetNode.targetrack.have receive the response from targetnode");
      return true;
    }

    return false;

  }
  
  protected DistributedFileSystem getDFS(Path p, Configuration conf) throws IOException {
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
