#include "drc864.hh"

#include <iostream>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>

using namespace std;

DRC864::DRC864() {
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

bool DRC864::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
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

bool DRC864::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
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

bool DRC864::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
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
        if(i/2 != failureGroupNum && i%2 != 0)
        	nodeLenArray[i] = dataLen/_m;
        else
        	nodeLenArray[i] = dataLen;
    }
    for(int32_t i=0; i<_r; i++) {
        groupLenArray[i] = dataLen;
    }

    return true;
}

bool DRC864::nodeEncode(int32_t nodeId, int32_t* corruptArray, uint8_t *data,
        int32_t inputLen, uint8_t *code, int32_t outputLen) {
	int32_t failureGroupNum = 0;
	for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureGroupNum = i/_nr;
    }
	if(failureGroupNum == 0)
	{
		uint8_t tmp[6] = {1,8,1,32,171,112};
		uint8_t tbls[32*2];

		if(nodeId == 3)
			ec_init_tables(2,1,tmp,tbls);
		else if(nodeId == 5)
			ec_init_tables(2,1,&tmp[2],tbls);
		else if(nodeId == 7)
			ec_init_tables(2,1,&tmp[4],tbls);
		else
			memcpy(code, data, outputLen);


		if(nodeId == 3|| nodeId == 5|| nodeId == 7)
		{
			uint8_t* databuf[2];
			uint8_t* codebuf[1];
			databuf[0] = data;
			databuf[1] = data+outputLen*sizeof(uint8_t);
			codebuf[0] = code;
			ec_encode_data(outputLen,2,1,tbls,databuf,codebuf);
		}
	}
	else if(failureGroupNum == 1)
	{
		uint8_t tmp[6] = {1,2,1,32,72,57};
		uint8_t tbls[32*2];

		if(nodeId == 1)
			ec_init_tables(2,1,tmp,tbls);
		else if(nodeId == 5)
			ec_init_tables(2,1,&tmp[2],tbls);
		else if(nodeId == 7)
			ec_init_tables(2,1,&tmp[4],tbls);
		else
			memcpy(code, data, outputLen);


		if(nodeId == 1|| nodeId == 5|| nodeId == 7)
		{
			uint8_t* databuf[2];
			uint8_t* codebuf[1];
			databuf[0] = data;
			databuf[1] = data+outputLen*sizeof(uint8_t);
			codebuf[0] = code;
			ec_encode_data(outputLen,2,1,tbls,databuf,codebuf);
		}
	}
	else if(failureGroupNum == 2)
	{
		uint8_t tmp[6] = {1,2,1,8,61,221};
		uint8_t tbls[32*2];

		if(nodeId == 1)
			ec_init_tables(2,1,tmp,tbls);
		else if(nodeId == 3)
			ec_init_tables(2,1,&tmp[2],tbls);
		else if(nodeId == 7)
			ec_init_tables(2,1,&tmp[4],tbls);
		else
			memcpy(code, data, outputLen);


		if(nodeId == 1|| nodeId == 3|| nodeId == 7)
		{
			uint8_t* databuf[2];
			uint8_t* codebuf[1];
			databuf[0] = data;
			databuf[1] = data+outputLen*sizeof(uint8_t);
			codebuf[0] = code;
			ec_encode_data(outputLen,2,1,tbls,databuf,codebuf);
		}
	}
	else
	{
		uint8_t tmp[6] = {243,15,6,48,24,231};
		uint8_t tbls[32*2];

		if(nodeId == 1)
			ec_init_tables(2,1,tmp,tbls);
		else if(nodeId == 3)
			ec_init_tables(2,1,&tmp[2],tbls);
		else if(nodeId == 5)
			ec_init_tables(2,1,&tmp[4],tbls);
		else
			memcpy(code, data, outputLen);


		if(nodeId == 1|| nodeId == 3|| nodeId == 5)
		{
			uint8_t* databuf[2];
			uint8_t* codebuf[1];
			databuf[0] = data;
			databuf[1] = data+outputLen*sizeof(uint8_t);
			codebuf[0] = code;
			ec_encode_data(outputLen,2,1,tbls,databuf,codebuf);
		}
	}

    return true;
}

bool DRC864::groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput, int32_t inputSize,
        int32_t *inputLen, uint8_t **groupoutput, int32_t outputSize, int32_t outputLen) {
	int32_t failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
        {
        	failureGroupNum = i/_nr;
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
		uint8_t a[18]= {1,4,1,123,241,1,1,16,1,143,235,0,1,1,0,70,166,1};

		if(groupId == 1)
			ec_init_tables(3,2,a,tbls);
		else if(groupId == 2)
			ec_init_tables(3,2,&a[6],tbls);
		else
			ec_init_tables(3,2,&a[12],tbls);
		ec_encode_data(symbolSize,3,2,tbls,databuf,codebuf);
	}
	else if(failureGroupNum == 1)
	{
		uint8_t a[18]= {1,1,1,145,74,1,1,16,1,101,220,0,1,1,0,217,115,1};

		if(groupId == 0)
			ec_init_tables(3,2,a,tbls);
		else if(groupId == 2)
			ec_init_tables(3,2,&a[6],tbls);
		else
			ec_init_tables(3,2,&a[12],tbls);
		ec_encode_data(symbolSize,3,2,tbls,databuf,codebuf);
	}
	else if(failureGroupNum == 2)
	{
		uint8_t a[18]= {1,1,1,123,123,1,1,4,1,143,243,0,1,1,0,70,166,1};

		if(groupId == 0)
			ec_init_tables(3,2,a,tbls);
		else if(groupId == 1)
			ec_init_tables(3,2,&a[6],tbls);
		else
			ec_init_tables(3,2,&a[12],tbls);
		ec_encode_data(symbolSize,3,2,tbls,databuf,codebuf);
	}
	else
	{
		uint8_t a[18]= {1,1,0,84,206,1,3,8,1,7,28,1,9,224,1,5,64,0};

		if(groupId == 0)
			ec_init_tables(3,2,a,tbls);
		else if(groupId == 1)
			ec_init_tables(3,2,&a[6],tbls);
		else
			ec_init_tables(3,2,&a[12],tbls);
		ec_encode_data(symbolSize,3,2,tbls,databuf,codebuf);
	}
	return true;
}


bool DRC864::decode(int32_t *corruptArray, uint8_t **targetinput, int32_t *decodeArray,
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
    uint8_t* inbuf[8];
    for (int32_t i = 0; i < 8; i++) {
        inbuf[i] = targetinput[i/_chunk_num_per_node]+i%_chunk_num_per_node*symbolSize*sizeof(uint8_t);
    }
    uint8_t *tmpbuf[_chunk_num_per_node];
    for (int32_t i = 0; i < _chunk_num_per_node; i++) {
        tmpbuf[i] = targetoutput[i/_chunk_num_per_node]+i%_chunk_num_per_node*symbolSize*sizeof(uint8_t);
    }

    uint8_t tmp_gftbl[32*16];
    uint8_t gftbl[32*4];

    switch(failureNode)
	{
    	case 0:
		{
			uint8_t a[16]= {1,2,1,0,1,0,1,0,166,140,0,1,0,1,0,1};
			uint8_t b[4]= {1,1,237,214};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	case 1:
		{
			uint8_t a[16]= {1,1,1,0,1,0,1,0,237,214,0,1,0,1,0,1};
			uint8_t b[4]= {1,2,166,140};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	case 2:
		{
			uint8_t a[16]= {1,0,1,8,1,0,1,0,0,1,70,89,0,1,0,1};
			uint8_t b[4]= {1,4,172,102};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	case 3:
		{
			uint8_t a[16]= {1,0,1,4,1,0,1,0,0,1,172,102,0,1,0,1};
			uint8_t b[4]= {1,8,70,89};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	case 4:
		{
			uint8_t a[16]= {1,0,1,0,1,32,1,0,0,1,0,1,5,128,0,1};
			uint8_t b[4]= {1,16,140,203};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	case 5:
		{
			uint8_t a[16]= {1,0,1,0,1,16,1,0,0,1,0,1,140,203,0,1};
			uint8_t b[4]= {1,32,5,128};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	case 6:
		{
			uint8_t a[16]= {1,0,1,0,1,0,142,244,0,1,0,1,0,1,221,61};
			uint8_t b[4]= {143,245,137,243};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	case 7:
		{
			uint8_t a[16]= {1,0,1,0,1,0,143,245,0,1,0,1,0,1,137,243};
			uint8_t b[4]= {142,244,221,61};
			uint8_t c[4];
		    gf_invert_matrix(b,c,2);
		    ec_init_tables(2, 2, c, gftbl);
		    ec_init_tables(8, 2, a, tmp_gftbl);
			break;
		}
    	default:
    		return false;
	}
    ec_encode_data(symbolSize, 8, 2, tmp_gftbl, inbuf, tmpbuf);
    ec_encode_data(symbolSize, 2, 2, gftbl, tmpbuf, outbuf);

    return true;
}


void DRC864::show_matrix(uint8_t* mat, int32_t row_num, int32_t column_num){
    for (int32_t i = 0; i < row_num; i++) {
        for (int32_t j = 0; j < column_num; j++) {
            printf("%4d", mat[i*column_num+j]);
        }
        printf("\n");
    }
    printf("\n");
}

bool DRC864::generate_encoding_matrix() {

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
			temp = gf_mul((uint8_t)((i-_sys_chunk_num)+1),temp);
		}
	}
	//show_matrix(_enc_matrix,_total_chunk_num,_sys_chunk_num);
    return true;
}
