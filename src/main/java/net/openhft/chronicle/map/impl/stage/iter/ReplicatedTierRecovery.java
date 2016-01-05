/*
 *      Copyright (C) 2015  higherfrequencytrading.com
 *
 *      This program is free software: you can redistribute it and/or modify
 *      it under the terms of the GNU Lesser General Public License as published by
 *      the Free Software Foundation, either version 3 of the License.
 *
 *      This program is distributed in the hope that it will be useful,
 *      but WITHOUT ANY WARRANTY; without even the implied warranty of
 *      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *      GNU Lesser General Public License for more details.
 *
 *      You should have received a copy of the GNU Lesser General Public License
 *      along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.map.impl.stage.iter;

import net.openhft.chronicle.algo.bitset.ReusableBitSet;
import net.openhft.chronicle.hash.impl.CompactOffHeapLinearHashTable;
import net.openhft.chronicle.hash.impl.VanillaChronicleHash;
import net.openhft.chronicle.hash.impl.stage.entry.SegmentStages;
import net.openhft.chronicle.hash.impl.stage.hash.LogHolder;
import net.openhft.chronicle.hash.impl.stage.iter.TierRecovery;
import net.openhft.chronicle.map.ReplicatedChronicleMap;
import net.openhft.chronicle.map.impl.ReplicatedChronicleMapHolder;
import net.openhft.chronicle.map.impl.stage.entry.ReplicatedMapEntryStages;
import net.openhft.sg.StageRef;
import net.openhft.sg.Staged;

@Staged
public class ReplicatedTierRecovery extends TierRecovery {

    @StageRef ReplicatedChronicleMapHolder<?, ?, ?> rh;
    @StageRef SegmentStages s;
    @StageRef ReplicatedMapEntryStages<?, ?> e;
    @StageRef LogHolder lh;

    @Override
    public void removeDuplicatesInSegment() {
        super.removeDuplicatesInSegment();
        recoverTierDeleted();
        cleanupModificationIterationBits();
    }

    private void recoverTierDeleted() {
        VanillaChronicleHash<?, ?, ?, ?> h = rh.h();
        CompactOffHeapLinearHashTable hl = h.hashLookup;
        long hlAddr = s.tierBaseAddr;

        long deleted = 0;
        long hlPos = 0;
        do {
            long hlEntry = hl.readEntry(hlAddr, hlPos);
            if (!hl.empty(hlEntry)) {
                e.readExistingEntry(hl.value(hlEntry));
                if (e.entryDeleted()) {
                    deleted++;
                }
            }
            hlPos = hl.step(hlPos);
        } while (hlPos != 0);
        if (s.tierDeleted() != deleted) {
            lh.LOG.error("wrong deleted counter for tier with index {}, stored: {}, should be: {}",
                    s.tierIndex, s.tierDeleted(), deleted);
            s.tierDeleted(deleted);
        }
    }

    private void cleanupModificationIterationBits() {
        ReplicatedChronicleMap<?, ?, ?> m = rh.m();
        ReplicatedChronicleMap<?, ?, ?>.ModificationIterator[] its =
                m.acquireAllModificationIterators();
        ReusableBitSet freeList = s.freeList;
        for (long pos = 0; pos < m.actualChunksPerSegmentTier;) {
            long nextPos = freeList.nextSetBit(pos);
            if (nextPos > pos) {
                for (ReplicatedChronicleMap<?, ?, ?>.ModificationIterator it : its) {
                    it.clearRange0(s.tierIndex, pos, nextPos);
                }
            }
            if (nextPos > 0) {
                e.readExistingEntry(nextPos);
                if (e.entrySizeInChunks > 1) {
                    for (ReplicatedChronicleMap<?, ?, ?>.ModificationIterator it : its) {
                        it.clearRange0(s.tierIndex, nextPos + 1, nextPos + e.entrySizeInChunks);
                    }
                }
                pos = nextPos + e.entrySizeInChunks;
            } else {
                for (ReplicatedChronicleMap<?, ?, ?>.ModificationIterator it : its) {
                    it.clearRange0(s.tierIndex, pos, m.actualChunksPerSegmentTier);
                }
                break;
            }
        }
    }
}
