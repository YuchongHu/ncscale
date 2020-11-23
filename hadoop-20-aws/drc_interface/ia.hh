#ifndef IA_HH_
#define IA_HH_

#include "drc.hh"

#ifdef __cplusplus
extern "C" {
#include <isa-l.h>
#endif

class IA : public DRC {
private:
  int32_t _m;
  int32_t _r;
  int32_t _nr;
  int32_t _k;
  int32_t _n;
  int32_t _chunk_num_per_node;
  int32_t _sys_chunk_num;
  int32_t _enc_chunk_num;
  int32_t _total_chunk_num;
  int32_t _survNum;
  int32_t _f;
  uint8_t *_ori_encoding_matrix;
  uint8_t *_dual_enc_matrix;
  uint8_t *_offline_enc_vec;
  uint8_t *_recovery_equations;
  int32_t *_failed_node_list;
  uint8_t *_final_enc_matrix;

  void generate_inverse_table();
  bool is_failed(int32_t index);
  void square_cauchy_matrix(uint8_t *des, int32_t size);
  uint8_t *matrix_multiply(uint8_t *mat1, uint8_t *mat2, int32_t size);
  uint8_t *matrix_multiply2(uint8_t *mat1, uint8_t *mat2, int32_t row,
                            int32_t column, int32_t mcolumn);
  void show_matrix(uint8_t *mat, int32_t row_num, int32_t column_num);
  bool generate_encoding_matrix();
  void encOlGenerator();
  bool set_f(int32_t num, int32_t *list);
  void single_node_repair(int32_t index);

public:
  IA();
  bool initialize(int32_t k, int32_t m, int32_t r, int32_t nr);
  bool construct(uint8_t **data, uint8_t **code, int32_t dataLen);
  bool check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
             int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray);
  bool nodeEncode(int32_t nodeId, int32_t *corruptArray, uint8_t *data,
                  int32_t inputLen, uint8_t *code, int32_t outputLen);
  bool groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput,
                   int32_t inputSize, int32_t *inputLen, uint8_t **groupoutput,
                   int32_t outputSize, int32_t outputLen);
  bool decode(int32_t *corruptArray, uint8_t **targetinput,
              int32_t *decodeArray, int32_t *inputLen, uint8_t **targetoutput,
              int32_t outputSize, int32_t outputLen);
};

#ifdef __cplusplus
}
#endif

#endif
