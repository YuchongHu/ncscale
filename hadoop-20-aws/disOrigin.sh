#!/bin/bash
BASEDIR=/home/hadoop/

for i in 1 2 3 4 5 6 7 8
do
scp -r $BASEDIR/hadoop-20-aws.tar.gz hadoop@slave$i:
done
