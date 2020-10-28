import java.util.*;

public class Processor {
  enum State {
    READY, WAITCACHE, COMPUTE, DONE
  }

  Cache cache;
  Scanner sc;
  public State state = State.READY;
  long currentCycle = 0;
  long computeCycles = 0;
  long loads = 0;
  long stores = 0;
  long idle = 0;

  int computeRemaining;

  public Processor(Simulator.Protocol protocol, Scanner sc, int cacheSize, int associativity, int blockSize) {
    this.sc = sc;
    cache = new Cache(this, protocol, cacheSize, associativity, blockSize);
  }

  public void tick() {
    switch (state) {
      case DONE:
        return;
      case WAITCACHE:
      case COMPUTE:
        cache.issue();
        break;
      case READY:
        if (!sc.hasNext()) {
          state = State.DONE;
          return;
        }

        Instruction inst = getNextInstruction();
        switch (inst.type) {
          case LOAD:
            loads++;
            state = State.WAITCACHE;
            // Hand off to cache
            break;
          case STORE:
            stores++;
            state = State.WAITCACHE;
            // Hand off to cache
            break;
          case OTHER:
            state = State.COMPUTE;
            computeRemaining = inst.address;
        }
    }
    currentCycle++;
  }

  public void tock() {
    switch (state) {
      case DONE:
        return;
      case COMPUTE:
        computeCycles++;
        computeRemaining--;
        if (computeRemaining == 0)
          state = State.READY;
        break;
      case WAITCACHE:
        idle++;
        break;
      case READY:
        throw new RuntimeException("Processor in READY state when tock() called");
    }
  }

  public void unstall() {
    if (state == State.WAITCACHE) { // For idempotency
      state = State.READY;
    } else {
      tock();
    }
  }

  Instruction getNextInstruction() {
    int instType = sc.nextInt();
    Instruction.Type type;
    switch (instType) {
      case 0:
        type = Instruction.Type.LOAD;
        break;
      case 1:
        type = Instruction.Type.STORE;
        break;
      case 2:
        type = Instruction.Type.OTHER;
        break;
      default:
        throw new InputMismatchException("Unknown instruction code :" + instType);
    }

    int address = Integer.parseInt(sc.next().substring(2), 16);
    return new Instruction(type, address);
  }

}
