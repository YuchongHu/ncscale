#include <iostream>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <isa-l.h>
#include "drc.hh"

DRC::DRC() {
}

bool DRC::initialize(int32_t stripeSize, 
					int32_t paritySize, 
					int32_t rackNum, 
					int32_t rackSize)
{
	_k = stripeSize;
	_m = paritySize;
	_r = rackNum;
	_nr = rackSize;
	/**
	 * other needed matrix can be initialized there
	 */

	return true;
}


bool DRC::construct(uint8_t **data, 
					uint8_t **code, 
					int32_t dataLen){
	return true;
}

bool DRC::check(int32_t *corruptArray, 
				int32_t *dataArray, 
				int32_t *groupArray,
				int32_t dataLen,
				int32_t *nodeLenArray,
                int32_t *groupLenArray){ 
    return true;
}

bool DRC::nodeEncode(int32_t nodeId,
					int32_t *corruptArray,
					uint8_t *data, 
					int32_t inputLen,
					uint8_t *code, 
					int32_t outputLen){

	return true;
}

bool DRC::groupEncode(int32_t groupId,
					int32_t *corruptArray, 
					uint8_t **groupinput, 
					int32_t inputSize, 
					int32_t *inputLen,
					uint8_t **groupoutput, 
					int32_t outputSize,
					int32_t outputLen){
    return true;
}

bool DRC::decode(int32_t *corruptArray, 
				uint8_t **targetinput, 
				int32_t *decodeArray,
				int32_t *inputLen,
                uint8_t **targetoutput, 
				int32_t outputSize, 
				int32_t outputLen){
    return true;
}

