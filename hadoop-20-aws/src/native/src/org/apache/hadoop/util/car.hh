#ifndef CAR_HH_
#define CAR_HH_

#include "drc.hh"
#define CAR_N_MAX (32)

/**
 * car code
 * Z. Shen, J. Shu, and P. P. Lee. Reconsidering Single Failure Recovery in Clustered File Systems. In Proc. of IEEE/IFIP
 * DSN, 2016.
 * support generate parameters
 */

#ifdef __cplusplus
extern "C" {
#endif

class CAR : public DRC {

private:
  int32_t _n; // total amount of data blocks and parity blocks
  uint8_t _encode_matrix[CAR_N_MAX * CAR_N_MAX];
  uint8_t _gftbl[CAR_N_MAX * CAR_N_MAX * 32];
  uint8_t fmat[CAR_N_MAX * CAR_N_MAX];

public:
  CAR();

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
