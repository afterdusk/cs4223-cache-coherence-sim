import java.io.BufferedReader;
import java.io.FileReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.*;

public class Simulator {
  enum Protocol {
    DRAGON, MESI
  }

  public static void main(String[] args) throws Exception {
    System.out.println("========== Cache Coherence Simulator ==========");
    validateArgLength(args.length);
    Protocol protocol = parseProtocol(args[0]);
    List<BufferedReader> readers = parseInputFile(args[1]);
    int cacheSize = parseCacheSize(args[2]);
    int associativity = parseAssociativity(args[3]);
    int blockSize = parseBlockSize(args[4]);
  }

  public static void validateArgLength(int length) {
    if (length < 5) {
      exitWithUsage("insufficient arguments");
    }
  }

  public static Protocol parseProtocol(String protocolString) {
    Protocol protocol = Protocol.MESI;
    switch (protocolString.toLowerCase()) {
      case "mesi":
        protocol = Protocol.MESI;
        break;
      case "dragon":
        protocol = Protocol.DRAGON;
        break;
      default:
        exitWithUsage(protocolString + " is not a recognized protocol");
    }
    return protocol;
  }

  public static List<BufferedReader> parseInputFile(String inputFile) {
    File directory = new File("./data/" + inputFile);
    if (!directory.isDirectory()) {
      exitWithUsage(directory + " is not a vallid directory");
    }
    System.out.println("...Reading benchmark files from: " + directory.getAbsolutePath());

    File[] files = directory.listFiles();
    Arrays.sort(files);

    List<BufferedReader> readers = new ArrayList<>();
    for (File file : files) {
      System.out.println("..." + file.getName() + " found");
      try {
        BufferedReader reader = new BufferedReader(new FileReader(file));
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
    String protocol = "\tprotocol: (MESI | Dragon)";
    String inputFile = "\tinput_file: benchmark located in data/ directory (e.g. blackscholes_four)";
    String cacheSize = "\tcache_size: cache size in bytes";
    String associativity = "\tassociativity: associativity of the cache";
    String blockSize = "\tblock_size: block size in bytes";
    System.out.println(String.join("\n", usage, protocol, inputFile, cacheSize, associativity, blockSize));
  }
}
