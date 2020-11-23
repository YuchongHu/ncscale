/**
 * @file NativeDRC.c
 * @brief Native C implementation of DRC codes
 * @author 
 * @version 
 * @date 
 * @note revised on 
 */

#include <cstring>
#include "ncscale.hh"

#include "NativeNCScale.h"
#include <jni.h>

int drcN;
int drcK;
int drcS;
int blockSize;

NCSCALE *ncscale;

JNIEXPORT jboolean JNICALL Java_org_apache_hadoop_util_NativeNCScale_nativeInit
  (JNIEnv *env, jclass clazz, jint N, jint K, jint S, jint blocksize) {
	drcN = N;
	drcK = K;
	drcS = S;
	blockSize = blocksize;
	ncscale = new NCSCALE();
	ncscale->initialize(drcN, drcK, drcS, blockSize);
	return JNI_TRUE;
}


JNIEXPORT void JNICALL Java_org_apache_hadoop_util_NativeNCScale_nativeParityUpdate
  (JNIEnv *env, jclass clazz, jobject parityblock, jobject deltablock, jobject outputblock){

  uint8_t *parity = (uint8_t *)(env->GetDirectBufferAddress(parityblock));
  uint8_t *delta = (uint8_t *)(env->GetDirectBufferAddress(deltablock));
  uint8_t *output = (uint8_t *)(env->GetDirectBufferAddress(outputblock));

  ncscale->parityUpdate(parity, delta, output);

}

JNIEXPORT void JNICALL Java_org_apache_hadoop_util_NativeNCScale_nativeCompute
  (JNIEnv *env, jclass clazz, jobjectArray input, jint inputSize,
		  jobjectArray output, jint outputSize) {

  uint8_t *inputs[inputSize];
  uint8_t *outputs[outputSize];

  int32_t i;
  int32_t input_length = inputSize;
  int32_t output_length = outputSize;

  for(i=0; i<inputSize; i++ ) {
    jobject j_inputBuffer = env->GetObjectArrayElement(input, i);
    inputs[i] = (uint8_t *)env->GetDirectBufferAddress(j_inputBuffer);
  }

  for(i=0; i<outputSize; i++) {
    jobject j_outputBuffer = env->GetObjectArrayElement(output, i);
    outputs[i] = (uint8_t *)env->GetDirectBufferAddress(j_outputBuffer);
  }

  ncscale->compute(inputs, input_length,
      outputs, output_length);

}

