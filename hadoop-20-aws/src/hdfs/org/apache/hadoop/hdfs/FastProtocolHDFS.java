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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.qjournal.server.JournalNodeRpcServer;

/**
 * This class enumerates all FastProtocol classes for hdfs, and forces their
 * registration. This must be used on server side at startup time, before any
 * client requests are served.
 */
public final class FastProtocolHDFS {

  public static final Log LOG = LogFactory.getLog(FastProtocolHDFS.class
      .getName());

  private static boolean initialized = false;

  public synchronized static void init() {
    if (initialized) {
      LOG.info("FastProtocolHDFS already initialized");
      return;
    }

    // enumerate all FastProtocol classes for hdfs, and force
    // initialization
    JournalNodeRpcServer.init();

    // initialization is only done once
    initialized = true;
    LOG.info("FastProtocolHDFS initialization complete.");
  }

}
