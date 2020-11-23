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
import java.util.concurrent.atomic.AtomicInteger;

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
import org.apache.hadoop.hdfs.util.InjectionEvent;
import org.apache.hadoop.security.UnixUserGroupInformation;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.InjectionEventI;
import org.apache.hadoop.util.InjectionHandler;
import org.apache.log4j.Level;

public class TestLeaseRecovery2 extends junit.framework.TestCase {
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
    conf.setLong("dfs.block.size", BLOCK_SIZE);
    conf.setInt("dfs.heartbeat.interval", 1);
    conf.setLong("dfs.socket.timeout", 2998);
  //  conf.setInt("io.bytes.per.checksum", 16);

    MiniDFSCluster cluster = null;
    byte[] actual = new byte[FILE_SIZE];

    try {
      cluster = new MiniDFSCluster(conf, 5, true, null);
      cluster.waitActive();

      //create a file
      DistributedFileSystem dfs = (DistributedFileSystem)cluster.getFileSystem();
      int size = AppendTestUtil.nextInt(FILE_SIZE);
      Path filepath = createFile(dfs, size, true);

      // set the soft limit to be 1 second so that the
      // namenode triggers lease recovery on next attempt to write-for-open.
      cluster.setLeasePeriod(softLease, hardLease);

      recoverLeaseUsingCreate(filepath);
      verifyFile(dfs, filepath, actual, size);

      //test recoverLease
      // set the soft limit to be 1 hour but recoverLease should
      // close the file immediately
      cluster.setLeasePeriod(hardLease, hardLease);
      size = AppendTestUtil.nextInt(FILE_SIZE);
      filepath = createFile(dfs, size, false);

      // test recoverLese from a different client
      recoverLease(filepath, null);
      verifyFile(dfs, filepath, actual, size);

      // test recoverlease from the same client
      size = AppendTestUtil.nextInt(FILE_SIZE);
      filepath = createFile(dfs, size, false);

      // create another file using the same client
      Path filepath1 = new Path("/foo" + AppendTestUtil.nextInt());
      FSDataOutputStream stm = dfs.create(filepath1, true,
          bufferSize, REPLICATION_NUM, BLOCK_SIZE);

      // recover the first file
      recoverLease(filepath, dfs);
      verifyFile(dfs, filepath, actual, size);

      // continue to write to the second file
      stm.write(buffer, 0, size);
      stm.close();
      verifyFile(dfs, filepath1, actual, size);

    
      // create another file using the same client
      Path filepath2 = new Path("/foo2" + AppendTestUtil.nextInt());
      FSDataOutputStream stm2 = dfs.create(filepath2, true,
          bufferSize, REPLICATION_NUM, BLOCK_SIZE);
      stm2.write(buffer, 0, size);
      stm2.sync();

      // For one of the datanodes, hang all the block recover requests.
      InjectionHandler.set(new InjectionHandler() {
        AtomicInteger hangDnPort = new AtomicInteger(-1);
        
        @Override
        protected void _processEvent(InjectionEventI event, Object... args) {
          if (event == InjectionEvent.DATANODE_BEFORE_RECOVERBLOCK) {
            DataNode dn = (DataNode) args[0];
            int dnPort = dn.getPort();
            hangDnPort.compareAndSet(-1, dnPort);
            if (hangDnPort.get() == dnPort) {
              try {
                System.out.println("SLEEPING DN PORT: " + dnPort
                    + " IPC PORT: " + dn.getRpcPort());
                Thread.sleep(60000);
              } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
              }
            }
          }
        }
      });
      
      // recover the file
      recoverLease(filepath2, dfs);
      
      verifyFile(dfs, filepath2, actual, size);
    }
    finally {
      try {
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

    while (!dfs2.recoverLease(filepath, false)) {
      AppendTestUtil.LOG.info("sleep " + 1000 + "ms");
      Thread.sleep(1000);
    }
  }

  // try to re-open the file before closing the previous handle. This
  // should fail but will trigger lease recovery.
  private Path createFile(DistributedFileSystem dfs, int size,
      boolean triggerSoftLease) throws IOException, InterruptedException {
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
    if (triggerSoftLease) {
      AppendTestUtil.LOG.info("leasechecker.interruptAndJoin()");
      dfs.dfs.leasechecker.interruptAndJoin();
    }
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
