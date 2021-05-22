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

## Publication

Xiaoyang Zhang, Yuchong Hu, Patrick P. C. Lee, Pan Zhou.
**"Toward Optimal Storage Scaling via Network Coding: From Theory to Practice."**
Proceedings of IEEE International Conference on Computer Communications (INFOCOM 2018), Honolulu, HI, USA, April 2018.
(AR: 309/1606 = 19.2%)

## Contact

Please email to Yuchong Hu ([yuchonghu@hust.edu.cn](mailto:yuchonghu@hust.edu.cn)) if you have any questions.

## Our other works

Welcome to follow our other works!

1. FAST 2021: https://github.com/YuchongHu/ecwide
2. ICDCS 2021: https://github.com/YuchongHu/stripe-merge
3. SoCC 2019: https://github.com/YuchongHu/echash
4. INFOCOM 2018: https://github.com/YuchongHu/ncscale
5. TOS: https://github.com/YuchongHu/doubler