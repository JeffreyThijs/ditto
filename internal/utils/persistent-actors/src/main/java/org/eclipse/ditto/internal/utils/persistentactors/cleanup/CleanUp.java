/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.internal.utils.persistentactors.cleanup;

import static org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal.LIFECYCLE;
import static org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal.S_ID;
import static org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal.S_SN;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.eclipse.ditto.internal.utils.persistence.mongo.streaming.MongoReadJournal;

import akka.NotUsed;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.javadsl.Source;

/**
 * An Akka stream to handle background cleanup regulated by insert times.
 */
public final class CleanUp {

    private final MongoReadJournal readJournal;
    private final Materializer materializer;
    private final Supplier<Pair<Integer, Integer>> responsibilitySupplier;
    private final int readBatchSize;
    private final int deleteBatchSize;
    private final boolean deleteFinalDeletedSnapshot;

    CleanUp(final MongoReadJournal readJournal,
            final Materializer materializer,
            final Supplier<Pair<Integer, Integer>> responsibilitySupplier,
            final int readBatchSize,
            final int deleteBatchSize,
            final boolean deleteFinalDeletedSnapshot) {

        this.readJournal = readJournal;
        this.materializer = materializer;
        this.responsibilitySupplier = responsibilitySupplier;
        this.readBatchSize = readBatchSize;
        this.deleteBatchSize = deleteBatchSize;
        this.deleteFinalDeletedSnapshot = deleteFinalDeletedSnapshot;
    }

    Source<Source<CleanUpResult, NotUsed>, NotUsed> getCleanUpStream(final String lowerBound) {
        return getSnapshotRevisions(lowerBound).flatMapConcat(sr -> cleanUpEvents(sr).concat(cleanUpSnapshots(sr)));
    }

    private Source<SnapshotRevision, NotUsed> getSnapshotRevisions(final String lowerBound) {
        return readJournal.getNewestSnapshotsAbove(lowerBound, readBatchSize, true, materializer)
                .map(document -> new SnapshotRevision(document.getString(S_ID),
                        document.getLong(S_SN),
                        "DELETED".equals(document.getString(LIFECYCLE))))
                .filter(this::isMyResponsibility);
    }

    private boolean isMyResponsibility(final SnapshotRevision sr) {
        final var responsibility = responsibilitySupplier.get();
        final int denominator = responsibility.second();
        final int remainder = responsibility.first();
        return Math.abs(Math.abs(sr.pid.hashCode()) % denominator) == remainder;
    }

    private Source<Source<CleanUpResult, NotUsed>, NotUsed> cleanUpEvents(final SnapshotRevision sr) {
        // leave 1 event for each snapshot to store the "always alive" tag
        return readJournal.getSmallestEventSeqNo(sr.pid).flatMapConcat(minSnOpt -> {
            if (minSnOpt.isEmpty() || minSnOpt.orElseThrow() >= sr.sn) {
                return Source.empty();
            } else {
                final List<Long> upperBounds = getSnUpperBoundsPerBatch(minSnOpt.orElseThrow(), sr.sn);
                return Source.from(upperBounds).map(upperBound -> Source.lazily(() ->
                        readJournal.deleteEvents(sr.pid, upperBound - deleteBatchSize + 1, upperBound)
                                .map(result -> new CleanUpResult(CleanUpResult.Type.EVENTS, sr, result))
                ).mapMaterializedValue(ignored -> NotUsed.getInstance()));
            }
        });
    }

    private Source<Source<CleanUpResult, NotUsed>, NotUsed> cleanUpSnapshots(final SnapshotRevision sr) {
        return readJournal.getSmallestSnapshotSeqNo(sr.pid).flatMapConcat(minSnOpt -> {
            if (minSnOpt.isEmpty() || (minSnOpt.orElseThrow() >= sr.sn && !deleteFinalDeletedSnapshot)) {
                return Source.empty();
            } else {
                final long maxSnToDelete = deleteFinalDeletedSnapshot && sr.isDeleted ? sr.sn + 1 : sr.sn;
                final List<Long> upperBounds = getSnUpperBoundsPerBatch(minSnOpt.orElseThrow(), maxSnToDelete);
                return Source.from(upperBounds).map(upperBound -> Source.lazily(() ->
                        readJournal.deleteSnapshots(sr.pid, upperBound - deleteBatchSize + 1, upperBound)
                                .map(result -> new CleanUpResult(CleanUpResult.Type.SNAPSHOTS, sr, result))
                ).mapMaterializedValue(ignored -> NotUsed.getInstance()));
            }
        });
    }

    private List<Long> getSnUpperBoundsPerBatch(final long minSn, final long snapshotRevisionSn) {
        final long difference = snapshotRevisionSn - minSn;
        // number of batches = ceil(difference / deleteBatchSize) as real numbers
        final long batches = (difference / deleteBatchSize) + (difference % deleteBatchSize == 0L ? 0L : 1L);
        final long firstBatchSn = snapshotRevisionSn - 1 - ((batches - 1) * deleteBatchSize);
        return LongStream.range(0, batches)
                .mapToObj(multiplier -> firstBatchSn + multiplier * deleteBatchSize)
                .collect(Collectors.toList());
    }
}
