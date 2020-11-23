#include "butterfly64.hh"

#include <iostream>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>

using namespace std;

BUTTERFLY64::BUTTERFLY64() {
    _m = 0;
    _r = 0;
    _nr = 0;
    _k = 0;
    _n = 0;
    _chunk_num_per_node = 0;
    _sys_chunk_num = 0;
    _enc_chunk_num = 0;
    _total_chunk_num = 0;
    _enc_matrix = NULL;
}

bool BUTTERFLY64::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
    _m = m;
    _r = r;
    _nr = nr;
    _k = k;
    _n = k + m;
    _chunk_num_per_node = 8;
    _sys_chunk_num = _k * _chunk_num_per_node;
    _enc_chunk_num = _m * _chunk_num_per_node;
    _total_chunk_num = _sys_chunk_num + _enc_chunk_num;
    _enc_matrix=(uint8_t*)calloc(_total_chunk_num*_sys_chunk_num, sizeof(uint8_t));
    generate_encoding_matrix();
    return true;
}

bool BUTTERFLY64::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
    int32_t symbolSize = dataLen / _chunk_num_per_node;
    uint8_t *databuf[_k*8], *codebuf[_m*8];
    uint8_t tmp_gftbl[32*32*16];
    for (int32_t i = 0; i < _k*8; i++)
        databuf[i] = data[i/8]+i%8*symbolSize*sizeof(uint8_t);
    for (int32_t i = 0; i < _m*8; i++)
        codebuf[i] = code[i/8]+i%8*symbolSize*sizeof(uint8_t);

    ec_init_tables(_k*8, _m*8, &_enc_matrix[_k*8*_k*8], tmp_gftbl);
    ec_encode_data(symbolSize, _k*8, _m*8 , tmp_gftbl, databuf, codebuf);

    return true;
}

bool BUTTERFLY64::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
        int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray) {
    int32_t corruptNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i]) {
            dataArray[i] = -1;
            corruptNum++;
        }
        else dataArray[i] = i / _nr;
    }

    if (1 != corruptNum) return false;

    for (int32_t i = 0; i < _r; i++)
        	groupArray[i] = 0;

    for(int32_t i=0; i<(_k+_m); i++)
		nodeLenArray[i] = dataLen/2;
    for(int32_t i=0; i<_r; i++)
        groupLenArray[i] = dataLen;

    return true;
}

bool BUTTERFLY64::nodeEncode(int32_t nodeId, int32_t* corruptArray, uint8_t *data,
        int32_t inputLen, uint8_t *code, int32_t outputLen) {
    int32_t failureNode = 0;
    int32_t symbolSize = inputLen / _chunk_num_per_node;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureNode = i;
    }

    switch(failureNode)
	{
    	case 0:
		{
			memcpy(code, data, 4*symbolSize);
			break;
		}
    	case 1:
		{
			memcpy(code, data, 2*symbolSize);
			memcpy(&code[2*symbolSize], &data[6*symbolSize], 2*symbolSize);
			break;
		}
    	case 2:
		{
			memcpy(code, data, symbolSize);
			memcpy(&code[symbolSize], &data[3*symbolSize], 2*symbolSize);
			memcpy(&code[3*symbolSize], &data[7*symbolSize], symbolSize);
			break;
		}
    	case 3:
		{
			if(nodeId == 5)
			{
				memcpy(code, &data[symbolSize], symbolSize);
				memcpy(&code[symbolSize], &data[3*symbolSize], symbolSize);
				memcpy(&code[2*symbolSize], &data[5*symbolSize], symbolSize);
				memcpy(&code[3*symbolSize], &data[7*symbolSize], symbolSize);
			}
			else
			{
				memcpy(code, data, symbolSize);
				memcpy(&code[symbolSize], &data[2*symbolSize], symbolSize);
				memcpy(&code[2*symbolSize], &data[4*symbolSize], symbolSize);
				memcpy(&code[3*symbolSize], &data[6*symbolSize], symbolSize);
			}
			break;
		}
    	case 4:
		{
			memcpy(code, &data[4*symbolSize], 4*symbolSize);
			break;
		}
    	case 5:
		{
			if(nodeId == 0)
				memcpy(code, data, 4*symbolSize);
			else if(nodeId == 4)
				memcpy(code, &data[4*symbolSize], 4*symbolSize);
			else
			{
			    uint8_t *outbuf[4];
			    for (int32_t i = 0; i < 4; i++) {
			        outbuf[i] = code+i%4*symbolSize*sizeof(uint8_t);
			    }
			    uint8_t* inbuf[8];
			    for (int32_t i = 0; i < 8; i++) {
			        inbuf[i] = data+i%8*symbolSize*sizeof(uint8_t);
			    }
			    uint8_t tmp_gftbl[32*32*3];
			    uint8_t a[96] = {1,0,0,0,1,0,0,0,0,1,0,0,0,1,0,0,0,0,1,0,0,0,1,0,0,0,0,1,0,0,0,1,
			    				0,0,0,1,0,1,0,1,0,0,1,0,1,0,1,0,0,1,0,0,0,0,0,1,1,0,0,0,0,0,1,0,
			    				0,0,0,1,1,0,0,1,0,0,1,0,1,1,1,0,0,1,0,0,0,0,1,1,1,0,0,0,0,0,0,1};
			    ec_init_tables(8, 4, &a[(nodeId-1)*32], tmp_gftbl);
			    ec_encode_data(symbolSize, 8, 4, tmp_gftbl, inbuf, outbuf);
			}
			break;
		}
    	default:
    		return false;
	}

    return true;
}

bool BUTTERFLY64::groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput, int32_t inputSize,
        int32_t *inputLen, uint8_t **groupoutput, int32_t outputSize, int32_t outputLen) {
	//cout<<"groupEncode Error, should not be here."<<endl;
	return true;
}

bool BUTTERFLY64::decode(int32_t *corruptArray, uint8_t **targetinput, int32_t *decodeArray,
        int32_t *inputLen, uint8_t **targetoutput, int32_t outputSize, int32_t outputLen) {
    int32_t failureNode = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureNode = i;
    }

	int32_t symbolSize = outputLen / _chunk_num_per_node;
    uint8_t *outbuf[_chunk_num_per_node];
    for (int32_t i = 0; i < _chunk_num_per_node; i++) {
        outbuf[i] = targetoutput[i/_chunk_num_per_node]+i%_chunk_num_per_node*symbolSize*sizeof(uint8_t);
    }
    uint8_t* inbuf[20];
    for (int32_t i = 0; i < 20; i++) {
        inbuf[i] = targetinput[i/4]+i%4*symbolSize*sizeof(uint8_t);
    }

    uint8_t tmp_gftbl[32*20*8];

    switch(failureNode)
	{
    	case 0:
		{
			uint8_t a[160]= {1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,
							0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,
							0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,
							0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,
							1,0,0,0,1,0,1,0,1,0,0,1,0,0,0,0,0,0,0,1,
							0,1,0,0,0,1,0,1,0,1,1,1,0,0,0,0,0,0,1,0,
							0,0,1,0,1,0,0,0,1,1,0,0,0,0,0,0,0,1,0,0,
							0,0,0,1,0,1,0,0,1,0,0,0,0,0,0,0,1,0,0,0};
			ec_init_tables(20, 8, a, tmp_gftbl);
			break;
		}
    	case 1:
		{
			uint8_t a[160]= {1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,
							0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,
							0,0,1,0,1,0,0,0,1,1,0,0,0,0,0,0,0,1,0,0,
							0,0,0,1,0,1,0,0,1,0,0,0,0,0,0,0,1,0,0,0,
							0,0,0,0,0,0,1,0,0,0,0,1,1,0,0,0,0,0,0,1,
							0,0,0,0,0,0,0,1,0,0,1,1,0,1,0,0,0,0,1,0,
							0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,
							0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0};
			ec_init_tables(20, 8, a, tmp_gftbl);
			break;
		}
    	case 2:
		{
			uint8_t a[160]= {1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,
							0,0,0,1,0,1,0,0,1,0,0,0,0,0,0,0,1,0,0,0,
							1,0,1,0,0,0,0,0,0,1,0,0,1,0,0,0,0,1,0,0,
							0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,
							0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,
							0,0,0,1,0,0,0,0,0,0,1,0,0,1,0,1,0,0,1,0,
							0,0,0,0,0,0,1,0,0,0,0,1,1,0,0,0,0,0,0,1,
							0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0};
			ec_init_tables(20, 8, a, tmp_gftbl);
			break;
		}
    	case 3:
		{
			uint8_t a[160]= {1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,
							1,0,0,1,1,1,0,0,0,0,0,0,1,0,0,0,1,0,0,0,
							0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,
							1,0,1,0,0,0,0,0,0,1,0,0,1,0,0,0,0,1,0,0,
							0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,
							0,0,1,1,0,0,1,0,0,0,0,0,0,1,1,1,0,0,1,0,
							0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,
							0,0,0,0,0,0,1,0,0,0,0,1,1,0,0,0,0,0,0,1};
			ec_init_tables(20, 8, a, tmp_gftbl);
			break;
		}
    	case 4:
		{
			uint8_t a[160]= {0,0,0,0,1,0,0,0,0,0,1,0,0,0,0,1,0,0,0,1,
							0,0,0,0,0,1,0,0,0,0,0,1,0,0,1,1,0,0,1,0,
							0,0,0,0,0,0,1,0,1,0,1,0,1,1,1,0,0,1,0,0,
							0,0,0,0,0,0,0,1,0,1,0,1,1,0 ,0,1,1,0,0,0,
							1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,
							0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,0,
							0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,
							0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0};
			ec_init_tables(20, 8, a, tmp_gftbl);
			break;
		}
    	case 5:
		{
			uint8_t a[160]= {0,0,0,0,0,0,0,1,0,0,1,0,0,0,0,1,0,0,0,1,
							0,0,0,0,0,0,1,0,0,0,0,1,0,0,1,1,0,0,1,0,
							0,0,0,0,0,1,0,0,1,0,1,0,1,1,1,0,0,1,0,0,
							0,0,0,0,1,0,0,0,0,1,0,1,1,0,0,1,1,0,0,0,
							0,0,0,1,0,0,0,1,1,0,0,0,1,0,0,0,0,0,0,0,
							0,0,1,0,0,0,1,0,0,1,0,0,0,1,0,0,0,0,0,0,
							0,1,0,0,0,1,0,0,0,0,1,0,0,0,1,0,0,0,0,0,
							1,0,0,0,1,0,0,0,0,0,0,1,0,0,0,1,0,0,0,0};
			ec_init_tables(20, 8, a, tmp_gftbl);
			break;
		}
    	default:
    		return false;
	}
    ec_encode_data(symbolSize, 20, 8, tmp_gftbl, inbuf, outbuf);
   
    return true;
}


void BUTTERFLY64::show_matrix(uint8_t* mat, int32_t row_num, int32_t column_num){
    for (int32_t i = 0; i < row_num; i++) {
        for (int32_t j = 0; j < column_num; j++) {
            printf("%4d", mat[i*column_num+j]);
        }
        printf("\n");
    }
    printf("\n");
}

bool BUTTERFLY64::generate_encoding_matrix() {

	for(int32_t i=0; i<_sys_chunk_num; i++) {
		_enc_matrix[i*_sys_chunk_num+i]=1;
	}
	uint8_t temp=0;
	for(int32_t i=_sys_chunk_num; i<_sys_chunk_num+8; i++) {
		for(int32_t j=0; j<_k; j++) {
			if(i % 8 == temp){
				_enc_matrix[i*_sys_chunk_num+8*j+temp]=1;
			}
		}
		temp++;
	}
	uint8_t a[32*8]={0,0,0,0,0,0,0,1,0,0,0,1,0,0,0,0,0,1,0,0,0,0,0,0,1,0,0,0,0,0,0,0,
					0,0,0,0,0,0,1,0,0,0,1,0,0,0,0,0,1,0,0,0,0,0,0,0,1,1,0,0,0,0,0,0,
					0,0,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,1,0,1,0,0,0,0,0,1,1,1,0,0,0,0,
					0,0,0,0,1,0,0,0,1,0,0,0,0,0,0,0,1,0,1,0,0,0,0,0,1,0,0,1,0,0,0,0,
					0,0,0,1,0,0,0,0,0,0,0,1,0,0,0,1,0,0,0,1,0,1,0,1,0,0,0,1,1,0,0,1,
					0,0,1,0,0,0,0,0,0,0,1,0,0,0,1,0,0,0,1,0,1,0,1,0,0,0,1,0,1,1,1,0,
					0,1,0,0,0,0,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,1,0,1,0,0,0,0,1,1,
					1,0,0,0,0,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,0,0,1,0,1,0,0,0,0,0,0,1};
	memcpy(&_enc_matrix[32*40], a, 32*8*sizeof(uint8_t));
	//show_matrix(_enc_matrix,_total_chunk_num,_sys_chunk_num);
    return true;
}
