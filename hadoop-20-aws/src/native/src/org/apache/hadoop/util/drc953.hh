#ifndef DRC953_HH_
#define DRC953_HH_

#include "drc.hh"

/**
 * drc953 code
 * Y. Hu, P. P. C. Lee, and X. Zhang. Double Regenerating Codes for Hierarchical Data Centers. In Proc. of IEEE ISIT, 2016.
 * n = 9, k = 5, r = 3
 */

#ifdef __cplusplus
extern "C" {
#include <isa-l.h>
#endif

class DRC953 : public DRC {
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
        uint8_t* _enc_matrix;

        void show_matrix(uint8_t* mat, int32_t row_num, int32_t column_num);
        bool generate_encoding_matrix();

    public:
        DRC953();
        bool initialize(int32_t k, int32_t m, int32_t r, int32_t nr);
        bool construct(uint8_t **data, uint8_t **code, int32_t dataLen);
        bool check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray, int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray);
        bool nodeEncode(int32_t nodeId, int32_t* corruptArray, uint8_t *data, int32_t inputLen, uint8_t *code, int32_t outputLen);
        bool groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput, int32_t inputSize, int32_t *inputLen, uint8_t **groupoutput, int32_t outputSize, int32_t outputLen);
        bool decode(int32_t *corruptArray, uint8_t **targetinput, int32_t *decodeArray, int32_t *inputLen, uint8_t **targetoutput, int32_t outputSize, int32_t outputLen);
};

#ifdef __cplusplus
}
#endif

#endif
