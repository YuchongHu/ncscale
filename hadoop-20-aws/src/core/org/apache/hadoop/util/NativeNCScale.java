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


public class NativeNCScale {
  private static boolean nativeLoaded = false;
  static {
      System.loadLibrary("ncscale");
      nativeLoaded = true;
  }

  public static boolean isAvailable() {
    return nativeLoaded;
  }

  public static native boolean nativeInit(int drcN, int drcK, int drcS, int blockSize);

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
  
  public static void compute(byte[][] inputs, byte[][] outputs) {
    ByteBuffer[] inputBuffers = new ByteBuffer[inputs.length];
    ByteBuffer[] outputBuffers = new ByteBuffer[outputs.length];
    int bufferLen = inputs[0].length;
    for (int i = 0; i < outputs.length; i++) {
    	outputBuffers[i] = ByteBuffer.allocateDirect(bufferLen);
    }
    for (int i = 0; i < inputs.length; i++) {
    	inputBuffers[i] = directify(inputs[i], 0, bufferLen);
    }
    nativeCompute(inputBuffers, inputs.length, outputBuffers, outputs.length);
//    System.out.println("NativeNCScale.compute.outputBuffers is null? "+ outputBuffers.equals(null));
   
    for (int i = 0; i < outputs.length; i++) {
    	outputBuffers[i].get(outputs[i]);
    }
  }

  private static native void nativeCompute(ByteBuffer[] inputBuffers, int inputSize,
      ByteBuffer[] outputBuffers, int outputSize);


  public static void parityUpdate(byte[] parity, byte[] delta, byte[] out) {
	    ByteBuffer parityBuffer = directify(parity, 0, parity.length);
	    ByteBuffer deltaBuffer = directify(delta, 0, delta.length);
	    ByteBuffer outputBuffer = ByteBuffer.allocateDirect(parity.length);
		nativeParityUpdate(parityBuffer, deltaBuffer, outputBuffer);
	    outputBuffer.get(out);
	  }

  private static native void nativeParityUpdate(ByteBuffer parity, ByteBuffer delta, ByteBuffer out);

	 
}
