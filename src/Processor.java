import java.util.*;

public class Processor {
  enum ProcessorState {
    READY, WAITCACHE, COMPUTE, DONE
  }

  private Cache cache;
  private Scanner sc;

  // State variables
  public ProcessorState state;
  private int computeRemaining;

  // Statistics
  private long processorCycle = 0;
  private long processorComputeCycles = 0;
  private long processorLoads = 0;
  private long processorStores = 0;
  private long processorIdleCycles = 0;

  public Processor(Scanner sc, Cache cache) {
    this.sc = sc;
    this.cache = cache;
    cache.registerProcessor(this);
    this.state = ProcessorState.READY;
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
          state = ProcessorState.DONE;
          sc.close();
          return;
        }

        Instruction inst = getNextInstruction();
        switch (inst.getType()) {
          case LOAD:
            processorLoads++;
            state = ProcessorState.WAITCACHE;
            cache.read(inst.getAddress());
            break;
          case STORE:
            processorStores++;
            state = ProcessorState.WAITCACHE;
            cache.write(inst.getAddress());
            break;
          case OTHER:
            state = ProcessorState.COMPUTE;
            computeRemaining = inst.getCycleDuration();
        }
    }
    cache.tick();
    processorCycle++;
  }

  public void tock() {
    switch (state) {
      case COMPUTE:
        processorComputeCycles++;
        computeRemaining--;
        if (computeRemaining == 0)
          state = ProcessorState.READY;
        break;
      case WAITCACHE:
        processorIdleCycles++;
        break;
      default:
        break;
    }
  }

  public void unstall() {
    if (state == ProcessorState.WAITCACHE) { // For idempotency
      state = ProcessorState.READY;
    } else {
      throw new RuntimeException("unstall() was called when processor was not in WAITCACHE state");
    }
  }

  public Map<String, Number> getProcessorStatistics() {
    assert processorComputeCycles + processorIdleCycles == processorCycle;

    Map<String, Number> statistics = new LinkedHashMap<>();
    statistics.put("Cycle", processorCycle);
    statistics.put("Compute Cycles", processorComputeCycles);
    statistics.put("Idle Cycles", processorIdleCycles);
    statistics.put("Loads", processorLoads);
    statistics.put("Stores", processorStores);
    return statistics;
  }

  private Instruction getNextInstruction() {
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
