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
package org.apache.hadoop.hbase.util.hbck;

import static org.apache.hadoop.hbase.util.hbck.HbckTestingUtil.assertErrors;
import static org.apache.hadoop.hbase.util.hbck.HbckTestingUtil.doFsck;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.Arrays;

import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.util.HBaseFsck;
import org.apache.hadoop.hbase.util.HBaseFsck.ErrorReporter.ERROR_CODE;
import org.apache.hadoop.hbase.util.HBaseFsck.HbckInfo;
import org.junit.Test;

import com.google.common.collect.Multimap;

/**
 * This builds a table, builds an overlap, and then fails when attempting to
 * rebuild meta.
 */
public class TestOfflineMetaRebuildOverlap extends OfflineMetaRebuildTestCore {

  @Test(timeout = 120000)
  public void testMetaRebuildOverlapFail() throws Exception {
    // Add a new .regioninfo meta entry in hdfs
    byte[] startKey = splits[0];
    byte[] endKey = splits[2];
    createRegion(conf, htbl, startKey, endKey);

    wipeOutMeta();

    // is meta really messed up?
    assertEquals(0, scanMeta());
    assertErrors(doFsck(conf, false),
        new ERROR_CODE[] { ERROR_CODE.NOT_IN_META_OR_DEPLOYED,
            ERROR_CODE.NOT_IN_META_OR_DEPLOYED,
            ERROR_CODE.NOT_IN_META_OR_DEPLOYED,
            ERROR_CODE.NOT_IN_META_OR_DEPLOYED, });
    // Note, would like to check # of tables, but this takes a while to time
    // out.

    // shutdown the minicluster
    TEST_UTIL.shutdownMiniHBaseCluster();
    TEST_UTIL.shutdownMiniZKCluster();

    // attempt to rebuild meta table from scratch
    HBaseFsck fsck = new HBaseFsck(conf);
    assertFalse(fsck.rebuildMeta());

    Multimap<byte[], HbckInfo> problems = fsck.getOverlapGroups(table);
    assertEquals(1, problems.keySet().size());
    assertEquals(3, problems.size());

    // bring up the minicluster
    TEST_UTIL.startMiniZKCluster(); // tables seem enabled by default
    TEST_UTIL.restartHBaseCluster(3);

    // Meta still messed up.
    assertEquals(0, scanMeta());
    HTableDescriptor[] htbls = TEST_UTIL.getHBaseAdmin().listTables();
    LOG.info("Tables present after restart: " + Arrays.toString(htbls));

    // Before HBASE-451 HBaseAdmin.listTables() gets table descriptors by
    // performing a meta scan, so this should be 0.
    assertEquals(0, htbls.length);
    assertErrors(doFsck(conf, false),
        new ERROR_CODE[] { ERROR_CODE.NOT_IN_META_OR_DEPLOYED,
            ERROR_CODE.NOT_IN_META_OR_DEPLOYED,
            ERROR_CODE.NOT_IN_META_OR_DEPLOYED,
            ERROR_CODE.NOT_IN_META_OR_DEPLOYED, });
  }
}