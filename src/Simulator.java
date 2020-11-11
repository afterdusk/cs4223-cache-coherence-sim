import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

public class Simulator {
  private static final int DEFAULT_CACHE_SIZE = 4096;
  private static final int DEFAULT_ASSOCIATIVITY = 2;
  private static final int DEFAULT_BLOCK_SIZE = 32;

  enum Protocol {
    MESI, MOESI, DRAGON
  }

  // Statistics
  public static long simulatorCycle = 0;

  public static void main(String[] args) throws Exception {
    System.out.println("========== Cache Coherence Simulator ==========");
    validateArgLength(args.length);
    Protocol protocol = parseProtocol(args[0]);
    List<Scanner> readers = parseInputFile(args[1]);
    int cacheSize = args.length < 3 ? DEFAULT_CACHE_SIZE : parseCacheSize(args[2]);
    int associativity = args.length < 4 ? DEFAULT_ASSOCIATIVITY : parseAssociativity(args[3]);
    int blockSize = args.length < 5 ? DEFAULT_BLOCK_SIZE : parseBlockSize(args[4]);

    List<Processor> processors = new ArrayList<>(readers.size());
    List<Cache> caches = new ArrayList<>(readers.size());
    Bus bus = new Bus();
    for (Scanner reader : readers) {
      Cache cache;
      switch (protocol) {
        case MESI:
          cache = new MesiCache(bus, cacheSize, associativity, blockSize);
          break;
        case MOESI:
          cache = new MoesiCache(bus, cacheSize, associativity, blockSize);
          break;
        case DRAGON:
          cache = new DragonCache(bus, cacheSize, associativity, blockSize);
          break;
        default:
          throw new RuntimeException("Cache not implemented for protocol");
      }
      caches.add(cache);
      processors.add(new Processor(reader, cache));
    }

    System.out.println("...Simulation starting");
    while (!processors.stream().allMatch(p -> p.state == Processor.ProcessorState.DONE)) {
      processors.stream().filter(p -> p.state != Processor.ProcessorState.DONE).forEach(p -> p.tick());
      bus.tick();
      bus.tock();
      processors.stream().filter(p -> p.state != Processor.ProcessorState.DONE).forEach(p -> p.tock());
      simulatorCycle++;
    }
    System.out.printf("...Simulation completed!\n\n");

    printStatistics(bus, processors, caches);
  }

  public static void validateArgLength(int length) {
    if (length < 2) {
      exitWithUsage("insufficient arguments");
    }
  }

  public static Protocol parseProtocol(String protocolString) {
    Protocol protocol = Protocol.MESI;
    switch (protocolString.toLowerCase()) {
      case "mesi":
        protocol = Protocol.MESI;
        break;
      case "moesi":
        protocol = Protocol.MOESI;
        break;
      case "dragon":
        protocol = Protocol.DRAGON;
        break;
      default:
        exitWithUsage(protocolString + " is not a recognized protocol");
    }
    return protocol;
  }

  public static List<Scanner> parseInputFile(String inputFile) {
    File directory = new File("./data/" + inputFile);
    if (!directory.isDirectory()) {
      exitWithUsage(directory + " is not a valid directory");
    }
    System.out.println("...Reading benchmark files from: " + directory.getAbsolutePath());

    File[] files = directory.listFiles();
    Arrays.sort(files);

    List<Scanner> readers = new ArrayList<>();
    for (File file : files) {
      System.out.println("..." + file.getName() + " found");
      try {
        Scanner reader = new Scanner(file);
        readers.add(reader);
      } catch (FileNotFoundException e) {
        exitWithUsage(file.getName() + " found but could not be read");
      }
    }
    return readers;
  }

  public static int parseCacheSize(String cacheSizeString) {
    int cacheSize = -1;
    try {
      cacheSize = Integer.parseInt(cacheSizeString, 10);
      if (cacheSize <= 0) {
        throw new NumberFormatException();
      }
    } catch (NumberFormatException e) {
      exitWithUsage("Error: " + cacheSizeString + " is not a valid cache size");
    }
    return cacheSize;
  }

  public static int parseAssociativity(String associativityString) {
    int associativity = -1;
    try {
      associativity = Integer.parseInt(associativityString, 10);
      if (associativity <= 0) {
        throw new NumberFormatException();
      }
    } catch (NumberFormatException e) {
      exitWithUsage(associativityString + " is not a valid associativity");
    }
    return associativity;
  }

  public static int parseBlockSize(String blockSizeString) {
    int blockSize = -1;
    try {
      blockSize = Integer.parseInt(blockSizeString, 10);
      if (blockSize <= 0) {
        throw new NumberFormatException();
      }
    } catch (NumberFormatException e) {
      exitWithUsage(blockSizeString + " is not a valid block size");
    }
    return blockSize;
  }

  public static void exitWithUsage(String message) {
    System.out.println("Error: " + message);
    printUsage();
    System.exit(1);
  }

  public static void printUsage() {
    String usage = "usage: Simulator protocol input_file cache_size associativity block_size";
    String protocol = "\tprotocol: (MESI | MOESI | Dragon)";
    String inputFile = "\tinput_file: benchmark located in data/ directory (e.g. blackscholes_four)";
    String cacheSize = "\tcache_size: cache size in bytes";
    String associativity = "\tassociativity: associativity of the cache";
    String blockSize = "\tblock_size: block size in bytes";
    System.out.println(String.join("\n", usage, protocol, inputFile, cacheSize, associativity, blockSize));
  }

  public static void printStatistics(Bus bus, List<Processor> processors, List<Cache> caches) {
    System.out.println("============ Simulation Statistics ============");
    System.out.println("Overall Execution Cycle: " + simulatorCycle);
    System.out.println("------------------- Bus --------------------");
    printStatisticsMap(bus.getBusStatistics());
    for (int i = 0; i < processors.size(); i++) {
      System.out.printf("------------------ Core %d ------------------\n", i + 1);
      printStatisticsMap(processors.get(i).getProcessorStatistics());
      printStatisticsMap(caches.get(i).getCacheStatistics());
    }
  }

  public static void printStatisticsMap(Map<String, Number> map) {
    map.forEach((k, v) -> {
      if (v instanceof Float) {
        System.out.printf("%-28s %15.2f\n", k + ":", v);
      } else {
        System.out.printf("%-28s %15d\n", k + ":", v);
      }
    });
  }
}
