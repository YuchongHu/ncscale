
package org.apache.hadoop.raid;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocksWithMetaInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.raid.StripeStore.StripeInfo;

public class CorruptStripeInfo {
  
  private Codec codec;
  private Path srcPath;   // for dir-raid srcName = dir
                                // for file-raid srcName = corruptsrcFileName
  private int stripeIdx;
  private Map<Integer, LocatedBlockWithFileName> corruptBlocks = null;
  private Decoder decoder;
  private Configuration conf;

  private DistributedFileSystem srcFs;
  private Map<Integer, LocatedBlockWithFileName> stripeBlocks = null;
  private int[] corruptArray;
  private int namespaceId;


  CorruptStripeInfo(Codec codec, Path srcPath, int stripeIdx, 
      Map<Integer, LocatedBlockWithFileName>corruptBlocks, Decoder decoder,
      Configuration conf) throws IOException {
    this.codec = codec;
    this.srcPath = srcPath;
    this.stripeIdx = stripeIdx;
    this.corruptBlocks = corruptBlocks;
    this.decoder = decoder;
    this.conf = conf;

    this.srcFs = getDFS(srcPath, conf);
    stripeBlocks = new HashMap<Integer, LocatedBlockWithFileName>();
    corruptArray = new int[codec.stripeLength + codec.parityLength];
    for(int i=0 ;i<codec.stripeLength + codec.parityLength; i++) {
      corruptArray[i] = 0;
    }
  }

  public Codec getCodec() {
    return codec;
  }

  public Path getSrcPath() {
    return srcPath;
  }

  public int getStripeIdx() {
    return stripeIdx;
  }

  public Map<Integer, LocatedBlockWithFileName> getCorruptBlocks() {
    return corruptBlocks;
  }

  public Map<Integer, LocatedBlockWithFileName> getStripeBlocks() {
    return stripeBlocks;
  }

  public int[] getCorruptArray() {
    return corruptArray;
  }

  public void setCorruptBlocks(Map<Integer, LocatedBlockWithFileName> corrupt) {
    this.corruptBlocks = corrupt;
  }

  public void addCorruptBlock(int blockidx, LocatedBlock lb, String file) {
    if(this.corruptBlocks == null) {
      this.corruptBlocks = new HashMap<Integer, LocatedBlockWithFileName>();
    }

    LocatedBlockWithFileName lbwfn = new LocatedBlockWithFileName(lb.getBlock(),
        lb.getLocations(), file);
    this.corruptBlocks.put(blockidx, lbwfn);
    corruptArray[blockidx] = 1;
  }

  public LocatedBlockWithFileName getLBWFN(Block block, LocatedBlockWithFileName lb, Configuration conf) 
      throws IOException {
    Block bb = block;
    DatanodeInfo[] locs = lb.getLocations();
    long startOffset = -1;
    boolean corrupt = false;
    String filename = lb.getFileName();
    
    Path filePath = new Path(filename);
    String uriPath = filePath.toUri().getPath();
    DistributedFileSystem fileFs = getDFS(filePath, conf);
    FileStatus fileStat = fileFs.getFileStatus(filePath);
	long blockSize = fileStat.getBlockSize();
    LocatedBlocksWithMetaInfo lbksm = fileFs.getClient().namenode.
        openAndFetchMetaInfo(uriPath, 0, fileStat.getLen());
    for(LocatedBlock b: lbksm.getLocatedBlocks()) {
      if(b.getBlock().getBlockId() == bb.getBlockId()) {
        startOffset = b.getStartOffset();
        corrupt = b.isCorrupt();
        break;
      }
    }

    return new LocatedBlockWithFileName(bb, locs, startOffset, corrupt, filename);
  }

  public void findStripeBlocks() throws IOException {
    LocatedBlockWithFileName oneCorruptBlock = new LocatedBlockWithFileName();
    for(Map.Entry<Integer, LocatedBlockWithFileName> entry : corruptBlocks.entrySet()) {
      oneCorruptBlock = entry.getValue();
      break;
    }

    Block lostBlock = oneCorruptBlock.getBlock();
    long lostOffset = oneCorruptBlock.getStartOffset();

    if(codec.isDirRaid) {
      StripeInfo si = decoder.retrieveStripe(lostBlock, srcPath, lostOffset, srcFs, null,
          false);
      List<Block> srcBlocks = si.srcBlocks;
      for(int i=0; i<srcBlocks.size(); i++) {
        long blockId = srcBlocks.get(i).getBlockId();
        LocatedBlockWithFileName lb = srcFs.getClient().getBlockInfo(blockId);
        stripeBlocks.put(i, getLBWFN(srcBlocks.get(i), lb, this.conf));
      }
      List<Block> parityBlocks = si.parityBlocks;
      for(int i=0; i<parityBlocks.size() ;i++) {
        long blockId = parityBlocks.get(i).getBlockId();
        LocatedBlockWithFileName lb = srcFs.getClient().getBlockInfo(blockId);
        stripeBlocks.put(i+codec.stripeLength, getLBWFN(parityBlocks.get(i), lb, this.conf));
      }
    } else {
      // intra-file raid srcpath is the corrupt stripe src file
      FileStatus srcStat = srcFs.getFileStatus(srcPath);
      LocatedBlocksWithMetaInfo locatedblocks = srcFs.getClient().namenode.openAndFetchMetaInfo(
          srcPath.toUri().getPath(), 0, srcStat.getLen());
      List<LocatedBlock> lb = locatedblocks.getLocatedBlocks();
      for(int i=stripeIdx*codec.stripeLength; i<lb.size(); i++) {
        LocatedBlock currentLb = lb.get(i);
        stripeBlocks.put(i%codec.stripeLength,
            new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
                currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
      }

      ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
      Path parityPath = ppair.getPath();
      DistributedFileSystem parityFs= getDFS(parityPath, conf);
      FileStatus parityStat = parityFs.getFileStatus(parityPath);
      locatedblocks = parityFs.getClient().namenode.openAndFetchMetaInfo(
          parityPath.toUri().getPath(), 0, parityStat.getLen());
      lb = locatedblocks.getLocatedBlocks();
      for(int i = stripeIdx*codec.parityLength; i<lb.size(); i++) {
        LocatedBlock currentLb = lb.get(i);
        stripeBlocks.put(i%codec.parityLength + codec.stripeLength,
            new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
                currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
      }
    }
    
      
  }
    
  protected DistributedFileSystem getDFS(Path p, Configuration conf) throws IOException {
    FileSystem fs = p.getFileSystem(conf);
    DistributedFileSystem dfs = null;
    if (fs instanceof DistributedFileSystem) {
      dfs = (DistributedFileSystem) fs;
    } else if (fs instanceof FilterFileSystem) {
      FilterFileSystem ffs = (FilterFileSystem) fs;
      if (ffs.getRawFileSystem() instanceof DistributedFileSystem) {
        dfs = (DistributedFileSystem) ffs.getRawFileSystem();
      }
    }
    return dfs;
  }

  public void dumpStripeBlocks() throws IOException{
    if(this.stripeBlocks == null) {
      throw new IOException("no block in this stripe!!!");
    }

    for(Map.Entry<Integer, LocatedBlockWithFileName> entry: this.stripeBlocks.entrySet()) {
      RaidNode.LOG.info("CorruptStripeInfo.dumpStripeBLocks.blockIdx = " + entry.getKey()
        + " filename = " + entry.getValue().getFileName() + " blockid = " 
        + entry.getValue().getBlock().getBlockId() + " block = " + entry.getValue().getBlock());
    }
  }

  public boolean checkCorrupt() {
    for(Map.Entry<Integer, LocatedBlockWithFileName> entry: stripeBlocks.entrySet()) {
      LocatedBlockWithFileName lb = entry.getValue();
      int i = entry.getKey();
    }
    return true;

  }


}
