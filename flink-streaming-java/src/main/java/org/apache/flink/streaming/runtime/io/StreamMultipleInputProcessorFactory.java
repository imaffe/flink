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

package org.apache.flink.streaming.runtime.io;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.TaskInfo;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.memory.ManagedMemoryUseCase;
import org.apache.flink.metrics.Counter;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.checkpoint.InflightDataRescalingDescriptor;
import org.apache.flink.runtime.io.disk.iomanager.IOManager;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.memory.MemoryManager;
import org.apache.flink.runtime.metrics.groups.TaskIOMetricGroup;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.Input;
import org.apache.flink.streaming.api.operators.InputSelectable;
import org.apache.flink.streaming.api.operators.MultipleInputStreamOperator;
import org.apache.flink.streaming.api.operators.sort.MultiInputSortingDataInput;
import org.apache.flink.streaming.api.operators.sort.MultiInputSortingDataInput.SelectableSortingInputs;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.io.checkpointing.CheckpointedInputGate;
import org.apache.flink.streaming.runtime.metrics.WatermarkGauge;
import org.apache.flink.streaming.runtime.partitioner.StreamPartitioner;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.streamstatus.StatusWatermarkValve;
import org.apache.flink.streaming.runtime.streamstatus.StreamStatus;
import org.apache.flink.streaming.runtime.tasks.OperatorChain;
import org.apache.flink.streaming.runtime.tasks.SourceOperatorStreamTask;
import org.apache.flink.streaming.runtime.tasks.WatermarkGaugeExposingOutput;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.apache.flink.streaming.api.graph.StreamConfig.requiresSorting;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/** A factory for {@link StreamMultipleInputProcessor}. */
@Internal
public class StreamMultipleInputProcessorFactory {

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static StreamMultipleInputProcessor create(
            AbstractInvokable ownerTask,
            CheckpointedInputGate[] checkpointedInputGates,
            StreamConfig.InputConfig[] configuredInputs,
            IOManager ioManager,
            MemoryManager memoryManager,
            TaskIOMetricGroup ioMetricGroup,
            Counter mainOperatorRecordsIn,
            MultipleInputStreamOperator<?> mainOperator,
            WatermarkGauge[] inputWatermarkGauges,
            StreamConfig streamConfig,
            Configuration taskManagerConfig,
            Configuration jobConfig,
            ExecutionConfig executionConfig,
            ClassLoader userClassloader,
            OperatorChain<?, ?> operatorChain,
            InflightDataRescalingDescriptor inflightDataRescalingDescriptor,
            Function<Integer, StreamPartitioner<?>> gatePartitioners,
            TaskInfo taskInfo) {
        checkNotNull(operatorChain);

        List<Input> operatorInputs = mainOperator.getInputs();
        int inputsCount = operatorInputs.size();

        StreamOneInputProcessor<?>[] inputProcessors = new StreamOneInputProcessor[inputsCount];
        Counter networkRecordsIn = new SimpleCounter();
        ioMetricGroup.reuseRecordsInputCounter(networkRecordsIn);

        checkState(
                configuredInputs.length == inputsCount,
                "Number of configured inputs in StreamConfig [%s] doesn't match the main operator's number of inputs [%s]",
                configuredInputs.length,
                inputsCount);
        StreamTaskInput[] inputs = new StreamTaskInput[inputsCount];
        for (int i = 0; i < inputsCount; i++) {
            StreamConfig.InputConfig configuredInput = configuredInputs[i];
            if (configuredInput instanceof StreamConfig.NetworkInputConfig) {
                StreamConfig.NetworkInputConfig networkInput =
                        (StreamConfig.NetworkInputConfig) configuredInput;
                inputs[i] =
                        StreamTaskNetworkInputFactory.create(
                                checkpointedInputGates[networkInput.getInputGateIndex()],
                                networkInput.getTypeSerializer(),
                                ioManager,
                                new StatusWatermarkValve(
                                        checkpointedInputGates[networkInput.getInputGateIndex()]
                                                .getNumberOfInputChannels()),
                                i,
                                inflightDataRescalingDescriptor,
                                gatePartitioners,
                                taskInfo);
            } else if (configuredInput instanceof StreamConfig.SourceInputConfig) {
                StreamConfig.SourceInputConfig sourceInput =
                        (StreamConfig.SourceInputConfig) configuredInput;
                inputs[i] = operatorChain.getSourceTaskInput(sourceInput);
            } else {
                throw new UnsupportedOperationException("Unknown input type: " + configuredInput);
            }
        }

        InputSelectable inputSelectable =
                mainOperator instanceof InputSelectable ? (InputSelectable) mainOperator : null;

        StreamConfig.InputConfig[] inputConfigs = streamConfig.getInputs(userClassloader);
        boolean anyRequiresSorting =
                Arrays.stream(inputConfigs).anyMatch(StreamConfig::requiresSorting);

        if (anyRequiresSorting) {

            if (inputSelectable != null) {
                throw new IllegalStateException(
                        "The InputSelectable interface is not supported with sorting inputs");
            }

            StreamTaskInput[] sortingInputs =
                    IntStream.range(0, inputsCount)
                            .filter(idx -> requiresSorting(inputConfigs[idx]))
                            .mapToObj(idx -> inputs[idx])
                            .toArray(StreamTaskInput[]::new);
            KeySelector[] sortingInputKeySelectors =
                    IntStream.range(0, inputsCount)
                            .filter(idx -> requiresSorting(inputConfigs[idx]))
                            .mapToObj(idx -> streamConfig.getStatePartitioner(idx, userClassloader))
                            .toArray(KeySelector[]::new);
            TypeSerializer[] sortingInputKeySerializers =
                    IntStream.range(0, inputsCount)
                            .filter(idx -> requiresSorting(inputConfigs[idx]))
                            .mapToObj(idx -> streamConfig.getTypeSerializerIn(idx, userClassloader))
                            .toArray(TypeSerializer[]::new);

            StreamTaskInput[] passThroughInputs =
                    IntStream.range(0, inputsCount)
                            .filter(idx -> !requiresSorting(inputConfigs[idx]))
                            .mapToObj(idx -> inputs[idx])
                            .toArray(StreamTaskInput[]::new);

            SelectableSortingInputs selectableSortingInputs =
                    MultiInputSortingDataInput.wrapInputs(
                            ownerTask,
                            sortingInputs,
                            sortingInputKeySelectors,
                            sortingInputKeySerializers,
                            streamConfig.getStateKeySerializer(userClassloader),
                            passThroughInputs,
                            memoryManager,
                            ioManager,
                            executionConfig.isObjectReuseEnabled(),
                            streamConfig.getManagedMemoryFractionOperatorUseCaseOfSlot(
                                    ManagedMemoryUseCase.OPERATOR,
                                    taskManagerConfig,
                                    userClassloader),
                            jobConfig);

            StreamTaskInput<?>[] sortedInputs = selectableSortingInputs.getSortedInputs();
            StreamTaskInput<?>[] passedThroughInputs =
                    selectableSortingInputs.getPassThroughInputs();
            int sortedIndex = 0;
            int passThroughIndex = 0;
            for (int i = 0; i < inputs.length; i++) {
                if (requiresSorting(inputConfigs[i])) {
                    inputs[i] = sortedInputs[sortedIndex];
                    sortedIndex++;
                } else {
                    inputs[i] = passedThroughInputs[passThroughIndex];
                    passThroughIndex++;
                }
            }
            inputSelectable = selectableSortingInputs.getInputSelectable();
        }

        for (int i = 0; i < inputsCount; i++) {
            StreamConfig.InputConfig configuredInput = configuredInputs[i];
            if (configuredInput instanceof StreamConfig.NetworkInputConfig) {
                StreamTaskNetworkOutput dataOutput =
                        new StreamTaskNetworkOutput<>(
                                operatorChain.getFinishedOnRestoreInputOrDefault(
                                        operatorInputs.get(i)),
                                inputWatermarkGauges[i],
                                mainOperatorRecordsIn,
                                networkRecordsIn);

                inputProcessors[i] =
                        new StreamOneInputProcessor(inputs[i], dataOutput, operatorChain);
            } else if (configuredInput instanceof StreamConfig.SourceInputConfig) {
                StreamConfig.SourceInputConfig sourceInput =
                        (StreamConfig.SourceInputConfig) configuredInput;
                WatermarkGaugeExposingOutput<StreamRecord<?>> chainedSourceOutput =
                        operatorChain.getChainedSourceOutput(sourceInput);

                inputProcessors[i] =
                        new StreamOneInputProcessor(
                                inputs[i],
                                new StreamTaskSourceOutput(
                                        chainedSourceOutput, inputWatermarkGauges[i]),
                                operatorChain);
            } else {
                throw new UnsupportedOperationException("Unknown input type: " + configuredInput);
            }
        }

        return new StreamMultipleInputProcessor(
                new MultipleInputSelectionHandler(inputSelectable, inputsCount), inputProcessors);
    }

    /**
     * The network data output implementation used for processing stream elements from {@link
     * StreamTaskNetworkInput} in two input selective processor.
     */
    private static class StreamTaskNetworkOutput<T> implements PushingAsyncDataInput.DataOutput<T> {
        private final Input<T> input;

        private final WatermarkGauge inputWatermarkGauge;

        private final Counter mainOperatorRecordsIn;

        private final Counter networkRecordsIn;

        private StreamTaskNetworkOutput(
                Input<T> input,
                WatermarkGauge inputWatermarkGauge,
                Counter mainOperatorRecordsIn,
                Counter networkRecordsIn) {
            this.input = checkNotNull(input);
            this.inputWatermarkGauge = checkNotNull(inputWatermarkGauge);
            this.mainOperatorRecordsIn = mainOperatorRecordsIn;
            this.networkRecordsIn = networkRecordsIn;
        }

        @Override
        public void emitRecord(StreamRecord<T> record) throws Exception {
            input.setKeyContextElement(record);
            input.processElement(record);
            mainOperatorRecordsIn.inc();
            networkRecordsIn.inc();
        }

        @Override
        public void emitWatermark(Watermark watermark) throws Exception {
            inputWatermarkGauge.setCurrentWatermark(watermark.getTimestamp());
            input.processWatermark(watermark);
        }

        @Override
        public void emitStreamStatus(StreamStatus streamStatus) throws Exception {
            input.processStreamStatus(streamStatus);
        }

        @Override
        public void emitLatencyMarker(LatencyMarker latencyMarker) throws Exception {
            input.processLatencyMarker(latencyMarker);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static class StreamTaskSourceOutput
            extends SourceOperatorStreamTask.AsyncDataOutputToOutput {
        private final WatermarkGaugeExposingOutput<StreamRecord<?>> chainedOutput;

        public StreamTaskSourceOutput(
                WatermarkGaugeExposingOutput<StreamRecord<?>> chainedSourceOutput,
                WatermarkGauge inputWatermarkGauge) {
            super(chainedSourceOutput, new SimpleCounter(), inputWatermarkGauge);
            this.chainedOutput = chainedSourceOutput;
        }

        @Override
        public void emitStreamStatus(StreamStatus streamStatus) {
            chainedOutput.emitStreamStatus(streamStatus);
        }
    }
}
