#include "ia.hh"
#include <iostream>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

using namespace std;

IA::IA() {
  _m = 0;
  _r = 0;
  _nr = 0;
  _k = 0;
  _n = 0;
  _chunk_num_per_node = 0;
  _sys_chunk_num = 0;
  _enc_chunk_num = 0;
  _total_chunk_num = 0;
  _survNum = 0;
  _f = 0;
  _ori_encoding_matrix = NULL;
  _dual_enc_matrix = NULL;
  _offline_enc_vec = NULL;
  _final_enc_matrix = NULL;
  _failed_node_list = NULL;
  _recovery_equations = NULL;
}

bool IA::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
  _m = m;
  _r = r;
  _nr = nr;
  _k = k;
  _n = k + m;
  _chunk_num_per_node = _k;
  _sys_chunk_num = _k * _chunk_num_per_node;
  _enc_chunk_num = _m * _chunk_num_per_node;
  _total_chunk_num = _sys_chunk_num + _enc_chunk_num;

  generate_encoding_matrix();
  return true;
}

bool IA::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
  int32_t symbolSize = dataLen / _chunk_num_per_node;
  /* cout << "symbolSize in construct(): " << symbolSize << endl; */
  uint8_t *databuf[_k * _k], *codebuf[_k * _k];
  uint8_t tmp_gftbl[32 * _k * _k * _k * _k];
  for (int32_t i = 0; i < _k * _k; i++) {
    databuf[i] = data[i / _k] + i % _k * symbolSize * sizeof(uint8_t);
    codebuf[i] = code[i / _k] + i % _k * symbolSize * sizeof(uint8_t);
  }
  ec_init_tables(_k * _k, _k * _k, _final_enc_matrix, tmp_gftbl);
  ec_encode_data(symbolSize, _k * _k, _k * _k, tmp_gftbl, databuf, codebuf);
  return true;
}

bool IA::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
               int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray) {
  int32_t corruptNum = 0;
  for (int32_t i = 0; i < _n; i++) {
    if (1 == corruptArray[i]) {
      dataArray[i] = -1;
      corruptNum++;
    } else
      dataArray[i] = i / _nr;
  }
  int32_t *corruptList = new int32_t[corruptNum];
  int32_t corruptListIdx = 0;
  for (int32_t i = 0; i < _n; i++) {
    if (1 == corruptArray[i]) {
      corruptList[corruptListIdx++] = i;
    }
  }

  if (1 != corruptNum)
    return false;
  if (!set_f(corruptNum, corruptList))
    return false;

  for (int32_t i = 0; i < _r; i++) {
    if (groupArray[i] == 1)
      groupArray[i] = 0;
  }
  for (int32_t i = 0; i < _n; i++) {
    nodeLenArray[i] = dataLen / _chunk_num_per_node;
  }
  for (int32_t i = 0; i < _r; i++) {
    groupLenArray[i] = dataLen / _chunk_num_per_node;
  }
  /* lenArray[0] = dataLen/_chunk_num_per_node; */
  /* lenArray[1] = dataLen/_chunk_num_per_node; */
  return true;
}

bool IA::nodeEncode(int32_t nodeId, int32_t *corruptArray, uint8_t *data,
                    int32_t inputLen, uint8_t *code, int32_t outputLen) {

  // decide which subset of date is required to send out
  int32_t corruptId = -1;
  for (int32_t i = 0; i < _n; i++) {
    if (corruptArray[i] == 1) {
      corruptId = i;
      break;
    }
  }
  // send data out
  if (corruptId < _k) {
    memcpy(code, data + corruptId * outputLen * sizeof(uint8_t), outputLen);
  } else {
    uint8_t *databuf[_chunk_num_per_node];
    uint8_t *codebuf[1];
    for (int32_t i = 0; i < _chunk_num_per_node; i++) {
      databuf[i] = data + i * outputLen * sizeof(uint8_t);
    }
    codebuf[0] = code;
    uint8_t gftbls[32 * _chunk_num_per_node];
    ec_init_tables(_chunk_num_per_node, 1,
                   _offline_enc_vec +
                       corruptId * _chunk_num_per_node * sizeof(uint8_t),
                   gftbls);
    ec_encode_data(outputLen, _chunk_num_per_node, 1, gftbls, databuf, codebuf);
  }
  return true;
}

bool IA::groupEncode(int32_t groupId, int32_t *corruptArray,
                     uint8_t **groupinput, int32_t inputSize, int32_t *inputLen,
                     uint8_t **groupoutput, int32_t outputSize,
                     int32_t outputLen) {
  return true;
}

bool IA::decode(int32_t *corruptArray, uint8_t **targetinput,
                int32_t *decodeArray, int32_t *inputLen, uint8_t **targetoutput,
                int32_t outputSize, int32_t outputLen) {
  int32_t symbolSize = outputLen / _chunk_num_per_node;
  uint8_t *outbuf[_k];
  for (int32_t i = 0; i < _k; i++) {
    outbuf[i] = targetoutput[i / _k] + i % _k * symbolSize * sizeof(uint8_t);
  }
  uint8_t tmp_gftbl[32 * _survNum * _chunk_num_per_node];
  ec_init_tables(_survNum, _chunk_num_per_node, _recovery_equations, tmp_gftbl);
  ec_encode_data(symbolSize, _survNum, _chunk_num_per_node, tmp_gftbl,
                 targetinput, outbuf);

  return true;
}

void IA::square_cauchy_matrix(uint8_t *des, int32_t size) {
  uint8_t *Xset = (uint8_t *)calloc(size, sizeof(uint8_t));
  uint8_t *Yset = (uint8_t *)calloc(size, sizeof(uint8_t));
  for (int32_t i = 0; i < size; i++) {
    Xset[i] = (uint8_t)(i + 1);
    Yset[i] = (uint8_t)(i + 1 + size);
  }
  for (int32_t i = 0; i < size; i++) {
    for (int32_t j = 0; j < size; j++) {
      des[i * size + j] = gf_inv(Xset[i] ^ Yset[j]);
    }
  }
  free(Xset);
  free(Yset);
}

// This is used for multiplication of two square matrix
uint8_t *IA::matrix_multiply(uint8_t *mat1, uint8_t *mat2, int32_t size) {
  uint8_t *des = (uint8_t *)calloc(size * size, sizeof(uint8_t));
  for (int32_t i = 0; i < size; i++) {
    for (int32_t j = 0; j < size; j++) {
      for (int32_t k = 0; k < size; k++) {
        des[i * size + j] ^= gf_mul(mat1[i * size + k], mat2[k * size + j]);
      }
    }
  }
  return des;
}

// This is for multiplication of two matrix generally
// m * n, where m_column = n_rows
uint8_t *IA::matrix_multiply2(uint8_t *mat1, uint8_t *mat2, int32_t row,
                              int32_t column, int32_t mcolumn) {
  uint8_t *des = (uint8_t *)calloc(row * column, sizeof(uint8_t));
  for (int32_t i = 0; i < row; i++) {
    for (int32_t j = 0; j < column; j++) {
      for (int32_t k = 0; k < mcolumn; k++) {
        des[i * column + j] ^=
            gf_mul(mat1[i * mcolumn + k], mat2[k * column + j]);
      }
    }
  }
  return des;
}

void IA::show_matrix(uint8_t *mat, int32_t row_num, int32_t column_num) {
  for (int32_t i = 0; i < row_num; i++) {
    for (int32_t j = 0; j < column_num; j++) {
      printf("%4d", mat[i * column_num + j]);
    }
    printf("\n");
  }
  printf("\n");
}

bool IA::generate_encoding_matrix() {
  /* cout << "enter generateEncodingMatrix()" << endl; */
  // For each num i in [1, pow(2, _conf_w_)),
  // calculate j where galois_single_multiply(i, j, _conf_w_) = 1
  _ori_encoding_matrix =
      (uint8_t *)calloc(_total_chunk_num * _sys_chunk_num, sizeof(uint8_t));
  _dual_enc_matrix =
      (uint8_t *)calloc(_total_chunk_num * _sys_chunk_num, sizeof(uint8_t));
  _offline_enc_vec = (uint8_t *)calloc(_n * _k, sizeof(uint8_t));
  uint8_t *UMat = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
  uint8_t *VMat = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
  uint8_t *PMat = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
  uint8_t *invUMat = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
  uint8_t *invPMat = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
  uint8_t greekK = 2;
  // generate matrix P first
  square_cauchy_matrix(PMat, _k);
  // generate matrix V and U, make V be I
  for (int32_t i = 0; i < _k; i++) {
    VMat[i * _k + i] = 1;
  }
  // The equation for calculating Matrix U should be U=1/k * P
  uint8_t KMinus = gf_inv(greekK);
  for (int32_t i = 0; i < _k; i++) {
    for (int32_t j = 0; j < _k; j++) {
      UMat[i * _k + j] = gf_mul(KMinus, PMat[i * _k + j]);
    }
  }
  /* cout << "UMat after mutiply KMinus in generateEncodingMatrix(): " << endl;
   */
  /* show_matrix(UMat, _k, _k); */
  uint8_t *tmp_matrix = (uint8_t *)malloc(_k * _k * sizeof(uint8_t));
  memcpy(tmp_matrix, UMat, _k * _k);
  gf_invert_matrix(tmp_matrix, invUMat, _k);
  memcpy(tmp_matrix, PMat, _k * _k);
  gf_invert_matrix(tmp_matrix, invPMat, _k);
  delete tmp_matrix;
  /* cout << "invUMat in generateEncodingMatrix(): " << endl; */
  /* show_matrix(invUMat, _k, _k); */
  /* cout << "invPMat in generateEncodingMatrix(): " << endl; */
  /* show_matrix(invPMat, _k, _k); */

  // Now the lower part of encoding matrix. It was composed of _k*_k
  // square matrices, each of size _k*_k. We generate them one by one
  for (int32_t i = 0; i < _sys_chunk_num; i++) {
    _ori_encoding_matrix[i * _sys_chunk_num + i] = 1;
  }
  for (int32_t i = 0; i < _k; i++) {
    for (int32_t j = 0; j < _k; j++) {
      // Generate the square matrix at i-th row and j-th column
      // The way to calculate should be u_i * v_j^t + p[j,i] * I
      int32_t rStart = (_k + j) * _k;
      int32_t cStart = i * _k;
      uint8_t *VRow = (uint8_t *)calloc(_k, sizeof(uint8_t));
      uint8_t *URow = (uint8_t *)calloc(_k, sizeof(uint8_t));
      for (int32_t k = 0; k < _k; k++) {
        VRow[k] = VMat[k * _k + i];
        URow[k] = UMat[k * _k + j];
      }
      uint8_t *tmpMat = matrix_multiply2(URow, VRow, _k, _k, 1);
      for (int32_t k = 0; k < _k; k++) {
        tmpMat[k * _k + k] ^= PMat[i * _k + j];
      }
      for (int32_t k = 0; k < _k; k++) {
        for (int32_t l = 0; l < _k; l++) {
          _ori_encoding_matrix[(rStart + k) * _sys_chunk_num + l + cStart] =
              tmpMat[l * _k + k];
        }
      }
      free(tmpMat);
      // show_matrix(tmpMat,_k,_k);
    }
  }
  tmp_matrix =
      (uint8_t *)malloc(_enc_chunk_num * _sys_chunk_num * sizeof(uint8_t));
  memcpy(tmp_matrix, _ori_encoding_matrix + _sys_chunk_num * _sys_chunk_num,
         _enc_chunk_num * _sys_chunk_num);
  gf_invert_matrix(tmp_matrix, _dual_enc_matrix, _sys_chunk_num);
  delete tmp_matrix;
  for (int32_t i = 0; i < _sys_chunk_num; i++) {
    _dual_enc_matrix[_sys_chunk_num * _sys_chunk_num + i * _sys_chunk_num + i] =
        1;
  }

  // Create metadata for data reconstruction
  memcpy((uint8_t *)_offline_enc_vec, (uint8_t *)VMat,
         _k * _k * sizeof(uint8_t));
  for (int32_t i = 0; i < _k; i++) {
    for (int32_t j = 0; j < _k; j++) {
      _offline_enc_vec[_k * _k + i * _k + j] = UMat[j * _k + i];
    }
  }
  /* cout << "_offline_enc_vec, _offline_enc_vec in generateEncodingMatrix(): "
   * << endl; */
  /* show_matrix(_offline_enc_vec, _n, _k); */

  free(UMat);
  free(VMat);
  free(PMat);
  free(invUMat);
  free(invPMat);
  /* cout << "_dual_enc_matrix in generateEncodingMatrix(): " << endl; */
  /* show_matrix(_dual_enc_matrix, _total_chunk_num,_sys_chunk_num); */
  /* printf("_ori_encoding_matrix in generateEncodingMatrix()\n"); */
  /* show_matrix(_ori_encoding_matrix, _total_chunk_num,_sys_chunk_num); */
  // take the bottom part of _ori_encoding_matrix as _final_enc_matrix
  _final_enc_matrix = _ori_encoding_matrix + _sys_chunk_num * _sys_chunk_num;

  /* printf("_final_enc_matrix in generateEncodingMatrix()\n"); */
  /* show_matrix(_final_enc_matrix,_k*_k,_k*_k); */

  return true;
}

bool IA::set_f(int32_t num, int32_t *list) {
  /* cout << "enter set_f()" << endl; */
  _f = num;
  _failed_node_list = list;
  _survNum = _n - _f;
  this->single_node_repair(_failed_node_list[0]);

  if (_recovery_equations == NULL) {
    return false;
  } else {
    return true;
  }
}

void IA::single_node_repair(int32_t index) {
  /* cout << "enter single_node_repair()" << endl; */
  uint8_t *recoveryEquations =
      (uint8_t *)calloc(_chunk_num_per_node * (_n - 1), sizeof(uint8_t));
  uint8_t *recvData =
      (uint8_t *)calloc((_n - 1) * _sys_chunk_num, sizeof(uint8_t));
  // This part consists of two situations:
  // Node index is a data node, or Node index is a parity node
  // we consider them separately
  if (index < _k) {
    // Data Node repair
    // First we collect the recv'd data
    int32_t survInd = 0;
    for (int32_t i = 0; i < _n; i++) {
      if (i == index) {
        continue;
      }
      for (int32_t j = 0; j < _k; j++) {
        if (_offline_enc_vec[index * _k + j] != 0) {
          for (int32_t k = 0; k < _sys_chunk_num; k++) {
            recvData[survInd * _sys_chunk_num + k] ^=
                gf_mul(_offline_enc_vec[index * _k + j],
                       _ori_encoding_matrix[(i * _k + j) * _sys_chunk_num + k]);
          }
        }
      }
      survInd++;
    }
    /* cout << "Before IA elimination, recvData in single_node_repair() for data
     * repair: " << endl; */
    /* show_matrix(recvData, _n-1, _sys_chunk_num); */

    // Now we eliminate the interference alignment
    survInd = 0;
    for (int32_t i = 0; i < _k; i++) {
      recoveryEquations[i * (_n - 1) + i + _k - 1] = 1;
    }
    for (int32_t i = 0; i < _k; i++) {
      if (i == index) {
        continue;
      }
      int32_t coefficient = 0;
      for (int32_t j = 0; j < _k; j++) {
        for (int32_t k = i * _k; k < (i + 1) * _k; k++) {
          if ((recvData[survInd * _sys_chunk_num + k] != 0) &&
              (recvData[(j + _k - 1) * _sys_chunk_num + k] != 0)) {
            coefficient = recvData[(j + _k - 1) * _sys_chunk_num + k] *
                          (gf_inv(recvData[survInd * _sys_chunk_num + k]));
            recoveryEquations[j * (_n - 1) + survInd] = coefficient;
            break;
          }
        }
      }
      survInd++;
    }
    /* printf("After IA Elimination, recoveryEquations: \n"); */
    /* show_matrix(recoveryEquations,_k,_n-1); */

    // Now generate the final recovery equations
    uint8_t *oriSqr = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
    uint8_t *invOriSqr = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
    int32_t rStart = _k - 1;
    int32_t cStart = index * _k;
    for (int32_t i = 0; i < _k; i++) {
      for (int32_t j = 0; j < _k; j++) {
        oriSqr[i * _k + j] =
            recvData[(rStart + i) * _sys_chunk_num + j + cStart];
      }
    }
    gf_invert_matrix(oriSqr, invOriSqr, _k);
    uint8_t *temp = recoveryEquations;
    recoveryEquations = matrix_multiply2(invOriSqr, temp, _k, _n - 1, _k);

    /* printf("recvData\n"); */
    /* show_matrix(recvData, _n-1, _sys_chunk_num); */
    /* printf("Final Recovery Equations\n"); */
    /* show_matrix(recoveryEquations, _k, _n-1); */
    /* uint8_t* validate; */
    /* validate=matrix_multiply2(recoveryEquations,recvData,_k, */
    /*         _sys_chunk_num,_n-1); */
    /* show_matrix(validate,_k,_sys_chunk_num); */

    free(temp);
    free(oriSqr);
    free(invOriSqr);
    free(recvData);
  } else {
    // Parity Node repair
    // The process is almost the same as reconstruction for data node, but
    // we use the dual encoding matrix instead
    // First we collect the recv'd data
    int32_t survInd = 0;
    for (int32_t i = 0; i < _n; i++) {
      if (i == index)
        continue;
      // printf("%d %d\n",i,survInd);
      for (int32_t j = 0; j < _k; j++) {
        if (_offline_enc_vec[index * _k + j] != 0) {
          for (int32_t k = 0; k < _sys_chunk_num; k++) {
            recvData[survInd * _sys_chunk_num + k] ^=
                gf_mul(_offline_enc_vec[index * _k + j],
                       _dual_enc_matrix[(i * _k + j) * _sys_chunk_num + k]);
          }
        }
      }
      survInd++;
    }
    /* cout << "recvData in single_node_repair for parity recovery:"<<endl; */
    /* show_matrix(recvData,_n-1,_sys_chunk_num); */

    // Now we eliminate the interference alignment
    survInd = _k;
    for (int32_t i = 0; i < _k; i++) {
      recoveryEquations[i * (_n - 1) + i] = 1;
    }
    for (int32_t i = _k; i < _n; i++) {
      if (i == index)
        continue;
      int32_t coefficient = 0;
      for (int32_t j = 0; j < _k; j++) {
        for (int32_t k = (i - _k) * _k; k < (i + 1 - _k) * _k; k++) {
          if ((recvData[survInd * _sys_chunk_num + k] != 0) &&
              (recvData[j * _sys_chunk_num + k] != 0)) {
            coefficient =
                gf_mul(recvData[j * _sys_chunk_num + k],
                       gf_inv(recvData[survInd * _sys_chunk_num + k]));
            recoveryEquations[j * (_n - 1) + survInd] = coefficient;
            break;
          }
        }
      }
      survInd++;
    }
    /* printf("After IA Elimination\n"); */
    /* show_matrix(recoveryEquations,_k,_n-1); */

    // Now generate the final recover equations
    uint8_t *oriSqr = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
    uint8_t *invOriSqr = (uint8_t *)calloc(_k * _k, sizeof(uint8_t));
    int32_t rStart = 0;
    int32_t cStart = (index - _k) * _k;
    for (int32_t i = 0; i < _k; i++) {
      for (int32_t j = 0; j < _k; j++) {
        oriSqr[i * _k + j] =
            recvData[(rStart + i) * _sys_chunk_num + j + cStart];
      }
    }
    gf_invert_matrix(oriSqr, invOriSqr, _k);
    uint8_t *temp = recoveryEquations;
    recoveryEquations = matrix_multiply2(invOriSqr, temp, _k, _n - 1, _k);

    /* printf("Final Recovery Equations\n"); */
    /* show_matrix(recoveryEquations, _k, _n-1); */
    /* uint8_t* validate; */
    /* validate=matrix_multiply2(recoveryEquations,recvData,_k, */
    /*         _sys_chunk_num,_n-1); */
    /* show_matrix(validate,_k,_sys_chunk_num); */
    free(temp);
    free(recvData);
    free(oriSqr);
    free(invOriSqr);
  }
  /* cout << "recoveryEquations in single_node_repair:"<<endl; */
  /* show_matrix(recoveryEquations, _chunk_num_per_node, _n-1); */
  _recovery_equations = recoveryEquations;
}

bool IA::is_failed(int32_t index) {
  for (int32_t i = 0; i < _f; i++) {
    if (index == _failed_node_list[i])
      return true;
  }
  return false;
}
