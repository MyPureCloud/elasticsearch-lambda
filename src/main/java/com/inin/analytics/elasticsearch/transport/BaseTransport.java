package com.inin.analytics.elasticsearch.transport;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Collection;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;
import org.apache.commons.io.filefilter.FileFilterUtils;

import com.google.common.base.Preconditions;
import com.inin.analytics.elasticsearch.BaseESReducer;
import com.inin.analytics.elasticsearch.transport.SnapshotTransportStrategy.STORAGE_SYSTEMS;

public abstract class BaseTransport {
	protected String snapshotWorkingLocation;
	protected String snapshotFinalDestination;
	private DirectoryFilter directoryFilter = new DirectoryFilter();
	
	public BaseTransport(String snapshotWorkingLocation, String snapshotFinalDestination) {
		this.snapshotWorkingLocation = snapshotWorkingLocation;
		this.snapshotFinalDestination = snapshotFinalDestination;
		Preconditions.checkNotNull(snapshotWorkingLocation);
		Preconditions.checkNotNull(snapshotFinalDestination);
	}
	
	protected abstract void init();
	protected abstract void close();
	protected abstract void transferFile(boolean deleteSource, String destination, String filename, String localDirectory) throws IOException;
	protected abstract void transferDir(String destination, String localShardPath, String shard) throws IOException;
	protected abstract boolean checkExists(String destination, Integer shardNumber) throws IOException;
	protected abstract boolean checkExists(String destination, String filename) throws IOException;

	/**
     * ES 5 generates index name. Get the auto-generated index. 
     * 
     * @param snapshotName
     * @return autoGeneratedIndex
     * @throws IOException
     */
	public String execute(String snapshotName) throws IOException {
	    String indexSource =  snapshotWorkingLocation + "indices";
        String index = new File(indexSource).listFiles((FileFilter) DirectoryFileFilter.DIRECTORY)[0].getName();
        execute(snapshotName, index);
        return index;
	}

	/**
	 * Transport a snapshot sitting on the local filesystem to a remote repository. Snapshots are stiched together
	 * shard by shard because we're snapshotting 1 shard at a time. 
	 * 
	 * @param snapshotName
	 * @param index
	 * @param shardNumber
	 * @throws IOException
	 */
	public void execute(String snapshotName, String index) throws IOException {
		init();
   
		// Figure out which shard has all the data
		String destination = removeStorageSystemFromPath(snapshotFinalDestination);

		// Upload top level manifests
        Collection<File> topManifests = FileUtils.listFiles(new File(snapshotWorkingLocation), FileFilterUtils.trueFileFilter(), FileFilterUtils.falseFileFilter());
        for (File f: topManifests) {
            transferFile(false, destination, f.getName(), snapshotWorkingLocation);
        }

        String indexManifestSource =  snapshotWorkingLocation + "indices" + BaseESReducer.DIR_SEPARATOR + index;
        String indexManifestDestination = destination + BaseESReducer.DIR_SEPARATOR + "indices" + BaseESReducer.DIR_SEPARATOR + index;
        Collection<File> indexManifests = FileUtils.listFiles(new File(indexManifestSource), FileFilterUtils.trueFileFilter(), FileFilterUtils.falseFileFilter());
        for (File f: indexManifests) {
            transferFile(false, indexManifestDestination, f.getName(), indexManifestSource);
        }    

        // Upload shard data
        String shardDestination = destination + BaseESReducer.DIR_SEPARATOR + "indices" + BaseESReducer.DIR_SEPARATOR + index + BaseESReducer.DIR_SEPARATOR;
        File[] shardDirs = new File(indexManifestSource).listFiles((FileFilter) DirectoryFileFilter.DIRECTORY);
        for (File shardDir: shardDirs) {
            String shardSource = snapshotWorkingLocation + "indices" + BaseESReducer.DIR_SEPARATOR + index + BaseESReducer.DIR_SEPARATOR + shardDir.getName();
            transferDir(shardDestination, shardSource, shardDir.getName());            
        }
		close();
	}
	
    public void placeMissingShards(String snapshotName, String index, int numShards, boolean includeRootManifest) throws IOException {
        init();
        String destination = removeStorageSystemFromPath(snapshotFinalDestination);

        if (includeRootManifest) {
            // Upload top level manifests
            Collection<File> topManifests = FileUtils.listFiles(new File(snapshotWorkingLocation), FileFilterUtils.trueFileFilter(), FileFilterUtils.falseFileFilter());
            for (File f : topManifests) {
                transferFile(false, destination, f.getName(), snapshotWorkingLocation);
            }
        }

        File[] indexDirs = new File(snapshotWorkingLocation + "indices").listFiles((FileFilter) DirectoryFileFilter.DIRECTORY);
        for (File indexDir : indexDirs) {
            String shardSource = snapshotWorkingLocation + "indices" + BaseESReducer.DIR_SEPARATOR + indexDir.getName() + BaseESReducer.DIR_SEPARATOR;
            String indexDestination = destination + BaseESReducer.DIR_SEPARATOR + "indices" + BaseESReducer.DIR_SEPARATOR;
            if (!checkExists(indexDestination, indexDir.getName())) {
                // Upload shard data
                transferDir(indexDestination, shardSource, indexDir.getName());
            }
        }
        close();
    }
	
	/**
	 * Rip out filesystem specific stuff off the path EG s3:// 
	 * @param s
	 * @return s
	 */
	private String removeStorageSystemFromPath(String s) {
		for(STORAGE_SYSTEMS storageSystem : SnapshotTransportStrategy.STORAGE_SYSTEMS.values()) {
			s = s.replaceFirst(storageSystem.name() + "://", "");			
		}

		return s;
	}
	
	/**
	 * We've snapshotted an index with all data routed to a single shard (1 shard per reducer). Problem is 
	 * we don't know which shard # it routed all the data to. We can determine that by picking 
	 * out the largest shard folder and renaming it to the shard # we want it to be.
	 */
	private String getShardSource(String index) throws IOException {
		// Get a list of shards in the snapshot
		String baseIndexLocation = snapshotWorkingLocation + "indices" + BaseESReducer.DIR_SEPARATOR + index + BaseESReducer.DIR_SEPARATOR;
		File file = new File(baseIndexLocation);
		String[] shardDirectories = file.list(directoryFilter);
		
		System.out.println("baseIndexLocation: "+baseIndexLocation);
		// Figure out which shard has all the data in it. Since we've routed all data to it, there'll only be one
		Long biggestDirLength = null;
		String biggestDir = null;
		for(String directory : shardDirectories) {
			File curDir = new File(baseIndexLocation + directory);
                        long curDirLength = FileUtils.sizeOfDirectory(curDir);
			if(biggestDirLength == null || biggestDirLength < curDirLength) {
				biggestDir = directory;
				biggestDirLength = curDirLength;
			}
		}
		
		System.out.println("biggestDir: "+biggestDir);
		return biggestDir;
	}
	
	/**
	 * We're building 1 shard at a time. Therefore each snapshot has a bunch of empty
	 * shards and 1 shard with all the data in it. This deletes all the empty shard folders
	 * for you.
	 * 
	 * @param index
	 * @param biggestDir
	 * @throws IOException
	 */
	private void cleanEmptyShards(String index, String biggestDir) throws IOException {
        String baseIndexLocation = snapshotWorkingLocation + "indices" + BaseESReducer.DIR_SEPARATOR + index + BaseESReducer.DIR_SEPARATOR;
        File file = new File(baseIndexLocation);
        String[] shardDirectories = file.list(directoryFilter);
        
        // Remove the empty shards
        for(String directory : shardDirectories) {
            if(!directory.equals(biggestDir)) {
                FileUtils.deleteDirectory(new File(baseIndexLocation + directory));
            }
        }
    }
	
	private class DirectoryFilter implements FilenameFilter {
		
		@Override
		public boolean accept(File current, String name) {
			return new File(current, name).isDirectory();
		}
	}
}
