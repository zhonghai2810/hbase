/*
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

package org.apache.hadoop.hbase;

import java.io.Serializable;
import java.util.Comparator;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.hbase.KeyValue.Type;
import org.apache.hadoop.hbase.util.Bytes;

import com.google.common.primitives.Longs;

/**
 * Compare two HBase cells.  Do not use this method comparing <code>-ROOT-</code> or
 * <code>hbase:meta</code> cells.  Cells from these tables need a specialized comparator, one that
 * takes account of the special formatting of the row where we have commas to delimit table from
 * regionname, from row.  See KeyValue for how it has a special comparator to do hbase:meta cells
 * and yet another for -ROOT-.
 */
@edu.umd.cs.findbugs.annotations.SuppressWarnings(
    value="UNKNOWN",
    justification="Findbugs doesn't like the way we are negating the result of a compare in below")
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class CellComparator implements Comparator<Cell>, Serializable{
  private static final long serialVersionUID = -8760041766259623329L;

  @Override
  public int compare(Cell a, Cell b) {
    return compareStatic(a, b, false);
  }

  public static int compareStatic(Cell a, Cell b, boolean onlyKey) {
    // row
    int c = compareRows(a, b);
    if (c != 0) return c;

    c = compareWithoutRow(a, b);
    if(c != 0) return c;

    if (!onlyKey) {
      // Negate following comparisons so later edits show up first

      // compare log replay tag value if there is any
      // when either keyvalue tagged with log replay sequence number, we need to compare them:
      // 1) when both keyvalues have the tag, then use the tag values for comparison
      // 2) when one has and the other doesn't have, the one without the log
      // replay tag wins because
      // it means the edit isn't from recovery but new one coming from clients during recovery
      // 3) when both doesn't have, then skip to the next mvcc comparison
      long leftChangeSeqNum = getReplaySeqNum(a);
      long RightChangeSeqNum = getReplaySeqNum(b);
      if (leftChangeSeqNum != Long.MAX_VALUE || RightChangeSeqNum != Long.MAX_VALUE) {
        return Longs.compare(RightChangeSeqNum, leftChangeSeqNum);
      }
      // mvccVersion: later sorts first
      return Longs.compare(b.getMvccVersion(), a.getMvccVersion());
    } else {
      return c;
    }
  }

  /**
   * Return replay log sequence number for the cell
   *
   * @param c
   * @return Long.MAX_VALUE if there is no LOG_REPLAY_TAG
   */
  private static long getReplaySeqNum(final Cell c) {
    Tag tag = Tag.getTag(c.getTagsArray(), c.getTagsOffset(), c.getTagsLength(),
        TagType.LOG_REPLAY_TAG_TYPE);

    if (tag != null) {
      return Bytes.toLong(tag.getBuffer(), tag.getTagOffset(), tag.getTagLength());
    }
    return Long.MAX_VALUE;
  }

  public static int findCommonPrefixInRowPart(Cell left, Cell right, int rowCommonPrefix) {
    return findCommonPrefix(left.getRowArray(), right.getRowArray(), left.getRowLength()
        - rowCommonPrefix, right.getRowLength() - rowCommonPrefix, left.getRowOffset()
        + rowCommonPrefix, right.getRowOffset() + rowCommonPrefix);
  }

  private static int findCommonPrefix(byte[] left, byte[] right, int leftLength, int rightLength,
      int leftOffset, int rightOffset) {
    int length = Math.min(leftLength, rightLength);
    int result = 0;

    while (result < length && left[leftOffset + result] == right[rightOffset + result]) {
      result++;
    }
    return result;
  }

  public static int findCommonPrefixInFamilyPart(Cell left, Cell right, int familyCommonPrefix) {
    return findCommonPrefix(left.getFamilyArray(), right.getFamilyArray(), left.getFamilyLength()
        - familyCommonPrefix, right.getFamilyLength() - familyCommonPrefix, left.getFamilyOffset()
        + familyCommonPrefix, right.getFamilyOffset() + familyCommonPrefix);
  }

  public static int findCommonPrefixInQualifierPart(Cell left, Cell right,
      int qualifierCommonPrefix) {
    return findCommonPrefix(left.getQualifierArray(), right.getQualifierArray(),
        left.getQualifierLength() - qualifierCommonPrefix, right.getQualifierLength()
            - qualifierCommonPrefix, left.getQualifierOffset() + qualifierCommonPrefix,
        right.getQualifierOffset() + qualifierCommonPrefix);
  }

  /**************** equals ****************************/

  public static boolean equals(Cell a, Cell b){
    return equalsRow(a, b)
        && equalsFamily(a, b)
        && equalsQualifier(a, b)
        && equalsTimestamp(a, b)
        && equalsType(a, b);
  }

  public static boolean equalsRow(Cell a, Cell b){
    return Bytes.equals(
      a.getRowArray(), a.getRowOffset(), a.getRowLength(),
      b.getRowArray(), b.getRowOffset(), b.getRowLength());
  }

  public static boolean equalsFamily(Cell a, Cell b){
    return Bytes.equals(
      a.getFamilyArray(), a.getFamilyOffset(), a.getFamilyLength(),
      b.getFamilyArray(), b.getFamilyOffset(), b.getFamilyLength());
  }

  public static boolean equalsQualifier(Cell a, Cell b){
    return Bytes.equals(
      a.getQualifierArray(), a.getQualifierOffset(), a.getQualifierLength(),
      b.getQualifierArray(), b.getQualifierOffset(), b.getQualifierLength());
  }

  public static boolean equalsTimestamp(Cell a, Cell b){
    return a.getTimestamp() == b.getTimestamp();
  }

  public static boolean equalsType(Cell a, Cell b){
    return a.getTypeByte() == b.getTypeByte();
  }

  public static int compareColumns(final Cell left, final Cell right) {
    int lfoffset = left.getFamilyOffset();
    int rfoffset = right.getFamilyOffset();
    int lclength = left.getQualifierLength();
    int rclength = right.getQualifierLength();
    int lfamilylength = left.getFamilyLength();
    int rfamilylength = right.getFamilyLength();
    int diff = compare(left.getFamilyArray(), lfoffset, lfamilylength, right.getFamilyArray(),
        rfoffset, rfamilylength);
    if (diff != 0) {
      return diff;
    } else {
      return compare(left.getQualifierArray(), left.getQualifierOffset(), lclength,
          right.getQualifierArray(), right.getQualifierOffset(), rclength);
    }
  }

  public static int compareFamilies(Cell left, Cell right) {
    return Bytes.compareTo(left.getFamilyArray(), left.getFamilyOffset(), left.getFamilyLength(),
        right.getFamilyArray(), right.getFamilyOffset(), right.getFamilyLength());
  }

  public static int compareQualifiers(Cell left, Cell right) {
    return Bytes.compareTo(left.getQualifierArray(), left.getQualifierOffset(),
        left.getQualifierLength(), right.getQualifierArray(), right.getQualifierOffset(),
        right.getQualifierLength());
  }

  public int compareFlatKey(Cell left, Cell right) {
    int compare = compareRows(left, right);
    if (compare != 0) {
      return compare;
    }
    return compareWithoutRow(left, right);
  }

  public static int compareRows(final Cell left, final Cell right) {
    return Bytes.compareTo(left.getRowArray(), left.getRowOffset(), left.getRowLength(),
        right.getRowArray(), right.getRowOffset(), right.getRowLength());
  }

  public static int compareRows(byte[] left, int loffset, int llength, byte[] right, int roffset,
      int rlength) {
    return Bytes.compareTo(left, loffset, llength, right, roffset, rlength);
  }

  public static int compareWithoutRow(final Cell leftCell, final Cell rightCell) {
    if (leftCell.getFamilyLength() + leftCell.getQualifierLength() == 0
        && leftCell.getTypeByte() == Type.Minimum.getCode()) {
      // left is "bigger", i.e. it appears later in the sorted order
      return 1;
    }
    if (rightCell.getFamilyLength() + rightCell.getQualifierLength() == 0
        && rightCell.getTypeByte() == Type.Minimum.getCode()) {
      return -1;
    }
    boolean sameFamilySize = (leftCell.getFamilyLength() == rightCell.getFamilyLength());
    if (!sameFamilySize) {
      // comparing column family is enough.

      return Bytes.compareTo(leftCell.getFamilyArray(), leftCell.getFamilyOffset(),
          leftCell.getFamilyLength(), rightCell.getFamilyArray(), rightCell.getFamilyOffset(),
          rightCell.getFamilyLength());
    }
    int diff = compareColumns(leftCell, rightCell);
    if (diff != 0) return diff;

    diff = compareTimestamps(leftCell, rightCell);
    if (diff != 0) return diff;

    // Compare types. Let the delete types sort ahead of puts; i.e. types
    // of higher numbers sort before those of lesser numbers. Maximum (255)
    // appears ahead of everything, and minimum (0) appears after
    // everything.
    return (0xff & rightCell.getTypeByte()) - (0xff & leftCell.getTypeByte());
  }

  public static int compareTimestamps(final Cell left, final Cell right) {
    long ltimestamp = left.getTimestamp();
    long rtimestamp = right.getTimestamp();
    return compareTimestamps(ltimestamp, rtimestamp);
  }

  /********************* hashCode ************************/

  /**
   * Returns a hash code that is always the same for two Cells having a matching equals(..) result.
   * Currently does not guard against nulls, but it could if necessary.
   */
  public static int hashCode(Cell cell){
    if (cell == null) {// return 0 for empty Cell
      return 0;
    }

    //pre-calculate the 3 hashes made of byte ranges
    int rowHash = Bytes.hashCode(cell.getRowArray(), cell.getRowOffset(), cell.getRowLength());
    int familyHash =
      Bytes.hashCode(cell.getFamilyArray(), cell.getFamilyOffset(), cell.getFamilyLength());
    int qualifierHash = Bytes.hashCode(cell.getQualifierArray(), cell.getQualifierOffset(),
      cell.getQualifierLength());

    //combine the 6 sub-hashes
    int hash = 31 * rowHash + familyHash;
    hash = 31 * hash + qualifierHash;
    hash = 31 * hash + (int)cell.getTimestamp();
    hash = 31 * hash + cell.getTypeByte();
    hash = 31 * hash + (int)cell.getMvccVersion();
    return hash;
  }


  /******************** lengths *************************/

  public static boolean areKeyLengthsEqual(Cell a, Cell b) {
    return a.getRowLength() == b.getRowLength()
        && a.getFamilyLength() == b.getFamilyLength()
        && a.getQualifierLength() == b.getQualifierLength();
  }

  public static boolean areRowLengthsEqual(Cell a, Cell b) {
    return a.getRowLength() == b.getRowLength();
  }


  /*********************common prefixes*************************/

  private static int compare(byte[] left, int leftOffset, int leftLength, byte[] right,
      int rightOffset, int rightLength) {
    return Bytes.compareTo(left, leftOffset, leftLength, right, rightOffset, rightLength);
  }

  public static int compareCommonRowPrefix(Cell left, Cell right, int rowCommonPrefix) {
    return compare(left.getRowArray(), left.getRowOffset() + rowCommonPrefix, left.getRowLength()
        - rowCommonPrefix, right.getRowArray(), right.getRowOffset() + rowCommonPrefix,
        right.getRowLength() - rowCommonPrefix);
  }

  public static int compareCommonFamilyPrefix(Cell left, Cell right,
      int familyCommonPrefix) {
    return compare(left.getFamilyArray(), left.getFamilyOffset() + familyCommonPrefix,
        left.getFamilyLength() - familyCommonPrefix, right.getFamilyArray(),
        right.getFamilyOffset() + familyCommonPrefix,
        right.getFamilyLength() - familyCommonPrefix);
  }

  public static int compareCommonQualifierPrefix(Cell left, Cell right,
      int qualCommonPrefix) {
    return compare(left.getQualifierArray(), left.getQualifierOffset() + qualCommonPrefix,
        left.getQualifierLength() - qualCommonPrefix, right.getQualifierArray(),
        right.getQualifierOffset() + qualCommonPrefix, right.getQualifierLength()
            - qualCommonPrefix);
  }

  /***************** special cases ****************************/
  /**
   * special case for KeyValue.equals
   */
  public static boolean equalsIgnoreMvccVersion(Cell a, Cell b){
    return 0 == compareStaticIgnoreMvccVersion(a, b);
  }

  private static int compareStaticIgnoreMvccVersion(Cell a, Cell b) {
    // row
    int c = compareRows(a, b);
    if (c != 0) return c;

    // family
    c = compareColumns(a, b);
    if (c != 0) return c;

    // timestamp: later sorts first
    c = compareTimestamps(a, b);
    if (c != 0) return c;

    //type
    c = (0xff & b.getTypeByte()) - (0xff & a.getTypeByte());
    return c;
  }

  private static int compareTimestamps(final long ltimestamp, final long rtimestamp) {
    // The below older timestamps sorting ahead of newer timestamps looks
    // wrong but it is intentional. This way, newer timestamps are first
    // found when we iterate over a memstore and newer versions are the
    // first we trip over when reading from a store file.
    if (ltimestamp < rtimestamp) {
      return 1;
    } else if (ltimestamp > rtimestamp) {
      return -1;
    }
    return 0;
  }

  /**
   * Counter part for the KeyValue.RowOnlyComparator
   */
  public static class RowComparator extends CellComparator {
    @Override
    public int compare(Cell a, Cell b) {
      return compareRows(a, b);
    }
  }

}
