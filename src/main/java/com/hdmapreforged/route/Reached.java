package com.hdmapreforged.route;

import lombok.*;

/**
 * The nodes a search reached, as bits per {@link NodeTable#block}: from any of them the search's own ways reach nothing
 * more (what it could get to from there it got to from its start). Immutable once made.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class Reached
{
    private final IntMap blocks;
    private final long[][] bits;

    boolean contains(int node)
    {
        int block = node < 0 ? IntMap.MISSING : blocks.get(NodeTable.block(node));
        int i = NodeTable.index(node);
        return block != IntMap.MISSING && (bits[block][i >> 6] & 1L << i) != 0;
    }
}
