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

package org.apache.hadoop.mapreduce;

import java.io.IOException;
import java.security.PrivilegedExceptionAction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.EVStatistics.Stats;
import org.apache.hadoop.mapreduce.EVStatistics.StatsType;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapred.EVStatsServer;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.JobTracker;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TaskCompletionEvent;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.hsqldb.lib.StringUtil;
//import org.mortbay.log.Log;

/**
 * The job submitter's view of the Job. It allows the user to configure the
 * job, submit it, control its execution, and query the state. The set methods
 * only work until the job is submitted, afterwards they will throw an 
 * IllegalStateException.
 */
public class Job extends JobContext {  
	public static final Log LOG = LogFactory.getLog(Job.class);
	
  public static enum JobState {DEFINE, RUNNING};
  private JobState state = JobState.DEFINE;
  private JobClient jobClient;
  private RunningJob info;
  
  //EVStatsServer port (TODO: start a bunch of ports to handle concurrent connections?)
  private int portEVStatsServer; 
  private static EVStatsServer evStatsServer;

  public Job() throws IOException {
    this(new Configuration());
  }

  public Job(Configuration conf) throws IOException {
	  super(conf, null);
	  if (evStatsServer == null) {
	    // Start EVStatsServer
	    Random rand = new Random();
	    this.portEVStatsServer = 10593 + rand.nextInt(1000);
	    getConfiguration().setInt("mapred.evstats.serverport", portEVStatsServer);
	    evStatsServer = new EVStatsServer(this.portEVStatsServer, this);
	    evStatsServer.start();	    
	  }    
  }

  public Job(Configuration conf, String jobName) throws IOException {
    this(conf);
    setJobName(jobName);
  }

  JobClient getJobClient() {
    return jobClient;
  }
  
  private void ensureState(JobState state) throws IllegalStateException {
    if (state != this.state) {
      throw new IllegalStateException("Job in state "+ this.state + 
                                      " instead of " + state);
    }

    if (state == JobState.RUNNING && jobClient == null) {
      throw new IllegalStateException("Job in state " + JobState.RUNNING + 
                                      " however jobClient is not initialized!");
    }
  }

  /**
   * Set the number of reduce tasks for the job.
   * @param tasks the number of reduce tasks
   * @throws IllegalStateException if the job is submitted
   */
  public void setNumReduceTasks(int tasks) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setNumReduceTasks(tasks);
  }

  /**
   * Set the current working directory for the default file system.
   * 
   * @param dir the new current working directory.
   * @throws IllegalStateException if the job is submitted
   */
  public void setWorkingDirectory(Path dir) throws IOException {
    ensureState(JobState.DEFINE);
    conf.setWorkingDirectory(dir);
  }

  /**
   * Set the {@link InputFormat} for the job.
   * @param cls the <code>InputFormat</code> to use
   * @throws IllegalStateException if the job is submitted
   */
  public void setInputFormatClass(Class<? extends InputFormat> cls
                                  ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setClass(INPUT_FORMAT_CLASS_ATTR, cls, InputFormat.class);
  }

  /**
   * Set the {@link OutputFormat} for the job.
   * @param cls the <code>OutputFormat</code> to use
   * @throws IllegalStateException if the job is submitted
   */
  public void setOutputFormatClass(Class<? extends OutputFormat> cls
                                   ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setClass(OUTPUT_FORMAT_CLASS_ATTR, cls, OutputFormat.class);
  }

  /**
   * Set the {@link Mapper} for the job.
   * @param cls the <code>Mapper</code> to use
   * @throws IllegalStateException if the job is submitted
   */
  public void setMapperClass(Class<? extends Mapper> cls
                             ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setClass(MAP_CLASS_ATTR, cls, Mapper.class);
  }

  /**
   * Set the Jar by finding where a given class came from.
   * @param cls the example class
   */
  public void setJarByClass(Class<?> cls) {
    conf.setJarByClass(cls);
  }
  
  /**
   * Get the pathname of the job's jar.
   * @return the pathname
   */
  public String getJar() {
    return conf.getJar();
  }

  /**
   * Set the combiner class for the job.
   * @param cls the combiner to use
   * @throws IllegalStateException if the job is submitted
   */
  public void setCombinerClass(Class<? extends Reducer> cls
                               ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setClass(COMBINE_CLASS_ATTR, cls, Reducer.class);
  }

  /**
   * Set the {@link Reducer} for the job.
   * @param cls the <code>Reducer</code> to use
   * @throws IllegalStateException if the job is submitted
   */
  public void setReducerClass(Class<? extends Reducer> cls
                              ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setClass(REDUCE_CLASS_ATTR, cls, Reducer.class);
  }

  /**
   * Set the {@link Partitioner} for the job.
   * @param cls the <code>Partitioner</code> to use
   * @throws IllegalStateException if the job is submitted
   */
  public void setPartitionerClass(Class<? extends Partitioner> cls
                                  ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setClass(PARTITIONER_CLASS_ATTR, cls, Partitioner.class);
  }

  /**
   * Set the key class for the map output data. This allows the user to
   * specify the map output key class to be different than the final output
   * value class.
   * 
   * @param theClass the map output key class.
   * @throws IllegalStateException if the job is submitted
   */
  public void setMapOutputKeyClass(Class<?> theClass
                                   ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setMapOutputKeyClass(theClass);
  }

  /**
   * Set the value class for the map output data. This allows the user to
   * specify the map output value class to be different than the final output
   * value class.
   * 
   * @param theClass the map output value class.
   * @throws IllegalStateException if the job is submitted
   */
  public void setMapOutputValueClass(Class<?> theClass
                                     ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setMapOutputValueClass(theClass);
  }

  /**
   * Set the key class for the job output data.
   * 
   * @param theClass the key class for the job output data.
   * @throws IllegalStateException if the job is submitted
   */
  public void setOutputKeyClass(Class<?> theClass
                                ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setOutputKeyClass(theClass);
  }

  /**
   * Set the value class for job outputs.
   * 
   * @param theClass the value class for job outputs.
   * @throws IllegalStateException if the job is submitted
   */
  public void setOutputValueClass(Class<?> theClass
                                  ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setOutputValueClass(theClass);
  }

  /**
   * Define the comparator that controls how the keys are sorted before they
   * are passed to the {@link Reducer}.
   * @param cls the raw comparator
   * @throws IllegalStateException if the job is submitted
   */
  public void setSortComparatorClass(Class<? extends RawComparator> cls
                                     ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setOutputKeyComparatorClass(cls);
  }

  /**
   * Define the comparator that controls which keys are grouped together
   * for a single call to 
   * {@link Reducer#reduce(Object, Iterable, 
   *                       org.apache.hadoop.mapreduce.Reducer.Context)}
   * @param cls the raw comparator to use
   * @throws IllegalStateException if the job is submitted
   */
  public void setGroupingComparatorClass(Class<? extends RawComparator> cls
                                         ) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setOutputValueGroupingComparator(cls);
  }

  /**
   * Set the user-specified job name.
   * 
   * @param name the job's new name.
   * @throws IllegalStateException if the job is submitted
   */
  public void setJobName(String name) throws IllegalStateException {
    ensureState(JobState.DEFINE);
    conf.setJobName(name);
  }
  
  /**
   * Turn speculative execution on or off for this job. 
   * 
   * @param speculativeExecution <code>true</code> if speculative execution 
   *                             should be turned on, else <code>false</code>.
   */
  public void setSpeculativeExecution(boolean speculativeExecution) {
    ensureState(JobState.DEFINE);
    conf.setSpeculativeExecution(speculativeExecution);
  }

  /**
   * Turn speculative execution on or off for this job for map tasks. 
   * 
   * @param speculativeExecution <code>true</code> if speculative execution 
   *                             should be turned on for map tasks,
   *                             else <code>false</code>.
   */
  public void setMapSpeculativeExecution(boolean speculativeExecution) {
    ensureState(JobState.DEFINE);
    conf.setMapSpeculativeExecution(speculativeExecution);
  }

  /**
   * Turn speculative execution on or off for this job for reduce tasks. 
   * 
   * @param speculativeExecution <code>true</code> if speculative execution 
   *                             should be turned on for reduce tasks,
   *                             else <code>false</code>.
   */
  public void setReduceSpeculativeExecution(boolean speculativeExecution) {
    ensureState(JobState.DEFINE);
    conf.setReduceSpeculativeExecution(speculativeExecution);
  }

  /**
   * Get the URL where some job progress information will be displayed.
   * 
   * @return the URL where some job progress information will be displayed.
   */
  public String getTrackingURL() {
    ensureState(JobState.RUNNING);
    return info.getTrackingURL();
  }

  /**
   * Get the <i>progress</i> of the job's setup, as a float between 0.0 
   * and 1.0.  When the job setup is completed, the function returns 1.0.
   * 
   * @return the progress of the job's setup.
   * @throws IOException
   */
  public float setupProgress() throws IOException {
    ensureState(JobState.RUNNING);
    return info.setupProgress();
  }

  /**
   * Get the <i>progress</i> of the job's map-tasks, as a float between 0.0 
   * and 1.0.  When all map tasks have completed, the function returns 1.0.
   * 
   * @return the progress of the job's map-tasks.
   * @throws IOException
   */
  public float mapProgress() throws IOException {
    ensureState(JobState.RUNNING);
    return info.mapProgress();
  }

  /**
   * Get the <i>progress</i> of the job's reduce-tasks, as a float between 0.0 
   * and 1.0.  When all reduce tasks have completed, the function returns 1.0.
   * 
   * @return the progress of the job's reduce-tasks.
   * @throws IOException
   */
  public float reduceProgress() throws IOException {
    ensureState(JobState.RUNNING);
    return info.reduceProgress();
  }

  /**
   * Check if the job is finished or not. 
   * This is a non-blocking call.
   * 
   * @return <code>true</code> if the job is complete, else <code>false</code>.
   * @throws IOException
   */
  public boolean isComplete() throws IOException {
    ensureState(JobState.RUNNING);
    return info.isComplete();
  }

  /**
   * Check if the job completed successfully. 
   * 
   * @return <code>true</code> if the job succeeded, else <code>false</code>.
   * @throws IOException
   */
  public boolean isSuccessful() throws IOException {
    ensureState(JobState.RUNNING);
    return info.isSuccessful();
  }

  /**
   * Kill the running job.  Blocks until all job tasks have been
   * killed as well.  If the job is no longer running, it simply returns.
   * 
   * @throws IOException
   */
  public void killJob() throws IOException {
    ensureState(JobState.RUNNING);
    info.killJob();
  }
    
  /**
   * Get events indicating completion (success/failure) of component tasks.
   *  
   * @param startFrom index to start fetching events from
   * @return an array of {@link TaskCompletionEvent}s
   * @throws IOException
   */
  public TaskCompletionEvent[] getTaskCompletionEvents(int startFrom
                                                       ) throws IOException {
    ensureState(JobState.RUNNING);
    return info.getTaskCompletionEvents(startFrom);
  }
  
  /**
   * Kill indicated task attempt.
   * 
   * @param taskId the id of the task to be terminated.
   * @throws IOException
   */
  public void killTask(TaskAttemptID taskId) throws IOException {
    ensureState(JobState.RUNNING);
    info.killTask(org.apache.hadoop.mapred.TaskAttemptID.downgrade(taskId), 
                  false);
  }

  /**
   * Fail indicated task attempt.
   * 
   * @param taskId the id of the task to be terminated.
   * @throws IOException
   */
  public void failTask(TaskAttemptID taskId) throws IOException {
    ensureState(JobState.RUNNING);
    info.killTask(org.apache.hadoop.mapred.TaskAttemptID.downgrade(taskId), 
                  true);
  }

  /**
   * Gets the counters for this job.
   * 
   * @return the counters for this job.
   * @throws IOException
   */
  public Counters getCounters() throws IOException {
    ensureState(JobState.RUNNING);
    return new Counters(info.getCounters());
  }

  private void ensureNotSet(String attr, String msg) throws IOException {
    if (conf.get(attr) != null) {
      throw new IOException(attr + " is incompatible with " + msg + " mode.");
    }    
  }
  
  /**
   * Sets the flag that will allow the JobTracker to cancel the HDFS delegation
   * tokens upon job completion. Defaults to true.
   */
  public void setCancelDelegationTokenUponJobCompletion(boolean value) {
    ensureState(JobState.DEFINE);
    conf.setBoolean(JOB_CANCEL_DELEGATION_TOKEN, value);
  }

  /**
   * Default to the new APIs unless they are explicitly set or the old mapper or
   * reduce attributes are used.
   * @throws IOException if the configuration is inconsistant
   */
  private void setUseNewAPI() throws IOException {
    int numReduces = conf.getNumReduceTasks();
    String oldMapperClass = "mapred.mapper.class";
    String oldReduceClass = "mapred.reducer.class";
    conf.setBooleanIfUnset("mapred.mapper.new-api",
                           conf.get(oldMapperClass) == null);
    if (conf.getUseNewMapper()) {
      String mode = "new map API";
      ensureNotSet("mapred.input.format.class", mode);
      ensureNotSet(oldMapperClass, mode);
      if (numReduces != 0) {
        ensureNotSet("mapred.partitioner.class", mode);
       } else {
        ensureNotSet("mapred.output.format.class", mode);
      }      
    } else {
      String mode = "map compatability";
      ensureNotSet(JobContext.INPUT_FORMAT_CLASS_ATTR, mode);
      ensureNotSet(JobContext.MAP_CLASS_ATTR, mode);
      if (numReduces != 0) {
        ensureNotSet(JobContext.PARTITIONER_CLASS_ATTR, mode);
       } else {
        ensureNotSet(JobContext.OUTPUT_FORMAT_CLASS_ATTR, mode);
      }
    }
    if (numReduces != 0) {
      conf.setBooleanIfUnset("mapred.reducer.new-api",
                             conf.get(oldReduceClass) == null);
      if (conf.getUseNewReducer()) {
        String mode = "new reduce API";
        ensureNotSet("mapred.output.format.class", mode);
        ensureNotSet(oldReduceClass, mode);   
      } else {
        String mode = "reduce compatability";
        ensureNotSet(JobContext.OUTPUT_FORMAT_CLASS_ATTR, mode);
        ensureNotSet(JobContext.REDUCE_CLASS_ATTR, mode);   
      }
    }   
  }

  /**
   * Submit the job to the cluster and return immediately.
   * @throws IOException
   */
  public void submit() throws IOException, InterruptedException, 
                              ClassNotFoundException {
    ensureState(JobState.DEFINE);
    setUseNewAPI();
    
    // Connect to the JobTracker and submit the job
    connect();
    info = jobClient.submitJobInternal(conf);
    super.setJobID(info.getID());
    state = JobState.RUNNING;
   }
  
  /**
   * Open a connection to the JobTracker
   * @throws IOException
   * @throws InterruptedException 
   */
  private void connect() throws IOException, InterruptedException {
    ugi.doAs(new PrivilegedExceptionAction<Object>() {
      public Object run() throws IOException {
        jobClient = new JobClient((JobConf) getConfiguration());    
        return null;
      }
    });
  }
  
  /**
   * Submit the job to the cluster and wait for it to finish.
   * @param verbose print the progress to the user
   * @return true if the job succeeded
   * @throws IOException thrown if the communication with the 
   *         <code>JobTracker</code> is lost
   */
  public boolean waitForCompletion(boolean verbose
                                   ) throws IOException, InterruptedException,
                                            ClassNotFoundException {
    if (state == JobState.DEFINE) {
      submit();
    }
    if (verbose) {
      jobClient.monitorAndPrintJob(conf, info);
    } else {
      info.waitForCompletion();
    }
    return isSuccessful();
  }
  
  /**
   * Get the sample strategy and submit a series of jobs
   * @throws ClassNotFoundException 
   * @throws InterruptedException 
   * @throws IOException 
   * @throws CloneNotSupportedException 
   */
  public boolean waitForSampleCompletion() throws IOException, InterruptedException, ClassNotFoundException, CloneNotSupportedException
  {
	  String dirs = this.getConfiguration().get("mapred.input.dir", "");
	  String [] list = StringUtils.split(dirs);
	  
	  // Get time constraint.
	  int timeConstraint = this.getConfiguration().getInt("mapred.deadline.second", 200);
	  long deadline = System.currentTimeMillis() + timeConstraint * 1000;
	  LOG.info("Deadline: " + deadline + " (" + timeConstraint + "s)");
	  
	  Configuration conf = this.getConfiguration();
	  InputFormat<?, ?> input = ReflectionUtils.newInstance(this.getInputFormatClass(), conf);

	  List<FileStatus> files = ((FileInputFormat)input).getListStatus(this);
	  int runCount = 0;
	  // loop until deadline.
	  while(System.currentTimeMillis() < deadline)
	  {		  
		  LOG.info("To deadline: " + (deadline - System.currentTimeMillis()) + " ms");
		  runCount++;
		  Map<String, Double> proportion = processEVStats();
		  long avgTime = Long.valueOf(evStats.getAggreStat("time_per_record"));
		  int totalSize = Integer.valueOf(evStats.getAggreStat("total_size")); 
		  int nextSize = 1;
		  if (runCount < 3) {
			  nextSize = 10 * runCount;
		  } else {
			  // Now, nextSize is simply determined by the average time cost of previous 
			  // Map execution. Current simple estimation is not accurate as extra overhead
			  // is ignored. 
			  if (avgTime > 0) {
				  nextSize = (int) ((deadline - System.currentTimeMillis()) * 1000000 / avgTime);
			  }
		  }
		  LOG.info("Next sample size = " + nextSize + "\t" + avgTime + " : " + totalSize);
		  List<String> inputfile =  RandomSample(files,  nextSize, proportion);
		  Job newjob = new Job(this.getConfiguration(), "sample_" + runCount);
		  LOG.info(newjob.getJar() + " " + list.length);
		  FileOutputFormat.setOutputPath(newjob, new Path(this.getConfiguration().get(("mapred.output.dir")) + "_" + runCount));
		  FileInputFormat.setInputPaths(newjob, new Path(inputfile.get(0)));
		  for (int j=1; j<inputfile.size(); j++)
		  {
			  FileInputFormat.addInputPath(newjob, new Path(inputfile.get(j)));
		  }
			
		  newjob.waitForCompletion(true);
	  }
	  return true;
  }
  
  /**
   * Random sample num files from a FileStatus List
   * @param files
   * @param num
   * @return
   */
  private List<String> RandomSample(List<FileStatus> files, int num)
  {
	  if (num > files.size())
		  num = files.size();
	  List<String> res = new ArrayList<String>();
	  Random rand = new Random();
	  for(int i=0; i<num; i++)
	  {
		  int idx = rand.nextInt(files.size()-1);
		  res.add(HDFStoLocalConvert(files.get(idx).getPath().toString()));
	  }
	  return res;
  }
  
  // Get file list based on the proportion of different dimensions (i.e., location).
  private List<String> RandomSample(List<FileStatus> files, int num, Map<String, Double> proportion)
  {
	  if (proportion.size() == 0) {
		  return RandomSample(files, num);
	  }
	  if (num > files.size())
		  num = files.size();
	  List<String> res = new ArrayList<String>();
	  Map<String, Double> sizeProportion = new HashMap<String, Double>();
	  for (String key : proportion.keySet()) {
		  sizeProportion.put(key, num * proportion.get(key));
		  LOG.info("RandomSample: " + key + " " + sizeProportion.get(key));
	  }
	  int count = num;
	  Random rand = new Random();
	  while(count > 0.99)
	  {
		  int idx = rand.nextInt(files.size()-1);
		  String filename = HDFStoLocalConvert(files.get(idx).getPath().toString());
		  for (String key : sizeProportion.keySet()) {
			  if (filename.contains(key) && sizeProportion.get(key) >= 1.0) {
				  proportion.put(key, proportion.get(key) - 1.0); // decrease one from quota
				  res.add(filename);
				  count--;
				  break;
			  }
		  }
	  }
	  return res;
  }
  
  /**
   * Convert HDFS dir to local dir
   */
  private String HDFStoLocalConvert(String HDFSStr)
  {
	  int iter = 5;
	  int pos = -1;
	  for (int i=0; i<iter; i++)
	  {
		  pos = HDFSStr.indexOf("/", pos+1);
	  }
	  //LOG.info(HDFSStr.substring(pos+1));
	  return HDFSStr.substring(pos+1);
  }
 
  Set<EVStatistics> evStatsSet = new HashSet<EVStatistics>();
  EVStatistics evStats = new EVStatistics();
  
  /**
   * Add one piece of EVStats into the global set.
   * @param evStat
   */
  public void addEVStats(EVStatistics evStat){
	  if (evStat == null || evStat.getSize() == 0) {
		  LOG.warn("Got a null/empty stat.");
		  return;
	  }
	  synchronized (Job.this){
		  evStatsSet.add(evStat);
		  LOG.warn("addEVStats size = " + evStatsSet.size());
	  }
  }
  
  /**
   * Process current set of EVStats to get the time-cost distribution across different domain. 
   * Currently, we aggregate data for each folder (i.e, camera location).
   * @return Map<String, Double>, e.g., <"16m_1", 0.90>, <"16m_1", 0.099>
   */
  Map<String, Double> processEVStats(){
	  long avgTime = 0;
	  long totalSize = 0;
	  Map<String, Stats> final_stat = new HashMap<String, Stats>();
	  
      for (EVStatistics evstat : evStatsSet) {
    	  avgTime += evstat.getAvgTime() * evstat.getSize();
    	  totalSize += evstat.getSize();
    	  for (StatsType type : evstat.timeProfile.keySet()) {
    		  String loc = type.value;
    		  loc = loc.substring(0, loc.lastIndexOf("/"));
    		  loc = loc.substring(loc.lastIndexOf("/")+1);
    		  if (!final_stat.containsKey(loc)) {
    			  final_stat.put(loc, evStats.new Stats());
    		  }
    		  Stats stat = final_stat.get(loc);
    		  stat.addValue(evstat.timeProfile.get(type));
    	  }
      }
      for (String key : final_stat.keySet()) {
    	  final_stat.get(key).computeAvg();
      }
      for (EVStatistics evstat : evStatsSet) {
    	  for (StatsType type : evstat.timeProfile.keySet()) {
    		  String loc = type.value;
    		  loc = loc.substring(0, loc.lastIndexOf("/"));
    		  loc = loc.substring(loc.lastIndexOf("/")+1);
    		  Stats stat = final_stat.get(loc);
    		  stat.addDiff(evstat.timeProfile.get(type));
    	  }
      }
      double total = 0;
      for (String key : final_stat.keySet()) {
    	  final_stat.get(key).computeVar();
    	  total += final_stat.get(key).var;
    	  LOG.info(key + "\t" + final_stat.get(key).avg + "  " + final_stat.get(key).var + " " +
    			  final_stat.get(key).count);
      }
      Map<String, Double> ret = new HashMap<String, Double>();
      for (String key : final_stat.keySet()) {
    	  ret.put(key, final_stat.get(key).var / total);
      }
      
      if(totalSize > 0) {
    	  evStats.addAggreStat("time_per_record", String.valueOf(avgTime / totalSize));
    	  evStats.addAggreStat("total_size", String.valueOf(totalSize));
      } else {
    	  evStats.addAggreStat("time_per_record", String.valueOf(0));
    	  evStats.addAggreStat("total_size", String.valueOf(0));
      }
      evStatsSet.clear();
      
      return ret;
  }
  
}
