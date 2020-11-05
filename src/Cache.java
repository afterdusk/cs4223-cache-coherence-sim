import java.util.*;

public abstract class Cache {
  protected static final int WORD_SIZE = 4;

  protected Processor processor;
  protected Bus bus;
  protected int blockSize;
  protected int numSets;
  protected List<CacheSet> sets;

  public Cache(Bus bus, int cacheSize, int associativity, int blockSize) {
    this.bus = bus;
    this.blockSize = blockSize;
    this.numSets = cacheSize / (blockSize * associativity);
    this.sets = new ArrayList<>();
    for (int i = 0; i < this.numSets; i++) {
      this.sets.add(new CacheSet(associativity));
    }
    bus.registerCache(this);
  }

  public void registerProcessor(Processor processor) {
    this.processor = processor;
  }

  abstract void tick();

  abstract boolean contains(int address);

  abstract void read(int address);

  abstract void write(int address);

  abstract BusTransaction accessBus();

  abstract void exitBus(BusTransaction result);

  abstract BusTransaction snoop(BusTransaction transaction);

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
}
