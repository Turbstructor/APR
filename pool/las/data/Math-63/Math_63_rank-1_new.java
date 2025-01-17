/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.cf.taste.impl.recommender.slopeone.file;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

import org.apache.mahout.cf.taste.common.Refreshable;
import org.apache.mahout.cf.taste.common.Weighting;
import org.apache.mahout.cf.taste.impl.common.FastByIDMap;
import org.apache.mahout.cf.taste.impl.common.FastIDSet;
import org.apache.mahout.cf.taste.impl.common.FullRunningAverage;
import org.apache.mahout.cf.taste.impl.common.FullRunningAverageAndStdDev;
import org.apache.mahout.cf.taste.impl.common.InvertedRunningAverage;
import org.apache.mahout.cf.taste.impl.common.LongPrimitiveIterator;
import org.apache.mahout.cf.taste.impl.common.RunningAverage;
import org.apache.mahout.cf.taste.impl.recommender.slopeone.SlopeOneRecommender;
import org.apache.mahout.cf.taste.model.PreferenceArray;
import org.apache.mahout.cf.taste.recommender.slopeone.DiffStorage;
import org.apache.mahout.common.iterator.FileLineIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

/**
 * <p>
 * {@link DiffStorage} which reads pre-computed diffs from a file and stores in memory. The file should have
 * one diff per line:
 * </p>
 * 
 * {@code itemID1,itemID2,diff[,count[,mk,sk]]}
 * 
 * <p>
 * The fourth column is optional, and is a count representing the number of occurrences of the item-item pair
 * that contribute to the diff. It is assumed to be 1 if not present. The fifth and sixth arguments are
 * computed values used by {@link FullRunningAverageAndStdDev} implementations to compute a running standard deviation.
 * They are required if using {@link Weighting#WEIGHTED} with {@link SlopeOneRecommender}.
 * </p>
 *
 * <p>
 * Commas or tabs can be delimiters. This is intended for use in conjuction with the output of
 * {@link org.apache.mahout.cf.taste.hadoop.slopeone.SlopeOneAverageDiffsJob}.
 * </p>
 *
 * <p>Note that the same item-item pair should not appear on multiple lines -- one line per item-item pair.</p>
 */
public final class FileDiffStorage implements DiffStorage {
  
  private static final Logger log = LoggerFactory.getLogger(FileDiffStorage.class);
  
  private static final long MIN_RELOAD_INTERVAL_MS = 60 * 1000L; // 1 minute?
  private static final char COMMENT_CHAR = '#';
  private static final Pattern SEPARATOR = Pattern.compile("[\t,]");

  private final File dataFile;
  private long lastModified;
  private final long maxEntries;
  private final FastByIDMap<FastByIDMap<RunningAverage>> averageDiffs;
  private final FastIDSet allRecommendableItemIDs;
  private final ReadWriteLock buildAverageDiffsLock;
  
  /**
   * @param dataFile
   *          diffs file
   * @param maxEntries
   *          maximum number of diffs to store
   * @throws FileNotFoundException
   *           if data file does not exist or is a directory
   */
  public FileDiffStorage(File dataFile, long maxEntries) throws FileNotFoundException {
    Preconditions.checkArgument(dataFile != null, "dataFile is null");
    if (!dataFile.exists() || dataFile.isDirectory()) {
      throw new FileNotFoundException(dataFile.toString());
    }
    Preconditions.checkArgument(maxEntries > 0L, "maxEntries must be positive");
    log.info("Creating FileDataModel for file {}", dataFile);
    this.dataFile = dataFile.getAbsoluteFile();
    this.lastModified = dataFile.lastModified();
    this.maxEntries = maxEntries;
    this.averageDiffs = new FastByIDMap<FastByIDMap<RunningAverage>>();
    this.allRecommendableItemIDs = new FastIDSet();
    this.buildAverageDiffsLock = new ReentrantReadWriteLock();

    buildDiffs();
  }
  
  private void buildDiffs() {
    if (buildAverageDiffsLock.writeLock().tryLock()) {
      try {

        averageDiffs.clear();
        allRecommendableItemIDs.clear();
        
        FileLineIterator iterator = new FileLineIterator(dataFile, false);
        String firstLine = iterator.peek();
        while (firstLine.isEmpty() || firstLine.charAt(0) == COMMENT_CHAR) {
          iterator.next();
          firstLine = iterator.peek();
        }
        long averageCount = 0L;
        while (iterator.hasNext()) {
          averageCount = processLine(iterator.next(), averageCount);
        }
        
        pruneInconsequentialDiffs();
        updateAllRecommendableItems();
        
      } catch (IOException ioe) {
        log.warn("Exception while reloading", ioe);
      } finally {
        buildAverageDiffsLock.writeLock().unlock();
      }
    }
  }
  
  private long processLine(String line, long averageCount) {

    if (line.isEmpty() || line.charAt(0) == COMMENT_CHAR) {
      return averageCount;
    }
    
    String[] tokens = SEPARATOR.split(line);
    Preconditions.checkArgument(tokens.length >=3 && tokens.length != 5, "Bad line: %s", line);

    long itemID1 = Long.parseLong(tokens[0]);
    long itemID2 = Long.parseLong(tokens[1]);
    double diff = Double.parseDouble(tokens[2]);
    int count = tokens.length >= 4 ? Integer.parseInt(tokens[3]) : 1;
    boolean hasMkSk = tokens.length >= 5;
    
    if (itemID1 > itemID2) {
      long temp = itemID1;
      itemID1 = itemID2;
      itemID2 = temp;
    }
    
    FastByIDMap<RunningAverage> level1Map = averageDiffs.get(itemID1);
    if (level1Map == null) {
      level1Map = new FastByIDMap<RunningAverage>();
      averageDiffs.put(itemID1, level1Map);
    }
    RunningAverage average = level1Map.get(itemID2);
    if (average != null) {
      throw new IllegalArgumentException("Duplicated line for item-item pair " + itemID1 + " / " + itemID2);
    }
    if (averageCount < maxEntries) {
      if (hasMkSk) {
        double mk = Double.parseDouble(tokens[4]);
        double sk = Double.parseDouble(tokens[5]);
        average = new FullRunningAverageAndStdDev(count, diff, mk, sk);
      } else {
        average = new FullRunningAverage(count, diff);
      }
      level1Map.put(itemID2, average);
      averageCount++;
    }

    allRecommendableItemIDs.add(itemID1);
    allRecommendableItemIDs.add(itemID2);
    
    return averageCount;
  }
  
  private void pruneInconsequentialDiffs() {
    // Go back and prune inconsequential diffs. "Inconsequential" means, here, only represented by one
    // data point, so possibly unreliable
    Iterator<Map.Entry<Long,FastByIDMap<RunningAverage>>> it1 = averageDiffs.entrySet().iterator();
    while (it1.hasNext()) {
      FastByIDMap<RunningAverage> map = it1.next().getValue();
      Iterator<Map.Entry<Long,RunningAverage>> it2 = map.entrySet().iterator();
      while (it2.hasNext()) {
        RunningAverage average = it2.next().getValue();
        if (average.getCount() <= 1) {
          it2.remove();
        }
      }
      if (map.isEmpty()) {
        it1.remove();
      } else {
        map.rehash();
      }
    }
    averageDiffs.rehash();
  }
  
  private void updateAllRecommendableItems() {
    for (Map.Entry<Long,FastByIDMap<RunningAverage>> entry : averageDiffs.entrySet()) {
      allRecommendableItemIDs.add(entry.getKey());
      LongPrimitiveIterator it = entry.getValue().keySetIterator();
      while (it.hasNext()) {
        allRecommendableItemIDs.add(it.next());
      }
    }
    allRecommendableItemIDs.rehash();
  }
  
  @Override
  public RunningAverage getDiff(long itemID1, long itemID2) {

    boolean inverted = false;
    if (itemID1 > itemID2) {
      inverted = true;
      long temp = itemID1;
      itemID1 = itemID2;
      itemID2 = temp;
    }
    
    FastByIDMap<RunningAverage> level2Map;
    try {
      buildAverageDiffsLock.readLock().lock();
      level2Map = averageDiffs.get(itemID1);
    } finally {
      buildAverageDiffsLock.readLock().unlock();
    }
    RunningAverage average = null;
    if (level2Map != null) {
      average = level2Map.get(itemID2);
    }
    if (inverted) {
      return average == null ? null : average.inverse();
    } else {
      return average;
    }
  }
  
  @Override
  public RunningAverage[] getDiffs(long userID, long itemID, PreferenceArray prefs) {
    try {
      buildAverageDiffsLock.readLock().lock();
      int size = prefs.length();
      RunningAverage[] result = new RunningAverage[size];
      for (int i = 0; i < size; i++) {
        result[i] = getDiff(prefs.getItemID(i), itemID);
      }
      return result;
    } finally {
      buildAverageDiffsLock.readLock().unlock();
    }
  }
  
  @Override
  public RunningAverage getAverageItemPref(long itemID) {
    return null; // TODO can't do this without a DataModel
  }

  @Override
  public void addItemPref(long userID, long itemIDA, float prefValue) {
    // Can't do this without a DataModel; should it just be a no-op?
    throw new UnsupportedOperationException();
  }

  @Override
  public void updateItemPref(long itemID, float prefDelta) {
    try {
      buildAverageDiffsLock.readLock().lock();
      for (Map.Entry<Long,FastByIDMap<RunningAverage>> entry : averageDiffs.entrySet()) {
        boolean matchesItemID1 = itemID == entry.getKey();
        for (Map.Entry<Long,RunningAverage> entry2 : entry.getValue().entrySet()) {
          RunningAverage average = entry2.getValue();
          if (matchesItemID1) {
            average.changeDatum(-prefDelta);
          } else if (itemID == entry2.getKey()) {
            average.changeDatum(prefDelta);
          }
        }
      }
      // RunningAverage itemAverage = averageItemPref.get(itemID);
      // if (itemAverage != null) {
      // itemAverage.changeDatum(prefDelta);
      // }
    } finally {
      buildAverageDiffsLock.readLock().unlock();
    }
  }

  @Override
  public void removeItemPref(long userID, long itemIDA, float prefValue) {
    // Can't do this without a DataModel; should it just be a no-op?
    throw new UnsupportedOperationException();
  }
  
  @Override
  public FastIDSet getRecommendableItemIDs(long userID) {
    try {
      buildAverageDiffsLock.readLock().lock();
      return allRecommendableItemIDs.clone();
    } finally {
      buildAverageDiffsLock.readLock().unlock();
    }
  }
  
  @Override
  public void refresh(Collection<Refreshable> alreadyRefreshed) {
    long mostRecentModification = dataFile.lastModified();
    if (mostRecentModification > lastModified + MIN_RELOAD_INTERVAL_MS) {
      log.debug("File has changed; reloading...");
      lastModified = mostRecentModification;
      buildDiffs();
    }
  }
  
}
