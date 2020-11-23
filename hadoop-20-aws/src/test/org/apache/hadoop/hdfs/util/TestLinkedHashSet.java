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
package org.apache.hadoop.hdfs.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hdfs.util.LightWeightLinkedSet;

public class TestLinkedHashSet extends TestCase {

  private static final Log LOG = LogFactory
      .getLog("org.apache.hadoop.hdfs.TestLinkedHashSet");
  private ArrayList<Integer> list = new ArrayList<Integer>();
  private final int NUM = 100;
  private LightWeightLinkedSet<Integer> set;
  private Random rand;

  protected void setUp() {
    float maxF = LightWeightLinkedSet.rMaxLoadFactor;
    float minF = LightWeightLinkedSet.rMinLoadFactor;
    int initCapacity = LightWeightLinkedSet.MINIMUM_CAPACITY;
    rand = new Random(System.currentTimeMillis());
    list.clear();
    for (int i = 0; i < NUM; i++) {
      list.add(rand.nextInt());
    }
    set = new LightWeightLinkedSet<Integer>(initCapacity, maxF, minF);
  }

  public void testEmptyBasic() {
    LOG.info("Test empty basic");
    Iterator<Integer> iter = set.iterator();
    // iterator should not have next
    assertFalse(iter.hasNext());
    assertEquals(0, set.size());
    assertTrue(set.isEmpty());

    // poll should return nothing
    assertNull(set.pollFirst());
    assertEquals(0, set.pollAll().size());
    assertEquals(0, set.pollN(10).size());

    LOG.info("Test empty - DONE");
  }

  public void testOneElementBasic() {
    LOG.info("Test one element basic");
    set.add(list.get(0));
    // set should be non-empty
    assertEquals(1, set.size());
    assertFalse(set.isEmpty());

    // iterator should have next
    Iterator<Integer> iter = set.iterator();
    assertTrue(iter.hasNext());

    // iterator should not have next
    assertEquals(list.get(0), iter.next());
    assertFalse(iter.hasNext());
    LOG.info("Test one element basic - DONE");
  }

  public void testMultiBasic() {
    LOG.info("Test multi element basic");
    // add once
    for (Integer i : list) {
      assertTrue(set.add(i));
    }
    assertEquals(list.size(), set.size());

    // check if the elements are in the set
    for (Integer i : list) {
      assertTrue(set.contains(i));
    }

    // add again - should return false each time
    for (Integer i : list) {
      assertFalse(set.add(i));
    }

    // check again if the elements are there
    for (Integer i : list) {
      assertTrue(set.contains(i));
    }

    Iterator<Integer> iter = set.iterator();
    int num = 0;
    while (iter.hasNext()) {
      assertEquals(list.get(num++), iter.next());
    }
    // check the number of element from the iterator
    assertEquals(list.size(), num);
    LOG.info("Test multi element basic - DONE");
  }

  public void testRemoveOne() {
    LOG.info("Test remove one");
    assertTrue(set.add(list.get(0)));
    assertEquals(1, set.size());

    // remove from the head/tail
    assertTrue(set.remove(list.get(0)));
    assertEquals(0, set.size());

    // check the iterator
    Iterator<Integer> iter = set.iterator();
    assertFalse(iter.hasNext());

    // poll should return nothing
    assertNull(set.pollFirst());
    assertEquals(0, set.pollAll().size());
    assertEquals(0, set.pollN(10).size());

    // add the element back to the set
    assertTrue(set.add(list.get(0)));
    assertEquals(1, set.size());

    iter = set.iterator();
    assertTrue(iter.hasNext());
    LOG.info("Test remove one - DONE");
  }

  public void testRemoveMulti() {
    LOG.info("Test remove multi");
    for (Integer i : list) {
      assertTrue(set.add(i));
    }
    for (int i = 0; i < NUM / 2; i++) {
      assertTrue(set.remove(list.get(i)));
    }

    // the deleted elements should not be there
    for (int i = 0; i < NUM / 2; i++) {
      assertFalse(set.contains(list.get(i)));
    }

    // the rest should be there
    for (int i = NUM / 2; i < NUM; i++) {
      assertTrue(set.contains(list.get(i)));
    }

    Iterator<Integer> iter = set.iterator();
    // the remaining elements should be in order
    int num = NUM / 2;
    while (iter.hasNext()) {
      assertEquals(list.get(num++), iter.next());
    }
    assertEquals(num, NUM);
    LOG.info("Test remove multi - DONE");
  }

  public void testRemoveAll() {
    LOG.info("Test remove all");
    for (Integer i : list) {
      assertTrue(set.add(i));
    }
    for (int i = 0; i < NUM; i++) {
      assertTrue(set.remove(list.get(i)));
    }
    // the deleted elements should not be there
    for (int i = 0; i < NUM; i++) {
      assertFalse(set.contains(list.get(i)));
    }

    // iterator should not have next
    Iterator<Integer> iter = set.iterator();
    assertFalse(iter.hasNext());
    assertTrue(set.isEmpty());
    LOG.info("Test remove all - DONE");
  }

  public void testPollOneElement() {
    LOG.info("Test poll one element");
    set.add(list.get(0));
    assertEquals(list.get(0), set.pollFirst());
    assertNull(set.pollFirst());
    LOG.info("Test poll one element - DONE");
  }

  public void testPollMulti() {
    LOG.info("Test poll multi");
    for (Integer i : list) {
      assertTrue(set.add(i));
    }
    // remove half of the elements by polling
    for (int i = 0; i < NUM / 2; i++) {
      assertEquals(list.get(i), set.pollFirst());
    }
    assertEquals(NUM / 2, set.size());
    // the deleted elements should not be there
    for (int i = 0; i < NUM / 2; i++) {
      assertFalse(set.contains(list.get(i)));
    }
    // the rest should be there
    for (int i = NUM / 2; i < NUM; i++) {
      assertTrue(set.contains(list.get(i)));
    }
    Iterator<Integer> iter = set.iterator();
    // the remaining elements should be in order
    int num = NUM / 2;
    while (iter.hasNext()) {
      assertEquals(list.get(num++), iter.next());
    }
    assertEquals(num, NUM);

    // add elements back
    for (int i = 0; i < NUM / 2; i++) {
      assertTrue(set.add(list.get(i)));
    }
    // order should be switched
    assertEquals(NUM, set.size());
    for (int i = NUM / 2; i < NUM; i++) {
      assertEquals(list.get(i), set.pollFirst());
    }
    for (int i = 0; i < NUM / 2; i++) {
      assertEquals(list.get(i), set.pollFirst());
    }
    assertEquals(0, set.size());
    assertTrue(set.isEmpty());
    LOG.info("Test poll multi - DONE");
  }

  public void testPollAll() {
    LOG.info("Test poll all");
    for (Integer i : list) {
      assertTrue(set.add(i));
    }
    // remove all elements by polling
    while (set.pollFirst() != null);
    assertEquals(0, set.size());
    assertTrue(set.isEmpty());

    // the deleted elements should not be there
    for (int i = 0; i < NUM; i++) {
      assertFalse(set.contains(list.get(i)));
    }

    Iterator<Integer> iter = set.iterator();
    assertFalse(iter.hasNext());
    LOG.info("Test poll all - DONE");
  }

  public void testPollNOne() {
    LOG.info("Test pollN one");
    set.add(list.get(0));
    List<Integer> l = set.pollN(10);
    assertEquals(1, l.size());
    assertEquals(list.get(0), l.get(0));
    
    // to list version
    set.add(list.get(0));
    l = new ArrayList<Integer>(); 
    set.pollNToList(10, l);
    assertEquals(1, l.size());
    assertEquals(list.get(0), l.get(0));
    
    LOG.info("Test pollN one - DONE");
  }

  public void testPollNMulti() {
    LOG.info("Test pollN multi");

    // use addAll
    set.addAll(list);

    // poll existing elements
    List<Integer> l = set.pollN(10);
    assertEquals(10, l.size());

    for (int i = 0; i < 10; i++) {
      assertEquals(list.get(i), l.get(i));
    }

    // poll more elements than present
    l = set.pollN(1000);
    assertEquals(NUM - 10, l.size());

    // check the order
    for (int i = 10; i < NUM; i++) {
      assertEquals(list.get(i), l.get(i - 10));
    }
    // set is empty
    assertTrue(set.isEmpty());
    assertEquals(0, set.size());

    LOG.info("Test pollN multi - DONE");
  }
  
  public void testPollNMultiToList() {
    LOG.info("Test pollN multi to list");

    // use addAll
    set.addAll(list);

    // poll existing elements
    List<Integer> l = new ArrayList<Integer>();
    set.pollNToList(10, l);
    assertEquals(10, l.size());

    for (int i = 0; i < 10; i++) {
      assertEquals(list.get(i), l.get(i));
    }

    // poll more elements than present
    l.clear();
    set.pollNToList(1000, l);
    assertEquals(NUM - 10, l.size());

    // check the order
    for (int i = 10; i < NUM; i++) {
      assertEquals(list.get(i), l.get(i - 10));
    }
    // set is empty
    assertTrue(set.isEmpty());
    assertEquals(0, set.size());

    LOG.info("Test pollN to list multi - DONE");
  }

  public void testClear() {
    LOG.info("Test clear");
    // use addAll
    set.addAll(list);
    assertEquals(NUM, set.size());
    assertFalse(set.isEmpty());

    // clear the set
    set.clear();
    assertEquals(0, set.size());
    assertTrue(set.isEmpty());

    // poll should return an empty list
    assertEquals(0, set.pollAll().size());
    assertEquals(0, set.pollN(10).size());
    assertNull(set.pollFirst());

    // iterator should be empty
    Iterator<Integer> iter = set.iterator();
    assertFalse(iter.hasNext());

    LOG.info("Test clear - DONE");
  }

  public void testOther() {
    LOG.info("Test other");

    assertTrue(set.addAll(list));
    // to array
    Integer[] array = set.toArray(new Integer[0]);
    assertEquals(NUM, array.length);
    for (int i = 0; i < array.length; i++) {
      assertTrue(list.contains(array[i]));
    }
    assertEquals(NUM, set.size());

    // to array
    Object[] array2 = set.toArray();
    assertEquals(NUM, array2.length);
    for (int i = 0; i < array2.length; i++) {
      assertTrue(list.contains((Integer) array2[i]));
    }

    LOG.info("Test other - DONE");
  }

}
