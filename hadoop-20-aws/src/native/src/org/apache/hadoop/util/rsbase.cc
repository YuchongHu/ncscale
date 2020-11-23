#include "rsbase.hh"
#include <iostream>
#include <isa-l.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

using namespace std;

RSBASE::RSBASE() {}

bool RSBASE::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
  _k = k;
  _m = m;
  _r = r;
  _nr = nr;
  _n = k + m;

  gf_gen_rs_matrix(this->_encode_matrix, _n,
                   _k); // vandermonde matrix, aplpha=2
  ec_init_tables(_k, _m, &this->_encode_matrix[_k * _k], this->_gftbl);

  return true;
}

bool RSBASE::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
  ec_encode_data(dataLen, _k, _m, this->_gftbl, data, code);

  return true;
}

bool RSBASE::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
                int32_t dataLen, int32_t *nodeLenArray,
                int32_t *groupLenArray) {

  int32_t nSrcErrs = 0, nParErrs = 0, dataSize = 0;
  int32_t crackId = 0;
  for (int32_t i = 0; i < _n; i++) {
    // Calculate the total amount of corrupted block
    // Choose the first _k blocks to do recovery
    if (corruptArray[i] == 1) {
      dataArray[i] = -1;
      if (i < _k) {
        nSrcErrs++;
      } else {
        nParErrs++;
      }
      crackId = i / _nr;
    } else {
      dataSize++;
      dataArray[i] = i / _nr;
    }
  }

  if ((nSrcErrs + nParErrs) > _m)
    return false;

  // now start from the corrupt rack, first choose all available blocks in that rack.
  int32_t collected = 0;
  for (int32_t i = 0; i < _r; i++) {
    for (int32_t j = 0; j < _nr; j++) {
      int32_t idx = (crackId + i) * _nr + j;
      idx = idx % _n;
      if (collected >= _k) {
        dataArray[idx] = -1;
      } else {
        if(corruptArray[idx] == 0) collected += 1;
        else dataArray[idx] = -1;
      }
    }
  }

  for (int32_t i = 0; i < _n; i++) {
    nodeLenArray[i] = dataLen;
  }
  for (int32_t i = 0; i < _r; i++) {
    groupLenArray[i] = dataLen;
  }
  /* lenArray[0] = dataLen; */
  /* lenArray[1] = dataLen; */
  for (int32_t i = 0; i < _r; i++) {
    groupArray[i] = 0;
  }

  // 1. choose the related lines in the encode matrix
  uint8_t emat[_k * _k];
  int32_t pemat = 0;
  for(int32_t i=0; i<_n; i++) {
    if(dataArray[i] != -1) {
      memcpy(emat+pemat*_k, _encode_matrix+i*_k, _k);
      pemat++;
    }
  }

  // 2. calculate the invert matrix.
  uint8_t dmat[_k*_k];
  gf_invert_matrix(emat, dmat, _k);

  // 3. choose the recover lines in encode matrix
  uint8_t rmat[(nSrcErrs+nParErrs)*_k];
  int32_t prmat=0;
  for(int32_t i=0; i<_n; i++) {
    if(corruptArray[i] == 1) {
      memcpy(rmat+prmat*_k, _encode_matrix+i*_k, _k);
      prmat++;
    }
  }

  // 4. calculate the final matrix
  //  cout<<"final matrix:"<<endl;
  uint8_t totalErrs = nSrcErrs + nParErrs;
  for(int32_t i=0; i<totalErrs; i++) {
    for(int32_t j=0; j<_k; j++) {
      fmat[i*_k+j] = 0;
      for(int32_t l=0; l<_k; l++) {
        fmat[i*_k+j] ^= gf_mul(rmat[i*_k+l], dmat[l*_k+j]);
      }
  //      printf("%d ", fmat[i*_k+j]);
    }
  //    printf("\n");
  }

  return true;
}

bool RSBASE::nodeEncode(int32_t nodeId, int32_t *corruptArray, uint8_t *data,
                     int32_t inputLen, uint8_t *code, int32_t outputLen) {
  memcpy(code, data, outputLen);
  return true;
}

bool RSBASE::groupEncode(int32_t groupId, int32_t *corruptArray,
                      uint8_t **groupinput, int32_t inputSize,
                      int32_t *inputLen, uint8_t **groupoutput,
                      int32_t outputSize, int32_t outputLen) {

  return true;
}

bool RSBASE::decode(int32_t *corruptArray, uint8_t **targetinput,
                 int32_t *decodeArray, int32_t *inputLen,
                 uint8_t **targetoutput, int32_t outputSize,
      		 int32_t outputLen) {
  int32_t inputSize = 0;
  for(int32_t i=0; i<_r; i++) {
    inputSize += decodeArray[i];
  }
  cout<<"inputSize = "<<inputSize<<endl;
  uint8_t tmp_gftbl[32 * outputSize * inputSize];
  ec_init_tables(inputSize, outputSize, fmat, tmp_gftbl);
  ec_encode_data(outputLen, inputSize, outputSize, tmp_gftbl, targetinput, targetoutput);

  return true;
}
