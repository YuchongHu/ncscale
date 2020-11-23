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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;


import org.apache.commons.logging.Log;
import org.apache.hadoop.fs.FSDataNodeReadProfilingData;
import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.net.SocketOutputStream;
import org.apache.hadoop.raid.DRCCode;
import org.apache.hadoop.raid.ErasureCode;
import org.apache.hadoop.util.ChecksumUtil;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.DataTransferThrottler;
import org.apache.hadoop.util.NativeDRC;
import org.apache.hadoop.util.ReflectionUtils;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * DRCBlockSender
 */

public class DRCBlockSender implements java.io.Closeable, FSConstants {

  //TODO: should claim native method to do the network coding
  //TODO: should load native library
  public static final String ERASURE_CODE_KEY_PREFIX = "hdfs.raid.erasure.code.";

  public static final Log LOG = DataNode.LOG;
  static final Log ClientTraceLog = DataNode.ClientTraceLog;
  
  private Configuration conf;
  private ByteBuffer cache;
  private DataChecksum checksum;
  
  private long offset;        // encoded offset now read  
  private long realOffset;    // real offset now read in block
  private long endOffset;     // encoded block offset 
  private long realEndOffset; // offset in hdfs block
  private long calEndOffset;
  private long startOffset;   // encoded start offset
  private long realStartOffset; // startoffset in block
  private long blockLength; // real block length
  private int bytesPerChecksum;
  private int checksumSize;
  private boolean chunkOffsetOK; // whether to send the chunkOffset in the socket data stream
  private long seqno;
  private boolean corruptChecksumOk;

  private boolean transferToAllowed = true;
  private boolean pktIncludeVersion = false;
  private int packetVersion;

  private boolean verifyChecksum; // if true, check the checksum when reading data
  private boolean sentEntireByteRange;
  private DataTransferThrottler throttler;
  private String clientTraceFmt;
  private DatanodeBlockReader blockReader;
  private int maxBufSize = 4096;

  private BlockCrcUpdater crcUpdater = null;

  final ReplicaToRead replicaToRead;

  private static final int MIN_BUFFER_WITH_TRANSFERTO = 64 * 1024;
  int drcStripDataSize;
  private String jsonStr;

  // related to json
  private JSONObject json;
  private ErasureCode code;
  private String erasureCodeClass;
  private int stripeSize;
  private int paritySize;
  private int groupNum;

  // related to new interface
  private int nodeId; // now not of use TODO: should init in constructor
  private int[] corruptArray = null; // now not of use TODO:init
  private int[] dataArray = null;
  private int[] groupArray = null;
  private int[] nodeLenArray = null;
  private int[] groupLenArray = null;

  private int nodeEncodeLen;

  //queue
  ArrayBlockingQueue<byte[]> readqueue;
  ArrayBlockingQueue<ByteBuffer> calculatequeue;

  private long send1=0, send2=0, sendtime=0;
  private long read1=0, read2=0, readtime=0;
  private long rt1=0, rt2=0, rttime=0;
  private long cal1=0, cal2=0, caltime=0;
  private long take1=0, take2=0, taketime=0;
  private long writebuf1=0, writebuf2=0, writebuftime=0;
  private long sd1=0, sd2=0, sdtime=0;
  private long sdc1=0, sdc2=0, sdctime=0;
  private long total1=0, total2=0, totaltime=0;


  DRCBlockSender( int namespaceId, Block block, long startOffset, long length,
      boolean ignoreChecksum, boolean corruptChecksumOk, boolean chunkOffsetOK,
      boolean verifyChecksum, boolean pktIncludeVersion, boolean forceOldPktVersion,
      DataNode datanode, String clientTraceFmt, String jsonstr,
	  Configuration c, int nodeid, int[] corruptarray) throws IOException {
    
    // here length is the encoded length

	this.nodeId = nodeid;
	this.corruptArray = corruptarray;

    this.conf = c;
    replicaToRead = datanode.data.getReplicaToRead(namespaceId, block);
    if(replicaToRead == null) {
      throw new IOException("can't find block " + block + " in volumeMap");
    }

    long blockLength = replicaToRead.getBytesVisible(); //real block length
    boolean transferToAllowed = datanode.transferToAllowed;

    DatanodeBlockReader.BlockInputStreamFactory streamFactory =
        new DatanodeBlockReader.BlockInputStreamFactory(
          namespaceId, block, replicaToRead, datanode, datanode.data, ignoreChecksum,
          verifyChecksum, corruptChecksumOk);
    blockReader = streamFactory.getBlockReader();

    boolean brtmp = (blockReader instanceof BlockInlineChecksumReader);

    this.drcStripDataSize = (datanode.getConf()).getInt("hdfs.raid.strip.size", 1024*1024);


    initialize(namespaceId, block, blockLength, startOffset, length,
        corruptChecksumOk, chunkOffsetOK, verifyChecksum, transferToAllowed,
        datanode.updateBlockCrcWhenRead, pktIncludeVersion, forceOldPktVersion,
        streamFactory, clientTraceFmt, jsonstr);

  }

  public ErasureCode createErasureCode(Configuration conf) {
    Class<?> erasureCode = null;
	
	try {
	  erasureCode = conf.getClass(ERASURE_CODE_KEY_PREFIX +
	      "drc", conf.getClassByName(this.erasureCodeClass));
    } catch (ClassNotFoundException e) {
	  throw new RuntimeException(e);
	}
	
	ErasureCode code =
	    (ErasureCode)ReflectionUtils.newInstance(erasureCode, conf);
	code.init(this.stripeSize, this.paritySize,	this.jsonStr);
	return code;
  }

  private void initialize(int namespaceId, Block block, long blockLength,
      long startOffset, long length, boolean corruptChecksumOk,
      boolean chunkOffsetOK, boolean verifyChecksum, boolean transferToAllowed,
      boolean allowUpdateBlocrCrc, boolean pktIncludeVersion, boolean forceOldPktVersion,
      BlockWithChecksumFileReader.InputStreamWithChecksumFactory streamFactory,
      String clientTraceFmt, String jsonstr) throws IOException {
	
	// initialize native code
	this.jsonStr = jsonstr;
	try {
	  json = new JSONObject(jsonStr);
	  this.stripeSize = json.getInt("stripe_length");
	  this.paritySize = json.getInt("parity_length");
	  this.groupNum = json.getInt("group_num");
	  this.erasureCodeClass = json.getString("erasure_code");
	} catch (JSONException e) {
	  throw new IOException(e);
	}
	this.code = createErasureCode(this.conf);

	// verify
    this.dataArray = new int[stripeSize + paritySize];
	this.groupArray = new int[groupNum];
    this.nodeLenArray = new int[stripeSize + paritySize];
    this.groupLenArray = new int[groupNum];
    ((DRCCode)code).verify(corruptArray, dataArray, groupArray,
	    drcStripDataSize, nodeLenArray, groupLenArray);
	
    this.nodeEncodeLen = nodeLenArray[nodeId];
    this.maxBufSize = this.nodeEncodeLen;

    try {
      this.cache = null;
      this.chunkOffsetOK = chunkOffsetOK;
      this.verifyChecksum = verifyChecksum;
      this.blockLength = blockLength;
      this.transferToAllowed = transferToAllowed;
      this.clientTraceFmt = clientTraceFmt;
      this.pktIncludeVersion = pktIncludeVersion;
      this.startOffset = startOffset;
      this.corruptChecksumOk = corruptChecksumOk;

      if (this.pktIncludeVersion && (!forceOldPktVersion)) {
        this.packetVersion = blockReader.getPreferredPacketVersion();
        boolean inline = (packetVersion == DataTransferProtocol.PACKET_VERSION_CHECKSUM_INLINE);
      } else {
        this.packetVersion = DataTransferProtocol.PACKET_VERSION_CHECKSUM_FIRST;
      }

      checksum = blockReader.getChecksumToSend(blockLength);

      bytesPerChecksum = blockReader.getBytesPerChecksum();
      checksumSize = blockReader.getChecksumSize();

      if (length < 0) {
        length = blockLength; //TODO: should calculate the encoded length rather than just the blocklength
      }

      //TODO: transfer bewteen the real length and encoded length
      realEndOffset = blockLength;  // realEndOffset: the real endoffset int hdfs data block
	  calEndOffset = realEndOffset;
      endOffset = (realEndOffset*(long)nodeEncodeLen)/(long)drcStripDataSize;;    // endOffset: the encoded offset of the block, should calculate when nativemethod is added
 
      realStartOffset = startOffset; // 

      long realLength = blockLength;
    
      if (realStartOffset < 0 || realStartOffset > realEndOffset
          || (realLength + realStartOffset ) > realEndOffset) {
        String msg = " Offset " + startOffset + " and length " + length
            + " don't match block " + block + " ( blockLen " + realEndOffset
            + " )";
        LOG.warn(":sendBlock() : " + msg);
        throw new IOException(msg);
      }

      //TODO: deal with the packet size if possible
      offset = startOffset - (startOffset % bytesPerChecksum); //TODO: MO encoded drcpacketdatasize
      realOffset = realStartOffset -(realStartOffset % bytesPerChecksum); //TODO: MO real drcpacketdatasize
      

      if (length >= 0) {
        long tmpLen = startOffset + length;
        if(tmpLen % bytesPerChecksum != 0) {  //TODO: use encoded drcpacketsize rather than bytesPerChecksum
          tmpLen += (bytesPerChecksum - tmpLen % bytesPerChecksum);
        }
        if(tmpLen < endOffset) {
          endOffset = tmpLen;
        }
      }

      if (allowUpdateBlocrCrc &&
          (!transferToAllowed || verifyChecksum)
          && startOffset == 0
          && length >= blockLength
          && replicaToRead != null
          && !replicaToRead.hasBlockCrcInfo()
          && replicaToRead.isFinalized()
          && replicaToRead instanceof DatanodeBlockInfo
          && checksumSize == DataChecksum.DEFAULT_CHECKSUM_SIZE
          && checksum != null
          && (checksum.getChecksumType() == DataChecksum.CHECKSUM_CRC32 || checksum
              .getChecksumType() == DataChecksum.CHECKSUM_CRC32C)) {
        crcUpdater = new BlockCrcUpdater(bytesPerChecksum, true);
      }

      seqno = 0;
      blockReader.initialize(realOffset, blockLength);
    } catch (IOException ioe) {
      IOUtils.closeStream(this);
      throw ioe;
    }


  }

  long sendBlock(DataOutputStream out, OutputStream baseStream,
      DataTransferThrottler throttler) throws IOException {
    if(out == null) {
      throw new IOException("outstream is null");
    }

    this.throttler = throttler;

    long initialOffset = offset;
    long totalRead = 0;
	long totalSend = 0;
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
      } catch(IOException e) {
        throw e;
      }
      

      int maxChunksPerPacket = 1;
      int pktSize = SIZE_OF_INTEGER + getPacketHeaderLen();  //TODO: pktsize may change

      if (transferToAllowed && !verifyChecksum &&
          baseStream instanceof SocketOutputStream &&
          blockReader.prepareTransferTo()) {
        streamForSendChunks = baseStream;
        maxChunksPerPacket = (Math.max(BUFFER_SIZE,
                                       MIN_BUFFER_WITH_TRANSFERTO)
                              + bytesPerChecksum - 1)/bytesPerChecksum;

        pktSize += (bytesPerChecksum + checksumSize) * maxChunksPerPacket;
      } else {
        maxChunksPerPacket = Math.max(1,
            (maxBufSize + bytesPerChecksum - 1)/bytesPerChecksum);
        pktSize += (bytesPerChecksum + checksumSize) * maxChunksPerPacket;
      }

      DataNode.LOG.info("BUFFER_SIZE = " + BUFFER_SIZE);
      DataNode.LOG.info("MIN_BUFFER_WITH_TRANSFERTO = " + MIN_BUFFER_WITH_TRANSFERTO);
      DataNode.LOG.info("maxChunksPerPacket = " + maxChunksPerPacket);

	  readqueue = new ArrayBlockingQueue<byte[]>(300);
	  calculatequeue = new ArrayBlockingQueue<ByteBuffer>(300);
   
      //Encode Thread
      ReadThread readThread = new ReadThread(readqueue, this.code);
      readThread.start();
      
	  // calculate Thread
	  CalThread calThread = new CalThread(readqueue, calculatequeue,
	      calEndOffset, "drc", this.code);
      calThread.start();

      ByteBuffer pktBuf = ByteBuffer.allocate(pktSize);
      DataNode.LOG.info("DRCBlockSender.sendBlock.pktSize = " + pktSize);

      //output sendchunks
      sd1=System.nanoTime();
      DataNode.LOG.info("DRCBlockSender.sendBlock.endOffset = " + endOffset);
      DataNode.LOG.info("DRCBlockSender.sendBlock.offset = " + offset);
      while(endOffset > offset) {
        sdc1=System.nanoTime();
        long len = sendChunks(pktBuf, maxChunksPerPacket, streamForSendChunks, calculatequeue);
        sdc2=System.nanoTime();
        sdctime += sdc2-sdc1;
        offset += len;
        totalRead += len + ((len + bytesPerChecksum - 1)/bytesPerChecksum*checksumSize);
        seqno++;
		totalSend += pktSize;
      }

      try {
        //send empty packet
        sendChunks(pktBuf, maxChunksPerPacket, streamForSendChunks, calculatequeue);
        out.flush();
      } catch (IOException e) {
        throw e;
      }
      sd2=System.nanoTime();
      sdtime+=sd2-sd1;

      sentEntireByteRange = true;
    } catch (RuntimeException e) {
      throw new IOException("unexpected runtime exception", e);
    }finally {
      final long endTime = System.nanoTime();;
      if (clientTraceFmt != null) {
        ClientTraceLog.info(String.format(clientTraceFmt, totalRead, initialOffset, endTime - startTime));
      }
      close();
	  totaltime = endTime-startTime;
    }
	DataNode.LOG.info("DRCBlockSender.final.readtime = " + readtime);
    DataNode.LOG.info("DRCBlockSender.final.readthread start = " + rt1);
    DataNode.LOG.info("DRCBlockSender.final.readthread time = " + rttime);
	DataNode.LOG.info("DRCBlockSender.final.caltime = " + caltime);
	DataNode.LOG.info("DRCBlockSender.final.taketime = " + taketime);
	DataNode.LOG.info("DRCBlockSender.final.writebuftime = " + writebuftime);
	DataNode.LOG.info("DRCBlockSender.final.send fun time = " + sendtime);
    DataNode.LOG.info("DRCBlockSender.final.sd start = " + sd1);
    DataNode.LOG.info("DRCBlockSender.final.sendtime = " + sdtime);
	DataNode.LOG.info("DRCBlockSender.final.totaltime = " +	totaltime);
	DataNode.LOG.info("DRCBlockSender.final.totalsend = " +	totalSend);

    if (crcUpdater != null && crcUpdater.isCrcValid(realOffset)
        && !replicaToRead.hasBlockCrcInfo()) {
      int blockCrcOffset = crcUpdater.getBlockCrcOffset();
      int blockCrc = crcUpdater.getBlockCrc();
      if (DataNode.LOG.isDebugEnabled()) {
        DataNode.LOG.debug("Setting block CRC " + replicaToRead + " offset "
            + blockCrcOffset + " CRC " + blockCrc);
      }
      ((DatanodeBlockInfo) replicaToRead).setBlockCrc(blockCrcOffset, blockCrc);
    }

    return totalRead;
  }

  private int sendChunks(ByteBuffer pkt, int maxChunks, OutputStream out, BlockingQueue<ByteBuffer> q)
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


    if(cache == null)
      try {
		take1 = System.nanoTime();
        cache = q.take();
		take2 = System.nanoTime();
		taketime += take2-take1;
      } catch (InterruptedException e) {
      }

    int r = cache.remaining();
    int taken = 0;
    while(r < datalen) {
      int zeronumchunks = (r + bytesPerChecksum - 1)/bytesPerChecksum;
	  writebuf1=System.nanoTime();
      writeIntoBuffer(cache, buf, dataWithChecksumOff + taken, r - taken, zeronumchunks, r - taken);
	  writebuf2=System.nanoTime();
	  writebuftime+=writebuf2-writebuf1;

      try {
		take1 = System.nanoTime();
        cache = q.take();
		take2 = System.nanoTime();
		taketime = take2-take1;
      } catch (InterruptedException e) {
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
	  send1 = System.nanoTime();
      out.write(buf, 0, (dataWithChecksumOff + datalen + numChunks * checksumSize));
	  send2 = System.nanoTime();
	  sendtime+=send2-send1;
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
      assert remain>0;

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

  private void sendPackage(OutputStream out, ByteBuffer cacheBuf, int numChunks, 
          int dataLen) throws IOException {
    int remain = dataLen;
    for (int i=0; i<numChunks; i++) {
        assert remain>0;
        int lenToRead = (remain > bytesPerChecksum) ? bytesPerChecksum : remain;
        byte[] datatmp = new byte[lenToRead+checksumSize];
        cacheBuf.get(datatmp, 0, lenToRead);
        checksum.reset();
        checksum.update(datatmp, lenToRead, lenToRead);
        checksum.writeValue(datatmp,lenToRead, false);
        out.write(datatmp,0,lenToRead+checksumSize);
        remain-=lenToRead;
    }
  }

    

  private int getPacketHeaderLen() {
    return DataNode.getPacketHeaderLen(pktIncludeVersion);
  }

  public void fadviseStream(int advise, long offset, long len)
      throws IOException {
    blockReader.fadviseStream(advise, offset, len);
  }

  boolean didSendEntireByteRange() {
    return sentEntireByteRange;
  }

  public ReplicaToRead getReplicaToRead() {
    return replicaToRead;
  }

  /**
   * close opened files
   */
  public void close() throws IOException {
 
    if(blockReader != null) {
      blockReader.close();
    }

  }

  public class ReadThread extends Thread {
    
    private final BlockingQueue<byte[]> queue;
//    private final BlockingQueue<ByteBuffer> queue;
    byte[] dataBuf = null;
    byte[] checksumBuf = null;
	byte[] codeBuf = null;
    int chunknum;
    int drcStripTotalSize;
    FSDataNodeReadProfilingData dnData;
	ErasureCode ecd;

    ReadThread(BlockingQueue<byte[]> readq,
	    ErasureCode ec) {
      queue = readq;
      int chunknum = (drcStripDataSize + bytesPerChecksum - 1)/bytesPerChecksum;
      drcStripTotalSize = drcStripDataSize + chunknum * checksumSize;
      checksumBuf = new byte[chunknum * checksumSize];
	  codeBuf = new byte[nodeEncodeLen];
      dnData = blockReader.getProfilingData();
	  ecd = ec;
    }


    public void run() {
      rt1=System.nanoTime(); 
      int iteration = 0;
      while(realEndOffset > realOffset) {
        boolean lastDataPacket = realOffset + drcStripDataSize == realEndOffset;
        long offsetInFile = BlockInlineChecksumReader
            .getPosFromBlockOffset(realOffset, bytesPerChecksum, checksumSize);
        dataBuf = new byte[drcStripDataSize];
		if(packetVersion == DataTransferProtocol.PACKET_VERSION_CHECKSUM_FIRST) {
        } else if(packetVersion == DataTransferProtocol.PACKET_VERSION_CHECKSUM_INLINE) {
          if (blockReader instanceof BlockInlineChecksumReader) {
            try {
              read1=System.nanoTime();
              int tempread = ((BlockInlineChecksumReader)blockReader).readDRCStripFully(
                  dataBuf, 0, drcStripDataSize, offsetInFile, checksumBuf, 0, bytesPerChecksum, checksumSize, true);
			  read2=System.nanoTime();
			  readtime+=read2-read1;
            } catch (IOException e) {
              e.printStackTrace(); // TODO: how to elegently deal with the IOException
            }
            // if verifyChecksum
            if(verifyChecksum && !corruptChecksumOk) {
              try {
                ((BlockInlineChecksumReader)blockReader).verifyDRCStripChecksum(
                    drcStripDataSize, chunknum, bytesPerChecksum, checksumSize,
                    dataBuf, (int)realOffset, checksumBuf);
              } catch (IOException e) {
                e.printStackTrace();  // TODO: how to elegently deal with the IOException
              }
            }


			try {
			  queue.put(dataBuf);
			} catch(InterruptedException ex) {
			  DataNode.LOG.info("DRCBlockSender.ReadThread.queue.put.error");
			}
          } 
        } else {
        }

        realOffset += drcStripDataSize;
        iteration++;
      } 
      rt2=System.nanoTime();
      rttime+=rt2-rt1;
    }
  }

  public class CalThread extends Thread {
    ArrayBlockingQueue<byte[]> rqueue;
	ArrayBlockingQueue<ByteBuffer> cqueue;
	long remainingbytes;
	String codeid;
	ErasureCode ecd;
	boolean running = true;
	byte[] calinput = null;

	CalThread(ArrayBlockingQueue<byte[]> readq, ArrayBlockingQueue<ByteBuffer> calq,
	    long remain, String cid, ErasureCode ec) {
      rqueue = readq;
	  cqueue = calq;
	  remainingbytes = remain;
	  codeid = cid;
	  ecd = ec;
	}

	public void run() {
	  while(running) {
	    if(calinput == null) {
		  try {
		    calinput = rqueue.take();
		  } catch(InterruptedException e) {
		    DataNode.LOG.info("DRCBlockSender.CalThread.run.take error");
		  }
		}

		byte[] caloutput = new byte[nodeEncodeLen];
//		DataNode.LOG.info("DRCBlockSender.CalThread.caloutput len = " + caloutput.length);
		boolean res = false;
		if(codeid.equals("drc")) {
		  try {
			cal1=System.nanoTime();
		    res = ((DRCCode)ecd).encodeBlock(nodeId, corruptArray,
			    calinput, drcStripDataSize, caloutput, nodeEncodeLen);
			cal2=System.nanoTime();
			caltime+=cal2-cal1;
          } catch(IOException e) {
		    DataNode.LOG.info("DRCBlockSender.CalThread.run.cal.error");
		  }
		}




		// to put encoded data in calqueue
		try {
		  ByteBuffer tmp = ByteBuffer.allocate(nodeEncodeLen);
		  tmp.put(caloutput);
		  tmp.rewind();
		  cqueue.put(tmp);
		  tmp = null;
		} catch(InterruptedException e) {
		  LOG.info("DRCBlockSender.CalThread.run.putqueue.error");
		}

	    calinput = null;
		remainingbytes -= drcStripDataSize;
        if(remainingbytes >0 ) {
          running = true;
		} else {
		  running = false;
		}
	  }
	}
  }
}
