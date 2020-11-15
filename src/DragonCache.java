import java.util.*;

public class DragonCache extends Cache {
  public DragonCache(Bus bus, int cacheSize, int associativity, int blockSize) {
    super(bus, cacheSize, associativity, blockSize);
  }

  @Override
  protected void prRd(int address) {
    if (cacheState != CacheState.PENDING_READ)
      throw new RuntimeException("Called prRd when not in PENDING_READ cachestate");
    CacheSet set = getSet(address);
    int tag = getTag(address);
    if (set.contains(tag)) {
      updateCacheStatistics(Optional.of(set.getState(tag)));
      set.use(tag);
      processor.unstall();
      cacheState = CacheState.READY;
      return;
    }
    updateCacheStatistics(Optional.empty());
    bus.reserve(this);
    cacheState = CacheState.READING_WAITBUS;
  }

  @Override
  protected void prWr(int address) {
    if (cacheState != CacheState.PENDING_WRITE)
      throw new RuntimeException("Called prWr when not in PENDING_WRITE cachestate");
    CacheSet set = getSet(address);
    int tag = getTag(address);
    Optional<BlockState> stateForStat = Optional.empty();
    if (set.contains(tag)) {
      BlockState blockState = set.getState(tag);
      stateForStat = Optional.of(blockState);
      if (blockState == BlockState.DRAGON_EXCLUSIVE || blockState == BlockState.DRAGON_MODIFIED) {
        updateCacheStatistics(stateForStat);
        set.update(tag, BlockState.DRAGON_MODIFIED);
        set.use(tag);
        processor.unstall();
        cacheState = CacheState.READY;
        return;
      }
    }
    updateCacheStatistics(stateForStat);
    bus.reserve(this);
    cacheState = CacheState.WRITING_WAITBUS;
  }

  @Override
  public BusTransaction accessBus() {
    CacheSet set = getSet(pendingAddress);
    int tag = getTag(pendingAddress);

    // Read miss
    if (cacheState == CacheState.READING_WAITBUS) {
      cacheState = CacheState.READING;
      // Capacity available
      if (!set.isFull())
        return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);

      // Conflict miss
      int evictionTargetTag = set.getEvictionTargetTag();
      BlockState evictionTargetState = set.getState(evictionTargetTag);
      int evictionTargetAddress = getAddress(evictionTargetTag, getSetIndex(pendingAddress));
      // Need to flush evictee
      if (evictionTargetState == BlockState.DRAGON_MODIFIED
          || evictionTargetState == BlockState.DRAGON_SHARED_MODIFIED) {
        cacheState = CacheState.READING_PENDING_FLUSH;
        return new BusTransaction(Transition.FLUSH, evictionTargetAddress, blockSize);
      }
      set.evict();
      return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);
    }

    if (cacheState == CacheState.WRITING_WAITBUS) {
      cacheState = CacheState.WRITING;
      // Write hit
      if (set.contains(tag))
        return new BusTransaction(Transition.BUS_UPD, pendingAddress, blockSize);

      // Write miss, capacity available
      if (!set.isFull())
        return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);

      // Conflict miss
      int evictionTargetTag = set.getEvictionTargetTag();
      BlockState evictionTargetState = set.getState(evictionTargetTag);
      int evictionTargetAddress = getAddress(evictionTargetTag, getSetIndex(pendingAddress));
      // Need to flush evictee
      if (evictionTargetState == BlockState.DRAGON_MODIFIED
          || evictionTargetState == BlockState.DRAGON_SHARED_MODIFIED) {
        cacheState = CacheState.WRITING_PENDING_FLUSH;
        return new BusTransaction(Transition.FLUSH, evictionTargetAddress, blockSize);
      }
      set.evict();
      return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);
    }

    throw new RuntimeException(
        "Accessing bus when not in a WAITBUS state. State is: " + cacheState + " in processor: " + processor);
  }

  @Override
  public void exitBus(BusTransaction result) {
    int address = result.getAddress();
    int tag = getTag(address);
    boolean shared = result.getShared();
    CacheSet set = getSet(address);
    switch (result.getTransition()) {
      // Read/Write miss
      case BUS_RD:
        BlockState newBlockState;
        switch (cacheState) {
          case READING:
            newBlockState = shared ? BlockState.DRAGON_SHARED_CLEAN : BlockState.DRAGON_EXCLUSIVE;
            set.add(tag, newBlockState);
            cacheState = CacheState.READY;
            processor.unstall();
            break;
          case WRITING:
            newBlockState = shared ? BlockState.DRAGON_SHARED_MODIFIED : BlockState.DRAGON_MODIFIED;
            set.add(tag, newBlockState);

            // Do the BusUpd next cycle
            if (newBlockState == BlockState.DRAGON_SHARED_MODIFIED) {
              bus.reserveToFront(this);
              cacheState = CacheState.WRITING_WAITBUS;
            } else {
              cacheState = CacheState.READY;
              processor.unstall();
            }
            break;
          default:
            throw new RuntimeException("Did a BusRd when state not READING or WRITING");
        }
        break;
      case BUS_UPD:
        // Must be write hit, already in cache
        set.update(tag, shared ? BlockState.DRAGON_SHARED_MODIFIED : BlockState.DRAGON_MODIFIED);
        set.use(tag);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case FLUSH:
        set.invalidate(tag);
        bus.reserve(this);
        switch (cacheState) {
          case READING_PENDING_FLUSH:
            cacheState = CacheState.READING_WAITBUS;
            break;
          case WRITING_PENDING_FLUSH:
            cacheState = CacheState.WRITING_WAITBUS;
            break;
          default:
            throw new RuntimeException("Did a Flush when state not pending flush");
        }
        break;
      default:
        throw new RuntimeException("Exiting bus with invalid Dragon transaction type: " + result.getTransition());
    }
  }

  @Override
  public Optional<BusTransaction> snoop(BusTransaction transaction) {
    int address = transaction.getAddress();
    int tag = getTag(address);
    CacheSet set = getSet(address);

    if (!set.contains(tag))
      return Optional.empty();

    BlockState blockState = set.getState(tag);
    switch (transaction.getTransition()) {
      case BUS_RD:
        switch (blockState) {
          case DRAGON_EXCLUSIVE:
            set.update(tag, BlockState.DRAGON_SHARED_CLEAN);
            // FALLTHROUGH
          case DRAGON_SHARED_CLEAN:
            return Optional.empty();
          case DRAGON_MODIFIED:
            set.update(tag, BlockState.DRAGON_SHARED_MODIFIED);
            // FALLTHROUGH
          case DRAGON_SHARED_MODIFIED:
            return Optional.of(new BusTransaction(Transition.FLUSH, address, blockSize));
          default:
            break;
        }
      case BUS_UPD:
        if (blockState != BlockState.DRAGON_SHARED_CLEAN && blockState != BlockState.DRAGON_SHARED_MODIFIED)
          throw new RuntimeException("Snooped BusUpd for block that is not Shared");
        set.update(tag, BlockState.DRAGON_SHARED_CLEAN);
        return Optional.empty();
      case FLUSH:
        return Optional.empty();
      default:
        throw new RuntimeException("Snooped invalid Dragon transaction type: " + transaction.getTransition());
    }

  }

  @Override
  protected void updateCacheStatistics(Optional<BlockState> state) {
    cacheNumTotalAccesses++;
    if (state.isEmpty()) {
      cacheNumMisses++;
      return;
    }
    cacheNumHits++;
    switch (state.get()) {
      case DRAGON_MODIFIED:
      case DRAGON_EXCLUSIVE:
        cacheNumPrivateAccesses++;
        break;
      case DRAGON_SHARED_MODIFIED:
      case DRAGON_SHARED_CLEAN:
        cacheNumSharedAccesses++;
        break;
      default:
        break;
    }

  };

}
