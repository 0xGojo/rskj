/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.db;

import co.rsk.core.BlockDifficulty;
import co.rsk.crypto.Keccak256;
import co.rsk.metrics.profilers.Metric;
import co.rsk.metrics.profilers.Profiler;
import co.rsk.metrics.profilers.ProfilerFactory;
import co.rsk.net.BlockCache;
import co.rsk.remasc.Sibling;
import co.rsk.util.MaxSizeHashMap;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.core.Block;
import org.ethereum.core.BlockFactory;
import org.ethereum.core.BlockHeader;
import org.ethereum.datasource.KeyValueDataSource;
import org.mapdb.DB;
import org.mapdb.DataIO;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static co.rsk.core.BlockDifficulty.ZERO;

public class IndexedBlockStore implements BlockStore {

    private static final Logger logger = LoggerFactory.getLogger("general");
    private static final Profiler profiler = ProfilerFactory.getInstance();

    private final BlockCache blockCache;
    private final MaxSizeHashMap<Keccak256, Map<Long, List<Sibling>>> remascCache;

    private final Map<Long, List<BlockInfo>> index;
    private final DB indexDB;
    private final KeyValueDataSource blocks;
    private final BlockFactory blockFactory;

    public IndexedBlockStore(
            BlockFactory blockFactory,
            Map<Long, List<BlockInfo>> index,
            KeyValueDataSource blocks,
            DB indexDB) {
        this.index = index;
        this.blocks = blocks;
        this.indexDB  = indexDB;
        this.blockFactory = blockFactory;
        //TODO(lsebrie): move these maps creation outside blockstore,
        // remascCache should be an external component and not be inside blockstore
        this.blockCache = new BlockCache(5000);
        this.remascCache = new MaxSizeHashMap<>(50000, true);
    }

    @Override
    public synchronized void removeBlock(Block block) {
        this.blockCache.removeBlock(block);
        this.remascCache.remove(block.getHash());
        this.blocks.delete(block.getHash().getBytes());

        List<BlockInfo> binfos = this.index.get(block.getNumber());

        if (binfos == null) {
            return;
        }

        List<BlockInfo> toremove = new ArrayList<>();

        for (BlockInfo binfo : binfos) {
            if (binfo.getHash().equals(block.getHash())) {
                toremove.add(binfo);
            }
        }

        binfos.removeAll(toremove);
    }

    @Override
    public synchronized Block getBestBlock() {
        Long maxLevel = getMaxNumber();
        if (maxLevel < 0) {
            return null;
        }

        Block bestBlock = getChainBlockByNumber(maxLevel);
        if (bestBlock != null) {
            return  bestBlock;
        }

        // That scenario can happen
        // if there is a fork branch that is
        // higher than main branch but has
        // less TD than the main branch TD
        while (bestBlock == null && maxLevel >= 0) {
            --maxLevel;
            bestBlock = getChainBlockByNumber(maxLevel);
        }

        return bestBlock;
    }

    @Override
    public byte[] getBlockHashByNumber(long blockNumber, byte[] branchBlockHash) {
        Block branchBlock = getBlockByHash(branchBlockHash);
        if (branchBlock.getNumber() < blockNumber) {
            throw new IllegalArgumentException("Requested block number > branch hash number: " + blockNumber + " < " + branchBlock.getNumber());
        }
        while(branchBlock.getNumber() > blockNumber) {
            branchBlock = getBlockByHash(branchBlock.getParentHash().getBytes());
        }
        return branchBlock.getHash().getBytes();
    }

    @Override
    public Block getBlockByHashAndDepth(byte[] hash, long depth) {
        Block block = this.getBlockByHash(hash);

        for (long i = 0; i < depth; i++) {
            block = this.getBlockByHash(block.getParentHash().getBytes());
        }

        return block;
    }

    @Override
    // This method is an optimized way to traverse a branch in search for a block at a given depth. Starting at a given
    // block (by hash) it tries to find the first block that is part of the best chain, when it finds one we now that
    // we can jump to the block that is at the remaining depth. If not block is found then it continues traversing the
    // branch from parent to parent. The search is limited by the maximum depth received as parameter.
    // This method either needs to traverse the parent chain or if a block in the parent chain is part of the best chain
    // then it can skip the traversal by going directly to the block at the remaining depth.
    public Block getBlockAtDepthStartingAt(long depth, byte[] hash) {
        Block start = this.getBlockByHash(hash);

        if (start != null && depth == 0) {
            return start;
        }

        if (start == null || start.getNumber() <= depth) {
            return null;
        }

        Block block = start;

        for (long i = 0; i < depth; i++) {
            if (isBlockInMainChain(block.getNumber(), block.getHash())) {
                return getChainBlockByNumber(start.getNumber() - depth);
            }

            block = this.getBlockByHash(block.getParentHash().getBytes());
        }

        return block;
    }

    public boolean isBlockInMainChain(long blockNumber, Keccak256 blockHash){
        List<BlockInfo> blockInfos = index.get(blockNumber);
        if (blockInfos == null) {
            return false;
        }

        for (BlockInfo blockInfo : blockInfos) {
            if (blockInfo.isMainChain() && blockHash.equals(blockInfo.getHash())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public synchronized void flush() {
        Metric metric = profiler.start(Profiler.PROFILING_TYPE.DB_WRITE);
        
        //long t1 = System.nanoTime();
        if (indexDB != null) {
            indexDB.commit();
        }
        //long t2 = System.nanoTime(); logger.info("Flush block store in: {} ms", ((float)(t2 - t1) / 1_000_000));
        profiler.stop(metric);
    }

    @Override
    public synchronized void saveBlock(Block block, BlockDifficulty cummDifficulty, boolean mainChain) {
        List<BlockInfo> blockInfos = index.get(block.getNumber());
        if (blockInfos == null) {
            blockInfos = new ArrayList<>();
        }

        BlockInfo blockInfo = null;
        for (BlockInfo bi : blockInfos) {
            if (bi.getHash().equals(block.getHash())) {
                blockInfo = bi;
            } else if (mainChain) {
                bi.setMainChain(false);
            }
        }
        if (blockInfo == null) {
            blockInfo = new BlockInfo();
            blockInfos.add(blockInfo);
        }

        blockInfo.setCummDifficulty(cummDifficulty);
        blockInfo.setHash(block.getHash().getBytes());
        blockInfo.setMainChain(mainChain);

        if (blocks.get(block.getHash().getBytes()) == null) {
            blocks.put(block.getHash().getBytes(), block.getEncoded());
        }
        index.put(block.getNumber(), blockInfos);
        blockCache.addBlock(block);
        remascCache.put(block.getHash(), getSiblingsFromBlock(block));
    }

    @Override
    public synchronized List<BlockInformation> getBlocksInformationByNumber(long number) {
        List<BlockInformation> result = new ArrayList<>();

        List<BlockInfo> blockInfos = index.get(number);

        if (blockInfos == null) {
            return result;
        }

        for (BlockInfo blockInfo : blockInfos) {
            byte[] hash = blockInfo.getHash().copy().getBytes();
            BlockDifficulty totalDifficulty = blockInfo.getCummDifficulty();
            boolean isInBlockChain = blockInfo.isMainChain();

            result.add(new BlockInformation(hash, totalDifficulty, isInBlockChain));
        }

        return result;
    }

    @Override
    public synchronized Block getChainBlockByNumber(long number){
        List<BlockInfo> blockInfos = index.get(number);
        if (blockInfos == null) {
            return null;
        }

        for (BlockInfo blockInfo : blockInfos) {

            if (blockInfo.isMainChain()) {

                byte[] hash = blockInfo.getHash().getBytes();
                return getBlockByHash(hash);
            }
        }

        return null;
    }

    @Override
    public synchronized Block getBlockByHash(byte[] hash) {

        Block block = getBlock(hash);
        if (block == null) {
            return null;
        }

        blockCache.addBlock(block);
        remascCache.put(block.getHash(), getSiblingsFromBlock(block));
        return block;
    }

    private synchronized Block getBlock(byte[] hash) {
        Block block = this.blockCache.getBlockByHash(hash);

        if (block != null) {
            return block;
        }

        byte[] blockRlp = blocks.get(hash);
        if (blockRlp == null) {
            return null;
        }

        return blockFactory.decodeBlock(blockRlp);
    }

    @Override
    public synchronized Map<Long, List<Sibling>> getSiblingsFromBlockByHash(Keccak256 hash) {
        return this.remascCache.computeIfAbsent(hash, key -> getSiblingsFromBlock(getBlock(key.getBytes())));
    }

    @Override
    public synchronized boolean isBlockExist(byte[] hash) {
        return getBlockByHash(hash) != null;
    }

    @Override
    public synchronized BlockDifficulty getTotalDifficultyForHash(byte[] hash){
        Block block = this.getBlockByHash(hash);
        if (block == null) {
            return ZERO;
        }

        Long level  =  block.getNumber();
        List<BlockInfo> blockInfos =  index.get(level);

        if (blockInfos == null) {
            return ZERO;
        }

        for (BlockInfo blockInfo : blockInfos) {
            if (Arrays.equals(blockInfo.getHash().getBytes(), hash)) {
                return blockInfo.getCummDifficulty();
            }
        }

        return ZERO;
    }

    @Override
    public synchronized long getMaxNumber() {
        return (long)index.size() - 1L;
    }

    @Override
    public synchronized List<byte[]> getListHashesEndWith(byte[] hash, long number){

        List<Block> blocks = getListBlocksEndWith(hash, number);
        List<byte[]> hashes = new ArrayList<>(blocks.size());

        for (Block b : blocks) {
            hashes.add(b.getHash().getBytes());
        }

        return hashes;
    }

    private synchronized List<Block> getListBlocksEndWith(byte[] hash, long qty) {
        Block block = getBlockByHash(hash);

        if (block == null) {
            return new ArrayList<>();
        }

        List<Block> blocks = new ArrayList<>((int) qty);

        for (int i = 0; i < qty; ++i) {

            blocks.add(block);
            block = getBlockByHash(hash);
            if (block == null) {
                break;
            }
        }

        return blocks;
    }

    @Override
    public synchronized void reBranch(Block forkBlock){

        Block bestBlock = getBestBlock();
        long maxLevel = Math.max(bestBlock.getNumber(), forkBlock.getNumber());

        // 1. First ensure that you are on the save level
        long currentLevel = maxLevel;
        Block forkLine = forkBlock;

        if (forkBlock.getNumber() > bestBlock.getNumber()) {

            while(currentLevel > bestBlock.getNumber()) {
                List<BlockInfo> blocks = index.get(currentLevel);
                BlockInfo blockInfo = getBlockInfoForHash(blocks, forkLine.getHash().getBytes());
                if (blockInfo != null) {
                    blockInfo.setMainChain(true);
                    if (index.containsKey(currentLevel)) {
                        index.put(currentLevel, blocks);
                    }
                }
                forkLine = getBlockByHash(forkLine.getParentHash().getBytes());
                --currentLevel;
            }
        }

        Block bestLine = bestBlock;
        if (bestBlock.getNumber() > forkBlock.getNumber()){

            while(currentLevel > forkBlock.getNumber()) {
                List<BlockInfo> blocks =  index.get(currentLevel);
                BlockInfo blockInfo = getBlockInfoForHash(blocks, bestLine.getHash().getBytes());
                if (blockInfo != null) {
                    blockInfo.setMainChain(false);
                    if (index.containsKey(currentLevel)) {
                        index.put(currentLevel, blocks);
                    }
                }
                bestLine = getBlockByHash(bestLine.getParentHash().getBytes());
                --currentLevel;
            }
        }

        // 2. Loop back on each level until common block
        while( !bestLine.isEqual(forkLine) ) {

            List<BlockInfo> levelBlocks = index.get(currentLevel);
            BlockInfo bestInfo = getBlockInfoForHash(levelBlocks, bestLine.getHash().getBytes());
            if (bestInfo != null) {
                bestInfo.setMainChain(false);
                if (index.containsKey(currentLevel)) {
                    index.put(currentLevel, levelBlocks);
                }
            }

            BlockInfo forkInfo = getBlockInfoForHash(levelBlocks, forkLine.getHash().getBytes());
            if (forkInfo != null) {
                forkInfo.setMainChain(true);
                if (index.containsKey(currentLevel)) {
                    index.put(currentLevel, levelBlocks);
                }
            }

            bestLine = getBlockByHash(bestLine.getParentHash().getBytes());
            forkLine = getBlockByHash(forkLine.getParentHash().getBytes());

            --currentLevel;
        }
    }

    @VisibleForTesting
    public synchronized List<byte[]> getListHashesStartWith(long number, long maxBlocks) {

        List<byte[]> result = new ArrayList<>();

        int i;
        for (i = 0; i < maxBlocks; ++i) {
            List<BlockInfo> blockInfos =  index.get(number);
            if (blockInfos == null) {
                break;
            }

            for (BlockInfo blockInfo : blockInfos) {
                if (blockInfo.isMainChain()) {
                    result.add(blockInfo.getHash().getBytes());
                    break;
                }
            }

            ++number;
        }

        return result;
    }

    public static class BlockInfo implements Serializable {
        private static final long serialVersionUID = 5906746360128478753L;

        private byte[] hash;
        private BigInteger cummDifficulty;
        private boolean mainChain;

        public Keccak256 getHash() {
            return new Keccak256(hash);
        }

        private void setHash(byte[] hash) {
            this.hash = hash;
        }

        private BlockDifficulty getCummDifficulty() {
            return new BlockDifficulty(cummDifficulty);
        }

        private void setCummDifficulty(BlockDifficulty cummDifficulty) {
            this.cummDifficulty = cummDifficulty.asBigInteger();
        }

        private boolean isMainChain() {
            return mainChain;
        }

        private void setMainChain(boolean mainChain) {
            this.mainChain = mainChain;
        }
    }


    public static final Serializer<List<BlockInfo>> BLOCK_INFO_SERIALIZER = new Serializer<List<BlockInfo>>(){

        @Override
        public void serialize(DataOutput out, List<BlockInfo> value) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);
            oos.writeObject(value);

            byte[] data = bos.toByteArray();
            DataIO.packInt(out, data.length);
            out.write(data);
        }

        @Override
        public List<BlockInfo> deserialize(DataInput in, int available) throws IOException {

            List<BlockInfo> value = null;
            try {
                int size = DataIO.unpackInt(in);
                byte[] data = new byte[size];
                in.readFully(data);

                ByteArrayInputStream bis = new ByteArrayInputStream(data, 0, data.length);
                ObjectInputStream ois = new ObjectInputStream(bis);
                value = (List<BlockInfo>)ois.readObject();
            } catch (ClassNotFoundException e) {
                logger.error("Class not found", e);
            }

            return value;
        }
    };

    private static BlockInfo getBlockInfoForHash(List<BlockInfo> blocks, byte[] hash){
        if (blocks == null) {
            return null;
        }

        for (BlockInfo blockInfo : blocks) {
            if (Arrays.equals(hash, blockInfo.getHash().getBytes())) {
                return blockInfo;
            }
        }

        return null;
    }

    @Override
    public synchronized List<Block> getChainBlocksByNumber(long number){
        List<Block> result = new ArrayList<>();

        List<BlockInfo> blockInfos = index.get(number);

        if (blockInfos == null){
            return result;
        }

        for (BlockInfo blockInfo : blockInfos){

            byte[] hash = blockInfo.getHash().getBytes();
            Block block = getBlockByHash(hash);

            // TODO(mc) investigate and fix this, probably a cache invalidation problem
            if (block != null) {
                result.add(block);
            }
        }

        return result;
    }

    /**
     * Deletes from disk storage all blocks with number strictly larger than blockNumber.
     * Note that this doesn't clean the caches, making it unsuitable for using after initialization.
     */
    public void rewind(long blockNumber) {
        long maxNumber = getMaxNumber();
        for (long i = maxNumber; i > blockNumber; i--) {
            List<BlockInfo> blockInfos = index.remove(i);
            if (blockInfos == null) {
                continue;
            }

            for (BlockInfo blockInfo : blockInfos) {
                this.blocks.delete(blockInfo.getHash().getBytes());
            }
        }
    }

    /**
     * When a block is processed on remasc the contract needs to calculate all siblings that
     * that should be rewarded when fees on this block are paid
     * @param block the block is looked for siblings
     * @return
     */
    private Map<Long, List<Sibling>> getSiblingsFromBlock(Block block) {
        return block.getUncleList().stream()
                .collect(
                    Collectors.groupingBy(
                        BlockHeader::getNumber,
                        Collectors.mapping(
                                header -> new Sibling(header, block.getCoinbase(), block.getNumber()),
                                Collectors.toList()
                        )
                    )
                );
    }

}
