/**
 *
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

package org.apache.hadoop.hbase.regionserver;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.rmi.UnexpectedException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableSet;
import java.util.SortedSet;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.KeyValueUtil;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.ByteRange;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.ClassSize;
import org.apache.hadoop.hbase.util.CollectionBackedScanner;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.ReflectionUtils;

/**
 * The MemStore holds in-memory modifications to the Store.  Modifications
 * are {@link Cell}s.  When asked to flush, current memstore is moved
 * to snapshot and is cleared.  We continue to serve edits out of new memstore
 * and backing snapshot until flusher reports in that the flush succeeded. At
 * this point we let the snapshot go.
 *  <p>
 * The MemStore functions should not be called in parallel. Callers should hold
 *  write and read locks. This is done in {@link HStore}.
 *  </p>
 *
 * TODO: Adjust size of the memstore when we remove items because they have
 * been deleted.
 * TODO: With new KVSLS, need to make sure we update HeapSize with difference
 * in KV size.
 */
@InterfaceAudience.Private
public class DefaultMemStore implements MemStore {
  private static final Log LOG = LogFactory.getLog(DefaultMemStore.class);
  static final String USEMSLAB_KEY = "hbase.hregion.memstore.mslab.enabled";
  private static final boolean USEMSLAB_DEFAULT = true;
  static final String MSLAB_CLASS_NAME = "hbase.regionserver.mslab.class";

  private Configuration conf;

  // MemStore.  Use a KeyValueSkipListSet rather than SkipListSet because of the
  // better semantics.  The Map will overwrite if passed a key it already had
  // whereas the Set will not add new KV if key is same though value might be
  // different.  Value is not important -- just make sure always same
  // reference passed.
  volatile KeyValueSkipListSet kvset;

  // Snapshot of memstore.  Made for flusher.
  volatile KeyValueSkipListSet snapshot;

  final KeyValue.KVComparator comparator;

  // Used to track own heapSize
  final AtomicLong size;
  private volatile long snapshotSize;

  // Used to track when to flush
  volatile long timeOfOldestEdit = Long.MAX_VALUE;

  TimeRangeTracker timeRangeTracker;
  TimeRangeTracker snapshotTimeRangeTracker;

  volatile MemStoreLAB allocator;
  volatile MemStoreLAB snapshotAllocator;
  volatile long snapshotId;

  /**
   * Default constructor. Used for tests.
   */
  public DefaultMemStore() {
    this(HBaseConfiguration.create(), KeyValue.COMPARATOR);
  }

  /**
   * Constructor.
   * @param c Comparator
   */
  public DefaultMemStore(final Configuration conf,
                  final KeyValue.KVComparator c) {
    this.conf = conf;
    this.comparator = c;
    this.kvset = new KeyValueSkipListSet(c);
    this.snapshot = new KeyValueSkipListSet(c);
    timeRangeTracker = new TimeRangeTracker();
    snapshotTimeRangeTracker = new TimeRangeTracker();
    this.size = new AtomicLong(DEEP_OVERHEAD);
    this.snapshotSize = 0;
    if (conf.getBoolean(USEMSLAB_KEY, USEMSLAB_DEFAULT)) {
      String className = conf.get(MSLAB_CLASS_NAME, HeapMemStoreLAB.class.getName());
      this.allocator = ReflectionUtils.instantiateWithCustomCtor(className,
          new Class[] { Configuration.class }, new Object[] { conf });
    } else {
      this.allocator = null;
    }
  }

  void dump() {
    for (KeyValue kv: this.kvset) {
      LOG.info(kv);
    }
    for (KeyValue kv: this.snapshot) {
      LOG.info(kv);
    }
  }

  /**
   * Creates a snapshot of the current memstore.
   * Snapshot must be cleared by call to {@link #clearSnapshot(long)}
   */
  @Override
  public MemStoreSnapshot snapshot() {
    // If snapshot currently has entries, then flusher failed or didn't call
    // cleanup.  Log a warning.
    if (!this.snapshot.isEmpty()) {
      LOG.warn("Snapshot called again without clearing previous. " +
          "Doing nothing. Another ongoing flush or did we fail last attempt?");
    } else {
      this.snapshotId = EnvironmentEdgeManager.currentTimeMillis();
      this.snapshotSize = keySize();
      if (!this.kvset.isEmpty()) {
        this.snapshot = this.kvset;
        this.kvset = new KeyValueSkipListSet(this.comparator);
        this.snapshotTimeRangeTracker = this.timeRangeTracker;
        this.timeRangeTracker = new TimeRangeTracker();
        // Reset heap to not include any keys
        this.size.set(DEEP_OVERHEAD);
        this.snapshotAllocator = this.allocator;
        // Reset allocator so we get a fresh buffer for the new memstore
        if (allocator != null) {
          String className = conf.get(MSLAB_CLASS_NAME, HeapMemStoreLAB.class.getName());
          this.allocator = ReflectionUtils.instantiateWithCustomCtor(className,
              new Class[] { Configuration.class }, new Object[] { conf });
        }
        timeOfOldestEdit = Long.MAX_VALUE;
      }
    }
    return new MemStoreSnapshot(this.snapshotId, snapshot.size(), this.snapshotSize,
        this.snapshotTimeRangeTracker, new CollectionBackedScanner(snapshot, this.comparator));
  }

  /**
   * The passed snapshot was successfully persisted; it can be let go.
   * @param id Id of the snapshot to clean out.
   * @throws UnexpectedException
   * @see #snapshot()
   */
  @Override
  public void clearSnapshot(long id) throws UnexpectedException {
    MemStoreLAB tmpAllocator = null;
    if (this.snapshotId != id) {
      throw new UnexpectedException("Current snapshot id is " + this.snapshotId + ",passed " + id);
    }
    // OK. Passed in snapshot is same as current snapshot. If not-empty,
    // create a new snapshot and let the old one go.
    if (!this.snapshot.isEmpty()) {
      this.snapshot = new KeyValueSkipListSet(this.comparator);
      this.snapshotTimeRangeTracker = new TimeRangeTracker();
    }
    this.snapshotSize = 0;
    this.snapshotId = -1;
    if (this.snapshotAllocator != null) {
      tmpAllocator = this.snapshotAllocator;
      this.snapshotAllocator = null;
    }
    if (tmpAllocator != null) {
      tmpAllocator.close();
    }
  }

  @Override
  public long getFlushableSize() {
    return this.snapshotSize > 0 ? this.snapshotSize : keySize();
  }

  /**
   * Write an update
   * @param cell
   * @return approximate size of the passed key and value.
   */
  @Override
  public long add(Cell cell) {
    KeyValue toAdd = maybeCloneWithAllocator(KeyValueUtil.ensureKeyValue(cell));
    return internalAdd(toAdd);
  }

  @Override
  public long timeOfOldestEdit() {
    return timeOfOldestEdit;
  }

  private boolean addToKVSet(KeyValue e) {
    boolean b = this.kvset.add(e);
    setOldestEditTimeToNow();
    return b;
  }

  private boolean removeFromKVSet(KeyValue e) {
    boolean b = this.kvset.remove(e);
    setOldestEditTimeToNow();
    return b;
  }

  void setOldestEditTimeToNow() {
    if (timeOfOldestEdit == Long.MAX_VALUE) {
      timeOfOldestEdit = EnvironmentEdgeManager.currentTimeMillis();
    }
  }

  /**
   * Internal version of add() that doesn't clone KVs with the
   * allocator, and doesn't take the lock.
   *
   * Callers should ensure they already have the read lock taken
   */
  private long internalAdd(final KeyValue toAdd) {
    long s = heapSizeChange(toAdd, addToKVSet(toAdd));
    timeRangeTracker.includeTimestamp(toAdd);
    this.size.addAndGet(s);
    return s;
  }

  private KeyValue maybeCloneWithAllocator(KeyValue kv) {
    if (allocator == null) {
      return kv;
    }

    int len = kv.getLength();
    ByteRange alloc = allocator.allocateBytes(len);
    if (alloc == null) {
      // The allocation was too large, allocator decided
      // not to do anything with it.
      return kv;
    }
    assert alloc.getBytes() != null;
    alloc.put(0, kv.getBuffer(), kv.getOffset(), len);
    KeyValue newKv = new KeyValue(alloc.getBytes(), alloc.getOffset(), len);
    newKv.setMvccVersion(kv.getMvccVersion());
    return newKv;
  }

  /**
   * Remove n key from the memstore. Only kvs that have the same key and the
   * same memstoreTS are removed.  It is ok to not update timeRangeTracker
   * in this call. It is possible that we can optimize this method by using
   * tailMap/iterator, but since this method is called rarely (only for
   * error recovery), we can leave those optimization for the future.
   * @param cell
   */
  @Override
  public void rollback(Cell cell) {
    // If the key is in the snapshot, delete it. We should not update
    // this.size, because that tracks the size of only the memstore and
    // not the snapshot. The flush of this snapshot to disk has not
    // yet started because Store.flush() waits for all rwcc transactions to
    // commit before starting the flush to disk.
    KeyValue kv = KeyValueUtil.ensureKeyValue(cell);
    KeyValue found = this.snapshot.get(kv);
    if (found != null && found.getMvccVersion() == kv.getMvccVersion()) {
      this.snapshot.remove(kv);
    }
    // If the key is in the memstore, delete it. Update this.size.
    found = this.kvset.get(kv);
    if (found != null && found.getMvccVersion() == kv.getMvccVersion()) {
      removeFromKVSet(kv);
      long s = heapSizeChange(kv, true);
      this.size.addAndGet(-s);
    }
  }

  /**
   * Write a delete
   * @param deleteCell
   * @return approximate size of the passed key and value.
   */
  @Override
  public long delete(Cell deleteCell) {
    long s = 0;
    KeyValue toAdd = maybeCloneWithAllocator(KeyValueUtil.ensureKeyValue(deleteCell));
    s += heapSizeChange(toAdd, addToKVSet(toAdd));
    timeRangeTracker.includeTimestamp(toAdd);
    this.size.addAndGet(s);
    return s;
  }

  /**
   * @param kv Find the row that comes after this one.  If null, we return the
   * first.
   * @return Next row or null if none found.
   */
  KeyValue getNextRow(final KeyValue kv) {
    return getLowest(getNextRow(kv, this.kvset), getNextRow(kv, this.snapshot));
  }

  /*
   * @param a
   * @param b
   * @return Return lowest of a or b or null if both a and b are null
   */
  private KeyValue getLowest(final KeyValue a, final KeyValue b) {
    if (a == null) {
      return b;
    }
    if (b == null) {
      return a;
    }
    return comparator.compareRows(a, b) <= 0? a: b;
  }

  /*
   * @param key Find row that follows this one.  If null, return first.
   * @param map Set to look in for a row beyond <code>row</code>.
   * @return Next row or null if none found.  If one found, will be a new
   * KeyValue -- can be destroyed by subsequent calls to this method.
   */
  private KeyValue getNextRow(final KeyValue key,
      final NavigableSet<KeyValue> set) {
    KeyValue result = null;
    SortedSet<KeyValue> tail = key == null? set: set.tailSet(key);
    // Iterate until we fall into the next row; i.e. move off current row
    for (KeyValue kv: tail) {
      if (comparator.compareRows(kv, key) <= 0)
        continue;
      // Note: Not suppressing deletes or expired cells.  Needs to be handled
      // by higher up functions.
      result = kv;
      break;
    }
    return result;
  }

  /**
   * @param state column/delete tracking state
   */
  @Override
  public void getRowKeyAtOrBefore(final GetClosestRowBeforeTracker state) {
    getRowKeyAtOrBefore(kvset, state);
    getRowKeyAtOrBefore(snapshot, state);
  }

  /*
   * @param set
   * @param state Accumulates deletes and candidates.
   */
  private void getRowKeyAtOrBefore(final NavigableSet<KeyValue> set,
      final GetClosestRowBeforeTracker state) {
    if (set.isEmpty()) {
      return;
    }
    if (!walkForwardInSingleRow(set, state.getTargetKey(), state)) {
      // Found nothing in row.  Try backing up.
      getRowKeyBefore(set, state);
    }
  }

  /*
   * Walk forward in a row from <code>firstOnRow</code>.  Presumption is that
   * we have been passed the first possible key on a row.  As we walk forward
   * we accumulate deletes until we hit a candidate on the row at which point
   * we return.
   * @param set
   * @param firstOnRow First possible key on this row.
   * @param state
   * @return True if we found a candidate walking this row.
   */
  private boolean walkForwardInSingleRow(final SortedSet<KeyValue> set,
      final KeyValue firstOnRow, final GetClosestRowBeforeTracker state) {
    boolean foundCandidate = false;
    SortedSet<KeyValue> tail = set.tailSet(firstOnRow);
    if (tail.isEmpty()) return foundCandidate;
    for (Iterator<KeyValue> i = tail.iterator(); i.hasNext();) {
      KeyValue kv = i.next();
      // Did we go beyond the target row? If so break.
      if (state.isTooFar(kv, firstOnRow)) break;
      if (state.isExpired(kv)) {
        i.remove();
        continue;
      }
      // If we added something, this row is a contender. break.
      if (state.handle(kv)) {
        foundCandidate = true;
        break;
      }
    }
    return foundCandidate;
  }

  /*
   * Walk backwards through the passed set a row at a time until we run out of
   * set or until we get a candidate.
   * @param set
   * @param state
   */
  private void getRowKeyBefore(NavigableSet<KeyValue> set,
      final GetClosestRowBeforeTracker state) {
    KeyValue firstOnRow = state.getTargetKey();
    for (Member p = memberOfPreviousRow(set, state, firstOnRow);
        p != null; p = memberOfPreviousRow(p.set, state, firstOnRow)) {
      // Make sure we don't fall out of our table.
      if (!state.isTargetTable(p.kv)) break;
      // Stop looking if we've exited the better candidate range.
      if (!state.isBetterCandidate(p.kv)) break;
      // Make into firstOnRow
      firstOnRow = new KeyValue(p.kv.getRowArray(), p.kv.getRowOffset(), p.kv.getRowLength(),
          HConstants.LATEST_TIMESTAMP);
      // If we find something, break;
      if (walkForwardInSingleRow(p.set, firstOnRow, state)) break;
    }
  }

  /**
   * Only used by tests. TODO: Remove
   *
   * Given the specs of a column, update it, first by inserting a new record,
   * then removing the old one.  Since there is only 1 KeyValue involved, the memstoreTS
   * will be set to 0, thus ensuring that they instantly appear to anyone. The underlying
   * store will ensure that the insert/delete each are atomic. A scanner/reader will either
   * get the new value, or the old value and all readers will eventually only see the new
   * value after the old was removed.
   *
   * @param row
   * @param family
   * @param qualifier
   * @param newValue
   * @param now
   * @return  Timestamp
   */
  public long updateColumnValue(byte[] row,
                                byte[] family,
                                byte[] qualifier,
                                long newValue,
                                long now) {
    KeyValue firstKv = KeyValueUtil.createFirstOnRow(
        row, family, qualifier);
    // Is there a KeyValue in 'snapshot' with the same TS? If so, upgrade the timestamp a bit.
    SortedSet<KeyValue> snSs = snapshot.tailSet(firstKv);
    if (!snSs.isEmpty()) {
      KeyValue snKv = snSs.first();
      // is there a matching KV in the snapshot?
      if (CellUtil.matchingRow(snKv, firstKv) && CellUtil.matchingQualifier(snKv, firstKv)) {
        if (snKv.getTimestamp() == now) {
          // poop,
          now += 1;
        }
      }
    }

    // logic here: the new ts MUST be at least 'now'. But it could be larger if necessary.
    // But the timestamp should also be max(now, mostRecentTsInMemstore)

    // so we cant add the new KV w/o knowing what's there already, but we also
    // want to take this chance to delete some kvs. So two loops (sad)

    SortedSet<KeyValue> ss = kvset.tailSet(firstKv);
    for (KeyValue kv : ss) {
      // if this isnt the row we are interested in, then bail:
      if (!CellUtil.matchingColumn(kv, family, qualifier) || !CellUtil.matchingRow(kv, firstKv)) {
        break; // rows dont match, bail.
      }

      // if the qualifier matches and it's a put, just RM it out of the kvset.
      if (kv.getTypeByte() == KeyValue.Type.Put.getCode() &&
          kv.getTimestamp() > now && CellUtil.matchingQualifier(firstKv, kv)) {
        now = kv.getTimestamp();
      }
    }

    // create or update (upsert) a new KeyValue with
    // 'now' and a 0 memstoreTS == immediately visible
    List<Cell> cells = new ArrayList<Cell>(1);
    cells.add(new KeyValue(row, family, qualifier, now, Bytes.toBytes(newValue)));
    return upsert(cells, 1L);
  }

  /**
   * Update or insert the specified KeyValues.
   * <p>
   * For each KeyValue, insert into MemStore.  This will atomically upsert the
   * value for that row/family/qualifier.  If a KeyValue did already exist,
   * it will then be removed.
   * <p>
   * Currently the memstoreTS is kept at 0 so as each insert happens, it will
   * be immediately visible.  May want to change this so it is atomic across
   * all KeyValues.
   * <p>
   * This is called under row lock, so Get operations will still see updates
   * atomically.  Scans will only see each KeyValue update as atomic.
   *
   * @param cells
   * @param readpoint readpoint below which we can safely remove duplicate KVs 
   * @return change in memstore size
   */
  @Override
  public long upsert(Iterable<Cell> cells, long readpoint) {
    long size = 0;
    for (Cell cell : cells) {
      size += upsert(cell, readpoint);
    }
    return size;
  }

  /**
   * Inserts the specified KeyValue into MemStore and deletes any existing
   * versions of the same row/family/qualifier as the specified KeyValue.
   * <p>
   * First, the specified KeyValue is inserted into the Memstore.
   * <p>
   * If there are any existing KeyValues in this MemStore with the same row,
   * family, and qualifier, they are removed.
   * <p>
   * Callers must hold the read lock.
   *
   * @param cell
   * @return change in size of MemStore
   */
  private long upsert(Cell cell, long readpoint) {
    // Add the KeyValue to the MemStore
    // Use the internalAdd method here since we (a) already have a lock
    // and (b) cannot safely use the MSLAB here without potentially
    // hitting OOME - see TestMemStore.testUpsertMSLAB for a
    // test that triggers the pathological case if we don't avoid MSLAB
    // here.
    KeyValue kv = KeyValueUtil.ensureKeyValue(cell);
    long addedSize = internalAdd(kv);

    // Get the KeyValues for the row/family/qualifier regardless of timestamp.
    // For this case we want to clean up any other puts
    KeyValue firstKv = KeyValueUtil.createFirstOnRow(
        kv.getRowArray(), kv.getRowOffset(), kv.getRowLength(),
        kv.getFamilyArray(), kv.getFamilyOffset(), kv.getFamilyLength(),
        kv.getQualifierArray(), kv.getQualifierOffset(), kv.getQualifierLength());
    SortedSet<KeyValue> ss = kvset.tailSet(firstKv);
    Iterator<KeyValue> it = ss.iterator();
    // versions visible to oldest scanner
    int versionsVisible = 0;
    while ( it.hasNext() ) {
      KeyValue cur = it.next();

      if (kv == cur) {
        // ignore the one just put in
        continue;
      }
      // check that this is the row and column we are interested in, otherwise bail
      if (CellUtil.matchingRow(kv, cur) && CellUtil.matchingQualifier(kv, cur)) {
        // only remove Puts that concurrent scanners cannot possibly see
        if (cur.getTypeByte() == KeyValue.Type.Put.getCode() &&
            cur.getMvccVersion() <= readpoint) {
          if (versionsVisible > 1) {
            // if we get here we have seen at least one version visible to the oldest scanner,
            // which means we can prove that no scanner will see this version

            // false means there was a change, so give us the size.
            long delta = heapSizeChange(cur, true);
            addedSize -= delta;
            this.size.addAndGet(-delta);
            it.remove();
            setOldestEditTimeToNow();
          } else {
            versionsVisible++;
          }
        }
      } else {
        // past the row or column, done
        break;
      }
    }
    return addedSize;
  }

  /*
   * Immutable data structure to hold member found in set and the set it was
   * found in.  Include set because it is carrying context.
   */
  private static class Member {
    final KeyValue kv;
    final NavigableSet<KeyValue> set;
    Member(final NavigableSet<KeyValue> s, final KeyValue kv) {
      this.kv = kv;
      this.set = s;
    }
  }

  /*
   * @param set Set to walk back in.  Pass a first in row or we'll return
   * same row (loop).
   * @param state Utility and context.
   * @param firstOnRow First item on the row after the one we want to find a
   * member in.
   * @return Null or member of row previous to <code>firstOnRow</code>
   */
  private Member memberOfPreviousRow(NavigableSet<KeyValue> set,
      final GetClosestRowBeforeTracker state, final KeyValue firstOnRow) {
    NavigableSet<KeyValue> head = set.headSet(firstOnRow, false);
    if (head.isEmpty()) return null;
    for (Iterator<KeyValue> i = head.descendingIterator(); i.hasNext();) {
      KeyValue found = i.next();
      if (state.isExpired(found)) {
        i.remove();
        continue;
      }
      return new Member(head, found);
    }
    return null;
  }

  /**
   * @return scanner on memstore and snapshot in this order.
   */
  @Override
  public List<KeyValueScanner> getScanners(long readPt) {
    return Collections.<KeyValueScanner> singletonList(new MemStoreScanner(readPt));
  }

  /**
   * Check if this memstore may contain the required keys
   * @param scan
   * @return False if the key definitely does not exist in this Memstore
   */
  public boolean shouldSeek(Scan scan, long oldestUnexpiredTS) {
    return (timeRangeTracker.includesTimeRange(scan.getTimeRange()) ||
        snapshotTimeRangeTracker.includesTimeRange(scan.getTimeRange()))
        && (Math.max(timeRangeTracker.getMaximumTimestamp(),
                     snapshotTimeRangeTracker.getMaximumTimestamp()) >=
            oldestUnexpiredTS);
  }

  /*
   * MemStoreScanner implements the KeyValueScanner.
   * It lets the caller scan the contents of a memstore -- both current
   * map and snapshot.
   * This behaves as if it were a real scanner but does not maintain position.
   */
  protected class MemStoreScanner extends NonLazyKeyValueScanner {
    // Next row information for either kvset or snapshot
    private KeyValue kvsetNextRow = null;
    private KeyValue snapshotNextRow = null;

    // last iterated KVs for kvset and snapshot (to restore iterator state after reseek)
    private KeyValue kvsetItRow = null;
    private KeyValue snapshotItRow = null;
    
    // iterator based scanning.
    private Iterator<KeyValue> kvsetIt;
    private Iterator<KeyValue> snapshotIt;

    // The kvset and snapshot at the time of creating this scanner
    private KeyValueSkipListSet kvsetAtCreation;
    private KeyValueSkipListSet snapshotAtCreation;

    // the pre-calculated KeyValue to be returned by peek() or next()
    private KeyValue theNext;

    // The allocator and snapshot allocator at the time of creating this scanner
    volatile MemStoreLAB allocatorAtCreation;
    volatile MemStoreLAB snapshotAllocatorAtCreation;
    
    // A flag represents whether could stop skipping KeyValues for MVCC
    // if have encountered the next row. Only used for reversed scan
    private boolean stopSkippingKVsIfNextRow = false;

    private long readPoint;

    /*
    Some notes...

     So memstorescanner is fixed at creation time. this includes pointers/iterators into
    existing kvset/snapshot.  during a snapshot creation, the kvset is null, and the
    snapshot is moved.  since kvset is null there is no point on reseeking on both,
      we can save us the trouble. During the snapshot->hfile transition, the memstore
      scanner is re-created by StoreScanner#updateReaders().  StoreScanner should
      potentially do something smarter by adjusting the existing memstore scanner.

      But there is a greater problem here, that being once a scanner has progressed
      during a snapshot scenario, we currently iterate past the kvset then 'finish' up.
      if a scan lasts a little while, there is a chance for new entries in kvset to
      become available but we will never see them.  This needs to be handled at the
      StoreScanner level with coordination with MemStoreScanner.

      Currently, this problem is only partly managed: during the small amount of time
      when the StoreScanner has not yet created a new MemStoreScanner, we will miss
      the adds to kvset in the MemStoreScanner.
    */

    MemStoreScanner(long readPoint) {
      super();

      this.readPoint = readPoint;
      kvsetAtCreation = kvset;
      snapshotAtCreation = snapshot;
      if (allocator != null) {
        this.allocatorAtCreation = allocator;
        this.allocatorAtCreation.incScannerCount();
      }
      if (snapshotAllocator != null) {
        this.snapshotAllocatorAtCreation = snapshotAllocator;
        this.snapshotAllocatorAtCreation.incScannerCount();
      }
    }

    private KeyValue getNext(Iterator<KeyValue> it) {
      KeyValue startKV = theNext;
      KeyValue v = null;
      try {
        while (it.hasNext()) {
          v = it.next();
          if (v.getMvccVersion() <= this.readPoint) {
            return v;
          }
          if (stopSkippingKVsIfNextRow && startKV != null
              && comparator.compareRows(v, startKV) > 0) {
            return null;
          }
        }

        return null;
      } finally {
        if (v != null) {
          // in all cases, remember the last KV iterated to
          if (it == snapshotIt) {
            snapshotItRow = v;
          } else {
            kvsetItRow = v;
          }
        }
      }
    }

    /**
     *  Set the scanner at the seek key.
     *  Must be called only once: there is no thread safety between the scanner
     *   and the memStore.
     * @param key seek value
     * @return false if the key is null or if there is no data
     */
    @Override
    public synchronized boolean seek(Cell key) {
      if (key == null) {
        close();
        return false;
      }
      KeyValue kv = KeyValueUtil.ensureKeyValue(key);
      // kvset and snapshot will never be null.
      // if tailSet can't find anything, SortedSet is empty (not null).
      kvsetIt = kvsetAtCreation.tailSet(kv).iterator();
      snapshotIt = snapshotAtCreation.tailSet(kv).iterator();
      kvsetItRow = null;
      snapshotItRow = null;

      return seekInSubLists(kv);
    }


    /**
     * (Re)initialize the iterators after a seek or a reseek.
     */
    private synchronized boolean seekInSubLists(KeyValue key){
      kvsetNextRow = getNext(kvsetIt);
      snapshotNextRow = getNext(snapshotIt);

      // Calculate the next value
      theNext = getLowest(kvsetNextRow, snapshotNextRow);

      // has data
      return (theNext != null);
    }


    /**
     * Move forward on the sub-lists set previously by seek.
     * @param key seek value (should be non-null)
     * @return true if there is at least one KV to read, false otherwise
     */
    @Override
    public synchronized boolean reseek(Cell key) {
      /*
      See HBASE-4195 & HBASE-3855 & HBASE-6591 for the background on this implementation.
      This code is executed concurrently with flush and puts, without locks.
      Two points must be known when working on this code:
      1) It's not possible to use the 'kvTail' and 'snapshot'
       variables, as they are modified during a flush.
      2) The ideal implementation for performance would use the sub skip list
       implicitly pointed by the iterators 'kvsetIt' and
       'snapshotIt'. Unfortunately the Java API does not offer a method to
       get it. So we remember the last keys we iterated to and restore
       the reseeked set to at least that point.
       */
      KeyValue kv = KeyValueUtil.ensureKeyValue(key);
      kvsetIt = kvsetAtCreation.tailSet(getHighest(kv, kvsetItRow)).iterator();
      snapshotIt = snapshotAtCreation.tailSet(getHighest(kv, snapshotItRow)).iterator();

      return seekInSubLists(kv);
    }


    @Override
    public synchronized KeyValue peek() {
      //DebugPrint.println(" MS@" + hashCode() + " peek = " + getLowest());
      return theNext;
    }

    @Override
    public synchronized KeyValue next() {
      if (theNext == null) {
          return null;
      }

      final KeyValue ret = theNext;

      // Advance one of the iterators
      if (theNext == kvsetNextRow) {
        kvsetNextRow = getNext(kvsetIt);
      } else {
        snapshotNextRow = getNext(snapshotIt);
      }

      // Calculate the next value
      theNext = getLowest(kvsetNextRow, snapshotNextRow);

      //long readpoint = ReadWriteConsistencyControl.getThreadReadPoint();
      //DebugPrint.println(" MS@" + hashCode() + " next: " + theNext + " next_next: " +
      //    getLowest() + " threadpoint=" + readpoint);
      return ret;
    }

    /*
     * Returns the lower of the two key values, or null if they are both null.
     * This uses comparator.compare() to compare the KeyValue using the memstore
     * comparator.
     */
    private KeyValue getLowest(KeyValue first, KeyValue second) {
      if (first == null && second == null) {
        return null;
      }
      if (first != null && second != null) {
        int compare = comparator.compare(first, second);
        return (compare <= 0 ? first : second);
      }
      return (first != null ? first : second);
    }

    /*
     * Returns the higher of the two key values, or null if they are both null.
     * This uses comparator.compare() to compare the KeyValue using the memstore
     * comparator.
     */
    private KeyValue getHighest(KeyValue first, KeyValue second) {
      if (first == null && second == null) {
        return null;
      }
      if (first != null && second != null) {
        int compare = comparator.compare(first, second);
        return (compare > 0 ? first : second);
      }
      return (first != null ? first : second);
    }

    public synchronized void close() {
      this.kvsetNextRow = null;
      this.snapshotNextRow = null;

      this.kvsetIt = null;
      this.snapshotIt = null;
      
      if (allocatorAtCreation != null) {
        this.allocatorAtCreation.decScannerCount();
        this.allocatorAtCreation = null;
      }
      if (snapshotAllocatorAtCreation != null) {
        this.snapshotAllocatorAtCreation.decScannerCount();
        this.snapshotAllocatorAtCreation = null;
      }

      this.kvsetItRow = null;
      this.snapshotItRow = null;
    }

    /**
     * MemStoreScanner returns max value as sequence id because it will
     * always have the latest data among all files.
     */
    @Override
    public long getSequenceID() {
      return Long.MAX_VALUE;
    }

    @Override
    public boolean shouldUseScanner(Scan scan, SortedSet<byte[]> columns,
        long oldestUnexpiredTS) {
      return shouldSeek(scan, oldestUnexpiredTS);
    }

    /**
     * Seek scanner to the given key first. If it returns false(means
     * peek()==null) or scanner's peek row is bigger than row of given key, seek
     * the scanner to the previous row of given key
     */
    @Override
    public synchronized boolean backwardSeek(Cell key) {
      seek(key);
      if (peek() == null || comparator.compareRows(peek(), key) > 0) {
        return seekToPreviousRow(key);
      }
      return true;
    }

    /**
     * Separately get the KeyValue before the specified key from kvset and
     * snapshotset, and use the row of higher one as the previous row of
     * specified key, then seek to the first KeyValue of previous row
     */
    @Override
    public synchronized boolean seekToPreviousRow(Cell key) {
      KeyValue firstKeyOnRow = KeyValueUtil.createFirstOnRow(key.getRowArray(), key.getRowOffset(),
          key.getRowLength());
      SortedSet<KeyValue> kvHead = kvsetAtCreation.headSet(firstKeyOnRow);
      KeyValue kvsetBeforeRow = kvHead.isEmpty() ? null : kvHead.last();
      SortedSet<KeyValue> snapshotHead = snapshotAtCreation
          .headSet(firstKeyOnRow);
      KeyValue snapshotBeforeRow = snapshotHead.isEmpty() ? null : snapshotHead
          .last();
      KeyValue lastKVBeforeRow = getHighest(kvsetBeforeRow, snapshotBeforeRow);
      if (lastKVBeforeRow == null) {
        theNext = null;
        return false;
      }
      KeyValue firstKeyOnPreviousRow = KeyValueUtil.createFirstOnRow(lastKVBeforeRow.getRowArray(),
          lastKVBeforeRow.getRowOffset(), lastKVBeforeRow.getRowLength());
      this.stopSkippingKVsIfNextRow = true;
      seek(firstKeyOnPreviousRow);
      this.stopSkippingKVsIfNextRow = false;
      if (peek() == null
          || comparator.compareRows(peek(), firstKeyOnPreviousRow) > 0) {
        return seekToPreviousRow(lastKVBeforeRow);
      }
      return true;
    }

    @Override
    public synchronized boolean seekToLastRow() {
      KeyValue first = kvsetAtCreation.isEmpty() ? null : kvsetAtCreation
          .last();
      KeyValue second = snapshotAtCreation.isEmpty() ? null
          : snapshotAtCreation.last();
      KeyValue higherKv = getHighest(first, second);
      if (higherKv == null) {
        return false;
      }
      KeyValue firstKvOnLastRow = KeyValueUtil.createFirstOnRow(higherKv.getRowArray(),
          higherKv.getRowOffset(), higherKv.getRowLength());
      if (seek(firstKvOnLastRow)) {
        return true;
      } else {
        return seekToPreviousRow(higherKv);
      }

    }
  }

  public final static long FIXED_OVERHEAD = ClassSize.align(
      ClassSize.OBJECT + (9 * ClassSize.REFERENCE) + (3 * Bytes.SIZEOF_LONG));

  public final static long DEEP_OVERHEAD = ClassSize.align(FIXED_OVERHEAD +
      ClassSize.ATOMIC_LONG + (2 * ClassSize.TIMERANGE_TRACKER) +
      (2 * ClassSize.KEYVALUE_SKIPLIST_SET) + (2 * ClassSize.CONCURRENT_SKIPLISTMAP));

  /*
   * Calculate how the MemStore size has changed.  Includes overhead of the
   * backing Map.
   * @param kv
   * @param notpresent True if the kv was NOT present in the set.
   * @return Size
   */
  static long heapSizeChange(final KeyValue kv, final boolean notpresent) {
    return notpresent ?
        ClassSize.align(ClassSize.CONCURRENT_SKIPLISTMAP_ENTRY + kv.heapSize()):
        0;
  }

  private long keySize() {
    return heapSize() - DEEP_OVERHEAD;
  }

  /**
   * Get the entire heap usage for this MemStore not including keys in the
   * snapshot.
   */
  @Override
  public long heapSize() {
    return size.get();
  }

  @Override
  public long size() {
    return heapSize();
  }
 
  /**
   * Code to help figure if our approximation of object heap sizes is close
   * enough.  See hbase-900.  Fills memstores then waits so user can heap
   * dump and bring up resultant hprof in something like jprofiler which
   * allows you get 'deep size' on objects.
   * @param args main args
   */
  public static void main(String [] args) {
    RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
    LOG.info("vmName=" + runtime.getVmName() + ", vmVendor=" +
      runtime.getVmVendor() + ", vmVersion=" + runtime.getVmVersion());
    LOG.info("vmInputArguments=" + runtime.getInputArguments());
    DefaultMemStore memstore1 = new DefaultMemStore();
    // TODO: x32 vs x64
    long size = 0;
    final int count = 10000;
    byte [] fam = Bytes.toBytes("col");
    byte [] qf = Bytes.toBytes("umn");
    byte [] empty = new byte[0];
    for (int i = 0; i < count; i++) {
      // Give each its own ts
      size += memstore1.add(new KeyValue(Bytes.toBytes(i), fam, qf, i, empty));
    }
    LOG.info("memstore1 estimated size=" + size);
    for (int i = 0; i < count; i++) {
      size += memstore1.add(new KeyValue(Bytes.toBytes(i), fam, qf, i, empty));
    }
    LOG.info("memstore1 estimated size (2nd loading of same data)=" + size);
    // Make a variably sized memstore.
    DefaultMemStore memstore2 = new DefaultMemStore();
    for (int i = 0; i < count; i++) {
      size += memstore2.add(new KeyValue(Bytes.toBytes(i), fam, qf, i,
        new byte[i]));
    }
    LOG.info("memstore2 estimated size=" + size);
    final int seconds = 30;
    LOG.info("Waiting " + seconds + " seconds while heap dump is taken");
    for (int i = 0; i < seconds; i++) {
      // Thread.sleep(1000);
    }
    LOG.info("Exiting.");
  }

}
