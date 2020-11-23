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

package org.apache.hadoop.hdfs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSClientReadProfilingData;
import org.apache.hadoop.fs.FSInputChecker;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.protocol.ReadGroupHeader;
import org.apache.hadoop.hdfs.server.common.HdfsConstants;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.util.DataChecksum;
import org.apache.hadoop.util.NativeCrc32;

public class GroupReader extends FSInputChecker{

  public static final Log LOG
      = LogFactory.getLog(GroupReader.class);

  private boolean sentStatusCode = false;

  Socket dnSock; 
  private DataInputStream in;
  protected DataChecksum checksum;
  protected long lastChunkOffset = -1;
  protected long lastChunkLen = -1;
  private long lastSeqNo = -1;
  
  private boolean pktIncludeVersion = true;
  protected long startOffset = 0;
  protected long firstChunkOffset;
  private boolean eos = false;

  protected int bytesPerChecksum;
  protected int checksumSize;
  int packetLen = 0;

  ByteBuffer checksumBytes = null;
  int dataLeft = 0;
  int currentPacketVersion;

  boolean isLastPacket = false;
  protected long bytesToCheckReadSpeed;
  protected long minSpeedBps;
  protected long bytesRead;
  protected long timeRead;
  protected FileSystem.Statistics fsStats = null;
  FSClientReadProfilingData cliData;

  private long dataTransferVersion;

  private boolean verifyChecksum = true;
  private boolean slownessLoged;

  public GroupReader(Socket dnSock, DataInputStream in, boolean verifyChecksum, DataChecksum checksum,
      long firstChunkOffset, long minSpeedBps, long bytesToCheckReadSpeed, FSClientReadProfilingData cliData,
      long dataTransferVersion){
    super(new Path("/"), 1, verifyChecksum, checksum, 
        checksum.getBytesPerChecksum(),
        checksum.getChecksumSize());

    this.dnSock = dnSock;
    this.in = in;
    this.verifyChecksum = verifyChecksum;
    this.checksum = checksum;
    this.firstChunkOffset = firstChunkOffset;
    this.minSpeedBps = minSpeedBps;
    this.bytesToCheckReadSpeed = bytesToCheckReadSpeed;
    this.cliData = cliData;
    this.dataTransferVersion = dataTransferVersion;

    this.lastChunkOffset = firstChunkOffset;
    this.pktIncludeVersion =
        (dataTransferVersion >= DataTransferProtocol.PACKET_INCLUDE_VERSION_VERSION);
    bytesPerChecksum = this.checksum.getBytesPerChecksum();
    checksumSize = this.checksum.getChecksumSize();

    this.bytesRead = 0;
    this.timeRead = 0;
    this.slownessLoged = false;

  }

  public static GroupReader newGroupReader(int dataTransferVersion, int namespaceId, int outputUnitNum, 
      Socket sock, int groupId, int groupSize, Map<Integer, LocatedBlockWithFileName> groupBlocks,
      int[] idxArray, int[] corruptArray, String jsonStr, int toReadInGroup, int bufferSize, 
      boolean verifyChecksum, String clientName, long bytesToCheckReadSpeed, long minSpeedBps, 
      boolean reuseConnection, FSClientReadProfilingData cliData, ReadOptions options, String code)
      throws IOException {
    DataOutputStream out = new DataOutputStream(
        new BufferedOutputStream(NetUtils.getOutputStream(sock,HdfsConstants.WRITE_TIMEOUT)));

    ReadGroupHeader readGroupHeader = new ReadGroupHeader(dataTransferVersion, namespaceId, 
        groupId, groupSize, groupBlocks, idxArray, corruptArray, jsonStr, toReadInGroup,
        bufferSize, verifyChecksum, clientName, bytesToCheckReadSpeed, minSpeedBps,
        reuseConnection, cliData != null, code, outputUnitNum);
    readGroupHeader.setReadOptions(options);
    readGroupHeader.writeVersionAndOpCode(out);
    readGroupHeader.write(out);

    out.flush();

    DataInputStream in = new DataInputStream(
        new BufferedInputStream(NetUtils.getInputStream(sock),
            bufferSize));
    if (in.readShort() != DataTransferProtocol.OP_STATUS_SUCCESS) {
      throw new IOException("Got error in reponse to OP_DRC_RACK_LEVEL " +
          "self= " + sock.getLocalSocketAddress() +
          ", remote= " + sock.getRemoteSocketAddress());
    }

    LOG.info("GroupReader.newGroupReader.readback SUCCESS");

    DataChecksum checksum = DataChecksum.newDataChecksum(in, new NativeCrc32());
    long firstChunkOffset = in.readLong();
    if(firstChunkOffset < 0) {
      throw new IOException("RackReader error in firstChunkOffset");
    }

    return new GroupReader(sock, in, verifyChecksum, checksum, firstChunkOffset, minSpeedBps, 
        bytesToCheckReadSpeed, cliData, dataTransferVersion);
  }        
  
  public boolean hasSentStatusCode() {
    return sentStatusCode;
  }

  public synchronized void close() throws IOException {
  }

  public synchronized int read(byte[] buf, int off, int len)
      throws IOException {
    if(lastChunkLen < 0 && startOffset > firstChunkOffset) {
      throw new IOException("wrong startOffset for this stream");
    }

    boolean eosBefore = eos;
    int nRead = super.read(buf, off, len);
    if(dnSock != null && eos && !eosBefore && nRead >= 0) {
      if(needChecksum()) {
        sendReadResult(dnSock, DataTransferProtocol.OP_STATUS_CHECKSUM_OK);
      }else {
        sendReadResult(dnSock, DataTransferProtocol.OP_STATUS_SUCCESS);
      }
    }
    return nRead;
  }

  @Override
  protected synchronized int readChunk(long pos, byte[] buf, int offset,
      int len, byte[] checksumBuf) throws IOException {
    if(eos) {
      if(startOffset < 0) {
        throw new IOException("GroupReader error!");
      }
      startOffset = -1;
      return -1;
    }
    // Read one data chunk
    long chunkOffset = lastChunkOffset;
    if(lastChunkLen > 0) {
      chunkOffset += lastChunkLen;
    }
    if((pos + firstChunkOffset) != chunkOffset) {
      throw new IOException("Mismatch in pos:" + pos + " + " + firstChunkOffset
          + " != " + chunkOffset);
    }

    long startTime = System.currentTimeMillis();

    // Read next packet if the previous has been read completely
    if (dataLeft <= 0) {
      if (minSpeedBps > 0) {
        bytesRead += packetLen;
        if(bytesRead > bytesToCheckReadSpeed) {
          if(timeRead > 0 && bytesRead * 100 / timeRead < minSpeedBps) {
            if(!slownessLoged) {
              FileSystem.LogForCollect.info("Too slow when rack reading");
            }
          }
          timeRead = 0;
          bytesRead = 0;
        }
      }
      //Read packet headers
      packetLen = in.readInt();
      if(packetLen > 0 && pktIncludeVersion) {
        currentPacketVersion = in.readInt();
      } else {
        throw new IOException("packet version not match CHECKSUM_INLINE, doesn't support!");
      }
       
      if(packetLen == 0) {
        eos = true;
        return 0;
      }
       
      long offsetInStream = in.readLong();
      long seqno = in.readLong();
      boolean lastPacketInStream = in.readBoolean();
      
      if(LOG.isDebugEnabled()) {
        LOG.debug("RackReader. DFSClient readrack got seqno " + seqno +
            " offsetinstream " + offsetInStream +
            " lastPacketInStream " + lastPacketInStream +
            " packetLen " + packetLen);
      }
       
      int dataLen = in.readInt();
       
      //Sanity check the length
      if (dataLen < 0 ||
          ((dataLen % bytesPerChecksum) != 0 && !lastPacketInStream) ||
          (seqno != (lastSeqNo + 1))) {
              throw new IOException("RackReader. datalen error!!");
      }
       
      lastSeqNo = seqno;
      isLastPacket = lastPacketInStream;
      dataLeft = dataLen;
    }
    int chunkLen = Math.min(dataLeft, bytesPerChecksum);
     
    if(chunkLen > 0) {
      if(currentPacketVersion == DataTransferProtocol.PACKET_VERSION_CHECKSUM_INLINE) {
        IOUtils.readFully(in, buf, offset, chunkLen);
        IOUtils.readFully(in, checksumBuf, 0, checksumSize);
      } else {
        throw new IOException("RackReader.readChunk doesn't support other packet version now!");
      }
    }
     
    dataLeft -= chunkLen;
    lastChunkOffset = chunkOffset;
    lastChunkLen = chunkLen;
     
    if(minSpeedBps > 0) {
      this.timeRead += System.currentTimeMillis() - startTime;
    }
     
    if((dataLeft == 0 && isLastPacket) || chunkLen == 0) {
      eos = true;
      int expectZero = in.readInt();
      assert expectZero == 0;
    }

    if(cliData != null) {
      cliData.recordReadChunkTime();
    }
     
    if (chunkLen == 0) {
      return -1;
    }
    return chunkLen;
  }

  @Override
  protected long getChunkPosition(long pos) {
    return 0;
  }

  @Override
  public boolean seekToNewSource(long targetPos) throws IOException {
    return true;
  }

  void sendReadResult(Socket sock, int statusCode) {
    assert !sentStatusCode : "already sent status code to " + sock;
   
    try {
      OutputStream out = NetUtils.getOutputStream(sock, HdfsConstants.WRITE_TIMEOUT);
      byte buf[] = { (byte) ((statusCode >>> 8) & 0xff),
          (byte) ((statusCode) & 0xff) };
      out.write(buf);
      out.flush();
      sentStatusCode = true;
    } catch (IOException e) {
    LOG.debug("Could not write to datanode " + sock.getInetAddress() +
        ": " + e.getMessage());
    }
   
  }
}


