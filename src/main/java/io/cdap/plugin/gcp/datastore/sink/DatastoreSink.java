/*
 * Copyright © 2019 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package io.cdap.plugin.gcp.datastore.sink;

import com.google.cloud.datastore.FullEntity;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.api.data.batch.Output;
import io.cdap.cdap.api.data.format.StructuredRecord;
import io.cdap.cdap.api.data.schema.Schema;
import io.cdap.cdap.api.dataset.lib.KeyValue;
import io.cdap.cdap.etl.api.Emitter;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.StageConfigurer;
import io.cdap.cdap.etl.api.batch.BatchRuntimeContext;
import io.cdap.cdap.etl.api.batch.BatchSink;
import io.cdap.cdap.etl.api.batch.BatchSinkContext;
import io.cdap.plugin.common.LineageRecorder;
import org.apache.hadoop.io.NullWritable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

/**
 * A {@link BatchSink} that writes data to Cloud Datastore.
 * This {@link DatastoreSink} takes a {@link StructuredRecord} in, converts it to Entity, and writes it to the
 * Cloud Datastore kind.
 */
@Plugin(type = BatchSink.PLUGIN_TYPE)
@Name(DatastoreSink.PLUGIN_NAME)
@Description("CDAP Google Cloud Datastore Batch Sink takes the structured record from the input source and writes "
  + "to Google Cloud Datastore.")
public class DatastoreSink extends BatchSink<StructuredRecord, NullWritable, FullEntity<?>> {

  private static final Logger LOG = LoggerFactory.getLogger(DatastoreSink.class);

  public static final String PLUGIN_NAME = "Datastore";

  private final DatastoreSinkConfig config;
  private RecordToEntityTransformer recordToEntityTransformer;

  public DatastoreSink(DatastoreSinkConfig config) {
    this.config = config;
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    super.configurePipeline(pipelineConfigurer);
    StageConfigurer configurer = pipelineConfigurer.getStageConfigurer();
    Schema inputSchema = configurer.getInputSchema();
    FailureCollector collector = configurer.getFailureCollector();
    config.validate(inputSchema, collector);
  }

  @SuppressWarnings("ConstantConditions")
  @Override
  public void prepareRun(BatchSinkContext context) {
    Schema inputSchema = context.getInputSchema();
    LOG.debug("DatastoreSink `prepareRun` input schema: {}", inputSchema);
    FailureCollector collector = context.getFailureCollector();
    config.validate(inputSchema, collector);
    collector.getOrThrowException();

    String project = config.getProject();
    String serviceAccountFile = config.getServiceAccountFilePath();
    String shouldAutoGenerateKey = Boolean.toString(config.shouldUseAutoGeneratedKey(collector));
    String batchSize = Integer.toString(config.getBatchSize());

    context.addOutput(Output.of(config.getReferenceName(),
                                new DatastoreOutputFormatProvider(project, serviceAccountFile,
                                                                  shouldAutoGenerateKey, batchSize)));

    LineageRecorder lineageRecorder = new LineageRecorder(context, config.getReferenceName());
    lineageRecorder.createExternalDataset(inputSchema);
    // Record the field level WriteOperation
    lineageRecorder.recordWrite("Write", "Wrote to Cloud Datastore sink",
                                inputSchema.getFields().stream()
                                  .map(Schema.Field::getName)
                                  .collect(Collectors.toList()));
  }

  @Override
  public void initialize(BatchRuntimeContext context) throws Exception {
    super.initialize(context);
    FailureCollector collector = context.getFailureCollector();
    this.recordToEntityTransformer = new RecordToEntityTransformer(config.getProject(),
                                                                   config.getNamespace(),
                                                                   config.getKind(),
                                                                   config.getKeyType(collector),
                                                                   config.getKeyAlias(),
                                                                   config.getAncestor(collector),
                                                                   config.getIndexStrategy(collector),
                                                                   config.getIndexedProperties());
  }

  @Override
  public void transform(StructuredRecord record, Emitter<KeyValue<NullWritable, FullEntity<?>>> emitter) {
    FullEntity<?> fullEntity = recordToEntityTransformer.transformStructuredRecord(record);
    emitter.emit(new KeyValue<>(null, fullEntity));
  }
}
