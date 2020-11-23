#!/bin/bash
BASEDIR=/home/hadoop/hadoop-20

ssh master rm -rf $BASEDIR/tmp
ssh master rm -rf $BASEDIR/logs
ssh master rm -rf $BASEDIR/stripeStore

for i in  1 2 3 4 #5 6 7 8 9 10 11 12 13
do
ssh slave$i rm -rf $BASEDIR/tmp
ssh slave$i rm -rf $BASEDIR/logs
ssh slave$i rm -rf $BASEDIR/stripeStore
done
