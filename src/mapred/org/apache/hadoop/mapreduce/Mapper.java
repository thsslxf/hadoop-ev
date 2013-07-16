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
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.mapred.DirUtil;
import org.apache.hadoop.mapreduce.EVStatistics.StatsType;

/** 
 * Maps input key/value pairs to a set of intermediate key/value pairs.  
 * 
 * <p>Maps are the individual tasks which transform input records into a 
 * intermediate records. The transformed intermediate records need not be of 
 * the same type as the input records. A given input pair may map to zero or 
 * many output pairs.</p> 
 * 
 * <p>The Hadoop Map-Reduce framework spawns one map task for each 
 * {@link InputSplit} generated by the {@link InputFormat} for the job.
 * <code>Mapper</code> implementations can access the {@link Configuration} for 
 * the job via the {@link JobContext#getConfiguration()}.
 * 
 * <p>The framework first calls 
 * {@link #setup(org.apache.hadoop.mapreduce.Mapper.Context)}, followed by
 * {@link #map(Object, Object, Context)} 
 * for each key/value pair in the <code>InputSplit</code>. Finally 
 * {@link #cleanup(Context)} is called.</p>
 * 
 * <p>All intermediate values associated with a given output key are 
 * subsequently grouped by the framework, and passed to a {@link Reducer} to  
 * determine the final output. Users can control the sorting and grouping by 
 * specifying two key {@link RawComparator} classes.</p>
 *
 * <p>The <code>Mapper</code> outputs are partitioned per 
 * <code>Reducer</code>. Users can control which keys (and hence records) go to 
 * which <code>Reducer</code> by implementing a custom {@link Partitioner}.
 * 
 * <p>Users can optionally specify a <code>combiner</code>, via 
 * {@link Job#setCombinerClass(Class)}, to perform local aggregation of the 
 * intermediate outputs, which helps to cut down the amount of data transferred 
 * from the <code>Mapper</code> to the <code>Reducer</code>.
 * 
 * <p>Applications can specify if and how the intermediate
 * outputs are to be compressed and which {@link CompressionCodec}s are to be
 * used via the <code>Configuration</code>.</p>
 *  
 * <p>If the job has zero
 * reduces then the output of the <code>Mapper</code> is directly written
 * to the {@link OutputFormat} without sorting by keys.</p>
 * 
 * <p>Example:</p>
 * <p><blockquote><pre>
 * public class TokenCounterMapper 
 *     extends Mapper<Object, Text, Text, IntWritable>{
 *    
 *   private final static IntWritable one = new IntWritable(1);
 *   private Text word = new Text();
 *   
 *   public void map(Object key, Text value, Context context) throws IOException {
 *     StringTokenizer itr = new StringTokenizer(value.toString());
 *     while (itr.hasMoreTokens()) {
 *       word.set(itr.nextToken());
 *       context.collect(word, one);
 *     }
 *   }
 * }
 * </pre></blockquote></p>
 *
 * <p>Applications may override the {@link #run(Context)} method to exert 
 * greater control on map processing e.g. multi-threaded <code>Mapper</code>s 
 * etc.</p>
 * 
 * @see InputFormat
 * @see JobContext
 * @see Partitioner  
 * @see Reducer
 */
public class Mapper<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {
	private static final Log LOG = LogFactory.getLog(Mapper.class);
	
  public class Context 
    extends MapContext<KEYIN,VALUEIN,KEYOUT,VALUEOUT> {
	  EVStatistics myEVStat = null;
	  KEYIN lastKeyIn;
	  VALUEIN lastValueIn;
	  KEYOUT lastKeyOut;
	  VALUEOUT lastValueOut;
	  
    public Context(Configuration conf, TaskAttemptID taskid,
                   RecordReader<KEYIN,VALUEIN> reader,
                   RecordWriter<KEYOUT,VALUEOUT> writer,
                   OutputCommitter committer,
                   StatusReporter reporter,
                   InputSplit split) throws IOException, InterruptedException {
      super(conf, taskid, reader, writer, committer, reporter, split);
      myEVStat = new EVStatistics();
    }
    
    public void write(KEYOUT key, VALUEOUT value
    		) throws IOException, InterruptedException {
    	super.write(key, value);
    	lastKeyIn = super.getCurrentKey();
    	//lastValueIn = super.getCurrentValue();
    	//lastKeyOut = key;
    	lastValueOut = value;
	}
    
    /**
     * add one piece of stat into EVStatistics
     * @param time in microsecond, us
     */
    public void addStat(long time){
    	if(myEVStat == null)
    		myEVStat = new EVStatistics();
    	myEVStat.addTimeStat(myEVStat.new StatsType(
    		lastKeyIn.getClass().getName(), lastKeyIn.toString()), time);
    }
    
    /**
     * add a record processing result to myEVStat
     */
    public void addCache(){
    	if(myEVStat == null)
    		myEVStat = new EVStatistics();
    	myEVStat.addCacheItem(lastKeyIn.toString(), lastValueOut.toString());
    }
    
    public void addTimeCost(long startTime, long timeCost) {
    	myEVStat.addTimeCost((double) startTime, (double) timeCost);
    }
    
    public EVStatistics getEVStats(){
    	return myEVStat;
    }
  }
  
  /**
   * Called once at the beginning of the task.
   */
  protected void setup(Context context
                       ) throws IOException, InterruptedException {
    // NOTHING
  }

  /**
   * Called once for each key/value pair in the input split. Most applications
   * should override this, but the default is the identity function.
   */
  @SuppressWarnings("unchecked")
  protected void map(KEYIN key, VALUEIN value, 
                     Context context) throws IOException, InterruptedException {
    context.write((KEYOUT) key, (VALUEOUT) value);
  }

  /**
   * Called once at the end of the task.
   */
  protected void cleanup(Context context
                         ) throws IOException, InterruptedException {
    // NOTHING
  }
  
  /**
   * Expert users can override this method for more complete control over the
   * execution of the Mapper.
   * @param context
   * @throws IOException
   */
  public void run(Context context) throws IOException, InterruptedException {
	  long t0 = System.currentTimeMillis();
    setup(context);
    while (context.nextKeyValue()) {
      /* cache begin*/
      /*
      String[] cacheres = FindCacheResult(context.getCurrentKey().toString(), context);
      if(cacheres != null)
      {
    	  LOG.info("Cache hit: " + context.getCurrentKey().toString());
    	  context.write((KEYOUT)(new Text(cacheres[0])), 
    			  (VALUEOUT)(new IntWritable(Integer.parseInt(cacheres[1]))));
      }
      */
      /* cache end*/
//      else
//      {
	      long t1 = System.nanoTime();
	      map(context.getCurrentKey(), context.getCurrentValue(), context);
	      long t2 = System.nanoTime();
	      if (context.getConfiguration().getInt("mapred.evstatistic.enable", 1) == 1)
	      {
	    	  context.addStat((t2 - t1)/1000); // in microsecond
	    	  context.addCache();
	      }
//      }
    }
    cleanup(context);
    long t3 = System.currentTimeMillis();
    if (context.getConfiguration().getInt("mapred.evstatistic.enable", 1) == 1)
    {
    	context.addTimeCost(t0, t3 - t0); // In milliseconds
    }
  }
  
  /**
   * Find the cached result, if not cached, return null
   * @param key
   * @param context
   * @return String tuple {filename, cached result}
   * @throws IOException
   */
  public String[] FindCacheResult(String key, Context context) throws IOException
  {
      FileSystem hdfs = FileSystem.get(context.getConfiguration());
	  String cacheFilename = DirUtil.GetLast2ndSeg(key);
	  cacheFilename = "/cache/" + cacheFilename;
	  if(!hdfs.exists(new Path(cacheFilename)))
		  return null;
	  FSDataInputStream ins = hdfs.open(new Path(cacheFilename));
	  if (ins == null) return null;
	  String line = ins.readLine();
	  while(line != null)
	  {
		  String[] record = line.split(";");
		  if(record[0].equalsIgnoreCase(key))
		  {
			  String outvalue = record[1];
			  String outkey = key.substring(0, key.lastIndexOf("/"));
			  String[] retpair = {outkey, outvalue};
			  return retpair;
		  };
		  line = ins.readLine();
	  }
	  return null;
  }
}