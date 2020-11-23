#ifndef RS_HH_
#define RS_HH_

#include "drc.hh"
#define RS_N_MAX (32)

#ifdef __cplusplus
extern "C" {
#endif

class RS : public DRC {

private:
  int32_t _n; // total amount of data blocks and parity blocks
  uint8_t _encode_matrix[RS_N_MAX * RS_N_MAX];
  uint8_t _gftbl[RS_N_MAX * RS_N_MAX * 32];

public:
  RS();

  bool initialize(int32_t k, int32_t m, int32_t r, int32_t nr);

  bool construct(uint8_t **data, uint8_t **code, int32_t dataLen);

  bool check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
             int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray);

  bool nodeEncode(int32_t nodeId, int32_t *corruptArray, uint8_t *data,
                  int32_t inputLen, uint8_t *code, int32_t outputLen);

  bool groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput,
                   int32_t inputSize, int32_t *inputlen, uint8_t **groupoutput,
                   int32_t outputSize, int32_t outputLen);

  bool decode(int32_t *corruptArray, uint8_t **targetinput,
              int32_t *decodeArray, int32_t *inputlen, uint8_t **targetoutput,
              int32_t outputSize, int32_t outputLen);
};

#ifdef __cplusplus
}
#endif

#endif
