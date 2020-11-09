import java.util.*;

public class DragonCache extends Cache {
  enum CacheState {
    READY, PENDING_READ, PENDING_WRITE, READING_PENDING_FLUSH, READING, WRITING_PENDING_FLUSH, WRITING
  }

  public CacheState state;

  int pendingAddress;

  public DragonCache(Bus bus, int cacheSize, int associativity, int blockSize) {
    super(bus, cacheSize, associativity, blockSize);
    state = CacheState.READY;
  }

  public void tick() {
    if (hoggedByBus)
      return;
    switch (state) {
      case PENDING_READ:
        prRd();
        break;
      case PENDING_WRITE:
        prWr();
        break;
      default:
        break;
    }
  }

  public void read(int address) {
    state = CacheState.PENDING_READ;
    pendingAddress = address;
  }

  public void write(int address) {
    state = CacheState.PENDING_WRITE;
    pendingAddress = address;
  }

  private void prRd() {
    if (state != CacheState.PENDING_READ)
      return;
    CacheSet set = getSet(pendingAddress);
    int tag = getTag(pendingAddress);
    if (set.contains(tag)) {
      set.use(tag);
      processor.unstall();
      state = CacheState.READY;
      return;
    }
    bus.reserve(this);
  }

  private void prWr() {
    if (state != CacheState.PENDING_WRITE)
      return;
    CacheSet set = getSet(pendingAddress);
    int tag = getTag(pendingAddress);
    if (set.contains(tag)) {
      State blockState = set.getState(tag);
      if (blockState == State.DRAGON_EXCLUSIVE || blockState == State.DRAGON_MODIFIED) {
        set.update(tag, State.DRAGON_MODIFIED);
        set.use(tag);
        processor.unstall();
        state = CacheState.READY;
        return;
      }
    }
    bus.reserve(this);
  }

  public BusTransaction accessBus() {
    CacheSet set = getSet(pendingAddress);
    int tag = getTag(pendingAddress);

    // Read miss
    if (state == CacheState.PENDING_READ) {
      state = CacheState.READING;
      // Capacity available
      if (!set.isFull())
        return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);

      // Conflict miss
      int evictionTargetTag = set.getEvictionTargetTag();
      State evictionTargetState = set.getState(evictionTargetTag);
      int evictionTargetAddress = getAddress(evictionTargetTag, getSetIndex(pendingAddress));
      // Need to flush evictee
      if (evictionTargetState == State.DRAGON_MODIFIED || evictionTargetState == State.DRAGON_SHARED_MODIFIED) {
        state = CacheState.READING_PENDING_FLUSH;
        return new BusTransaction(Transition.FLUSH, evictionTargetAddress, blockSize);
      }
      set.evict();
      return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);
    }

    if (state == CacheState.PENDING_WRITE) {
      state = CacheState.WRITING;
      // Write hit
      if (set.contains(tag))
        return new BusTransaction(Transition.BUS_UPD, pendingAddress, blockSize);

      // Write miss, capacity available
      if (!set.isFull())
        return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);

      // Conflict miss
      int evictionTargetTag = set.getEvictionTargetTag();
      State evictionTargetState = set.getState(evictionTargetTag);
      int evictionTargetAddress = getAddress(evictionTargetTag, getSetIndex(pendingAddress));
      // Need to flush evictee
      if (evictionTargetState == State.DRAGON_MODIFIED || evictionTargetState == State.DRAGON_SHARED_MODIFIED) {
        state = CacheState.WRITING_PENDING_FLUSH;
        return new BusTransaction(Transition.FLUSH, evictionTargetAddress, blockSize);
      }
      set.evict();
      return new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);
    }

    throw new RuntimeException(
        "Accessing bus when not in a Pending state. State is: " + state + " in processor: " + processor);
  }

  public void exitBus(BusTransaction result) {
    int address = result.getAddress();
    int tag = getTag(address);
    boolean shared = result.getShared();
    CacheSet set = getSet(address);
    switch (result.getTransition()) {
      // Read/Write miss
      case BUS_RD:
        State newBlockState;
        switch (state) {
          case READING:
            newBlockState = shared ? State.DRAGON_SHARED_CLEAN : State.DRAGON_EXCLUSIVE;
            set.add(tag, newBlockState);
            state = CacheState.READY;
            processor.unstall();
            break;
          case WRITING:
            newBlockState = shared ? State.DRAGON_SHARED_MODIFIED : State.DRAGON_MODIFIED;
            set.add(tag, newBlockState);

            // Do the BusUpd next cycle
            if (newBlockState == State.DRAGON_SHARED_MODIFIED) {
              bus.reserveToFront(this);
              state = CacheState.PENDING_WRITE;
            } else {
              state = CacheState.READY;
              processor.unstall();
            }
            break;
          default:
            throw new RuntimeException("Did a BusRd when state not READING or WRITING");
        }
        break;
      case BUS_UPD:
        // Must be write hit, already in cache
        set.update(tag, shared ? State.DRAGON_SHARED_MODIFIED : State.DRAGON_MODIFIED);
        set.use(tag);
        state = CacheState.READY;
        processor.unstall();
        break;
      case FLUSH:
        set.invalidate(tag);
        switch (state) {
          case READING_PENDING_FLUSH:
            state = CacheState.PENDING_READ;
            break;
          case WRITING_PENDING_FLUSH:
            state = CacheState.PENDING_WRITE;
            break;
          default:
            throw new RuntimeException("Did a Flush when state not pending flush");
        }
        break;
      default:
        throw new RuntimeException("Exiting bus with invalid Dragon transaction type: " + result.getTransition());
    }

  }

  public Optional<BusTransaction> snoop(BusTransaction transaction) {
    int address = transaction.getAddress();
    int tag = getTag(address);
    CacheSet set = getSet(address);

    if (!set.contains(tag))
      return Optional.empty();

    State cacheState = set.getState(tag);
    switch (transaction.getTransition()) {
      case BUS_RD:
        switch (cacheState) {
          case DRAGON_EXCLUSIVE:
            set.update(tag, State.DRAGON_SHARED_CLEAN);
            // FALLTHROUGH
          case DRAGON_SHARED_CLEAN:
            return Optional.empty();
          case DRAGON_MODIFIED:
            set.update(tag, State.DRAGON_SHARED_MODIFIED);
            // FALLTHROUGH
          case DRAGON_SHARED_MODIFIED:
            return Optional.of(new BusTransaction(Transition.FLUSH, address, blockSize));
          default:
            break;
        }
      case BUS_UPD:
        if (cacheState != State.DRAGON_SHARED_CLEAN && cacheState != State.DRAGON_SHARED_MODIFIED)
          throw new RuntimeException("Snooped BusUpd for block that is not Shared");
        set.update(tag, State.DRAGON_SHARED_CLEAN);
        return Optional.empty();
      case FLUSH:
        return Optional.empty();
      default:
        throw new RuntimeException("Snooped invalid Dragon transaction type: " + transaction.getTransition());
    }

  };

}
