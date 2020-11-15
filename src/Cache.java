import java.util.*;

public abstract class Cache {
  enum CacheState {
    READY,

    // Reading
    PENDING_READ, READING_WAITBUS, READING_PENDING_FLUSH, READING,

    // Writing
    PENDING_WRITE, WRITING_WAITBUS, WRITING_PENDING_FLUSH, WRITING,
  }

  protected static final int WORD_SIZE = 4;

  protected Processor processor;
  protected Bus bus;
  protected int blockSize;
  protected int numSets;
  protected List<CacheSet> sets;
  protected boolean hoggedByBus;

  // State variables
  protected CacheState cacheState;
  protected int pendingAddress;

  // Statistics
  protected long cacheNumTotalAccesses = 0;
  protected long cacheNumHits = 0;
  protected long cacheNumMisses = 0;
  protected long cacheNumPrivateAccesses = 0;
  protected long cacheNumSharedAccesses = 0;

  public Cache(Bus bus, int cacheSize, int associativity, int blockSize) {
    this.bus = bus;
    this.blockSize = blockSize;
    this.numSets = cacheSize / (blockSize * associativity);
    this.sets = new ArrayList<>();
    for (int i = 0; i < this.numSets; i++) {
      this.sets.add(new CacheSet(associativity));
    }
    bus.registerCache(this);
    this.hoggedByBus = false;
    this.cacheState = CacheState.READY;
  }

  public void registerProcessor(Processor processor) {
    this.processor = processor;
  }

  public boolean contains(int address) {
    return getSet(address).contains(getTag(address));
  }

  public void hog() {
    hoggedByBus = true;
  }

  public void unhog() {
    hoggedByBus = false;
  }

  public Map<String, Number> getCacheStatistics() {
    assert cacheNumHits + cacheNumMisses == cacheNumTotalAccesses;
    assert cacheNumPrivateAccesses + cacheNumSharedAccesses == cacheNumHits;

    Map<String, Number> statistics = new LinkedHashMap<>();
    statistics.put("Cache Accesses", cacheNumTotalAccesses);
    statistics.put("Cache Hits", cacheNumHits);
    statistics.put("Cache Misses", cacheNumMisses);
    statistics.put("Cache Miss Rate (%)", (float) cacheNumMisses / cacheNumTotalAccesses * 100);
    statistics.put("Private Data Accesses", cacheNumPrivateAccesses);
    statistics.put("Shared Data Accesses", cacheNumSharedAccesses);
    statistics.put("Private Data Accesses (%)", (float) cacheNumPrivateAccesses / cacheNumHits * 100);
    statistics.put("Shared Data Accesses (%)", (float) cacheNumSharedAccesses / cacheNumHits * 100);
    return statistics;
  }

  public void tick() {
    if (hoggedByBus)
      return;
    switch (cacheState) {
      case PENDING_READ:
        prRd(pendingAddress);
        break;
      case PENDING_WRITE:
        prWr(pendingAddress);
        break;
      default:
        break;
    }
  }

  public void read(int address) {
    if (cacheState != CacheState.READY) {
      throw new RuntimeException("Read operation called when cache is not in READY state");
    }
    pendingAddress = address;
    cacheState = CacheState.PENDING_READ;
  }

  public void write(int address) {
    if (cacheState != CacheState.READY) {
      throw new RuntimeException("Write operation called when cache is not in READY state");
    }
    pendingAddress = address;
    cacheState = CacheState.PENDING_WRITE;
  }

  public abstract BusTransaction accessBus();

  public abstract void exitBus(BusTransaction result);

  public abstract Optional<BusTransaction> snoop(BusTransaction transaction);

  protected abstract void prRd(int address);

  protected abstract void prWr(int address);

  protected abstract void updateCacheStatistics(Optional<BlockState> state);

  protected CacheSet getSet(int address) {
    return sets.get(getSetIndex(address));
  }

  protected int getSetIndex(int address) {
    int blockNumber = address / blockSize;
    return blockNumber % numSets;
  }

  protected int getTag(int address) {
    int blockNumber = address / blockSize;
    return blockNumber / numSets;
  }

  protected int getAddress(int tag, int setIndex) {
    int blockNumber = (tag * numSets) + setIndex;
    return blockNumber * blockSize;
  }
}
