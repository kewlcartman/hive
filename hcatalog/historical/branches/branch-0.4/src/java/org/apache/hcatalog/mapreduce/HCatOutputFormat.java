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

package org.apache.hcatalog.mapreduce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Index;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hcatalog.common.ErrorType;
import org.apache.hcatalog.common.HCatConstants;
import org.apache.hcatalog.common.HCatException;
import org.apache.hcatalog.common.HCatUtil;
import org.apache.hcatalog.data.HCatRecord;
import org.apache.hcatalog.data.schema.HCatSchema;

/** The OutputFormat to use to write data to HCatalog. The key value is ignored and
 *  should be given as null. The value is the HCatRecord to write.*/
public class HCatOutputFormat extends HCatBaseOutputFormat {

    static final private Log LOG = LogFactory.getLog(HCatOutputFormat.class);

    private static int maxDynamicPartitions;
    private static boolean harRequested;

    /**
     * Set the information about the output to write for the job. This queries the metadata server
     * to find the StorageHandler to use for the table.  It throws an error if the 
     * partition is already published.
     * @param job the job object
     * @param outputJobInfo the table output information for the job
     * @throws IOException the exception in communicating with the metadata server
     */
    @SuppressWarnings("unchecked")
    public static void setOutput(Job job, OutputJobInfo outputJobInfo) throws IOException {
      HiveMetaStoreClient client = null;

      try {

        Configuration conf = job.getConfiguration();
        HiveConf hiveConf = HCatUtil.getHiveConf(conf);
        client = HCatUtil.getHiveClient(hiveConf);
        Table table = client.getTable(outputJobInfo.getDatabaseName(), outputJobInfo.getTableName());

        List<String> indexList = client.listIndexNames(outputJobInfo.getDatabaseName(), outputJobInfo.getTableName(), Short.MAX_VALUE);

        for (String indexName : indexList) {
            Index index = client.getIndex(outputJobInfo.getDatabaseName(), outputJobInfo.getTableName(), indexName);
            if (!index.isDeferredRebuild()) {
                throw new HCatException(ErrorType.ERROR_NOT_SUPPORTED, "Store into a table with an automatic index from Pig/Mapreduce is not supported");
            }
        }
        StorageDescriptor sd = table.getSd();

        if (sd.isCompressed()) {
            throw new HCatException(ErrorType.ERROR_NOT_SUPPORTED, "Store into a compressed partition from Pig/Mapreduce is not supported");
        }

        if (sd.getBucketCols()!=null && !sd.getBucketCols().isEmpty()) {
            throw new HCatException(ErrorType.ERROR_NOT_SUPPORTED, "Store into a partition with bucket definition from Pig/Mapreduce is not supported");
        }

        if (sd.getSortCols()!=null && !sd.getSortCols().isEmpty()) {
            throw new HCatException(ErrorType.ERROR_NOT_SUPPORTED, "Store into a partition with sorted column definition from Pig/Mapreduce is not supported");
        }

        if (table.getPartitionKeysSize() == 0 ){
          if ((outputJobInfo.getPartitionValues() != null) && (!outputJobInfo.getPartitionValues().isEmpty())){
            // attempt made to save partition values in non-partitioned table - throw error.
            throw new HCatException(ErrorType.ERROR_INVALID_PARTITION_VALUES,
                "Partition values specified for non-partitioned table");
          }
          // non-partitioned table
          outputJobInfo.setPartitionValues(new HashMap<String, String>());

        } else {
          // partitioned table, we expect partition values
          // convert user specified map to have lower case key names
          Map<String, String> valueMap = new HashMap<String, String>();
          if (outputJobInfo.getPartitionValues() != null){
            for(Map.Entry<String, String> entry : outputJobInfo.getPartitionValues().entrySet()) {
              valueMap.put(entry.getKey().toLowerCase(), entry.getValue());
            }
          }

          if ((outputJobInfo.getPartitionValues() == null)
              || (outputJobInfo.getPartitionValues().size() < table.getPartitionKeysSize())){
            // dynamic partition usecase - partition values were null, or not all were specified
            // need to figure out which keys are not specified.
            List<String> dynamicPartitioningKeys = new ArrayList<String>();
            boolean firstItem = true;
            for (FieldSchema fs : table.getPartitionKeys()){
              if (!valueMap.containsKey(fs.getName().toLowerCase())){
                dynamicPartitioningKeys.add(fs.getName().toLowerCase());
              }
            }

            if (valueMap.size() + dynamicPartitioningKeys.size() != table.getPartitionKeysSize()){
              // If this isn't equal, then bogus key values have been inserted, error out.
              throw new HCatException(ErrorType.ERROR_INVALID_PARTITION_VALUES,"Invalid partition keys specified");
            }

            outputJobInfo.setDynamicPartitioningKeys(dynamicPartitioningKeys);
            String dynHash;
            if ((dynHash = conf.get(HCatConstants.HCAT_DYNAMIC_PTN_JOBID)) == null){
              dynHash = String.valueOf(Math.random());
//              LOG.info("New dynHash : ["+dynHash+"]");
//            }else{
//              LOG.info("Old dynHash : ["+dynHash+"]");
            }
            conf.set(HCatConstants.HCAT_DYNAMIC_PTN_JOBID, dynHash);

          }

          outputJobInfo.setPartitionValues(valueMap);
        }

        StorageDescriptor tblSD = table.getSd();
        HCatSchema tableSchema = HCatUtil.extractSchemaFromStorageDescriptor(tblSD);
        StorerInfo storerInfo = InternalUtil.extractStorerInfo(tblSD,table.getParameters());

        List<String> partitionCols = new ArrayList<String>();
        for(FieldSchema schema : table.getPartitionKeys()) {
          partitionCols.add(schema.getName());
        }

       HCatStorageHandler storageHandler = HCatUtil.getStorageHandler(job.getConfiguration(), storerInfo);

        //Serialize the output info into the configuration
        outputJobInfo.setTableInfo(HCatTableInfo.valueOf(table));
        outputJobInfo.setOutputSchema(tableSchema);
        harRequested = getHarRequested(hiveConf);
        outputJobInfo.setHarRequested(harRequested);
        maxDynamicPartitions = getMaxDynamicPartitions(hiveConf);
        outputJobInfo.setMaximumDynamicPartitions(maxDynamicPartitions);

        HCatUtil.configureOutputStorageHandler(storageHandler,job,outputJobInfo);

        Path tblPath = new Path(table.getSd().getLocation());

        /*  Set the umask in conf such that files/dirs get created with table-dir
         * permissions. Following three assumptions are made:
         * 1. Actual files/dirs creation is done by RecordWriter of underlying
         * output format. It is assumed that they use default permissions while creation.
         * 2. Default Permissions = FsPermission.getDefault() = 777.
         * 3. UMask is honored by underlying filesystem.
         */

        FsPermission.setUMask(conf, FsPermission.getDefault().applyUMask(
            tblPath.getFileSystem(conf).getFileStatus(tblPath).getPermission()));

        if(Security.getInstance().isSecurityEnabled()) {
            Security.getInstance().handleSecurity(job, outputJobInfo, client, conf, harRequested);
        }
      } catch(Exception e) {
        if( e instanceof HCatException ) {
          throw (HCatException) e;
        } else {
          throw new HCatException(ErrorType.ERROR_SET_OUTPUT, e);
        }
      } finally {
        HCatUtil.closeHiveClientQuietly(client);
      }
    }

    /**
     * Set the schema for the data being written out to the partition. The
     * table schema is used by default for the partition if this is not called.
     * @param job the job object
     * @param schema the schema for the data
     * @throws IOException
     */
    public static void setSchema(final Job job, final HCatSchema schema) throws IOException {

        OutputJobInfo jobInfo = getJobInfo(job);
        Map<String,String> partMap = jobInfo.getPartitionValues();
        setPartDetails(jobInfo, schema, partMap);
        job.getConfiguration().set(HCatConstants.HCAT_KEY_OUTPUT_INFO, HCatUtil.serialize(jobInfo));
    }

    /**
     * Get the record writer for the job. This uses the StorageHandler's default 
     * OutputFormat to get the record writer.
     * @param context the information about the current task
     * @return a RecordWriter to write the output for the job
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public RecordWriter<WritableComparable<?>, HCatRecord>
        getRecordWriter(TaskAttemptContext context)
        throws IOException, InterruptedException {
      return getOutputFormat(context).getRecordWriter(context);
    }


    /**
     * Get the output committer for this output format. This is responsible
     * for ensuring the output is committed correctly.
     * @param context the task context
     * @return an output committer
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public OutputCommitter getOutputCommitter(TaskAttemptContext context
                                       ) throws IOException, InterruptedException {
        return getOutputFormat(context).getOutputCommitter(context);
    }

    private static int getMaxDynamicPartitions(HiveConf hConf) {
      // by default the bounds checking for maximum number of
      // dynamic partitions is disabled (-1)
      int maxDynamicPartitions = -1;

      if (HCatConstants.HCAT_IS_DYNAMIC_MAX_PTN_CHECK_ENABLED){
        maxDynamicPartitions = hConf.getIntVar(
                                HiveConf.ConfVars.DYNAMICPARTITIONMAXPARTS);
      }

      return maxDynamicPartitions;
    }

    private static boolean getHarRequested(HiveConf hConf) {
      return hConf.getBoolVar(HiveConf.ConfVars.HIVEARCHIVEENABLED);
    }

}
