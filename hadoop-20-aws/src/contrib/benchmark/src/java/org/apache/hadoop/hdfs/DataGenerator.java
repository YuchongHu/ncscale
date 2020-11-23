package org.apache.hadoop.hdfs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

import org.apache.hadoop.mapred.CreateFiles;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * This program reads the directory structure and file structure from the input
 * directory and creates the namespace in the file system specified by the
 * configuration in the specified root. All the files are filled with 'a'.
 *
 * The synopsis of the command is java DataGenerator -inDir <inDir>: input
 * directory name where directory/file structures are stored. Its default value
 * is the current directory. -root <root>: the name of the root directory which
 * the new namespace is going to be placed under. Its default value is
 * "/testLoadSpace".
 */
@SuppressWarnings("deprecation")
public class DataGenerator extends Configured implements Tool {
	private File inDir = StructureGenerator.DEFAULT_STRUCTURE_DIRECTORY;
	private Path root = DEFAULT_ROOT;
	private int nFiles;
	private FileSystem fs;
	private Configuration config;
	@SuppressWarnings("unused")
	final static private long BLOCK_SIZE = 10;
	final static String ROOT = "/root";
	final static private String USAGE = "java DataGenerator "
			+ "-inDir <inDir> " + "-root <root>";

	/** default name of the root where the test namespace will be placed under */
	final static Path DEFAULT_ROOT = new Path("/testLoadSpace");

	/**
	 * Main function. It first parses the command line arguments. It then reads
	 * the directory structure from the input directory structure file and
	 * creates directory structure in the file system namespace. Afterwards it
	 * reads the file attributes and creates files in the file. All file content
	 * is filled with 'a'.
	 *
	 * @return
	 */

	public int run(String[] args) throws Exception {
		int exitCode = 0;
		exitCode = init(args);
		if (exitCode != 0) {
			return exitCode;
		}
		// genDirStructure();
		genFiles();
		return exitCode;
	}

	/** Parse the command line arguments and initialize the data */
	private int init(String[] args) {
		try { // initialize file system handle
			fs = FileSystem.get(getConf());
		} catch (IOException ioe) {
			System.err.println("Can not initialize the file system: "
					+ ioe.getLocalizedMessage());
			return -1;
		}

		for (int i = 0; i < args.length; i++) { // parse command line
			if (args[i].equals("-root")) {
				root = new Path(args[++i]);
			} else if (args[i].equals("-inDir")) {
				inDir = new File(args[++i]);
			} else if (args[i].equals("-files"))
				nFiles = Integer.parseInt(args[++i]);
			else {
				System.err.println(USAGE);
				ToolRunner.printGenericCommandUsage(System.err);
				System.exit(-1);
			}
		}
		return 0;
	}

	/**
	 * Read directory structure file under the input directory. Create each
	 * directory under the specified root. The directory names are relative to
	 * the specified root.
	 */
	@SuppressWarnings("unused")
	private void genDirStructure() throws IOException {
		BufferedReader in = new BufferedReader(new FileReader(new File(inDir,
				StructureGenerator.DIR_STRUCTURE_FILE_NAME)));
		String line;
		while ((line = in.readLine()) != null) {
			fs.mkdirs(new Path(root + line));
		}
	}

	/**
	 * Read file structure file under the input directory. Create each file
	 * under the specified root. The file names are relative to the root.
	 */
	private void genFiles() throws IOException {
		//
		// BufferedReader in = new BufferedReader(new FileReader(new File(inDir,
		// StructureGenerator.FILE_STRUCTURE_FILE_NAME)));
		// String line;
		// while ((line = in.readLine()) != null) {
		// String[] tokens = line.split(" ");
		// if (tokens.length != 2) {
		// throw new IOException("Expect at most 2 tokens per line: "
		// + line);
		// }
		// String fileName = root + tokens[0];
		// long fileSize = (long) (BLOCK_SIZE * Double.parseDouble(tokens[1]));
		// genFile(new Path(fileName), fileSize);
		// }

		config = new Configuration(getConf());
		config.setInt("dfs.replication", 3);
		config.set("dfs.rootdir", root.toString());

		JobConf job = new JobConf(config, DataGenerator.class);
		job.setJobName("data-genarator");

		FileOutputFormat.setOutputPath(job, new Path("data-generator-result"));
		// create the input for the map-reduce job
		Path inputPath = new Path(ROOT + "load_input");
		fs.mkdirs(inputPath);
		fs.copyFromLocalFile(new Path(inDir + "/"
				+ StructureGenerator.FILE_STRUCTURE_FILE_NAME), inputPath);
		FileInputFormat.setInputPaths(job, new Path(ROOT + "load_input"));

		job.setInputFormat(TextInputFormat.class);
		job.setOutputKeyClass(Text.class);
		job.setOutputValueClass(Text.class);

		job.setMapperClass(CreateFiles.class);

		job.setNumMapTasks(nFiles/10);
		job.setNumReduceTasks(0);
		JobClient.runJob(job);
	}

	/**
	 * Create a file with the name <code>file</code> and a length of
	 * <code>fileSize</code>. The file is filled with character 'a'.
	 */
	@SuppressWarnings("unused")
	private void genFile(Path file, long fileSize) throws IOException {
		FSDataOutputStream out = fs.create(file, true,
				getConf().getInt("io.file.buffer.size", 4096),
				(short) getConf().getInt("dfs.replication", 3),
				fs.getDefaultBlockSize());
		for (long i = 0; i < fileSize; i++) {
			out.writeByte('a');
		}
		out.close();
	}

	/**
	 * Main program.
	 *
	 * @param args
	 *            Command line arguments
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		int res = ToolRunner
				.run(new Configuration(), new DataGenerator(), args);
		System.exit(res);
	}
}
