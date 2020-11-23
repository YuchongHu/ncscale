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

import java.io.IOException;

import org.apache.commons.logging.impl.Log4JLogger;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.AlreadyBeingCreatedException;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.LeaseManager;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.log4j.Level;

public class TestLeaseRecovery3 extends junit.framework.TestCase {
  {
    DataNode.LOG.getLogger().setLevel(Level.ALL);
    ((Log4JLogger)LeaseManager.LOG).getLogger().setLevel(Level.ALL);
    ((Log4JLogger)FSNamesystem.LOG).getLogger().setLevel(Level.ALL);
  }

  static final long BLOCK_SIZE = 1024;
  static final int FILE_SIZE = 1024*16;
  static final short REPLICATION_NUM = (short)3;
  private static byte[] buffer = new byte[FILE_SIZE];
  private final Configuration conf = new Configuration();
  private final int bufferSize = conf.getInt("io.file.buffer.size", 4096);

  public void testBlockSynchronization() throws Exception {
    final long softLease = 1000;
    final long hardLease = 60 * 60 *1000;
    final long packetSize =  BLOCK_SIZE/2;
    conf.setLong("dfs.block.size", BLOCK_SIZE);
    conf.setInt("dfs.heartbeat.interval", 1);
    conf.setInt("dfs.write.packet.size", (int) packetSize);

    MiniDFSCluster cluster = null;
    byte[] actual = new byte[FILE_SIZE];

    FSDataOutputStream stm =null;
    try {
      cluster = new MiniDFSCluster(conf, 5, true, null);
      cluster.waitActive();

      //create a file, write 2 blocks, then do a sync and then write
      //another block. The last block is not synced.
      DistributedFileSystem dfs = (DistributedFileSystem)cluster.getFileSystem();
      int fileSize = 2 * (int)BLOCK_SIZE;
      String filestr = "/foo.dhruba";

      System.out.println("Creating file " + filestr);
      Path filepath = new Path(filestr);
      stm = dfs.create(filepath, true,
        bufferSize, REPLICATION_NUM, BLOCK_SIZE);
      assertTrue(dfs.dfs.exists(filestr));

      // write 2 blocks worth of data
      System.out.println("Writing data " + fileSize);
      stm.write(buffer, 0, fileSize);

      stm.sync();
      AppendTestUtil.LOG.info("sync done");

      // write another piece of data to file. This piece of data
      // is not yet synced
      System.out.println("Writing data again " + (packetSize + 1));
      stm.write(buffer, 0, (int)(packetSize + 1));

      // invoke recoverLease from a different client. This should truncate the
      // file to 2 blocks.
      recoverLease(filepath, null);

      // verify that file is 2 blocks long.
      verifyFile(null, filepath, actual, fileSize);
    }
    finally {
      try {
        if (stm != null) {
          stm.close();
        }
        if (cluster != null) {cluster.getFileSystem().close(); cluster.shutdown();}
      } catch (Exception e) {
        // ignore
      }
    }
  }

  private void recoverLease(Path filepath, DistributedFileSystem dfs2) throws Exception {
    if (dfs2==null) {
      Configuration conf2 = new Configuration(conf);
      String username = UserGroupInformation.getCurrentUGI().getUserName()+"_1";
      UnixUserGroupInformation.saveToConf(conf2,
          UnixUserGroupInformation.UGI_PROPERTY_NAME,
          new UnixUserGroupInformation(username, new String[]{"supergroup"}));
      dfs2 = (DistributedFileSystem)FileSystem.get(conf2);
    }

    AppendTestUtil.LOG.info("XXX  test recoverLease");
    while (!dfs2.recoverLease(filepath, true)) {
      AppendTestUtil.LOG.info("sleep " + 1000 + "ms");
      Thread.sleep(1000);
    }
  }

  // try to re-open the file before closing the previous handle. This
  // should fail but will trigger lease recovery.
  private Path createFile(DistributedFileSystem dfs, int size
      ) throws IOException, InterruptedException {
    // create a random file name
    String filestr = "/foo" + AppendTestUtil.nextInt();
    System.out.println("filestr=" + filestr);
    Path filepath = new Path(filestr);
    FSDataOutputStream stm = dfs.create(filepath, true,
        bufferSize, REPLICATION_NUM, BLOCK_SIZE);
    assertTrue(dfs.dfs.exists(filestr));

    // write random number of bytes into it.
    System.out.println("size=" + size);
    stm.write(buffer, 0, size);

    // sync file
    AppendTestUtil.LOG.info("sync");
    stm.sync();

    // write another piece of data to file. This piece of data
    // is not yet synced
    stm.write(buffer, 0, size);
    return filepath;
  }

  private void recoverLeaseUsingCreate(Path filepath) throws IOException {
    Configuration conf2 = new Configuration(conf);
    String username = UserGroupInformation.getCurrentUGI().getUserName()+"_1";
    UnixUserGroupInformation.saveToConf(conf2,
        UnixUserGroupInformation.UGI_PROPERTY_NAME,
        new UnixUserGroupInformation(username, new String[]{"supergroup"}));
    FileSystem dfs2 = FileSystem.get(conf2);

    boolean done = false;
    for(int i = 0; i < 10 && !done; i++) {
      AppendTestUtil.LOG.info("i=" + i);
      try {
        dfs2.create(filepath, false, bufferSize, (short)1, BLOCK_SIZE);
        fail("Creation of an existing file should never succeed.");
      } catch (IOException ioe) {
        final String message = ioe.getMessage();
        if (message.contains("file exists")) {
          AppendTestUtil.LOG.info("done", ioe);
          done = true;
        }
        else if (message.contains(AlreadyBeingCreatedException.class.getSimpleName())) {
          AppendTestUtil.LOG.info("GOOD! got " + message);
        }
        else {
          AppendTestUtil.LOG.warn("UNEXPECTED IOException", ioe);
        }
      }

      if (!done) {
        AppendTestUtil.LOG.info("sleep " + 5000 + "ms");
        try {Thread.sleep(5000);} catch (InterruptedException e) {}
      }
    }
    assertTrue(done);

  }

  private void verifyFile(FileSystem dfs, Path filepath, byte[] actual,
      int size) throws IOException {
    if (dfs==null) {
      Configuration conf2 = new Configuration(conf);
      String username = UserGroupInformation.getCurrentUGI().getUserName()+"_1";
      UnixUserGroupInformation.saveToConf(conf2,
          UnixUserGroupInformation.UGI_PROPERTY_NAME,
          new UnixUserGroupInformation(username, new String[]{"supergroup"}));
      dfs = (DistributedFileSystem)FileSystem.get(conf2);
    }
    AppendTestUtil.LOG.info("Lease for file " +  filepath + " is recovered. "
        + "Validating its contents now...");

    // verify that file-size matches
    assertTrue("File should be " + size + " bytes, but is actually " +
               " found to be " + dfs.getFileStatus(filepath).getLen() +
               " bytes",
               dfs.getFileStatus(filepath).getLen() == size);

    // verify that there is enough data to read.
    System.out.println("File size is good. Now validating sizes from datanodes...");
    FSDataInputStream stmin = dfs.open(filepath);
    stmin.readFully(0, actual, 0, size);
    stmin.close();
  }
}
