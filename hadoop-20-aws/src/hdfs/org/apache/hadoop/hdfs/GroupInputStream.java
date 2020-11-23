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

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.apache.hadoop.fs.ChecksumException;
import org.apache.hadoop.fs.FSClientReadProfilingData;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSClient.DataNodeSlowException;
import org.apache.hadoop.hdfs.GroupReader;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DataTransferProtocol;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.LocatedBlocksWithMetaInfo;
import org.apache.hadoop.hdfs.protocol.VersionedLocatedBlocks;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.io.ReadOptions;
import org.apache.hadoop.net.NetUtils;

public class GroupInputStream extends InputStream {
  private final DFSClient dfsClient;
  private int groupSize; // TODO: should read through Codec
  private int buffersize = 1;
  private Map<Integer, LocatedBlockWithFileName> groupBlocks;
  private int[] idxArray;
  private int[] corruptArray;
  private int outputUnitNum;
  private boolean verifyChecksum;
  private boolean clearOsBuffer;
  private ReadOptions options = new ReadOptions();
  private String code = null;
  private int groupId = -1;
  private int toRead = 0;  // read data in this stream
  private String jsonStr;

  private final SocketCache socketCache;
  private long prefetchSize;

  private boolean closed = false;

  private GroupReader groupReader = null;
  private volatile DatanodeInfo relayer = null;
  private Map<Integer, Long> startOffsets = new HashMap<Integer, Long>();
  private long blockSize;

  private long pos = 0;  // denote the position in stream
  private long streamEnd = -1;  // the ened offset of the stream
  private int stripeNum;

  private FSClientReadProfilingData cliData = null;

  GroupInputStream(DFSClient dfsClient, int groupSize, int buffersize, Map<Integer, LocatedBlockWithFileName> groupBlocks,
      int[] idxArray, int[] corruptArray, int outputUnitNum, boolean verifyChecksum, boolean clearOsBuffer, 
      ReadOptions options, String code, int groupId, int toRead, String jsonStr) throws IOException {

    this.dfsClient = dfsClient;
    this.groupSize = groupSize;
    this.buffersize = buffersize;
    this.groupBlocks = groupBlocks;
    this.idxArray = idxArray;
    this.corruptArray = corruptArray;
    this.outputUnitNum = outputUnitNum;
    this.verifyChecksum = verifyChecksum;
    this.clearOsBuffer = clearOsBuffer;
    this.options = options;
    this.code = code;
    this.groupId = groupId;
    this.toRead = toRead;
    this.jsonStr = jsonStr;

    this.socketCache = dfsClient.socketCache;
    
    this.prefetchSize = dfsClient.conf.getLong("dfs.read.prefetch.size",
        10 * dfsClient.defaultBlockSize);
  }

  // buf is the final buffer to hold the data, now I have read off, this time want to read len
  public synchronized int read(byte buf[], int off, int len) throws IOException {
    
    dfsClient.checkOpen();
    if (closed) {
      dfsClient.incReadExpCntToStats();
      throw new IOException("Stream closed");
    }

    DFSClient.dfsInputStreamfailures.set(0);
    long start = System.currentTimeMillis();

    //toRead the the datalength I want to read through this stream
    if(pos < toRead) {
      int retries = 1;
      while(retries > 0) {
        try {
          if(len == 0) {
          } else if(pos > streamEnd){
            streamSeekTo(pos,true);
          }

          int realLen = (int)Math.min((long)len, (streamEnd - pos + 1L));
          int result = readBuffer(buf, off, realLen);
          if(result > 0) {
            pos += result;
          } else if(len != 0) {
            throw new IOException("unexpected EOS from the groupReader!");
          }
          if (dfsClient.stats != null && result != -1) {
            dfsClient.stats.incrementBytesRead(result);
          }
          long timeval = System.currentTimeMillis() - start;
          dfsClient.metrics.incReadTime(timeval);
          dfsClient.metrics.incReadSize(result);
          dfsClient.metrics.incReadOps();
          return (result >= 0) ? result : 0;
        } catch (InterruptedIOException iie) {
          throw iie;
        } catch(ChecksumException ce) {
          dfsClient.incReadExpCntToStats();
          throw ce;
        } catch(IOException e) {
          dfsClient.incReadExpCntToStats();
          streamEnd = -1;
          if(--retries == 0) {
            if(len != 0) {
              throw e;
            } else {
              return 0;
            }
          }
        }
      }
    }

    return -1;
  }

  private synchronized int readBuffer(byte buf[], int off, int len)
    throws IOException {
    IOException ioe;
    try {
      int bytesRead = groupReader.read(buf, off, len);
      if(cliData != null) {
        cliData.recordReadBufferTime();
      }

      return bytesRead;

    } catch (DataNodeSlowException dnse) {
      ioe = dnse;
    } catch (ChecksumException ce) {
      ioe = ce;
    } catch ( IOException e ) {
      ioe = e;
    }

    throw ioe;
  }

  private synchronized void streamSeekTo(long target,
      boolean throwWhenNotFound) throws IOException {

    if(target > toRead) {
      throw new IOException("Attempted to read more than the stream want");
    }

    // will be getting a new GroupReader
    if(groupReader != null) {
      closeGroupReader(groupReader, false);  // close need to  implement
      groupReader = null;
    }

    relayer = getRelayer();
    while(true) {
      InetSocketAddress relayerAddr = NetUtils.createSocketAddr(relayer.getName());
      DataNode.LOG.info("GroupInputStream.relayeraddr = " + relayerAddr);
      final InetAddress relayerDatanodeAddr = relayerAddr.getAddress();
      final boolean isRelayerLocal = NetUtils.isLocalAddressWithCaching(relayerDatanodeAddr)
          || (dfsClient.localHost != null && dfsClient.localHost.equals(relayerDatanodeAddr));

      try {
        long minReadSpeedBps = dfsClient.minReadSpeedBps;
        groupReader = getGroupReader(dfsClient.getDataTransferProtocolVersion(),
            dfsClient.getnamespaceId(), relayerAddr, this.groupId, this.groupSize, this.groupBlocks,
            this.idxArray, this.corruptArray, this.toRead, this.buffersize, this.verifyChecksum,
            dfsClient.clientName, dfsClient.bytesToCheckReadSpeed, minReadSpeedBps, false, 
            cliData, this.options);
      } catch (IOException e) {
      }
      break;
    }
    this.pos = target;
    this.streamEnd = toRead - 1;
  }

  protected GroupReader getGroupReader(int protocolVersion, int namespaceId, 
      InetSocketAddress dnAddr, int groupId, int groupSize, 
      Map<Integer, LocatedBlockWithFileName> groupBlocks, int[] idxArray,
      int[] corruptArray, int toReadInGroup, int bufferSize, boolean verifyChecksum,
      String clientName, long bytesToCheckReadSpeed, long minSpeedBps, boolean reuseConnection,
      FSClientReadProfilingData cliData, ReadOptions options)
      throws IOException {
    
    IOException err = null;
    GroupReader reader = null;

    boolean fromCache = true;
    if (protocolVersion < DataTransferProtocol.READ_REUSE_CONNECTION_VERSION ||
        reuseConnection == false) {
      Socket sock = dfsClient.socketFactory.createSocket();
      sock.setTcpNoDelay(true);
      NetUtils.connect(sock, dnAddr, dfsClient.socketTimeout);
      sock.setSoTimeout(dfsClient.socketTimeout);

      if(this.code != null) {

        reader = GroupReader.newGroupReader(protocolVersion, namespaceId, outputUnitNum, sock,
            groupId, groupSize, groupBlocks, idxArray, corruptArray, this.jsonStr, toReadInGroup,
            bufferSize, verifyChecksum, clientName, bytesToCheckReadSpeed, minSpeedBps, reuseConnection,
            cliData, options, this.code);
        return reader;
      }
    }
    return null;
  }
 


  private DatanodeInfo getRelayer() throws IOException {
    // choose one as relayer
    for(Map.Entry<Integer, LocatedBlockWithFileName> entry: groupBlocks.entrySet()) {
      LocatedBlockWithFileName lb = entry.getValue();
      DatanodeInfo[] info = lb.getLocations();
      assert info.length == 1;
      return info[0];
    }
    return null;
  }

  private void closeGroupReader(GroupReader reader, boolean reuseConnection)
    throws IOException {
    if (reader.hasSentStatusCode()) {
    }

    reader.close();
  }

  @Override
  public synchronized int read() throws IOException {
    return 0;
  }

}
