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

  public Processor(Scanner sc, Cache cache) {
    this.sc = sc;
    this.cache = cache;
    cache.registerProcessor(this);
  }

  public void tick() {
    switch (state) {
      case DONE:
        return;
      case WAITCACHE:
      case COMPUTE:
        break;
      case READY:
        if (!sc.hasNext()) {
          state = State.DONE;
          return;
        }

        Instruction inst = getNextInstruction();
        switch (inst.getType()) {
          case LOAD:
            loads++;
            state = State.WAITCACHE;
            cache.read(inst.getAddress());
            break;
          case STORE:
            stores++;
            state = State.WAITCACHE;
            cache.write(inst.getAddress());
            break;
          case OTHER:
            state = State.COMPUTE;
            computeRemaining = inst.getCycleDuration();
        }
    }
    cache.tick();
    currentCycle++;
  }

  public void tock() {
    switch (state) {
      case COMPUTE:
        computeCycles++;
        computeRemaining--;
        if (computeRemaining == 0)
          state = State.READY;
        break;
      case WAITCACHE:
        idle++;
        break;
      default:
        break;
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

    int value = Integer.parseInt(sc.next().substring(2), 16);
    return new Instruction(type, value);
  }

}
