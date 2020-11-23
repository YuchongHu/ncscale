
package org.apache.hadoop.raid;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FilterFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocksWithMetaInfo;
import org.apache.hadoop.hdfs.protocol.LocatedBlockWithFileName;

/*
 * Get blocks in a scaling group.
 * Xiaoyang add in Dec, 2017. 
 */
public class ScalingGroupInfo {
  
  private Codec codec;
  private Path srcPath;   
  private Configuration conf;

  private DistributedFileSystem srcFs;
  private Map<String, Map<Integer, LocatedBlockWithFileName>> dataBlocks = null;
  private Map<String, Map<Integer, LocatedBlockWithFileName>> parityBlocks = null;

  ScalingGroupInfo(Codec codec, Path srcPath,// Decoder decoder,
      Configuration conf) throws IOException {
    this.codec = codec;
    this.srcPath = srcPath;
    this.conf = conf;

    this.srcFs = getDFS(srcPath, conf);
    dataBlocks = new HashMap<String, Map<Integer, LocatedBlockWithFileName>>();
    parityBlocks = new HashMap<String, Map<Integer, LocatedBlockWithFileName>>();
  }

  public Codec getCodec() {
    return codec;
  }

  public Path getSrcPath() {
    return srcPath;
  }

  public Map<String, Map<Integer, LocatedBlockWithFileName>> getDataBlocks() {
    return dataBlocks;
  }

  public Map<String, Map<Integer, LocatedBlockWithFileName>> getParityBlocks() {
	    return parityBlocks;
	  }

/*
 * This is for NCScale.
 * put all data blocks in one scaling group to dataBlocks, and the key of the MAP is nodename.
 * parity is the same.
 */
public void findBlocks(int scalingID) throws IOException {
	 
	  int drcK = codec.stripeLength;
	  int drcS = codec.newNode;
	  int drcN = codec.stripeLength + codec.parityLength;
	  long blockSize = (long)conf.getInt("dfs.block.size", 1048576);
	  int numDataBlocksInAGroup = drcK*drcN*(drcK+drcS)*(drcN+drcS);
	  int numParityBlocksInAGroup = (drcN-drcK)*drcN*(drcK+drcS)*(drcN+drcS);
	  long dataStartOffset = blockSize*scalingID*numDataBlocksInAGroup;
	  long parityStartOffset = blockSize*scalingID*numParityBlocksInAGroup;
	  FileStatus srcStat = srcFs.getFileStatus(srcPath);
	  ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
	  Path parityPath = ppair.getPath();
	  DistributedFileSystem parityFs= getDFS(parityPath, conf);
	  
      LocatedBlocksWithMetaInfo locatedDataBlocks = srcFs.getClient().namenode.openAndFetchMetaInfo(
	          srcPath.toUri().getPath(), dataStartOffset, numDataBlocksInAGroup*blockSize);
	  LocatedBlocksWithMetaInfo locatedParityBlocks = parityFs.getClient().namenode.openAndFetchMetaInfo(
			  parityPath.toUri().getPath(), parityStartOffset, numParityBlocksInAGroup*blockSize);
	  List<LocatedBlock> lbdata = locatedDataBlocks.getLocatedBlocks();
	  List<LocatedBlock> lbparity = locatedParityBlocks.getLocatedBlocks(); 
	  String[] racks =  srcFs.getRacks();

	  
	  for(int i = 0; i < drcN; i++)
	  {
		  Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
		  Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
	      String nodeName = srcFs.getDataNodeByRackName(racks[i]);
	      int dataBlockIDInNode = 0;
	      int parityBlockIDInNode = 0;

	      for(int j = 0; j < lbdata.size(); j++) {
	    	  LocatedBlock currentLb = lbdata.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  dataBlocksInNode.put(dataBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  dataBlockIDInNode++;
	    	  }
	      }
	      
	      for(int j = 0; j < lbparity.size(); j++) {
	    	  LocatedBlock currentLb = lbparity.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  parityBlocksInNode.put(parityBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  parityBlockIDInNode++;
	    	  }
	      }
	       //Here, put the collection of blocks
		  dataBlocks.put(nodeName, dataBlocksInNode);
		  parityBlocks.put(nodeName, parityBlocksInNode);
	  }  
  }
   
/*
 * This is for NCScale scale down.
 * put all data blocks in one scaling group to dataBlocks, and the key of the MAP is nodename.
 * parity is the same.
 * Xiaoyang add in Jul, 2018.
 */
public void findBlocksDown1(int scalingID) throws IOException {
	 
	  int drcK = codec.stripeLength;
//	  int drcS = codec.newNode;
	  int drcN = codec.stripeLength + codec.parityLength;
	  long blockSize = (long)conf.getInt("dfs.block.size", 1048576);
	  int numDataBlocksInAGroup = drcK*drcN*(drcN-1)*(drcN-2);
	  int numParityBlocksInAGroup = drcN*(drcN-1)*(drcN-2);
	  long dataStartOffset = blockSize*scalingID*numDataBlocksInAGroup;
	  long parityStartOffset = blockSize*scalingID*numParityBlocksInAGroup;
	  FileStatus srcStat = srcFs.getFileStatus(srcPath);
	  ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
	  Path parityPath = ppair.getPath();
	  DistributedFileSystem parityFs= getDFS(parityPath, conf);
	  
      LocatedBlocksWithMetaInfo locatedDataBlocks = srcFs.getClient().namenode.openAndFetchMetaInfo(
	          srcPath.toUri().getPath(), dataStartOffset, numDataBlocksInAGroup*blockSize);
	  LocatedBlocksWithMetaInfo locatedParityBlocks = parityFs.getClient().namenode.openAndFetchMetaInfo(
			  parityPath.toUri().getPath(), parityStartOffset, numParityBlocksInAGroup*blockSize);
	  List<LocatedBlock> lbdata = locatedDataBlocks.getLocatedBlocks();
	  List<LocatedBlock> lbparity = locatedParityBlocks.getLocatedBlocks(); 
	  String[] racks =  srcFs.getRacks();

	  
	  for(int i = 0; i < drcN; i++)
	  {
		  Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
		  Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
	      String nodeName = srcFs.getDataNodeByRackName(racks[i]);
	      int dataBlockIDInNode = 0;
	      int parityBlockIDInNode = 0;

	      for(int j = 0; j < lbdata.size(); j++) {
	    	  LocatedBlock currentLb = lbdata.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  dataBlocksInNode.put(dataBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  dataBlockIDInNode++;
	    	  }
	      }
	      
	      for(int j = 0; j < lbparity.size(); j++) {
	    	  LocatedBlock currentLb = lbparity.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  parityBlocksInNode.put(parityBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  parityBlockIDInNode++;
	    	  }
	      }
	       //Here, put the collection of blocks
		  dataBlocks.put(nodeName, dataBlocksInNode);
		  parityBlocks.put(nodeName, parityBlocksInNode);
	  }  
  }

/*
 * This is for NCScale scale down.
 * put all data blocks in one scaling group to dataBlocks, and the key of the MAP is nodename.
 * parity is the same.
 * Xiaoyang add in Nov, 2018.
 */
public void findBlocksDown2(int scalingID) throws IOException {
	 
	  int drcK = codec.stripeLength;
	  int drcS = codec.newNode;
	  int drcN = codec.stripeLength + codec.parityLength;
	  long blockSize = (long)conf.getInt("dfs.block.size", 1048576);
	  int numDataBlocksInAGroup = drcK*drcN*(drcK-drcS)*(drcN-drcS);
	  int numParityBlocksInAGroup = drcN*(drcN-drcK)*(drcK-drcS)*(drcN-drcS);
	  long dataStartOffset = blockSize*scalingID*numDataBlocksInAGroup;
	  long parityStartOffset = blockSize*scalingID*numParityBlocksInAGroup;
	  FileStatus srcStat = srcFs.getFileStatus(srcPath);
	  ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
	  Path parityPath = ppair.getPath();
	  DistributedFileSystem parityFs= getDFS(parityPath, conf);
	  
      LocatedBlocksWithMetaInfo locatedDataBlocks = srcFs.getClient().namenode.openAndFetchMetaInfo(
	          srcPath.toUri().getPath(), dataStartOffset, numDataBlocksInAGroup*blockSize);
	  LocatedBlocksWithMetaInfo locatedParityBlocks = parityFs.getClient().namenode.openAndFetchMetaInfo(
			  parityPath.toUri().getPath(), parityStartOffset, numParityBlocksInAGroup*blockSize);
	  List<LocatedBlock> lbdata = locatedDataBlocks.getLocatedBlocks();
	  List<LocatedBlock> lbparity = locatedParityBlocks.getLocatedBlocks(); 
	  String[] racks =  srcFs.getRacks();

	  
	  for(int i = 0; i < drcN; i++)
	  {
		  Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
		  Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
	      String nodeName = srcFs.getDataNodeByRackName(racks[i]);
	      int dataBlockIDInNode = 0;
	      int parityBlockIDInNode = 0;

	      for(int j = 0; j < lbdata.size(); j++) {
	    	  LocatedBlock currentLb = lbdata.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  dataBlocksInNode.put(dataBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  dataBlockIDInNode++;
	    	  }
	      }
	      
	      for(int j = 0; j < lbparity.size(); j++) {
	    	  LocatedBlock currentLb = lbparity.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  parityBlocksInNode.put(parityBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  parityBlockIDInNode++;
	    	  }
	      }
	       //Here, put the collection of blocks
		  dataBlocks.put(nodeName, dataBlocksInNode);
		  parityBlocks.put(nodeName, parityBlocksInNode);
	  }  
  }

/*
 * This is for ScaleRS.
 * put all data blocks in one scaling group to dataBlocks, and the key of the MAP is nodename.
 * parity is the same.
 */
public void findBlocksScaleRS(int scalingID) throws IOException {
	 
	  int drcK = codec.stripeLength;
	  int drcS = codec.newNode;
	  int drcN = codec.stripeLength + codec.parityLength;
	  long blockSize = (long)conf.getInt("dfs.block.size", 1048576);
	  int numDataBlocksInAGroup = drcK*drcN*(drcK+drcS)*(drcN+drcS);
	  int numParityBlocksInAGroup = (drcN-drcK)*drcN*(drcK+drcS)*(drcN+drcS);
	  long dataStartOffset = blockSize*scalingID*numDataBlocksInAGroup;
	  long parityStartOffset = blockSize*scalingID*numParityBlocksInAGroup;
	  FileStatus srcStat = srcFs.getFileStatus(srcPath);
	  ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
	  Path parityPath = ppair.getPath();
	  DistributedFileSystem parityFs= getDFS(parityPath, conf);
	  
      LocatedBlocksWithMetaInfo locatedDataBlocks = srcFs.getClient().namenode.openAndFetchMetaInfo(
	          srcPath.toUri().getPath(), dataStartOffset, numDataBlocksInAGroup*blockSize);
	  LocatedBlocksWithMetaInfo locatedParityBlocks = parityFs.getClient().namenode.openAndFetchMetaInfo(
			  parityPath.toUri().getPath(), parityStartOffset, numParityBlocksInAGroup*blockSize);
	  List<LocatedBlock> lbdata = locatedDataBlocks.getLocatedBlocks();
	  List<LocatedBlock> lbparity = locatedParityBlocks.getLocatedBlocks(); 
	  String[] racks =  srcFs.getRacks();

	  
	  for(int i = 0; i < drcN; i++)
	  {
		  Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
		  Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
	      String nodeName = srcFs.getDataNodeByRackName(racks[i]);
	      int dataBlockIDInNode = 0;
	      int parityBlockIDInNode = 0;

	      for(int j = 0; j < lbdata.size(); j++) {
	    	  LocatedBlock currentLb = lbdata.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  dataBlocksInNode.put(dataBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  dataBlockIDInNode++;
	    	  }
	      }
	      
	      for(int j = 0; j < lbparity.size(); j++) {
	    	  LocatedBlock currentLb = lbparity.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  parityBlocksInNode.put(parityBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  parityBlockIDInNode++;
	    	  }
	      }
	       //Here, put the collection of blocks
		  dataBlocks.put(nodeName, dataBlocksInNode);
		  parityBlocks.put(nodeName, parityBlocksInNode);
	  }  
  }

/*
 * This is for ScaleRS.
 * put all data blocks in one scaling group to dataBlocks, and the key of the MAP is nodename.
 * parity is the same.
 */
public void findBlocksScaleRSDown(int scalingID) throws IOException {
	 
	  int drcK = codec.stripeLength;
	  int drcS = codec.newNode;
	  int drcN = codec.stripeLength + codec.parityLength;
	  long blockSize = (long)conf.getInt("dfs.block.size", 1048576);
	  int numDataBlocksInAGroup = drcK*drcN*(drcK-drcS)*(drcN-drcS);
	  int numParityBlocksInAGroup = (drcN-drcK)*drcN*(drcK-drcS)*(drcN-drcS);
	  long dataStartOffset = blockSize*scalingID*numDataBlocksInAGroup;
	  long parityStartOffset = blockSize*scalingID*numParityBlocksInAGroup;
	  FileStatus srcStat = srcFs.getFileStatus(srcPath);
	  ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
	  Path parityPath = ppair.getPath();
	  DistributedFileSystem parityFs= getDFS(parityPath, conf);
	  
      LocatedBlocksWithMetaInfo locatedDataBlocks = srcFs.getClient().namenode.openAndFetchMetaInfo(
	          srcPath.toUri().getPath(), dataStartOffset, numDataBlocksInAGroup*blockSize);
	  LocatedBlocksWithMetaInfo locatedParityBlocks = parityFs.getClient().namenode.openAndFetchMetaInfo(
			  parityPath.toUri().getPath(), parityStartOffset, numParityBlocksInAGroup*blockSize);
	  List<LocatedBlock> lbdata = locatedDataBlocks.getLocatedBlocks();
	  List<LocatedBlock> lbparity = locatedParityBlocks.getLocatedBlocks(); 
	  String[] racks =  srcFs.getRacks();

	  
	  for(int i = 0; i < drcN; i++)
	  {
		  Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
		  Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
	      String nodeName = srcFs.getDataNodeByRackName(racks[i]);
	      int dataBlockIDInNode = 0;
	      int parityBlockIDInNode = 0;

	      for(int j = 0; j < lbdata.size(); j++) {
	    	  LocatedBlock currentLb = lbdata.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  dataBlocksInNode.put(dataBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  dataBlockIDInNode++;
	    	  }
	      }
	      
	      for(int j = 0; j < lbparity.size(); j++) {
	    	  LocatedBlock currentLb = lbparity.get(j);
	    	  if(currentLb.getFirstLocation().toString().equals(nodeName))
	    	  {
	    		  parityBlocksInNode.put(parityBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  parityBlockIDInNode++;
	    	  }
	      }
	       //Here, put the collection of blocks
	     // if(!dataBlocksInNode.isEmpty())
	    	  dataBlocks.put(nodeName, dataBlocksInNode);
	     //if(!parityBlocksInNode.isEmpty())
	    	  parityBlocks.put(nodeName, parityBlocksInNode);
	  }  
  }

/*
 * This is for MBR Scale.
 * put all data blocks in one scaling group to dataBlocks, and the key of the MAP is nodename.
 * parity is the same.
 */
public void findBlocksMBR(int scalingID) throws IOException {
	 
	  int drcK = codec.stripeLength;
	  int drcS = codec.newNode;
	  int drcN = codec.stripeLength + codec.parityLength;
	  int nodeNum = codec.nodeNum;
	  long blockSize = (long)conf.getInt("dfs.block.size", 1048576);
	  int numDataBlocksInAGroup = ((nodeNum+drcS+1)*(nodeNum+drcS-2)/2)*drcK;
	  int numParityBlocksInAGroup = ((nodeNum+drcS+1)*(nodeNum+drcS-2)/2)*(drcN-drcK);
	  long dataStartOffset = blockSize*scalingID*numDataBlocksInAGroup;
	  long parityStartOffset = blockSize*scalingID*numParityBlocksInAGroup;
	  FileStatus srcStat = srcFs.getFileStatus(srcPath);
	  ParityFilePair ppair = ParityFilePair.getDRCParityFile(this.codec, srcStat, this.conf, false);
	  Path parityPath = ppair.getPath();
	  DistributedFileSystem parityFs= getDFS(parityPath, conf);
	  
      LocatedBlocksWithMetaInfo locatedDataBlocks = srcFs.getClient().namenode.openAndFetchMetaInfo(
	          srcPath.toUri().getPath(), dataStartOffset, numDataBlocksInAGroup*blockSize);
	  LocatedBlocksWithMetaInfo locatedParityBlocks = parityFs.getClient().namenode.openAndFetchMetaInfo(
			  parityPath.toUri().getPath(), parityStartOffset, numParityBlocksInAGroup*blockSize);
	  List<LocatedBlock> lbdata = locatedDataBlocks.getLocatedBlocks();
	  List<LocatedBlock> lbparity = locatedParityBlocks.getLocatedBlocks(); 
	  String[] racks =  srcFs.getRacks();

	  //lbdata contains all the data blocks in a scaling group.
	  //lbparity contains all the parity blocks in a scaling group.
	  for(int i = 0; i < nodeNum; i++)
	  {
		  Map<Integer, LocatedBlockWithFileName> dataBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
		  Map<Integer, LocatedBlockWithFileName> parityBlocksInNode = 
				  new HashMap<Integer, LocatedBlockWithFileName>();
	      String nodeName = srcFs.getDataNodeByRackName(racks[i]);
	      int dataBlockIDInNode = 0;
	      int parityBlockIDInNode = 0;

	      for(int j = 0; j < lbdata.size(); j++) {
	    	  LocatedBlock currentLb = lbdata.get(j);
	    	  if(currentLb.getLocations()[0].toString().equals(nodeName) || 
	    		 currentLb.getLocations()[1].toString().equals(nodeName))
	    	  {
	    		  dataBlocksInNode.put(dataBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  dataBlockIDInNode++;
	    	  }
	      }
	      
	      for(int j = 0; j < lbparity.size(); j++) {
	    	  LocatedBlock currentLb = lbparity.get(j);
	    	  if(currentLb.getLocations()[0].toString().equals(nodeName) || 
	    		 currentLb.getLocations()[1].toString().equals(nodeName))
	    	  {
	    		  parityBlocksInNode.put(parityBlockIDInNode,
		                  new LocatedBlockWithFileName(currentLb.getBlock(), currentLb.getLocations(),
		                      currentLb.getStartOffset(), currentLb.isCorrupt(), srcPath.toUri().getPath()));
	    		  parityBlockIDInNode++;
	    	  }
	      }
	       //Here, put the collection of blocks
		  dataBlocks.put(nodeName, dataBlocksInNode);
		  parityBlocks.put(nodeName, parityBlocksInNode);
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

}
