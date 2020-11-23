#!/bin/bash
BASEDIR=/home/hadoop/hadoop-20


cd $BASEDIR
./stophadoop.sh
./clear.sh
cp -r conf/ confA/

ant -Dversion=0.20 -Dcompile.native=true clean jar bin-package

cp $HADOOP_HOME/build/contrib/raid/hadoop-0.20-raid.jar $HADOOP_HOME/lib	

cd $HADOOP_HOME/src/native/src/org/apache/hadoop/util
g++ -I $JAVA_HOME/include/linux/ -I $JAVA_HOME/include/ -I /usr/include -fPIC -shared -o libdrc.so NativeDRC.cc drc.cc drc633.cc drc643.cc drc864.cc drc953.cc drc963.cc ia.cc butterfly64.cc butterfly86.cc rsbase.cc car.cc NativeDRC.h drc.hh drc633.hh drc643.hh drc864.hh drc953.hh drc963.hh ia.hh butterfly64.hh butterfly86.hh rsbase.hh car.hh -L/usr/lib -lisal
cp libdrc.so $HADOOP_HOME/build/native/Linux-amd64-64/lib
g++ -I $JAVA_HOME/include/linux/ -I $JAVA_HOME/include/ -I /usr/include -fPIC -shared -o libncscale.so NativeNCScale.cc ncscale.cc NativeNCScale.h ncscale.hh -L/usr/lib -lisal
cp libncscale.so $HADOOP_HOME/build/native/Linux-amd64-64/lib
cd $BASEDIR

rm -r conf/
mv confA/ conf/


