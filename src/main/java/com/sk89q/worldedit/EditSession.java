// $Id$
/*
 * WorldEditLibrary
 * Copyright (C) 2010 sk89q <http://www.sk89q.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.sk89q.worldedit;

import com.sk89q.worldedit.bags.BlockBag;
import com.sk89q.worldedit.bags.BlockBagException;
import com.sk89q.worldedit.bags.UnplaceableBlockException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.blocks.BlockType;
import com.sk89q.worldedit.expression.Expression;
import com.sk89q.worldedit.expression.ExpressionException;
import com.sk89q.worldedit.expression.runtime.RValue;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.ExtentBuffer;
import com.sk89q.worldedit.function.GroundFunction;
import com.sk89q.worldedit.function.RegionMaskingFilter;
import com.sk89q.worldedit.function.block.BlockCount;
import com.sk89q.worldedit.function.block.BlockReplace;
import com.sk89q.worldedit.function.block.Naturalizer;
import com.sk89q.worldedit.function.generator.GardenPatchGenerator;
import com.sk89q.worldedit.function.mask.*;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.OperationHelper;
import com.sk89q.worldedit.function.operation.OperationQueue;
import com.sk89q.worldedit.function.pattern.BlockPattern;
import com.sk89q.worldedit.function.pattern.Patterns;
import com.sk89q.worldedit.function.util.RegionOffset;
import com.sk89q.worldedit.function.visitor.DownwardVisitor;
import com.sk89q.worldedit.function.visitor.LayerVisitor;
import com.sk89q.worldedit.function.visitor.RecursiveVisitor;
import com.sk89q.worldedit.function.visitor.RegionVisitor;
import com.sk89q.worldedit.masks.Mask;
import com.sk89q.worldedit.math.interpolation.Interpolation;
import com.sk89q.worldedit.math.interpolation.KochanekBartelsInterpolation;
import com.sk89q.worldedit.math.interpolation.Node;
import com.sk89q.worldedit.math.noise.RandomNoise;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.patterns.Pattern;
import com.sk89q.worldedit.patterns.SingleBlockPattern;
import com.sk89q.worldedit.regions.*;
import com.sk89q.worldedit.shape.ArbitraryBiomeShape;
import com.sk89q.worldedit.shape.ArbitraryShape;
import com.sk89q.worldedit.shape.RegionShape;
import com.sk89q.worldedit.shape.WorldEditExpressionEnvironment;
import com.sk89q.worldedit.util.TreeGenerator;

import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.sk89q.worldedit.regions.Regions.*;

/**
 * This class can wrap all block editing operations into one "edit session" that
 * stores the state of the blocks before modification. This allows for easy undo
 * or redo. In addition to that, this class can use a "queue mode" that will
 * know how to handle some special types of items such as signs and torches. For
 * example, torches must be placed only after there is already a block below it,
 * otherwise the torch will be placed as an item.
 *
 * @author sk89q
 */
public class EditSession implements Extent {

    /**
     * Random number generator.
     */
    private static Random prng = new Random();

    /**
     * World.
     */
    protected LocalWorld world;

    /**
     * Stores the original blocks before modification.
     */
    private DoubleArrayList<BlockVector, BaseBlock> original =
            new DoubleArrayList<BlockVector, BaseBlock>(true);

    /**
     * Stores the current blocks.
     */
    private DoubleArrayList<BlockVector, BaseBlock> current =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * Blocks that should be placed before last.
     */
    private DoubleArrayList<BlockVector, BaseBlock> queueAfter =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * Blocks that should be placed last.
     */
    private DoubleArrayList<BlockVector, BaseBlock> queueLast =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * Blocks that should be placed after all other blocks.
     */
    private DoubleArrayList<BlockVector, BaseBlock> queueFinal =
            new DoubleArrayList<BlockVector, BaseBlock>(false);

    /**
     * The maximum number of blocks to change at a time. If this number is
     * exceeded, a MaxChangedBlocksException exception will be raised. -1
     * indicates no limit.
     */
    private int maxBlocks = -1;

    /**
     * Indicates whether some types of blocks should be queued for best
     * reproduction.
     */
    private boolean queued = false;

    /**
     * Use the fast mode, which may leave chunks not flagged "dirty".
     */
    private boolean fastMode = false;

    /**
     * Block bag to use for getting blocks.
     */
    private BlockBag blockBag;

    /**
     * List of missing blocks;
     */
    private Map<Integer, Integer> missingBlocks = new HashMap<Integer, Integer>();

    /**
     * Mask to cover operations.
     */
    private Mask mask;

    /**
     * Construct the object with a maximum number of blocks.
     *
     * @param world
     * @param maxBlocks
     */
    public EditSession(LocalWorld world, int maxBlocks) {
        if (maxBlocks < -1) {
            throw new IllegalArgumentException("Max blocks must be >= -1");
        }

        this.maxBlocks = maxBlocks;
        this.world = world;
    }

    /**
     * Construct the object with a maximum number of blocks and a block bag.
     *
     * @param world
     * @param maxBlocks
     * @param blockBag
     * @blockBag
     */
    public EditSession(LocalWorld world, int maxBlocks, BlockBag blockBag) {
        if (maxBlocks < -1) {
            throw new IllegalArgumentException("Max blocks must be >= -1");
        }

        this.maxBlocks = maxBlocks;
        this.blockBag = blockBag;
        this.world = world;
    }

    /**
     * Sets a block without changing history.
     *
     * @param pt
     * @param block
     * @return Whether the block changed
     */
    public boolean rawSetBlock(Vector pt, BaseBlock block) {
        final int y = pt.getBlockY();
        final int type = block.getType();
        if (y < 0 || y > world.getMaxY()) {
            return false;
        }

        world.checkLoadedChunk(pt);

        // No invalid blocks
        if (!world.isValidBlockType(type)) {
            return false;
        }

        final int existing = world.getBlockType(pt);

        // Clear the container block so that it doesn't drop items
        if (BlockType.isContainerBlock(existing)) {
            world.clearContainerBlockContents(pt);
            // Ice turns until water so this has to be done first
        } else if (existing == BlockID.ICE) {
            world.setBlockType(pt, BlockID.AIR);
        }

        if (blockBag != null) {
            if (type > 0) {
                try {
                    blockBag.fetchPlacedBlock(type, 0);
                } catch (UnplaceableBlockException e) {
                    return false;
                } catch (BlockBagException e) {
                    if (!missingBlocks.containsKey(type)) {
                        missingBlocks.put(type, 1);
                    } else {
                        missingBlocks.put(type, missingBlocks.get(type) + 1);
                    }
                    return false;
                }
            }

            if (existing > 0) {
                try {
                    blockBag.storeDroppedBlock(existing, world.getBlockData(pt));
                } catch (BlockBagException e) {
                }
            }
        }

        boolean result;

        if (type == 0) {
            if (fastMode) {
                result = world.setBlockTypeFast(pt, 0);
            } else {
                result = world.setBlockType(pt, 0);
            }
        } else {
            result = world.setBlock(pt, block, !fastMode);
        }

        return result;
    }

    @Override
    public boolean setBlock(Vector location, BaseBlock block, boolean notifyAdjacent) throws MaxChangedBlocksException {
        return setBlock(location, block);
    }

    /**
     * Sets the block at position x, y, z with a block type. If queue mode is
     * enabled, blocks may not be actually set in world until flushQueue() is
     * called.
     *
     * @param pt
     * @param block
     * @return Whether the block changed -- not entirely dependable
     * @throws MaxChangedBlocksException
     */
    public boolean setBlock(Vector pt, BaseBlock block)
            throws MaxChangedBlocksException {
        BlockVector blockPt = pt.toBlockVector();

        if (mask != null) {
            if (!mask.matches(this, blockPt)) {
                return false;
            }
        }

        // if (!original.containsKey(blockPt)) {
        original.put(blockPt, getBlock(pt));

        if (maxBlocks != -1 && original.size() > maxBlocks) {
            throw new MaxChangedBlocksException(maxBlocks);
        }
        // }

        current.put(blockPt, block);

        return smartSetBlock(pt, block);
    }

    /**
     * Insert a contrived block change into the history.
     *
     * @param pt
     * @param existing
     * @param block
     */
    public void rememberChange(Vector pt, BaseBlock existing, BaseBlock block) {
        BlockVector blockPt = pt.toBlockVector();

        original.put(blockPt, existing);
        current.put(pt.toBlockVector(), block);
    }

    /**
     * Set a block with a pattern.
     *
     * @param pt
     * @param pat
     * @return Whether the block changed -- not entirely dependable
     * @throws MaxChangedBlocksException
     */
    public boolean setBlock(Vector pt, Pattern pat)
            throws MaxChangedBlocksException {
        return setBlock(pt, pat.next(pt));
    }

    /**
     * Set a block only if there's no block already there.
     *
     * @param pt
     * @param block
     * @return if block was changed
     * @throws MaxChangedBlocksException
     */
    public boolean setBlockIfAir(Vector pt, BaseBlock block)
            throws MaxChangedBlocksException {
        if (!getBlock(pt).isAir()) {
            return false;
        } else {
            return setBlock(pt, block);
        }
    }

    /**
     * Actually set the block. Will use queue.
     *
     * @param pt
     * @param block
     * @return
     */
    public boolean smartSetBlock(Vector pt, BaseBlock block) {
        if (queued) {
            if (BlockType.shouldPlaceLast(block.getType())) {
                // Place torches, etc. last
                queueLast.put(pt.toBlockVector(), block);
                return !(getBlockType(pt) == block.getType() && getBlockData(pt) == block.getData());
            } else if (BlockType.shouldPlaceFinal(block.getType())) {
                // Place signs, reed, etc even later
                queueFinal.put(pt.toBlockVector(), block);
                return !(getBlockType(pt) == block.getType() && getBlockData(pt) == block.getData());
            } else if (BlockType.shouldPlaceLast(getBlockType(pt))) {
                // Destroy torches, etc. first
                rawSetBlock(pt, new BaseBlock(BlockID.AIR));
            } else {
                queueAfter.put(pt.toBlockVector(), block);
                return !(getBlockType(pt) == block.getType() && getBlockData(pt) == block.getData());
            }
        }

        return rawSetBlock(pt, block);
    }

    /**
     * Gets the block type at a position x, y, z.
     *
     * @param pt
     * @return Block type
     */
    public BaseBlock getBlock(Vector pt) {
        // In the case of the queue, the block may have not actually been
        // changed yet
        if (queued) {
            /*
             * BlockVector blockPt = pt.toBlockVector();
             *
             * if (current.containsKey(blockPt)) { return current.get(blockPt);
             * }
             */
        }

        return rawGetBlock(pt);
    }

    /**
     * Gets the block type at a position x, y, z.
     *
     * @param pt
     * @return Block type
     */
    public int getBlockType(Vector pt) {
        // In the case of the queue, the block may have not actually been
        // changed yet
        if (queued) {
            /*
             * BlockVector blockPt = pt.toBlockVector();
             *
             * if (current.containsKey(blockPt)) { return current.get(blockPt);
             * }
             */
        }

        return world.getBlockType(pt);
    }

    public int getBlockData(Vector pt) {
        // In the case of the queue, the block may have not actually been
        // changed yet
        if (queued) {
            /*
             * BlockVector blockPt = pt.toBlockVector();
             *
             * if (current.containsKey(blockPt)) { return current.get(blockPt);
             * }
             */
        }

        return world.getBlockData(pt);
    }

    /**
     * Gets the block type at a position x, y, z.
     *
     * @param pt
     * @return BaseBlock
     */
    public BaseBlock rawGetBlock(Vector pt) {
        return world.getBlock(pt);
    }

    /**
     * Restores all blocks to their initial state.
     *
     * @param sess
     */
    public void undo(EditSession sess) {
        for (Map.Entry<BlockVector, BaseBlock> entry : original) {
            BlockVector pt = entry.getKey();
            sess.smartSetBlock(pt, entry.getValue());
        }
        sess.flushQueue();
    }

    /**
     * Sets to new state.
     *
     * @param sess
     */
    public void redo(EditSession sess) {
        for (Map.Entry<BlockVector, BaseBlock> entry : current) {
            BlockVector pt = entry.getKey();
            sess.smartSetBlock(pt, entry.getValue());
        }
        sess.flushQueue();
    }

    /**
     * Get the number of changed blocks.
     *
     * @return
     */
    public int size() {
        return original.size();
    }

    /**
     * Get the maximum number of blocks that can be changed. -1 will be returned
     * if disabled.
     *
     * @return block change limit
     */
    public int getBlockChangeLimit() {
        return maxBlocks;
    }

    /**
     * Set the maximum number of blocks that can be changed.
     *
     * @param maxBlocks -1 to disable
     */
    public void setBlockChangeLimit(int maxBlocks) {
        if (maxBlocks < -1) {
            throw new IllegalArgumentException("Max blocks must be >= -1");
        }
        this.maxBlocks = maxBlocks;
    }

    /**
     * Returns queue status.
     *
     * @return whether the queue is enabled
     */
    public boolean isQueueEnabled() {
        return queued;
    }

    /**
     * Queue certain types of block for better reproduction of those blocks.
     */
    public void enableQueue() {
        queued = true;
    }

    /**
     * Disable the queue. This will flush the queue.
     */
    public void disableQueue() {
        if (queued) {
            flushQueue();
        }
        queued = false;
    }

    /**
     * Set fast mode.
     *
     * @param fastMode
     */
    public void setFastMode(boolean fastMode) {
        this.fastMode = fastMode;
    }

    /**
     * Return fast mode status.
     *
     * @return
     */
    public boolean hasFastMode() {
        return fastMode;
    }

    /**
     * Set a block by chance.
     *
     * @param pos
     * @param block
     * @param c 0-1 chance
     * @return whether a block was changed
     * @throws MaxChangedBlocksException
     */
    public boolean setChanceBlockIfAir(Vector pos, BaseBlock block, double c)
            throws MaxChangedBlocksException {
        if (Math.random() <= c) {
            return setBlockIfAir(pos, block);
        }
        return false;
    }

    public int countBlock(Region region, Set<Integer> searchIDs) {
        Set<BaseBlock> passOn = new HashSet<BaseBlock>();
        for (Integer i : searchIDs) {
            passOn.add(new BaseBlock(i, -1));
        }
        return countBlocks(region, passOn);
    }

    /**
     * Count the number of blocks of a list of types in a region.
     *
     * @param region the region
     * @param searchBlocks the list of blocks to search
     * @return the number of blocks that matched the pattern
     */
    public int countBlocks(Region region, Set<BaseBlock> searchBlocks) {
        FuzzyBlockMask mask = new FuzzyBlockMask(this, searchBlocks);
        BlockCount count = new BlockCount();
        RegionMaskingFilter filter = new RegionMaskingFilter(mask, count);
        RegionVisitor visitor = new RegionVisitor(region, filter);
        OperationHelper.completeBlindly(visitor); // We can't throw exceptions, nor do we expect any
        return count.getCount();
    }

    /**
     * Returns the highest solid 'terrain' block which can occur naturally.
     *
     * @param x
     * @param z
     * @param minY minimal height
     * @param maxY maximal height
     * @return height of highest block found or 'minY'
     */
    public int getHighestTerrainBlock(int x, int z, int minY, int maxY) {
        return getHighestTerrainBlock(x, z, minY, maxY, false);
    }

    /**
     * Returns the highest solid 'terrain' block which can occur naturally.
     *
     * @param x
     * @param z
     * @param minY minimal height
     * @param maxY maximal height
     * @param naturalOnly look at natural blocks or all blocks
     * @return height of highest block found or 'minY'
     */
    public int getHighestTerrainBlock(int x, int z, int minY, int maxY, boolean naturalOnly) {
        for (int y = maxY; y >= minY; --y) {
            Vector pt = new Vector(x, y, z);
            int id = getBlockType(pt);
            int data = getBlockData(pt);
            if (naturalOnly ? BlockType.isNaturalTerrainBlock(id, data) : !BlockType.canPassThrough(id, data)) {
                return y;
            }
        }
        return minY;
    }

    /**
     * Gets the list of missing blocks and clears the list for the next
     * operation.
     *
     * @return
     */
    public Map<Integer, Integer> popMissingBlocks() {
        Map<Integer, Integer> missingBlocks = this.missingBlocks;
        this.missingBlocks = new HashMap<Integer, Integer>();
        return missingBlocks;
    }

    /**
     * @return the blockBag
     */
    public BlockBag getBlockBag() {
        return blockBag;
    }

    /**
     * @param blockBag the blockBag to set
     */
    public void setBlockBag(BlockBag blockBag) {
        this.blockBag = blockBag;
    }

    /**
     * Get the world.
     *
     * @return
     */
    public LocalWorld getWorld() {
        return world;
    }

    /**
     * Get the number of blocks changed, including repeated block changes.
     *
     * @return
     */
    public int getBlockChangeCount() {
        return original.size();
    }

    /**
     * Get the mask.
     *
     * @return mask, may be null
     */
    public Mask getMask() {
        return mask;
    }

    /**
     * Set a mask.
     *
     * @param mask mask or null
     */
    public void setMask(Mask mask) {
        this.mask = mask;
    }

    /**
     * Finish off the queue.
     */
    public void flushQueue() {
        if (!queued) {
            return;
        }

        final Set<BlockVector2D> dirtyChunks = new HashSet<BlockVector2D>();

        for (Map.Entry<BlockVector, BaseBlock> entry : queueAfter) {
            BlockVector pt = entry.getKey();
            rawSetBlock(pt, entry.getValue());

            // TODO: use ChunkStore.toChunk(pt) after optimizing it.
            if (fastMode) {
                dirtyChunks.add(new BlockVector2D(pt.getBlockX() >> 4, pt.getBlockZ() >> 4));
            }
        }

        // We don't want to place these blocks if other blocks were missing
        // because it might cause the items to drop
        if (blockBag == null || missingBlocks.size() == 0) {
            for (Map.Entry<BlockVector, BaseBlock> entry : queueLast) {
                BlockVector pt = entry.getKey();
                rawSetBlock(pt, entry.getValue());

                // TODO: use ChunkStore.toChunk(pt) after optimizing it.
                if (fastMode) {
                    dirtyChunks.add(new BlockVector2D(pt.getBlockX() >> 4, pt.getBlockZ() >> 4));
                }
            }

            final Set<BlockVector> blocks = new HashSet<BlockVector>();
            final Map<BlockVector, BaseBlock> blockTypes = new HashMap<BlockVector, BaseBlock>();
            for (Map.Entry<BlockVector, BaseBlock> entry : queueFinal) {
                final BlockVector pt = entry.getKey();
                blocks.add(pt);
                blockTypes.put(pt, entry.getValue());
            }

            while (!blocks.isEmpty()) {
                BlockVector current = blocks.iterator().next();
                if (!blocks.contains(current)) {
                    continue;
                }

                final Deque<BlockVector> walked = new LinkedList<BlockVector>();

                while (true) {
                    walked.addFirst(current);

                    assert(blockTypes.containsKey(current));

                    final BaseBlock baseBlock = blockTypes.get(current);

                    final int type = baseBlock.getType();
                    final int data = baseBlock.getData();

                    switch (type) {
                    case BlockID.WOODEN_DOOR:
                    case BlockID.IRON_DOOR:
                        if ((data & 0x8) == 0) {
                            // Deal with lower door halves being attached to the floor AND the upper half
                            BlockVector upperBlock = current.add(0, 1, 0).toBlockVector();
                            if (blocks.contains(upperBlock) && !walked.contains(upperBlock)) {
                                walked.addFirst(upperBlock);
                            }
                        }
                        break;

                    case BlockID.MINECART_TRACKS:
                    case BlockID.POWERED_RAIL:
                    case BlockID.DETECTOR_RAIL:
                    case BlockID.ACTIVATOR_RAIL:
                        // Here, rails are hardcoded to be attached to the block below them.
                        // They're also attached to the block they're ascending towards via BlockType.getAttachment.
                        BlockVector lowerBlock = current.add(0, -1, 0).toBlockVector();
                        if (blocks.contains(lowerBlock) && !walked.contains(lowerBlock)) {
                            walked.addFirst(lowerBlock);
                        }
                        break;
                    }

                    final PlayerDirection attachment = BlockType.getAttachment(type, data);
                    if (attachment == null) {
                        // Block is not attached to anything => we can place it
                        break;
                    }

                    current = current.add(attachment.vector()).toBlockVector();

                    if (!blocks.contains(current)) {
                        // We ran outside the remaing set => assume we can place blocks on this
                        break;
                    }

                    if (walked.contains(current)) {
                        // Cycle detected => This will most likely go wrong, but there's nothing we can do about it.
                        break;
                    }
                }

                for (BlockVector pt : walked) {
                    rawSetBlock(pt, blockTypes.get(pt));
                    blocks.remove(pt);

                    // TODO: use ChunkStore.toChunk(pt) after optimizing it.
                    if (fastMode) {
                        dirtyChunks.add(new BlockVector2D(pt.getBlockX() >> 4, pt.getBlockZ() >> 4));
                    }
                }
            }
        }

        if (!dirtyChunks.isEmpty()) world.fixAfterFastMode(dirtyChunks);

        queueAfter.clear();
        queueLast.clear();
        queueFinal.clear();
    }

    /**
     * Fills an area recursively in the X/Z directions.
     *
     * @param origin the location to start from
     * @param block the block to fill with
     * @param radius the radius of the spherical area to fill
     * @param depth the maximum depth, starting from the origin
     * @param recursive whether a breadth-first search should be performed
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int fillXZ(Vector origin, BaseBlock block, double radius, int depth, boolean recursive)
            throws MaxChangedBlocksException {
        return fillXZ(origin, new SingleBlockPattern(block), radius, depth, recursive);
    }

    /**
     * Fills an area recursively in the X/Z directions.
     *
     * @param origin the origin to start the fill from
     * @param pattern the pattern to fill with
     * @param radius the radius of the spherical area to fill, with 0 as the smallest radius
     * @param depth the maximum depth, starting from the origin, with 1 as the smallest depth
     * @param recursive whether a breadth-first search should be performed
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int fillXZ(Vector origin, Pattern pattern, double radius, int depth, boolean recursive) throws MaxChangedBlocksException {
        checkNotNull(origin);
        checkNotNull(pattern);
        checkArgument(radius >= 0, "radius >= 0");
        checkArgument(depth >= 1, "depth >= 1");

        MaskIntersection mask = new MaskIntersection(
                new RegionMask(new EllipsoidRegion(null, origin, new Vector(radius, radius, radius))),
                new BoundedHeightMask(
                        Math.max(origin.getBlockY() - depth + 1, 0),
                        Math.min(getWorld().getMaxY(), origin.getBlockY())),
                Masks.negate(new ExistingBlockMask(this)));

        // Want to replace blocks
        BlockReplace replace = new BlockReplace(this, Patterns.wrap(pattern));

        // Pick how we're going to visit blocks
        RecursiveVisitor visitor;
        if (recursive) {
            visitor = new RecursiveVisitor(mask, replace);
        } else {
            visitor = new DownwardVisitor(mask, replace, origin.getBlockY());
        }

        // Start at the origin
        visitor.visit(origin);

        // Execute
        OperationHelper.completeLegacy(visitor);

        return visitor.getAffected();
    }

    /**
     * Remove a cuboid above the given position with a given apothem and a given height.
     *
     * @param position base position
     * @param apothem an apothem of the cuboid (on the XZ plane), where the minimum is 1
     * @param height the height of the cuboid, where the minimum is 1
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int removeAbove(Vector position, int apothem, int height) throws MaxChangedBlocksException {
        checkNotNull(position);
        checkArgument(apothem >= 1, "apothem >= 1");
        checkArgument(height >= 1, "height >= 1");

        Region region = new CuboidRegion(
                getWorld(), // Causes clamping of Y range
                position.add(-apothem + 1, 0, -apothem + 1),
                position.add(apothem - 1, height - 1, apothem - 1));
        Pattern pattern = new SingleBlockPattern(new BaseBlock(BlockID.AIR));
        return setBlocks(region, pattern);
    }

    /**
     * Remove a cuboid below the given position with a given apothem and a given height.
     *
     * @param position base position
     * @param apothem an apothem of the cuboid (on the XZ plane), where the minimum is 1
     * @param height the height of the cuboid, where the minimum is 1
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int removeBelow(Vector position, int apothem, int height) throws MaxChangedBlocksException {
        checkNotNull(position);
        checkArgument(apothem >= 1, "apothem >= 1");
        checkArgument(height >= 1, "height >= 1");

        Region region = new CuboidRegion(
                getWorld(), // Causes clamping of Y range
                position.add(-apothem + 1, 0, -apothem + 1),
                position.add(apothem - 1, -height + 1, apothem - 1));
        Pattern pattern = new SingleBlockPattern(new BaseBlock(BlockID.AIR));
        return setBlocks(region, pattern);
    }

    /**
     * Remove blocks of a certain type nearby a given position.
     *
     * @param position center position of cuboid
     * @param blockType the block type to match
     * @param apothem an apothem of the cuboid, where the minimum is 1
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int removeNear(Vector position, int blockType, int apothem) throws MaxChangedBlocksException {
        checkNotNull(position);
        checkArgument(apothem >= 1, "apothem >= 1");

        Mask mask = new com.sk89q.worldedit.masks.FuzzyBlockMask(new BaseBlock(blockType, -1));
        Vector adjustment = new Vector(1, 1, 1).multiply(apothem - 1);
        Region region = new CuboidRegion(
                getWorld(), // Causes clamping of Y range
                position.add(adjustment.multiply(-1)),
                position.add(adjustment));
        Pattern pattern = new SingleBlockPattern(new BaseBlock(BlockID.AIR));
        return replaceBlocks(region, mask, pattern);
    }

    /**
     * Sets all the blocks inside a region to a given block type.
     *
     * @param region the region
     * @param block the block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int setBlocks(Region region, BaseBlock block) throws MaxChangedBlocksException {
        return setBlocks(region, new SingleBlockPattern(block));
    }

    /**
     * Sets all the blocks inside a region to a given pattern.
     *
     * @param region the region
     * @param pattern the pattern that provides the replacement block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        BlockReplace replace = new BlockReplace(this, Patterns.wrap(pattern));
        RegionVisitor visitor = new RegionVisitor(region, replace);
        OperationHelper.completeLegacy(visitor);
        return visitor.getAffected();
    }

    /**
     * Replaces all the blocks matching a given filter, within a given region, to a block
     * returned by a given pattern.
     *
     * @param region the region to replace the blocks within
     * @param filter a list of block types to match, or null to use {@link com.sk89q.worldedit.masks.ExistingBlockMask}
     * @param replacement the replacement block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int replaceBlocks(Region region, Set<BaseBlock> filter, BaseBlock replacement) throws MaxChangedBlocksException {
        return replaceBlocks(region, filter, new SingleBlockPattern(replacement));
    }

    /**
     * Replaces all the blocks matching a given filter, within a given region, to a block
     * returned by a given pattern.
     *
     * @param region the region to replace the blocks within
     * @param filter a list of block types to match, or null to use {@link com.sk89q.worldedit.masks.ExistingBlockMask}
     * @param pattern the pattern that provides the new blocks
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int replaceBlocks(Region region, Set<BaseBlock> filter, Pattern pattern) throws MaxChangedBlocksException {
        Mask mask = filter == null ? new com.sk89q.worldedit.masks.ExistingBlockMask() : new com.sk89q.worldedit.masks.FuzzyBlockMask(filter);
        return replaceBlocks(region, mask, pattern);
    }

    /**
     * Replaces all the blocks matching a given mask, within a given region, to a block
     * returned by a given pattern.
     *
     * @param region the region to replace the blocks within
     * @param mask the mask that blocks must match
     * @param pattern the pattern that provides the new blocks
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int replaceBlocks(Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(mask);
        checkNotNull(pattern);

        BlockReplace replace = new BlockReplace(this, Patterns.wrap(pattern));
        RegionMaskingFilter filter = new RegionMaskingFilter(Masks.wrap(this, mask), replace);
        RegionVisitor visitor = new RegionVisitor(region, filter);
        OperationHelper.completeLegacy(visitor);
        return visitor.getAffected();
    }

    /**
     * Sets the blocks at the center of the given region to the given pattern.
     * If the center sits between two blocks on a certain axis, then two blocks
     * will be placed to mark the center.
     *
     * @param region the region to find the center of
     * @param pattern the replacement pattern
     * @return the number of blocks placed
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int center(Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        Vector center = region.getCenter();
        Region centerRegion = new CuboidRegion(
                getWorld(), // Causes clamping of Y range
                new Vector((int) center.getX(), (int) center.getY(), (int) center.getZ()),
                center.toBlockVector());
        return setBlocks(centerRegion, pattern);
    }

    /**
     * Make the faces of the given region as if it was a {@link CuboidRegion}.
     *
     * @param region the region
     * @param block the block to place
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    @SuppressWarnings("deprecation")
    public int makeCuboidFaces(Region region, BaseBlock block) throws MaxChangedBlocksException {
        return makeCuboidFaces(region, new SingleBlockPattern(block));
    }

    /**
     * Make the faces of the given region as if it was a {@link CuboidRegion}.
     *
     * @param region the region
     * @param pattern the pattern to place
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int makeCuboidFaces(Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        CuboidRegion cuboid = CuboidRegion.makeCuboid(region);
        Region faces = cuboid.getFaces();
        return setBlocks(faces, pattern);
    }

    /**
     * Make the faces of the given region. The method by which the faces are found
     * may be inefficient, because there may not be an efficient implementation supported
     * for that specific shape.
     *
     * @param region the region
     * @param pattern the pattern to place
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int makeFaces(final Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        if (region instanceof CuboidRegion) {
            return makeCuboidFaces(region, pattern);
        } else {
            return new RegionShape(region).generate(this, pattern, true);
        }
    }


    /**
     * Make the walls (all faces but those parallel to the X-Z plane) of the given region
     * as if it was a {@link CuboidRegion}.
     *
     * @param region the region
     * @param block the block to place
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    @SuppressWarnings("deprecation")
    public int makeCuboidWalls(Region region, BaseBlock block) throws MaxChangedBlocksException {
        return makeCuboidWalls(region, new SingleBlockPattern(block));
    }

    /**
     * Make the walls (all faces but those parallel to the X-Z plane) of the given region
     * as if it was a {@link CuboidRegion}.
     *
     * @param region the region
     * @param pattern the pattern to place
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int makeCuboidWalls(Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        CuboidRegion cuboid = CuboidRegion.makeCuboid(region);
        Region faces = cuboid.getWalls();
        return setBlocks(faces, pattern);
    }

    /**
     * Make the walls of the given region. The method by which the walls are found
     * may be inefficient, because there may not be an efficient implementation supported
     * for that specific shape.
     *
     * @param region the region
     * @param pattern the pattern to place
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int makeWalls(final Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        if (region instanceof CuboidRegion) {
            return makeCuboidWalls(region, pattern);
        } else {
            final int minY = region.getMinimumPoint().getBlockY();
            final int maxY = region.getMaximumPoint().getBlockY();
            final ArbitraryShape shape = new RegionShape(region) {
                @Override
                protected BaseBlock getMaterial(int x, int y, int z, BaseBlock defaultMaterial) {
                    if (y > maxY || y < minY) {
                        // Put holes into the floor and ceiling by telling ArbitraryShape that the shape goes on outside the region
                        return defaultMaterial;
                    }

                    return super.getMaterial(x, y, z, defaultMaterial);
                }
            };
            return shape.generate(this, pattern, true);
        }
    }

    /**
     * Places a layer of blocks on top of ground blocks in the given region
     * (as if it were a cuboid).
     *
     * @param region the region
     * @param block the placed block
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int overlayCuboidBlocks(Region region, BaseBlock block) throws MaxChangedBlocksException {
        checkNotNull(block);

        return overlayCuboidBlocks(region, new SingleBlockPattern(block));
    }

    /**
     * Places a layer of blocks on top of ground blocks in the given region
     * (as if it were a cuboid).
     *
     * @param region the region
     * @param pattern the placed block pattern
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    @SuppressWarnings("deprecation")
    public int overlayCuboidBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(pattern);

        BlockReplace replace = new BlockReplace(this, Patterns.wrap(pattern));
        RegionOffset offset = new RegionOffset(new Vector(0, 1, 0), replace);
        GroundFunction ground = new GroundFunction(this, offset);
        LayerVisitor visitor = new LayerVisitor(asFlatRegion(region), minimumBlockY(region), maximumBlockY(region), ground);
        OperationHelper.completeLegacy(visitor);
        return ground.getAffected();
    }

    /**
     * Turns the first 3 layers into dirt/grass and the bottom layers
     * into rock, like a natural Minecraft mountain.
     *
     * @param region the region to affect
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    public int naturalizeCuboidBlocks(Region region) throws MaxChangedBlocksException {
        checkNotNull(region);

        Naturalizer naturalizer = new Naturalizer(this);
        FlatRegion flatRegion = Regions.asFlatRegion(region);
        LayerVisitor visitor = new LayerVisitor(flatRegion, minimumBlockY(region), maximumBlockY(region), naturalizer);
        OperationHelper.completeLegacy(visitor);
        return naturalizer.getAffected();
    }

    /**
     * Stack a cuboid region.
     *
     * @param region the region to stack
     * @param dir the direction to stack
     * @param count the number of times to stack
     * @param copyAir true to also copy air blocks
     * @return number of blocks affected
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    public int stackCuboidRegion(Region region, Vector dir, int count, boolean copyAir) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(dir);
        checkArgument(count >= 1, "count >= 1 required");

        Vector size = region.getMaximumPoint().subtract(region.getMinimumPoint()).add(1, 1, 1);
        Vector to = region.getMinimumPoint();
        ForwardExtentCopy copy = new ForwardExtentCopy(this, region, this, to);
        copy.setRepetitions(count);
        copy.setTransform(new AffineTransform().translate(dir.multiply(size)));
        if (!copyAir) {
            copy.setSourceMask(new ExistingBlockMask(this));
        }
        OperationHelper.completeLegacy(copy);
        return copy.getAffected();
    }

    /**
     * Move the blocks in a region a certain direction.
     *
     * @param region the region to move
     * @param dir the direction
     * @param distance the distance to move
     * @param copyAir true to copy air blocks
     * @param replacement the replacement block to fill in after moving, or null to use air
     * @return number of blocks moved
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    public int moveRegion(Region region, Vector dir, int distance, boolean copyAir, BaseBlock replacement) throws MaxChangedBlocksException {
        checkNotNull(region);
        checkNotNull(dir);
        checkArgument(distance >= 1, "distance >= 1 required");

        Vector to = region.getMinimumPoint();

        // Remove the original blocks
        com.sk89q.worldedit.function.pattern.Pattern pattern = replacement != null ?
                new BlockPattern(replacement) :
                new BlockPattern(new BaseBlock(BlockID.AIR));
        BlockReplace remove = new BlockReplace(this, pattern);

        // Copy to a buffer so we don't destroy our original before we can copy all the blocks from it
        ExtentBuffer buffer = new ExtentBuffer(this, new RegionMask(region));
        ForwardExtentCopy copy = new ForwardExtentCopy(this, region, buffer, to);
        copy.setTransform(new AffineTransform().translate(dir.multiply(distance)));
        copy.setSourceFunction(remove); // Remove
        if (!copyAir) {
            copy.setSourceMask(new ExistingBlockMask(this));
        }

        // Then we need to copy the buffer to the world
        BlockReplace replace = new BlockReplace(this, buffer);
        RegionVisitor visitor = new RegionVisitor(buffer.asRegion(), replace);

        OperationQueue operation = new OperationQueue(copy, visitor);
        OperationHelper.completeLegacy(operation);

        return copy.getAffected();
    }

    /**
     * Move the blocks in a region a certain direction.
     *
     * @param region the region to move
     * @param dir the direction
     * @param distance the distance to move
     * @param copyAir true to copy air blocks
     * @param replacement the replacement block to fill in after moving, or null to use air
     * @return number of blocks moved
     * @throws MaxChangedBlocksException thrown if too many blocks are changed
     */
    public int moveCuboidRegion(Region region, Vector dir, int distance, boolean copyAir, BaseBlock replacement) throws MaxChangedBlocksException {
        return moveRegion(region, dir, distance, copyAir, replacement);
    }

    /**
     * Drain nearby pools of water or lava.
     *
     * @param origin the origin to drain from, which will search a 3x3 area
     * @param radius the radius of the removal, where a value should be 0 or greater
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int drainArea(Vector origin, double radius) throws MaxChangedBlocksException {
        checkNotNull(origin);
        checkArgument(radius >= 0, "radius >= 0 required");

        MaskIntersection mask = new MaskIntersection(
                new BoundedHeightMask(0, getWorld().getMaxY()),
                new RegionMask(new EllipsoidRegion(null, origin, new Vector(radius, radius, radius))),
                new BlockMask(this,
                        new BaseBlock(BlockID.STATIONARY_LAVA, -1),
                        new BaseBlock(BlockID.LAVA, -1),
                        new BaseBlock(BlockID.STATIONARY_WATER, -1),
                        new BaseBlock(BlockID.WATER, -1)));

        BlockReplace replace = new BlockReplace(this, new BlockPattern(new BaseBlock(BlockID.AIR)));
        RecursiveVisitor visitor = new RecursiveVisitor(mask, replace);

        // Around the origin in a 3x3 block
        for (BlockVector position : CuboidRegion.fromCenter(origin, 1)) {
            if (mask.test(position)) {
                visitor.visit(position);
            }
        }

        OperationHelper.completeLegacy(visitor);

        return visitor.getAffected();
    }

    /**
     * Level water.
     *
     * @param pos
     * @param radius
     * @param moving
     * @param stationary
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int fixLiquid(Vector pos, double radius, int moving, int stationary)
            throws MaxChangedBlocksException {
        int affected = 0;

        HashSet<BlockVector> visited = new HashSet<BlockVector>();
        Stack<BlockVector> queue = new Stack<BlockVector>();

        for (int x = pos.getBlockX() - 1; x <= pos.getBlockX() + 1; ++x) {
            for (int z = pos.getBlockZ() - 1; z <= pos.getBlockZ() + 1; ++z) {
                for (int y = pos.getBlockY() - 1; y <= pos.getBlockY() + 1; ++y) {
                    int type = getBlock(new Vector(x, y, z)).getType();

                    // Check block type
                    if (type == moving || type == stationary) {
                        queue.push(new BlockVector(x, y, z));
                    }
                }
            }
        }

        BaseBlock stationaryBlock = new BaseBlock(stationary);

        while (!queue.empty()) {
            BlockVector cur = queue.pop();

            int type = getBlockType(cur);

            // Check block type
            if (type != moving && type != stationary && type != BlockID.AIR) {
                continue;
            }

            // Don't want to revisit
            if (visited.contains(cur)) {
                continue;
            }

            visited.add(cur);

            if (setBlock(cur, stationaryBlock)) {
                ++affected;
            }

            // Check radius
            if (pos.distance(cur) > radius) {
                continue;
            }

            queue.push(cur.add(1, 0, 0).toBlockVector());
            queue.push(cur.add(-1, 0, 0).toBlockVector());
            queue.push(cur.add(0, 0, 1).toBlockVector());
            queue.push(cur.add(0, 0, -1).toBlockVector());
        }

        return affected;
    }

    /**
     * Makes a cylinder.
     *
     * @param pos Center of the cylinder
     * @param block The block pattern to use
     * @param radius The cylinder's radius
     * @param height The cylinder's up/down extent. If negative, extend downward.
     * @param filled If false, only a shell will be generated.
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makeCylinder(Vector pos, Pattern block, double radius, int height, boolean filled) throws MaxChangedBlocksException {
        return makeCylinder(pos, block, radius, radius, height, filled);
    }

    /**
     * Makes a cylinder.
     *
     * @param pos Center of the cylinder
     * @param block The block pattern to use
     * @param radiusX The cylinder's largest north/south extent
     * @param radiusZ The cylinder's largest east/west extent
     * @param height The cylinder's up/down extent. If negative, extend downward.
     * @param filled If false, only a shell will be generated.
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makeCylinder(Vector pos, Pattern block, double radiusX, double radiusZ, int height, boolean filled) throws MaxChangedBlocksException {
        int affected = 0;

        radiusX += 0.5;
        radiusZ += 0.5;

        if (height == 0) {
            return 0;
        } else if (height < 0) {
            height = -height;
            pos = pos.subtract(0, height, 0);
        }

        if (pos.getBlockY() < 0) {
            pos = pos.setY(0);
        } else if (pos.getBlockY() + height - 1 > world.getMaxY()) {
            height = world.getMaxY() - pos.getBlockY() + 1;
        }

        final double invRadiusX = 1 / radiusX;
        final double invRadiusZ = 1 / radiusZ;

        final int ceilRadiusX = (int) Math.ceil(radiusX);
        final int ceilRadiusZ = (int) Math.ceil(radiusZ);

        double nextXn = 0;
        forX: for (int x = 0; x <= ceilRadiusX; ++x) {
            final double xn = nextXn;
            nextXn = (x + 1) * invRadiusX;
            double nextZn = 0;
            forZ: for (int z = 0; z <= ceilRadiusZ; ++z) {
                final double zn = nextZn;
                nextZn = (z + 1) * invRadiusZ;

                double distanceSq = lengthSq(xn, zn);
                if (distanceSq > 1) {
                    if (z == 0) {
                        break forX;
                    }
                    break forZ;
                }

                if (!filled) {
                    if (lengthSq(nextXn, zn) <= 1 && lengthSq(xn, nextZn) <= 1) {
                        continue;
                    }
                }

                for (int y = 0; y < height; ++y) {
                    if (setBlock(pos.add(x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, -z), block)) {
                        ++affected;
                    }
                }
            }
        }

        return affected;
    }

    /**
    * Makes a sphere.
    *
    * @param pos Center of the sphere or ellipsoid
    * @param block The block pattern to use
    * @param radius The sphere's radius
    * @param filled If false, only a shell will be generated.
    * @return number of blocks changed
    * @throws MaxChangedBlocksException
    */
    public int makeSphere(Vector pos, Pattern block, double radius, boolean filled) throws MaxChangedBlocksException {
        return makeSphere(pos, block, radius, radius, radius, filled);
    }

    /**
     * Makes a sphere or ellipsoid.
     *
     * @param pos Center of the sphere or ellipsoid
     * @param block The block pattern to use
     * @param radiusX The sphere/ellipsoid's largest north/south extent
     * @param radiusY The sphere/ellipsoid's largest up/down extent
     * @param radiusZ The sphere/ellipsoid's largest east/west extent
     * @param filled If false, only a shell will be generated.
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makeSphere(Vector pos, Pattern block, double radiusX, double radiusY, double radiusZ, boolean filled) throws MaxChangedBlocksException {
        int affected = 0;

        radiusX += 0.5;
        radiusY += 0.5;
        radiusZ += 0.5;

        final double invRadiusX = 1 / radiusX;
        final double invRadiusY = 1 / radiusY;
        final double invRadiusZ = 1 / radiusZ;

        final int ceilRadiusX = (int) Math.ceil(radiusX);
        final int ceilRadiusY = (int) Math.ceil(radiusY);
        final int ceilRadiusZ = (int) Math.ceil(radiusZ);

        double nextXn = 0;
        forX: for (int x = 0; x <= ceilRadiusX; ++x) {
            final double xn = nextXn;
            nextXn = (x + 1) * invRadiusX;
            double nextYn = 0;
            forY: for (int y = 0; y <= ceilRadiusY; ++y) {
                final double yn = nextYn;
                nextYn = (y + 1) * invRadiusY;
                double nextZn = 0;
                forZ: for (int z = 0; z <= ceilRadiusZ; ++z) {
                    final double zn = nextZn;
                    nextZn = (z + 1) * invRadiusZ;

                    double distanceSq = lengthSq(xn, yn, zn);
                    if (distanceSq > 1) {
                        if (z == 0) {
                            if (y == 0) {
                                break forX;
                            }
                            break forY;
                        }
                        break forZ;
                    }

                    if (!filled) {
                        if (lengthSq(nextXn, yn, zn) <= 1 && lengthSq(xn, nextYn, zn) <= 1 && lengthSq(xn, yn, nextZn) <= 1) {
                            continue;
                        }
                    }

                    if (setBlock(pos.add(x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, -y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, -y, z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(x, -y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, y, -z), block)) {
                        ++affected;
                    }
                    if (setBlock(pos.add(-x, -y, -z), block)) {
                        ++affected;
                    }
                }
            }
        }

        return affected;
    }

    private static final double lengthSq(double x, double y, double z) {
        return (x * x) + (y * y) + (z * z);
    }

    private static final double lengthSq(double x, double z) {
        return (x * x) + (z * z);
    }

    /**
     * Makes a pyramid.
     *
     * @param pos
     * @param block
     * @param size
     * @param filled
     * @return number of blocks changed
     * @throws MaxChangedBlocksException
     */
    public int makePyramid(Vector pos, Pattern block, int size,
            boolean filled) throws MaxChangedBlocksException {
        int affected = 0;

        int height = size;

        for (int y = 0; y <= height; ++y) {
            size--;
            for (int x = 0; x <= size; ++x) {
                for (int z = 0; z <= size; ++z) {

                    if ((filled && z <= size && x <= size) || z == size || x == size) {

                        if (setBlock(pos.add(x, y, z), block)) {
                            ++affected;
                        }
                        if (setBlock(pos.add(-x, y, z), block)) {
                            ++affected;
                        }
                        if (setBlock(pos.add(x, y, -z), block)) {
                            ++affected;
                        }
                        if (setBlock(pos.add(-x, y, -z), block)) {
                            ++affected;
                        }
                    }
                }
            }
        }

        return affected;
    }

    /**
     * Thaw.
     *
     * @param pos
     * @param radius
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int thaw(Vector pos, double radius)
            throws MaxChangedBlocksException {
        int affected = 0;
        double radiusSq = radius * radius;

        int ox = pos.getBlockX();
        int oy = pos.getBlockY();
        int oz = pos.getBlockZ();

        BaseBlock air = new BaseBlock(0);
        BaseBlock water = new BaseBlock(BlockID.STATIONARY_WATER);

        int ceilRadius = (int) Math.ceil(radius);
        for (int x = ox - ceilRadius; x <= ox + ceilRadius; ++x) {
            for (int z = oz - ceilRadius; z <= oz + ceilRadius; ++z) {
                if ((new Vector(x, oy, z)).distanceSq(pos) > radiusSq) {
                    continue;
                }

                for (int y = world.getMaxY(); y >= 1; --y) {
                    Vector pt = new Vector(x, y, z);
                    int id = getBlockType(pt);

                    switch (id) {
                    case BlockID.ICE:
                        if (setBlock(pt, water)) {
                            ++affected;
                        }
                        break;

                    case BlockID.SNOW:
                        if (setBlock(pt, air)) {
                            ++affected;
                        }
                        break;

                    case BlockID.AIR:
                        continue;

                    default:
                        break;
                    }

                    break;
                }
            }
        }

        return affected;
    }

    /**
     * Make snow.
     *
     * @param pos
     * @param radius
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int simulateSnow(Vector pos, double radius)
            throws MaxChangedBlocksException {
        int affected = 0;
        double radiusSq = radius * radius;

        int ox = pos.getBlockX();
        int oy = pos.getBlockY();
        int oz = pos.getBlockZ();

        BaseBlock ice = new BaseBlock(BlockID.ICE);
        BaseBlock snow = new BaseBlock(BlockID.SNOW);

        int ceilRadius = (int) Math.ceil(radius);
        for (int x = ox - ceilRadius; x <= ox + ceilRadius; ++x) {
            for (int z = oz - ceilRadius; z <= oz + ceilRadius; ++z) {
                if ((new Vector(x, oy, z)).distanceSq(pos) > radiusSq) {
                    continue;
                }

                for (int y = world.getMaxY(); y >= 1; --y) {
                    Vector pt = new Vector(x, y, z);
                    int id = getBlockType(pt);

                    if (id == BlockID.AIR) {
                        continue;
                    }

                    // Ice!
                    if (id == BlockID.WATER || id == BlockID.STATIONARY_WATER) {
                        if (setBlock(pt, ice)) {
                            ++affected;
                        }
                        break;
                    }

                    // Snow should not cover these blocks
                    if (BlockType.isTranslucent(id)) {
                        break;
                    }

                    // Too high?
                    if (y == world.getMaxY()) {
                        break;
                    }

                    // add snow cover
                    if (setBlock(pt.add(0, 1, 0), snow)) {
                        ++affected;
                    }
                    break;
                }
            }
        }

        return affected;
    }

    /**
     * Green.
     *
     * @param pos
     * @param radius
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     * @deprecated Use {@link #green(Vector, double, boolean)}.
     */
    @Deprecated
    public int green(Vector pos, double radius)
            throws MaxChangedBlocksException {
        return green(pos, radius, true);
    }

    /**
     * Green.
     *
     * @param pos
     * @param radius
     * @param onlyNormalDirt only affect normal dirt (data value 0)
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int green(Vector pos, double radius, boolean onlyNormalDirt)
            throws MaxChangedBlocksException {
        int affected = 0;
        final double radiusSq = radius * radius;

        final int ox = pos.getBlockX();
        final int oy = pos.getBlockY();
        final int oz = pos.getBlockZ();

        final BaseBlock grass = new BaseBlock(BlockID.GRASS);

        final int ceilRadius = (int) Math.ceil(radius);
        for (int x = ox - ceilRadius; x <= ox + ceilRadius; ++x) {
            for (int z = oz - ceilRadius; z <= oz + ceilRadius; ++z) {
                if ((new Vector(x, oy, z)).distanceSq(pos) > radiusSq) {
                    continue;
                }

                loop: for (int y = world.getMaxY(); y >= 1; --y) {
                    final Vector pt = new Vector(x, y, z);
                    final int id = getBlockType(pt);
                    final int data = getBlockData(pt);

                    switch (id) {
                    case BlockID.DIRT:
                        if (onlyNormalDirt && data != 0) {
                            break loop;
                        }

                        if (setBlock(pt, grass)) {
                            ++affected;
                        }
                        break loop;

                    case BlockID.WATER:
                    case BlockID.STATIONARY_WATER:
                    case BlockID.LAVA:
                    case BlockID.STATIONARY_LAVA:
                        // break on liquids...
                        break loop;

                    default:
                        // ...and all non-passable blocks
                        if (!BlockType.canPassThrough(id, data)) {
                            break loop;
                        }
                    }
                }
            }
        }

        return affected;
    }

    /**
     * Makes pumpkin patches randomly in an area around the given position.
     *
     * @param position the base position
     * @param apothem the apothem of the (square) area
     * @return number of patches created
     * @throws MaxChangedBlocksException
     */
    public int makePumpkinPatches(Vector position, int apothem) throws MaxChangedBlocksException {
        // We want to generate pumpkins
        GardenPatchGenerator generator = new GardenPatchGenerator(this);
        generator.setPlant(GardenPatchGenerator.getPumpkinPattern());

        // In a region of the given radius
        FlatRegion region = new CuboidRegion(
                getWorld(), // Causes clamping of Y range
                position.add(-apothem, -5, -apothem),
                position.add(apothem, 10, apothem));
        double density = 0.02;

        GroundFunction ground = new GroundFunction(this, generator);
        LayerVisitor visitor = new LayerVisitor(region, minimumBlockY(region), maximumBlockY(region), ground);
        visitor.setMask(new NoiseFilter2D(new RandomNoise(), density));
        OperationHelper.completeLegacy(visitor);
        return ground.getAffected();
    }

    /**
     * Makes a forest.
     *
     * @param basePos
     * @param size
     * @param density
     * @param treeGenerator
     * @return number of trees created
     * @throws MaxChangedBlocksException
     */
    public int makeForest(Vector basePos, int size, double density,
            TreeGenerator treeGenerator) throws MaxChangedBlocksException {
        int affected = 0;

        for (int x = basePos.getBlockX() - size; x <= basePos.getBlockX()
                + size; ++x) {
            for (int z = basePos.getBlockZ() - size; z <= basePos.getBlockZ()
                    + size; ++z) {
                // Don't want to be in the ground
                if (!getBlock(new Vector(x, basePos.getBlockY(), z)).isAir()) {
                    continue;
                }
                // The gods don't want a tree here
                if (Math.random() >= density) {
                    continue;
                } // def 0.05

                for (int y = basePos.getBlockY(); y >= basePos.getBlockY() - 10; --y) {
                    // Check if we hit the ground
                    int t = getBlock(new Vector(x, y, z)).getType();
                    if (t == BlockID.GRASS || t == BlockID.DIRT) {
                        treeGenerator.generate(this, new Vector(x, y + 1, z));
                        ++affected;
                        break;
                    } else if (t == BlockID.SNOW) {
                        setBlock(new Vector(x, y, z), new BaseBlock(BlockID.AIR));
                    } else if (t != BlockID.AIR) { // Trees won't grow on this!
                        break;
                    }
                }
            }
        }

        return affected;
    }

    /**
     * Get the block distribution inside a region.
     *
     * @param region
     * @return
     */
    public List<Countable<Integer>> getBlockDistribution(Region region) {
        List<Countable<Integer>> distribution = new ArrayList<Countable<Integer>>();
        Map<Integer, Countable<Integer>> map = new HashMap<Integer, Countable<Integer>>();

        if (region instanceof CuboidRegion) {
            // Doing this for speed
            Vector min = region.getMinimumPoint();
            Vector max = region.getMaximumPoint();

            int minX = min.getBlockX();
            int minY = min.getBlockY();
            int minZ = min.getBlockZ();
            int maxX = max.getBlockX();
            int maxY = max.getBlockY();
            int maxZ = max.getBlockZ();

            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        Vector pt = new Vector(x, y, z);

                        int id = getBlockType(pt);

                        if (map.containsKey(id)) {
                            map.get(id).increment();
                        } else {
                            Countable<Integer> c = new Countable<Integer>(id, 1);
                            map.put(id, c);
                            distribution.add(c);
                        }
                    }
                }
            }
        } else {
            for (Vector pt : region) {
                int id = getBlockType(pt);

                if (map.containsKey(id)) {
                    map.get(id).increment();
                } else {
                    Countable<Integer> c = new Countable<Integer>(id, 1);
                    map.put(id, c);
                }
            }
        }

        Collections.sort(distribution);
        // Collections.reverse(distribution);

        return distribution;
    }

    /**
     * Get the block distribution (with data values) inside a region.
     *
     * @param region
     * @return
     */
    // TODO reduce code duplication - probably during ops-redux
    public List<Countable<BaseBlock>> getBlockDistributionWithData(Region region) {
        List<Countable<BaseBlock>> distribution = new ArrayList<Countable<BaseBlock>>();
        Map<BaseBlock, Countable<BaseBlock>> map = new HashMap<BaseBlock, Countable<BaseBlock>>();

        if (region instanceof CuboidRegion) {
            // Doing this for speed
            Vector min = region.getMinimumPoint();
            Vector max = region.getMaximumPoint();

            int minX = min.getBlockX();
            int minY = min.getBlockY();
            int minZ = min.getBlockZ();
            int maxX = max.getBlockX();
            int maxY = max.getBlockY();
            int maxZ = max.getBlockZ();

            for (int x = minX; x <= maxX; ++x) {
                for (int y = minY; y <= maxY; ++y) {
                    for (int z = minZ; z <= maxZ; ++z) {
                        Vector pt = new Vector(x, y, z);

                        BaseBlock blk = new BaseBlock(getBlockType(pt), getBlockData(pt));

                        if (map.containsKey(blk)) {
                            map.get(blk).increment();
                        } else {
                            Countable<BaseBlock> c = new Countable<BaseBlock>(blk, 1);
                            map.put(blk, c);
                            distribution.add(c);
                        }
                    }
                }
            }
        } else {
            for (Vector pt : region) {
                BaseBlock blk = new BaseBlock(getBlockType(pt), getBlockData(pt));

                if (map.containsKey(blk)) {
                    map.get(blk).increment();
                } else {
                    Countable<BaseBlock> c = new Countable<BaseBlock>(blk, 1);
                    map.put(blk, c);
                }
            }
        }

        Collections.sort(distribution);
        // Collections.reverse(distribution);

        return distribution;
    }

    public int makeShape(final Region region, final Vector zero, final Vector unit, final Pattern pattern, final String expressionString, final boolean hollow) throws ExpressionException, MaxChangedBlocksException {
        final Expression expression = Expression.compile(expressionString, "x", "y", "z", "type", "data");
        expression.optimize();

        final RValue typeVariable = expression.getVariable("type", false);
        final RValue dataVariable = expression.getVariable("data", false);

        final WorldEditExpressionEnvironment environment = new WorldEditExpressionEnvironment(this, unit, zero);
        expression.setEnvironment(environment);

        final ArbitraryShape shape = new ArbitraryShape(region) {
            @Override
            protected BaseBlock getMaterial(int x, int y, int z, BaseBlock defaultMaterial) {
                final Vector current = new Vector(x, y, z);
                environment.setCurrentBlock(current);
                final Vector scaled = current.subtract(zero).divide(unit);

                try {
                    if (expression.evaluate(scaled.getX(), scaled.getY(), scaled.getZ(), defaultMaterial.getType(), defaultMaterial.getData()) <= 0) {
                        return null;
                    }

                    return new BaseBlock((int) typeVariable.getValue(), (int) dataVariable.getValue());
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }
        };

        return shape.generate(this, pattern, hollow);
    }

    public int deformRegion(final Region region, final Vector zero, final Vector unit, final String expressionString) throws ExpressionException, MaxChangedBlocksException {
        final Expression expression = Expression.compile(expressionString, "x", "y", "z");
        expression.optimize();

        final RValue x = expression.getVariable("x", false);
        final RValue y = expression.getVariable("y", false);
        final RValue z = expression.getVariable("z", false);

        final WorldEditExpressionEnvironment environment = new WorldEditExpressionEnvironment(this, unit, zero);
        expression.setEnvironment(environment);

        final DoubleArrayList<BlockVector, BaseBlock> queue = new DoubleArrayList<BlockVector, BaseBlock>(false);

        for (BlockVector position : region) {
            // offset, scale
            final Vector scaled = position.subtract(zero).divide(unit);

            // transform
            expression.evaluate(scaled.getX(), scaled.getY(), scaled.getZ());

            final BlockVector sourcePosition = environment.toWorld(x.getValue(), y.getValue(), z.getValue());

            // read block from world
            // TODO: use getBlock here once the reflection is out of the way
            final BaseBlock material = new BaseBlock(world.getBlockType(sourcePosition), world.getBlockData(sourcePosition));

            // queue operation
            queue.put(position, material);
        }

        int affected = 0;
        for (Map.Entry<BlockVector, BaseBlock> entry : queue) {
            BlockVector position = entry.getKey();
            BaseBlock material = entry.getValue();

            // set at new position
            if (setBlock(position, material)) {
                ++affected;
            }
        }

        return affected;
    }

    private static final Vector[] recurseDirections = {
        PlayerDirection.NORTH.vector(),
        PlayerDirection.EAST.vector(),
        PlayerDirection.SOUTH.vector(),
        PlayerDirection.WEST.vector(),
        PlayerDirection.UP.vector(),
        PlayerDirection.DOWN.vector(),
    };

    /**
     * Hollows out the region (Semi-well-defined for non-cuboid selections).
     *
     * @param region the region to hollow out.
     * @param thickness the thickness of the shell to leave (manhattan distance)
     * @param pattern The block pattern to use
     *
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int hollowOutRegion(Region region, int thickness, Pattern pattern) throws MaxChangedBlocksException {
        int affected = 0;

        final Set<BlockVector> outside = new HashSet<BlockVector>();

        final Vector min = region.getMinimumPoint();
        final Vector max = region.getMaximumPoint();

        final int minX = min.getBlockX();
        final int minY = min.getBlockY();
        final int minZ = min.getBlockZ();
        final int maxX = max.getBlockX();
        final int maxY = max.getBlockY();
        final int maxZ = max.getBlockZ();

        for (int x = minX; x <= maxX; ++x) {
            for (int y = minY; y <= maxY; ++y) {
                recurseHollow(region, new BlockVector(x, y, minZ), outside);
                recurseHollow(region, new BlockVector(x, y, maxZ), outside);
            }
        }

        for (int y = minY; y <= maxY; ++y) {
            for (int z = minZ; z <= maxZ; ++z) {
                recurseHollow(region, new BlockVector(minX, y, z), outside);
                recurseHollow(region, new BlockVector(maxX, y, z), outside);
            }
        }

        for (int z = minZ; z <= maxZ; ++z) {
            for (int x = minX; x <= maxX; ++x) {
                recurseHollow(region, new BlockVector(x, minY, z), outside);
                recurseHollow(region, new BlockVector(x, maxY, z), outside);
            }
        }

        for (int i = 1; i < thickness; ++i) {
            final Set<BlockVector> newOutside = new HashSet<BlockVector>();
            outer: for (BlockVector position : region) {
                for (Vector recurseDirection: recurseDirections) {
                    BlockVector neighbor = position.add(recurseDirection).toBlockVector();

                    if (outside.contains(neighbor)) {
                        newOutside.add(position);
                        continue outer;
                    }
                }
            }

            outside.addAll(newOutside);
        }

        outer: for (BlockVector position : region) {
            for (Vector recurseDirection: recurseDirections) {
                BlockVector neighbor = position.add(recurseDirection).toBlockVector();

                if (outside.contains(neighbor)) {
                    continue outer;
                }
            }

            if (setBlock(position, pattern.next(position))) {
                ++affected;
            }
        }

        return affected;
    }

    /**
     * Draws a line (out of blocks) between two vectors.
     *
     * @param pattern The block pattern used to draw the line.
     * @param pos1 One of the points that define the line.
     * @param pos2 The other point that defines the line.
     * @param radius The radius (thickness) of the line.
     * @param filled If false, only a shell will be generated.
     *
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int drawLine(Pattern pattern, Vector pos1, Vector pos2, double radius, boolean filled)
            throws MaxChangedBlocksException {

        Set<Vector> vset = new HashSet<Vector>();
        boolean notdrawn = true;

        int x1 = pos1.getBlockX(), y1 = pos1.getBlockY(), z1 = pos1.getBlockZ();
        int x2 = pos2.getBlockX(), y2 = pos2.getBlockY(), z2 = pos2.getBlockZ();
        int tipx = x1, tipy = y1, tipz = z1;
        int dx = Math.abs(x2 - x1), dy = Math.abs(y2 - y1), dz = Math.abs(z2 - z1);

        if (dx + dy + dz == 0) {
            vset.add(new Vector(tipx, tipy, tipz));
            notdrawn = false;
        }

        if (Math.max(Math.max(dx, dy), dz) == dx && notdrawn) {
            for (int domstep = 0; domstep <= dx; domstep++) {
                tipx = x1 + domstep * (x2 - x1 > 0 ? 1 : -1);
                tipy = (int) Math.round(y1 + domstep * ((double) dy) / ((double) dx) * (y2 - y1 > 0 ? 1 : -1));
                tipz = (int) Math.round(z1 + domstep * ((double) dz) / ((double) dx) * (z2 - z1 > 0 ? 1 : -1));

                vset.add(new Vector(tipx, tipy, tipz));
            }
            notdrawn = false;
        }

        if (Math.max(Math.max(dx, dy), dz) == dy && notdrawn) {
            for (int domstep = 0; domstep <= dy; domstep++) {
                tipy = y1 + domstep * (y2 - y1 > 0 ? 1 : -1);
                tipx = (int) Math.round(x1 + domstep * ((double) dx) / ((double) dy) * (x2 - x1 > 0 ? 1 : -1));
                tipz = (int) Math.round(z1 + domstep * ((double) dz) / ((double) dy) * (z2 - z1 > 0 ? 1 : -1));

                vset.add(new Vector(tipx, tipy, tipz));
            }
            notdrawn = false;
        }

        if (Math.max(Math.max(dx, dy), dz) == dz && notdrawn) {
            for (int domstep = 0; domstep <= dz; domstep++) {
                tipz = z1 + domstep * (z2 - z1 > 0 ? 1 : -1);
                tipy = (int) Math.round(y1 + domstep * ((double) dy) / ((double) dz) * (y2-y1>0 ? 1 : -1));
                tipx = (int) Math.round(x1 + domstep * ((double) dx) / ((double) dz) * (x2-x1>0 ? 1 : -1));

                vset.add(new Vector(tipx, tipy, tipz));
            }
            notdrawn = false;
        }

        vset = getBallooned(vset, radius);
        if (!filled) {
            vset = getHollowed(vset);
        }
        return setBlocks(vset, pattern);
    }

    /**
     * Draws a spline (out of blocks) between specified vectors.
     *
     * @param pattern The block pattern used to draw the spline.
     * @param nodevectors The list of vectors to draw through.
     * @param tension The tension of every node.
     * @param bias The bias of every node.
     * @param continuity The continuity of every node.
     * @param quality The quality of the spline. Must be greater than 0.
     * @param radius The radius (thickness) of the spline.
     * @param filled If false, only a shell will be generated.
     *
     * @return number of blocks affected
     * @throws MaxChangedBlocksException
     */
    public int drawSpline(Pattern pattern, List<Vector> nodevectors, double tension, double bias, double continuity, double quality, double radius, boolean filled)
            throws MaxChangedBlocksException {

        Set<Vector> vset = new HashSet<Vector>();
        List<Node> nodes = new ArrayList(nodevectors.size());

        Interpolation interpol = new KochanekBartelsInterpolation();

        for (int loop = 0; loop < nodevectors.size(); loop++) {
            Node n = new Node(nodevectors.get(loop));
            n.setTension(tension);
            n.setBias(bias);
            n.setContinuity(continuity);
            nodes.add(n);
        }

        interpol.setNodes(nodes);
        double splinelength = interpol.arcLength(0, 1);
        for (double loop = 0; loop <= 1; loop += 1D / splinelength / quality) {
            Vector tipv = interpol.getPosition(loop);
            int tipx = (int) Math.round(tipv.getX());
            int tipy = (int) Math.round(tipv.getY());
            int tipz = (int) Math.round(tipv.getZ());

            vset.add(new Vector(tipx, tipy, tipz));
        }

        vset = getBallooned(vset, radius);
        if (!filled) {
            vset = getHollowed(vset);
        }
        return setBlocks(vset, pattern);
    }

    private static double hypot(double... pars) {
        double sum = 0;
        for (double d : pars) {
            sum += Math.pow(d, 2);
        }
        return Math.sqrt(sum);
    }

    private static Set<Vector> getBallooned(Set<Vector> vset, double radius) {
        Set<Vector> returnset = new HashSet<Vector>();
        int ceilrad = (int) Math.ceil(radius);

        for (Vector v : vset) {
            int tipx = v.getBlockX(), tipy = v.getBlockY(), tipz = v.getBlockZ();

            for (int loopx = tipx - ceilrad; loopx <= tipx + ceilrad; loopx++) {
                for (int loopy = tipy - ceilrad; loopy <= tipy + ceilrad; loopy++) {
                    for (int loopz = tipz - ceilrad; loopz <= tipz + ceilrad; loopz++) {
                        if (hypot(loopx - tipx, loopy - tipy, loopz - tipz) <= radius) {
                            returnset.add(new Vector(loopx, loopy, loopz));
                        }
                    }
                }
            }
        }
        return returnset;
    }

    private static Set<Vector> getHollowed(Set<Vector> vset) {
        Set<Vector> returnset = new HashSet<Vector>();
        for (Vector v : vset) {
            double x = v.getX(), y = v.getY(), z = v.getZ();
            if (!(vset.contains(new Vector(x + 1, y, z)) &&
            vset.contains(new Vector(x - 1, y, z)) &&
            vset.contains(new Vector(x, y + 1, z)) &&
            vset.contains(new Vector(x, y - 1, z)) &&
            vset.contains(new Vector(x, y, z + 1)) &&
            vset.contains(new Vector(x, y, z - 1)))) {
                returnset.add(v);
            }
        }
        return returnset;
    }

    private int setBlocks(Set<Vector> vset, Pattern pattern)
        throws MaxChangedBlocksException {

        int affected = 0;
        for (Vector v : vset) {
            affected += setBlock(v, pattern) ? 1 : 0;
        }
        return affected;
    }

    private void recurseHollow(Region region, BlockVector origin, Set<BlockVector> outside) {
        final LinkedList<BlockVector> queue = new LinkedList<BlockVector>();
        queue.addLast(origin);

        while (!queue.isEmpty()) {
            final BlockVector current = queue.removeFirst();
            if (!BlockType.canPassThrough(getBlockType(current), getBlockData(current))) {
                continue;
            }

            if (!outside.add(current)) {
                continue;
            }

            if (!region.contains(current)) {
                continue;
            }

            for (Vector recurseDirection: recurseDirections) {
                queue.addLast(current.add(recurseDirection).toBlockVector());
            }
        } // while
    }

    public int makeBiomeShape(final Region region, final Vector zero, final Vector unit, final BiomeType biomeType, final String expressionString, final boolean hollow) throws ExpressionException, MaxChangedBlocksException {
        final Vector2D zero2D = zero.toVector2D();
        final Vector2D unit2D = unit.toVector2D();

        final Expression expression = Expression.compile(expressionString, "x", "z");
        expression.optimize();

        final EditSession editSession = this;
        final WorldEditExpressionEnvironment environment = new WorldEditExpressionEnvironment(editSession, unit, zero);
        expression.setEnvironment(environment);

        final ArbitraryBiomeShape shape = new ArbitraryBiomeShape(region) {
            @Override
            protected BiomeType getBiome(int x, int z, BiomeType defaultBiomeType) {
                final Vector2D current = new Vector2D(x, z);
                environment.setCurrentBlock(current.toVector(0));
                final Vector2D scaled = current.subtract(zero2D).divide(unit2D);

                try {
                    if (expression.evaluate(scaled.getX(), scaled.getZ()) <= 0) {
                        return null;
                    }

                    // TODO: Allow biome setting via a script variable (needs BiomeType<->int mapping)
                    return defaultBiomeType;
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }
        };

        return shape.generate(this, biomeType, hollow);
    }
}
