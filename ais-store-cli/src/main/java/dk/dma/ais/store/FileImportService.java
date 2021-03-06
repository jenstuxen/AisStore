/* Copyright (c) 2011 Danish Maritime Authority.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.dma.ais.store;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.AbstractExecutionThreadService;

import dk.dma.ais.packet.AisPacket;
import dk.dma.ais.packet.AisPacketReader;
import dk.dma.commons.util.io.PathUtil;

/**
 * This class is responsible for reading text based ais files.
 * 
 * 
 * @author Kasper Nielsen
 */
class FileImportService extends AbstractExecutionThreadService {

    /** The logger. */
    private static final Logger LOG = LoggerFactory.getLogger(FileImportService.class);

    /** The archiver. */
    private final Archiver archiver;

    FileImportService(Archiver archiver) {
        this.archiver = requireNonNull(archiver);
    }

    /** {@inheritDoc} */
    @Override
    protected void run() throws Exception {
        // The backup directory
        Path backupDirectory = archiver.backup.toPath();
        LOG.info("Using " + backupDirectory.toAbsolutePath() + " for backup");
        try {
            Files.createDirectories(backupDirectory);
        } catch (IOException e) {
            LOG.error("Could not create backup directory, exiting", e);
            System.exit(1);
        }
        // Run in a loop until shutdown
        while (isRunning()) {
            archiver.sleepUnlessShutdown(1, TimeUnit.SECONDS);

            // only start reading backups if there is no pressure on cassandra
            if (archiver.getNumberOfOutstandingPackets() < archiver.batchSize) {
                if (Files.exists(backupDirectory)) {
                    try {
                        // Let's see if there are files we can process
                        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupDirectory)) {
                            int count = 0;
                            for (Path p : ds) {
                                if (!isRunning()) {
                                    break;
                                }
                                if (p.getFileName().toString().endsWith(".zip")) {
                                    try {
                                        restoreFile(p);
                                        count++;
                                    } catch (Exception e) {
                                        LOG.error("Unknown error while trying to restore backup from file " + p, e);
                                        Path ne = PathUtil.findUnique(p.resolveSibling(p.getFileName().toString()
                                                + ".unreadable"));
                                        LOG.error("Trying to rename the file to " + ne, e);
                                        try {
                                            Files.move(p, ne);
                                        } catch (IOException ioe) {
                                            LOG.error("Could not rename file ", ioe);
                                        }
                                    }
                                    // Take a long break after having imported 100 files
                                    if (count % 50 == 49) {
                                        LOG.info("File importer taking a long break, after having imported " + count
                                                + " files");
                                        archiver.sleepUnlessShutdown(60, TimeUnit.SECONDS);
                                    }
                                    // Wait until there is plenty of room in the queue
                                    while (isRunning() && archiver.getNumberOfOutstandingPackets() > archiver.batchSize) {
                                        archiver.sleepUnlessShutdown(1, TimeUnit.SECONDS);
                                    }
                                }
                            }
                        }
                    } catch (Exception e) {
                        LOG.error("Unknown error while trying to restore backup ", e);
                    }
                }
            }
        }
    }

    private void restoreFile(Path p) throws IOException, InterruptedException {
        LOG.info("Trying to restore " + p);
        try (AisPacketReader s = AisPacketReader.createFromFile(p, true)) {
            AisPacket packet;
            while ((packet = s.readPacket()) != null) {
                // we might be overloaded so sleep for a bit if we cannot write the packet
                while (isRunning()) {
                    int q = archiver.getNumberOfOutstandingPackets();
                    if (q > 10 * archiver.batchSize) {
                        LOG.info("Write queue to Cassandra is to busy size=" + q + ", sleeping for a bit");
                    } else if (archiver.mainStage.getInputQueue().offer(packet)) {
                        break;
                    } else {
                        LOG.info("Write queue to Cassandra was full size=" + q + ", sleeping for a bit");
                    }
                    archiver.sleepUnlessShutdown(1, TimeUnit.SECONDS);
                }
                if (!isRunning()) {
                    return;
                }
            }
        }
        LOG.info("Finished restoring " + p);
        try {
            Files.delete(p);// empty file
        } catch (IOException e) {
            LOG.error("Could not delete backup file: " + p, e);// Auch, we will keep reading the same file
        }
    }
}
