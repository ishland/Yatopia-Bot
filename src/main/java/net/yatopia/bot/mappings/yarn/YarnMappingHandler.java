package net.yatopia.bot.mappings.yarn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.yatopia.bot.mappings.Mapping;
import net.yatopia.bot.mappings.MappingParser;
import net.yatopia.bot.mappings.MappingType;
import net.yatopia.bot.mappings.NameType;
import net.yatopia.bot.mappings.NoSuchVersionException;
import net.yatopia.bot.util.TriPredicate;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.Nullable;

import static net.yatopia.bot.util.Utils.JSON_MAPPER;

public final class YarnMappingHandler implements MappingParser {

  private enum TinyType {
    V1,
    V2
  }

  private static final TriPredicate<NameType, Mapping, String> EXACT =
      (type, mapping, input) -> type.get(mapping).equalsIgnoreCase(input);
  private static final TriPredicate<NameType, Mapping, String> ENDS_WITH =
      (type, mapping, input) -> type.get(mapping).endsWith(input);

  private Cache<String, List<Mapping>> mappingCache =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(4)).build();
  private Map<String, MappingVersion> currentVersions = new ConcurrentHashMap<>();

  private long lastDownloadTime = -1;

  private void downloadIfNeeded(File dataFolder, String mcVersion) throws NoSuchVersionException {
    if (lastDownloadTime != -1
        && (lastDownloadTime + TimeUnit.HOURS.toMillis(4)) > System.currentTimeMillis()) {
      return;
    }
    File mappingsFolder =
        new File(dataFolder + File.separator + "yarn" + File.separator + mcVersion);
    if (currentVersions.get(mcVersion) == null && mappingsFolder.exists()) {
      File version = new File(mappingsFolder, ".dataversion");
      if (version.exists()) {
        try (BufferedReader reader = new BufferedReader(new FileReader(version))) {
          currentVersions.put(mcVersion, JSON_MAPPER.readValue(reader, MappingVersion.class));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
    MappingVersion version;
    try {
      URL versionsUrl =
          new URL("https://meta.fabricmc.net/v1/versions/mappings/" + mcVersion + "/");
      HttpURLConnection connection = (HttpURLConnection) versionsUrl.openConnection();
      connection.setRequestMethod("GET");
      connection.addRequestProperty("User-Agent", "Yatopia-Bot");
      try (InputStream in = connection.getInputStream()) {
        MappingVersion[] versions = JSON_MAPPER.readValue(in, MappingVersion[].class);
        if (versions == null || versions.length == 0) {
          throw new NoSuchVersionException(mcVersion);
        }
        version = versions[0];
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    MappingVersion current = currentVersions.get(mcVersion);
    if (current != null && current.getBuild() == version.getBuild()) {
      if (mappingCache.getIfPresent(mcVersion) == null) {
        TinyType mappingsType = TinyType.V1;
        String[] maven = current.getMaven().split(":");
        String fileName = "yarn-" + maven[2] + "-mergedv2.jar";
        File file = mappingsFolder.toPath().resolve(fileName).toFile();
        if (file.exists()) {
          mappingsType = TinyType.V2;
        } else {
          fileName = "yarn-" + maven[2] + "-tiny.gz";
          file = mappingsFolder.toPath().resolve(fileName).toFile();
        }
        try {
          List<Mapping> mappings;
          if (mappingsType == TinyType.V1) {
            mappings = TinyV1Parser.INSTANCE.parse(file, mcVersion, this);
          } else {
            mappings = TinyV2Parser.INSTANCE.parse(file, mcVersion, this);
          }
          mappingCache.put(mcVersion, mappings);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      return;
    }
    try {
      String mappingsUrl = version.getMavenUrl("https://maven.fabricmc.net/", "mergedv2", "jar");
      URL url = new URL(mappingsUrl);
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      if (isBadCode(connection.getResponseCode())) {
        // v1 mappings
        connection.disconnect();
        mappingsUrl = version.getMavenUrl("https://maven.fabricmc.net/", "tiny", "gz");
        url = new URL(mappingsUrl);
        if (mappingsFolder.exists()) {
          mappingsFolder.delete();
        }
        mappingsFolder.mkdirs();
        String fileName = mappingsUrl.substring(mappingsUrl.lastIndexOf('/') + 1);
        File mappingFile = mappingsFolder.toPath().resolve(fileName).toFile();
        FileUtils.copyURLToFile(url, mappingFile);
        mappingCache.invalidate(mcVersion);
        List<Mapping> mappings = TinyV1Parser.INSTANCE.parse(mappingFile, mcVersion, this);
        mappingCache.put(mcVersion, mappings);
      } else {
        // v2 mappings
        if (mappingsFolder.exists()) {
          mappingsFolder.delete();
        }
        mappingsFolder.mkdirs();
        String fileName = mappingsUrl.substring(mappingsUrl.lastIndexOf('/') + 1);
        File mappingFile = mappingsFolder.toPath().resolve(fileName).toFile();
        try (OutputStream out = new FileOutputStream(mappingFile)) {
          try (InputStream in = connection.getInputStream()) {
            IOUtils.copy(in, out);
          }
        }
        mappingCache.invalidate(mcVersion);
        List<Mapping> mappings = TinyV2Parser.INSTANCE.parse(mappingFile, mcVersion, this);
        mappingCache.put(mcVersion, mappings);
      }
      File versionFile = new File(mappingsFolder, ".dataversion");
      if (versionFile.exists()) {
        versionFile.delete();
      }
      versionFile.createNewFile();
      FileUtils.write(versionFile, JSON_MAPPER.writeValueAsString(version), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    lastDownloadTime = System.currentTimeMillis();
  }

  private boolean isBadCode(int code) {
    return code == 404 || code == 400 || code == 500 || code == 403;
  }

  @Override
  public List<Mapping> parseMapping(MappingType type, String mcVer, String input)
      throws NoSuchVersionException {
    return parseMappings(null, type, mcVer, input, ENDS_WITH);
  }

  @Override
  public List<Mapping> parseMappingExact(
      NameType nameType, MappingType mappingType, String mcVer, String input)
      throws NoSuchVersionException {
    return parseMappings(nameType, mappingType, mcVer, input, EXACT);
  }

  private List<Mapping> parseMappings(
      @Nullable NameType nameType,
      MappingType mappingType,
      String mcVer,
      String input,
      TriPredicate<NameType, Mapping, String> filter)
      throws NoSuchVersionException {
    File data = new File(".", "data");
    if (!data.exists()) {
      data.mkdirs();
    }
    downloadIfNeeded(data, mcVer);
    List<Mapping> mappings = mappingCache.getIfPresent(mcVer);
    if (mappings == null) {
      throw new NoSuchVersionException(mcVer);
    }
    List<Mapping> ret = new ArrayList<>();
    if (nameType == null) {
      for (Mapping mapping : mappings) {
        if (mapping.getMappingType() == mappingType) {
          for (NameType type : NameType.values()) {
            if (type.get(mapping) != null && filter.test(type, mapping, input)) {
              ret.add(mapping);
            }
          }
        }
      }
    } else {
      for (Mapping mapping : mappings) {
        if (mapping.getMappingType() == mappingType) {
          if (nameType.get(mapping) != null && filter.test(nameType, mapping, input)) {
            ret.add(mapping);
          }
        }
      }
    }
    return ret;
  }
}