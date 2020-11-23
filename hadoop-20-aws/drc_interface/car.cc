#include "car.hh"
#include <iostream>
#include <isa-l.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>

using namespace std;

CAR::CAR() {}

bool CAR::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
  _k = k;
  _m = m;
  _r = r;
  _nr = nr;
  _n = k + m;

  gf_gen_rs_matrix(this->_encode_matrix, _n,
                   _k); // vandermonde matrix, aplpha=2
  ec_init_tables(_k, _m, &this->_encode_matrix[_k * _k], this->_gftbl);
//   cout<<"after init"<<endl;
//   for(int32_t i=0; i<_n; i++) {
//     for(int32_t j=0; j<_k; j++) {
//       printf("%d ", _encode_matrix[i*_k+j]);
// //      cout<<_encode_matrix[i*_k+j]<<"	";
//     }
//     cout<<endl;
//   }


  return true;
}

bool CAR::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
  ec_encode_data(dataLen, _k, _m, this->_gftbl, data, code);

  return true;
}

bool CAR::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
               int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray) {
  int32_t nSrcErrs = 0, nParErrs = 0, dataSize = 0;
  int32_t crackId = 0;
  for (int32_t i = 0; i < _n; i++) {
    // Calculate the total amount of corrupted block
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

  // now check for the groupArray
  for(int32_t i = 0; i < _r; i++) {
    int32_t sumInRack = 0;
    for(int32_t j = 0; j < _nr; j++) {
      if(dataArray[i * _nr + j] != -1) {
        sumInRack++;
      }
    }
    if(sumInRack > 1) {
      groupArray[i] = nSrcErrs + nParErrs;
    } else {
      groupArray[i] = 0;
    }

    if(i==crackId) groupArray[i] = 0;
  }

  for (int32_t i = 0; i < _n; i++) {
    nodeLenArray[i] = dataLen;
  }
  for (int32_t i = 0; i < _r; i++) {
    groupLenArray[i] = dataLen;
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

//   cout<<"emat :"<<endl;
//   for(int32_t i=0; i<_k; i++) {
//     for(int32_t j=0; j<_k; j++) {
//       printf("%d ", emat[i*_k+j]);
//     }
//     cout<<endl;
//   }

  // 2. calculate the invert matrix.
  uint8_t dmat[_k*_k];
  gf_invert_matrix(emat, dmat, _k);

//   cout<<"dmat :"<<endl;
//   for(int32_t i=0; i<_k; i++) {
//     for(int32_t j=0; j<_k; j++) {
//       printf("%d ", dmat[i*_k+j]);
//     }
//     cout<<endl;
//   }

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

bool CAR::nodeEncode(int32_t nodeId, int32_t *corruptArray, uint8_t *data,
                    int32_t inputLen, uint8_t *code, int32_t outputLen) {
  memcpy(code, data, outputLen);
  return true;
}

bool CAR::groupEncode(int32_t groupId, int32_t *corruptArray,
                     uint8_t **groupinput, int32_t inputSize, int32_t *inputlen,
                     uint8_t **groupoutput, int32_t outputSize,
                     int32_t outputLen) {
  // 0. same with check, find out the proper lines to recover data

  int32_t nSrcErrs = 0, nParErrs = 0, dataSize = 0;
  int32_t crackId = 0;
  int32_t *dataArray = (int32_t *)malloc(sizeof(int32_t) * (_k+_m));
  for (int32_t i = 0; i < _n; i++) {
    // Calculate the total amount of corrupted block
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

  // choose determinant from fmat
  uint8_t rmat[(nSrcErrs + nParErrs) * inputSize];
  int32_t startr=0;
  for(int32_t i=0; i<_n; i++) {
    if(dataArray[i]>-1) {
      if(i/_nr == groupId) break;
      else startr++;
    }
  }
//  cout<<"startr = "<<startr<<endl;
//  cout<<"inputSize = "<<inputSize<<endl;
  for(int32_t i=0; i<(nSrcErrs + nParErrs); i++) {
    memcpy(rmat+i*inputSize, fmat+i*_k+startr, inputSize);
  }

//  cout<<"group mtx"<<endl;
//  for(int32_t i=0; i<(nSrcErrs + nParErrs); i++) {
//    for(int32_t j=0; j<inputSize; j++) {
//      printf("%d ", rmat[i*inputSize+j]);
//    }
//    printf("\n");
//  }

  uint8_t tmp_gftbl[32 * inputSize * (nSrcErrs + nParErrs)];
  ec_init_tables(inputSize, (nSrcErrs + nParErrs), rmat, tmp_gftbl);
  ec_encode_data(outputLen, inputSize, (nSrcErrs + nParErrs), tmp_gftbl, groupinput, groupoutput);


  return true;
}

bool CAR::decode(int32_t *corruptArray, uint8_t **targetinput,
                int32_t *decodeArray, int32_t *inputlen, uint8_t **targetoutput,
                int32_t outputSize, int32_t outputLen) {

  // 0. same with check, find out the proper lines to recover data

  int32_t nSrcErrs = 0, nParErrs = 0, dataSize = 0;
  int32_t crackId = 0;
  int32_t *dataArray = (int32_t *)malloc(sizeof(int32_t) * (_k+_m));
  int32_t *groupArray = (int32_t *)malloc(sizeof(int32_t) * (_r));
  for (int32_t i = 0; i < _n; i++) {
    // Calculate the total amount of corrupted block
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

  int32_t nErrs = nSrcErrs + nParErrs;
  int32_t inputSize = 0;
  for(int32_t i=0; i<_r; i++) {
    inputSize += decodeArray[i];
  }
//  cout<<"inputSize = "<<inputSize<<endl;

  // now check for the groupArray
  for(int32_t i = 0; i < _r; i++) {
    int32_t sumInRack = 0;
    for(int32_t j = 0; j < _nr; j++) {
      if(dataArray[i * _nr + j] != -1) {
        sumInRack++;
      }
    }
    if(sumInRack > 1) {
      groupArray[i] = nSrcErrs + nParErrs;
    } else {
      groupArray[i] = 0;
    }

    if(i==crackId) groupArray[i] = 0;
  }

  uint8_t decodemat[nErrs * inputSize];
  int32_t pdmat = 0;
  int32_t pfmat = 0;

  for(int32_t i=0; i<_r; i++) {
    if(groupArray[i] == 0) {
      for(int32_t j=0; j<_nr; j++) {
        if(dataArray[i*_nr+j]>-1) {
          for(int32_t l=0; l<nErrs; l++) {
            decodemat[l*inputSize+pdmat] = fmat[l*_k+pfmat];
          }
          pdmat++;
          pfmat++;
	}
      }
    } else {
      for(int32_t j=0; j<nErrs; j++) {
        for(int32_t l=0; l<groupArray[i]; l++) {
	  if(l==j) decodemat[j*inputSize+pdmat+l]=1;
	  else decodemat[j*inputSize+pdmat+l]=0;
	}
	pdmat+=groupArray[i];
      }
      for(int32_t j=0; j<_nr; j++) {
        if(dataArray[i * _nr + j] != -1) pfmat++;
      }
    }
  }

//  cout<<"recover mat:"<<endl;
//  for(int32_t i=0; i<nErrs; i++) {
//    for(int32_t j=0; j<inputSize; j++) {
//      printf("%d ", decodemat[i*inputSize+j]);
//    }
//    cout<<endl;
//  }
      
    
  uint8_t tmp_gftbl[32 * inputSize * nErrs];
  ec_init_tables(inputSize, nErrs, decodemat, tmp_gftbl);
  ec_encode_data(outputLen, inputSize, nErrs, tmp_gftbl, targetinput, targetoutput);

  return true;
}
