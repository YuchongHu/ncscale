/*
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
#ifndef NativeDRC_H_INCLUDED
#define NativeDRC_H_INCLUDED

#include "galois.h"
#include "jerasure.h"
#include "reed_sol.h"

#include <jni.h>

#define WORD_SIZE 8

#ifdef __cplusplus
extern "C" {
#endif
/**
 * @brief Java_org_apache_hadoop_util_NativeDRC_nativeInit - initialize all related variables
 *        used in replication-erasurecoding
 * @param env - JNI interface pointer
 * @param clazz - Java class object
 * @param stripeSize - number of data symbols in a stripe
 * @param paritySize - number of parity symbols in a stripe
 * @param rackNum - number of racks related in a stripe
 * @param rackSize - number of symbols in a rack of a stripe
 * @return - a boolean value to indicate if the operation is successful
 *
 * elaborate: 
 * 6_4_3:
 *      stripeSize = 4
 *      paritySize = 2
 *      rackNum = 3
 *      rackSize = 2
 */

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeInit
  (JNIEnv *env, jclass clazz, jint stripeSize, jint paritySize, jint rackNum, jint rackSize, jint type);

  
/**
 * @brief Java_org_apache_hadoop_util_NativeDRC_nativeEncodeBulk - encode data into parity in bulk
 *        used in replication-erasurecoding
 * @param env - JNI interface pointer
 * @param clazz - Java class object
 * @param inputBuffers - input buffers that store data
 * @param outputBuffers - output buffers that store parity
 * @param stripeSize - inputBuffers size
 * @param paritySize - outputBuffers size
 * @param dataLengh - the length of each buffer
 *
 * elaborate:
 * 6_4_3:
 *      stripeSize: 4
 *      paritySize: 2
 *      dataLength: generally 1048576(sometimes may larger than it)
 *      inputBuffers:  two-dimensional array with 4*dataLength
 *      outputBuffers: two-dimensional array with 2*dataLength
 */

JNIEXPORT void JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeEncodeBulk
  (JNIEnv *env, jclass clazz, jobjectArray inputBuffers, jobjectArray outputBuffers, 
  jint stripeSize, jint paritySize, jint dataLength);


/**
 * @brief in this function, corruptArray will tell the corrupt situation of a stripe, and this function will
 *        1. verify whether drc can recover for this situation
 *        2. what data will be needed in the following calculation
 *        3. for each group, if need relayer, how much blocks of data will be created
 * @param corruptArray - tells in a stripe what block is corrupted
 * @param dataArray - what data will be needed in recovery
 * @param groupArray - how much blocks of data will be created
 * @return a bool value to denote whether can recover for this situation
 */

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeVerify
  (JNIEnv *env, jclass clazz, jintArray corruptArray, jintArray dataArray, 
  jintArray groupArray, jint dataLen, jintArray nodeLenArray, jintArray groupLenArray);

/**
 * @brief in this function, corruptArray will tell the corrupt situation of a stripe,
 *      and this function will read from input and calculate the output
 * @param corruptArray - tells in a stripe what block is corrupted
 * @param groupInput - inputs
 * @param inputSize - how many blocks are in groupInput
 * @param groupOutput - outputs
 * @param outputSize - how many blocks are in groupOutput
 * @param groupId - the logic groupId
 * @param groupLength - the length of each block
 */

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeEncodeDRCGroup
  (JNIEnv *env, jclass clazz, jint groupId, jintArray corruptArray, 
   jobjectArray groupInput, jint inputSize, jintArray inputLen,
  jobjectArray groupOutput, jint outputSize, jint outputLen);

/**
 * @brief in this function, input data will be encoded
 * @param input - input data
 * @param output - output data
 * @param dataLen - input length
 * @param codeLen - encoded data length
 */

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeEncodeDRCBlock
  (JNIEnv *env, jclass clazz, jint nodeid, jintArray corruptArray,
   jobject input, jint dataLen, jobject output, jint codeLen);

/**
 * @brief in this function, do the final recover
 * @param corruptArray - tells in a stripe what block is corrupted
 * @param targetInput - input data to do the recover
 * @param decodeArray - tells the what is the input data
 * @param targetOutput - the recovered data
 * @param outputSize - the number of the recovered data
 * @param outputLength - the datalength
 */
JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeDecode
  (JNIEnv *env, jclass clazz, jintArray corruptArray, jobjectArray targetInput, 
  jintArray decodeArray, jintArray inputLen, jobjectArray targetOutput, jint outputSize, jint outputLen);






#ifdef __cplusplus
}
#endif

#endif
