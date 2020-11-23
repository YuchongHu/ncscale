#!/bin/bash
BASEDIR=/home/hadoop/hadoop-20


cd $BASEDIR
hadoop namenode -format
start-dfs.sh
hadoop dfs -put input raidTest/input
start-raidnode.sh
