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
package org.apache.hadoop.hdfs.server.namenode;

import org.apache.commons.logging.*;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;
import org.apache.hadoop.util.HostsFileReader;
import org.apache.hadoop.raid.Codec;
import org.apache.hadoop.hdfs.server.namenode.BlockPlacementPolicyDefault;

import java.io.IOException;
import java.util.*;
import org.apache.commons.lang.ArrayUtils;

/**
 * This block placement policy works for Double Regenerating Code:
 * 
 * (n, k, r) - DRC, n blocks of a stripe should be placed in r racks. 
 * Take (6, 4, 3) - DRC for example, 4 data blocks and 2 parity blocks in a
 * stripe are evenly placed in 3 racks.
 */
public class BlockPlacementPolicyDrc extends BlockPlacementPolicyRaid {
  private FSNamesystem namesystem = null;
  private boolean considerLoad = true;

  /* For unit test */
  private static Set<String> badRacks = new HashSet<String>();
  private static Set<String> badHosts = new HashSet<String>();

  // ZM add to fast place parity
  //private static Map<String, Integer> parStripeList = new HashMap<String, Integer>();
  private static int srcStripesCount = 0;

  /* hardcoded implementation */
  private int drcK = 4;
  private int drcR = 2;
  private int drcS = 2;
  private int groupNum = 3;
  private int groupSize = 2;
  private int drcType = 1;
  private int nodeNum = 6;

  BlockPlacementPolicyDrc(Configuration conf,
      FSClusterStats stats, NetworkTopology clusterMap) {
    initialize(conf, stats, clusterMap, null, null, null);
  }

  BlockPlacementPolicyDrc() {
  }

  /** A function to be used by unit tests only */
  public static void setBadHostsAndRacks(Set<String> racks,
      Set<String> hosts) {
    badRacks = racks;
    badHosts = hosts;
  }

  /** {@inheritDoc} */
  public void initialize(Configuration conf, FSClusterStats stats,
      NetworkTopology clusterMap, HostsFileReader hostsReader,
      DNSToSwitchMapping dnsToSwitchMapping, FSNamesystem namesystem) {
    super.initialize(conf, stats,
      clusterMap, hostsReader, dnsToSwitchMapping, namesystem);
    this.namesystem = namesystem;
    this.considerLoad = conf.getBoolean("dfs.replication.considerLoad", true);
    FSNamesystem.LOG.info("DRC: Block placement will consider load: "
      + this.considerLoad);
    
//    this.drcType = conf.getInt("hdfs.raid.drc.drc_type", 1);
//    if(15 == drcType)
//    {
        /* reading DRC settings */
//    	this.drcK = conf.getInt("hdfs.raid.drc.stripe_length", 4);
//    	this.drcR = conf.getInt("hdfs.raid.drc.parity_length", 2);
//    	this.groupNum = conf.getInt("hdfs.raid.drc.group_num", 3);
//    	this.groupSize = conf.getInt("hdfs.raid.drc.group_size", 2);
//      LOG.info("drc.stripe_length = " + code.stripeLength);
//      LOG.info("drc.parity_length = " + code.parityLength);
//      LOG.info("drc.group_num = " + code.groupNum);
//      LOG.info("drc.group_size = " + code.groupSize);
//      LOG.info("drc.drcType = " + code.drcType);
//    }
//    else
//    { 
    	Codec code = Codec.getCodec("drc");
    	this.drcK = code.stripeLength;
    	this.drcR = code.parityLength;
    	this.drcS = code.newNode;
    	this.groupNum = code.groupNum;
    	this.groupSize = code.groupSize;
    	this.drcType = code.drcType;	
    	this.nodeNum = code.nodeNum;
//    }
    LOG.info("DRC: Block Placement Initialized: drcK: " + drcK +
    	    " drcR: " + drcR + " groupNum: " + groupNum + " groupSize: " + groupSize + " drcType: " + drcType);
  }

  /** {@inheritDoc} */
  @Override
  public DatanodeDescriptor[] chooseTarget(String srcPath, 
    int numOfReplicas,
    DatanodeDescriptor writer,
    List<DatanodeDescriptor> chosenNodes,
    long blocksize) {
    return chooseTarget(
      srcPath, numOfReplicas, writer, chosenNodes, null, blocksize);
  }

  /** {@inheritDoc} */
  @Override
  public DatanodeDescriptor[] chooseTarget(
      String srcPath, int numOfReplicas, DatanodeDescriptor writer,
      List<DatanodeDescriptor> chosenNodes, List<Node> excludesNodes, long blocksize) {
    if (numOfReplicas > 2) {
      return super.chooseTarget(
        srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
    }
    try {
      FileType type = getFileType(srcPath);
      switch(type) {
        case NOT_DRC: 
          return super.chooseTarget(
            srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
        case DRC_DATA:
          if (15 == this.drcType){
        	  return chooseTargetRSRR(
        			  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, true);
          }else if (16 == this.drcType) {
        	  return chooseTargetMBRRR(
        			  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, true);
          }else if (17 == this.drcType) {
        	  return chooseTargetScaleRS(
        			  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, true);
          }else if (18 == this.drcType) {
        	  return chooseTargetScaleRSDown(
        			  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, true);
          }else if (2 == this.drcType) {
            return chooseTargetDrcTwo(
                srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, true);
          } else {
            return chooseTargetDrcOne(
                srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, true);
          }
        case DRC_TMP_PARITY:
        case DRC_PARITY:
        	if (15 == this.drcType){
          	return chooseTargetRSRR(
          			  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, false);
        	}else if (16 == this.drcType) {
        	return chooseTargetMBRRR(
            		  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, false);
        	}else if (17 == this.drcType) {
        	return chooseTargetScaleRS(
            		  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, false);
            }else if (18 == this.drcType) {
          	  return chooseTargetScaleRSDown(
          			  srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, false);
            }else if (2 == this.drcType) {
            return chooseTargetDrcTwo(
                srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, false);
          } else {
            return chooseTargetDrcOne(
                srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize, false);
          }
        default:
            return super.chooseTarget(
              srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
      } 
    } catch (IOException e) {
        return super.chooseTarget(
            srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
    }
  }

  /**
   * This function implement BlockPlacementPolicyDrc policy
   * for Double Regenerating Code.
   * ZM add in Apr, 2016
   */
  private DatanodeDescriptor[] chooseTargetDrcOne(
		    String srcPath,
		    int numOfReplicas,
		    DatanodeDescriptor writer,
		    List<DatanodeDescriptor> chosenNodes,
		    List<Node> excludesNodes,
		    long blocksize,
		    boolean isSource) {
		    FSNamesystem.LOG.info("BlockPlacementPolicyDrc: DRC-1 policy is invoked for file: " + srcPath 
		      + ", with replica count: " + numOfReplicas);
		    LocatedBlocks blocks;
		    int blockIndex = -1;
		    try {
		      blocks = this.namesystem.getBlockLocations(srcPath, 0, Long.MAX_VALUE);
		      blockIndex = blocks.getLocatedBlocks().size();
		    } catch (IOException e) {
		      FSNamesystem.LOG.error(
		        "BlockPlacementPolicyDrc: Error happened when calling getFileInfo()/getBlockLocations()");
		      return super.chooseTarget(
		        srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
		    }
		    
		    int rackNum = this.clusterMap.getNumOfRacks();
		    int stripeIndex;
		    int blockIndexInStripe;
		    if (isSource) {
		      // ZM add to fast place source START on Sep 2, 2016
		      if (blockIndex % this.drcK == 0) srcStripesCount++;
		      // ZM add to fast place source START on Sep 2, 2016
		      // stripeIndex = blockIndex / this.drcK;
		      stripeIndex = srcStripesCount-1;
		      blockIndexInStripe = blockIndex % this.drcK;
		    } else {
		      // ZM add to fast place parity START on Sep 2, 2016
		      try {
		          String[] srcPathSplits = srcPath.split("-");
		          stripeIndex = Integer.valueOf(srcPathSplits[srcPathSplits.length-1]);
		      } catch (NumberFormatException e) {
		          stripeIndex = 0;
		      }
		      // int parStripeListSize = parStripeList.size();
		      // if (parStripeList.get(srcPathSplits[srcPathSplits.length-1]) == null) {
		      //   parStripeList.put(srcPathSplits[srcPathSplits.length-1], new Integer(parStripeListSize+1));
		      //   stripeIndex = parStripeListSize;
		      // } else {
		      //   stripeIndex = parStripeList.get(srcPathSplits[srcPathSplits.length-1]).intValue()-1;
		      // }
		      // ZM add to fast place parity END 
		        
		      //stripeIndex = blockIndex / this.drcR;
		      blockIndexInStripe = blockIndex % this.drcR + this.drcK;
		    }
		    //zm debug
		    LOG.info("ZMDEBUG BlockPlacementPolicyDrc: srcPath = " + srcPath);
		    LOG.info("ZMDEBUG BlockPlacementPolicyDrc: blockIndex = " + blockIndex);
		    LOG.info("ZMDEBUG BlockPlacementPolicyDrc: stripeIndex = " + stripeIndex);
		    LOG.info("ZMDEBUG BlockPlacementPolicyDrc: blockIndexInStripe = " + blockIndexInStripe);
		    //zm debug
		    DatanodeDescriptor[] ret = new DatanodeDescriptor[numOfReplicas];   
		    // // ZM modify on Sep 21 --start
		    // if ( this.drcType == 5 || this.drcType == 6 ) {
		    //     // DRC643 or DRC633
		    //     int rackId = -1;
		    //     if (blockIndexInStripe%6 == 0 || blockIndexInStripe%6 == 1) {
		    //         // the first two blocks are always stored on the first rack
		    //         rackId = 0;
		    //     } else {
		    //         rackId = (stripeIndex%5) + 1;// the third and the fourth block
		    //         // the last two blocks
		    //         if (blockIndexInStripe%6 == 4 || blockIndexInStripe%6 == 5) {
		    //             rackId = rackId%5 + 1;
		    //         }
		    //     }
		    //     List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
		    //     int candNodeId = (blockIndexInStripe+(stripeIndex/5)%2) % candidateNodes.size();
		    //     ret[0] = (DatanodeDescriptor)candidateNodes.get(candNodeId); 
		    //     FSNamesystem.LOG.info("DRC: rackId: " + rackId + " candNodeId: " + candNodeId
		    //                 + " DatanodeDescriptor: " + ret[0]); 
		    // 
		    // } else if ( this.drcType == 3 || this.drcType == 10 ) {
		    //     // IA633 or Butterfly64
		    //     int rackId = blockIndexInStripe%6;
		    //     List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
		    //     int candNodeId = (stripeIndex) % candidateNodes.size();
		    //     if (rackId == 0) candNodeId = 0; 
		    //     ret[0] = (DatanodeDescriptor)candidateNodes.get(candNodeId); 
		    //     FSNamesystem.LOG.info("DRC: rackId: " + rackId + " candNodeId: " + candNodeId
		    //                 + " DatanodeDescriptor: " + ret[0]); 
		    // 
		    // } else {
		    //     int rackId = -1;
		    //     //int nodeId = -1;
		    //     for(int replicaId = 0; replicaId < numOfReplicas; replicaId++) {
		    //         rackId = (smear(stripeIndex + replicaId) + blockIndexInStripe / this.groupSize) % rackNum;
		    //         List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
		    //         int candNodeId = (smear(stripeIndex + replicaId) + blockIndexInStripe % this.groupSize) % candidateNodes.size();
		    //         ret[replicaId] = (DatanodeDescriptor)candidateNodes.get(candNodeId); 
		    //         FSNamesystem.LOG.info("DRC: replicaId: " + replicaId + " rackId: " + rackId + " candNodeId: " + candNodeId
		    //                 + " DatanodeDescriptor: " + ret[replicaId]); 
		    //     }
		    // }
		    // ZM modify on Sep 21 --end
		    int rackId = -1;
		    //int nodeId = -1;
		    for(int replicaId = 0; replicaId < numOfReplicas; replicaId++) {
		        rackId = (smear(stripeIndex + replicaId) + blockIndexInStripe / this.groupSize) % rackNum;
		        List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
		        int candNodeId = (smear(stripeIndex + replicaId) + blockIndexInStripe % this.groupSize) % candidateNodes.size();
		        ret[replicaId] = (DatanodeDescriptor)candidateNodes.get(candNodeId); 
		        FSNamesystem.LOG.info("DRC: replicaId: " + replicaId + " rackId: " + rackId + " candNodeId: " + candNodeId
		                + " DatanodeDescriptor: " + ret[replicaId]); 
		    }
		    return ret;
		  }
  
  /**
   * This function implement BlockPlacementPolicyDrc policy
   * for Double Regenerating Code.
   * ZM add in Apr, 2016
   */
  private DatanodeDescriptor[] chooseTargetDrcTwo(
    String srcPath,
    int numOfReplicas,
    DatanodeDescriptor writer,
    List<DatanodeDescriptor> chosenNodes,
    List<Node> excludesNodes,
    long blocksize,
    boolean isSource) {
    FSNamesystem.LOG.info("DRC: DRC - (6, 3, 3) policy is invoked for file: " + srcPath 
      + ", with replica count: " + numOfReplicas);
    LocatedBlocks blocks;
    int blockIndex = -1;
    try {
      blocks = this.namesystem.getBlockLocations(srcPath, 0, Long.MAX_VALUE);
      blockIndex = blocks.getLocatedBlocks().size();
    } catch (IOException e) {
      FSNamesystem.LOG.error(
        "DRC: Error happened when calling getFileInfo()/getBlockLocations()");
      return super.chooseTarget(
        srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
    }
    
    int stripeIndex;
    int blockIndexInStripe;
    if (isSource) {
      stripeIndex = blockIndex / (this.drcK * 2);
      blockIndexInStripe = blockIndex % (this.drcK * 2);
    } else {
      stripeIndex = blockIndex / (this.drcR * 2);
      blockIndexInStripe = blockIndex % (this.drcR * 2) + this.drcK * 2;
    }
    DatanodeDescriptor[] ret = new DatanodeDescriptor[numOfReplicas];   
    ret[0] = chooseNodeDrcTwo(stripeIndex, blockIndexInStripe);
    return ret;
  }

  private DatanodeDescriptor chooseNodeDrcTwo(int stripeIndex, int blockIndexInStripe) {
    int rackId = -1;
    int rackNum = this.clusterMap.getNumOfRacks();
    switch (blockIndexInStripe) {
      case 0:
        rackId = (smear(stripeIndex) + blockIndexInStripe) % rackNum;
        break;
      case 1:
      case 2:
        rackId = (smear(stripeIndex) + blockIndexInStripe - 1) % rackNum;
        break;
      case 3:
        blockIndexInStripe -= 3;
        rackId = (smear(stripeIndex) + blockIndexInStripe) % rackNum;
        break;
      case 4:
        blockIndexInStripe -= 3;
        rackId = (smear(stripeIndex) + blockIndexInStripe - 1) % rackNum;
        break;
      case 5:
        rackId = (smear(stripeIndex) + blockIndexInStripe - 3) % rackNum;
        break;
      case 6:
        blockIndexInStripe -= 3;
        rackId = (smear(stripeIndex) + blockIndexInStripe - 2) % rackNum;
        break;
      case 7:
        blockIndexInStripe -= 2;
        rackId = (smear(stripeIndex) + blockIndexInStripe - 3) % rackNum;
        break;
      case 8:
        blockIndexInStripe -= 2;
        rackId = (smear(stripeIndex) + blockIndexInStripe - 4) % rackNum;
        break;
      case 9:
        blockIndexInStripe -= 7;
        rackId = (smear(stripeIndex) + blockIndexInStripe - 1) % rackNum;
        break;
      case 10:
        blockIndexInStripe -= 7;
        rackId = (smear(stripeIndex) + blockIndexInStripe - 2) % rackNum;
        break;
      case 11:
        blockIndexInStripe -= 5;
        rackId = (smear(stripeIndex) + blockIndexInStripe - 4) % rackNum;
        break;
    }
    List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
    int candNodeId = smear(blockIndexInStripe) % candidateNodes.size();
    return (DatanodeDescriptor)candidateNodes.get(candNodeId); 
  }
  
  /**
   * This function implement BlockPlacementPolicyDrc policy
   * for Scaling Operation with RS codes distributed in Round Robin way.
   * Xiaoyang add in Nov, 2017
   */
  private DatanodeDescriptor[] chooseTargetRSRR(
		    String srcPath,
		    int numOfReplicas,
		    DatanodeDescriptor writer,
		    List<DatanodeDescriptor> chosenNodes,
		    List<Node> excludesNodes,
		    long blocksize,
		    boolean isSource) {
		    FSNamesystem.LOG.info("BlockPlacementPolicyDrc.RSRR: Scaling policy is invoked for file: " + srcPath 
		      + ", with replica count: " + numOfReplicas);
		    LocatedBlocks blocks;
		    int blockIndex = -1;
		    try {
		      blocks = this.namesystem.getBlockLocations(srcPath, 0, Long.MAX_VALUE);
		      blockIndex = blocks.getLocatedBlocks().size();
		    } catch (IOException e) {
		      FSNamesystem.LOG.error(
		        "BlockPlacementPolicyDrc: Error happened when calling getFileInfo()/getBlockLocations()");
		      return super.chooseTarget(
		        srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
		    }
//		    int rackNum = this.clusterMap.getNumOfRacks();
		    int stripeIndex;
		    int blockIndexInStripe;
		    if (isSource) {
		      if (blockIndex % this.drcK == 0) srcStripesCount++;
		      stripeIndex = srcStripesCount-1;
		      blockIndexInStripe = blockIndex % this.drcK + this.drcR;
		    } else {
		      try {
		          String[] srcPathSplits = srcPath.split("-");
		          stripeIndex = Integer.valueOf(srcPathSplits[srcPathSplits.length-1]);
		      } catch (NumberFormatException e) {
		          stripeIndex = 0;
		      }
		      blockIndexInStripe = blockIndex % this.drcR;
		    }
	
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.RSRR: srcPath = " + srcPath);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.RSRR: blockIndex = " + blockIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.RSRR: stripeIndex = " + stripeIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.RSRR: blockIndexInStripe = " + blockIndexInStripe);

		    DatanodeDescriptor[] ret = new DatanodeDescriptor[numOfReplicas];   
		  
		    int rackId = -1;
	
		    for(int replicaId = 0; replicaId < numOfReplicas; replicaId++) {
		    	//XXX:如果有直接获得node的方法更好，跳过rack。 
		    	rackId = ((stripeIndex % (drcK+drcR)) + blockIndexInStripe) % (drcK+drcR);
		        List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
		        ret[replicaId] = (DatanodeDescriptor)candidateNodes.get(0); 
		        FSNamesystem.LOG.info("RSRR: replicaId: " + replicaId + " rackId: " + rackId
		                + " DatanodeDescriptor: " + ret[replicaId]); 
		    }
		    return ret;
		  }

  /**
   * This function implement BlockPlacementPolicyDrc policy
   * for Scale-RS.
   * Xiaoyang add in Nov, 2017
   */
  private DatanodeDescriptor[] chooseTargetScaleRS(
		    String srcPath,
		    int numOfReplicas,
		    DatanodeDescriptor writer,
		    List<DatanodeDescriptor> chosenNodes,
		    List<Node> excludesNodes,
		    long blocksize,
		    boolean isSource) {
		    FSNamesystem.LOG.info("BlockPlacementPolicyDrc.ScaleRS: Scaling policy is invoked for file: " + srcPath 
		      + ", with replica count: " + numOfReplicas);
		    LocatedBlocks blocks;
		    int blockIndex = -1;
		    try {
		      blocks = this.namesystem.getBlockLocations(srcPath, 0, Long.MAX_VALUE);
		      blockIndex = blocks.getLocatedBlocks().size();
		    } catch (IOException e) {
		      FSNamesystem.LOG.error(
		        "BlockPlacementPolicyDrc: Error happened when calling getFileInfo()/getBlockLocations()");
		      return super.chooseTarget(
		        srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
		    }
//		    int rackNum = this.clusterMap.getNumOfRacks();
		    int stripeIndex;
		    int blockIndexInStripe;
		    if (isSource) {
		      if (blockIndex % this.drcK == 0) srcStripesCount++;
		      stripeIndex = srcStripesCount-1;
		      blockIndexInStripe = blockIndex % this.drcK + this.drcR;
		    } else {
		      try {
		          String[] srcPathSplits = srcPath.split("-");
		          stripeIndex = Integer.valueOf(srcPathSplits[srcPathSplits.length-1]);
		      } catch (NumberFormatException e) {
		          stripeIndex = 0;
		      }
		      blockIndexInStripe = blockIndex % this.drcR;
		    }
	
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: srcPath = " + srcPath);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: blockIndex = " + blockIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: stripeIndex = " + stripeIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: blockIndexInStripe = " + blockIndexInStripe);

		    DatanodeDescriptor[] ret = new DatanodeDescriptor[numOfReplicas];   
		  
		    int rackId = -1;
		    int scalingType = 0;
		    //XXX:如果有直接获得node的方法更好，跳过rack。 
		    scalingType = (stripeIndex / (drcK+drcS)) % (drcK+drcR);//Xiaoyang modified in 20180814: (drcK+drcR) to (drcK+drcS)
		    rackId = (scalingType + blockIndexInStripe) % (drcK+drcR);
		    List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
		    ret[0] = (DatanodeDescriptor)candidateNodes.get(0); 
		    FSNamesystem.LOG.info("ScaleRS: rackId: " + rackId
		            + " DatanodeDescriptor: " + ret[0]); 
		 
		    return ret;
		  }
  
  /**
   * This function implement BlockPlacementPolicyDrc policy
   * for Scale-RS Down.
   * Xiaoyang add in Nov, 2018
   */
  private DatanodeDescriptor[] chooseTargetScaleRSDown(
		    String srcPath,
		    int numOfReplicas,
		    DatanodeDescriptor writer,
		    List<DatanodeDescriptor> chosenNodes,
		    List<Node> excludesNodes,
		    long blocksize,
		    boolean isSource) {
		    FSNamesystem.LOG.info("BlockPlacementPolicyDrc.ScaleRSDown: Scaling policy is invoked for file: " + srcPath 
		      + ", with replica count: " + numOfReplicas);
		    LocatedBlocks blocks;
		    int blockIndex = -1;
		    try {
		      blocks = this.namesystem.getBlockLocations(srcPath, 0, Long.MAX_VALUE);
		      blockIndex = blocks.getLocatedBlocks().size();
		    } catch (IOException e) {
		      FSNamesystem.LOG.error(
		        "BlockPlacementPolicyDrc: Error happened when calling getFileInfo()/getBlockLocations()");
		      return super.chooseTarget(
		        srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
		    }
//		    int rackNum = this.clusterMap.getNumOfRacks();
		    int stripeIndex;
		    int blockIndexInStripe;
		    if (isSource) {
		      if (blockIndex % this.drcK == 0) srcStripesCount++;
		      stripeIndex = srcStripesCount-1;
		      blockIndexInStripe = blockIndex % this.drcK + this.drcR;
		    } else {
		      try {
		          String[] srcPathSplits = srcPath.split("-");
		          stripeIndex = Integer.valueOf(srcPathSplits[srcPathSplits.length-1]);
		      } catch (NumberFormatException e) {
		          stripeIndex = 0;
		      }
		      blockIndexInStripe = blockIndex % this.drcR;
		    }
	
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: srcPath = " + srcPath);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: blockIndex = " + blockIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: stripeIndex = " + stripeIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.ScaleRS: blockIndexInStripe = " + blockIndexInStripe);

		    DatanodeDescriptor[] ret = new DatanodeDescriptor[numOfReplicas];   
		  
		    int rackId = -1;
		    //XXX:如果有直接获得node的方法更好，跳过rack。  
		    rackId = blockIndexInStripe;
		    List<Node> candidateNodes = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId));
		    ret[0] = (DatanodeDescriptor)candidateNodes.get(0); 
		    FSNamesystem.LOG.info("ScaleRS: rackId: " + rackId
		            + " DatanodeDescriptor: " + ret[0]); 
		    return ret;
		  }
  
  /**
   * This function implement BlockPlacementPolicyDrc policy
   * for Scaling Operation with MBR codes distributed in Round Robin way.
   * Xiaoyang add in Mar, 2018
   */
  private DatanodeDescriptor[] chooseTargetMBRRR(
		    String srcPath,
		    int numOfReplicas,
		    DatanodeDescriptor writer,
		    List<DatanodeDescriptor> chosenNodes,
		    List<Node> excludesNodes,
		    long blocksize,
		    boolean isSource) {
		    FSNamesystem.LOG.info("BlockPlacementPolicyDrc.MBRRR: Scaling policy is invoked for file: " + srcPath 
		      + ", with replica count: " + numOfReplicas);
		    LocatedBlocks blocks;
		    int blockIndex = -1;
		    try {
		      blocks = this.namesystem.getBlockLocations(srcPath, 0, Long.MAX_VALUE);
		      blockIndex = blocks.getLocatedBlocks().size();
		    } catch (IOException e) {
		      FSNamesystem.LOG.error(
		        "BlockPlacementPolicyDrc: Error happened when calling getFileInfo()/getBlockLocations()");
		      return super.chooseTarget(
		        srcPath, numOfReplicas, writer, chosenNodes, excludesNodes, blocksize);
		    }
		    int stripeIndex;
		    int blockIndexInStripe;
		    	   
		    if (isSource) {
		      if (blockIndex % this.drcK == 0) srcStripesCount++;
		      stripeIndex = srcStripesCount-1;
		      blockIndexInStripe = blockIndex % this.drcK;
		    } else {
		      try {
		          String[] srcPathSplits = srcPath.split("-");
		          stripeIndex = Integer.valueOf(srcPathSplits[srcPathSplits.length-1]);
		      } catch (NumberFormatException e) {
		          stripeIndex = 0;
		      }
		      blockIndexInStripe = blockIndex % this.drcR+ this.drcK;
		    }		    
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.MBRRR: srcPath = " + srcPath);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.MBRRR: blockIndex = " + blockIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.MBRRR: stripeIndex = " + stripeIndex);
		    LOG.info("Xiaoyang DEBUG BlockPlacementPolicyDrc.MBRRR: blockIndexInStripe = " + blockIndexInStripe);

		    DatanodeDescriptor[] ret = new DatanodeDescriptor[2];   
		  
		    int rackId1 = -1;
		    int rackId2 = -1;
		    int sum = 0;
		    for(int i = 0; i < this.nodeNum-1; i++)
		    {
		    	sum += this.nodeNum-1-i;
		    	if(blockIndexInStripe < sum)
		    	{
		    		rackId1 = i;
		    		rackId2 = this.nodeNum - (sum-blockIndexInStripe);
		    		break;
		    	}
		   	}
		   //	if(rackId1 == -1 || rackId2 == -1)
		    List<Node> candidateNodes1 = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId1));
		    List<Node> candidateNodes2 = this.clusterMap.getDatanodesInRack(this.clusterMap.getRack(rackId2));
		    ret[0] = (DatanodeDescriptor)candidateNodes1.get(0); 
		    ret[1] = (DatanodeDescriptor)candidateNodes2.get(0); 
		    FSNamesystem.LOG.info("MBR: rackId1: " + rackId1 + " rackId2: " + rackId2
		            + " DatanodeDescriptor1: " + ret[0] + " DatanodeDescriptor2: " + ret[1]); 
		    return ret;
}
  
  
  /*
   * This method was written by Doug Lea with assistance from members of JCP
   * JSR-166 Expert Group and released to the public domain, as explained at
   * http://creativecommons.org/licenses/publicdomain
   * 
   * As of 2010/06/11, this method is identical to the (package private) hash
   * method in OpenJDK 7's java.util.HashMap class.
   */
  static int smear(int hashCode) {
    hashCode ^= (hashCode >>> 20) ^ (hashCode >>> 12);
    return hashCode ^ (hashCode >>> 7) ^ (hashCode >>> 4);
  }


  /** {@inheritDoc} */
  @Override
  public DatanodeDescriptor chooseReplicaToDelete(FSInodeInfo inode,
    Block block, short replicationFactor,
    Collection<DatanodeDescriptor> first,
    Collection<DatanodeDescriptor> second) {
    StackTraceElement[] elements = Thread.currentThread().getStackTrace();
    for(int i=0; i<elements.length; i++) {
        LOG.info("element: " + elements[i]);
    }
    // ZM DEBUG START
    // LOG.info("ZM Enter chooseReplicaToDelete() in BlockPlacementPolicyDrc.java");
    // LOG.info("ZM FSInodeInfo: " + inode);
    // LOG.info("ZM Block: " + block);
    // LOG.info("ZM replicationFactor: " + replicationFactor);
    // LOG.info("ZM first collection length: " + first.size());
    Iterator itr = first.iterator();
    while(itr.hasNext()){
        LOG.info("fist item: " + itr.next());
    }
    //LOG.info("ZM second collection length: " + second.size());
    itr = second.iterator();
    while(itr.hasNext()){
        LOG.info("second item: " + itr.next());
    }
    return super.chooseReplicaToDelete(inode, block, replicationFactor,
            first, second);
  }

  enum FileType {
    NOT_DRC,
    DRC_DATA,
    DRC_TMP_PARITY,
    DRC_PARITY
  }

  /**
   * Check FileType to decide whether to leverage
   * BlockPlacementPolicyDrc Policy
   */
  FileType getFileType(String srcPath) throws IOException {
    if (srcPath.contains("tmp")) {
      return FileType.DRC_TMP_PARITY;
    }
    if (srcPath.contains("drc") || srcPath.contains("js") || srcPath.contains("rs")) {
      return FileType.DRC_PARITY;
    }
    if (srcPath.contains("raidTest")) {
      return FileType.DRC_DATA;
    }
    return FileType.NOT_DRC;
  }


}
