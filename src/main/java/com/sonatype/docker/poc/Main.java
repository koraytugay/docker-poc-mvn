package com.sonatype.docker.poc;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

public class Main
{
  static class TokenResponse
  {
    String access_token;
  }

  static class Layer
  {
    String digest;

    long size;
  }

  static class ManifestResponse
  {
    List<Layer> layers;
  }

  //static String repoName = "koraytugay";
  //
  //static String imageName = "white-rabbit";

  //static String repoName = "library";
  //
  //static String imageName = "httpd";

  static String repoName = "bigspotteddog";

  static String imageName = "docker-nexus3";

  static String base64EncodedUsernamePassword = "base64encoded(username:password)";

  public static void main(String[] args) throws Exception {
    FileUtils.deleteQuietly(Paths.get("./fs").toFile());
    FileUtils.forceMkdir(Paths.get("./fs").toFile());

    try (CloseableHttpClient httpclient = HttpClients.createDefault()) {
      // Get a token
      HttpGet httpget = new HttpGet(
          "https://auth.docker.io/token?service=registry.docker.io&scope=repository:" + repoName + "/" + imageName +
              ":pull");
      httpget.setHeader("Authorization", "Basic " + base64EncodedUsernamePassword);

      CloseableHttpResponse response = httpclient.execute(httpget);
      String text = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8.name());
      String token = new Gson().fromJson(text, TokenResponse.class).access_token;

      // Get the list of layers
      httpget = new HttpGet("https://registry.hub.docker.com/v2/" + repoName + "/" + imageName + "/manifests/latest");
      httpget.setHeader("Authorization", "Bearer " + token);
      httpget.setHeader("Accept", "application/vnd.docker.distribution.manifest.v2+json");

      response = httpclient.execute(httpget);
      text = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8.name());
      ManifestResponse manifestResponse = new Gson().fromJson(text, ManifestResponse.class);

      List<File> files = new ArrayList<>();

      int counter = 100;

      // Download layers (tar files)
      for (Layer layer : manifestResponse.layers) {
        System.out.println("Downloading layer: " + layer.digest);
        System.out.println("Layer size:" + layer.size + " bytes.(" + ((double) layer.size) / 1_000_000 + " MB)");
        httpget =
            new HttpGet("https://registry.hub.docker.com/v2/" + repoName + "/" + imageName + "/blobs/" + layer.digest);
        httpget.setHeader("Authorization", "Bearer " + token);
        httpget.setHeader("Accept", "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip");

        response = httpclient.execute(httpget);
        File targetFile = new File(counter + "_" + layer.digest.substring(7, 17) + ".tar");
        counter++;
        files.add(targetFile);

        InputStream inputStream = response.getEntity().getContent();
        OutputStream outputStream = new FileOutputStream(targetFile);
        IOUtils.copy(inputStream, outputStream);
        inputStream.close();
        outputStream.close();
      }

      unTarGz(files);
    }
  }

  public static void unTarGz(List<File> files) throws IOException {
    for (File file : files) {
      System.out.println("=============");
      System.out.println("Processing file:" + file.getName());

      TarArchiveInputStream tararchiveinputstream =
          new TarArchiveInputStream(
              new GzipCompressorInputStream(
                  new BufferedInputStream(Files.newInputStream(file.toPath()))));

      TarArchiveEntry archiveentry;
      while ((archiveentry = (TarArchiveEntry) tararchiveinputstream.getNextEntry()) != null) {
        Path pathEntryOutput = Paths.get("./fs").resolve(archiveentry.getName());
        if (archiveentry.isDirectory()) {
          Files.createDirectories(pathEntryOutput);
          continue;
        }

        if (archiveentry.getName().contains(".wh..wh..")) {
          continue;
        }

        if (archiveentry.getName().contains(".wh")) {
          Path resolvedPath = Paths.get("./fs").resolve(archiveentry.getName().replace(".wh.", ""));
          try {
            if (Files.isDirectory(resolvedPath)) {
              FileUtils.deleteDirectory(resolvedPath.toFile());
            } else {
              Files.delete(resolvedPath);
            }
          }
          catch (NoSuchFileException noSuchFileException) {
            System.out.println("Could not delete file:" + resolvedPath);
            System.out.println("This file does not exist.");
          }
          catch (DirectoryNotEmptyException directoryNotEmptyException) {
            System.out.println("Could not delete directory:" + resolvedPath);
            directoryNotEmptyException.printStackTrace();
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
          }
          catch (FileAlreadyExistsException e) {
            System.out.println("File exists but was not deleted successfully:" + pathEntryOutput);
            System.out.println("This seems to happen when the existing file is a symbolic link but new file is actual");
            System.out.println("Will delete the file again and copy the new file.");
            Files.delete(pathEntryOutput);
            Files.copy(tararchiveinputstream, pathEntryOutput);
          }
        }
      }

      tararchiveinputstream.close();

      System.out.println("=============");
    }
  }
}
