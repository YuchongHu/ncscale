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

package org.apache.hadoop.hdfs.server.datanode;

import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.raid.DRCCode;
import org.apache.hadoop.raid.ErasureCode;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.DataTransferThrottler;
import org.apache.hadoop.util.NativeDRC;
import org.apache.hadoop.util.ReflectionUtils;

import org.json.JSONException;
import org.json.JSONObject;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class GroupSender implements java.io.Closeable, FSConstants {

  // static properties
  public static final Log LOG = DataNode.LOG;
  static final Log ClientTraceLog = DataNode.ClientTraceLog;
  public static final int DEFAULT_MAX_BUFFER_SIZE = 1024*1024; 
  public static final String DECODING_MAX_BUFFER_SIZE_KEY =
      "hdfs.raid.strip.size";
  public static final String ERASURE_CODE_KEY_PREFIX = "hdfs.raid.erasure.code.";

  // read from input
  private int groupId;
  private int groupSize;
  private Map<Integer, LocatedBlockWithFileName> groupBlocks;
  private int[] blockIdxInStripe;  // here the blockIdx is in sequence
  private int outputUnitNum;
  private String codeId;
  private long toReadInGroup;
  private String clientTraceFmt;
  private boolean chunkOffsetOK;
  private boolean verifyChecksum;
  private boolean pktIncludeVersion = false;
  private boolean corruptChecksumOk;
  private String jsonStr;
  private int[] corruptArray;

  // for new interface
  private int[] dataArray = null;
  private int[] groupArray = null;
  private int[] nodeLenArray = null;
  private int[] groupLenArray = null;
  private int[] groupInputLen = null;
  private int gilidx = 0;

  private int stripSize;
  private long groupOutputLen;

  // calculate from input
  protected int maxBufSize;
  protected int bufSize;
  private Configuration conf;
  private DataChecksum checksum;
  private ByteBuffer cache;
  
  //related to code
  private JSONObject json;
  private ErasureCode code;
  private String erasureCodeClass;
  private int stripeSize;
  private int paritySize;
  private int groupNum;

  // other properties may need
  private int bytesPerChecksum = 512;
  private int checksumSize = 4;
  private int checksumType = 1;
  private long initialOffset = 0;
  private long offset = 0;
  private long endOffset = 0;
  private long seqno;
  private int blockSize;

  // queue
  ArrayBlockingQueue<byte[][]> readqueue;    // used for readthread pass data to calculate thread
  ArrayBlockingQueue<ByteBuffer> calculatequeue;  // used for calculate thread pass data to write thread

  long[] readremain;
  long[] calremain;

  private long read1=0, read2=0, readtime=0;
  private long syn1=0, syn2=0, syntime=0;
  private long cal1=0, cal2=0, caltime=0;
  private long take1=0, take2=0, taketime=0;
  private long writebuf1=0, writebuf2=0, writebuftime=0;
  private long send1=0, send2=0, sendtime=0;
  private long total1=0, total2=0, totaltime=0;


  private boolean transferToAllowed = true;
  private int packetVersion = DataTransferProtocol.PACKET_VERSION_CHECKSUM_INLINE;
  private boolean sentEntireByteRange;
  private DataTransferThrottler throttler;

  public GroupSender(int groupId, int groupSize, Map<Integer, LocatedBlockWithFileName> groupBlocks,
      int[] blocksIdxInStripe, int outputUnitNum, String codeId, long toReadInGroup,
      String clientTraceFmt, DataNode datanode, boolean chunkOffsetOK, boolean verifyChecksum,
      boolean pktIncludeVersion, boolean corruptChecksumOk, String jsonStr, int[] corruptArray)
      throws IOException {
    this.groupId = groupId;
    this.groupSize = groupSize;
    this.groupBlocks = groupBlocks;
     LOG.info("GroupSender.constructor. groupBlocks.size = " + groupBlocks.size());
    this.blockIdxInStripe = blocksIdxInStripe;
    this.outputUnitNum = outputUnitNum;
    this.codeId = codeId;
    this.toReadInGroup = toReadInGroup;
    this.jsonStr = jsonStr;
    this.corruptArray = corruptArray;

    this.clientTraceFmt = clientTraceFmt;
    this.chunkOffsetOK = chunkOffsetOK;
    this.verifyChecksum = verifyChecksum;
    this.pktIncludeVersion = pktIncludeVersion;
    this.corruptChecksumOk = corruptChecksumOk;

    this.conf = datanode.getConf();

    this.checksum = DataChecksum.newDataChecksum(checksumType, bytesPerChecksum);
    this.cache = null;
	this.blockSize = conf.getInt("dfs.block.size", 1048576);

    seqno = 0;
    endOffset = toReadInGroup;

	this.stripSize = conf.getInt("hdfs.raid.strip.size", 1024*1024);

    init();
    ((DRCCode)code).verify(corruptArray, dataArray, groupArray,
	    stripSize, nodeLenArray, groupLenArray);

    this.maxBufSize = groupLenArray[groupId];

    this.groupOutputLen = ((long)this.blockSize*groupLenArray[groupId])/(long)this.stripeSize;
    LOG.info("GroupSender.constructor.groupOutputLen = " + groupOutputLen);

  }

  public void init() throws IOException {
    try { 
      json = new JSONObject(jsonStr);
      this.stripeSize = json.getInt("stripe_length");
      this.paritySize = json.getInt("parity_length");
	  this.groupNum = json.getInt("group_num");
      this.erasureCodeClass = json.getString("erasure_code");
    } catch (JSONException e) {
      throw new IOException(e);
    }

    this.code = createErasureCode(conf);
	this.dataArray = new int[stripeSize + paritySize];
	this.groupArray = new int[groupNum];
    this.nodeLenArray = new int[stripeSize + paritySize];
    this.groupLenArray = new int[groupNum];
    this.groupInputLen = new int[groupBlocks.size()];
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

  long sendGroup(DataOutputStream out, OutputStream baseStream,
      DataTransferThrottler throttler) throws IOException {
    this.throttler = throttler;

    long totalRead = 0;
	long totalSend = 0;
    total1 = System.nanoTime();

    OutputStream streamForSendChunks = out;
    final long startTime = ClientTraceLog.isInfoEnabled() ? System.nanoTime() : 0;

    try {
      try {
        checksum.writeHeader(out);
		totalSend += 5;
        if(chunkOffsetOK) {
          out.writeLong(offset);
		  totalSend += 4;
        }
        out.flush();
      } catch(IOException e){
        throw e;
      }

      int maxChunksPerPacket = Math.max(1,
              (maxBufSize + bytesPerChecksum - 1)/bytesPerChecksum);

      int pktSize = SIZE_OF_INTEGER + getPacketHeaderLen();
      pktSize += (bytesPerChecksum + checksumSize) * maxChunksPerPacket;


      readqueue = new ArrayBlockingQueue<byte[][]>(300);
      calculatequeue = new ArrayBlockingQueue<ByteBuffer>(300);

	  // create input stream
      int streamNum = this.groupSize;
	  FSDataInputStream[] inputs = new FSDataInputStream[streamNum];
	  readremain = new long[streamNum];
	  try {
	    for(int i=0; i<this.groupSize; i++) {
		  int idx = blockIdxInStripe[i];
		  LocatedBlockWithFileName lb = groupBlocks.get(idx);
		  String fileName = lb.getFileName();
		  long startOffset = lb.getStartOffset();
		  Path filePath = new Path(fileName);
		  DistributedFileSystem fs = getDFS(filePath, this.conf);
		  FileStatus stat = fs.getFileStatus(filePath);

          long tmpnodeoutputlen = (this.blockSize*(long)nodeLenArray[idx])/(long)stripSize;
          DataNode.LOG.info("GroupSender.tmpnodeoutputlen = " + tmpnodeoutputlen);

          inputs[i] = fs.open(filePath, conf.getInt("io.file.buffer.size", 64 * 1024), codeId,
              jsonStr, idx, this.corruptArray, tmpnodeoutputlen);

          inputs[i].seek(startOffset);

          readremain[i] = Math.min(
              (this.blockSize*(long)nodeLenArray[idx])/(long)stripSize,
              (stat.getLen()-startOffset)*(long)nodeLenArray[idx]/(long)stripSize);

          groupInputLen[gilidx++] = nodeLenArray[idx];
		}
	  } catch(IOException e) {
	    DataNode.LOG.info("GroupSender.create input stream exception");
	  }


	  // create ReadThread
	  ThreadFactory readFactory = new ThreadFactoryBuilder().setNameFormat("parallel-rack-read-pool-%d").build();;
	  ExecutorService readPool =  Executors.newFixedThreadPool(streamNum, readFactory);
	  Thread readThread = new ReadThread(readqueue, readPool, inputs, readremain);
	  readThread.start();

	  // create CalThread
	  calremain = new long[outputUnitNum];
	  for(int i=0; i<outputUnitNum; i++) {
	    calremain[i] = groupOutputLen;
	  }
	  Thread calThread = new CalThread(readqueue, calculatequeue, this.outputUnitNum, groupLenArray[groupId], 
	      calremain, this.codeId, this.corruptArray, this.groupSize, this.groupId, this.code);
      calThread.start();
     
      ByteBuffer pktBuf = ByteBuffer.allocate(pktSize);

      while (endOffset > offset) {
        long havesent = sendChunks(pktBuf, maxChunksPerPacket, streamForSendChunks, calculatequeue);
        offset += havesent;
        totalRead += havesent + ((havesent + bytesPerChecksum -1)/bytesPerChecksum * checksumSize);
        seqno++;
		totalSend += pktSize;
      }

      try {
        // send empty packet
        sendChunks(pktBuf, maxChunksPerPacket, streamForSendChunks, calculatequeue);
        out.flush();
      } catch(IOException e) {
        throw e;
      }
      sentEntireByteRange = true;
      
      // output sendchunks
    } catch (RuntimeException e) {
      throw new IOException("unexpected runtime exception", e);
    } finally {
      if(clientTraceFmt != null) {
        final long endTime = System.nanoTime();
        ClientTraceLog.info(String.format(clientTraceFmt, totalRead, initialOffset, endTime - startTime));
      }
      close();
      }
    total2 = System.nanoTime();
	LOG.info("GroupSender.final.readtime = " + readtime + " ns");
    LOG.info("GroupSender.final.syntime = " + syntime + " ns");
	LOG.info("GroupSender.final.caltime = " + caltime + " ns");
	LOG.info("GroupSender.final.taketime = " + taketime + " ns");
	LOG.info("GroupSender.final.writebuftime = " + writebuftime + " ns");
	LOG.info("GroupSender.final.sendtime = " + sendtime + " ns");
    LOG.info("GroupSender.final.totaltime = " + (total2 - total1) + " ns");
	LOG.info("GroupSender.final.totalSend = " + totalSend + " ns");
    return totalRead;
  }

  private int sendChunks(ByteBuffer pkt, int maxChunks, OutputStream out, ArrayBlockingQueue<ByteBuffer> q)
      throws IOException {
    int datalen = (int)Math.min((endOffset - offset),
        (((long)bytesPerChecksum)*((long)maxChunks)));
    int numChunks = (datalen + bytesPerChecksum - 1)/bytesPerChecksum;
    int packetLen = datalen + numChunks * checksumSize + 4;
    int lenWithChecksum = datalen + numChunks * checksumSize;

    

    pkt.clear();
    //packet header
    pkt.putInt(packetLen);
    if(pktIncludeVersion) {
      pkt.putInt(packetVersion);
    }
    pkt.putLong(offset);
    pkt.putLong(seqno);
    pkt.put((byte)((offset + datalen >= endOffset) ? 1 : 0));
    pkt.putInt(datalen);

    int dataWithChecksumOff = pkt.position();
    byte[] buf = pkt.array();

    int lxlcount=0;

    if(cache == null) {
      try {
		take1 = System.nanoTime();
        cache = q.take();
		take2 = System.nanoTime();
		taketime += take2-take1;
      } catch(InterruptedException e) {
        LOG.info("GroupSender.sendChunks.interruptedexception1");
      }
    }

    int r = cache.remaining();
    int taken = 0;
    while(r < datalen) {
      int zeronumChunks = (r + bytesPerChecksum - 1)/bytesPerChecksum;
	  writebuf1=System.nanoTime();
      writeIntoBuffer(cache, buf, dataWithChecksumOff + taken, r - taken, zeronumChunks, r - taken);
	  writebuf2=System.nanoTime();
	  writebuftime+=writebuf2-writebuf1;
      try {
		take1 = System.nanoTime();
        cache = q.take();
		take2 = System.nanoTime();
		taketime += take2-take1;
        lxlcount++;
      } catch (InterruptedException e) {
        LOG.info("GroupSender.sendChunks.interruptedexception2");
      }
      taken = r;
      r += cache.remaining();
    }
	writebuf1=System.nanoTime();
    writeIntoBuffer(cache, buf, dataWithChecksumOff + taken, lenWithChecksum - taken, numChunks, datalen - taken);
	writebuf2=System.nanoTime();
	writebuftime+=writebuf2-writebuf1;



    //send
    try {
	  send1=System.nanoTime();
      out.write(buf, 0, (dataWithChecksumOff + datalen + numChunks * checksumSize));
	  send2=System.nanoTime();
	  sendtime += send2-send1;
    } catch (IOException e) {
      throw e;
    }

    if (throttler != null) {
      throttler.throttle(packetLen);
    }

    return datalen;
  }

  private void writeIntoBuffer(ByteBuffer cacheBuf, byte[] pktBuf, int destOff,
      int toWrite, int numChunks, int dataLen) throws IOException {
    int startOff = destOff;
    int remain = dataLen;
    for(int i=0; i<numChunks; i++) {
      assert remain > 0;
      int lenToRead = (remain > bytesPerChecksum) ? bytesPerChecksum : remain;
      cacheBuf.get(pktBuf, startOff, lenToRead);
      checksum.reset();
      checksum.update(pktBuf, startOff, lenToRead);
      startOff += lenToRead;
      checksum.writeValue(pktBuf, startOff, false);
      startOff += checksumSize;
      remain -= lenToRead;
    }
  }


  private int getPacketHeaderLen() {
    return DataNode.getPacketHeaderLen(pktIncludeVersion);
  }

  public void close() throws IOException {
  }

  public static class ReadResult {
    public byte[][] readBufs;
    public int[] numRead;
    public IOException[] ioExceptions;
    ReadResult(int numStreams, int bufSize) {
      this.readBufs = new byte[numStreams][];
      this.numRead = new int[numStreams];
      for (int i = 0; i < readBufs.length; i++) {
        this.readBufs[i] = new byte[bufSize];
        numRead[i] = 0;
      }
      this.ioExceptions = new IOException[readBufs.length];
    }
    
    void setException(int idx, Exception e) {
      synchronized(ioExceptions) {
        if (e == null) {
          ioExceptions[idx] = null;
          return;
        }
        LOG.info("Set Exception: " + e.getMessage(), e);
        if (e instanceof IOException) {
          ioExceptions[idx] = (IOException) e;
        } else {
          ioExceptions[idx] = new IOException(e);
        }
      }
    }
    
    IOException getException() {
      synchronized(ioExceptions) {
        for (int i = 0; i < ioExceptions.length; i++) {
          if (ioExceptions[i] != null) {
            return ioExceptions[i];
          }
        }
      }
      return null;
    }
    
    List<Integer> getErrorIdx() {
      List<Integer> errorIdxs = new ArrayList<Integer>();
      synchronized(ioExceptions) {
        for (int i = 0; i< ioExceptions.length; i++) {
          if (ioExceptions[i] != null) {
            errorIdxs.add(i);
          }
        }
      }
      return errorIdxs;
    }
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
  

  class ReadThread extends Thread {
    ArrayBlockingQueue<byte[][]> queue;
	ExecutorService readPool;
    FSDataInputStream[] inputs;
    long[] remainingbytes;
    long[] groupretime;

    int threadnum;
    Semaphore slots;
	boolean running = true;

    byte[][] dataBuf = null;

    ReadThread(ArrayBlockingQueue<byte[][]> q, ExecutorService pool,
        FSDataInputStream[] in, long[] r) {
      queue = q;
	  readPool = pool;
	  inputs = in;
      remainingbytes = r;

	  threadnum = in.length;      
	  slots = new Semaphore(threadnum);
	  groupretime = new long[threadnum];
	  for(int i=0; i<threadnum; i++) {
	    groupretime[i] = 0;
      }

    }
    
    public void run() {
	  read1 = System.nanoTime();
      LOG.info("GroupSender.ReadThread.run");
      while(running) {
        dataBuf = new byte[groupSize][];
        for (int i=0; i<groupSize; i++) {
          int idx = blockIdxInStripe[i];
          int nodeencodelen = nodeLenArray[idx];
          dataBuf[i] = new byte[nodeencodelen];
        }
        
        for(int i=0; i<threadnum;) {
          try {
            boolean acquired = slots.tryAcquire(1, 100, TimeUnit.MINUTES);
            if(acquired) {
              int idx = blockIdxInStripe[i];
              int nodeencodelen = nodeLenArray[idx];
              readPool.execute(new ReadOperation(nodeencodelen, i, dataBuf[i], inputs[i], slots, groupretime));
              i++;
            }
          } catch(Exception e) {
            LOG.info("GroupSender.ReadThread.run.for exception");
          }
        }
        syn1=System.nanoTime();
        while(true) {
          try {
            boolean acquired = slots.tryAcquire(groupSize, 100, TimeUnit.MINUTES);
            if(acquired) {
              slots.release(groupSize);
              break;
            }
          } catch(Exception e) {
            LOG.info("GroupSender.ReadThread.run.while exception");
          } 
        }
        syn2=System.nanoTime();
        syntime+=syn2-syn1;

		// get the read result and put into the readqueue
	    try {
          queue.put(dataBuf);
	    } catch (InterruptedException ie) {
	      DataNode.LOG.info("GroupSender.ReadThread.run.ReadThread.put exception");
	    }

        boolean finish = true;
		for(int i=0; i<threadnum; i++) {
          int idx = blockIdxInStripe[i];
          int nodeencodelen = nodeLenArray[idx];
		  remainingbytes[i] -= nodeencodelen;
		  if(remainingbytes[i]>0){
		    finish = false;
		  }
		}
        if (finish == true) {
          running = false;
        }
      }
	  for(int i=0; i<groupSize; i++) {
	    DataNode.LOG.info("GroupSender.ReadThread.readtime " + i
	      + " = " + groupretime[i]);
	  }
      readPool.shutdown();
	  read2 = System.nanoTime();
	  readtime += read2-read1;
	}
  }

  public class ReadOperation implements Runnable {
    int toRead;
    byte[] b;
    FSDataInputStream input;
    Semaphore slots;
    int idx;

	long[] rotime;

    ReadOperation(int to, int i, byte[] buf, FSDataInputStream in, Semaphore s, long[] time) {
      toRead = to;
      idx = i;
      b = buf;
      input = in;
      slots = s;
	  rotime = time;
    }

    public void run() {
	  long ro1=System.nanoTime();
      try {
        if(input == null) {
          Arrays.fill(b, (byte)0);
        } else {
          input.read(b, 0, toRead);
        }
      } catch(IOException e) {
        LOG.info("GroupSender.ReadOperation.run IOException");
      } finally {
        slots.release();
      }
	  long ro2=System.nanoTime();
	  long reotime = ro2-ro1;
	  rotime[idx] += reotime;
    }
  }

  public class CalThread extends Thread {
    ArrayBlockingQueue<byte[][]> rqueue;
	ArrayBlockingQueue<ByteBuffer> cqueue;
	int outnum;
    int toRead;
	long[] remainingbytes;
	String codeid;
	int[] carray;
	int gsize;
	int gid;
	ErasureCode ecd;

	boolean running = true;
	byte[][] calinput = null;

	CalThread(ArrayBlockingQueue<byte[][]> readq, ArrayBlockingQueue<ByteBuffer> calq,
	    int on, int tr, long[] r, String cid, int[] corruptArray, int	groupSize,
		int groupId, ErasureCode code) {
	  rqueue = readq;
	  cqueue = calq;
	  outnum = on;
	  toRead = tr;
	  remainingbytes = r;
	  codeid = cid;
	  carray = corruptArray;
	  gsize = groupSize;
	  gid = groupId;
	  ecd = code;
	}

	public void run() {
	  //cal1 = System.nanoTime();
      while(running) {
	    if(calinput == null) {
	      try {
	        calinput = rqueue.take();
          } catch (InterruptedException e) {
            DataNode.LOG.info("GroupSender.CalThread.run.CalThread.take exception");
          }
        }

        byte[][] caloutput = new byte[outnum][];
        for(int i=0; i<outnum; i++) {
          caloutput[i] = new byte[toRead];
        }


        boolean res = false;
        if(codeid.equals("drc")) {
          try {
	        cal1 = System.nanoTime();
            res = ((DRCCode)ecd).encodeGroup(gid, carray, calinput,
                gsize, groupInputLen, caloutput, outnum,
				groupLenArray[groupId]);
	        cal2 = System.nanoTime();
	        caltime += cal2-cal1;
          } catch(IOException e) {
            DataNode.LOG.info("GroupSender.CalThread.cal exception");
          }
        }
        if(res == false) {
          DataNode.LOG.info("GroupSender.CalThread.cal false");
        }

        // to put data in queue
        try {
          byte[] toput = new byte[outnum * toRead];
          int destlen = 0;
          for(int i=0; i<outnum; i++) {
            System.arraycopy(caloutput[i], 0, toput, destlen, toRead);
            destlen += toRead;
          }

          ByteBuffer tmp = ByteBuffer.allocate(toRead * outnum);
          tmp.put(toput);
          tmp.rewind();
          cqueue.put(tmp);
          tmp = null;
        } catch(InterruptedException e) {
          LOG.info("GroupSender.CalThread.run.inqueue exception");
        }

        calinput = null;
        
        boolean finish = true;
        for(int i=0; i<outnum; i++) {
          remainingbytes[i] -= toRead;
          if(remainingbytes[i] > 0) {
            finish = false;
          }
        }

        if(finish == true) {
          running = false;
        }
      }
    }
  }
}
