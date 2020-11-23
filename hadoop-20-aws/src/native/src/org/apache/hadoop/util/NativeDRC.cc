/**
 * @file NativeDRC.c
 * @brief Native C implementation of DRC codes
 * @author 
 * @version 
 * @date 
 * @note revised on 
 */

#include <cstring>
#include "drc.hh"
#include "drc633.hh"
#include "drc643.hh"
#include "drc864.hh"
#include "drc953.hh"
#include "drc963.hh"
#include "ia.hh"
#include "butterfly64.hh"
#include "butterfly86.hh"
#include "rsbase.hh"
#include "car.hh"

#include "NativeDRC.h"
#include <jni.h>

int k_drc;
int m_drc;
int r_drc;
int l_drc;
int codetype;

DRC *drc;

/**
 * description:
 *   (1)  this function is called before encoding(replication->erasurecode), and will prepare the coding matrix.
 *      for the encoding, only stripeSize and paritySize are used in jerasure.
 *      please refer to 
 *          org_apache_hadoop_util_NativeDRC.h 
 *      and NativeDRC.h for more description.
 *      pay attention to the NativeDRC.h to add some related headers(because now I use jerasure
 *      to do the encoding). You can add all that are needed for isa-l.
 *   (2)  this function is called during recover, the initialize of native-code do recover can implement here.
 */

int *codingmatrix = NULL;
JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeInit
  (JNIEnv *env, jclass clazz, jint stripeSize, jint paritySize, jint rackNum, jint rackSize, jint type) {
  k_drc = stripeSize;
  m_drc = paritySize;
  r_drc = rackNum;
  l_drc = rackSize;
  codetype = type;

  // here xiaolu try to init drccode
  if(codetype == 1) {
    drc = new DRC633();
  } else if (codetype == 2) {
    drc = new DRC643();
  } else if(codetype == 3) {
    drc = new DRC864();
  } else if(codetype == 4) {
    drc = new DRC953();
  } else if(codetype == 5) {
    drc = new DRC963();
  } else if(codetype == 6) {
    drc = new IA();
  } else if(codetype == 7) {
    drc = new BUTTERFLY64();
  } else if(codetype == 8) {
    drc = new BUTTERFLY86();
  } else if(codetype == 9) {
    drc = new RSBASE();
  } else if(codetype == 10) {
    drc = new CAR();
  } else {
    drc = new RSBASE();
  }

  drc->initialize(k_drc, m_drc, r_drc, l_drc);
  return JNI_TRUE;

}

/**
 * description:
 *  (1)  this function is called during replication->erasurecode
 *    inputBuffers are in prepare and hold the data to erasurecode
 *    outputBuffers should be calculate and fill in use erasurecode
 *    please refer to 
 *        org_apache_hadoop_util_NativeDRC.h 
 *    and NativeDRC.h for more description.
 *    TODO:
 *      if to use isa-l accelerate the encoding, this function should be implemented.
 *      now I use the jerasure to do replication->erasurecode.
 *      add something related to isa-l to do erasurecoding
 */
JNIEXPORT void JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeEncodeBulk
    (JNIEnv *env, jclass clazz, jobjectArray inputBuffers, jobjectArray outputBuffers,
    jint stripeSize, jint paritySize, jint dataLength) {

//  printf("dataLength = %d\n", dataLength);
//  char* data[stripeSize];
  uint8_t *data[stripeSize];
//  char* coding[paritySize];
  uint8_t *coding[paritySize];
//  int i;
  int32_t i;

  for (i = 0; i < stripeSize; i++) {
    jobject j_inputBuffer = env->GetObjectArrayElement(inputBuffers, i);
//    data[i] = (char*)env->GetDirectBufferAddress(j_inputBuffer);
    data[i] = (uint8_t *)env->GetDirectBufferAddress(j_inputBuffer);
  }

  for (i = 0; i < paritySize; i++) {
    jobject j_outputBuffer = env->GetObjectArrayElement(outputBuffers, i);
//    coding[i] = (char*)env->GetDirectBufferAddress(j_outputBuffer);
    coding[i] = (uint8_t *)env->GetDirectBufferAddress(j_outputBuffer);
//    memset(coding[i], 0, dataLength);
  }

//  jerasure_matrix_encode(stripeSize, paritySize, WORD_SIZE, codingmatrix, data, coding, dataLength);
//  printf("before drc construct!!!");
  drc->construct(data, coding, dataLength);
}


JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeVerify
  (JNIEnv *env, jclass clazz, jintArray corruptArray, jintArray dataArray,
    jintArray groupArray, jint dataLen, jintArray nodeLenArray, jintArray groupLenArray) {

  int32_t *corrupt;
  int32_t *data;
  int32_t *group;
  int32_t *nodelen;
  int32_t *grouplen;
  
  jboolean e;
  corrupt = env->GetIntArrayElements(corruptArray, &e);

  int32_t data_len = env->GetArrayLength(dataArray);
  int32_t group_len = env->GetArrayLength(groupArray);
  int32_t nodelen_len = env->GetArrayLength(nodeLenArray);
  int32_t grouplen_len = env->GetArrayLength(groupLenArray);

  data = (int32_t *)malloc(data_len * sizeof(int32_t));
  memset(data, -1, data_len*sizeof(int32_t));
  group = (int32_t *)malloc(group_len * sizeof(int32_t));
  memset(group, 0, group_len*sizeof(int32_t));
  nodelen = (int32_t *)malloc(nodelen_len * sizeof(int32_t));
  memset(nodelen, 0, nodelen_len*sizeof(int32_t));
  grouplen = (int32_t *)malloc(grouplen_len * sizeof(int32_t));
  memset(grouplen, 0, grouplen_len*sizeof(int32_t));

//  jboolean res = drc->verify(corrupt, data, group, dataLen, len);
  jboolean res = drc->check(corrupt, data, group, dataLen, nodelen, grouplen);
  
  env->SetIntArrayRegion(dataArray, 0, data_len, data);

  env->SetIntArrayRegion(groupArray, 0, group_len, group);

  env->SetIntArrayRegion(nodeLenArray, 0, nodelen_len, nodelen);

  env->SetIntArrayRegion(groupLenArray, 0, grouplen_len, grouplen);
  return res;
}

JNIEXPORT void JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeTest
  (JNIEnv *env, jclass clazz) {

}

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeEncodeDRCBlock
  (JNIEnv *env, jclass clazz, jint nodeid, jintArray corruptArray,
   jobject input, jint dataLen, jobject output, jint codeLen){

  int32_t *corrupt;
  jboolean e;
  corrupt = env->GetIntArrayElements(corruptArray, &e);

  uint8_t *data = (uint8_t *)(env->GetDirectBufferAddress(input));
  uint8_t *code = (uint8_t *)(env->GetDirectBufferAddress(output));

  int32_t i;
  int32_t len = dataLen;

  // TODO: should use native code if there is
  jboolean res = drc->nodeEncode(nodeid, corrupt, data, dataLen, code, codeLen); 
//  for(i=0; i<len; i++) {
//    code[i] = data[i];
//  }
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeEncodeDRCGroup
  (JNIEnv *env, jclass clazz, jint groupId, jintArray corruptArray, 
   jobjectArray groupInput, jint inputSize, jintArray inputLen,
    jobjectArray groupOutput, jint outputSize, jint outputLen) {

  int32_t *corrupt;
  int32_t *inputlen;
  uint8_t *inputs[inputSize];
  uint8_t *outputs[outputSize];

  int32_t i;
  int32_t input_length = inputSize;
  int32_t output_length = outputSize;
  int32_t groupid = groupId;
  int32_t data_length = outputLen;

  for(i=0; i<inputSize; i++ ) {
    jobject j_inputBuffer = env->GetObjectArrayElement(groupInput, i);
    inputs[i] = (uint8_t *)env->GetDirectBufferAddress(j_inputBuffer);
  }

  for(i=0; i<outputSize; i++) {
    jobject j_outputBuffer = env->GetObjectArrayElement(groupOutput, i);
    outputs[i] = (uint8_t *)env->GetDirectBufferAddress(j_outputBuffer);
  }

  jboolean e;
  corrupt = env->GetIntArrayElements(corruptArray, &e);

  inputlen = env->GetIntArrayElements(inputLen, &e);

  // xiaolu start April 29 to copy some input to output
/*  for(i=0; i<outputSize; i++) {
    int32_t j;
    for(j=0; j<groupLength; j++) {
      outputs[i][j] = inputs[i][j];
    }
  }
*/  
  jboolean res = drc->groupEncode(groupid, corrupt, inputs, input_length, inputlen, 
      outputs, output_length, outputLen);
  return JNI_TRUE;

}

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeDRC_nativeDecode
  (JNIEnv *env, jclass clazz, jintArray corruptArray, jobjectArray targetInput, 
  jintArray decodeArray, jintArray inputLen, jobjectArray targetOutput, jint outputSize, jint outputLen) {

  int32_t *corrupt;
  jboolean e;
  corrupt = env->GetIntArrayElements(corruptArray, &e);

  int32_t *decode;
  decode = env->GetIntArrayElements(decodeArray, &e);

  int32_t *inputlen;
  inputlen = env->GetIntArrayElements(inputLen, &e);

  int32_t i;
  int32_t inputSize = 0;
  for(i=0; i<r_drc; i++) {
    inputSize += decode[i];
  }

  uint8_t *inputs[inputSize];
  uint8_t *outputs[outputSize];

  for(i=0; i<inputSize; i++) {
    jobject j_inputBuffer = env->GetObjectArrayElement(targetInput, i);
    inputs[i] = (uint8_t *)env->GetDirectBufferAddress(j_inputBuffer);
  }

  for(int i=0; i<outputSize; i++) {
    jobject j_outputBuffer = env->GetObjectArrayElement(targetOutput, i);
    outputs[i] = (uint8_t *)env->GetDirectBufferAddress(j_outputBuffer);
  }

  jboolean res = drc->decode(corrupt, inputs, decode, inputlen, outputs, outputSize, outputLen);
  return JNI_TRUE;
}
