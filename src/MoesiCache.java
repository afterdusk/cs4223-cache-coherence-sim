import java.util.*;

public class MoesiCache extends Cache {
  public MoesiCache(Bus bus, int cacheSize, int associativity, int blockSize) {
    super(bus, cacheSize, associativity, blockSize);
  }

  @Override
  void tick() {
    if (hoggedByBus) {
      return;
    }
    switch (cacheState) {
      case PENDING_READ:
        prRd(pendingAddress);
        break;
      case PENDING_WRITE:
        prWr(pendingAddress);
        break;
      case READY:
      case READING_WAITBUS:
      case READING_PENDING_FLUSH:
      case READING:
      case WRITING_WAITBUS:
      case WRITING_PENDING_FLUSH:
      case WRITING:
        break;
    }
  }

  @Override
  public void read(int address) {
    if (cacheState != CacheState.READY) {
      throw new RuntimeException("Read operation called when cache is not in READY state");
    }
    pendingAddress = address;
    cacheState = CacheState.PENDING_READ;
  }

  @Override
  public void write(int address) {
    if (cacheState != CacheState.READY) {
      throw new RuntimeException("Write operation called when cache is not in READY state");
    }
    pendingAddress = address;
    cacheState = CacheState.PENDING_WRITE;
  }

  @Override
  public BusTransaction accessBus() {
    CacheSet set = getSet(pendingAddress);

    CacheState pendingState;
    BusTransaction pendingTransaction;
    switch (cacheState) {
      case READING_WAITBUS: {
        // Cold miss
        if (!set.isFull()) {
          pendingState = CacheState.READING;
          pendingTransaction = new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);
          break;
        }
        // Conflict miss
        int evictionTargetTag = set.getEvictionTargetTag();
        BlockState evictionTargetState = set.getState(evictionTargetTag);
        int evictionTargetAddress = getAddress(evictionTargetTag, getSetIndex(pendingAddress));
        set.evict();

        if (evictionTargetState == BlockState.MOESI_MODIFIED || evictionTargetState == BlockState.MOESI_OWNED) {
          // Need to flush evictee
          pendingState = CacheState.READING_PENDING_FLUSH;
          pendingTransaction = new BusTransaction(Transition.FLUSH, evictionTargetAddress, blockSize);
        } else {
          // No need to flush evictee
          pendingState = CacheState.READING;
          pendingTransaction = new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);
        }
        break;
      }
      case WRITING_WAITBUS: {
        // Contains element, but need to invalidate
        int tag = getTag(pendingAddress);
        if (set.contains(tag)) {
          if (set.getState(tag) == BlockState.MOESI_SHARED || set.getState(tag) == BlockState.MOESI_OWNED) {
            pendingState = CacheState.WRITING;
            pendingTransaction = new BusTransaction(Transition.BUS_UPGR, pendingAddress, 0);
            break;
          }
        }
        // Cold miss
        if (!set.isFull()) {
          pendingState = CacheState.WRITING;
          pendingTransaction = new BusTransaction(Transition.BUS_RD_X, pendingAddress, blockSize);
          break;
        }
        // Conflict miss
        int evictionTargetTag = set.getEvictionTargetTag();
        BlockState evictionTargetState = set.getState(evictionTargetTag);
        int evictionTargetAddress = getAddress(evictionTargetTag, getSetIndex(pendingAddress));
        set.evict();

        if (evictionTargetState == BlockState.MOESI_MODIFIED || evictionTargetState == BlockState.MOESI_OWNED) {
          // Need to flush evictee
          pendingState = CacheState.WRITING_PENDING_FLUSH;
          pendingTransaction = new BusTransaction(Transition.FLUSH, evictionTargetAddress, blockSize);
        } else {
          // No need to flush evictee
          pendingState = CacheState.WRITING;
          pendingTransaction = new BusTransaction(Transition.BUS_RD_X, pendingAddress, blockSize);
        }
        break;
      }
      default:
        throw new RuntimeException("Cache in invalid state when accessBus() called");
    }
    cacheState = pendingState;
    return pendingTransaction;
  }

  @Override
  public void exitBus(BusTransaction result) {
    int address = result.getAddress();
    int tag = getTag(address);
    CacheSet set = getSet(address);
    switch (result.getTransition()) {
      case BUS_RD:
        if (cacheState != CacheState.READING) {
          throw new RuntimeException("Did a BusRd when state is not READING");
        }
        BlockState newBlockState = result.getShared() ? BlockState.MOESI_SHARED : BlockState.MOESI_EXCLUSIVE;
        set.add(tag, newBlockState);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case BUS_RD_X:
        if (cacheState != CacheState.WRITING) {
          throw new RuntimeException("Did a BusRdX when state is not WRITING");
        }
        set.add(tag, BlockState.MOESI_MODIFIED);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case BUS_UPGR:
        if (cacheState != CacheState.WRITING) {
          throw new RuntimeException("Did a BusUpgr when state is not WRITING");
        }
        set.update(tag, BlockState.MOESI_MODIFIED);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case FLUSH:
        if (cacheState == CacheState.READING_PENDING_FLUSH) {
          bus.reserve(this);
          cacheState = CacheState.READING_WAITBUS;
        }
        if (cacheState == CacheState.WRITING_PENDING_FLUSH) {
          bus.reserve(this);
          cacheState = CacheState.WRITING_WAITBUS;
        }
        break;
      default:
        throw new RuntimeException("Unexpected transaction received in exitBus()");
    }
  }

  @Override
  public Optional<BusTransaction> snoop(BusTransaction transaction) {
    int address = transaction.getAddress();

    Optional<BusTransaction> response = Optional.empty();
    switch (transaction.getTransition()) {
      case BUS_RD:
        response = busRd(address);
        break;
      case BUS_RD_X:
        response = busRdX(address);
        break;
      case BUS_UPGR:
        busUpgr(address);
        break;
      default:
        throw new RuntimeException("Invalid transaction detected in snoop()");
    }
    return response;
  }

  @Override
  public void updateCacheStatistics(Optional<BlockState> state) {
    cacheNumTotalAccesses++;
    switch (state.get()) {
      case MOESI_MODIFIED:
      case MOESI_EXCLUSIVE:
        cacheNumHits++;
        cacheNumPrivateAccesses++;
        break;
      case MOESI_OWNED:
      case MOESI_SHARED:
        cacheNumHits++;
        cacheNumSharedAccesses++;
        break;
      case MOESI_INVALID:
        cacheNumMisses++;
        break;
      default:
        break;
    }
  }

  private void prRd(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    BlockState state = BlockState.MOESI_INVALID;
    if (set.contains(tag)) {
      state = set.getState(tag);
    }
    updateCacheStatistics(Optional.of(state));
    switch (state) {
      case MOESI_MODIFIED:
      case MOESI_OWNED:
      case MOESI_EXCLUSIVE:
      case MOESI_SHARED:
        set.use(tag);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case MOESI_INVALID:
        bus.reserve(this);
        cacheState = CacheState.READING_WAITBUS;
        break;
      default:
        throw new RuntimeException("Invalid state detected in MOESI cache: " + set.getState(tag));
    }
  }

  private void prWr(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    BlockState state = BlockState.MOESI_INVALID;
    if (set.contains(tag)) {
      state = set.getState(tag);
    }
    updateCacheStatistics(Optional.of(state));
    switch (state) {
      case MOESI_EXCLUSIVE:
        set.update(tag, BlockState.MOESI_MODIFIED);
      case MOESI_MODIFIED:
        set.use(tag);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case MOESI_OWNED:
      case MOESI_SHARED:
      case MOESI_INVALID:
        bus.reserve(this);
        cacheState = CacheState.WRITING_WAITBUS;
        break;
      default:
        throw new RuntimeException("Invalid state detected in MOESI cache: " + set.getState(tag));
    }
  }

  private Optional<BusTransaction> busRd(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    if (!set.contains(tag)) {
      return Optional.empty();
    }

    Optional<BusTransaction> response = Optional.empty();
    switch (set.getState(tag)) {
      case MOESI_MODIFIED:
        set.update(tag, BlockState.MOESI_OWNED);
      case MOESI_OWNED:
        response = Optional.of(new BusTransaction(Transition.FLUSH_OPT, address, blockSize));
        break;
      case MOESI_EXCLUSIVE:
        set.update(tag, BlockState.MOESI_SHARED);
      case MOESI_SHARED:
        response = Optional.of(new BusTransaction(Transition.FLUSH_OPT, address, blockSize));
        break;
      case MOESI_INVALID:
      default:
        throw new RuntimeException("Invalid state for busRd detected in MOESI cache: " + set.getState(tag));
    }
    return response;
  }

  private Optional<BusTransaction> busRdX(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    if (!set.contains(tag)) {
      return Optional.empty();
    }

    Optional<BusTransaction> response = Optional.empty();
    switch (set.getState(tag)) {
      case MOESI_MODIFIED:
      case MOESI_OWNED:
        set.invalidate(tag);
        response = Optional.of(new BusTransaction(Transition.FLUSH, address, blockSize));
        break;
      case MOESI_EXCLUSIVE:
      case MOESI_SHARED:
        set.invalidate(tag);
        response = Optional.of(new BusTransaction(Transition.FLUSH_OPT, address, blockSize));
        break;
      case MOESI_INVALID:
      default:
        throw new RuntimeException("Invalid state for busRdX detected in MOESI cache: " + set.getState(tag));
    }
    return response;
  }

  private void busUpgr(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    if (!set.contains(tag)) {
      return;
    }

    switch (set.getState(tag)) {
      case MOESI_OWNED:
      case MOESI_SHARED:
        set.invalidate(tag);
        break;
      case MOESI_MODIFIED:
      case MOESI_EXCLUSIVE:
      case MOESI_INVALID:
      default:
        throw new RuntimeException("Invalid state for busUpgr detected in MOESI cache: " + set.getState(tag));
    }
  }
}
