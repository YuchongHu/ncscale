#include "drc643.hh"

#include <iostream>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>

using namespace std;

DRC643::DRC643() {
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

bool DRC643::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
    _m = m;
    _r = r;
    _nr = nr;
    _k = k;
    _n = k + m;
    _chunk_num_per_node = m;
    _sys_chunk_num = _k * _chunk_num_per_node;
    _enc_chunk_num = _m * _chunk_num_per_node;
    _total_chunk_num = _sys_chunk_num + _enc_chunk_num;
    _enc_matrix=(uint8_t*)calloc(_total_chunk_num*_sys_chunk_num, sizeof(uint8_t));
    generate_encoding_matrix();
    return true;
}

bool DRC643::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
    int32_t symbolSize = dataLen / _chunk_num_per_node;
    /* cout << "symbolSize in construct(): " << symbolSize << endl; */
    uint8_t *databuf[_k*_m], *codebuf[_m*_m];
    uint8_t tmp_gftbl[32*_k*_m*_m*_m];
    for (int32_t i = 0; i < _k*_m; i++)
        databuf[i] = data[i/_m]+i%_m*symbolSize*sizeof(uint8_t);
    for (int32_t i = 0; i < _m*_m; i++)
        codebuf[i] = code[i/_m]+i%_m*symbolSize*sizeof(uint8_t);

    ec_init_tables(_k*_m, _m*_m, &_enc_matrix[_k*_m*_k*_m], tmp_gftbl);
    ec_encode_data(symbolSize, _k*_m, _m*_m , tmp_gftbl, databuf, codebuf);

    return true;
}

bool DRC643::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
        int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray) {
    int32_t corruptNum = 0,failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i]) {
            dataArray[i] = -1;
            corruptNum++;
            failureGroupNum = i/_m;
        }
        else dataArray[i] = i / _nr;
    }

    if (1 != corruptNum) return false;

    for (int32_t i = 0; i < _r; i++) {
        if (failureGroupNum == i)
        	groupArray[i] = 0;
        else
        	groupArray[i] = 1;
    }


    for(int32_t i=0; i<(_k+_m); i++) {
		if(failureGroupNum==0 && (i == 3 || i == 5))
			nodeLenArray[i] = dataLen/2;
		else if(failureGroupNum==1 && (i == 1 || i == 5))
			nodeLenArray[i] = dataLen/2;
		else if(failureGroupNum==2 && (i == 1 || i == 3))
			nodeLenArray[i] = dataLen/2;
		else
			nodeLenArray[i] = dataLen;

    }
    for(int32_t i=0; i<_r; i++) {
        groupLenArray[i] = dataLen;
    }

    return true;
}

bool DRC643::nodeEncode(int32_t nodeId, int32_t* corruptArray, uint8_t *data,
        int32_t inputLen, uint8_t *code, int32_t outputLen) {
    int32_t failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureGroupNum = i/_m;
    }

	if(failureGroupNum == 0 && nodeId == 3)
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else if((failureGroupNum == 1 || failureGroupNum == 2) && nodeId == 1)
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else
		memcpy(code, data, outputLen);
    return true;
}

bool DRC643::groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput, int32_t inputSize,
        int32_t *inputLen, uint8_t **groupoutput, int32_t outputSize, int32_t outputLen) {
    int32_t failureGroupNum = 0,failureNode = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
        {
        	failureGroupNum = i/_m;
        	failureNode = i;
        }
    }

	int32_t symbolSize = outputLen / _chunk_num_per_node;
	uint8_t tbls[32*6];

	uint8_t* databuf[3];
	uint8_t* codebuf[2];
	databuf[0] = groupinput[0];
	databuf[1] = groupinput[0]+symbolSize*sizeof(uint8_t);
	databuf[2] = groupinput[1];
	codebuf[0] = groupoutput[0];
	codebuf[1] = groupoutput[0]+symbolSize*sizeof(uint8_t);

	if(failureGroupNum == 0)
	{
		if(groupId == 2)
		{
			uint8_t a[6]= {8,0,1,0,1,0};
			ec_init_tables(3,2,a,tbls);
		}
		else
		{
			uint8_t a[6]= {12,0,0,0,1,1};
			ec_init_tables(3,2,a,tbls);
		}
	}
	else if(failureGroupNum == 1)///need to modify
	{
		if(groupId == 0)//inner_group's mul_coff
		{
			uint8_t a[6]= {3,0,0,0,1,1};
			ec_init_tables(3,2,a,tbls);
		}
		else
		{
			uint8_t a[6]= {2,0,1,0,1,0};
			ec_init_tables(3,2,a,tbls);
		}
	}
	else
	{
		if(failureNode == 4)
		{
			if(groupId == 0)
			{
				uint8_t a[6]= {143,0,0,0,172,70};
				ec_init_tables(3,2,a,tbls);
			}
			else
			{
				uint8_t a[6]= {3,0,5,0,143,0};
				ec_init_tables(3,2,a,tbls);
			}
		}
		else
		{
			if(groupId == 0)
			{
				uint8_t a[6]= {3,0,0,0,9,10};
				ec_init_tables(3,2,a,tbls);
			}
			else
			{
				uint8_t a[6]= {6,0,10,0,12,0};
				ec_init_tables(3,2,a,tbls);
			}
		}
	}
	ec_encode_data(symbolSize,3,2,tbls,databuf,codebuf);

	return true;
}

bool DRC643::decode(int32_t *corruptArray, uint8_t **targetinput, int32_t *decodeArray,
        int32_t *inputLen, uint8_t **targetoutput, int32_t outputSize, int32_t outputLen) {
    int32_t failureNode = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureNode = i;
    }

	int32_t symbolSize = outputLen / _chunk_num_per_node;
    uint8_t *outbuf[_m];
    for (int32_t i = 0; i < _m; i++) {
        outbuf[i] = targetoutput[i/_m]+i%_m*symbolSize*sizeof(uint8_t);
    }
    uint8_t* inbuf[6];
    for (int32_t i = 0; i < 6; i++) {
        inbuf[i] = targetinput[i/_m]+i%_m*symbolSize*sizeof(uint8_t);
    }

    uint8_t tmp_gftbl[32*6*2];

    switch(failureNode)
	{
    	case 0:
		{
			uint8_t a[12]= {187,0,157,0,157,0,0,1,0,1,0,1};
			ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 1:
		{
			uint8_t a[12]= {123,0,221,0,221,0,0,1,0,1,0,1};
			ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 2:
		{
			uint8_t a[12]= {122,0,3,0,122,0,0,1,0,1,0,1};
			ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 3:
		{
			uint8_t a[12]= {221,0,244,0,221,0,0,1,0,1,0,1};
			ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 4:
		{
			uint8_t a[12]= {1,0,1,0,142,0,0,1,0,1,0,173};
			ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 5:
		{
			uint8_t a[12]= {1,0,1,0,2,0,0,1,0,1,0,8};
			ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	default:
    		return false;
	}
    ec_encode_data(symbolSize, 6, 2, tmp_gftbl, inbuf, outbuf);
    
    return true;
}


void DRC643::show_matrix(uint8_t* mat, int32_t row_num, int32_t column_num){
    for (int32_t i = 0; i < row_num; i++) {
        for (int32_t j = 0; j < column_num; j++) {
            printf("%4d", mat[i*column_num+j]);
        }
        printf("\n");
    }
    printf("\n");
}

bool DRC643::generate_encoding_matrix() {

	for(int32_t i=0; i<_sys_chunk_num; i++) {
		_enc_matrix[i*_sys_chunk_num+i]=1;
	}
	for(int32_t i=_sys_chunk_num; i<_total_chunk_num; i++) {
		uint8_t temp=1;
		for(int32_t j=0; j<_k; j++) {
			if(i % 2 == 0){
				_enc_matrix[i*_sys_chunk_num+2*j]=temp;
			}else{
				_enc_matrix[i*_sys_chunk_num+2*j+1]=temp;
			}
			temp = gf_mul((uint8_t)((i-_sys_chunk_num)/_m+1),temp);
		}
	}
	//show_matrix(_enc_matrix,_total_chunk_num,_sys_chunk_num);
    return true;
}
