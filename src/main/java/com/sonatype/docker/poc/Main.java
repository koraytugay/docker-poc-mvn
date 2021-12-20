package com.sonatype.docker.poc;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
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

  //static String imageName = "white-rabbit";

  //static String repoName = "koraytugay";

  static String imageName = "httpd";

  static String repoName = "library";

  static String base64EncodedUsernamePassword = "a29yYXl0dWdheTpBZmdUZ1g5Q0Y4UjJq";

  public static void main(String[] args) throws Exception {
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
      httpget = new HttpGet("https://registry-1.docker.io/v2/" + repoName + "/" + imageName + "/manifests/latest");
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
            new HttpGet("https://registry-1.docker.io/v2/" + repoName + "/" + imageName + "/blobs/" + layer.digest);
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
      TarArchiveInputStream tararchiveinputstream =
          new TarArchiveInputStream(
              new GzipCompressorInputStream(
                  new BufferedInputStream(Files.newInputStream(file.toPath()))));

      ArchiveEntry archiveentry;
      while ((archiveentry = tararchiveinputstream.getNextEntry()) != null) {
        Path pathEntryOutput = Paths.get("./fs").resolve(archiveentry.getName());
        if (archiveentry.isDirectory()) {
          if (!Files.exists(pathEntryOutput)) {
            Files.createDirectory(pathEntryOutput);
          }
        }
        else {
          if (archiveentry.getName().contains(".wh")) {
            try {
              Files.delete(Paths.get("./fs").resolve(archiveentry.getName().replace(".wh.", "")));
            }
            catch (NoSuchFileException noSuchFileException) {
              noSuchFileException.printStackTrace();
            }
          }
          else {
            if (Files.exists(pathEntryOutput)) {
              Files.delete(pathEntryOutput);
            }
            Files.copy(tararchiveinputstream, pathEntryOutput);
          }
        }
      }

      tararchiveinputstream.close();
    }
  }
}
