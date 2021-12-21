package com.sonatype.docker.poc;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.IOUtils;

import static java.util.UUID.randomUUID;

public class DockerApproach
{
  // # create container from image without starting it
  //  docker create --name my_container my_image
  //
  //  # export container file system to tar archive
  //  docker export -o my_image.tar my_container
  //
  //  # or directly extract the archive in the current folder
  //  docker export my_container | tar -x
  //
  //  # clean up temporary container
  //  docker rm my_container
  public static void main(String[] args) throws IOException, InterruptedException {
    String imageName = "sonatype/gitlab-nexus-iq-pipeline";

    // Create container from the image..
    String randomContainerName = randomUUID().toString();
    String[] arguments = new String[]{"docker", "create", "--name", randomContainerName, imageName};
    Process proc = new ProcessBuilder(arguments).start();
    String containerId = IOUtils.toString(proc.getInputStream(), StandardCharsets.UTF_8);
    System.out.println("Container generated with id: " + containerId);

    // Export container to tar
    String containerTar = randomContainerName.substring(0, 8) + ".tar";
    arguments = new String[]{"docker", "export", "-o", containerTar, randomContainerName};
    proc = new ProcessBuilder(arguments).start();

    proc.waitFor();

    System.out.println("Container tar exported:" + containerTar);
    unTar(Paths.get(containerTar).toFile(), randomContainerName.substring(0, 8));
    System.out.println("Container tar unzipped.");
  }

  public static void unTar(File file, String folderName) throws IOException {
    TarArchiveInputStream tararchiveinputstream
        = new TarArchiveInputStream(new BufferedInputStream(Files.newInputStream(file.toPath())));

    TarArchiveEntry archiveEntry;
    while ((archiveEntry = (TarArchiveEntry) tararchiveinputstream.getNextEntry()) != null) {
      Path pathEntryOutput = Paths.get(folderName).resolve(archiveEntry.getName());
      if (archiveEntry.isDirectory()) {
        if (!Files.exists(pathEntryOutput)) {
          Files.createDirectories(pathEntryOutput);
        }
      }
      else {
        if (archiveEntry.isSymbolicLink()) {
          Files.deleteIfExists(pathEntryOutput);
          Path link = Paths.get(folderName).resolve(archiveEntry.getName());
          Path target = Paths.get(archiveEntry.getLinkName());
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
        if (archiveEntry.getSize() != 0) {
          Files.copy(tararchiveinputstream, pathEntryOutput);
        }
      }
    }

    tararchiveinputstream.close();
  }
}
