/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.engine.compaction.task;

import org.apache.iotdb.commons.conf.IoTDBConstant;
import org.apache.iotdb.db.conf.IoTDBDescriptor;
import org.apache.iotdb.db.engine.compaction.CompactionUtils;
import org.apache.iotdb.db.engine.compaction.log.CompactionLogAnalyzer;
import org.apache.iotdb.db.engine.compaction.log.CompactionLogger;
import org.apache.iotdb.db.engine.compaction.log.TsFileIdentifier;
import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.modification.ModificationFile;
import org.apache.iotdb.db.engine.storagegroup.TsFileManager;
import org.apache.iotdb.db.engine.storagegroup.TsFileNameGenerator;
import org.apache.iotdb.db.engine.storagegroup.TsFileResource;
import org.apache.iotdb.db.utils.FileLoaderUtils;
import org.apache.iotdb.tsfile.common.constant.TsFileConstant;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.utils.TsFileUtils;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** CompactionRecoverTask executes the recover process for all compaction tasks. */
public class CompactionRecoverTask {
  private final Logger LOGGER = LoggerFactory.getLogger(IoTDBConstant.COMPACTION_LOGGER_NAME);
  private final File compactionLogFile;
  private final boolean isInnerSpace;
  private final String fullStorageGroupName;
  private final TsFileManager tsFileManager;

  public CompactionRecoverTask(
      String logicalStorageGroupName,
      String virtualStorageGroupName,
      TsFileManager tsFileManager,
      File logFile,
      boolean isInnerSpace) {
    this.compactionLogFile = logFile;
    this.isInnerSpace = isInnerSpace;
    this.fullStorageGroupName = logicalStorageGroupName + "-" + virtualStorageGroupName;
    this.tsFileManager = tsFileManager;
  }

  public void doCompaction() {
    boolean recoverSuccess = true;
    LOGGER.info(
        "{} [Compaction][Recover] compaction log is {}", fullStorageGroupName, compactionLogFile);
    try {
      if (compactionLogFile.exists()) {
        LOGGER.info(
            "{} [Compaction][Recover] compaction log file {} exists, start to recover it",
            fullStorageGroupName,
            compactionLogFile);
        CompactionLogAnalyzer logAnalyzer = new CompactionLogAnalyzer(compactionLogFile);
        CompactionRecoverFromOld compactionRecoverFromOld = new CompactionRecoverFromOld();
        if (isInnerSpace && compactionRecoverFromOld.isInnerCompactionLogBefore013()) {
          // inner compaction log from previous version (<0.13)
          logAnalyzer.analyzeOldInnerCompactionLog();
        } else if (!isInnerSpace && compactionRecoverFromOld.isCrossCompactionLogBefore013()) {
          // cross compaction log from previous version (<0.13)
          logAnalyzer.analyzeOldCrossCompactionLog();
        } else {
          logAnalyzer.analyze();
        }
        List<TsFileIdentifier> sourceFileIdentifiers = logAnalyzer.getSourceFileInfos();
        List<TsFileIdentifier> targetFileIdentifiers = logAnalyzer.getTargetFileInfos();

        // compaction log file is incomplete
        if (targetFileIdentifiers.isEmpty() || sourceFileIdentifiers.isEmpty()) {
          LOGGER.info(
              "{} [Compaction][Recover] incomplete log file, abort recover", fullStorageGroupName);
          return;
        }

        // check is all source files existed
        boolean isAllSourcesFileExisted = true;
        for (TsFileIdentifier sourceFileIdentifier : sourceFileIdentifiers) {
          File sourceFile = sourceFileIdentifier.getFileFromDataDirs();
          if (sourceFile == null) {
            isAllSourcesFileExisted = false;
            break;
          }
        }

        if (isAllSourcesFileExisted) {
          if (!isInnerSpace && logAnalyzer.isLogFromOld()) {
            recoverSuccess =
                compactionRecoverFromOld.handleCrossCompactionWithAllSourceFilesExistBefore013(
                    targetFileIdentifiers);
          } else {
            recoverSuccess =
                handleWithAllSourceFilesExist(targetFileIdentifiers, sourceFileIdentifiers);
          }
        } else {
          if (!isInnerSpace && logAnalyzer.isLogFromOld()) {
            recoverSuccess =
                compactionRecoverFromOld.handleCrossCompactionWithSomeSourceFilesLostBefore013(
                    targetFileIdentifiers, sourceFileIdentifiers);
          } else {
            recoverSuccess =
                handleWithSomeSourceFilesLost(targetFileIdentifiers, sourceFileIdentifiers);
          }
        }
      }
    } catch (IOException e) {
      LOGGER.error("Recover compaction error", e);
    } finally {
      if (!recoverSuccess) {
        LOGGER.error(
            "{} [Compaction][Recover] Failed to recover compaction, set allowCompaction to false",
            fullStorageGroupName);
        tsFileManager.setAllowCompaction(false);
      } else {
        if (compactionLogFile.exists()) {
          try {
            LOGGER.info(
                "{} [Compaction][Recover] Recover compaction successfully, delete log file {}",
                fullStorageGroupName,
                compactionLogFile);
            FileUtils.delete(compactionLogFile);
          } catch (IOException e) {
            LOGGER.error(
                "{} [Compaction][Recover] Exception occurs while deleting log file {}, set allowCompaction to false",
                fullStorageGroupName,
                compactionLogFile,
                e);
            tsFileManager.setAllowCompaction(false);
          }
        }
      }
    }
  }

  /**
   * All source files exist: (1) delete all the target files and tmp target files (2) delete
   * compaction mods files.
   */
  private boolean handleWithAllSourceFilesExist(
      List<TsFileIdentifier> targetFileIdentifiers, List<TsFileIdentifier> sourceFileIdentifiers) {
    LOGGER.info(
        "{} [Compaction][Recover] all source files exists, delete all target files.",
        fullStorageGroupName);

    // remove tmp target files and target files
    for (TsFileIdentifier targetFileIdentifier : targetFileIdentifiers) {
      // xxx.inner or xxx.cross
      File tmpTargetFile = targetFileIdentifier.getFileFromDataDirs();
      // xxx.tsfile
      File targetFile =
          getFileFromDataDirs(
              targetFileIdentifier
                  .getFilePath()
                  .replace(
                      isInnerSpace
                          ? IoTDBConstant.INNER_COMPACTION_TMP_FILE_SUFFIX
                          : IoTDBConstant.CROSS_COMPACTION_TMP_FILE_SUFFIX,
                      TsFileConstant.TSFILE_SUFFIX));
      TsFileResource targetResource = null;
      if (tmpTargetFile != null) {
        targetResource = new TsFileResource(tmpTargetFile);
      } else if (targetFile != null) {
        targetResource = new TsFileResource(targetFile);
      }

      if (targetResource != null && !targetResource.remove()) {
        // failed to remove tmp target tsfile
        // system should not carry out the subsequent compaction in case of data redundant
        LOGGER.error(
            "{} [Compaction][Recover] failed to remove target file {}",
            fullStorageGroupName,
            targetResource);
        return false;
      }
    }

    // delete compaction mods files
    List<TsFileResource> sourceTsFileResourceList = new ArrayList<>();
    for (TsFileIdentifier sourceFileIdentifier : sourceFileIdentifiers) {
      sourceTsFileResourceList.add(new TsFileResource(sourceFileIdentifier.getFileFromDataDirs()));
    }
    try {
      CompactionUtils.deleteCompactionModsFile(sourceTsFileResourceList, Collections.emptyList());
    } catch (Throwable e) {
      LOGGER.error(
          "{} [Compaction][Recover] Exception occurs while deleting compaction mods file, set allowCompaction to false",
          fullStorageGroupName,
          e);
      return false;
    }
    return true;
  }

  /**
   * Some source files lost: delete remaining source files, including: tsfile, resource file, mods
   * file and compaction mods file.
   */
  private boolean handleWithSomeSourceFilesLost(
      List<TsFileIdentifier> targetFileIdentifiers, List<TsFileIdentifier> sourceFileIdentifiers)
      throws IOException {
    // some source files have been deleted, while target file must exist and complete.
    if (!checkIsTargetFilesComplete(targetFileIdentifiers)) {
      return false;
    }

    boolean handleSuccess = true;
    for (TsFileIdentifier sourceFileIdentifier : sourceFileIdentifiers) {
      File sourceFile = sourceFileIdentifier.getFileFromDataDirs();
      if (sourceFile != null) {
        // delete source tsfile, resource file and mods file
        if (!new TsFileResource(sourceFile).remove()) {
          LOGGER.error(
              "{} [Compaction][Recover] fail to delete remaining source file {}.",
              fullStorageGroupName,
              sourceFile);
          handleSuccess = false;
        }
      } else {
        // if source file does not exist, its resource file may still exist, so delete it.
        File resourceFile =
            getFileFromDataDirs(
                sourceFileIdentifier.getFilePath() + TsFileResource.RESOURCE_SUFFIX);

        if (!checkAndDeleteFile(resourceFile)) {
          handleSuccess = false;
        }

        // delete .mods file of source tsfile
        File modFile =
            getFileFromDataDirs(sourceFileIdentifier.getFilePath() + ModificationFile.FILE_SUFFIX);
        if (!checkAndDeleteFile(modFile)) {
          handleSuccess = false;
        }
      }

      // delete .compaction.mods file of all source files
      File compactionModFile =
          getFileFromDataDirs(
              sourceFileIdentifier.getFilePath() + ModificationFile.COMPACTION_FILE_SUFFIX);
      if (!checkAndDeleteFile(compactionModFile)) {
        handleSuccess = false;
      }
    }
    return handleSuccess;
  }

  /**
   * This method find the File object of given filePath by searching it in every data directory. If
   * the file is not found, it will return null.
   */
  private File getFileFromDataDirs(String filePath) {
    String[] dataDirs = IoTDBDescriptor.getInstance().getConfig().getDataDirs();
    for (String dataDir : dataDirs) {
      File f = new File(dataDir, filePath);
      if (f.exists()) {
        return f;
      }
    }
    return null;
  }

  private boolean checkIsTargetFilesComplete(List<TsFileIdentifier> targetFileIdentifiers)
      throws IOException {
    for (TsFileIdentifier targetFileIdentifier : targetFileIdentifiers) {
      // xxx.tsfile
      File targetFile =
          getFileFromDataDirs(
              targetFileIdentifier
                  .getFilePath()
                  .replace(
                      isInnerSpace
                          ? IoTDBConstant.INNER_COMPACTION_TMP_FILE_SUFFIX
                          : IoTDBConstant.CROSS_COMPACTION_TMP_FILE_SUFFIX,
                      TsFileConstant.TSFILE_SUFFIX));
      if (targetFile == null
          || !TsFileUtils.isTsFileComplete(new TsFileResource(targetFile).getTsFile())) {
        LOGGER.error(
            "{} [Compaction][ExceptionHandler] target file {} is not complete, and some source files is lost, do nothing. Set allowCompaction to false",
            fullStorageGroupName,
            targetFileIdentifier.getFilePath());
        IoTDBDescriptor.getInstance().getConfig().setReadOnly(true);
        return false;
      }
    }
    return true;
  }

  /**
   * Return true if the file is not existed or if the file is existed and has been deleted
   * correctly. Otherwise, return false.
   */
  private boolean checkAndDeleteFile(File file) {
    if ((file != null && file.exists()) && !file.delete()) {
      LOGGER.error("{} [Compaction][Recover] failed to remove file {}", fullStorageGroupName, file);
      return false;
    }
    return true;
  }

  /**
   * Used to check whether it is recoverd from last version (<0.13) and perform corresponding
   * process.
   */
  private class CompactionRecoverFromOld {

    /** Return whether cross compaction log file is from previous version (<0.13). */
    private boolean isCrossCompactionLogBefore013() {
      return compactionLogFile
          .getName()
          .equals(CompactionLogger.CROSS_COMPACTION_LOG_NAME_FROM_OLD);
    }

    /** Return whether inner compaction log file is from previous version (<0.13). */
    private boolean isInnerCompactionLogBefore013() {
      return compactionLogFile.getName().startsWith(tsFileManager.getStorageGroupName());
    }

    /** Delete tmp target file and compaction mods file. */
    private boolean handleCrossCompactionWithAllSourceFilesExistBefore013(
        List<TsFileIdentifier> targetFileIdentifiers) {
      // delete tmp target file
      for (TsFileIdentifier targetFileIdentifier : targetFileIdentifiers) {
        // xxx.tsfile.merge
        File tmpTargetFile = targetFileIdentifier.getFileFromDataDirs();
        if (tmpTargetFile != null) {
          tmpTargetFile.delete();
        }
      }

      // delete compaction mods file
      File compactionModsFileFromOld =
          new File(
              tsFileManager.getStorageGroupDir()
                  + File.separator
                  + IoTDBConstant.COMPACTION_MODIFICATION_FILE_NAME_FROM_OLD);
      return checkAndDeleteFile(compactionModsFileFromOld);
    }

    /**
     * 1. If target file does not exist, then move .merge file to target file <br>
     * 2. If target resource file does not exist, then serialize it. <br>
     * 3. Append merging modification to target mods file and delete merging mods file. <br>
     * 4. Delete source files and .merge file. <br>
     */
    private boolean handleCrossCompactionWithSomeSourceFilesLostBefore013(
        List<TsFileIdentifier> targetFileIdentifiers,
        List<TsFileIdentifier> sourceFileIdentifiers) {
      try {
        File compactionModsFileFromOld =
            new File(
                tsFileManager.getStorageGroupDir()
                    + File.separator
                    + IoTDBConstant.COMPACTION_MODIFICATION_FILE_NAME_FROM_OLD);
        List<TsFileResource> targetFileResources = new ArrayList<>();
        for (int i = 0; i < sourceFileIdentifiers.size(); i++) {
          TsFileIdentifier sourceFileIdentifier = sourceFileIdentifiers.get(i);
          if (sourceFileIdentifier.isSequence()) {
            File tmpTargetFile = targetFileIdentifiers.get(i).getFileFromDataDirs();
            File targetFile;
            File targetIndexFile;

            // move tmp target file to target file if not exist
            if (tmpTargetFile != null) {
              // move tmp target file to target file
              String sourceFilePath =
                  tmpTargetFile
                      .getPath()
                      .replace(
                          TsFileConstant.TSFILE_SUFFIX
                              + IoTDBConstant.CROSS_COMPACTION_TMP_FILE_SUFFIX_FROM_OLD,
                          TsFileConstant.TSFILE_SUFFIX);
              targetFile = TsFileNameGenerator.increaseCrossCompactionCnt(new File(sourceFilePath));
              FSFactoryProducer.getFSFactory().moveFile(tmpTargetFile, targetFile);

              String tmpIndexFilePath =
                  tmpTargetFile
                      .getPath()
                      .replace(
                          TsFileConstant.TSFILE_SUFFIX,
                          TsFileConstant.TSFILE_SUFFIX + TsFileConstant.INDEX_SUFFIX);
              String indexFilePath =
                  targetFile
                      .getPath()
                      .replace(
                          TsFileConstant.TSFILE_SUFFIX,
                          TsFileConstant.TSFILE_SUFFIX + TsFileConstant.INDEX_SUFFIX);
              File tmpIndexFile = FSFactoryProducer.getFSFactory().getFile(tmpIndexFilePath);
              targetIndexFile = new File(indexFilePath);
              FSFactoryProducer.getFSFactory().moveFile(tmpIndexFile, targetIndexFile);
            } else {
              // target file must exist
              File file =
                  TsFileNameGenerator.increaseCrossCompactionCnt(
                      new File(
                          targetFileIdentifiers
                              .get(i)
                              .getFilePath()
                              .replace(
                                  TsFileConstant.TSFILE_SUFFIX
                                      + IoTDBConstant.CROSS_COMPACTION_TMP_FILE_SUFFIX_FROM_OLD,
                                  TsFileConstant.TSFILE_SUFFIX)));

              targetFile = getFileFromDataDirs(file.getPath());
              targetIndexFile =
                  FSFactoryProducer.getFSFactory()
                      .getFile(targetFile + TsFileConstant.INDEX_SUFFIX);
            }
            if (targetFile == null || targetIndexFile == null) {
              LOGGER.error(
                  "{} [Compaction][Recover] target file of source seq file {} does not exist (<0.13).",
                  fullStorageGroupName,
                  sourceFileIdentifier.getFilePath());
              return false;
            }

            // serialize target resource file if not exist
            TsFileResource targetResource = new TsFileResource(targetFile);
            if (!targetResource.resourceFileExists()) {
              try (TsFileSequenceReader reader =
                  new TsFileSequenceReader(targetFile.getAbsolutePath())) {
                FileLoaderUtils.updateTsFileResource(reader, targetResource);
              }
              targetResource.serialize();
            }

            targetFileResources.add(targetResource);

            // append compaction modifications to target mods file and delete compaction mods file
            if (compactionModsFileFromOld.exists()) {
              ModificationFile compactionModsFile =
                  new ModificationFile(compactionModsFileFromOld.getPath());
              appendCompactionModificationsBefore013(targetResource, compactionModsFile);
            }

            // delete tmp target file
            if (!checkAndDeleteFile(tmpTargetFile)) {
              return false;
            }
          }

          // delete source tsfile
          File sourceFile = sourceFileIdentifier.getFileFromDataDirs();
          if (!checkAndDeleteFile(sourceFile)) {
            return false;
          }

          // delete source resource file
          sourceFile =
              getFileFromDataDirs(
                  sourceFileIdentifier.getFilePath() + TsFileResource.RESOURCE_SUFFIX);
          if (!checkAndDeleteFile(sourceFile)) {
            return false;
          }

          // delete source mods file
          sourceFile =
              getFileFromDataDirs(
                  sourceFileIdentifier.getFilePath() + ModificationFile.FILE_SUFFIX);
          if (!checkAndDeleteFile(sourceFile)) {
            return false;
          }
        }

        // delete compaction mods file
        if (!checkAndDeleteFile(compactionModsFileFromOld)) {
          return false;
        }
      } catch (Throwable e) {
        LOGGER.error(
            "{} [Compaction][Recover] fail to handle with some source files lost from old version.",
            fullStorageGroupName,
            e);
        return false;
      }

      return true;
    }

    private void appendCompactionModificationsBefore013(
        TsFileResource resource, ModificationFile compactionModsFile) throws IOException {
      if (compactionModsFile != null) {
        for (Modification modification : compactionModsFile.getModifications()) {
          // we have to set modification offset to MAX_VALUE, as the offset of source chunk may
          // change after compaction
          modification.setFileOffset(Long.MAX_VALUE);
          resource.getModFile().write(modification);
        }
        resource.getModFile().close();
      }
    }
  }
}
