package org.apache.hadoop.hdfs;

import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.hdfs.server.datanode.DataNode;

public class ReadStreamInfo {
  private int groupId;
  private InputStream input;
  private boolean isGroupStream;
  private int unitSize;
  private int outUnitNum;
  private byte[] readBuffer;
  private byte[][] outBuffer;


  // for DFSInputStream
  public ReadStreamInfo(int groupId, InputStream in, boolean isGroupStream, int unitSize,
      int outUnitNum) {
    this.groupId = groupId;
    this.input = in;
    this.isGroupStream = isGroupStream;
    this.unitSize = unitSize;
    this.outUnitNum = outUnitNum;

    this.readBuffer = new byte[unitSize*outUnitNum];
    this.outBuffer = new byte[outUnitNum][];
    for(int i=0; i<outUnitNum; i++) {
      outBuffer[i] = new byte[unitSize];
    }
    DataNode.LOG.info("ReadStreamInfo.groupId = " + groupId);
    DataNode.LOG.info("ReadStreamInfo.isGroupStream = " + isGroupStream);
    DataNode.LOG.info("ReadStreamInfo.unitSize = " + unitSize);
    DataNode.LOG.info("ReadStreamInfo.unitNum = " + outUnitNum);
  }

  public byte[] allocate(int toReadByUnit) {
    this.unitSize = toReadByUnit;
    readBuffer = new byte[unitSize * outUnitNum];

    return readBuffer;
  }

  public byte[] read() throws IOException {
    this.readBuffer = new byte[this.unitSize*this.outUnitNum];
    this.input.read(this.readBuffer, 0, this.unitSize*this.outUnitNum);
    return this.readBuffer;
  }

  public void mapToOut() {
    for(int i=0; i<this.outUnitNum; i++) {
      System.arraycopy(readBuffer, unitSize * i, outBuffer[i], 0, unitSize);
    }
  }

  public byte[] getOutBuffer(int i) {
    return this.outBuffer[i];
  }

  public InputStream getInputStream() {
    return this.input;
  }

  public int getUnitSize() {
    return this.unitSize;
  }

  public int getUnitNum() {
    return this.outUnitNum;
  }
}
