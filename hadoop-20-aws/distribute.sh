#!/bin/bash
BASEDIR=/home/hadoop/hadoop-20

for i in 1 2 3 4 5 6 7 8 9 10 11 12 13
do
scp -r $BASEDIR hadoop@slave$i:
done
