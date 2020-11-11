import java.util.*;

/**
 * CacheSet represents a cache set with a LRU eviction scheme. State is an enum
 * representing the states of a cache coherence protocol.
 */
public class CacheSet {
  private int numBlocks;
  private Map<Integer, BlockState> blocks;

  CacheSet(int associativity) {
    this.numBlocks = associativity;
    this.blocks = new LinkedHashMap<Integer, BlockState>(this.numBlocks);
  }

  public boolean contains(int tag) {
    return blocks.containsKey(tag);
  }

  public BlockState getState(int tag) {
    if (!contains(tag)) {
      throw new RuntimeException("CacheSet does not contain tag, unable to get state");
    }
    return blocks.get(tag);
  }

  public void add(int tag, BlockState state) {
    if (contains(tag)) {
      throw new RuntimeException("CacheSet already contains tag, unable to add");
    }
    blocks.put(tag, state);
  }

  public int getEvictionTargetTag() {
    if (!isFull()) {
      throw new RuntimeException("Requesting eviction target when cache set not full");
    }
    return blocks.keySet().iterator().next();
  }

  public void evict() {
    if (!isFull()) {
      throw new RuntimeException("Evicting when cache set not full");
    }
    blocks.remove(getEvictionTargetTag());
  }

  public void use(int tag) {
    if (!contains(tag)) {
      throw new RuntimeException("CacheSet does not contain tag, unable to use");
    }
    BlockState state = blocks.remove(tag);
    blocks.put(tag, state);
  }

  public void update(int tag, BlockState state) {
    if (!contains(tag)) {
      throw new RuntimeException("CacheSet does not contain tag, unable to update");
    }
    blocks.put(tag, state);
  }

  public void invalidate(int tag) {
    blocks.remove(tag);
  }

  public boolean isFull() {
    return blocks.size() == numBlocks;
  }
}
