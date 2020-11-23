#include "drc953.hh"

#include <iostream>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>

using namespace std;

DRC953::DRC953() {
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

bool DRC953::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
    _m = m;
    _r = r;
    _nr = nr;
    _k = k;
    _n = k + m;
    _chunk_num_per_node = 2;
    _sys_chunk_num = _k * _chunk_num_per_node;
    _enc_chunk_num = _m * _chunk_num_per_node;
    _total_chunk_num = _sys_chunk_num + _enc_chunk_num;
    _enc_matrix=(uint8_t*)calloc(_total_chunk_num*_sys_chunk_num, sizeof(uint8_t));
    generate_encoding_matrix();
    return true;
}

bool DRC953::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
    int32_t symbolSize = dataLen / _chunk_num_per_node;
    uint8_t *databuf[_k*_chunk_num_per_node], *codebuf[_m*_chunk_num_per_node];
    uint8_t tmp_gftbl[32*_k*_chunk_num_per_node*_m*_chunk_num_per_node];
    for (int32_t i = 0; i < _k*_chunk_num_per_node; i++)
        databuf[i] = data[i/_chunk_num_per_node]+i%_chunk_num_per_node*symbolSize*sizeof(uint8_t);
    for (int32_t i = 0; i < _m*_chunk_num_per_node; i++)
        codebuf[i] = code[i/_chunk_num_per_node]+i%_chunk_num_per_node*symbolSize*sizeof(uint8_t);

    ec_init_tables(_k*_chunk_num_per_node, _m*_chunk_num_per_node, &_enc_matrix[_k*_chunk_num_per_node*_k*_chunk_num_per_node], tmp_gftbl);
    ec_encode_data(symbolSize, _k*_chunk_num_per_node, _m*_chunk_num_per_node , tmp_gftbl, databuf, codebuf);

    return true;
}

bool DRC953::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
        int32_t dataLen, int32_t *nodeLenArray, int32_t *groupLenArray) {
    int32_t corruptNum = 0,failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i]) {
            dataArray[i] = -1;
            corruptNum++;
            failureGroupNum = i/_nr;
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
        if(i/3 == failureGroupNum)
        	nodeLenArray[i] = dataLen;
        else
        	nodeLenArray[i] = dataLen/_chunk_num_per_node;
    }
    for(int32_t i=0; i<_r; i++) {
        groupLenArray[i] = dataLen/_chunk_num_per_node;
    }

    return true;
}

bool DRC953::nodeEncode(int32_t nodeId, int32_t* corruptArray, uint8_t *data,
        int32_t inputLen, uint8_t *code, int32_t outputLen) {
    int32_t failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureGroupNum = i/_nr;
    }

	if(failureGroupNum == 0 && (nodeId/3 == 2))
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else if(failureGroupNum == 1 && (nodeId/3 == 2))
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else if(failureGroupNum == 2 && (nodeId/3 == 1))
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else
		memcpy(code, data, outputLen);

    return true;
}

bool DRC953::groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput, int32_t inputSize,
        int32_t *inputLen, uint8_t **groupoutput, int32_t outputSize, int32_t outputLen) {
    int32_t failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
        {
        	failureGroupNum = i/_nr;
        }
    }
	uint8_t tbls[32*6];
	uint8_t* databuf[3];
	uint8_t* codebuf[1];
	databuf[0] = groupinput[0];
	databuf[1] = groupinput[1];
	databuf[2] = groupinput[2];
	codebuf[0] = groupoutput[0];

	uint8_t a[18]= {1,1,1,1,232,80,1,1,1,3,184,186,185,122,2,10,40,244};

	if(failureGroupNum == 0)
	{
		if(groupId == 1)
			ec_init_tables(3,1,a,tbls);
		else
			ec_init_tables(3,1,&a[3],tbls);
	}
	else if(failureGroupNum == 1)
	{
		if(groupId == 0)
			ec_init_tables(3,1,&a[6],tbls);
		else
			ec_init_tables(3,1,&a[9],tbls);
	}
	else
	{
		if(groupId == 0)
			ec_init_tables(3,1,&a[12],tbls);
		else
			ec_init_tables(3,1,&a[15],tbls);
	}

	ec_encode_data(outputLen,3,1,tbls,databuf,codebuf);

	return true;
}

bool DRC953::decode(int32_t *corruptArray, uint8_t **targetinput, int32_t *decodeArray,
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
    uint8_t* inbuf[6];
    if(failureNode/3 == 0)
    {
    	inbuf[0] = targetinput[0];
    	inbuf[1] = targetinput[0]+symbolSize*sizeof(uint8_t);
    	inbuf[2] = targetinput[1];
    	inbuf[3] = targetinput[1]+symbolSize*sizeof(uint8_t);
    	inbuf[4] = targetinput[2];
    	inbuf[5] = targetinput[3];
    }
    else if(failureNode/3 == 1)
    {
    	inbuf[0] = targetinput[0];
    	inbuf[1] = targetinput[1];
    	inbuf[2] = targetinput[1]+symbolSize*sizeof(uint8_t);
    	inbuf[3] = targetinput[2];
    	inbuf[4] = targetinput[2]+symbolSize*sizeof(uint8_t);
    	inbuf[5] = targetinput[3];
    }
    else
    {
    	inbuf[0] = targetinput[0];
    	inbuf[1] = targetinput[1];
    	inbuf[2] = targetinput[2];
    	inbuf[3] = targetinput[2]+symbolSize*sizeof(uint8_t);
    	inbuf[4] = targetinput[3];
    	inbuf[5] = targetinput[3]+symbolSize*sizeof(uint8_t);
    }

    uint8_t tmp_gftbl[32*12];

    switch(failureNode)
	{
    	case 0:
		{
		    uint8_t a[12]= {1,0,1,0,1,0,0,229,0,200,0,100};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 1:
		{
		    uint8_t a[12]= {1,0,1,0,1,0,0,177,0,12,0,6};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 2:
		{
		    uint8_t a[12]= {1,0,1,0,1,0,0,210,0,61,0,142};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 3:
		{
		    uint8_t a[12]= {1,1,0,1,0,0,0,0,4,0,75,75};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 4:
		{
		    uint8_t a[12]= {1,1,0,1,0,0,0,0,71,0,219,219};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 5:
		{
		    uint8_t a[12]= {1,1,0,1,0,0,0,0,30,0,120,1};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 6:
		{
		    uint8_t a[12]= {1,0,232,0,80,0,0,1,0,104,0,157};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 7:
		{
		    uint8_t a[12]= {250,0,250,0,119,0,0,82,0,82,0,150};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	case 8:
		{
		    uint8_t a[12]= {56,0,56,0,121,0,0,9,0,9,0,15};
		    ec_init_tables(6, 2, a, tmp_gftbl);
			break;
		}
    	default:
    		return false;
	}
    ec_encode_data(symbolSize, 6, 2, tmp_gftbl, inbuf, outbuf);

    return true;
}


void DRC953::show_matrix(uint8_t* mat, int32_t row_num, int32_t column_num){
    for (int32_t i = 0; i < row_num; i++) {
        for (int32_t j = 0; j < column_num; j++) {
            printf("%4d", mat[i*column_num+j]);
        }
        printf("\n");
    }
    printf("\n");
}

bool DRC953::generate_encoding_matrix() {

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
			temp = gf_mul((uint8_t)((i-_sys_chunk_num)/2+1),temp);
		}
	}
	//show_matrix(_enc_matrix,_total_chunk_num,_sys_chunk_num);
    return true;
}
