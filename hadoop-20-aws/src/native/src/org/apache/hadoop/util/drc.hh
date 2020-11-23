/**
 * drc.hh
 * - A generate erasure code interface
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
	 *        recover to initialize all kinds of tables that are needed in
	 *        calculation; please create needed tables as you need
	 * @param k - number of data blocks in a stripe
	 * @param m - number of parity blocks in a stripe
	 * @param r - number of groups in a stripe
	 * @param nr - the number of nodes related in a group
	 * @return a bool value to denote whether the initilal part is all right
	 *
	 */
	virtual bool initialize(int32_t k, 
							int32_t m, 
							int32_t r, 
							int32_t nr);

	/**
	 * @brief drc encode method
	 * @param data - data blocks to be encoded
	 * @param code - parity blocks to be calculted out
	 * @dataLen - the length of one datablock
	 * @return - a bool to denote the successfully replication->erasurecoding
	 *
	 * TODO: do the replication->erasurecoding for drc
	 *
	 */
	virtual bool construct( uint8_t **data, 
							uint8_t **code, 
							int32_t dataLen);

    /**
     * @brief in this function, corruptArray will tell the corrupt situation of a stripe, and this function will
     *        1. verify whether drc can recover for this situation
     *        2. what data will be needed in the following calculation
     *        3. for each group, if need relayer, how much blocks of data will be created
     * @param corruptArray - tells in a stripe what block is corrupted
     * @param dataArray - what data will be needed in recovery
     * @param groupArray - how much blocks of data will be created
	 * @param dataLen - origin data length
	 * @param lenArray - denode the length of some data
	 *        lenArray[0] - nodeEncodeLen
	 *        lenArray[1] - groupEncodeLen
     * @return a bool value to denote whether can recover for this situation
     *
     */
    virtual bool check(int32_t *corruptArray, 
						int32_t *dataArray, 
						int32_t *groupArray,
						int32_t dataLen,
						int32_t *nodeLenArray,
                        int32_t *groupLenArray); 

	/**
	 * @brief this function is used in read a block in recover.
	 *        in hdfs, when read a block, a request will be sent to the datanode which holds the data block, and it will
	 *        return the data to the client. this function is used when datanode receive a request to read a encoded block,
	 *        it will first encoded this block and then return the encoded data to the client. So that the throughput will be 
	 *        declined.
	 * @param nodeId - the logic index in a stripe
	 * @param corruptArray - the same with verify
	 * @param data - block data
	 * @param inputLen - the length of an input unit
	 * @param code - encoded data
	 * @param outputLen - the length of an output unit
	 * @return a bool value to denote the successful node encode
	 *
	 */
	virtual bool nodeEncode(int32_t nodeId,
							int32_t *corruptArray,
							uint8_t *data, 
							int32_t inputLen,
							uint8_t *code, 
							int32_t outputLen);

	/**
	 * @brief this function is used in relayer to calculate the data which will be sent to targetnode to do recover at last
	 *        and only for one node corrupt
	 *
	 * @param groupId - the groupId of groupinput 
	 * @param corruptArray - the same with verify
	 * @param groupinput - the data which relayer read from other datanode in the same rack
     * @param inputSize - the number of blocks in groupinput
	 * @param inputLen - the length of an input unit
	 * @param groupoutput - the data relayer calculate out
     * @param outputSize - the number of blocks in groupoutput
	 * @param outputLen - the length of an output unit
	 * @return - a bool value to denote the successfully groupEncode
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
	 * @param corruptArray - the same with verify
	 * @param targetinput - the input of the recover
     * @param decodeArray - denote how many blocks in each group
	 * @param inputLen - the length of an input unit
	 * @param targetoutput - the result of recover
	 * @param outputSize - the size of the targetoutput
	 * @param outputLen - the datalength of the targetinput
	 * @return a bool value to denote the successully recover
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
