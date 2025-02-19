/*
 * Copyright (C) 2024 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.blocks.impl;

import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.blocks.BlockStreamManager.ZERO_BLOCK_HASH;
import static com.hedera.node.app.blocks.BlockStreamService.FAKE_RESTART_BLOCK_HASH;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.appendHash;
import static com.hedera.node.app.blocks.impl.BlockImplUtils.combine;
import static com.hedera.node.app.blocks.schemas.V0540BlockStreamSchema.BLOCK_STREAM_INFO_KEY;
import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.hedera.node.app.hapi.utils.CommonUtils.noThrowSha384HashOf;
import static com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema.PLATFORM_STATE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.RecordFileItem;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.block.stream.output.TransactionResult;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockstream.BlockStreamInfo;
import com.hedera.hapi.platform.event.EventTransaction;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockItemWriter;
import com.hedera.node.app.blocks.BlockStreamService;
import com.hedera.node.app.tss.TssBaseService;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.system.Round;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockStreamManagerImplTest {
    private static final SemanticVersion CREATION_VERSION = new SemanticVersion(1, 2, 3, "alpha.1", "2");
    private static final long ROUND_NO = 123L;
    private static final long N_MINUS_2_BLOCK_NO = 664L;
    private static final long N_MINUS_1_BLOCK_NO = 665L;
    private static final long N_BLOCK_NO = 666L;
    private static final Instant CONSENSUS_NOW = Instant.ofEpochSecond(1_234_567L);
    private static final Bytes N_MINUS_2_BLOCK_HASH = Bytes.wrap(noThrowSha384HashOf(new byte[] {(byte) 0xAA}));
    private static final Bytes FIRST_FAKE_SIGNATURE = Bytes.fromHex("ff".repeat(48));
    private static final Bytes SECOND_FAKE_SIGNATURE = Bytes.fromHex("ee".repeat(48));
    private static final BlockItem FAKE_EVENT_TRANSACTION =
            BlockItem.newBuilder().eventTransaction(EventTransaction.DEFAULT).build();
    private static final BlockItem FAKE_TRANSACTION_RESULT =
            BlockItem.newBuilder().transactionResult(TransactionResult.DEFAULT).build();
    private static final Bytes FAKE_RESULT_HASH = noThrowSha384HashOfItem(FAKE_TRANSACTION_RESULT);
    private static final BlockItem FAKE_STATE_CHANGES =
            BlockItem.newBuilder().stateChanges(StateChanges.DEFAULT).build();
    private static final BlockItem FAKE_RECORD_FILE_ITEM =
            BlockItem.newBuilder().recordFile(RecordFileItem.DEFAULT).build();

    @Mock
    private TssBaseService tssBaseService;

    @Mock
    private ConfigProvider configProvider;

    @Mock
    private BoundaryStateChangeListener boundaryStateChangeListener;

    @Mock
    private BlockItemWriter aWriter;

    @Mock
    private BlockItemWriter bWriter;

    @Mock
    private ReadableStates readableStates;

    private WritableStates writableStates;

    @Mock
    private Round round;

    @Mock
    private State state;

    private final AtomicReference<Bytes> lastAItem = new AtomicReference<>();
    private final AtomicReference<Bytes> lastBItem = new AtomicReference<>();
    private final AtomicReference<PlatformState> stateRef = new AtomicReference<>();
    private final AtomicReference<BlockStreamInfo> infoRef = new AtomicReference<>();

    private WritableSingletonStateBase<BlockStreamInfo> blockStreamInfoState;

    private BlockStreamManagerImpl subject;

    @BeforeEach
    void setUp() {
        writableStates = mock(WritableStates.class, withSettings().extraInterfaces(CommittableWritableStates.class));
    }

    @Test
    void requiresLastHashToBeInitialized() {
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(DEFAULT_CONFIG, 1));
        subject = new BlockStreamManagerImpl(
                () -> aWriter, ForkJoinPool.commonPool(), configProvider, tssBaseService, boundaryStateChangeListener);
        assertThrows(IllegalStateException.class, () -> subject.startRound(round, state));
    }

    @Test
    void startsAndEndsBlockWithSingleRoundPerBlockAsExpected() throws ParseException {
        givenSubjectWith(
                1,
                blockStreamInfoWith(N_MINUS_1_BLOCK_NO, N_MINUS_2_BLOCK_HASH, Bytes.EMPTY),
                platformStateWith(null),
                aWriter);
        givenEndOfRoundSetup();
        final ArgumentCaptor<byte[]> blockHashCaptor = ArgumentCaptor.forClass(byte[].class);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        verify(boundaryStateChangeListener).setLastUsedConsensusTime(CONSENSUS_NOW);
        verify(aWriter).openBlock(N_BLOCK_NO);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertNull(subject.prngSeed());
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // End the round
        subject.endRound(state, ROUND_NO);

        // Assert the internal state of the subject has changed as expected and the writer has been closed
        final var expectedBlockInfo = new BlockStreamInfo(
                N_BLOCK_NO,
                asTimestamp(CONSENSUS_NOW),
                appendHash(combine(ZERO_BLOCK_HASH, FAKE_RESULT_HASH), appendHash(ZERO_BLOCK_HASH, Bytes.EMPTY, 4), 4),
                appendHash(FAKE_RESTART_BLOCK_HASH, appendHash(N_MINUS_2_BLOCK_HASH, Bytes.EMPTY, 256), 256));
        final var actualBlockInfo = infoRef.get();
        assertEquals(expectedBlockInfo, actualBlockInfo);
        verify(tssBaseService).requestLedgerSignature(blockHashCaptor.capture());

        // Provide the ledger signature to the subject
        subject.accept(blockHashCaptor.getValue(), FIRST_FAKE_SIGNATURE.toByteArray());

        // Assert the block proof was written
        final var proofItem = lastAItem.get();
        assertNotNull(proofItem);
        final var item = BlockItem.PROTOBUF.parse(proofItem);
        assertTrue(item.hasBlockProof());
        final var proof = item.blockProofOrThrow();
        assertEquals(N_BLOCK_NO, proof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, proof.blockSignature());
    }

    @Test
    void doesNotEndBlockWithMultipleRoundPerBlockIfNotModZero() {
        givenSubjectWith(
                7,
                blockStreamInfoWith(N_MINUS_1_BLOCK_NO, N_MINUS_2_BLOCK_HASH, Bytes.EMPTY),
                platformStateWith(null),
                aWriter);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        verify(boundaryStateChangeListener).setLastUsedConsensusTime(CONSENSUS_NOW);
        verify(aWriter).openBlock(N_BLOCK_NO);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);

        // End the round
        subject.endRound(state, ROUND_NO);

        // Assert the internal state of the subject has changed as expected and the writer has been closed
        verify(tssBaseService, never()).requestLedgerSignature(any());
    }

    @Test
    void alwaysEndsBlockOnFreezeRoundPerBlockAsExpected() throws ParseException {
        final var resultHashes = Bytes.fromHex("aa".repeat(48) + "bb".repeat(48) + "cc".repeat(48) + "dd".repeat(48));
        givenSubjectWith(
                7,
                blockStreamInfoWith(N_MINUS_1_BLOCK_NO, N_MINUS_2_BLOCK_HASH, resultHashes),
                platformStateWith(CONSENSUS_NOW.minusSeconds(1)),
                aWriter);
        givenEndOfRoundSetup();
        given(round.getRoundNum()).willReturn(ROUND_NO);
        final ArgumentCaptor<byte[]> blockHashCaptor = ArgumentCaptor.forClass(byte[].class);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);

        // Assert the internal state of the subject has changed as expected and the writer has been opened
        verify(boundaryStateChangeListener).setLastUsedConsensusTime(CONSENSUS_NOW);
        verify(aWriter).openBlock(N_BLOCK_NO);
        assertEquals(N_MINUS_2_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_2_BLOCK_NO));
        assertEquals(FAKE_RESTART_BLOCK_HASH, subject.blockHashByBlockNumber(N_MINUS_1_BLOCK_NO));
        assertEquals(N_BLOCK_NO, subject.blockNo());

        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        assertEquals(Bytes.fromHex("aa".repeat(48)), subject.prngSeed());
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        assertEquals(Bytes.fromHex("bb".repeat(48)), subject.prngSeed());
        subject.writeItem(FAKE_STATE_CHANGES);
        for (int i = 0; i < 8; i++) {
            subject.writeItem(FAKE_RECORD_FILE_ITEM);
        }

        // End the round
        subject.endRound(state, ROUND_NO);

        // Assert the internal state of the subject has changed as expected and the writer has been closed
        final var expectedBlockInfo = new BlockStreamInfo(
                N_BLOCK_NO,
                asTimestamp(CONSENSUS_NOW),
                appendHash(combine(Bytes.fromHex("dd".repeat(48)), FAKE_RESULT_HASH), resultHashes, 4),
                appendHash(FAKE_RESTART_BLOCK_HASH, appendHash(N_MINUS_2_BLOCK_HASH, Bytes.EMPTY, 256), 256));
        final var actualBlockInfo = infoRef.get();
        assertEquals(expectedBlockInfo, actualBlockInfo);
        verify(tssBaseService).requestLedgerSignature(blockHashCaptor.capture());

        // Provide the ledger signature to the subject
        subject.accept(blockHashCaptor.getValue(), FIRST_FAKE_SIGNATURE.toByteArray());

        // Assert the block proof was written
        final var proofItem = lastAItem.get();
        assertNotNull(proofItem);
        final var item = BlockItem.PROTOBUF.parse(proofItem);
        assertTrue(item.hasBlockProof());
        final var proof = item.blockProofOrThrow();
        assertEquals(N_BLOCK_NO, proof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, proof.blockSignature());
    }

    @Test
    void supportsMultiplePendingBlocksWithIndirectProofAsExpected() throws ParseException {
        givenSubjectWith(
                1,
                blockStreamInfoWith(N_MINUS_1_BLOCK_NO, N_MINUS_2_BLOCK_HASH, Bytes.EMPTY),
                platformStateWith(null),
                aWriter,
                bWriter);
        givenEndOfRoundSetup();
        doAnswer(invocationOnMock -> {
                    lastBItem.set(invocationOnMock.getArgument(0));
                    return bWriter;
                })
                .when(bWriter)
                .writeItem(any());
        final ArgumentCaptor<byte[]> blockHashCaptor = ArgumentCaptor.forClass(byte[].class);

        // Initialize the last (N-1) block hash
        subject.initLastBlockHash(FAKE_RESTART_BLOCK_HASH);

        // Start the round that will be block N
        subject.startRound(round, state);
        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);
        // End the round in block N
        subject.endRound(state, ROUND_NO);

        // Start the round that will be block N+1
        subject.startRound(round, state);
        // Write some items to the block
        subject.writeItem(FAKE_EVENT_TRANSACTION);
        subject.writeItem(FAKE_TRANSACTION_RESULT);
        subject.writeItem(FAKE_STATE_CHANGES);
        subject.writeItem(FAKE_RECORD_FILE_ITEM);
        // End the round in block N+1
        subject.endRound(state, ROUND_NO + 1);

        verify(tssBaseService, times(2)).requestLedgerSignature(blockHashCaptor.capture());
        final var allBlockHashes = blockHashCaptor.getAllValues();
        assertEquals(2, allBlockHashes.size());

        // Provide the N+1 ledger signature to the subject first
        subject.accept(allBlockHashes.getLast(), FIRST_FAKE_SIGNATURE.toByteArray());
        subject.accept(allBlockHashes.getFirst(), SECOND_FAKE_SIGNATURE.toByteArray());

        // Assert both block proofs were written, but with the proof for N using an indirect proof
        final var aProofItem = lastAItem.get();
        assertNotNull(aProofItem);
        final var aItem = BlockItem.PROTOBUF.parse(aProofItem);
        assertTrue(aItem.hasBlockProof());
        final var aProof = aItem.blockProofOrThrow();
        assertEquals(N_BLOCK_NO, aProof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, aProof.blockSignature());
        assertEquals(2, aProof.siblingHashes().size());
        // And the proof for N+1 using a direct proof
        final var bProofItem = lastBItem.get();
        assertNotNull(bProofItem);
        final var bItem = BlockItem.PROTOBUF.parse(bProofItem);
        assertTrue(bItem.hasBlockProof());
        final var bProof = bItem.blockProofOrThrow();
        assertEquals(N_BLOCK_NO + 1, bProof.block());
        assertEquals(FIRST_FAKE_SIGNATURE, bProof.blockSignature());
        assertTrue(bProof.siblingHashes().isEmpty());
    }

    private void givenSubjectWith(
            final int roundsPerBlock,
            @NonNull final BlockStreamInfo blockStreamInfo,
            @NonNull final PlatformState platformState,
            @NonNull final BlockItemWriter... writers) {
        given(round.getConsensusTimestamp()).willReturn(CONSENSUS_NOW);
        final AtomicInteger nextWriter = new AtomicInteger(0);
        final var config = HederaTestConfigBuilder.create()
                .withValue("blockStream.roundsPerBlock", roundsPerBlock)
                .getOrCreateConfig();
        given(configProvider.getConfiguration()).willReturn(new VersionedConfigImpl(config, 1L));
        subject = new BlockStreamManagerImpl(
                () -> writers[nextWriter.getAndIncrement()],
                ForkJoinPool.commonPool(),
                configProvider,
                tssBaseService,
                boundaryStateChangeListener);
        subject.appendRealHashes();
        given(state.getReadableStates(BlockStreamService.NAME)).willReturn(readableStates);
        given(state.getReadableStates(PlatformStateService.NAME)).willReturn(readableStates);
        infoRef.set(blockStreamInfo);
        stateRef.set(platformState);
        blockStreamInfoState = new WritableSingletonStateBase<>(BLOCK_STREAM_INFO_KEY, infoRef::get, infoRef::set);
        given(readableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_KEY))
                .willReturn(blockStreamInfoState);
        given(readableStates.<PlatformState>getSingleton(PLATFORM_STATE_KEY))
                .willReturn(new WritableSingletonStateBase<>(PLATFORM_STATE_KEY, stateRef::get, stateRef::set));
    }

    private void givenEndOfRoundSetup() {
        given(boundaryStateChangeListener.flushChanges()).willReturn(FAKE_STATE_CHANGES);
        doAnswer(invocationOnMock -> {
                    lastAItem.set(invocationOnMock.getArgument(0));
                    return aWriter;
                })
                .when(aWriter)
                .writeItem(any());
        given(state.getWritableStates(BlockStreamService.NAME)).willReturn(writableStates);
        given(writableStates.<BlockStreamInfo>getSingleton(BLOCK_STREAM_INFO_KEY))
                .willReturn(blockStreamInfoState);
        doAnswer(invocationOnMock -> {
                    blockStreamInfoState.commit();
                    return null;
                })
                .when((CommittableWritableStates) writableStates)
                .commit();
    }

    private BlockStreamInfo blockStreamInfoWith(
            final long blockNumber, @NonNull final Bytes nMinus2Hash, @NonNull final Bytes resultHashes) {
        return BlockStreamInfo.newBuilder()
                .blockNumber(blockNumber)
                .trailingBlockHashes(appendHash(nMinus2Hash, Bytes.EMPTY, 256))
                .trailingOutputHashes(resultHashes)
                .build();
    }

    private PlatformState platformStateWith(@Nullable final Instant freezeTime) {
        return PlatformState.newBuilder()
                .creationSoftwareVersion(CREATION_VERSION)
                .freezeTime(freezeTime == null ? null : asTimestamp(freezeTime))
                .build();
    }

    private static Bytes noThrowSha384HashOfItem(@NonNull final BlockItem item) {
        return Bytes.wrap(noThrowSha384HashOf(BlockItem.PROTOBUF.toBytes(item).toByteArray()));
    }
}
