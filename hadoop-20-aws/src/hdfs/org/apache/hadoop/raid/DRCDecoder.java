/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.raid;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Enumeration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSInputStream;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.GroupInputStream;
import org.apache.hadoop.hdfs.ReadStreamInfo;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.protocol.VersionAndOpcode;
import org.apache.hadoop.hdfs.protocol.WriteBlockHeader;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.hdfs.server.datanode.BlockDataFile;
import org.apache.hadoop.hdfs.server.datanode.BlockSender;
import org.apache.hadoop.hdfs.server.datanode.BlockWithChecksumFileReader;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.FSDataset;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.raid.RaidUtils;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.Progressable;
import org.apache.hadoop.util.ReflectionUtils;

import org.json.JSONException;
import org.json.JSONObject;

public class DRCDecoder {
    public static final Log LOG = LogFactory.getLog(
            "DRCDecoder.class");
    public static final String ERASURE_CODE_KEY_PREFIX = "hdfs.raid.erasure.code.";
    public static final String DECODING_MAX_BUFFER_SIZE_KEY =
        "hdfs.raid.strip.size";

    protected Configuration conf;
    protected String jsonStr;
    private Map<Integer, LocatedBlockWithFileName> stripeBlocks;
    private int[] corruptArray;

    private JSONObject json;

    private String codeId;
    private int stripeSize;
    private int paritySize;
    private int groupNum;
    private int groupSize;
    private String erasureCodeClass;
    private String parityDir;
    private boolean isDirRaid;
    private ErasureCode code;
    private boolean RecoverOver = false;

    private int[] dataArray = null;
    private int[] groupArray = null;
    private int[] decodeArray = null;
    private long[] remain = null;         // used for read thread
    private long[] calremain = null;      // used for cal thread
    private long[] endOffset = null;      // used for write thread
    private File[] localFile = null;
    //  private ByteBuffer cache = null;

    private boolean recoverable;

    private int stripSize=0;
    private int blockSize;
    private int corruptNum=0;

    // for new interface
    private int[] nodeLenArray = null;
    private int[] groupLenArray = null;
    // TODO tobe deleted
    private int decodeOutputLen;    // unitlen
    //
    private int finalInputSize = 0;
    private int[] finalInputLen = null;

    private DFSClient dfsClient;
    private int numThread;

    // statistic data
    private long pre1=0, pre2=0, pretime=0;
    private long read1=0, read2=0, readtime = 0;
    private long syn1=0, syn2=0, syntime=0;
    private long readthread1=0, readthread2=0, readthreadtime=0;
    private long cal1=0, cal2=0, caltime=0;
    private long calthread1=0, calthread2=0, calthreadtime=0;
    private long caltake1=0, caltake2=0, caltaketime=0;
    private long writetake1=0, writetake2=0, writetaketime=0;
    private long write1=0, write2=0, writetime=0;
    private long total1=0, total2=0, totaltime=0;

    // degraded read
    private boolean degraded = false;
    private int lostIdx = -1;
    
    private int[] cpoint;
    ArrayBlockingQueue<byte[][]> readqueue;
    Map<Integer, ArrayBlockingQueue<ByteBuffer>> queues;
    ByteBuffer degradedBuffer = null;
    private int degradedPos = -1;

    private int namespaceid = -1;

    private boolean readend = false;
    private boolean calend = false;
    private boolean writeend = false;

    public DRCDecoder(Configuration conf, String jsonStr, Map<Integer, LocatedBlockWithFileName> stripeBlocks,
            int[] corruptArray, boolean degradedRead, int lostBlkIdxInStripe) throws IOException {
        this(conf, jsonStr, stripeBlocks, corruptArray);

        this.degraded = degradedRead;
        this.lostIdx = lostBlkIdxInStripe;
        System.out.println("degraded read = " + this.degraded);
    }

    public DRCDecoder(Configuration conf, String jsonStr, Map<Integer, LocatedBlockWithFileName> stripeBlocks,
            int[] corruptArray) 
        throws IOException {
        long drc1 = System.nanoTime();
        this.conf = conf;
        this.jsonStr = jsonStr;
        this.stripeBlocks = stripeBlocks;
        this.corruptArray = corruptArray;
        this.numThread = conf.getInt("raid.decode.parallel.num", 1);
        long i1=System.nanoTime(); 
        init();
        long i2=System.nanoTime();
        DataNode.LOG.info("DRCDecoder.constructor.init time = "+(i2-i1));
        assert dataArray != null;
        assert groupArray != null;

        this.stripSize = conf.getInt("hdfs.raid.strip.size", 1024*1024);
        
        // TODO: init nodeLenArray and groupLenArray
        this.nodeLenArray = new int[this.stripeSize + this.paritySize];
        this.groupLenArray = new int[this.groupNum];

        long v1=System.nanoTime();
        this.recoverable = verify(corruptArray, dataArray, groupArray,
                this.stripSize, nodeLenArray, groupLenArray);
        long v2=System.nanoTime();
        DataNode.LOG.info("DRCDecoder.constructor.verify time = "+(v2-v1));

        DataNode.LOG.info("DRCDecoder.before change lenArray");
        for (int i=0; i<this.stripeSize+this.paritySize; i++) {
            DataNode.LOG.info("nodeLenArray[" + i + "] = " + nodeLenArray[i]);
        }

        for(int i=0; i<this.groupNum; i++) {
            DataNode.LOG.info("groupLenArray[" + i + "] = " + groupLenArray[i]);
        }


        // TODO:here nodeEncodeLen and groupEncodeLen is old, should change to new interface

        // this part should delete
        this.decodeOutputLen = this.stripSize;

        for(int i=0; i<this.stripeSize+this.paritySize; i++) {
            DataNode.LOG.info("corruptArray[" + i + "] = " + corruptArray[i]);
        }

        for(int i=0; i<this.stripeSize+this.paritySize; i++) {
            DataNode.LOG.info("dataArray[" + i + "] = " + dataArray[i]);
        }

        for(int i=0; i<this.groupNum; i++) {
            DataNode.LOG.info("groupArray[" + i + "] = " + groupArray[i]);
        }


        this.blockSize = conf.getInt("dfs.block.size", 1048576);

        this.decodeArray = new int[groupNum];
        for(int i=0; i<groupNum; i++) {
            decodeArray[i] = 0;
        }

        this.corruptNum = 0;
        for(int i=0; i<corruptArray.length; i++) {
            if(corruptArray[i]==1) {
                corruptNum++;
            }
        }

        int cp=0;
        this.cpoint = new int[corruptNum];
        for(int i=0; i<corruptArray.length; i++) {
            if(corruptArray[i] == 1) {
                cpoint[cp++] = i;
            }
        }

        Path tmppath = new Path("/");
        DistributedFileSystem fs = getDFS(tmppath, conf);
        this.dfsClient = fs.getClient();

        this.namespaceid = dfsClient.getnamespaceId();
        long drc2 = System.nanoTime();
        DataNode.LOG.info("DRCDecoder.constructor.time = " + (drc2-drc1));
    }

    public void init() throws IOException {
        try {
            json = new JSONObject(jsonStr);
            this.codeId = json.getString("id");
            this.stripeSize = json.getInt("stripe_length");
            this.paritySize = json.getInt("parity_length");
            this.groupNum = json.getInt("group_num");
            this.groupSize = json.getInt("group_size");
            this.erasureCodeClass = json.getString("erasure_code");
            this.parityDir = json.getString("parity_dir");
            this.isDirRaid = Boolean.parseBoolean(getJSONString(json, "dir_raid", "false"));
        } catch (JSONException e) {
            throw new IOException(e);
        }

        this.code = createErasureCode(conf);

        this.dataArray = new int[this.stripeSize + this.paritySize];
        this.groupArray = new int[this.groupNum];
        //    DataNode.LOG.info("DRCDecoder.constructor.createErasureCode finish");
    }

    public ErasureCode createErasureCode(Configuration conf) {
        Class<?> erasureCode = null;

        try {
            erasureCode = conf.getClass(ERASURE_CODE_KEY_PREFIX + this.codeId,
                    conf.getClassByName(this.erasureCodeClass));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        ErasureCode code = (ErasureCode)ReflectionUtils.newInstance(erasureCode,
                conf);
        code.init(this.stripeSize, this.paritySize, this.jsonStr);
        return code;
    }

    static private String getJSONString(JSONObject json, String key, String defaultResult) {
        String result = defaultResult;
        try {
            result = json.getString(key);
        } catch (JSONException e) {
        }

        return result;
    }

    public boolean getRecoverable() {
        return this.recoverable;
    }

    public boolean verify(int[] corruptArray, int[] dataArray, int[] groupArray,
            int dataLen, int[] nodeLenArray, int[] groupLenArray)
        throws IOException {
        boolean res = false;
        if(this.codeId.equals("drc"))
            res = ((DRCCode)code).verify(corruptArray, dataArray, groupArray, dataLen,
                    nodeLenArray, groupLenArray);
        return res;
    }    

    public DistributedFileSystem getDFS(Path p, Configuration conf) throws IOException {
        FileSystem fs = p.getFileSystem(conf);
        DistributedFileSystem dfs = null;
        if (fs instanceof DistributedFileSystem) {
            dfs = (DistributedFileSystem) fs;
        } else if (fs instanceof FilterFileSystem) {
            FilterFileSystem ffs = (FilterFileSystem) fs;
            if (ffs.getRawFileSystem() instanceof DistributedFileSystem) {
                dfs = (DistributedFileSystem) ffs.getRawFileSystem();
            }
        }
        return dfs;
    }

    public int recoverBlockToFile() throws IOException {
        //1.find how much corruptions are there
        total1=System.nanoTime();
        pre1=System.nanoTime();
        calremain = new long[this.corruptNum];
        endOffset = new long[this.corruptNum];
        localFile = new File[this.corruptNum];
        int corruptp=0;
        for(int i=0; i<corruptArray.length; i++) {
            if(corruptArray[i] == 1) {
                LocatedBlockWithFileName lb = stripeBlocks.get(i);
                Path p = new Path(lb.getFileName());
                DistributedFileSystem fs = getDFS(p, this.conf);
                FileStatus stat = fs.getFileStatus(p);
                endOffset[corruptp] = Math.min(this.blockSize, (stat.getLen() - lb.getStartOffset()));
                calremain[corruptp] = endOffset[corruptp];
                corruptp++;
            }
        }
        //2.create BlockingQueueArray
        // this queue is used for read thread send data to calthread
        readqueue = new ArrayBlockingQueue<byte[][]>(300);

        // this queues is uesd for calculate thread send data to write thread
        queues = new HashMap<Integer, ArrayBlockingQueue<ByteBuffer>>(300);  

        for(int i=0; i<this.corruptNum; i++) {
            ArrayBlockingQueue<ByteBuffer> q = new ArrayBlockingQueue<ByteBuffer>(300);
            queues.put(i, q);
        }
        //3.find how much inputstream are there
        /*
         * Here, groupArray[i] = 1 -> this rack needs relayer
         * dataArray[j] = rackID -> this datanode does not corrupt
         * dataArray[j] = -1 -> this datanode is corrupted.
         * Xiaoyang add.
         */
        int inputStreamNum = 0;
        for(int i=0; i<groupArray.length; i++) {
            if(groupArray[i] == 0) {
                // do not need relayer for this group
                for(int j=0; j<dataArray.length; j++) {
                    if(dataArray[j] == i) {
                        inputStreamNum++;
                        this.decodeArray[i]++;
                        finalInputSize++;
                    }
                }
            } else if(groupArray[i] > 0) {
                inputStreamNum++;
                this.decodeArray[i] += groupArray[i];
                finalInputSize += groupArray[i];
            } else {
                throw new IOException("wrong groupArray to recover by double regenerating code!");
            }
            DataNode.LOG.info("DRCDecoder.recoverBlockToFile.groupArray[" + i + "] = " + groupArray[i]);
            DataNode.LOG.info("DRCDecoder.recoverBlockToFile.decodeArray[" + i + "] = " + decodeArray[i]);
        }

        
        // create infos need for the final decode input len
        finalInputLen = new int[finalInputSize];
        int filidx = 0;

        ReadStreamInfo readInfo[] = new ReadStreamInfo[inputStreamNum];
        remain = new long[inputStreamNum];
        int k=0;
        for(int i=0; i<groupArray.length; i++) {
            DataNode.LOG.info("DRCDecoder.recoverBlockToFile.groupArray " + i + " = " + groupArray[i]);
            if(groupArray[i] == 0) {
                //do not need relayer for this group
                for(int j=0; j<dataArray.length; j++) {
                    if(dataArray[j] == i) {
                        //readInfo[k] is a DFSInputStream
                        LocatedBlockWithFileName lb = stripeBlocks.get(j);
                        String fileName = lb.getFileName();
                        Path filePath = new Path(fileName);
                        long offset = lb.getStartOffset();
                        DistributedFileSystem fs = getDFS(filePath, this.conf);

                        DataNode.LOG.info("DRCDecoder.recoverBlockToFile. j = " + j);
                        long tmpnodeoutputlen = ((long)this.blockSize *(long)nodeLenArray[j])/(long)this.stripSize;
                        DataNode.LOG.info("DRCDecoder.recoverBlockToFile.tmpnodeoutputlen = " + tmpnodeoutputlen);

                        FSDataInputStream input = fs.open(filePath, conf.getInt("io.file.buffer.size", 64 * 1024), 
                                this.codeId, this.jsonStr, j, corruptArray,	tmpnodeoutputlen);
                        input.seek(offset);

                        DataNode.LOG.info("DRCDecoder.recoverBlockToFile.readInfo " + k + " = dataArray " + j);
                        remain[k] = tmpnodeoutputlen;
                        readInfo[k++] = new ReadStreamInfo(i, input, false, nodeLenArray[j], 1);
                        finalInputLen[filidx++] = nodeLenArray[j];
                    }
                }
            } else if(groupArray[i] > 0) {
                //this group need a relayer
                int num = 0;
                for(int p=0; p<dataArray.length; p++) {
                    if(dataArray[p] == i) {
                        num++;
                    }
                }

                Map<Integer, LocatedBlockWithFileName> groupBlocks = new HashMap<Integer, LocatedBlockWithFileName>();
                int[] idxArray = new int[num];

                int r=0;
                for(int j=0; j<stripeBlocks.size(); j++) {
                    if(dataArray[j] == i) {
                        idxArray[r++] = j;
                        LocatedBlockWithFileName lb = stripeBlocks.get(j);
                        groupBlocks.put(j, lb);
                    }
                }

                Path p = new Path("/");
                DistributedFileSystem fs = getDFS(p, this.conf);

                DataNode.LOG.info("DRCDecoder.recoverBlockToFile.i = " + i);
                long tmpgroupoutputlen = ((long)this.blockSize *(long)groupLenArray[i])/(long)this.stripSize;
                DataNode.LOG.info("DRCDecoder.recoverBlockToFile.tmpgroupoutputlen = " + tmpgroupoutputlen);
                
                
                // 2th input of fs.open this.groupSize->num
                GroupInputStream input = fs.open(i, num, conf.getInt("io.file.buffer.size", 4096), groupBlocks, 
                        groupArray[i], this.codeId, new ReadOptions(), (int)(groupArray[i]*tmpgroupoutputlen), corruptArray, 
                        idxArray, this.jsonStr);

                remain[k] = tmpgroupoutputlen * groupArray[i];
                readInfo[k++] = new ReadStreamInfo(i, input, true, groupLenArray[i], groupArray[i]);
                for(int ll=0; ll<groupArray[i]; ll++) {
                    finalInputLen[filidx++] = groupLenArray[i];
                }
            } else {
                throw new IOException("wrong groupArray to recover by double regenerating code!");
            }
        }        
        pre2=System.nanoTime();
        pretime+=pre2-pre1;
        //4.create recoverEncoder
        ThreadFactory readFactory = new ThreadFactoryBuilder().setNameFormat("parallel-rack-read-pool-%d").build();
        ExecutorService readPool = Executors.newFixedThreadPool(inputStreamNum, readFactory);

        Thread readThread = new ReadThread(remain, readInfo.length, readInfo.length, readPool, readInfo, corruptNum, readqueue);
        readThread.setName("parallel-read-main");
        readThread.start();

        for(int i=0; i<finalInputSize; i++) {
            DataNode.LOG.info("DRCDecoder.recoverBlockToFile.finalInputLen[" + i + "] = " + finalInputLen[i]);
        }

        //5. create calculation thread
        Thread calThread = new CalThread(readqueue, queues, corruptNum, calremain);
        calThread.start();
        if(!degraded) {
            writeLocal();
        }

        total2=System.nanoTime();
        totaltime+=total2-total1;
        DataNode.LOG.info("DRCDecoder.recoverBlockTofile.pretime = " + pretime + " ns");
        DataNode.LOG.info("DRCDecoder.recoverBlockTofile.syntime = " + syntime + " ns");
        DataNode.LOG.info("DRCDecoder.recoverBlockTofile.readthreadtime = " + readthreadtime + " ns");
        DataNode.LOG.info("DRCDecoder.recoverBlockTofile.caltime = " + caltime + " ns");
        DataNode.LOG.info("DRCDecoder.recoverBlockTofile.calthreadtime = " + calthreadtime + " ns");
        DataNode.LOG.info("DRCDecoder.recoverBlockTofile.caltaketime = " + caltaketime + " ns");
        DataNode.LOG.info("DRCDecoder.recoverBlockTofile.totaltime = " + totaltime + " ns");

        return 0;

    }

    public void writeLocal() throws IOException {
        DataNode.LOG.info("start writeLocal");
        Semaphore writeSlots = new Semaphore(corruptNum);
        long startwrite = System.nanoTime();
        for(int i=0; i<corruptNum;) {
            int stripei = cpoint[i];
            LocatedBlockWithFileName lb = stripeBlocks.get(stripei);

            //address
            DatanodeInfo[] datanodes = lb.getLocations();
            InetAddress localaddr = InetAddress.getLocalHost();
            String nic = conf.get("dfs.datanode.dns.interface");
            NetworkInterface ni = NetworkInterface.getByName(nic);
            Enumeration<InetAddress> nicaddrs = ni.getInetAddresses();
            while(nicaddrs.hasMoreElements()) {
                InetAddress curr = nicaddrs.nextElement();
                if(curr instanceof Inet4Address) {
                    localaddr = curr;
                    break;
                }
            }
            String port = conf.get("dfs.datanode.address", "50010");
            String[] porttmp = port.split(":");
            String localname = localaddr.getHostAddress() + ":" + porttmp[1];

            assert localname != null;

            try {
                boolean acquired = writeSlots.tryAcquire(1, 100,	TimeUnit.MINUTES);
                if(acquired) {
                    new WriteThread(queues, i, lb, endOffset[i], this.conf, localname, this.dfsClient, writeSlots, localFile).start();
                    i++;
                }
            } catch (Exception e) {
                DataNode.LOG.info("DRCDecoder.writeLocal.create writethread error");
            }
        }

        try {
            boolean acquired = writeSlots.tryAcquire(corruptNum, 100, TimeUnit.MINUTES);
            if(acquired) {
                writeSlots.release(corruptNum);
            }
        }catch(Exception e) {
            DataNode.LOG.info("DRCDecoder.writeLocal.release all slots error");
        }
    }

    public void writeHdfs() throws IOException {

        for(int i=0; i<corruptNum;i++) {
            int stripei = cpoint[i];
            LocatedBlockWithFileName lb = stripeBlocks.get(stripei);

            // address
            DatanodeInfo[] datanodes = lb.getLocations();
            InetAddress localaddr = InetAddress.getLocalHost();
            String nic = conf.get("dfs.datanode.dns.interface");
            NetworkInterface ni = NetworkInterface.getByName(nic);
            Enumeration<InetAddress> nicaddrs = ni.getInetAddresses();
            while(nicaddrs.hasMoreElements()) {
                InetAddress curr = nicaddrs.nextElement();
                if(curr instanceof Inet4Address) {
                    localaddr = curr;
                    break;
                }
            }
            String port = conf.get("dfs.datanode.address", "50010");
            String[] porttmp = port.split(":");
            String localnametmp = localaddr.getHostAddress() + ":" + porttmp[1];

            String localname = checkAddr(datanodes, localnametmp);
            assert localname != null;

            try {
                // compute
                FileInputStream blockContents = new FileInputStream(localFile[i]);
                DataInputStream blockMetaData = computeMetaData(this.conf, blockContents);
                blockContents.close();

                // connect to host
                blockContents = new FileInputStream(localFile[i]);
                Progressable progress = new Progressable() {
                    @Override
                    public void progress() {
                    }
                };
               
                connectLocal(localname, blockContents, blockMetaData, lb.getBlock(),
                        endOffset[i], this.dfsClient.getDataTransferProtocolVersion(),
                        this.dfsClient.getnamespaceId(), progress, this.conf);
                blockContents.close();
                blockMetaData.close();
            } catch (Exception e) {
                DataNode.LOG.info("writeHdfs.exception for corruptnum " + i);
            } finally {
                localFile[i].delete();
            }
        }
    }

    public byte[] getBufferForDegraded(int size) throws IOException{
        ByteBuffer tmp = null;	
        System.out.println("degradedPos = " + degradedPos);
        System.out.println("stripSize = " + stripSize);
        System.out.println("lostIdx = " + lostIdx);
        if(degradedPos == stripSize) {
            degradedBuffer = null;
            degradedPos = -1;
        }

        // lostIdx queues cpoint degraded corruptNum
        while(degradedBuffer == null) {
            System.out.println("degradedBuffer == null");
            System.out.println("corruptNum = " + corruptNum);
            System.out.println("queue.size = " + queues.size());
            for (int i=0; i<corruptNum; i++) {
                try {
                    System.out.println("queue." + i+ ".length = " + queues.get(i).size());
                    tmp = queues.get(i).take();
                } catch (InterruptedException e) {
                }
                if (cpoint[i] == lostIdx) {
                    degradedBuffer = tmp;
                    degradedPos = 0;
                }
            }
        }

        System.out.println("degradedPos = " + degradedPos);
        byte[] buf = degradedBuffer.array();
        System.out.println("degraded buf length = " + buf.length);
        byte[] ret = new byte[size];
        int len = Math.min(size, stripSize - degradedPos);
        System.out.println("degraded.len = " + len);
        System.out.println("buf.len = " + buf.length);
        System.out.println("ret.len = " + ret.length);
        System.arraycopy(buf, degradedPos, ret, 0, len);
        degradedPos += size;

        	return ret;
    }

    private String checkAddr(DatanodeInfo[] avoids, String localtmp) throws IOException {
        String ret;
        boolean refind = false;

        if(avoids.length == 0) {
            return localtmp;
        }

        String rack = avoids[0].getNetworkLocation();
        Path p = new Path("/");
        DistributedFileSystem fs = getDFS(p, this.conf);
        String[] datanodes = fs.getDatanodesByRack(rack);

        for(String datanode: datanodes) {
            refind = false;
            for(DatanodeInfo avoid: avoids) {
                if(datanode.equals(avoid.name)) {
                    refind = true;
                    break;
                }
            }
            if(!refind) {
                return datanode;
            }
        }
        return null;
    }

    class ReadThread extends Thread {
        boolean running = true;
        long[] remainingbytes = null;
        int streamnum;
        int threadnum;
        ExecutorService readPool;
        ReadStreamInfo[] streams;
        int corruptnum;
        ArrayBlockingQueue<byte[][]> queue;
        Semaphore slots;
        int[] streamoffset;
        long[] recoverreadtime;

        public ReadThread(long[] remain, int stream, int threads, ExecutorService rp, ReadStreamInfo[] rsis,
                int corrupt, ArrayBlockingQueue<byte[][]> q) {
            remainingbytes = remain;
            streamnum = stream;
            threadnum = threads;
            readPool = rp;
            streams = rsis;
            corruptnum = corrupt;
            queue = q;

            slots = new Semaphore(threadnum);
            streamoffset = new int[streamnum];
            for(int i=0; i<streamnum; i++) {
                streamoffset[i] = 0;
            }

            recoverreadtime = new long[streamnum];
            for(int i=0; i<streamnum; i++) {
                recoverreadtime[i] = 0;
            }
        }

        public void run() {
            readthread1 = System.nanoTime();
            while(running) {
                read1=System.nanoTime();
                for(int i=0; i<streamnum;) {
                    try {
                        boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
                        if(acquired) {
                            int toreadbyunit = streams[i].getUnitSize();
                            int off = streamoffset[i];
                            readPool.execute(new ReadOperation(toreadbyunit, streams[i], slots, off, i, recoverreadtime));
                            streamoffset[i] += streams[i].getUnitNum()*toreadbyunit;
                            i++;
                        }
                    } catch(Exception e) {
                        DataNode.LOG.info("DRCDecoder.ReadThread.run stream " + i + " for Exception");
                    }
                }

                // should wait for all the stream read ready
                syn1=System.nanoTime();
                while(true) {
                    try {
                        boolean acquired = slots.tryAcquire(threadnum, 100, TimeUnit.MINUTES);
                        if(acquired) {
                            slots.release(threadnum);
                            break;
                        }
                    } catch(Exception e) {
                        DataNode.LOG.info("DRCDecoder.ReadThread.run.while Exception");
                    }
                }
                read2=System.nanoTime();
                readtime+=read2-read1;
                syn2=System.nanoTime();
                syntime+=syn2-syn1;

                int inputnum = 0;
                for(int i=0; i<streamnum; i++) {
                    inputnum += streams[i].getUnitNum();
                }

                byte[][] input = new byte[inputnum][];
                int k=0;
                for(int i=0; i<streamnum; i++){
                    streams[i].mapToOut();
                    for(int j=0; j<streams[i].getUnitNum(); j++) {
                        input[k++] = streams[i].getOutBuffer(j);
                    }
                }
                try {
                    queue.put(input);
                } catch (InterruptedException ie) {
                    DataNode.LOG.info("DRCDecoder.ReadThread.put exception");
                }

                boolean finish = true;
                for(int i=0; i<streamnum; i++) {
                    int toreadbyunit = streams[i].getUnitSize();
                    remainingbytes[i] -= (long)(streams[i].getUnitNum()*toreadbyunit);
                    if(remainingbytes[i]>0) {
                        finish = false;
                    }
                }

                if(finish == true) {
                    running = false;
                }
            }
            for(int i=0; i<streamnum; i++) {
                DataNode.LOG.info("DRCDecoder.ReadThread.readtime " + i 
                        + " = " + recoverreadtime[i]);
            }
            readPool.shutdown();
            readthread2=System.nanoTime();
            readthreadtime+=readthread2-readthread1;
            readend = true;
        }
    }

    public class ReadOperation implements Runnable {
        int toReadByUnit;
        ReadStreamInfo stream;
        Semaphore slot;
        int offset;
        int idx;
        long[] rtime;

        byte[] buffer;

        ReadOperation(int to, ReadStreamInfo rsi, Semaphore s, int off, int id, long[] retime) {
            toReadByUnit = to;
            stream = rsi;
            slot = s;
            offset = off;
            idx = id;
            rtime = retime;

            buffer = rsi.allocate(toReadByUnit);
        }

        public void run() {
            long recread1 = System.nanoTime();
            try {
                if(stream.getInputStream() == null) {
                    Arrays.fill(buffer, (byte)0);
                } else {
                    stream.getInputStream().read(buffer, 0, toReadByUnit * stream.getUnitNum());
                }
            } catch(IOException e) {
                DataNode.LOG.info("DRCDecoder.ReadOperation.run IOException idx = " + idx);
            } finally {
                slot.release();
                String temp = Integer.toHexString(buffer[stream.getUnitSize() * stream.getUnitNum() - 1]);
            }
            long recread2 = System.nanoTime();
            long recreadtime = recread2-recread1;
            rtime[idx] += recreadtime;
        }
    }

    public class CalThread extends Thread {
        ArrayBlockingQueue<byte[][]> rqueue;
        Map<Integer, ArrayBlockingQueue<ByteBuffer>> cqueue;
        int corruptnum;
        long[] rbytes = null;
        boolean running = true;
        byte[][] calinput = null;

        CalThread(ArrayBlockingQueue<byte[][]> rq, 
                Map<Integer, ArrayBlockingQueue<ByteBuffer>> cq,
                int cnum, long[] remain) {
            rqueue = rq;
            cqueue = cq;
            corruptnum = cnum;
            rbytes = remain;
        }

        public void run() {
            calthread1=System.nanoTime();	
            while(running) {
                if(calinput == null) {
                    try {
                        caltake1=System.nanoTime();
                        calinput = rqueue.take();
                        caltake2=System.nanoTime();
                        caltaketime+=caltake2-caltake1;
                    } catch (InterruptedException e) {
                        DataNode.LOG.info("DRCDecoder.CalThread.take exception");
                    }
                }

                byte[][] caloutput = new byte[corruptnum][];
                for(int i=0; i<corruptnum; i++) {
                    caloutput[i] = new byte[decodeOutputLen];
                }


                boolean res = false;
                if(codeId.equals("drc")){
                    try {
                        cal1=System.nanoTime();
                        res = ((DRCCode)code).recover(corruptArray, calinput,
                                decodeArray, finalInputLen, caloutput, corruptnum, decodeOutputLen);
                        cal2=System.nanoTime();
                        caltime+= cal2-cal1;
                    } catch(IOException e) {
                        DataNode.LOG.info("DRCDecoder.CalThread.cal exception");
                    }
                }
                if(res == false) {
                    DataNode.LOG.info("DRCDecoder.CalThread.cal false");
                }

                // to put data in queue
                try {
                    for(int i=0; i<corruptnum; i++) {
                        ByteBuffer tmp = ByteBuffer.allocate(decodeOutputLen);
                        tmp.put(caloutput[i]);
                        tmp.rewind();
                        cqueue.get(i).put(tmp);
                        tmp = null;
                    }
                } catch (InterruptedException e) {
                    DataNode.LOG.info("DRCDecoder.CalThread.put exception");
                }

                calinput = null;

                boolean finish = true;
                for(int i=0; i<corruptnum; i++) {
                    rbytes[i] -= decodeOutputLen;
                    if(rbytes[i] > 0) {
                        finish = false;
                    }
                }

                if(finish == true) {
                    running = false;
                }
            }
            calthread2=System.nanoTime();
            calthreadtime+=calthread2-calthread1;
            RecoverOver = true;

        }
    }


    public class WriteThread extends Thread {
        Map<Integer, ArrayBlockingQueue<ByteBuffer>> queue;
        int idx;
        LocatedBlockWithFileName writeLb;
        long writeEndOffset;
        Configuration writeConf;
        String writeAddress;
        DFSClient writeClient;
        Semaphore writeslots;
        int count=0;

        String writeBlockName;
        ByteBuffer writeCache = null;


        WriteThread(Map<Integer, ArrayBlockingQueue<ByteBuffer>> queues, int queueid,
                LocatedBlockWithFileName lb, long off, Configuration c, String addr,
                DFSClient dfs, Semaphore s, File[] file) {
            queue = queues;
            idx = queueid;
            writeLb = lb;
            writeEndOffset = off;
            writeConf = c;
            writeAddress = addr;
            writeClient = dfs;
            writeslots = s;

            writeBlockName = writeLb.getBlock().getBlockName();
        }

        public void run() {
            // create outputstream for lost data block
            long writethreadtime=0;
            long write1=System.nanoTime();
            try {
                localFile[idx] = File.createTempFile(writeBlockName, ".tmp");
                localFile[idx].deleteOnExit();
            } catch (IOException e) {
                DataNode.LOG.info("DRCDecoder.WriteThread " + idx + " constructor error!");
            }
            OutputStream out = null;
            try {
                out = new FileOutputStream(localFile[idx]);
                int nowOffset = 0;
                while(writeEndOffset > nowOffset) {
                    if(writeCache == null) {
                        try {
                            writetake1=System.nanoTime();
                            writeCache = queue.get(idx).take();
                            writetake2=System.nanoTime();
                            writetaketime+=writetake2-writetake1;
                        } catch(InterruptedException e) {
                            DataNode.LOG.info("DRCDecoder.WriteThread " + idx + " run exception");
                        }
                    }

                    int r = writeCache.remaining();
                    byte[] buffer = new byte[r];
                    writeCache.get(buffer, 0, r); 

                    nowOffset += r;
                    out.write(buffer, 0, r);
                    count++;
                    writeCache = null;
                }

            } catch (Exception e) {
                DataNode.LOG.info("DRCDecoder.WriteThread " + idx + "exception!!");
            } finally {
                if(null != out) {
                    try {
                        out.close();
                    } catch (Exception e) {
                        DataNode.LOG.info("DRCDecoder.WriteThread " + idx + " finally exception!!");
                    }
                }
                writeslots.release();
                DataNode.LOG.info("DRCDecoder.writeThread.release " + idx);
            }
            long write2=System.nanoTime();
            writetime = write2-write1;
            DataNode.LOG.info("DRCDecoder.WriteThread.writetime = " + writetime);

        }
    }

    public void connectLocal(String localName, final FileInputStream blockContents, 
            final DataInputStream metaData, Block block, long blockSize, int dataTransferVersion, 
            int namespaceId, Progressable progress, Configuration writeconf) throws IOException {
        InetSocketAddress target = NetUtils.createSocketAddr(localName);
        Socket sock = SocketChannel.open().socket();

        int readTimeout =
            writeconf.getInt(BlockIntegrityMonitor.BLOCKFIX_READ_TIMEOUT, 
                    HdfsConstants.READ_TIMEOUT);
        NetUtils.connect(sock, target, readTimeout);
        sock.setSoTimeout(readTimeout);

        int writeTimeout = 
             writeconf.getInt(BlockIntegrityMonitor.BLOCKFIX_WRITE_TIMEOUT,
                HdfsConstants.WRITE_TIMEOUT);

        OutputStream baseStream = NetUtils.getOutputStream(sock, writeTimeout);
        DataOutputStream out =
            new DataOutputStream(new BufferedOutputStream(baseStream, 
                        FSConstants.SMALL_BUFFER_SIZE));

        boolean corruptChecksumOk = false;
        boolean chunkOffsetOK = false;
        boolean verifyChecksum = true;
        boolean transferToAllowed = false;
        try {
            LOG.info("Sending block " + block +
                    " from " + sock.getLocalSocketAddress().toString() +
                    " to " + sock.getRemoteSocketAddress().toString());
            BlockSender blockSender = 
                new BlockSender(namespaceId, block, blockSize, 0, blockSize,
                        corruptChecksumOk, chunkOffsetOK, verifyChecksum,
                        transferToAllowed, dataTransferVersion >= DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION,
                        new BlockWithChecksumFileReader.InputStreamWithChecksumFactory() {
                            @Override
                            public InputStream createStream(long offset) throws IOException {
                                // we are passing 0 as the offset above,
                                // so we can safely ignore
                                // the offset passed
                                return blockContents; 
                            }

                            @Override
                            public DataInputStream getChecksumStream() throws IOException {
                                return metaData;
                            }

                            @Override
                            public BlockDataFile.Reader getBlockDataFileReader()
                            throws IOException {
                            return BlockDataFile.getDummyDataFileFromFileChannel(
                                    blockContents.getChannel()).getReader(null);
                            }
                        });
            WriteBlockHeader header = new WriteBlockHeader(new VersionAndOpcode(
                        dataTransferVersion, DataTransferProtocol.OP_WRITE_BLOCK));
            header.set(namespaceId, block.getBlockId(), block.getGenerationStamp(),
                    0, false, true, new DatanodeInfo(), 0, null, "");
            header.writeVersionAndOpCode(out);
            header.write(out);
            blockSender.sendBlock(out, baseStream, null, progress);
            DataNode.LOG.info("Sent block " + block + " to " + localName);
        } finally {
            sock.close();
            out.close();
        }
    }
    public DataInputStream computeMetaData(Configuration conf, InputStream dataStream) 
        throws IOException {
        ByteArrayOutputStream mdOutBase = new ByteArrayOutputStream(1024*1024);
        DataOutputStream mdOut = new DataOutputStream(mdOutBase);

        // First, write out the version.
        mdOut.writeShort(FSDataset.FORMAT_VERSION_NON_INLINECHECKSUM);

        // Create a summer and write out its header.
        int bytesPerChecksum = conf.getInt("io.bytes.per.checksum", 512);
        DataChecksum sum =
            DataChecksum.newDataChecksum(DataChecksum.CHECKSUM_CRC32,
                    bytesPerChecksum);
        sum.writeHeader(mdOut);

        // Buffer to read in a chunk of data.
        byte[] buf = new byte[bytesPerChecksum];
        // Buffer to store the checksum bytes.
        byte[] chk = new byte[sum.getChecksumSize()];

        // Read data till we reach the end of the input stream.
        int bytesSinceFlush = 0;
        while (true) {
            // Read some bytes.
            int bytesRead = dataStream.read(buf, bytesSinceFlush,
                    bytesPerChecksum - bytesSinceFlush);
            if (bytesRead == -1) {
                if (bytesSinceFlush > 0) {
                    boolean reset = true;
                    sum.writeValue(chk, 0, reset); // This also resets the sum.
                    // Write the checksum to the stream.
                    mdOut.write(chk, 0, chk.length);
                    bytesSinceFlush = 0;
                }
                break;
            }
            // Update the checksum.
            sum.update(buf, bytesSinceFlush, bytesRead);
            bytesSinceFlush += bytesRead;

            // Flush the checksum if necessary.
            if (bytesSinceFlush == bytesPerChecksum) {
                boolean reset = true;
                sum.writeValue(chk, 0, reset); // This also resets the sum.
                // Write the checksum to the stream.
                mdOut.write(chk, 0, chk.length);
                bytesSinceFlush = 0;
            }
        }
        byte[] mdBytes = mdOutBase.toByteArray();
        return new DataInputStream(new ByteArrayInputStream(mdBytes));
    }
}       
