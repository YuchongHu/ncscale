/* DO NOT EDIT THIS FILE - it is machine generated */
#include <jni.h>
/* Header for class org_apache_hadoop_io_compress_lz4_Lz4Compressor */

#ifndef _Included_org_apache_hadoop_io_compress_lz4_Lz4Compressor
#define _Included_org_apache_hadoop_io_compress_lz4_Lz4Compressor
#ifdef __cplusplus
extern "C" {
#endif
#undef org_apache_hadoop_io_compress_lz4_Lz4Compressor_DEFAULT_DIRECT_BUFFER_SIZE
#define org_apache_hadoop_io_compress_lz4_Lz4Compressor_DEFAULT_DIRECT_BUFFER_SIZE 65536L
/*
 * Class:     org_apache_hadoop_io_compress_lz4_Lz4Compressor
 * Method:    initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_org_apache_hadoop_io_compress_lz4_Lz4Compressor_initIDs
  (JNIEnv *, jclass);

/*
 * Class:     org_apache_hadoop_io_compress_lz4_Lz4Compressor
 * Method:    compressBytesDirect
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_apache_hadoop_io_compress_lz4_Lz4Compressor_compressBytesDirect
  (JNIEnv *, jobject);

/*
 * Class:     org_apache_hadoop_io_compress_lz4_Lz4Compressor
 * Method:    compressBytesDirectHC
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_org_apache_hadoop_io_compress_lz4_Lz4Compressor_compressBytesDirectHC
  (JNIEnv *, jobject);

#ifdef __cplusplus
}
#endif
#endif