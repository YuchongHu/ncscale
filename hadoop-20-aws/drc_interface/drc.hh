/**
 * drc.hh
 * - Definition of double regenerating codes
 * please refer to tester.cc for the usage examples
 */

#ifndef __drc_hh__
#define __drc_hh__

#include <stdint.h>
#include <isa-l.h>

class DRC { 
protected:
	int32_t _k;	// number of data blocks in a stripe
	int32_t _m;	// number of parity blocks in a stripe
	int32_t _r;	// number of groups in a stripe
	int32_t _nr;	// number of nodes per group

public:
    /**
     * Constructor
     */
    DRC(); 
    
   /**
    * @brief this function is used in both replication->erasurecode and
    *        recovery. 
    * @param k - number of data blocks in a stripe
    * @param m - number of parity blocks in a stripe
    * @param r - number of groups in a stripe
    * @param nr - number of nodes related in a group
    * @return - whether initialize successfully
    *
    */
   virtual bool initialize(int32_t k, 
                           int32_t m, 
			   int32_t r, 
			   int32_t nr);

    /**
     * @brief drc encode method
     * @param data - data blocks to be encoded
     * @param code - parity blocks to be calculted
     * @dataLen - the length of one datablock
     * @return - whether the construct is successful
     *
     */
    virtual bool construct(uint8_t **data, 
                           uint8_t **code, 
			   int32_t dataLen);

    /**
     * @brief in this function:
     *        1. To verify whether drc can recover this corruption
     *        2. To figure out the blocks which are needed in recovery, and the groupId of each needed block
     *        3. given the basic unit size(dataLen), what is the output data length of each node, and each group.
     * @param corruptArray - if corruptArray[i]==1, then the i-th block is corrupted.
     * @param dataArray - if dataArray[i]==groupId, then the i-th block will be needed in recovery
     *                    if dataArray[i]==-1, then the i-th block will not be needed
     * @param groupArray - the i-th group will encode to groupArray[i] units
     * @param dataLen - origin unit size
     * @param nodeLenArray - the output data length of each node
     * @param groupLenArray - the output data length of each group
     * @return - whether we can recover this corruption
     *
     */
    virtual bool check(int32_t *corruptArray, 
						int32_t *dataArray, 
						int32_t *groupArray,
						int32_t dataLen,
						int32_t *nodeLenArray,
                        int32_t *groupLenArray); 

    /**
     * @brief this function is used in reading a block in recover.
     * @param nodeId - the index in a stripe
     * @param corruptArray - the same with that in check function
     * @param data - disk data
     * @param inputLen - input data length
     * @param code - encoded data
     * @param outputLen - output data length
     * @return - whether we succesfully read an encoded block
     *
     */
    virtual bool nodeEncode(int32_t nodeId,
                            int32_t *corruptArray,
			    uint8_t *data, 
			    int32_t inputLen,
			    uint8_t *code, 
			    int32_t outputLen);

    /**
     * @brief this function is used in relayer
     * @param groupId - the groupId of current group
     * @param corruptArray - the same with that in check function
     * @param groupinput - the input data in a group
     * @param inputSize - the number of input data in a group
     * @param inputLen - the data length of an input
     * @param groupoutput - the data relayer calculate
     * @param outputSize - the number of output this group create
     * @param outputLen - the length of an output
     * @return - whether we succesfully finish groupEncode
     * 
     */
    virtual bool groupEncode(int32_t groupId,
                             int32_t *corruptArray, 
			     uint8_t **groupinput, 
			     int32_t inputSize, 
			     int32_t *inputLen,
			     uint8_t **groupoutput, 
			     int32_t outputSize,
			     int32_t outputLen);

    /**
     * @brief this function is used for the recover at last
     * @param corruptArray - the same with that in check function
     * @param targetinput - all inputs of the recover
     * @param decodeArray - the number of output in each group
     * @param inputLen - the data length of each input
     * @param targetoutput - the result of recover
     * @param outputSize - the number of targetoutput
     * @param outputLen - the data length of the targetoutput
     * @return - whether successfully recover this stripe
     *
     */
    virtual bool decode(int32_t *corruptArray, 
                        uint8_t **targetinput, 
			int32_t *decodeArray, 
			int32_t *inputLen,
			uint8_t **targetoutput, 
			int32_t outputSize, 
			int32_t outputLen);

};

#endif
