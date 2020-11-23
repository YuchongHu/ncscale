#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <iostream>
#include <isa-l.h>

#include "drc633.hh"
#include "drc643.hh"
#include "drc864.hh"
#include "drc953.hh"
#include "drc963.hh"
#include "ia.hh"
#include "butterfly64.hh"
#include "butterfly86.hh"
#include "rsbase.hh"
#include "car.hh"
using namespace std;

void usage(const char *s) {
    fprintf(stderr, "Usage:\n");
    fprintf(stderr, "   ./tester n k r dataLength type blockLength\n");
    fprintf(stderr, "Arguments:\n");
    fprintf(stderr, "      n: the number of data chunks and parity chunks in each stripe, which should be > 0\n");	
    fprintf(stderr, "      k: the number of parity chunk in each stripe, which should be > 0\n");
    fprintf(stderr, "      r: the number of racks, where n is divisible by r\n");
    fprintf(stderr, "      dataLength: the length of the strip, which should be a multiple of 32\n");
    fprintf(stderr, "      type: the type of the erasure code, which should be in {1,2,3,4,5,6,7,8,9,10}\n");
    fprintf(stderr, "           1: DRC633\n");
    fprintf(stderr, "           2: BUT643\n");
    fprintf(stderr, "           3: DRC864\n");
    fprintf(stderr, "           4: DRC953\n");
    fprintf(stderr, "           5: DRC963\n");
    fprintf(stderr, "           6: IA\n");
    fprintf(stderr, "           7: BUTTERFLY64\n");
    fprintf(stderr, "           8: BUTTERFLY86\n");
    fprintf(stderr, "           9: RSBASE\n");
    fprintf(stderr, " 		10: CAR\n");
    fprintf(stderr, "      blockLength: the length of the block, which should be a multiple of dataLength\n");
    if (s != NULL) {
        fprintf(stderr, "%s\n", s);
    }
    exit(1);
}

bool setError(int32_t *corruptArray, int32_t k, int32_t m) {
    for (int32_t i = 0; i < (k+m); i++) {
        if (i == 0) {
            corruptArray[i] = 1;
        } else {
            corruptArray[i] = 0;
        }
    }
    return true;
}

double getCurrentTime() {
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (double)tv.tv_sec * 1e+6 + (double)tv.tv_usec;	
}

int main(int argc, char **argv) {
    int32_t _n = 0, _k = 0, _m = 0, _r = 0, _nr = 0, _dataLen = 0, type = -1;
    int32_t blockLen = 0;
    if (argc <= 6) {
        usage(NULL);
    }
    if (sscanf(argv[1], "%d", &_n) == 0 || _n <= 0) {
        usage("Bad k (which should be > 0)");
    }
    if (sscanf(argv[2], "%d", &_k) == 0 || _k <= 0 || _k > _n) {
        usage("Bad m (which should be > 0)");
    }
    if (sscanf(argv[3], "%d", &_r) == 0 || _n%_r != 0) {
        usage("Bad r (where (k+m) should be divisible by r)");
    }
    if (sscanf(argv[4], "%d", &_dataLen) == 0 || _dataLen%32 != 0 || _dataLen/32 < 1) {
        usage("Bad dataLength (which should be a multiple of 32)");
    }
    if (sscanf(argv[5], "%d", &type) == 0 || type < 1 || type > 10) {
        usage("Bad type of erasure code (which should be in {1,2,3,4,5,6,7,8,9,10})");
    }
    if (sscanf(argv[6], "%d", &blockLen) == 0 || blockLen%_dataLen != 0 || blockLen/_dataLen < 1) {
        usage("Bad blockLength (which should be in multiple of dataLength)");
    }
    _m = _n - _k;
    _nr = _n/_r;
    
    //int32_t blockLen = 67108864;//64;
    int32_t countNum = blockLen/_dataLen;
    double construct_time = 0, check_time = 0, decode_time = 0;
    double node_time = 0, node_interval = 0, group_time = 0, group_interval = 0;
    int32_t nodecount = 0, groupcount = 0;

    uint8_t *data[_k], *parity[_m];
    //allocate arrays
    for(int32_t i = 0; i < _k; i++) {
        data[i] = (uint8_t *)malloc(_dataLen);
    }
    for(int32_t i = 0; i < _m; i++) {
        parity[i] = (uint8_t *)malloc(_dataLen);
    }
    int32_t *corruptArray = (int32_t *)malloc(sizeof(int32_t) * (_k+_m));
    int32_t *dataArray = (int32_t *)malloc(sizeof(int32_t) * (_k+_m));
    int32_t *decodeArray = (int32_t *)malloc(sizeof(int32_t) * _r);
    int32_t *groupArray = (int32_t *)malloc(sizeof(int32_t) * _r);
    int32_t *nodeLenArray = (int32_t *)malloc(sizeof(int32_t) * (_k+_m));
    int32_t *groupLenArray = (int32_t *)malloc(sizeof(int32_t) * (_r));
    // set corruptArray;
    setError(corruptArray, _k, _m);
    int numerrs = 0;
    for(int32_t i = 0; i < (_k+_m); i++) {
        if(corruptArray[i] == 1) {
            numerrs++;
        }
    }
    uint8_t *recover[numerrs];
    for(int32_t i=0; i<numerrs; i++) {
        recover[i] = (uint8_t *)malloc(_dataLen);
    }
    DRC* drc = NULL;
    if (type == 1) {
        drc = new DRC633();
    } else if (type == 2) {
        drc = new DRC643();
    } else if (type == 3) {
        drc = new DRC864();
    } else if (type == 4) {
        drc = new DRC953();
    } else if (type == 5) {
        drc = new DRC963();
    } else if (type == 6) {
        drc = new IA();
    } else if (type == 7) {
        drc = new BUTTERFLY64();
    } else if (type == 8) {
        drc = new BUTTERFLY86();
    } else if (type == 9) {
        drc = new RSBASE();
    } else if (type == 10) {
        drc = new CAR();
    } else {
        drc = new RSBASE();
    } 

    srand((unsigned)1234);
    for (int32_t testCount = 0; testCount < countNum; testCount++) {
        for(int32_t i = 0; i < _m; i++) {
            memset(parity[i], 0, _dataLen);
        }
        //initialize data
        for(int32_t i = 0; i < _k; i++) {
            for(int32_t j = 0; j < _dataLen; j++) {  
                data[i][j] = rand();
            }
        }
        // initialize code
        drc -> initialize(_k, _m, _r, _nr);
        // construct parity
        construct_time -= getCurrentTime();
        drc -> construct(data, parity, _dataLen);
        construct_time += getCurrentTime();
        check_time -= getCurrentTime();
        if(!drc -> check(corruptArray, dataArray, groupArray, _dataLen, nodeLenArray, groupLenArray)) {
            cout<<"bad errors! can't recover by double regenerating code"<<endl;
        }

        // xiaolu debug
        for(int32_t i=0; i<(_k+_m); i++) { 
            cout<<"nodeLenArray["<<i<<"] = "<<nodeLenArray[i]<<endl; 
        } 
        for(int32_t i=0; i<_r; i++) { 
            cout<<"groupLenArray["<<i<<"] = "<<groupLenArray[i]<<endl; 
        }
        for(int32_t i=0; i<(_k+_m); i++) {
            cout<<"corruptArray["<<i<<"] = "<<corruptArray[i]<<endl;
        }
        for(int32_t i=0; i<(_k+_m); i++) {
            cout<<"dataArray["<<i<<"] = "<<dataArray[i]<<endl;
        }
        for(int32_t i=0; i<_r; i++) {
            cout<<"groupArray["<<i<<"] = "<<groupArray[i]<<endl;
        }
        // xiaolu debug end
        
        check_time += getCurrentTime();
        // to find the final input size of decode
        int32_t finalinputsize = 0;
        for(int32_t i=0; i<_r; i++) {
            decodeArray[i] = 0;
            if(groupArray[i] == 0) {  // do not need relayer
                for(int32_t j=0; j<(_k+_m); j++) {
                    if(dataArray[j] == i) {  // find block that is in this group and is needed
                        decodeArray[i]++;
                        finalinputsize++;
                    }
                }
            } else {
                finalinputsize += groupArray[i];
                decodeArray[i] += groupArray[i];
            }
        }

        uint8_t *finalinput[finalinputsize];
        int32_t *finalinputlen = (int32_t *)malloc(sizeof(int32_t) * finalinputsize);
        int32_t filidx=0;
        int32_t p = 0;
        groupcount = 0;
        group_interval = 0;
        nodecount = 0;
        node_interval = 0;
        for(int32_t i = 0; i < _r; i++) {
            if(groupArray[i] == 0) {
                for(int32_t j = i*_nr; j < (i+1)*_nr; j++) {
                    if(dataArray[j] == i) {
                        uint8_t *nodeCode = (uint8_t *)malloc(nodeLenArray[j]);
                        if(j<_k) {
                            node_interval -= getCurrentTime();
                            drc -> nodeEncode(j, corruptArray, data[j], _dataLen, nodeCode, nodeLenArray[j]);
                            node_interval += getCurrentTime();
                            nodecount++;
                        } else {
                            node_interval -= getCurrentTime();
                            drc -> nodeEncode(j, corruptArray, parity[j-_k], _dataLen, nodeCode, nodeLenArray[j]);
                            node_interval += getCurrentTime();
                            nodecount++;
                        }
                        finalinput[p++] = nodeCode;
                        finalinputlen[filidx++] = nodeLenArray[j];
                    }
                }
            } else {
                int32_t num = 0; // num record how many blocks to read in a group
                for(int32_t j = i*_nr; j < (i+1)*_nr; j++) {
                    if(dataArray[j] == i) {
                        num++;
                    }
                }
                uint8_t *groupinput[num]; // assign data to groupinput
                int32_t *groupinputlen = (int32_t *)malloc(sizeof(int32_t) * num);
                int32_t gilidx=0;
                int32_t groupp = 0;
                
                for(int32_t j = i*_nr; j < (i+1)*_nr; j++) {
                    if(dataArray[j] == i) {
                        int32_t nodeoutputlen = nodeLenArray[j];
                        uint8_t *nodeCode = (uint8_t *)malloc(nodeoutputlen);
                        if(j<_k) {
                            node_interval -= getCurrentTime();
                            drc -> nodeEncode(j, corruptArray, data[j], _dataLen, nodeCode, nodeoutputlen);
                            node_interval += getCurrentTime();
                            nodecount++;
                        } else {
                            node_interval -= getCurrentTime();
                            drc -> nodeEncode(j, corruptArray, parity[j-_k], _dataLen, nodeCode, nodeoutputlen);
                            node_interval += getCurrentTime();
                            nodecount++;
                        }
                        groupinput[groupp++] = nodeCode;
                        groupinputlen[gilidx++] = nodeoutputlen;

                    }
                }
                uint8_t *groupoutput[groupArray[i]];
                for(int32_t j=0; j<groupArray[i]; j++) {
                    groupoutput[j] = (uint8_t *)malloc(groupLenArray[i]);
                    finalinputlen[filidx++] = groupLenArray[i];
                }
                group_interval -= getCurrentTime();
                drc -> groupEncode(i, corruptArray, groupinput, num, groupinputlen, 
                        groupoutput, groupArray[i], groupLenArray[i]);
                group_interval += getCurrentTime();
                groupcount++;
                // assign groupoutput to finalinput
                for(int32_t j=0; j<groupArray[i]; j++) {
                    finalinput[p++] = groupoutput[j];
                }
            }
        }

        // now decode
        decode_time -= getCurrentTime();
        drc -> decode(corruptArray, finalinput, decodeArray, finalinputlen, recover, numerrs, _dataLen);
        decode_time += getCurrentTime();
        if (groupcount != 0) {
            group_time += group_interval/groupcount;
        }
        if (nodecount != 0) {
            node_time += node_interval/nodecount;
        }
        bool error = false;
        int32_t now = 0;
        for(int32_t i=0; i<(_k+_m); i++) {
            if(corruptArray[i] == 1) {
                for(int32_t j=0; j<_dataLen; j++) {
                    if(i<_k) {
                        if(data[i][j] != recover[now][j]) {
                            error = true;
                            cout<<"recover fail at data "<<i<<", offset "<<j<<endl;
                        }
                    } else {
                        if(parity[i-_k][j] != recover[now][j]) {
                            error = true;
                            cout<<"recover rail at parity "<<i-_k<<", offset "<<j<<endl;
                        }
                    }
                    if(error) {
                        break;
                    }
                }
                now++;
                if(error) {
                    break;
                }
            }
        }

        if(error) {
            cout<<"recover failed!"<<endl;
        /* } else { */
        /*     cout<<"recover success!"<<endl; */
        }
    }

    printf("The throughput of construct() (MB/s): %.3lf\n", (blockLen*_k/1.048576) / construct_time); 
    printf("The repair throughput of check() (MB/s): %.3lf\n", (blockLen/1.048576) / check_time); 
    if (node_time != (double)0) {
        printf("The repair throughput of nodeEncode() (MB/s): %.3lf\n", (blockLen/1.048576) / node_time); 
    } else {
        printf("The repair throughput of nodeEncode() (MB/s): %.3lf\n", 0.0); 
    }   
    if (group_time != (double)0) {
        printf("The repair throughput of groupEncode() (MB/s): %.3lf\n", (blockLen/1.048576) / group_time); 
    } else {
        printf("The repair throughput of groupEncode() (MB/s): %.3lf\n", 0.0); 
    }   
    printf("The repair throughput of decode() (MB/s): %.3lf\n", (blockLen/1.048576) / decode_time); 

    return 0;
}
