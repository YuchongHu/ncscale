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

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import java.io.IOException;
import java.lang.IllegalArgumentException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import org.apache.hadoop.util.NativeDRC;

public class DRCCode extends ErasureCode {

  public static final Log LOG = LogFactory.getLog(DRCCode.class);
  
  private int stripeSize;    // total number of data symbols(parity not included in this) in a stripe
  private int paritySize;    // total number of parity symbols in a stripe
  private int groupNum;       // the number of groups in this coding scheme
  private int groupSize;      // the number of coding symbols in each group
  private int codeType;
                             /**
                              * clarify:
                              * for 6_4_3 drc code
                              * data symbol = 4, which means stripeSize = 4, d0,d1,d2,d3
                              * parity symbol = 2, which means paritySize = 2, p0,p1
                              * groupNum = 3, r0,r1,r2
                              * groupSize = 2, which means 2 symbols put in a group in each stripe
                              * for d0,d1,d2,d3,p0,p1 and r0,r1,r2
                              * r0: d0,d1
                              * r1: d2,d3
                              * r2: p0,p1
                              */


  DRCCode(int stripeSize, int paritySize, String jsonStr) {
    init(stripeSize, paritySize, jsonStr);
  }

  DRCCode() {
  }

  @Override
  public void init(int stripeSize, int paritySize, String jsonStr) {
    try {
      JSONObject json = new JSONObject(jsonStr);
      this.stripeSize = stripeSize;
      this.paritySize = paritySize;
      this.groupNum = json.getInt("group_num");
      this.groupSize = json.getInt("group_size");
      this.codeType = json.getInt("drc_type");
    } catch (JSONException ex) {
      ex.printStackTrace();
    } catch (IllegalArgumentException ex) {
      LOG.info("Just caught an exception..." + ex.getMessage());
    }

    try {
      System.loadLibrary("drc");
      LOG.info("load drc library");
    } catch(Throwable t) {
      LOG.info("fail to load drc with error: " + t);
      LOG.info("java.library.path = " + System.getProperty("java.library.path"));
    }

    if(NativeDRC.isAvailable()) {
      if(!NativeDRC.nativeInit(this.stripeSize, this.paritySize, this.groupNum, this.groupSize, this.codeType)) {
        LOG.info("fail to init native double regenerating code");
      }
    } else {
      LOG.info("can not use native C implemention of drc code");
    }
      
  }


  /**
   * this function may never be used
   */
  @Override
  public void init(int stripeSize, int paritySize) {

  }

  @Override
  public int stripeSize() {
    return stripeSize;
  }

  @Override
  public int paritySize() {
    return paritySize;
  }

  public int groupNum() {
    return groupNum;
  }

  public int groupSize() {
    return groupSize;
  }


  /**
   * TODO: what is the symbol here?
   */
  @Override
  public int symbolSize() {
    return 0;
  }

  @Override
  public void encode(int[] message, int[] parity) {
  }

  @Override
  public void encodeBulk(byte[][] inputs, byte[][] outputs) throws IOException {
    
    encodeBulk(inputs, outputs, true);

  }

  @Override
  public void encodeBulk(byte[][] inputs, byte[][] outputs, boolean useNative)
      throws IOException {

    final int stripeSize = stripeSize();
    final int paritySize = paritySize();
    assert (stripeSize == inputs.length);
    assert (paritySize == outputs.length);
    long encode_time = 0;
    if (NativeDRC.isAvailable() && useNative) {
        encode_time = 0;
        encode_time -= System.nanoTime(); 
        NativeDRC.encodeBulk(inputs, outputs, stripeSize, paritySize);
        encode_time += System.nanoTime(); 
    } else {
      throw new IOException("Double Regenerating Code can't using Native C Implementation");
    }

  }

  @Override
  public List<Integer> locationsToReadForDecode(List<Integer> erasedLocations)
      throws TooManyErasedLocations {

      return null;
  }

  @Override
  public void decode(int[] data, int[] erasedLocations, int[] erasedValues) {
  }

  @Override
  public void decodeBulk(byte[][] readBufs, byte[][] writeBufs,
      int[] erasedLocations)  throws IOException {
  }

  @Override
  public void decodeBulk(byte[][] readBufs, byte[][] writeBufs,
      int[] erasedLocations, int dataStart, int dataLen) throws IOException {

  }

  @Override
  public void decodeOneBlock(byte[][] readBufs, byte[] decodeVec, int dataLen,
      int[] erasedLocations, int decodeLocation, int decodePos, int decodeLen,
      boolean useNative) throws IOException {
  }

  public boolean verify(int[] corruptArray, int[] dataArray, int[] groupArray,
      int dataLen, int[] nodeLenArray, int[] groupLenArray) 
      throws IOException {
    if(NativeDRC.isAvailable()) {
      return NativeDRC.nativeVerify(corruptArray, dataArray, groupArray, dataLen,
	      nodeLenArray, groupLenArray);
    } else {
      throw new IOException("Double Regenerating Code can't using Native C Implementation");
    }
  }

  public Map<Integer, List<Integer>> placement() throws IOException {
    Map<Integer, List<Integer>> map = new HashMap<Integer, List<Integer>>();

    if(this.codeType == 1000) {
      int n = (this.stripeSize + this.paritySize) / 12;
	  for(int k=0; k<n; k++) {
	    List<Integer> list0 = map.get(0);
	    while(list0 == null) {
	      list0 = new LinkedList<Integer>();
		  map.put(0, list0);
	  	  list0 = map.get(0);
	    }
	    list0.add(12*k+0);
	    list0.add(12*k+1);
	    list0.add(12*k+3);
	    list0.add(12*k+4);

	    List<Integer> list1 = map.get(1);
	    while(list1 == null) {
          list1 = new LinkedList<Integer>();
	 	  map.put(1, list1);
		  list1 = map.get(1);
	    }
		list1.add(12*k+2);
		list1.add(12*k+6);
		list1.add(12*k+9);
		list1.add(12*k+10);

		List<Integer> list2 = map.get(2);
        while(list2 == null) {
		  list2 = new LinkedList<Integer>();
		  map.put(2, list2);
          list2 = map.get(2);
		}
		list1.add(12*k+5);
		list1.add(12*k+7);
		list1.add(12*k+8);
		list1.add(12*k+11);
	  }
	} else {
	  int blocknum = 0;
	  for(int groupnum = 0; groupnum < this.groupNum; groupnum++) {
	    List<Integer> list = new LinkedList<Integer>();
		for(int p=0; p<this.groupSize; p++) {
		  list.add(blocknum + p);
		}
		blocknum += this.groupSize;
		map.put(groupnum, list);
	  }
	}
   return map;
  }

  public boolean encodeBlock(int nodeid, int[] corruptArray, byte[] input, int dataLen,
      byte[] output, int codeLen) throws IOException {
    if(NativeDRC.isAvailable()) {
	  return NativeDRC.encodeDRCBlock(nodeid, corruptArray, input, dataLen, output, 
	      codeLen);
    } else {
	  throw new IOException("Double Regenerating Code can't using Native C Implementation");
	}
  }

  public boolean encodeGroup(int groupId, int[] corruptArray, byte[][] groupInput, int inputSize, 
      int[] inputLen, byte[][] groupOutput, int outputSize, int outputLen) throws IOException {
    if(NativeDRC.isAvailable()) {
      return NativeDRC.encodeDRCGroup(groupId, corruptArray, groupInput, inputSize, inputLen,
	      groupOutput, outputSize, outputLen);
    } else {
      throw new IOException("Double Regenerating Code can't using Native C Implementation");
    }
  }

  public boolean recover(int[] corruptArray, 
      byte[][] targetInput, int[] decodeArray, int[] inputLen,
      byte[][] targetOutput, int outputSize, int outputLen) throws IOException {
    if(NativeDRC.isAvailable()) {
      return NativeDRC.decode(corruptArray, targetInput, decodeArray, inputLen, targetOutput,
          outputSize, outputLen);
    } else {
      throw new IOException ("Double Regenerating Code can't using Native C Implementation");
    }
  }
}

