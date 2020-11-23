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

package org.apache.hadoop.util;

import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


public class NativeDRC {
  public static final Log LOG = LogFactory.getLog(NativeDRC.class);
  private static boolean nativeLoaded = false;
  static {
    try {
      System.loadLibrary("drc");
      nativeLoaded = true;
    } catch(Throwable t) {
      LOG.info("fail to load drc with error: " + t);
    }
  }
      
    

  public static boolean isAvailable() {
    return nativeLoaded;
  }


  /**
   * replication-erasurecoding
   */
  public static native boolean nativeInit(int stripeSize, int paritySize,
      int rackNum, int rackSize, int type);

  /**
   * replication-erasurecoding
   */
  public static void encodeBulk(byte[][] inputs, byte[][] outputs,
      int stripeSize, int paritySize) {
    ByteBuffer[] inputBuffers = new ByteBuffer[inputs.length];
    ByteBuffer[] outputBuffers = new ByteBuffer[outputs.length];
    int bufferLen = inputs[0].length;
    for (int i = 0; i < outputs.length; i++) {
      outputBuffers[i] = ByteBuffer.allocateDirect(bufferLen);
    }
    for (int i = 0; i < inputs.length; i++) {
      inputBuffers[i] = directify(inputs[i], 0, bufferLen);
    }
    nativeEncodeBulk(inputBuffers, outputBuffers, stripeSize, paritySize, bufferLen);
    for (int i = 0; i < outputs.length; i++) {
      outputBuffers[i].get(outputs[i]);
    }
  }

  /**
   * replication-erasurecoding
   */
  private static native void nativeEncodeBulk(ByteBuffer[] inputBuffers,
      ByteBuffer[] outputBuffers, int stripeSize, int paritySize, int dataLen);

  /**
   * recover
   */
  public static native boolean nativeVerify(int[] corruptArray, int[] dataArray,
      int[] groupArray, int dataLen, int[] nodeLenArray, int[] groupLenArray);

  public static boolean encodeDRCBlock(int nodeid, int[] corruptArray, 
      byte[] data, int datalen, byte[] code, int codelen) {
    ByteBuffer inputBuffer = directify(data, 0, datalen);
    ByteBuffer outputBuffer = ByteBuffer.allocateDirect(codelen);
	if(!nativeEncodeDRCBlock(nodeid, corruptArray, inputBuffer, datalen, outputBuffer, codelen)) {
	  return false;
	}

    outputBuffer.get(code);
	return true;
  }

  private static native boolean nativeEncodeDRCBlock(int nodeid, int[] corruptarray,
      ByteBuffer data, int datalen, ByteBuffer code, int codelen);

  public static boolean encodeDRCGroup(int groupId, int[] corruptArray, byte[][] groupInput, int inputSize,
      int[] inputLen, byte[][] groupOutput, int outputSize, int outputLen){
    ByteBuffer[] inputBuffers = new ByteBuffer[inputSize];
    ByteBuffer[] outputBuffers = new ByteBuffer[outputSize];
//    int bufferLen = outputLen;

    for(int i=0 ;i<inputSize; i++) {
      inputBuffers[i] = directify(groupInput[i], 0, inputLen[i]);
    }
    for(int i=0; i<outputSize; i++) {
      outputBuffers[i] = ByteBuffer.allocateDirect(outputLen);
    }

    if(!nativeEncodeDRCGroup(groupId, corruptArray, inputBuffers, inputSize, inputLen,
	    outputBuffers, outputSize, outputLen)) {
      LOG.info("nativeEncodeDRCGroup returns false");
      return false;
    }

    for(int i=0; i<outputSize; i++) {
      outputBuffers[i].get(groupOutput[i]);
    }
    return true;
  }

  public static ByteBuffer directify(byte[] readBufs, int dataStart, int dataLen) {
    ByteBuffer newBuf = null;
    newBuf = ByteBuffer.allocateDirect(dataLen);
    newBuf.position(0);
    newBuf.mark();
    newBuf.put(readBufs, dataStart, dataLen);
    newBuf.reset();
    newBuf.limit(dataLen);
    return newBuf;
  }

  private static native boolean nativeEncodeDRCGroup(int groupId, int[] corruptArray, 
      ByteBuffer[] input, int inputSize, int[] inputLen,
      ByteBuffer[] output, int outputSize, int outputLen);

  public  static boolean decode(int [] corruptArray, byte[][] targetInput, int[] decodeArray, 
      int[] inputLen, byte[][] targetOutput, int outputSize, int outputLen) {
    int inputSize = 0;
    for(int i=0; i<decodeArray.length; i++) {
      inputSize += decodeArray[i];
    }
    ByteBuffer[] inputBuffers = new ByteBuffer[inputSize];
    ByteBuffer[] outputBuffers = new ByteBuffer[outputSize];
    for(int i=0; i<inputSize; i++) {
      inputBuffers[i] = directify(targetInput[i], 0, inputLen[i]);
    }
    for(int i=0; i<outputSize; i++) {
      outputBuffers[i] = ByteBuffer.allocateDirect(outputLen);
    }

    if(!nativeDecode(corruptArray, inputBuffers, decodeArray, inputLen,
	    outputBuffers, outputSize, outputLen)) {
      LOG.info("nativeDecode returns false");
      return false;
    }
    for(int i=0; i<outputSize; i++) {
      outputBuffers[i].get(targetOutput[i]);
    }
    return true;
  }

  private static native boolean nativeDecode(int[] corruptArray, ByteBuffer[] input, int[] decodeArray,
      int[] inputLen, ByteBuffer[] output, int outputSize, int recoverLength);
}
