/**
 * drc.hh
 * - A generate erasure code interface
 * please refer to tester.cc for the usage examples
 */

#ifndef __ncscale_hh__
#define __ncscale_hh__

#include <stdint.h>
#include <isa-l.h>

class NCSCALE {
protected:
	int32_t drcN;
	int32_t drcK;
	int32_t drcS;
	int32_t blockSize;

public:

	NCSCALE();


	bool initialize(int32_t N,
					int32_t K,
					int32_t S,
					int32_t blocksize);

	void parityUpdate(uint8_t *parity,
					uint8_t *delta,
					uint8_t *out);

	void compute(uint8_t **input,
				int32_t inputSize,
				uint8_t **output,
				int32_t outputSize);

};

#endif
