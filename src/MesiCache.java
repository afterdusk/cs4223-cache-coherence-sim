import java.util.*;

public class MesiCache extends Cache {
  enum CacheState {
    READY, PENDING_READ, READING_PENDING_FLUSH, READING, PENDING_WRITE, WRITING_PENDING_FLUSH, WRITING
  }

  private CacheState cacheState;
  private int pendingAddress;

  public MesiCache(Bus bus, int cacheSize, int associativity, int blockSize) {
    super(bus, cacheSize, associativity, blockSize);
    cacheState = CacheState.READY;
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
      case READING:
      case READING_PENDING_FLUSH:
      case WRITING:
      case WRITING_PENDING_FLUSH:
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
      case PENDING_READ: {
        // Capacity available
        if (!set.isFull()) {
          pendingState = CacheState.READING;
          pendingTransaction = new BusTransaction(Transition.BUS_RD, pendingAddress, blockSize);
          break;
        }
        // Conflict miss
        int evictionTarget = set.getEvictionTarget();
        State evictionTargetState = set.getState(evictionTarget);
        int evictionTargetAddress = getAddress(evictionTarget, getSetIndex(pendingAddress));
        set.evict();

        if (evictionTargetState == State.MESI_MODIFIED) {
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
      case PENDING_WRITE: {
        // Capacity available
        if (!set.isFull()) {
          pendingState = CacheState.WRITING;
          pendingTransaction = new BusTransaction(Transition.BUS_RD_X, pendingAddress, blockSize);
          break;
        }
        // Conflict miss
        int evictionTarget = set.getEvictionTarget();
        State evictionTargetState = set.getState(evictionTarget);
        int evictionTargetAddress = getAddress(evictionTarget, getSetIndex(pendingAddress));
        set.evict();

        if (evictionTargetState == State.MESI_MODIFIED) {
          // Need to flush evictee
          pendingState = CacheState.READING_PENDING_FLUSH;
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
        State newBlockState = result.getShared() ? State.MESI_SHARED : State.MESI_EXCLUSIVE;
        set.add(tag, newBlockState);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case BUS_RD_X:
        if (cacheState != CacheState.WRITING) {
          throw new RuntimeException("Did a BusRdX when state is not WRITING");
        }
        if (set.contains(tag)) {
          set.update(tag, State.MESI_MODIFIED);
        } else {
          set.add(tag, State.MESI_MODIFIED);
        }
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case FLUSH:
        if (cacheState == CacheState.READING_PENDING_FLUSH) {
          cacheState = CacheState.PENDING_READ;
        }
        if (cacheState == CacheState.WRITING_PENDING_FLUSH) {
          cacheState = CacheState.PENDING_WRITE;
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
      default:
        throw new RuntimeException("Invalid transaction detected in snoop()");
    }
    return response;
  }

  private void prRd(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    State state = State.MESI_INVALID;
    if (set.contains(tag)) {
      state = set.getState(tag);
    }
    switch (state) {
      case MESI_MODIFIED:
      case MESI_EXCLUSIVE:
      case MESI_SHARED:
        set.use(tag);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case MESI_INVALID:
        bus.reserve(this);
        break;
      default:
        throw new RuntimeException("Invalid state detected in MESI cache: " + set.getState(tag));
    }
  }

  private void prWr(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    State state = State.MESI_INVALID;
    if (set.contains(tag)) {
      state = set.getState(tag);
    }
    switch (state) {
      case MESI_EXCLUSIVE:
        set.update(tag, State.MESI_MODIFIED);
      case MESI_MODIFIED:
        set.use(tag);
        cacheState = CacheState.READY;
        processor.unstall();
        break;
      case MESI_SHARED:
      case MESI_INVALID:
        bus.reserve(this);
        break;
      default:
        throw new RuntimeException("Invalid state detected in MESI cache: " + set.getState(tag));
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
      case MESI_MODIFIED:
        set.update(tag, State.MESI_SHARED);
        response = Optional.of(new BusTransaction(Transition.FLUSH, address, blockSize));
        break;
      case MESI_EXCLUSIVE:
        set.update(tag, State.MESI_SHARED);
        response = Optional.of(new BusTransaction(Transition.FLUSH_OPT, address, blockSize));
        break;
      case MESI_SHARED:
        response = Optional.of(new BusTransaction(Transition.FLUSH_OPT, address, blockSize));
        break;
      case MESI_INVALID:
      default:
        throw new RuntimeException("Invalid state for busRd detected in MESI cache: " + set.getState(tag));
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
      case MESI_MODIFIED:
        set.invalidate(tag);
        response = Optional.of(new BusTransaction(Transition.FLUSH, address, blockSize));
        break;
      case MESI_EXCLUSIVE:
        set.invalidate(tag);
        response = Optional.of(new BusTransaction(Transition.FLUSH_OPT, address, blockSize));
        break;
      case MESI_SHARED:
        set.invalidate(tag);
        response = Optional.of(new BusTransaction(Transition.FLUSH_OPT, address, blockSize));
        break;
      case MESI_INVALID:
      default:
        throw new RuntimeException("Invalid state for busRd detected in MESI cache: " + set.getState(tag));
    }
    return response;
  }
}
