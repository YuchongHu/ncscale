#include <iostream>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <isa-l.h>
#include "ncscale.hh"

using namespace std;
NCSCALE::NCSCALE() {
	drcN = 0;
	drcK = 0;
	drcS = 0;
	blockSize = 0;
}

bool NCSCALE::initialize(int32_t N,
					int32_t K,
					int32_t S,
					int32_t blocksize)
{
	drcN = N;
	drcK = K;
	drcS = S;
	blockSize = blocksize;
	cout<<" init."<<endl;
	return true;
}



void NCSCALE::parityUpdate(uint8_t *parity,
						uint8_t *delta,
						uint8_t *out){
	uint8_t* databuf[2];
	uint8_t* codebuf[1];
	databuf[0] = parity;
	databuf[1] = delta;
	codebuf[0] = out;

	cout<<" parity update operate start."<<endl;
	uint8_t tbls[32*2];
	uint8_t enc_matrix[2]= {1,1};
	ec_init_tables(2,1,enc_matrix,tbls);
	ec_encode_data(blockSize,2,1,tbls,databuf,codebuf);
	cout<<" parity update operate end."<<endl;
}

void NCSCALE::compute(uint8_t **input,
				int32_t inputSize,
				uint8_t **output,
				int32_t outputSize){cout<<" compute operate start."<<endl;
	uint8_t* databuf[inputSize];
	uint8_t* codebuf[outputSize];


	for(int i = 0; i < inputSize; i++)
	{
		databuf[i] = input[i];
	}
	for(int i = 0; i < outputSize; i++)
	{
		codebuf[i] = output[i];
	}

	uint8_t tbls[32*inputSize*outputSize];

	uint8_t enc_matrix[30] = {1,1,1,1,1,1,2,4,8,16,1,3,5,15,17, 1,4,16, 64,29,1 ,5 ,17,85,28, 1,6 ,20 ,120,13};
	ec_init_tables(inputSize,outputSize,enc_matrix,tbls);
	ec_encode_data(blockSize,inputSize,outputSize,tbls,databuf,codebuf);
	cout<<"compute operate end."<<endl;
}


