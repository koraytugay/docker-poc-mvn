package com.sonatype.docker.poc;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import com.google.gson.Gson;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;

import static java.util.UUID.randomUUID;

public class LocalImages
{
  static class Manifest
  {
    public List<String> Layers;
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    String imageName = "library/httpd";
    String tarName = randomUUID().toString();
    String[] arguments = new String[]{"docker", "save", "-o", "./" + tarName + ".tar", imageName};
    Process proc = new ProcessBuilder(arguments).start();
    proc.waitFor();
    unTar(new File(tarName + ".tar"), tarName);

    String manifestFileJson = FileUtils.readFileToString(new File(tarName + "/manifest.json"), StandardCharsets.UTF_8);
    Manifest[] manifests = new Gson().fromJson(manifestFileJson, Manifest[].class);
    for (String layer : manifests[0].Layers) {
      unTar(new File(tarName + "/" + layer), "fs");
    }
  }

  public static void unTar(File file, String folderName) throws IOException {
    if (!Paths.get(folderName).toFile().exists()) {
      Files.createDirectory(Paths.get(folderName));
    }
    TarArchiveInputStream tararchiveinputstream
        = new TarArchiveInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())));
    TarArchiveEntry archiveentry;
    while ((archiveentry = (TarArchiveEntry) tararchiveinputstream.getNextEntry()) != null) {
      Path pathEntryOutput = Paths.get(folderName).resolve(archiveentry.getName());
      if (archiveentry.isDirectory()) {
        Files.createDirectories(pathEntryOutput);
        continue;
      }

      if (archiveentry.getName().contains(".wh..wh..")) {
        continue;
      }

      if (archiveentry.getName().contains(".wh")) {
        try {
          Files.delete(Paths.get("./fs").resolve(archiveentry.getName().replace(".wh.", "")));
        }
        catch (NoSuchFileException noSuchFileException) {
          System.out.println("Could not delete:" + archiveentry.getName());
        }
        continue;
      }

      if (pathEntryOutput.toFile().exists()) {
        Files.delete(pathEntryOutput);
      }
      if (archiveentry.isSymbolicLink()) {
        Files.deleteIfExists(pathEntryOutput);
        Path link = Paths.get("./fs").resolve(archiveentry.getName());
        Path target = Paths.get(archiveentry.getLinkName());
        try {
          Files.createSymbolicLink(link, target);
        }
        catch (Exception e) {
          System.out.println("\t=============");
          System.out.println("\tCould not create symbolic link:");
          System.out.println("\tLink:" + link);
          System.out.println("\tTarget:" + target);
          System.out.println("\tLink exists: " + link.toFile().exists());
          System.out.println("\tTarget exists: " + target.toFile().exists());
          System.out.println("\t=============");
        }
      }
      else if (archiveentry.getSize() == 0) {
        Files.createFile(Paths.get("./fs").resolve(archiveentry.getName()));
      }
      else {
        try {
          Files.copy(tararchiveinputstream, pathEntryOutput);
        } catch (Exception e) {
          e.printStackTrace();
        }
      }

    }

    tararchiveinputstream.close();
  }
}
