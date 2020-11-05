public class MesiCache extends Cache {
  enum CacheState {
    READY, PENDING_READ, PENDING_WRITE, USING_BUS
  }

  CacheState cacheState;
  int pendingAddress;
  BusTransaction pendingTransaction;

  public MesiCache(Bus bus, int cacheSize, int associativity, int blockSize) {
    super(bus, cacheSize, associativity, blockSize);
    cacheState = CacheState.READY;
  }

  @Override
  void tick() {
    switch (cacheState) {
      case PENDING_READ:
        prRd(pendingAddress);
        break;
      case PENDING_WRITE:
        // prWr(pendingAddress);
        break;
      case USING_BUS:
      case READY:
        break;
    }
  }

  @Override
  public boolean contains(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    return set.contains(getTag(address));
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
    cacheState = CacheState.USING_BUS;
    return pendingTransaction;
  }

  @Override
  public void exitBus(BusTransaction result) {
    if (cacheState != CacheState.USING_BUS) {
      throw new RuntimeException("exitBus called when cache is not in AWAIT_BUS state");
    }
    cacheState = CacheState.READY;
    processor.unstall();
  }

  @Override
  public BusTransaction snoop(BusTransaction transaction) {
    BusTransaction response = null;
    switch (cacheState) {
      case PENDING_READ:
      case PENDING_WRITE:
      case READY:
        int address = transaction.getAddress();
        switch (transaction.getTransition()) {
          case BUS_RD:
            response = busRd(address);
            break;
          case BUS_RD_X:
            // busRdX(address);
            break;
          case FLUSH:
          case FLUSH_OPT:
            break;
        }
        break;
      case USING_BUS:
        break;
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
        processor.unstall();
        break;
      case MESI_INVALID:
        bus.reserve(this);
        pendingTransaction = new BusTransaction(Transition.BUS_RD, address, blockSize);
        break;
      default:
        throw new RuntimeException("Invalid state detected in MESI cache: " + set.getState(tag));
    }
  }

  private BusTransaction busRd(int address) {
    CacheSet set = sets.get(getSetIndex(address));
    int tag = getTag(address);
    if (!set.contains(tag)) {
      return null;
    }
    BusTransaction response = null;
    switch (set.getState(tag)) {
      case MESI_MODIFIED:
        set.update(tag, State.MESI_SHARED);
        response = new BusTransaction(Transition.FLUSH, address, blockSize);
        break;
      case MESI_EXCLUSIVE:
        set.update(tag, State.MESI_SHARED);
        response = new BusTransaction(Transition.FLUSH_OPT, address, blockSize);
        break;
      case MESI_SHARED:
        response = new BusTransaction(Transition.FLUSH_OPT, address, blockSize);
        break;
      case MESI_INVALID:
        break;
      default:
        throw new RuntimeException("Invalid state detected in MESI cache: " + set.getState(tag));
    }
    return response;
  }
}
