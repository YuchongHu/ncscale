#include "drc633.hh"

#include <iostream>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>

using namespace std;

DRC633::DRC633() {
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

bool DRC633::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
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

bool DRC633::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
    int32_t symbolSize = dataLen / _chunk_num_per_node;
    uint8_t *databuf[_k*_nr], *codebuf[_m*_nr];
    uint8_t tmp_gftbl[32*_k*_nr*_m*_nr];
    for (int32_t i = 0; i < _k*_nr; i++)
        databuf[i] = data[i/_nr]+i%_nr*symbolSize*sizeof(uint8_t);
    for (int32_t i = 0; i < _m*_nr; i++)
        codebuf[i] = code[i/_nr]+i%_nr*symbolSize*sizeof(uint8_t);

    ec_init_tables(_k*_nr, _m*_nr, &_enc_matrix[_k*_nr*_k*_nr], tmp_gftbl);
    ec_encode_data(symbolSize, _k*_nr, _m*_nr , tmp_gftbl, databuf, codebuf);
//cout<<(databuf[1][5]^databuf[3][5]+0)<<endl;cout<<(databuf[0][5]^databuf[2][5]^gf_mul(7,databuf[4][5])+0)<<endl;
    //cout<<(gf_inv(4)+0)<<endl;
/*

 uint8_t a[4]= {1,1,1,2};
uint8_t b[4];
gf_invert_matrix(a,b,2);
show_matrix(b,2,2);
*/
    //cout<<(1^70+0)<<endl;cout<<(3^gf_mul(70,2)+0)<<endl;
    //cout<<(gf_mul(244,1)^gf_mul(244,3)+0)<<endl;
    //cout<<(gf_mul(5,71)+0)<<endl;

    return true;
}

bool DRC633::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
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
		if(i/2 == failureGroupNum)
			nodeLenArray[i] = dataLen;
		else
			nodeLenArray[i] = dataLen/2;
    }
    for(int32_t i=0; i<_r; i++) {
        groupLenArray[i] = dataLen/_nr;
    }

    return true;
}

bool DRC633::nodeEncode(int32_t nodeId, int32_t* corruptArray, uint8_t *data,
        int32_t inputLen, uint8_t *code, int32_t outputLen) {
    int32_t failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureGroupNum = i/_nr;
    }

	if(failureGroupNum == 0 && (nodeId/2 == 2))
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else if(failureGroupNum == 1 && (nodeId/2 == 0))
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else if(failureGroupNum == 2 && (nodeId/2 == 1))
		memcpy(code, data+outputLen*sizeof(uint8_t), outputLen);
	else
		memcpy(code, data, outputLen);

    return true;
}

bool DRC633::groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput, int32_t inputSize,
        int32_t *inputLen, uint8_t **groupoutput, int32_t outputSize, int32_t outputLen) {
    int32_t failureGroupNum = 0,failureNode = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
        {
        	failureGroupNum = i/_nr;
        	failureNode = i;
        }
    }

	uint8_t tbls[32*4];
	uint8_t* databuf[2];
	uint8_t* codebuf[1];
	databuf[0] = groupinput[0];
	databuf[1] = groupinput[1];
	codebuf[0] = groupoutput[0];

	uint8_t a[16]= {1,1,5,4,1,1,2,3,167,245,3,142,71,143,2,244};
	if(failureGroupNum == 0)
	{
		if(groupId == 1)
			ec_init_tables(2,1,a,tbls);
		else
			ec_init_tables(2,1,&a[2],tbls);
	}
	else if(failureGroupNum == 1)///need to modify
	{
		if(groupId == 0)
			ec_init_tables(2,1,&a[4],tbls);
		else
			ec_init_tables(2,1,&a[6],tbls);
	}
	else
	{
		if(failureNode == 4)
		{
			if(groupId == 0)
				ec_init_tables(2,1,&a[8],tbls);
			else
				ec_init_tables(2,1,&a[10],tbls);
		}
		if(failureNode == 5)
		{
			if(groupId == 0)
				ec_init_tables(2,1,&a[12],tbls);
			else
				ec_init_tables(2,1,&a[14],tbls);
		}
	}

	ec_encode_data(outputLen,2,1,tbls,databuf,codebuf);

	return true;
}

bool DRC633::decode(int32_t *corruptArray, uint8_t **targetinput, int32_t *decodeArray,
        int32_t *inputLen, uint8_t **targetoutput, int32_t outputSize, int32_t outputLen) {
    int32_t failureNode = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureNode = i;
    }

	int32_t symbolSize = outputLen / _chunk_num_per_node;
    uint8_t *outbuf[_nr];
    for (int32_t i = 0; i < _nr; i++) {
        outbuf[i] = targetoutput[i/_nr]+i%_nr*symbolSize*sizeof(uint8_t);
    }
    uint8_t* inbuf[4];
    if(failureNode/2 == 0)
    {
    	inbuf[0] = targetinput[0];
    	inbuf[1] = targetinput[0]+symbolSize*sizeof(uint8_t);
    	inbuf[2] = targetinput[1];
    	inbuf[3] = targetinput[2];
    }
    else if(failureNode/2 == 1)
    {
    	inbuf[0] = targetinput[0];
    	inbuf[1] = targetinput[1];
    	inbuf[2] = targetinput[1]+symbolSize*sizeof(uint8_t);
    	inbuf[3] = targetinput[2];
    }
    else
    {
    	inbuf[0] = targetinput[0];
    	inbuf[1] = targetinput[1];
    	inbuf[2] = targetinput[2];
    	inbuf[3] = targetinput[2]+symbolSize*sizeof(uint8_t);
    }
    uint8_t tmp_gftbl[32*4*2];
    switch(failureNode)
	{
    	case 0:
		{
			uint8_t a[8]= {1,0,1,0,0,6,0,1};
			ec_init_tables(4, 2, a, tmp_gftbl);
			break;
		}
    	case 1:
		{
			uint8_t a[8]= {1,0,1,0,0,122,0,122};
			ec_init_tables(4, 2, a, tmp_gftbl);
			break;
		}
    	case 2:
		{
			uint8_t a[8]= {0,122,0,122,1,0,1,0};
			ec_init_tables(4, 2, a, tmp_gftbl);
			break;
		}
    	case 3:
		{
			uint8_t a[8]= {0,6,0,1,1,0,1,0};
			ec_init_tables(4, 2, a, tmp_gftbl);
			break;
		}
    	case 4:
		{
			uint8_t a[8]= {1,0,166,0,0,1,0,143};
			ec_init_tables(4, 2, a, tmp_gftbl);
			break;
		}
    	case 5:
		{
			uint8_t a[8]= {1,0,70,0,0,1,0,245};
			ec_init_tables(4, 2, a, tmp_gftbl);
			break;
		}
    	default:
    		return false;
	}

    ec_encode_data(symbolSize, 4, 2, tmp_gftbl, inbuf, outbuf);

    return true;
}


void DRC633::show_matrix(uint8_t* mat, int32_t row_num, int32_t column_num){
    for (int32_t i = 0; i < row_num; i++) {
        for (int32_t j = 0; j < column_num; j++) {
            printf("%4d", mat[i*column_num+j]);
        }
        printf("\n");
    }
    printf("\n");
}

bool DRC633::generate_encoding_matrix() {

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
