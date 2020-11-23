#include "drc963.hh"

#include <iostream>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <math.h>
#include <time.h>

using namespace std;

DRC963::DRC963() {
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

bool DRC963::initialize(int32_t k, int32_t m, int32_t r, int32_t nr) {
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

bool DRC963::construct(uint8_t **data, uint8_t **code, int32_t dataLen) {
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

bool DRC963::check(int32_t *corruptArray, int32_t *dataArray, int32_t *groupArray,
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
        if(i/3 != failureGroupNum && i%3 != 0)
        	nodeLenArray[i] = dataLen/_m;
        else
        	nodeLenArray[i] = dataLen;
    }
    for(int32_t i=0; i<_r; i++) {
        groupLenArray[i] = dataLen;
    }

    return true;
}

bool DRC963::nodeEncode(int32_t nodeId, int32_t* corruptArray, uint8_t *data,
        int32_t inputLen, uint8_t *code, int32_t outputLen) {
	int32_t failureGroupNum = 0;
	for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
            failureGroupNum = i/_nr;
    }
	if(failureGroupNum == 0)
	{
		uint8_t tmp[12] = {1,16,17,1,32,51,141,237,72,112,141,112};
		uint8_t tbls[32*3];

		if(nodeId == 4)
			ec_init_tables(3,1,tmp,tbls);
		else if(nodeId == 5)
			ec_init_tables(3,1,&tmp[3],tbls);
		else if(nodeId == 7)
			ec_init_tables(3,1,&tmp[6],tbls);
		else if(nodeId == 8)
			ec_init_tables(3,1,&tmp[9],tbls);
		else
			memcpy(code, data, outputLen);


		if(nodeId == 4|| nodeId == 5|| nodeId == 7|| nodeId == 8)
		{
			uint8_t* databuf[3];
			uint8_t* codebuf[1];
			databuf[0] = data;
			databuf[1] = data+outputLen*sizeof(uint8_t);
			databuf[2] = data+2*outputLen*sizeof(uint8_t);
			codebuf[0] = code;
			ec_encode_data(outputLen,3,1,tbls,databuf,codebuf);
		}
	}
	else if(failureGroupNum == 1)
	{
		uint8_t tmp[12] = {1,2,3,1,4,5,224,58,122,192,224,192};
		uint8_t tbls[32*3];

		if(nodeId == 1)
			ec_init_tables(3,1,tmp,tbls);
		else if(nodeId == 2)
			ec_init_tables(3,1,&tmp[3],tbls);
		else if(nodeId == 7)
			ec_init_tables(3,1,&tmp[6],tbls);
		else if(nodeId == 8)
			ec_init_tables(3,1,&tmp[9],tbls);
		else
			memcpy(code, data, outputLen);


		if(nodeId == 1|| nodeId == 2|| nodeId == 7|| nodeId == 8)
		{
			uint8_t* databuf[3];
			uint8_t* codebuf[1];
			databuf[0] = data;
			databuf[1] = data+outputLen*sizeof(uint8_t);
			databuf[2] = data+2*outputLen*sizeof(uint8_t);
			codebuf[0] = code;
			ec_encode_data(outputLen,3,1,tbls,databuf,codebuf);
		}
	}
	else
	{
		uint8_t tmp[12] = {79,207,74,131,116,2,56,23,159,25,198,222};
		uint8_t tbls[32*3];

		if(nodeId == 1)
			ec_init_tables(3,1,tmp,tbls);
		else if(nodeId == 2)
			ec_init_tables(3,1,&tmp[3],tbls);
		else if(nodeId == 4)
			ec_init_tables(3,1,&tmp[6],tbls);
		else if(nodeId == 5)
			ec_init_tables(3,1,&tmp[9],tbls);
		else
			memcpy(code, data, outputLen);


		if(nodeId == 1|| nodeId == 2|| nodeId == 4|| nodeId == 5)
		{
			uint8_t* databuf[3];
			uint8_t* codebuf[1];
			databuf[0] = data;
			databuf[1] = data+outputLen*sizeof(uint8_t);
			databuf[2] = data+2*outputLen*sizeof(uint8_t);
			codebuf[0] = code;
			ec_encode_data(outputLen,3,1,tbls,databuf,codebuf);
		}
	}


    return true;
}

bool DRC963::groupEncode(int32_t groupId, int32_t *corruptArray, uint8_t **groupinput, int32_t inputSize,
        int32_t *inputLen, uint8_t **groupoutput, int32_t outputSize, int32_t outputLen) {
    int32_t failureGroupNum = 0;
    for (int32_t i = 0; i < _n; i++) {
        if (1 == corruptArray[i])
        {
        	failureGroupNum = i/_nr;
        }
    }
	int32_t symbolSize = outputLen / _chunk_num_per_node;
	uint8_t tbls[32*15];

	uint8_t* databuf[5];
	uint8_t* codebuf[3];
	databuf[0] = groupinput[0];
	databuf[1] = groupinput[0]+symbolSize*sizeof(uint8_t);
	databuf[2] = groupinput[0]+2*symbolSize*sizeof(uint8_t);
	databuf[3] = groupinput[1];
	databuf[4] = groupinput[2];
	codebuf[0] = groupoutput[0];
	codebuf[1] = groupoutput[0]+symbolSize*sizeof(uint8_t);
	codebuf[2] = groupoutput[0]+2*symbolSize*sizeof(uint8_t);
	if(failureGroupNum == 0)
	{
		if(groupId == 1)
		{
			uint8_t a[15]= {1,8,15,1,1,70,170,134,1,0,186,2,184,0,1};
			ec_init_tables(5,3,a,tbls);
		}
		else
		{
			uint8_t a[15]= {1,1,1,0,0,166,104,245,1,0,122,167,122,0,1};
			ec_init_tables(5,3,a,tbls);
		}
		ec_encode_data(symbolSize,5,3,tbls,databuf,codebuf);
	}
	else if(failureGroupNum == 1)
	{
		if(groupId == 0)
		{
			uint8_t a[15]= {1,1,1,1,1,70,82,143,1,0,186,71,186,0,1};
			ec_init_tables(5,3,a,tbls);
		}
		else
		{
			uint8_t a[15]= {1,1,1,0,0,166,104,245,1,0,122,167,122,0,1};
			ec_init_tables(5,3,a,tbls);
		}
		ec_encode_data(symbolSize,5,3,tbls,databuf,codebuf);
	}
	else if(failureGroupNum == 2)
	{
		if(groupId == 0)
		{
			uint8_t a[15]= {1,1,1,0,0,37,239,126,1,0,52,206,70,0,1};
			ec_init_tables(5,3,a,tbls);
		}
		else
		{
			uint8_t a[15]= {28,80,90,1,1,253,36,48,0,1,17,71,218,1,0};
			ec_init_tables(5,3,a,tbls);
		}
		ec_encode_data(symbolSize,5,3,tbls,databuf,codebuf);
	}
	return true;
}

bool DRC963::decode(int32_t *corruptArray, uint8_t **targetinput, int32_t *decodeArray,
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
    uint8_t* inbuf[12];
    for (int32_t i = 0; i < 12; i++) {
        inbuf[i] = targetinput[i/_m]+i%_m*symbolSize*sizeof(uint8_t);
    }
    uint8_t* tmpbuf[3];
    for (int32_t i = 0; i < 3; i++) {
    	tmpbuf[i]=(uint8_t*)calloc(outputLen, sizeof(uint8_t));
    }

    uint8_t tmp_gftbl[32*12*3];
    uint8_t gftbl[32*3*3];

    switch(failureNode)
	{
    	case 0:
		{
			uint8_t a[36]= {1,2,3,1,4,5,1,0,0,1,0,0,168,174,175,158,38,207,0,1,0,0,1,0,55,79,89,132,70,174,0,0,1,0,0,1};
			uint8_t b[9] = {1,1,1,43,133,189,10,42,10};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 1:
		{
			uint8_t a[36]= {1,1,1,1,4,5,1,0,0,1,0,0,43,133,189,158,38,207,0,1,0,0,1,0,10,42,10,132,70,174,0,0,1,0,0,1};
			uint8_t b[9] = {1,2,3,168,174,175,55,79,89};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 2:
		{
			uint8_t a[36]= {1,1,1,1,2,3,1,0,0,1,0,0,43,133,189,168,174,175,0,1,0,0,1,0,10,42,10,55,79,89,0,0,1,0,0,1};
			uint8_t b[9] = {1,4,5,158,38,207,132,70,174};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 3:
		{
			uint8_t a[36]= {1,0,0,1,16,17,1,32,51,1,0,0,0,1,0,20,108,102,84,145,47,0,1,0,0,0,1,19,77,62,120,114,224,0,0,1};
			uint8_t b[9] = {1,8,15,4,20,30,6,40,34};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 4:
		{
			uint8_t a[36]= {1,0,0,1,8,15,1,32,51,1,0,0,0,1,0,4,20,30,84,145,47,0,1,0,0,0,1,6,40,34,120,114,224,0,0,1};
			uint8_t b[9] = {1,16,17,20,108,102,19,77,62};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 5:
		{
			uint8_t a[36]= {1,0,0,1,8,15,1,16,17,1,0,0,0,1,0,4,20,30,20,108,102,0,1,0,0,0,1,6,40,34,19,77,62,0,0,1};
			uint8_t b[9] = {1,32,51,84,145,47,120,114,224};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 6:
		{
			uint8_t a[36]= {1,0,0,1,0,0,197,121,197,83,170,150,0,1,0,0,1,0,12,23,183,25,128,152,0,0,1,0,0,1,1,178,239,114,231,19};
			uint8_t b[9] = {151,210,82,48,120,81,71,155,186};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 7:
		{
			uint8_t a[36]= {1,0,0,1,0,0,151,210,82,83,170,150,0,1,0,0,1,0,48,120,81,25,128,152,0,0,1,0,0,1,71,155,186,114,231,19};
			uint8_t b[9] = {197,121,197,12,23,183,1,178,239};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	case 8:
		{
			uint8_t a[36]= {1,0,0,1,0,0,151,210,82,197,121,197,0,1,0,0,1,0,48,120,81,12,23,183,0,0,1,0,0,1,71,155,186,1,178,239};
			uint8_t b[9] = {83,170,150,25,128,152,114,231,19};
			uint8_t c[9];
		    gf_invert_matrix(b,c,3);
		    ec_init_tables(3, 3, c, gftbl);
		    ec_init_tables(12, 3, a, tmp_gftbl);
			break;
		}
    	default:
    		return false;
	}
    ec_encode_data(symbolSize, 12, 3, tmp_gftbl, inbuf, tmpbuf);
    ec_encode_data(symbolSize, 3, 3, gftbl, tmpbuf, outbuf);
    return true;
}


void DRC963::show_matrix(uint8_t* mat, int32_t row_num, int32_t column_num){
    for (int32_t i = 0; i < row_num; i++) {
        for (int32_t j = 0; j < column_num; j++) {
            printf("%4d", mat[i*column_num+j]);
        }
        printf("\n");
    }
    printf("\n");
}

bool DRC963::generate_encoding_matrix() {

	for(int32_t i=0; i<_sys_chunk_num; i++) {
		_enc_matrix[i*_sys_chunk_num+i]=1;
	}
	for(int32_t i=_sys_chunk_num; i<_total_chunk_num; i++) {
		uint8_t temp=1;
		for(int32_t j=0; j<_k; j++) {
			if(i % 3 == 0){
				_enc_matrix[i*_sys_chunk_num+3*j]=temp;
			}else if(i % 3 == 1){
				_enc_matrix[i*_sys_chunk_num+3*j+1]=temp;
			}else{
				_enc_matrix[i*_sys_chunk_num+3*j+2]=temp;
			}
			temp = gf_mul((uint8_t)((i-_sys_chunk_num)+1),temp);
		}
	}
	//show_matrix(_enc_matrix,_total_chunk_num,_sys_chunk_num);
    return true;
}
