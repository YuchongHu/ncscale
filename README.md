We have tested NCScale on Ubuntu 14.04 LTS.
#### 1. Software Requirements
+ yasm 
+ ISA-L(2.14.0) 
+ Java8 
+ ant

#### 2. Download `hadoop-20-aws.tar.gz`, and extract it into the path `/user/hadoop/`.

#### 3. Configuration
+ **core-site.xml**    You can set proper value in core-site.xml according to your installation (See example in conf/core-site.xml).
+ **hadoop-env.sh**    Set proper value to : `JAVA_HOME` and `HADOOP_USERNAME` i.
+ **hdfs-site.xml**  You can set proper value in core-site.xml according to your installation (See example in conf/hdfs-site.xml). Note that the `drctype=15` in NCScale.
+ **masters**    This file contains the master ip.
+ **slaves**    This file constains all the slaves ip, one ip per line.

#### 4. Run Hadoop
    ./begin.sh
    ./starthadoop.sh

#### 5. start scaling
+ We trigger the scaling process by putting a file (named scaling) into `/user/hadoop/`:
    `dd if=/dev/zero of=scaling bs=1M count=1`
    `mv scaling /user/hadoop/scaling`
+ After scaling, you can get the scaling time by:
    `cat logs/hadoop-hadoop-raidnode-master.log | grep time`